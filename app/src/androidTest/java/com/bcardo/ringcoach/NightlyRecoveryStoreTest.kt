package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NightlyRecoveryStoreTest {
    @Test fun revisesDailyScoreWithoutDuplicatingOrMixingHrv() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "nightly-test-${UUID.randomUUID()}.db"
        val today = LocalDate.of(2026, 1, 5)
        try {
            NightlyRecoveryStore(context, name).use { store ->
                val score = NightlyMetric(today, "energy_score", 81.0, "score", "samsung_health_sdk", "score-id")
                store.put(NightlyMetric(today, "sleeping_hrv", 60.0, "ms", "samsung_health_screen", "daily"))
                store.put(NightlyMetric(today.plusDays(1), "energy_score", 99.0, "score", "samsung_health_sdk", "future"))
                store.replaceEnergy(today.minusDays(6), today, listOf(score))
                store.replaceEnergy(today.minusDays(6), today, listOf(score.copy(value = 82.0)))
                val rows = store.snapshot(today).getJSONArray("records")
                assertEquals(2, rows.length())
                assertEquals(82.0, (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("metric") == "energy_score" }.getDouble("value"), 0.001)
                store.replaceEnergy(today.minusDays(6), today, emptyList())
                assertEquals("sleeping_hrv", store.snapshot(today).getJSONArray("records").getJSONObject(0).getString("metric"))
                assertEquals(1, store.snapshot(today).getJSONArray("records").length())
                store.replaceScreenDay(today, emptyList())
                assertEquals(0, store.snapshot(today).getJSONArray("records").length())
                store.replaceScreenDay(today, listOf(NightlyMetric(today, "sleeping_hrv", 61.0, "ms", "samsung_health_screen", "daily")))
            }
            NightlyRecoveryStore(context, name).use { store -> assertEquals(1, store.snapshot(today).getJSONArray("records").length()) }
        } finally { context.deleteDatabase(name) }
    }
}
