package com.wmods.wppenhacer.xposed.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wmods.wppenhacer.xposed.core.db.entity.HideSeenEntity

@Dao
interface HideSeenDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertOrIgnore(entity: HideSeenEntity): Long

    @Query("UPDATE hide_seen_messages SET read = :viewed WHERE (jid = :jid AND message_id = :messageId) OR message_id = :messageId")
    fun updateRead(jid: String, messageId: String, viewed: Int): Int

    @Query("UPDATE hide_seen_messages SET played = :viewed WHERE (jid = :jid AND message_id = :messageId) OR message_id = :messageId")
    fun updatePlayed(jid: String, messageId: String, viewed: Int): Int

    @Query("SELECT read FROM hide_seen_messages WHERE message_id = :messageId LIMIT 1")
    fun getReadState(messageId: String): Int?

    @Query("SELECT read FROM hide_seen_messages WHERE message_id = :messageId OR (jid = :jid AND message_id = :messageId) LIMIT 1")
    fun getReadState(jid: String, messageId: String): Int?

    @Query("SELECT played FROM hide_seen_messages WHERE message_id = :messageId LIMIT 1")
    fun getPlayedState(messageId: String): Int?

    @Query("SELECT played FROM hide_seen_messages WHERE message_id = :messageId OR (jid = :jid AND message_id = :messageId) LIMIT 1")
    fun getPlayedState(jid: String, messageId: String): Int?

    @Query("SELECT _id, jid, message_id, read, played FROM hide_seen_messages WHERE (jid = :jid OR jid LIKE :pattern) AND read = :viewed")
    fun getMessagesByReadState(jid: String, pattern: String, viewed: Int): List<HideSeenEntity>

    @Query("SELECT _id, jid, message_id, read, played FROM hide_seen_messages WHERE jid = :jid AND read = :viewed")
    fun getMessagesByReadState(jid: String, viewed: Int): List<HideSeenEntity>

    @Query("SELECT _id, jid, message_id, read, played FROM hide_seen_messages WHERE (jid = :jid OR jid LIKE :pattern) AND played = :viewed")
    fun getMessagesByPlayedState(jid: String, pattern: String, viewed: Int): List<HideSeenEntity>

    @Query("SELECT _id, jid, message_id, read, played FROM hide_seen_messages WHERE jid = :jid AND played = :viewed")
    fun getMessagesByPlayedState(jid: String, viewed: Int): List<HideSeenEntity>
}
