package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.Instant
import java.util.UUID

/** Explicit repair only: a single empty, unresolved, completed turn, with original input/provenance. */
@RunWith(AndroidJUnit4::class)
class UnresolvedWorkoutOwnerRecoveryTest {
    @Test fun refreshWithExplicitTodaySelection() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("recover_today_selection") == "swim_rest")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val date = LocalDate.now()
        ProgressRuntime.mutex.withLock {
            JournalStore(context).use { store ->
                val previous = requireNotNull(store.latestCoachCard(date))
                val data = JSONObject(previous.contextJson)
                val policy = context.assets.open("coaching-policy.md").bufferedReader().use { it.readText() }
                val fresh = store.readyCoachingContext(date, policy).toJson()
                for (key in fresh.keys()) data.put(key, fresh.get(key))
                data.remove("previous_advice")
                data.remove("request_manifest")
                data.put("request_at", Instant.now().toString())
                    .put("coaching_trigger", "owner_requested_training_correction")
                    .put("current_user_request", "今天选择游泳或休息，请先说明今天的安排。这是当天选择，不是已完成训练，不要继续安排昨日腿部训练。")
                    .put("telemetry_status", "retained_capture")
                data.optJSONObject("telemetry")?.put("capture_mode", "retained_statistics")
                val prompt = context.assets.open("coach-system.txt").bufferedReader().use { it.readText() }
                val packaged = CoachingRequestPackager.prepare(data, prompt, onSize = { CoachingRequestSizeStore.record(context, it) })
                val advice = QwenClient.response(QwenClient.post(packaged.request, requireNotNull(QwenCredentials(context).read())))
                check(Regex("游泳|休息").containsMatchIn(advice.training.first())) { "Today's selection not addressed; no card saved" }
                store.saveCoachCard(SavedCoachCard(UUID.randomUUID().toString(), date, Instant.now(), QWEN_MODEL,
                    COACH_PROMPT_VERSION, packaged.retainedContext.toString(), advice))
                ProgressRuntime.changed()
            }
        }
    }

    @Test fun recoverOriginalUnresolvedReport() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("recover_unresolved_workout") == "true")
        val date = LocalDate.parse(requireNotNull(args.getString("recovery_date")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ProgressRuntime.mutex.withLock {
            JournalStore(context).use { store ->
                val db = store.writableDatabase
                val original = db.rawQuery("SELECT submission_id,result_json FROM daily_workout_state WHERE date=?", arrayOf(date.toString())).use {
                    check(it.moveToFirst()); it.getString(0) to it.getString(1)
                }
                val state = WorkoutDayState.decode(JSONObject(original.second))
                check(state.records.isEmpty() && state.unresolved.isNotEmpty())
                val submission = requireNotNull(store.progressSubmission(original.first))
                val rowId = db.rawQuery("SELECT rowid FROM progress_submissions WHERE id=?", arrayOf(original.first)).use { check(it.moveToFirst()); it.getLong(0) }
                check(db.rawQuery("SELECT count(*) FROM workout_turns WHERE date=?", arrayOf(date.toString())).use { it.moveToFirst(); it.getInt(0) } == 1)
                check(db.rawQuery("SELECT status FROM workout_turns WHERE submission_id=?", arrayOf(original.first)).use { it.moveToFirst(); it.getString(0) } == "complete")
                check(db.rawQuery("SELECT count(*) FROM exercise_logs WHERE date=?", arrayOf(date.toString())).use { it.moveToFirst(); it.getInt(0) } == 0)
                val turn = PendingWorkoutTurn(original.first, date, rowId, submission.getString("text"), original.first, WorkoutDayState(), "pending")
                val baselines = store.readCoachingContext(date, "").exercises
                val prompt = context.assets.open("workout-delta.txt").bufferedReader().use { it.readText() }
                val candidate = WorkoutDeltaExtractor.extract(turn, baselines, prompt, requireNotNull(QwenCredentials(context).read()))
                check(candidate.records.isNotEmpty() && candidate.unresolved.isEmpty()) { "Recovery remains ambiguous; no data changed" }
                check(state.unresolved.all { question -> candidate.records.any {
                    it.observation.evidence.contains(question.evidence) || question.evidence.contains(it.observation.evidence)
                } }) { "Recovery omitted original evidence; no data changed" }
                db.beginTransaction()
                try {
                    check(db.rawQuery("SELECT result_json FROM daily_workout_state WHERE date=?", arrayOf(date.toString())).use { it.moveToFirst(); it.getString(0) } == original.second)
                    check(requireNotNull(store.progressSubmission(original.first)).toString() == submission.toString())
                    db.execSQL("UPDATE workout_turns SET status='pending' WHERE submission_id=? AND status='complete'", arrayOf(original.first))
                    check(IncrementalWorkoutStore(db).apply(turn, candidate))
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                assertEquals(candidate.records.count { it.observation.status in setOf("completed", "partial") }, store.latestWorkoutRecords(date).count { it.date == date })
                assertTrue(store.workoutStatuses(date, date).single().getJSONArray("unresolved").length() == 0)
                ProgressRuntime.changed()
            }
        }
    }
}
