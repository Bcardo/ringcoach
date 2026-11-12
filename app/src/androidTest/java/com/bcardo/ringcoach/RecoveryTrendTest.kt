package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecoveryTrendTest {
    @Test fun sparseCorrectedSourceSwitchSeriesHasExactDatesAndNoGapOrSourceBridges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "trend-${UUID.randomUUID()}.db"
        val end = LocalDate.of(2026, 1, 15)
        val start = end.minusDays(29)
        val now = Instant.parse("2026-01-15T12:00:00Z")
        fun score(date: LocalDate, value: Double, source: String = "samsung_health_sdk") =
            NightlyMetric(date, "energy_score", value, "score", source, "daily")
        try {
            NightlyRecoveryStore(context, name).use { store ->
                store.put(score(start, 80.25), now)
                store.put(score(start, 80.0, "samsung_health_screen"), now)
                store.put(score(start.plusDays(1), 81.0, "samsung_health_screen"), now)
                store.put(score(start.plusDays(2), 82.0), now)
                store.put(score(end, 83.0), now)
                store.put(score(end, 85.0), now.plusSeconds(60))
                store.put(NightlyMetric(end, "sleeping_hrv", 61.0, "ms", "samsung_health_screen", "daily"), now)
                store.status("samsung_health_screen", "capture_incomplete:test")
                val trend = RecoveryTrend.decode(store.history(start, end))
                assertEquals(30, trend.days.size)
                assertEquals(start, trend.days.first())
                assertEquals(end, trend.days.last())
                val energy = trend.series.getValue("energy_score")
                assertEquals(4, energy.count { it.value != null })
                assertEquals(80.25, energy.first().value!!, 0.0)
                assertEquals(2, energy.first().observations.size)
                assertEquals(85.0, energy.last().value!!, 0.0)
                assertFalse(energy[0].connectsTo(energy[1]))
                assertFalse(energy[1].connectsTo(energy[2]))
                assertFalse(energy[2].connectsTo(energy.last()))
                assertNull(energy[3].value)
                assertEquals("not_checked", energy[3].status)
                assertEquals(1, trend.series.getValue("sleeping_hrv").count { it.value != null })
                assertEquals(1, trend.syncWarnings.size)
                assertEquals("85", energy.last().displayValue)
                assertEquals(7, RecoveryTrend.decode(store.history(end.minusDays(6), end)).days.size)
                val adjacent = energy.last().copy(date = end.plusDays(1))
                assertTrue(energy.last().connectsTo(adjacent))
                assertFalse(energy.last().connectsTo(adjacent.copy(definition = "different")))
            }
        } finally { context.deleteDatabase(name) }
    }
}
