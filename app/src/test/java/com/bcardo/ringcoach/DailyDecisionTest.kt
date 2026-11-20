package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DailyDecisionTest {
    private val day = LocalDate.parse("2026-06-12")
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-06-12T12:00:00Z")
    private fun card(date: LocalDate = day) = SavedCoachCard("synthetic", date, now, "synthetic", "synthetic", "{}",
        CoachAdvice(listOf("Keep saved plan", "Full detail"), listOf("Partial intake"), listOf("Focus", "Reason one", "Reason two", "More detail")))
    private fun session() = JSONObject().put("wake_date", day.toString()).put("start", "2026-06-11T23:00:00Z").put("end", "2026-06-12T06:00:00Z")
        .put("metrics", JSONObject().put("heart_rate", JSONObject().put("unit", "bpm").put("mean", 60).put("sample_count", 100))
            .put("heart_rate_summary", JSONObject().put("unit", "bpm").put("mean", 80).put("sample_count", 10))
            .put("hrv_rmssd", JSONObject().put("mean", 999).put("sample_count", 5).put("unit", "protocol_units_pending_reference_validation")))
    private fun snapshot(vararg sessions: JSONObject) = JSONObject().put("request_date", day.toString()).put("timezone", zone.id).put("sessions", JSONArray(sessions.toList()))

    @Test fun onlyRequestedDayCanSupplyAdviceEvenWhenBackdatedCardWasGeneratedLater() {
        assertNull(dailyDecision(day, DailyCoaching(day.minusDays(1), card(day.minusDays(1)), null, "idle", null)).card)
        assertNull(dailyDecision(day, DailyCoaching(day, card(day.minusDays(1)), null, "idle", null)).card)
        val decision = dailyDecision(day, DailyCoaching(day, card(), null, "idle", null))
        assertEquals("Focus", decision.focus); assertEquals(listOf("Reason one", "Reason two"), decision.reasons)
        assertEquals(4, decision.card!!.advice.notes.size)
    }

    @Test fun pendingQuestionRetainsAdviceButExplicitlyMarksItAsNotIncludingNewInput() {
        val decision = dailyDecision(day, DailyCoaching(day, card(), PendingCoaching("synthetic-question", day), "blocked", "context_too_large"))
        assertTrue(decision.awaitingLatest); assertEquals("Keep saved plan", decision.action)
        assertNull(dailyDecision(day, null).card)
        assertTrue(dailyDecision(day, DailyCoaching(day, null, PendingCoaching("synthetic", day), "coaching", null)).awaitingLatest)
    }

    @Test fun overlappingSessionsStaySeparateAndPrimaryHeartRateIsNeverAveragedWithSummary() {
        val records = nightRecords(snapshot(session(), session()), day, zone, now)
        assertEquals(2, records.size); assertEquals(60.0, records[0].heartRateMean!!, 0.0)
        assertEquals(100, records[0].heartRateCount); assertEquals(5, records[0].hrvWindows)
    }

    @Test fun missingOrUnvalidatedMeansNeverBecomeZeroOrRestingHeartRate() {
        val row = session(); row.getJSONObject("metrics").remove("heart_rate_summary")
        row.getJSONObject("metrics").getJSONObject("heart_rate").put("unit", "unknown")
        val record = nightRecords(snapshot(row), day, zone, now).single()
        assertNull(record.heartRateMean); assertEquals(0, record.heartRateCount)
        assertEquals(5, record.hrvWindows) // HRV numeric value/units are deliberately not a presentation field.
    }

    @Test fun oldFutureMalformedAndWrongTimezoneEvidenceCannotAppearAsLastNight() {
        assertTrue(nightRecords(snapshot(session()).put("request_date", "2026-06-11"), day, zone, now).isEmpty())
        assertTrue(nightRecords(snapshot(session()).put("timezone", "America/New_York"), day, zone, now).isEmpty())
        val old = session().put("wake_date", "2026-06-11")
        val future = session().put("end", "2026-06-12T15:00:00Z")
        val malformed = session().put("start", "invalid")
        val zeroSpan = session().put("start", "2026-06-12T06:00:00Z")
        assertTrue(nightRecords(snapshot(old, future, malformed, zeroSpan), day, zone, now).isEmpty())
    }

    @Test fun invalidValuesDoNotHideValidSeparateFallbackOrProduceNumbers() {
        val row = session(); row.getJSONObject("metrics").getJSONObject("heart_rate").put("mean", 300)
        assertEquals(80.0, nightRecords(snapshot(row), day, zone, now).single().heartRateMean!!, 0.0)
        row.getJSONObject("metrics").getJSONObject("heart_rate_summary").put("sample_count", 0)
        assertNull(nightRecords(snapshot(row), day, zone, now).single().heartRateMean)
    }
}
