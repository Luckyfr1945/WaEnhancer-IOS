package com.wmods.wppenhacer.xposed.core.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.wmods.wppenhacer.xposed.utils.Utils
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

class StatusReplayStore private constructor(context: Context) {

    data class ReplayRecord(
        val statusId: String,
        val viewerJid: String,
        val viewCount: Int,
        val firstSeen: Long,
        val lastSeen: Long,
        val history: List<Long>
    )

    private class DbHelper(context: Context) : SQLiteOpenHelper(context, "status_replays.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS status_replays (
                    status_id TEXT NOT NULL,
                    viewer_jid TEXT NOT NULL,
                    view_count INTEGER DEFAULT 1,
                    first_seen INTEGER NOT NULL,
                    last_seen INTEGER NOT NULL,
                    history_json TEXT NOT NULL,
                    PRIMARY KEY (status_id, viewer_jid)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_status_id ON status_replays (status_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_viewer_jid ON status_replays (viewer_jid)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS status_replays")
            onCreate(db)
        }
    }

    private val dbHelper = DbHelper(context.applicationContext)
    private val memoryCache = ConcurrentHashMap<String, ReplayRecord>()

    private fun cacheKey(statusId: String, viewerJid: String): String = "${statusId}_${viewerJid}"

    /**
     * Records a status view event.
     * Debounced by 5000ms (5 seconds) to prevent duplicate receipt packets from inflating the count.
     * Returns the updated ReplayRecord.
     */
    @Synchronized
    fun recordStatusView(statusId: String, viewerJid: String, timestamp: Long = System.currentTimeMillis()): ReplayRecord {
        val key = cacheKey(statusId, viewerJid)
        val existing = getReplayRecord(statusId, viewerJid)

        val newRecord: ReplayRecord
        if (existing == null) {
            val history = listOf(timestamp)
            newRecord = ReplayRecord(
                statusId = statusId,
                viewerJid = viewerJid,
                viewCount = 1,
                firstSeen = timestamp,
                lastSeen = timestamp,
                history = history
            )
            insertRecord(newRecord)
        } else {
            // Debounce: if receipt arrived within 5 seconds of the last recorded view, don't increment
            if (timestamp - existing.lastSeen < 5000L) {
                return existing
            }
            val newHistory = existing.history + timestamp
            newRecord = existing.copy(
                viewCount = existing.viewCount + 1,
                lastSeen = timestamp,
                history = newHistory
            )
            updateRecord(newRecord)
        }

        memoryCache[key] = newRecord
        return newRecord
    }

    fun getReplayRecord(statusId: String, viewerJid: String): ReplayRecord? {
        val key = cacheKey(statusId, viewerJid)
        memoryCache[key]?.let { return it }

        return try {
            val db = dbHelper.readableDatabase
            db.rawQuery(
                "SELECT status_id, viewer_jid, view_count, first_seen, last_seen, history_json FROM status_replays WHERE status_id = ? AND viewer_jid = ?",
                arrayOf(statusId, viewerJid)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val record = cursorToRecord(cursor)
                    memoryCache[key] = record
                    record
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getAllReplaysForStatus(statusId: String): Map<String, ReplayRecord> {
        val result = mutableMapOf<String, ReplayRecord>()
        try {
            val db = dbHelper.readableDatabase
            db.rawQuery(
                "SELECT status_id, viewer_jid, view_count, first_seen, last_seen, history_json FROM status_replays WHERE status_id = ?",
                arrayOf(statusId)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val record = cursorToRecord(cursor)
                    result[record.viewerJid] = record
                    memoryCache[cacheKey(record.statusId, record.viewerJid)] = record
                }
            }
        } catch (_: Exception) {}
        return result
    }

    fun getReplayCount(statusId: String, viewerJid: String): Int {
        return getReplayRecord(statusId, viewerJid)?.viewCount ?: 0
    }

    private fun insertRecord(record: ReplayRecord) {
        try {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("status_id", record.statusId)
                put("viewer_jid", record.viewerJid)
                put("view_count", record.viewCount)
                put("first_seen", record.firstSeen)
                put("last_seen", record.lastSeen)
                put("history_json", JSONArray(record.history).toString())
            }
            db.insertWithOnConflict("status_replays", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (_: Exception) {}
    }

    private fun updateRecord(record: ReplayRecord) {
        try {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("view_count", record.viewCount)
                put("last_seen", record.lastSeen)
                put("history_json", JSONArray(record.history).toString())
            }
            db.update(
                "status_replays",
                values,
                "status_id = ? AND viewer_jid = ?",
                arrayOf(record.statusId, record.viewerJid)
            )
        } catch (_: Exception) {}
    }

    private fun cursorToRecord(cursor: android.database.Cursor): ReplayRecord {
        val statusId = cursor.getString(0)
        val viewerJid = cursor.getString(1)
        val viewCount = cursor.getInt(2)
        val firstSeen = cursor.getLong(3)
        val lastSeen = cursor.getLong(4)
        val historyJson = cursor.getString(5)

        val historyList = mutableListOf<Long>()
        try {
            val jsonArray = JSONArray(historyJson)
            for (i in 0 until jsonArray.length()) {
                historyList.add(jsonArray.getLong(i))
            }
        } catch (_: Exception) {
            historyList.add(firstSeen)
        }

        return ReplayRecord(statusId, viewerJid, viewCount, firstSeen, lastSeen, historyList)
    }

    fun pruneOldRecords(maxAgeMillis: Long = 72 * 60 * 60 * 1000L) { // 3 days
        try {
            val cutoff = System.currentTimeMillis() - maxAgeMillis
            val db = dbHelper.writableDatabase
            db.delete("status_replays", "last_seen < ?", arrayOf(cutoff.toString()))
            memoryCache.clear()
        } catch (_: Exception) {}
    }

    companion object {
        @Volatile
        private var instance: StatusReplayStore? = null

        fun getInstance(context: Context = Utils.application): StatusReplayStore {
            return instance ?: synchronized(this) {
                instance ?: StatusReplayStore(context).also { instance = it }
            }
        }
    }
}
