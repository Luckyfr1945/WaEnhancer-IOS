package com.wmods.wppenhacer.xposed.core.db

import android.content.Context
import com.wmods.wppenhacer.xposed.core.db.entity.DelMessage

class DelMessageStore private constructor(context: Context) {

    private val database = DelMessageDatabase.getInstance(context)
    private val dao = database.delMessageDao()

    companion object {
        @Volatile
        private var instance: DelMessageStore? = null

        @JvmStatic
        fun getInstance(context: Context): DelMessageStore {
            return instance ?: synchronized(this) {
                instance ?: DelMessageStore(context.applicationContext).also { instance = it }
            }
        }
    }

    private val timestampCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val jidMessagesCache = java.util.concurrent.ConcurrentHashMap<String, java.util.HashSet<String>>()

    fun insertMessage(jid: String, msgid: String, timestamp: Long) {
        timestampCache[msgid] = timestamp
        jidMessagesCache.getOrPut(jid) { java.util.HashSet() }.add(msgid)
        val message = DelMessage(jid = jid, msgid = msgid, timestamp = timestamp)
        try {
            dao.insertMessage(message)
        } catch (_: Throwable) {}
    }

    fun getMessagesByJid(jid: String?): java.util.HashSet<String> {
        if (jid == null) return java.util.HashSet()
        jidMessagesCache[jid]?.let { return java.util.HashSet(it) }
        val set = try {
            java.util.HashSet(dao.getMessagesByJid(jid))
        } catch (_: Throwable) {
            java.util.HashSet()
        }
        jidMessagesCache[jid] = set
        return set
    }

    fun getTimestampByMessageId(msgid: String): Long {
        timestampCache[msgid]?.let { return it }
        java.util.concurrent.CompletableFuture.runAsync {
            try {
                val ts = dao.getTimestampByMessageId(msgid) ?: 0L
                if (ts > 0) {
                    timestampCache[msgid] = ts
                    com.wmods.wppenhacer.xposed.core.WppCore.getCurrentActivity()?.runOnUiThread {
                        com.wmods.wppenhacer.xposed.features.listeners.ConversationItemListener.notifyDataSetChanged()
                    }
                }
            } catch (_: Exception) {}
        }
        return 0L
    }
}
