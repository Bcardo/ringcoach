package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Synthetic disposable data; no owner records, permissions or model calls. */
@RunWith(AndroidJUnit4::class)
class DailyCoachingStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val today = LocalDate.of(2026, 10, 9)
    private fun isolated(block: (JournalStore) -> Unit) {
        val name = "daily-coaching-${UUID.randomUUID()}.db"
        try { JournalStore(context, name).use(block) } finally { context.deleteDatabase(name) }
    }
    private fun card(id: String, date: LocalDate, seconds: Long) = SavedCoachCard(id, date,
        Instant.ofEpochSecond(seconds), "test", "test", "{}",
        CoachAdvice(listOf("当天训练"), listOf("当天饮食"), listOf("当天回复")))

    @Test fun newerBackdatedResponseDoesNotReplaceCurrentDayAdvice() = isolated { store ->
        store.saveCoachCard(card("today", today, 1))
        store.saveCoachCard(card("backdated", today.minusDays(3), 2))
        assertEquals("backdated", store.latestCoachCard()!!.id)
        assertEquals("today", store.dailyCoaching(today).card!!.id)
        assertNull(store.dailyCoaching(today.plusDays(1)).card)
    }

    @Test fun historicalFailureDoesNotLabelTodaysSuccessfulResponse() = isolated { store ->
        val old = today.minusDays(3)
        store.appendProgress("old-question", old, "测试问题", emptyList(), true)
        ProgressWorkStore(store.writableDatabase).stage("blocked", old, "old-question", "invalid_response")
        store.saveCoachCard(card("today", today, 2))
        val shown = store.dailyCoaching(today)
        assertNull(shown.pending); assertFalse(shown.needsRetry); assertEquals("idle", shown.stage)
        assertTrue(store.dailyCoaching(old).needsRetry)
        assertNotNull(store.progressSubmission("old-question"))
    }

    @Test fun failedNewCurrentUpdateRetainsPriorAdviceAndRetry() = isolated { store ->
        store.saveCoachCard(card("earlier", today, 1))
        store.appendProgress("new-question", today, "测试新问题", emptyList(), true)
        ProgressWorkStore(store.writableDatabase).stage("blocked", today, "new-question", "extraction_incomplete")
        val shown = store.dailyCoaching(today)
        assertEquals("earlier", shown.card!!.id); assertEquals("new-question", shown.pending!!.id)
        assertTrue(shown.needsRetry); assertFalse(shown.updating)
        assertNotNull(store.progressSubmission("new-question"))
        store.saveCoachCard(card("new-question", today, 3))
        val complete = store.dailyCoaching(today)
        assertEquals("new-question", complete.card!!.id)
        assertNull(complete.pending); assertFalse(complete.needsRetry)
    }

    @Test fun anotherDatesRunningWorkDoesNotAppearAsCurrentUpdate() = isolated { store ->
        store.appendProgress("current-question", today, "测试新问题", emptyList(), true)
        ProgressWorkStore(store.writableDatabase).stage("coaching", today.minusDays(1), "other")
        val shown = store.dailyCoaching(today)
        assertNotNull(shown.pending); assertFalse(shown.updating); assertFalse(shown.needsRetry)
        assertEquals("pending", shown.stage)
    }

    @Test fun retryLeavesOtherDatesAndOriginalSubmissionsUntouched() = isolated { store ->
        val old = today.minusDays(3)
        store.appendProgress("old", old, "测试旧问题", emptyList(), true)
        store.appendProgress("new", today, "测试新问题", emptyList(), true)
        val db = store.writableDatabase
        db.execSQL("UPDATE workout_extractions SET status='failed'")
        db.execSQL("UPDATE nutrition_turns SET status='failed'")
        val work = ProgressWorkStore(db)
        work.failure("coach:old", "invalid_response"); work.failure("coach:new", "invalid_response")
        store.retryDailyCoaching(today)
        assertEquals("invalid_response", work.error("coach:old")); assertNull(work.error("coach:new"))
        db.rawQuery("SELECT date,status FROM workout_extractions", null).use { rows ->
            while (rows.moveToNext()) assertEquals(if (rows.getString(0) == today.toString()) "pending" else "failed", rows.getString(1))
        }
        assertNotNull(store.progressSubmission("old")); assertNotNull(store.progressSubmission("new"))
    }

    @Test fun dailyFeedbackCanReadWhileAnotherConnectionHoldsAReservedWriteLock() = isolated { store ->
        store.saveCoachCard(card("today", today, 1))
        JournalStore(context, store.databaseName).use { writer ->
            writer.writableDatabase.beginTransactionNonExclusive()
            try { assertEquals("today", store.dailyCoaching(today).card!!.id) }
            finally { writer.writableDatabase.endTransaction() }
        }
    }
}
