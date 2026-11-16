package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Disposable stores and a fake model only; no Bluetooth or owner data accessed. */
@RunWith(AndroidJUnit4::class)
class RingBluetoothIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val date = LocalDate.of(2026, 1, 5)
    private val at = Instant.parse("2026-01-05T10:00:00Z")
    private val start = Instant.parse("2026-01-05T01:00:00Z").toEpochMilli()
    private val end = Instant.parse("2026-01-05T09:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("UTC")
    private fun transfer() = RingTransfer(listOf(RingPoint("sleep_session", start, end, 480.0),
        RingPoint("hrv_rmssd", start, start + 300000, 61.25, 70.5),
        RingPoint("heart_rate", start, start + 59000, 66.0)), setOf(16, 150, 10))
    private fun isolated(block: (String, String) -> Unit) {
        val ring = "ring-test-${UUID.randomUUID()}.db"; val journal = "ring-journal-${UUID.randomUUID()}.db"
        try { block(ring, journal) } finally { context.deleteDatabase(ring); context.deleteDatabase(journal) }
    }
    @Test fun persistsDeduplicatesSeparatesDevicesAndDoesNotQueueBackfill() = isolated { name, _ ->
        RingBluetoothStore(context, name).use { store ->
            store.setEnabled(true)
            assertEquals(setOf(date), store.ingest(transfer(), "private-device", at, zone))
            assertTrue(store.ingest(transfer(), "private-device", at, zone).isEmpty())
            store.ingest(RingTransfer(listOf(RingPoint("hrv_rmssd", start, start + 300000, 999.0, 999.0)), setOf(150)), "other-device", at, zone)
        }
        RingBluetoothStore(context, name).use { store ->
            val snapshot = store.snapshot(date, zone)
            assertEquals(1, snapshot.getInt("target_date_session_count"))
            val hrv = snapshot.getJSONArray("sessions").getJSONObject(0).getJSONObject("metrics").getJSONObject("hrv_rmssd")
            assertEquals(1, hrv.getInt("sample_count")); assertEquals(61.25, hrv.getDouble("mean"), 0.0)
            assertFalse(snapshot.toString().contains("private-device")); assertFalse(snapshot.toString().contains("other-device"))
            assertFalse(snapshot.toString().contains("\"samples\"")); assertFalse(snapshot.toString().contains("\"rmssd\":61.25"))
            assertEquals(0, store.snapshot(date.minusDays(1), zone).getInt("target_date_session_count"))
            store.morningStatus(date, "complete")
            assertTrue(store.ingest(transfer(), "private-device", at, zone).isEmpty())
            assertNull(store.nextMorning(date))
        }
    }
    @Test fun rejectsFutureTransferAndRetainsUnknownRatherThanZero() = isolated { name, _ ->
        RingBluetoothStore(context, name).use { store ->
            assertThrows(IllegalArgumentException::class.java) { store.ingest(transfer(), "source", at.minusSeconds(86400), zone) }
            store.ingest(RingTransfer(listOf(RingPoint("sleep_session", start, end, 480.0)), setOf(16)), "source", at, zone)
            assertEquals(0, store.snapshot(date, zone).getJSONArray("sessions").getJSONObject(0).getJSONObject("metrics").length())
            assertNull(store.nextMorning(date))
        }
    }
    @Test fun importedResearchNeverQueuesAndSeparateLiveHrvCanCompleteSleep() = isolated { name, _ ->
        RingBluetoothStore(context, name).use { store ->
            store.setEnabled(true)
            store.ingest(transfer(), "research", at, zone, "research_capture_import")
            assertNull(store.nextMorning(date))
            assertTrue(store.snapshot(date, zone).getJSONArray("sessions").getJSONObject(0).getJSONArray("capture_origins").toString().contains("research_capture_import"))
            val parts = transfer().points
            assertTrue(store.ingest(RingTransfer(listOf(parts[0]), setOf(16)), "live", at, zone).isEmpty())
            assertEquals(setOf(date), store.ingest(RingTransfer(listOf(parts[1]), setOf(150)), "live", at, zone))
            store.ingest(RingTransfer(emptyList(), emptySet()), "live", at.plusSeconds(40L * 86400), zone)
            assertEquals(0, store.snapshot(date, zone).getInt("target_date_session_count"))
        }
    }
    @Test fun morningAdviceUsesRingContextOnceWithoutInventingProgressOrLiveCall() = isolated { ring, journal -> runBlocking {
        RingBluetoothStore(context, ring).use { it.setEnabled(true); it.ingest(transfer(), "source", at, zone) }
        var calls = 0
        val processor = RingMorningProcessor(context, ring, journal, { "fake-key" }) { request, _ ->
            calls++
            val content = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
            assertEquals("new_ring_sleep_transfer", content.getString("coaching_trigger"))
            assertFalse(content.has("progress_submission")); assertTrue(content.has("ring_bluetooth"))
            assertEquals(1, content.getJSONObject("request_manifest").getJSONObject("ring_bluetooth").getInt("target_date_hrv_window_count"))
            val advice = CoachAdvice(listOf("Synthetic training"), listOf("Synthetic diet"), listOf("Experimental Ring reference pending"))
            JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("content", advice.toJson().toString())))).toString()
        }
        assertFalse(processor.run(date)); assertFalse(processor.run(date)); assertEquals(1, calls)
        JournalStore(context, journal).use { store ->
            assertNotNull(store.coachCard("ring-morning-$date")); assertTrue(store.readAll().isEmpty())
        }
    } }
    @Test fun networkRetryIsBoundedAndInterruptedMorningCanResume() = isolated { ring, journal -> runBlocking {
        RingBluetoothStore(context, ring).use { it.setEnabled(true); it.ingest(transfer(), "source", at, zone); it.morningStatus(date, "coaching") }
        var calls = 0
        val processor = RingMorningProcessor(context, ring, journal, { "fake-key" }) { _, _ -> calls++; throw IOException("synthetic offline") }
        repeat(3) { assertEquals(it < 2, processor.run(date)) }
        assertFalse(processor.run(date)); assertEquals(3, calls)
        RingBluetoothStore(context, ring).use { it.retryMorning(date); assertEquals(date, it.nextMorning(date)) }
    } }
}
