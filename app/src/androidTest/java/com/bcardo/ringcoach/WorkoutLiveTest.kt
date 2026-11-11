package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/** Explicit opt-in only: one provider request using synthetic training and the phone's saved key. */
@RunWith(AndroidJUnit4::class)
class WorkoutLiveTest {
    @Test
    fun recognizesSyntheticDailyInputAndPersistsLatestPerformance() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_workout_parse") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "workout-live-" + UUID.randomUUID() + ".db"
        val date = LocalDate.now()
        val training = "今天 Day 1，第2个动作：单边20 lbs，完成8、8、7。明天计划单边21 lbs。"
        val store = JournalStore(context, name)
        try {
            store.save(JournalDraft(date, training))
            val job = store.nextPendingWorkout()!!
            val baseline = ExerciseBaseline("Day 1 (推)", "上斜杠铃推胸", "Reference only", date, 2)
            val baselines = listOf(
                ExerciseBaseline("Day 1 (推)", "杠铃平板卧推", "Reference only", date, 1),
                baseline,
                ExerciseBaseline("Day 2 (拉)", "单臂器械划船", "Reference only", date, 2),
            )
            val prompt = context.assets.open("workout-extraction.txt").bufferedReader().use { it.readText() }
            val key = QwenCredentials(context).read() ?: error("Saved key required")
            val response = QwenClient.post(WorkoutExtractor.request(job, baselines, prompt), key)
            val extracted = WorkoutExtractor.decode(QwenClient.responseText(response), training, baselines.map { it.exercise }.toSet())
            assertTrue(extracted.records.all { it.exercise == baseline.exercise })
            assertTrue(extracted.records.any { it.status == "completed" && it.performance.contains("20") })
            assertTrue(extracted.records.none { it.status == "completed" && it.performance.contains("21") })
            assertTrue(store.applyWorkoutExtraction(job, extracted))
            assertEquals(extracted.records.filter { it.status in setOf("completed", "partial") }.size,
                store.latestWorkoutRecords(date).size)
            assertEquals(null, store.nextPendingWorkout())
            assertEquals(training, store.readAll().single().draft.training)
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }
}
