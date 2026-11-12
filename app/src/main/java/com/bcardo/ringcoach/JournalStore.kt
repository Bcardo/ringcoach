package com.bcardo.ringcoach

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class JournalDraft(
    val date: LocalDate,
    val training: String = "",
    val diet: String = "",
    val additional: String = "",
) {
    val hasContent: Boolean
        get() = training.isNotBlank() || diet.isNotBlank() || additional.isNotBlank()

    fun trimmed() = copy(training = training.trim(), diet = diet.trim(), additional = additional.trim())
}

internal data class JournalEntry(val draft: JournalDraft, val updatedAt: Instant)

internal data class CoachingSettings(
    val routine: String = "",
    val diet: String = "",
    val instructions: String,
) {
    fun trimmed() = copy(routine = routine.trim(), diet = diet.trim(), instructions = instructions.trim())
}

internal class JournalStore(context: Context, name: String = "ringcoach.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 11) {

    override fun onCreate(db: SQLiteDatabase) {
        createJournalTable(db)
        createSettingsTable(db)
        createReferenceTables(db)
        createCoachTable(db)
        createWorkoutTables(db)
        createNutritionTable(db)
        PhotoMealStore.createTables(db)
        createProgressTable(db)
        IncrementalNutritionStore.createTables(db)
    }

    private fun createProgressTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE progress_submissions (
            id TEXT PRIMARY KEY NOT NULL, date TEXT NOT NULL, text TEXT NOT NULL,
            photo_ids TEXT NOT NULL, created_at TEXT NOT NULL)""")
        db.execSQL("ALTER TABLE nutrition_extractions ADD COLUMN unified INTEGER NOT NULL DEFAULT 0")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    private fun createNutritionTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE nutrition_extractions (
                date TEXT PRIMARY KEY NOT NULL REFERENCES journal_entries(date) ON DELETE CASCADE,
                source_revision TEXT NOT NULL,
                source_diet TEXT NOT NULL,
                source_additional TEXT NOT NULL,
                status TEXT NOT NULL,
                result_json TEXT
            )
        """.trimIndent())
    }

    private fun createWorkoutTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE workout_extractions (
                date TEXT PRIMARY KEY NOT NULL REFERENCES journal_entries(date) ON DELETE CASCADE,
                source_revision TEXT NOT NULL,
                status TEXT NOT NULL,
                unresolved_json TEXT NOT NULL DEFAULT '[]'
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE exercise_logs (
                date TEXT NOT NULL REFERENCES journal_entries(date) ON DELETE CASCADE,
                source_revision TEXT NOT NULL,
                ordinal INTEGER NOT NULL,
                exercise TEXT NOT NULL,
                performance TEXT NOT NULL,
                evidence TEXT NOT NULL,
                status TEXT NOT NULL,
                PRIMARY KEY (date, ordinal)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX exercise_log_lookup ON exercise_logs(exercise, date)")
    }

    private fun createCoachTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE coach_cards (
                id TEXT PRIMARY KEY NOT NULL,
                date TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                model TEXT NOT NULL,
                prompt_version TEXT NOT NULL,
                context_json TEXT NOT NULL,
                advice_json TEXT NOT NULL
            )
        """.trimIndent())
    }

    private fun createReferenceTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE nutrition_references (
                date TEXT NOT NULL,
                snapshot_date TEXT NOT NULL,
                weight_kg REAL NOT NULL,
                protein_grams REAL NOT NULL,
                carbohydrate_grams REAL NOT NULL,
                fat_grams REAL NOT NULL,
                intake_kcal REAL NOT NULL,
                expenditure_kcal REAL NOT NULL,
                reported_balance_kcal REAL NOT NULL,
                PRIMARY KEY (date, snapshot_date)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE exercise_baselines (
                snapshot_date TEXT NOT NULL,
                training_day TEXT NOT NULL,
                exercise TEXT NOT NULL,
                reference_notes TEXT NOT NULL,
                exercise_number INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY (snapshot_date, training_day, exercise)
            )
        """.trimIndent())
    }

    private fun createJournalTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE journal_entries (
                date TEXT PRIMARY KEY NOT NULL,
                training TEXT NOT NULL,
                diet TEXT NOT NULL,
                additional TEXT NOT NULL,
                training_revision TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
        """.trimIndent())
    }

    private fun createSettingsTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE coaching_settings (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                routine TEXT NOT NULL,
                diet TEXT NOT NULL,
                instructions TEXT NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..10 && newVersion == 11) { "Unsupported database migration" }
        // SQLiteOpenHelper runs the migration in a transaction.
        if (oldVersion == 1) {
            db.execSQL("ALTER TABLE journal_entries RENAME TO journal_entries_v1")
            createJournalTable(db)
            db.execSQL("""
                INSERT INTO journal_entries (date, training, diet, additional, created_at, updated_at)
                SELECT date, '', '',
                    TRIM(notes
                        || CASE WHEN TRIM(readiness) != '' THEN CHAR(10) || '晨起体感：' || readiness ELSE '' END
                        || CASE WHEN TRIM(discomfort) != '' THEN CHAR(10) || '不适记录：' || discomfort ELSE '' END),
                    created_at, updated_at
                FROM journal_entries_v1
            """.trimIndent())
            db.execSQL("DROP TABLE journal_entries_v1")
        }
        if (oldVersion < 3) createSettingsTable(db)
        if (oldVersion < 4) createReferenceTables(db)
        if (oldVersion < 5) createCoachTable(db)
        if (oldVersion in 2..5) {
            db.execSQL("ALTER TABLE journal_entries ADD COLUMN training_revision TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 6) {
            db.execSQL("UPDATE journal_entries SET training_revision = updated_at")
            createWorkoutTables(db)
            db.execSQL("""
            INSERT INTO workout_extractions (date, source_revision, status)
            SELECT date, training_revision, 'pending' FROM journal_entries WHERE TRIM(training) != ''
        """.trimIndent())
        }
        if (oldVersion in 4..6) {
            db.execSQL("ALTER TABLE exercise_baselines ADD COLUMN exercise_number INTEGER NOT NULL DEFAULT 1")
            // Earlier imports inserted rows in source-document order. Recover that order per day.
            db.execSQL("""
                UPDATE exercise_baselines AS e SET exercise_number = (
                    SELECT COUNT(*) FROM exercise_baselines AS x
                    WHERE x.snapshot_date = e.snapshot_date AND x.training_day = e.training_day
                        AND x.rowid <= e.rowid
                )
            """.trimIndent())
        }
        if (oldVersion < 8) {
        createNutritionTable(db)
        db.execSQL("""
            INSERT INTO nutrition_extractions (date, source_revision, source_diet, source_additional, status)
            SELECT date, updated_at, diet, additional, 'pending' FROM journal_entries
            WHERE TRIM(diet) != '' OR TRIM(additional) != ''
        """.trimIndent())
        }
        if (oldVersion < 9) PhotoMealStore.createTables(db)
        if (oldVersion < 10) createProgressTable(db)
        IncrementalNutritionStore.createTables(db)
        IncrementalNutritionStore(db).migrateConfirmedPhotos()
    }

    @Synchronized
    fun readSettings(defaultInstructions: String): CoachingSettings {
        return readableDatabase.rawQuery(
            "SELECT routine, diet, instructions FROM coaching_settings WHERE id = 1",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                CoachingSettings(cursor.getString(0), cursor.getString(1), cursor.getString(2))
            } else {
                CoachingSettings(instructions = defaultInstructions)
            }
        }
    }

    @Synchronized
    fun saveSettings(settings: CoachingSettings): CoachingSettings {
        val clean = settings.trimmed()
        require(clean.instructions.isNotBlank()) { "Coaching instructions are empty" }
        writableDatabase.execSQL("""
            INSERT INTO coaching_settings (id, routine, diet, instructions) VALUES (1, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                routine = excluded.routine,
                diet = excluded.diet,
                instructions = excluded.instructions
        """.trimIndent(), arrayOf(clean.routine, clean.diet, clean.instructions))
        return clean
    }

    @Synchronized
    fun save(draft: JournalDraft): JournalEntry {
        val clean = draft.trimmed()
        require(clean.hasContent || PhotoMealStore(readableDatabase).list(clean.date, clean.date).isNotEmpty()) { "Journal entry is empty" }
        val updated = Instant.now()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previous = db.rawQuery(
                "SELECT training, training_revision, diet, additional FROM journal_entries WHERE date = ?",
                arrayOf(clean.date.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) (0..3).map { cursor.getString(it) } else null
            }
            val changed = previous?.get(0) != clean.training
            val revision = if (changed) UUID.randomUUID().toString() else previous!![1]
            db.execSQL("""
            INSERT INTO journal_entries (date, training, diet, additional, created_at, updated_at, training_revision)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(date) DO UPDATE SET
                training = excluded.training,
                diet = excluded.diet,
                additional = excluded.additional,
                updated_at = excluded.updated_at,
                training_revision = excluded.training_revision
        """.trimIndent(), arrayOf(
            clean.date.toString(), clean.training, clean.diet, clean.additional,
            updated.toString(), updated.toString(), revision,
        ))
            if (changed) {
                db.delete("exercise_logs", "date = ?", arrayOf(clean.date.toString()))
                db.execSQL("""
                    INSERT INTO workout_extractions (date, source_revision, status) VALUES (?, ?, ?)
                    ON CONFLICT(date) DO UPDATE SET source_revision = excluded.source_revision,
                        status = excluded.status, unresolved_json = '[]'
                """.trimIndent(), arrayOf(clean.date.toString(), revision, if (clean.training.isBlank()) "complete" else "pending"))
            }
            if (previous?.get(2) != clean.diet || previous?.get(3) != clean.additional) {
                db.delete("nutrition_reconciliation", "date = ?", arrayOf(clean.date.toString()))
                if (clean.diet.isBlank() && clean.additional.isBlank()) {
                    db.delete("nutrition_extractions", "date = ?", arrayOf(clean.date.toString()))
                } else {
                    db.execSQL("""
                        INSERT INTO nutrition_extractions (date, source_revision, source_diet, source_additional, status)
                        VALUES (?, ?, ?, ?, 'pending') ON CONFLICT(date) DO UPDATE SET
                            source_revision = excluded.source_revision, source_diet = excluded.source_diet,
                            source_additional = excluded.source_additional, status = 'pending', result_json = NULL
                    """.trimIndent(), arrayOf(clean.date.toString(), UUID.randomUUID().toString(), clean.diet, clean.additional))
                }
            }
            db.setTransactionSuccessful()
            return JournalEntry(clean, updated)
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun saveDailySubmission(draft: JournalDraft, mealId: String?, notes: String, consumed: Boolean): JournalEntry {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (mealId != null) {
                val meals = PhotoMealStore(db)
                require(meals.get(mealId)?.date == draft.date)
                meals.edit(mealId, notes, consumed)
            }
            val saved = save(draft)
            db.setTransactionSuccessful()
            return saved
        } finally { db.endTransaction() }
    }

    /** Append immutable dated input and optionally queue an incremental food-state conversion. */
    @Synchronized
    fun appendProgress(id: String, date: LocalDate, text: String, photoIds: List<String>, incremental: Boolean = false): JournalEntry {
        require(text.isNotBlank() || photoIds.isNotEmpty())
        require(text.length <= 12000 && photoIds.distinct().size == photoIds.size)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery("SELECT date, text, photo_ids FROM progress_submissions WHERE id = ?", arrayOf(id)).use {
                if (it.moveToFirst()) listOf(it.getString(0), it.getString(1), it.getString(2)) else null
            }
            if (existing != null) {
                require(existing == listOf(date.toString(), text.trim(), JSONArray(photoIds).toString()))
                return readJournals("date = ?", arrayOf(date.toString())).single()
            }
            if (incremental) {
                val day = readCoachingContext(date, "").nutritionDays.first { it.date == date }
                if (day.result != null && day.status == "complete" && !day.photoConflict) {
                    val old = readJournals("date = ?", arrayOf(date.toString())).firstOrNull()?.draft ?: JournalDraft(date)
                    IncrementalNutritionStore(db).seed(date, day.result, old.diet, old.additional)
                }
            }
            val photos = PhotoMealStore(db)
            for (photoId in photoIds) {
                val meal = requireNotNull(photos.get(photoId))
                require(meal.date == date && !meal.consumed && meal.hasPhoto)
                photos.edit(photoId, text.take(4000), true)
            }
            val previous = readJournals("date = ?", arrayOf(date.toString())).firstOrNull()?.draft ?: JournalDraft(date)
            val stamp = Instant.now().toString()
            val event = "[更新 $stamp · $id]\n" + text.trim() + photoIds.joinToString("") { "\n[照片 $it]" }
            fun append(old: String) = listOf(old, event).filter { it.isNotBlank() }.joinToString("\n\n")
            val saved = save(previous.copy(training = append(previous.training), additional = append(previous.additional)))
            // Include pre-redesign meals on the first unified update, preserving their notes and estimates as context.
            val meals = photos.list(date, date).filter { it.consumed }
            val legacy = meals.filter { !saved.draft.additional.contains("[照片 ${it.id}]") }.joinToString("\n") {
                "[历史餐食 ${it.id}] ${it.notes}\n${it.estimate?.toJson() ?: ""}" +
                    if (it.hasPhoto) "\n[照片 ${it.id}]" else ""
            }
            val source = listOf(saved.draft.additional, legacy).filter { it.isNotBlank() }.joinToString("\n")
            db.execSQL("""INSERT INTO nutrition_extractions
                (date, source_revision, source_diet, source_additional, status, unified)
                VALUES (?, ?, ?, ?, 'pending', 1) ON CONFLICT(date) DO UPDATE SET
                source_revision = excluded.source_revision, source_diet = excluded.source_diet,
                source_additional = excluded.source_additional, status = 'pending', result_json = NULL, unified = 1
            """, arrayOf(date.toString(), id, saved.draft.diet, source))
            db.execSQL("INSERT INTO progress_submissions VALUES (?, ?, ?, ?, ?)",
                arrayOf(id, date.toString(), text.trim(), JSONArray(photoIds).toString(), stamp))
            if (incremental) IncrementalNutritionStore(db).enqueue(id, date, saved.draft.diet, source)
            db.setTransactionSuccessful()
            return saved
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun nutritionPhotos(job: PendingNutrition): List<Pair<String, ByteArray>> {
        if (job.turn != null) return IncrementalNutritionStore(readableDatabase).photos(job)
        if (!job.unified) return emptyList()
        val photos = PhotoMealStore(readableDatabase)
        return photos.list(job.date, job.date).filter {
            it.consumed && it.hasPhoto && job.source.contains("[照片 ${it.id}]")
        }.map { it.id to requireNotNull(photos.image(it.id)) }
    }

    @Synchronized
    fun incrementalSubmissionIds(date: LocalDate, completedOnly: Boolean = false): List<String> = readableDatabase.rawQuery(
        "SELECT t.submission_id FROM nutrition_turns t JOIN progress_submissions p ON p.id=t.submission_id " +
            "WHERE t.date BETWEEN ? AND ? " + (if (completedOnly) "AND t.status='complete' " else "") + "ORDER BY p.rowid",
        arrayOf(date.minusDays(6).toString(), date.toString()),
    ).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }

    @Synchronized
    fun hasUncoachedIncrementalProgress(date: LocalDate, latest: SavedCoachCard?): Boolean {
        val covered = latest?.let { JSONObject(it.contextJson).optJSONArray("nutrition_turn_ids") } ?: JSONArray()
        val ids = (0 until covered.length()).map { covered.getString(it) }.toSet()
        return incrementalSubmissionIds(date).any { it !in ids }
    }

    @Synchronized
    fun releaseConfirmedLegacyPhotos() {
        val db = writableDatabase
        db.beginTransaction()
        try { IncrementalNutritionStore(db).migrateConfirmedPhotos(); db.setTransactionSuccessful() }
        finally { db.endTransaction() }
    }

    @Synchronized
    fun readAll(): List<JournalEntry> {
        return readJournals()
    }

    private fun readJournals(selection: String? = null, args: Array<String>? = null): List<JournalEntry> {
        return readableDatabase.query(
            "journal_entries", arrayOf("date", "training", "diet", "additional", "updated_at"),
            selection, args, null, null, "date DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(JournalEntry(
                        JournalDraft(
                            LocalDate.parse(cursor.getString(0)),
                            cursor.getString(1), cursor.getString(2), cursor.getString(3),
                        ),
                        Instant.parse(cursor.getString(4)),
                    ))
                }
            }
        }
    }

    @Synchronized
    fun saveImportedSettings(settings: CoachingSettings, imported: SettingsImport): CoachingSettings {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val saved = saveSettings(settings)
            for (record in imported.nutrition) {
                db.execSQL("""
                    INSERT INTO nutrition_references VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(date, snapshot_date) DO UPDATE SET
                        weight_kg = excluded.weight_kg,
                        protein_grams = excluded.protein_grams,
                        carbohydrate_grams = excluded.carbohydrate_grams,
                        fat_grams = excluded.fat_grams,
                        intake_kcal = excluded.intake_kcal,
                        expenditure_kcal = excluded.expenditure_kcal,
                        reported_balance_kcal = excluded.reported_balance_kcal
                """.trimIndent(), arrayOf<Any>(
                    record.date.toString(), record.snapshotDate.toString(), record.weightKg,
                    record.proteinGrams, record.carbohydrateGrams, record.fatGrams,
                    record.intakeKcal, record.expenditureKcal, record.reportedBalanceKcal,
                ))
            }
            for (record in imported.exercises) {
                db.execSQL("""
                    INSERT INTO exercise_baselines
                        (snapshot_date, training_day, exercise, reference_notes, exercise_number) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(snapshot_date, training_day, exercise) DO UPDATE SET
                        reference_notes = excluded.reference_notes,
                        exercise_number = excluded.exercise_number
                """.trimIndent(), arrayOf<Any>(
                    record.snapshotDate.toString(), record.trainingDay, record.exercise, record.referenceNotes,
                    record.exerciseNumber,
                ))
            }
            db.setTransactionSuccessful()
            return saved
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun readCoachingContext(date: LocalDate, defaultInstructions: String): CoachingContext {
        val db = readableDatabase
        db.beginTransactionNonExclusive()
        try {
            val nutrition = db.rawQuery("""
                SELECT date, weight_kg, protein_grams, carbohydrate_grams, fat_grams,
                    intake_kcal, expenditure_kcal, reported_balance_kcal, snapshot_date
                FROM nutrition_references AS n
                WHERE date BETWEEN ? AND ? AND snapshot_date = (
                    SELECT MAX(snapshot_date) FROM nutrition_references
                    WHERE date = n.date AND snapshot_date <= ?
                )
                ORDER BY date
            """.trimIndent(), arrayOf(date.minusDays(6).toString(), date.toString(), date.toString())).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(NutritionReference(
                            LocalDate.parse(cursor.getString(0)), cursor.getDouble(1),
                            cursor.getDouble(2), cursor.getDouble(3), cursor.getDouble(4),
                            cursor.getDouble(5), cursor.getDouble(6), cursor.getDouble(7),
                            LocalDate.parse(cursor.getString(8)),
                        ))
                    }
                }
            }
            val exercises = db.rawQuery("""
                SELECT training_day, exercise, reference_notes, snapshot_date, exercise_number FROM exercise_baselines AS e
                WHERE snapshot_date = (SELECT MAX(snapshot_date) FROM exercise_baselines
                    WHERE training_day = e.training_day AND exercise = e.exercise AND snapshot_date <= ?)
                ORDER BY training_day, exercise_number, e.rowid
            """.trimIndent(), arrayOf(date.toString())).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(ExerciseBaseline(
                            cursor.getString(0), cursor.getString(1), cursor.getString(2),
                            LocalDate.parse(cursor.getString(3)), cursor.getInt(4),
                        ))
                    }
                }
            }
            val dailyNutrition = nutritionDays(date, nutrition)
            val context = CoachingContext(
                date, readSettings(defaultInstructions),
                readJournals("date BETWEEN ? AND ?", arrayOf(date.minusDays(6).toString(), date.toString())),
                nutrition.filter { ref -> dailyNutrition.none { it.date == ref.date && it.status != "reference" } },
                exercises, latestWorkoutRecords(date), workoutStatuses(date.minusDays(6), date), dailyNutrition,
            )
            db.setTransactionSuccessful()
            return context
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun nextPendingNutrition(): PendingNutrition? {
        IncrementalNutritionStore(readableDatabase).next()?.let { return it }
        return readableDatabase.rawQuery("""
            SELECT date, source_revision, source_diet, source_additional, unified FROM nutrition_extractions
            WHERE status = 'pending' AND NOT EXISTS (
                SELECT 1 FROM nutrition_turns t WHERE t.date = nutrition_extractions.date
            ) ORDER BY date LIMIT 1
        """.trimIndent(), null).use {
            if (!it.moveToFirst()) null else PendingNutrition(
                LocalDate.parse(it.getString(0)), it.getString(1), it.getString(2), it.getString(3), it.getInt(4) != 0,
            )
        }
    }

    @Synchronized
    fun applyNutritionExtraction(job: PendingNutrition, result: NutritionExtraction): Boolean {
        if (job.turn != null) return IncrementalNutritionStore(writableDatabase).apply(job, result)
        val values = android.content.ContentValues().apply {
            put("status", "complete")
            put("result_json", result.toJson().toString())
        }
        return writableDatabase.update("nutrition_extractions", values,
            "date = ? AND source_revision = ?", arrayOf(job.date.toString(), job.revision)) == 1
    }

    @Synchronized
    fun failNutritionExtraction(job: PendingNutrition) {
        if (job.turn != null) IncrementalNutritionStore(writableDatabase).fail(job)
        writableDatabase.execSQL("UPDATE nutrition_extractions SET status = 'failed' WHERE date = ? AND source_revision = ?",
            arrayOf(job.date.toString(), job.revision))
    }

    @Synchronized
    fun retryFailedNutrition() {
        IncrementalNutritionStore(writableDatabase).retryFailed()
        writableDatabase.execSQL("UPDATE nutrition_extractions SET status = 'pending' WHERE status = 'failed'")
    }

    private fun nutritionDays(date: LocalDate, references: List<NutritionReference>): List<NutritionDay> {
        val parsed = readableDatabase.rawQuery("""
            SELECT date, status, result_json, source_diet, source_additional, unified FROM nutrition_extractions
            WHERE date BETWEEN ? AND ? ORDER BY date
        """.trimIndent(), arrayOf(date.minusDays(6).toString(), date.toString())).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val day = LocalDate.parse(cursor.getString(0))
                    val result = if (cursor.isNull(2)) IncrementalNutritionStore(readableDatabase).confirmed(day) else
                        NutritionExtractor.decode(cursor.getString(2), nutritionSource(cursor.getString(3), cursor.getString(4)))
                    put(day, NutritionDay(day, cursor.getString(1), result,
                        photoMode = if (cursor.getInt(5) != 0) "unified" else null))
                }
            }
        }
        val photos = PhotoMealStore(readableDatabase)
        val meals = photos.list(date.minusDays(6), date).groupBy { it.date }
        return (0L..6L).map { offset ->
            val day = date.minusDays(offset)
            val base = parsed[day] ?: references.firstOrNull { it.date == day }?.let { NutritionDay(day, "reference", reference = it) }
                ?: NutritionDay(day, "missing")
            if (base.photoMode == "unified") base.copy(photoMeals = meals[day]?.filter { it.consumed } ?: emptyList())
            else MealNutrition.merge(base, meals[day] ?: emptyList(), photos.mode(day))
        }
    }

    @Synchronized
    fun retryFailedWorkouts() {
        writableDatabase.execSQL("UPDATE workout_extractions SET status = 'pending' WHERE status = 'failed'")
    }

    @Synchronized
    fun nextPendingWorkout(): PendingWorkout? {
        return readableDatabase.rawQuery("""
            SELECT j.date, j.training_revision, j.training FROM journal_entries AS j
            JOIN workout_extractions AS w ON w.date = j.date AND w.source_revision = j.training_revision
            WHERE w.status = 'pending' ORDER BY j.date LIMIT 1
        """.trimIndent(), null).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            PendingWorkout(LocalDate.parse(cursor.getString(0)), cursor.getString(1), cursor.getString(2))
        }
    }

    @Synchronized
    fun applyWorkoutExtraction(job: PendingWorkout, result: WorkoutExtraction): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = db.rawQuery(
                "SELECT training_revision FROM journal_entries WHERE date = ?",
                arrayOf(job.date.toString()),
            ).use { cursor -> cursor.moveToFirst() && cursor.getString(0) == job.revision }
            if (!current) return false
            db.delete("exercise_logs", "date = ?", arrayOf(job.date.toString()))
            for ((index, record) in result.records.withIndex()) {
                db.execSQL("INSERT INTO exercise_logs VALUES (?, ?, ?, ?, ?, ?, ?)", arrayOf<Any>(
                    job.date.toString(), job.revision, index, record.exercise,
                    record.performance, record.evidence, record.status,
                ))
            }
            db.execSQL("""
                UPDATE workout_extractions SET status = 'complete', unresolved_json = ?
                WHERE date = ? AND source_revision = ?
            """.trimIndent(), arrayOf(JSONArray(result.unresolved).toString(), job.date.toString(), job.revision))
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun failWorkoutExtraction(job: PendingWorkout) {
        writableDatabase.execSQL(
            "UPDATE workout_extractions SET status = 'failed' WHERE date = ? AND source_revision = ?",
            arrayOf(job.date.toString(), job.revision),
        )
    }

    @Synchronized
    fun latestWorkoutRecords(date: LocalDate): List<WorkoutRecord> {
        return readableDatabase.rawQuery("""
            SELECT e.date, e.exercise, e.performance, e.evidence, e.status FROM exercise_logs AS e
            JOIN journal_entries AS j ON j.date = e.date AND j.training_revision = e.source_revision
            WHERE e.status IN ('completed', 'partial') AND e.date = (
                SELECT MAX(x.date) FROM exercise_logs AS x
                JOIN journal_entries AS s ON s.date = x.date AND s.training_revision = x.source_revision
                WHERE x.exercise = e.exercise AND x.status IN ('completed', 'partial') AND x.date <= ?
            ) ORDER BY e.exercise, e.ordinal
        """.trimIndent(), arrayOf(date.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(WorkoutRecord(LocalDate.parse(cursor.getString(0)), WorkoutObservation(
                        cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4),
                    )))
                }
            }
        }
    }

    @Synchronized
    fun workoutStatuses(start: LocalDate, end: LocalDate): List<JSONObject> {
        return readableDatabase.rawQuery("""
            SELECT w.date, w.status, w.unresolved_json FROM workout_extractions AS w
            JOIN journal_entries AS j ON j.date = w.date AND j.training_revision = w.source_revision
            WHERE w.date BETWEEN ? AND ? ORDER BY w.date DESC
        """.trimIndent(), arrayOf(start.toString(), end.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(JSONObject().put("date", cursor.getString(0)).put("status", cursor.getString(1))
                        .put("unresolved", JSONArray(cursor.getString(2))))
                }
            }
        }
    }

    @Synchronized
    fun saveCoachCard(card: SavedCoachCard) {
        writableDatabase.execSQL("""
            INSERT INTO coach_cards (id, date, created_at, model, prompt_version, context_json, advice_json)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO NOTHING
        """.trimIndent(), arrayOf<Any>(
            card.id, card.date.toString(), card.createdAt.toEpochMilli(), card.model,
            card.promptVersion, card.contextJson, card.advice.toJson().toString(),
        ))
    }

    @Synchronized
    fun coachCardSummaries(before: CoachCardSummary? = null, limit: Int = 21): List<CoachCardSummary> {
        require(limit in 1..100)
        val selection = if (before == null) "" else
            "WHERE created_at < ? OR (created_at = ? AND id < ?)"
        val args = before?.let {
            arrayOf(it.createdAt.toEpochMilli().toString(), it.createdAt.toEpochMilli().toString(), it.id)
        }
        return readableDatabase.rawQuery("""
            SELECT id, date, created_at, model, prompt_version FROM coach_cards
            $selection ORDER BY created_at DESC, id DESC LIMIT $limit
        """.trimIndent(), args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(CoachCardSummary(
                    cursor.getString(0), LocalDate.parse(cursor.getString(1)),
                    Instant.ofEpochMilli(cursor.getLong(2)), cursor.getString(3), cursor.getString(4),
                ))
            }
        }
    }

    @Synchronized
    fun coachCard(id: String): SavedCoachCard? {
        return readableDatabase.rawQuery(
            "SELECT id, date, created_at, model, prompt_version, context_json, advice_json FROM coach_cards WHERE id = ?",
            arrayOf(id),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            SavedCoachCard(
                cursor.getString(0), LocalDate.parse(cursor.getString(1)), Instant.ofEpochMilli(cursor.getLong(2)),
                cursor.getString(3), cursor.getString(4), cursor.getString(5), CoachAdvice.decode(cursor.getString(6)),
            )
        }
    }

    @Synchronized
    fun latestCoachCard(): SavedCoachCard? {
        return readableDatabase.rawQuery(
            "SELECT id, date, created_at, model, prompt_version, context_json, advice_json FROM coach_cards ORDER BY created_at DESC, id DESC LIMIT 1",
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            SavedCoachCard(
                cursor.getString(0), LocalDate.parse(cursor.getString(1)), Instant.ofEpochMilli(cursor.getLong(2)),
                cursor.getString(3), cursor.getString(4), cursor.getString(5), CoachAdvice.decode(cursor.getString(6)),
            )
        }
    }

    @Synchronized
    override fun close() = super.close()
}
