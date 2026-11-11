package com.bcardo.ringcoach

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.LocalDate

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

internal class JournalStore(context: Context, name: String = "ringcoach.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE journal_entries (
                date TEXT PRIMARY KEY NOT NULL,
                training TEXT NOT NULL,
                diet TEXT NOT NULL,
                additional TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "Unsupported journal schema migration" }
        // SQLiteOpenHelper runs the migration in a transaction.
        db.execSQL("ALTER TABLE journal_entries RENAME TO journal_entries_v1")
        onCreate(db)
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

    @Synchronized
    fun save(draft: JournalDraft): JournalEntry {
        val clean = draft.trimmed()
        require(clean.hasContent) { "Journal entry is empty" }
        val updated = Instant.now()
        writableDatabase.execSQL("""
            INSERT INTO journal_entries (date, training, diet, additional, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(date) DO UPDATE SET
                training = excluded.training,
                diet = excluded.diet,
                additional = excluded.additional,
                updated_at = excluded.updated_at
        """.trimIndent(), arrayOf(
            clean.date.toString(), clean.training, clean.diet, clean.additional,
            updated.toString(), updated.toString(),
        ))
        return JournalEntry(clean, updated)
    }

    @Synchronized
    fun readAll(): List<JournalEntry> {
        return readableDatabase.rawQuery(
            "SELECT date, training, diet, additional, updated_at FROM journal_entries ORDER BY date DESC",
            null,
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
    override fun close() = super.close()
}
