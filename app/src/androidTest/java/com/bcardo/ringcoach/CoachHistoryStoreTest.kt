package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CoachHistoryStoreTest {
    @Test
    fun pagesEveryGenerationWithTiedTimesAndLoadsOriginalSnapshots() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "coach-history-" + UUID.randomUUID() + ".db"
        val store = JournalStore(context, name)
        try {
            assertTrue(store.coachCardSummaries().isEmpty())
            assertEquals(null, store.coachCard("missing"))
            val date = LocalDate.of(2025, 1, 10)
            val generated = (0 until 45).map { index ->
                SavedCoachCard("card-" + index.toString().padStart(2, '0'), date,
                    Instant.ofEpochMilli(1000L + index / 3), "model-$index", "prompt-$index",
                    "{\"generation\":$index}",
                    CoachAdvice(listOf("Train $index"), listOf("Diet $index"), listOf("Missing HRV")))
            }
            generated.forEach(store::saveCoachCard)
            // A concurrent newer insertion between pages must not shift the next page.
            val first = store.coachCardSummaries(limit = 20)
            store.saveCoachCard(generated.last().copy(id = "newer", createdAt = Instant.ofEpochMilli(99999)))
            val second = store.coachCardSummaries(first.last(), 20)
            val third = store.coachCardSummaries(second.last(), 20)
            val paged = first + second + third
            assertEquals(generated.reversed().map { it.id }, paged.map { it.id })
            assertEquals(45, paged.map { it.id }.distinct().size)
            assertTrue(store.coachCardSummaries(third.last()).isEmpty())
            assertEquals(generated[12], store.coachCard(generated[12].id))
            assertEquals("newer", store.latestCoachCard()!!.id)
            store.saveCoachCard(generated.last().copy(id = "another-day", date = date.plusDays(1), createdAt = Instant.ofEpochMilli(100000)))
            assertEquals("another-day", store.latestCoachCard()!!.id)
            assertEquals("newer", store.latestCoachCard(date)!!.id)
            assertEquals("another-day", store.coachCardSummaries().first().id)
            assertEquals(46, store.coachCardSummariesForDate(date).size)
            assertEquals("newer", store.coachCardSummariesForDate(date).first().id)
            assertTrue(store.coachCardSummariesForDate(date.minusDays(1)).isEmpty())
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }
}
