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
}
