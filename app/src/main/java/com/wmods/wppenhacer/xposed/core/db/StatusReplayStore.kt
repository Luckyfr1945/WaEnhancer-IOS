package com.wmods.wppenhacer.xposed.core.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.wmods.wppenhacer.xposed.utils.Utils
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class StatusReplayStore private constructor(context: Context) {

    data class ReplayRecord(
        val statusId: String,
        val viewerJid: String,
        val viewCount: Int,
        val firstSeen: Long,
        val lastSeen: Long,
        val history: List<Long>
    )

    private class DbHelper(context: Context) : SQLiteOpenHelper(context, "status_replays.db", null, 2) {
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
            // CATATAN: ini menghapus seluruh histori replay saat schema berubah.
            // Cukup aman untuk data cache non-kritis seperti ini; kalau nanti histori
            // dianggap penting untuk dipertahankan lintas update, ganti dengan ALTER TABLE bertahap.
            db.execSQL("DROP TABLE IF EXISTS status_replays")
            onCreate(db)
        }
    }

    private val dbHelper = DbHelper(context.applicationContext)

    // Key: Pair(statusId, viewerJid) — pakai Pair, bukan string concat, supaya
    // tidak ada risiko tabrakan key kalau delimiter kebetulan muncul di salah satu nilai.
    private val memoryCache = ConcurrentHashMap<Pair<String, String>, ReplayRecord>()
    private val preloadedStatusIds = ConcurrentHashMap.newKeySet<String>()
    private val ioExecutor = Executors.newSingleThreadExecutor()

    /**
     * Mencatat satu event "seen" status. Debounce 15 detik supaya paket receipt duplikat
     * dari WhatsApp tidak menggelembungkan hitungan.
     */
    @Synchronized
    fun recordStatusView(statusId: String, viewerJid: String, timestamp: Long = System.currentTimeMillis()): ReplayRecord {
        val key = statusId to viewerJid
        val existing = memoryCache[key] ?: readFromDb(statusId, viewerJid)

        val newRecord: ReplayRecord
        if (existing == null) {
            newRecord = ReplayRecord(statusId, viewerJid, 1, timestamp, timestamp, listOf(timestamp))
            insertRecord(newRecord)
        } else {
            if (timestamp - existing.lastSeen < 15_000L) {
                memoryCache[key] = existing
                return existing
            }
            newRecord = existing.copy(
                viewCount = existing.viewCount + 1,
                lastSeen = timestamp,
                history = existing.history + timestamp
            )
            updateRecord(newRecord)
        }

        memoryCache[key] = newRecord
        return newRecord
    }

    /**
     * Muat semua record untuk satu statusId ke memory cache dalam SATU query.
     * WAJIB dipanggil di background thread (lihat preloadStatusReplaysAsync),
     * idealnya begitu status aktif berganti — SEBELUM viewer list dirender.
     * Idempotent: query kedua dst untuk statusId yang sama langsung no-op.
     */
    fun preloadStatusReplays(statusId: String) {
        if (!preloadedStatusIds.add(statusId)) return
        try {
            val db = dbHelper.readableDatabase
            db.rawQuery(
                "SELECT status_id, viewer_jid, view_count, first_seen, last_seen, history_json FROM status_replays WHERE status_id = ?",
                arrayOf(statusId)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val record = cursorToRecord(cursor)
                    memoryCache[record.statusId to record.viewerJid] = record
                }
            }
        } catch (_: Exception) {
            preloadedStatusIds.remove(statusId) // biar dicoba ulang kalau gagal
        }
    }

    fun preloadStatusReplaysAsync(statusId: String) {
        ioExecutor.execute { preloadStatusReplays(statusId) }
    }

    /**
     * Lookup CACHE-ONLY — aman dipanggil dari UI thread (mis. di onBindViewHolder).
     * Tidak pernah menyentuh SQLite. Pastikan preloadStatusReplaysAsync() sudah
     * dipanggil untuk statusId ini sebelumnya.
     */
    fun getReplayRecordCached(statusId: String, viewerJid: String): ReplayRecord? {
        memoryCache[statusId to viewerJid]?.let { return it }
        val userPart = viewerJid.substringBefore("@")
        return memoryCache.entries.firstOrNull { (k, _) ->
            k.first == statusId && (k.second == viewerJid || k.second.substringBefore("@") == userPart)
        }?.value
    }

    /**
     * Lookup dengan fallback ke SQLite kalau cache miss. JANGAN dipakai di loop bind UI —
     * gunakan getReplayRecordCached() untuk itu. Cocok untuk pemanggilan sesekali
     * (mis. dari dialog riwayat).
     */
    fun getReplayRecord(statusId: String, viewerJid: String): ReplayRecord? {
        memoryCache[statusId to viewerJid]?.let { return it }
        return readFromDb(statusId, viewerJid)?.also { memoryCache[statusId to viewerJid] = it }
    }

    private fun readFromDb(statusId: String, viewerJid: String): ReplayRecord? {
        return try {
            val db = dbHelper.readableDatabase
            db.rawQuery(
                "SELECT status_id, viewer_jid, view_count, first_seen, last_seen, history_json FROM status_replays WHERE status_id = ? AND viewer_jid = ?",
                arrayOf(statusId, viewerJid)
            ).use { cursor -> if (cursor.moveToFirst()) cursorToRecord(cursor) else null }
        } catch (_: Exception) {
            null
        }
    }

    fun getAllReplaysForStatus(statusId: String): Map<String, ReplayRecord> {
        preloadStatusReplays(statusId) // idempotent, pastikan cache terisi dulu
        return memoryCache.filterKeys { it.first == statusId }.mapKeys { it.key.second }
    }

    fun getReplayCount(statusId: String, viewerJid: String): Int =
        getReplayRecordCached(statusId, viewerJid)?.viewCount ?: 0

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

    fun pruneOldRecords(maxAgeMillis: Long = 72 * 60 * 60 * 1000L) { // 3 hari
        try {
            val cutoff = System.currentTimeMillis() - maxAgeMillis
            val db = dbHelper.writableDatabase
            db.delete("status_replays", "last_seen < ?", arrayOf(cutoff.toString()))
            // Hapus dari cache HANYA entri yang memang dipangkas, jangan nuke semuanya —
            // supaya status lain yang masih aktif tidak perlu preload ulang dari nol.
            memoryCache.entries.removeIf { it.value.lastSeen < cutoff }
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