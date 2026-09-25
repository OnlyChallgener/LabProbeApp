package com.labprobe.app.feature.assistant

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow

/** Local, Hub-scoped inbox. Deleted rows retain only their ID to prevent replay. */
data class AiInboxEntry(
    val id: String,
    val title: String,
    val content: String,
    val receivedAt: Long,
)

internal object AiNotificationInboxSignals {
    val revision = MutableStateFlow(0)

    @Synchronized
    fun changed() {
        revision.value += 1
    }
}

class AiNotificationInboxStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "labprobe_ai_notifications.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE ai_inbox (
                hub_key TEXT NOT NULL,
                notice_id TEXT NOT NULL,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                received_at INTEGER NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (hub_key, notice_id)
            )"""
        )
        db.execSQL("CREATE INDEX ai_inbox_order ON ai_inbox(hub_key, deleted, received_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Returns only newly inserted rows; a deleted notification cannot be inserted again. */
    fun save(hubIdentity: String, rows: List<AiNotification>): List<AiInboxEntry> {
        val hubKey = aiNotificationHubKey(hubIdentity)
        require(hubKey.isNotBlank()) { "AI 通知缺少 Hub 身份" }
        if (rows.isEmpty()) return emptyList()
        val inserted = mutableListOf<AiInboxEntry>()
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { row ->
                val id = aiNotificationDeliveryIdentity(row.id, row.title, row.content, hubIdentity).noticeId
                val entry = AiInboxEntry(id, row.title, row.content, System.currentTimeMillis())
                val values = ContentValues().apply {
                    put("hub_key", hubKey)
                    put("notice_id", entry.id)
                    put("title", entry.title)
                    put("content", entry.content)
                    put("received_at", entry.receivedAt)
                }
                if (db.insertWithOnConflict("ai_inbox", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    inserted += entry
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (inserted.isNotEmpty()) AiNotificationInboxSignals.changed()
        return inserted
    }

    fun list(hubIdentity: String): List<AiInboxEntry> {
        val hubKey = aiNotificationHubKey(hubIdentity)
        if (hubKey.isBlank()) return emptyList()
        val rows = mutableListOf<AiInboxEntry>()
        readableDatabase.query(
            "ai_inbox",
            arrayOf("notice_id", "title", "content", "received_at"),
            "hub_key = ? AND deleted = 0",
            arrayOf(hubKey),
            null, null,
            "rowid DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += AiInboxEntry(
                    id = cursor.getString(0),
                    title = cursor.getString(1),
                    content = cursor.getString(2),
                    receivedAt = cursor.getLong(3),
                )
            }
        }
        return rows
    }

    fun find(hubIdentity: String, noticeId: String): AiInboxEntry? {
        val hubKey = aiNotificationHubKey(hubIdentity)
        if (hubKey.isBlank() || noticeId.isBlank()) return null
        readableDatabase.query(
            "ai_inbox",
            arrayOf("notice_id", "title", "content", "received_at"),
            "hub_key = ? AND notice_id = ? AND deleted = 0",
            arrayOf(hubKey, noticeId),
            null, null, null, "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return AiInboxEntry(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3))
        }
    }

    /** Clears content but keeps a tombstone, including after a cursor reset. */
    fun delete(hubIdentity: String, noticeIds: Collection<String>): Int {
        val hubKey = aiNotificationHubKey(hubIdentity)
        val ids = noticeIds.filter { it.isNotBlank() }.distinct()
        if (hubKey.isBlank() || ids.isEmpty()) return 0
        val db = writableDatabase
        var removed = 0
        db.beginTransaction()
        try {
            ids.chunked(250).forEach { group ->
                val values = ContentValues().apply {
                    put("title", "")
                    put("content", "")
                    put("deleted", 1)
                }
                removed += db.update(
                    "ai_inbox",
                    values,
                    "hub_key = ? AND deleted = 0 AND notice_id IN (${group.joinToString(",") { "?" }})",
                    arrayOf(hubKey, *group.toTypedArray()),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (removed > 0) AiNotificationInboxSignals.changed()
        return removed
    }
}
