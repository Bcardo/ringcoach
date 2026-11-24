package com.bcardo.ringcoach

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Bounded operational history. Never stores input text, model responses, readings or identifiers. */
internal class ProcessingFailureStore(context: Context, name: String = "processing-failures.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) = db.execSQL("""CREATE TABLE events (
        id INTEGER PRIMARY KEY AUTOINCREMENT,at INTEGER NOT NULL,stage TEXT NOT NULL,
        code TEXT NOT NULL,attempt INTEGER NOT NULL,outcome TEXT NOT NULL,model TEXT NOT NULL,prompt TEXT NOT NULL)""")
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported processing history version")
    fun record(stage: String, code: String, attempt: Int, outcome: String, prompt: String? = null) {
        require(stage in setOf("nutrition", "workout", "coach", "setup"))
        require(code.matches(Regex("[a-z_]{1,80}")))
        require(outcome in setOf("corrective_retry", "recovered", "blocked", "retry_scheduled"))
        val db = writableDatabase
        db.execSQL("INSERT INTO events(at,stage,code,attempt,outcome,model,prompt) VALUES (?,?,?,?,?,?,?)",
            arrayOf<Any>(System.currentTimeMillis(), stage, code, attempt, outcome, QWEN_MODEL,
                prompt ?: when (stage) {
                    "workout" -> WORKOUT_EXTRACTOR_VERSION
                    "nutrition" -> NutritionExtractor.VERSION
                    else -> COACH_PROMPT_VERSION
                }))
        db.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT 100)")
    }
}
