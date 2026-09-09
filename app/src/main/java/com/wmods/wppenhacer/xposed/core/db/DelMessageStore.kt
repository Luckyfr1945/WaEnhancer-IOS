package com.wmods.wppenhacer.xposed.core.db

import android.content.Context
import com.wmods.wppenhacer.xposed.core.db.entity.DelMessage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

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

    private val timestampCache = ConcurrentHashMap<String, Long>()
    private val jidMessagesCache = ConcurrentHashMap<String, MutableSet<String>>()

    fun insertMessage(jid: String, msgid: String, timestamp: Long) {
        timestampCache[msgid] = timestamp
        jidMessagesCache.getOrPut(jid) { ConcurrentHashMap.newKeySet() }.add(msgid)
        val message = DelMessage(jid = jid, msgid = msgid, timestamp = timestamp)
        try {
            dao.insertMessage(message)
        } catch (_: Throwable) {}
    }

    fun getMessagesByJid(jid: String?): Set<String> {
        if (jid == null) return emptySet()
        jidMessagesCache[jid]?.let { return HashSet(it) }
        val set = ConcurrentHashMap.newKeySet<String>()
        try {
            val list = dao.getMessagesByJid(jid)
            if (!list.isNullOrEmpty()) {
                set.addAll(list)
            }
        } catch (_: Throwable) {}
        jidMessagesCache[jid] = set
        return HashSet(set)
    }

    fun getTimestampByMessageId(msgid: String): Long {
        timestampCache[msgid]?.let { return it }
        timestampCache[msgid] = 0L
        CompletableFuture.runAsync {
            try {
                val ts = dao.getTimestampByMessageId(msgid) ?: 0L
                if (ts > 0L) {
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
