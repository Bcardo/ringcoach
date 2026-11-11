package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class JournalStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "journal-test-" + UUID.randomUUID() + ".db"
    private lateinit var store: JournalStore

    @Before
    fun openDatabase() {
        store = JournalStore(context, databaseName)
    }

    @After
    fun removeTestDatabase() {
        store.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun editingOneDayPersistsOneEntryAfterReopening() {
        val date = LocalDate.of(2025, 1, 2)
        store.save(JournalDraft(date, "测试日志"))
        val originalCreatedAt = store.readableDatabase.rawQuery(
            "SELECT created_at FROM journal_entries WHERE date = ?", arrayOf(date.toString()),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }
        val edited = JournalDraft(date, "  测试 O'Brien，卧推记录  ", "午餐记录", "精力充足")
        store.save(edited)
        store.close()
        store = JournalStore(context, databaseName)

        val records = store.readAll()
        assertEquals(1, records.size)
        assertEquals(edited.trimmed(), records.single().draft)
        val preservedCreatedAt = store.readableDatabase.rawQuery(
            "SELECT created_at FROM journal_entries WHERE date = ?", arrayOf(date.toString()),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }
        assertEquals(originalCreatedAt, preservedCreatedAt)
    }

    @Test
    fun historyOrdersDaysAndRejectsEmptyEntry() {
        val earlier = JournalDraft(LocalDate.of(2025, 1, 1), additional = "正常")
        val later = JournalDraft(LocalDate.of(2025, 1, 3), training = "休息")
        store.save(earlier)
        store.save(later)
        try {
            store.save(JournalDraft(LocalDate.of(2025, 1, 2), training = "  "))
            throw AssertionError("Empty journal entry was accepted")
        } catch (_: IllegalArgumentException) {
            assertTrue(store.readAll().all { it.draft.hasContent })
        }
        assertEquals(listOf(later, earlier), store.readAll().map { it.draft })
    }

    @Test
    fun previousJournalLayoutMigratesWithoutLosingText() {
        store.close()
        val timestamp = "2025-01-02T12:00:00Z"
        context.openOrCreateDatabase(databaseName, 0, null).use { old ->
            old.execSQL("""
                CREATE TABLE journal_entries (
                    date TEXT PRIMARY KEY NOT NULL,
                    notes TEXT NOT NULL,
                    readiness TEXT NOT NULL,
                    discomfort TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
            """.trimIndent())
            old.execSQL(
                "INSERT INTO journal_entries VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf("2025-01-02", "原始日志", "正常", "肩部发紧", timestamp, timestamp),
            )
            old.version = 1
        }
        store = JournalStore(context, databaseName)
        val migrated = store.readAll().single()
        assertEquals(
            JournalDraft(LocalDate.of(2025, 1, 2), additional = "原始日志\n晨起体感：正常\n不适记录：肩部发紧"),
            migrated.draft,
        )
        assertEquals(timestamp, migrated.updatedAt.toString())
        val edited = migrated.draft.copy(training = "新训练", diet = "新饮食")
        store.save(edited)
        store.close()
        store = JournalStore(context, databaseName)
        assertEquals(edited, store.readAll().single().draft)
    }

    @Test
    fun settingsPersistIndependentlyOfJournalEntries() {
        val journal = JournalDraft(LocalDate.of(2025, 1, 2), training = "原始训练")
        store.save(journal)
        assertEquals(CoachingSettings(instructions = "默认指令"), store.readSettings("默认指令"))
        val first = CoachingSettings("每周三练", "基础饮食", "目标 RPE 7.5")
        store.saveSettings(first)
        val edited = first.copy(routine = "  每周四练  ", instructions = "目标 RPE 7.0")
        store.saveSettings(edited)
        store.writableDatabase.execSQL("DROP TABLE nutrition_references")
        store.writableDatabase.execSQL("DROP TABLE exercise_baselines")
        store.writableDatabase.execSQL("DROP TABLE coach_cards")
        removeVersionSixTables()
        store.writableDatabase.version = 3
        store.close()
        store = JournalStore(context, databaseName)
        assertEquals(edited.trimmed(), store.readSettings("后续默认指令"))
        assertEquals(journal, store.readAll().single().draft)
        try {
            store.saveSettings(edited.copy(instructions = "  "))
            throw AssertionError("Empty coaching instructions were accepted")
        } catch (_: IllegalArgumentException) {
            assertEquals(edited.trimmed(), store.readSettings("默认指令"))
        }
    }

    @Test
    fun journalVersionTwoUpgradesWithoutChangingEntries() {
        store.close()
        val timestamp = "2025-01-02T12:00:00Z"
        context.openOrCreateDatabase(databaseName, 0, null).use { old ->
            old.execSQL("""
                CREATE TABLE journal_entries (
                    date TEXT PRIMARY KEY NOT NULL,
                    training TEXT NOT NULL,
                    diet TEXT NOT NULL,
                    additional TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
            """.trimIndent())
            old.execSQL(
                "INSERT INTO journal_entries VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf("2025-01-02", "训练记录", "饮食记录", "其他记录", timestamp, timestamp),
            )
            old.version = 2
        }
        store = JournalStore(context, databaseName)
        val expected = JournalDraft(LocalDate.of(2025, 1, 2), "训练记录", "饮食记录", "其他记录")
        assertEquals(expected, store.readAll().single().draft)
        store.saveSettings(CoachingSettings("基础训练", "基础饮食", "自定义指令"))
        assertEquals(expected, store.readAll().single().draft)
        assertEquals("自定义指令", store.readSettings("默认指令").instructions)
    }

    @Test
    fun coachingContextReadsSevenDaysAndKeepsBaselinesSeparate() {
        val today = LocalDate.of(2025, 1, 10)
        store.save(JournalDraft(today.minusDays(7), training = "窗口外"))
        store.save(JournalDraft(today.minusDays(6), training = "窗口内"))
        store.save(JournalDraft(today.plusDays(1), training = "未来日期"))
        val settings = CoachingSettings("基础训练", "基础饮食", "教练偏好")
        val nutrition = listOf(
            NutritionReference(today.minusDays(7), 70.0, 120.0, 180.0, 50.0, 1700.0, 1900.0, -200.0, today),
            NutritionReference(today, 70.0, 120.0, 180.0, 50.0, 1700.0, 1900.0, -200.0, today),
        )
        val exercise = ExerciseBaseline("推", "测试动作", "下轮计划", today)
        val imported = SettingsImport(settings, nutrition, listOf(exercise), "")
        store.saveImportedSettings(settings, imported)
        store.saveImportedSettings(settings, imported)
        val future = exercise.copy(snapshotDate = today.plusDays(1), referenceNotes = "未来基准")
        store.saveImportedSettings(settings, SettingsImport(settings, emptyList(), listOf(future), ""))

        val context = store.readCoachingContext(today, "默认指令")
        assertEquals(settings, context.settings)
        assertEquals(listOf(today.minusDays(6)), context.journals.map { it.draft.date })
        assertEquals(listOf(today), context.nutrition.map { it.date })
        assertEquals(listOf(exercise), context.exercises)
        assertEquals("baseline_reference",
            context.toJson().getJSONArray("exercise_baselines").getJSONObject(0).getString("record_kind"))
        assertEquals("not_loaded", context.toJson().getString("telemetry_status"))
    }

    @Test
    fun importFailureRollsBackSettingsAndReferenceRecords() {
        val date = LocalDate.of(2025, 1, 10)
        val original = CoachingSettings(instructions = "原始偏好")
        store.saveSettings(original)
        store.writableDatabase.execSQL("""
            CREATE TRIGGER reject_reference BEFORE INSERT ON exercise_baselines
            BEGIN SELECT RAISE(ABORT, 'test failure'); END
        """.trimIndent())
        val edited = original.copy(instructions = "修改偏好")
        val nutrition = NutritionReference(date, 70.0, 120.0, 180.0, 50.0, 1700.0, 1900.0, -200.0, date)
        val imported = SettingsImport(edited, listOf(nutrition), listOf(ExerciseBaseline("推", "测试", "计划", date)), "")
        try {
            store.saveImportedSettings(edited, imported)
            throw AssertionError("Expected reference import failure")
        } catch (_: android.database.sqlite.SQLiteException) {
            assertEquals(original, store.readSettings("默认"))
            assertTrue(store.readCoachingContext(date, "默认").nutrition.isEmpty())
        }
    }

    @Test
    fun versionFourUpgradePreservesInputsAndArchivesCardsIdempotently() {
        val date = LocalDate.of(2025, 1, 10)
        val journal = JournalDraft(date, training = "已确认训练")
        val settings = CoachingSettings(instructions = "原始偏好")
        store.save(journal)
        store.saveSettings(settings)
        store.writableDatabase.execSQL("DROP TABLE coach_cards")
        removeVersionSixTables()
        store.writableDatabase.version = 4
        store.close()
        store = JournalStore(context, databaseName)
        assertEquals(journal, store.readAll().single().draft)
        assertEquals(settings, store.readSettings("默认"))
        assertEquals(null, store.latestCoachCard())
        val card = SavedCoachCard(
            "test-card", date, java.time.Instant.parse("2025-01-10T12:00:00Z"), QWEN_MODEL,
            COACH_PROMPT_VERSION, "{\"request_date\":\"2025-01-10\"}",
            CoachAdvice(listOf("训练建议"), listOf("饮食建议"), listOf("缺少 HRV")),
        )
        store.saveCoachCard(card)
        store.saveCoachCard(card)
        store.close()
        store = JournalStore(context, databaseName)
        assertEquals(card, store.latestCoachCard())
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM coach_cards", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
    }
    private fun removeVersionSixTables() {
        removeExerciseNumbers()
        store.writableDatabase.execSQL("DROP TABLE exercise_logs")
        store.writableDatabase.execSQL("DROP TABLE workout_extractions")
        store.writableDatabase.execSQL("ALTER TABLE journal_entries DROP COLUMN training_revision")
    }

    private fun removeExerciseNumbers() {
        store.writableDatabase.execSQL("DROP TABLE meal_revisions")
        store.writableDatabase.execSQL("DROP TABLE photo_meals")
        store.writableDatabase.execSQL("DROP TABLE nutrition_reconciliation")
        store.writableDatabase.execSQL("DROP TABLE IF EXISTS nutrition_extractions")
        val exists = store.writableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'exercise_baselines'", null,
        ).use { it.moveToFirst() }
        if (exists) store.writableDatabase.execSQL("ALTER TABLE exercise_baselines DROP COLUMN exercise_number")
    }

    @Test
    fun versionSixRecoversSourceOrderAndPreservesItAfterRestart() {
        val date = LocalDate.of(2025, 1, 10)
        val settings = CoachingSettings(instructions = "Keep original order")
        val rows = listOf(
            ExerciseBaseline("Day 1", "Z first", "Reference", date, 1),
            ExerciseBaseline("Day 1", "A second", "Reference", date, 2),
            ExerciseBaseline("Day 2", "Y first", "Reference", date, 1),
        )
        store.saveImportedSettings(settings, SettingsImport(settings, emptyList(), rows, ""))
        removeExerciseNumbers()
        store.writableDatabase.version = 6
        store.close()
        store = JournalStore(context, databaseName)
        assertEquals(rows, store.readCoachingContext(date, "").exercises)
        val json = store.readCoachingContext(date, "").toJson().getJSONArray("exercise_baselines")
        assertEquals(2, json.getJSONObject(1).getInt("exercise_number"))
        store.close()
        store = JournalStore(context, databaseName)
        assertEquals(rows, store.readCoachingContext(date, "").exercises)
    }

    @Test
    fun trainingEditsInvalidateOldResultsButDietEditsPreserveThem() {
        val date = LocalDate.of(2025, 1, 10)
        val draft = JournalDraft(date, training = "Bench 20 lb x8")
        store.save(draft)
        val original = store.nextPendingWorkout()!!
        val result = WorkoutExtraction(listOf(
            WorkoutObservation("Bench", "20 lb x8", draft.training, "completed"),
        ), emptyList())
        assertTrue(store.applyWorkoutExtraction(original, result))
        store.save(draft.copy(diet = "Lunch"))
        assertEquals(null, store.nextPendingWorkout())
        assertEquals("20 lb x8", store.latestWorkoutRecords(date).single().observation.performance)

        store.save(draft.copy(training = "Bench 25 lb x6"))
        assertTrue(store.latestWorkoutRecords(date).isEmpty())
        assertTrue(!store.applyWorkoutExtraction(original, result))
        val updated = store.nextPendingWorkout()!!
        assertTrue(original.revision != updated.revision)
        store.failWorkoutExtraction(original)
        assertEquals(updated, store.nextPendingWorkout())
        store.save(draft.copy(training = "", diet = "Lunch"))
        assertTrue(!store.applyWorkoutExtraction(updated, result))
        assertEquals(null, store.nextPendingWorkout())
    }

    @Test
    fun skippedWorkDoesNotReplaceLastCompletedPerformance() {
        val date = LocalDate.of(2025, 1, 10)
        store.save(JournalDraft(date.minusDays(1), training = "Bench 20 lb x8"))
        val first = store.nextPendingWorkout()!!
        store.applyWorkoutExtraction(first, WorkoutExtraction(listOf(
            WorkoutObservation("Bench", "20 lb x8", first.training, "completed"),
        ), emptyList()))
        store.save(JournalDraft(date, training = "Bench skipped"))
        val second = store.nextPendingWorkout()!!
        store.applyWorkoutExtraction(second, WorkoutExtraction(listOf(
            WorkoutObservation("Bench", "skipped", second.training, "skipped"),
        ), emptyList()))
        assertEquals(date.minusDays(1), store.latestWorkoutRecords(date).single().date)
    }

    @Test
    fun versionFiveMigrationPreservesDataAndQueuesTraining() {
        val date = LocalDate.of(2025, 1, 10)
        val draft = JournalDraft(date, training = "Bench 20 lb x8")
        val settings = CoachingSettings(instructions = "Original preferences")
        val baseline = ExerciseBaseline("Push", "Bench", "Reference only", date)
        store.save(draft)
        store.saveImportedSettings(settings, SettingsImport(settings, emptyList(), listOf(baseline), ""))
        val card = SavedCoachCard("migration-card", date, java.time.Instant.ofEpochMilli(System.currentTimeMillis()), QWEN_MODEL,
            COACH_PROMPT_VERSION, "{}", CoachAdvice(listOf("Train"), listOf("Eat"), listOf("Note")))
        store.saveCoachCard(card)
        removeVersionSixTables()
        store.writableDatabase.version = 5
        store.close()
        store = JournalStore(context, databaseName)
        assertEquals(draft, store.readAll().single().draft)
        assertEquals(settings, store.readSettings(""))
        assertEquals(listOf(baseline), store.readCoachingContext(date, "").exercises)
        assertEquals(card, store.latestCoachCard())
        assertEquals(draft.training, store.nextPendingWorkout()!!.training)
    }
}
