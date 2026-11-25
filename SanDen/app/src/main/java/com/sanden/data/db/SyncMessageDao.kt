package com.sanden.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncMessageDao {

    @Query("SELECT * FROM sync_messages WHERE channel = :channel AND targetPeerId = :targetPeerId ORDER BY timestamp ASC")
    fun observeByChannelAndTarget(channel: String, targetPeerId: String = ""): Flow<List<SyncMessageEntity>>

    @Query("SELECT * FROM sync_messages WHERE channel = :channel ORDER BY timestamp ASC")
    fun observeByChannel(channel: String): Flow<List<SyncMessageEntity>>

    @Query("SELECT * FROM sync_messages ORDER BY timestamp ASC")
    fun observeAll(): Flow<List<SyncMessageEntity>>

    @Query("SELECT * FROM sync_messages WHERE channel = :channel AND (targetPeerId = '' OR targetPeerId = :requesterId) ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentByChannelForRequester(channel: String, requesterId: String = "", limit: Int = 100): List<SyncMessageEntity>

    @Query("SELECT * FROM sync_messages WHERE timestamp >= :since AND (targetPeerId = '' OR targetPeerId = :requesterId) ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getMessagesSinceForRequester(since: Long, requesterId: String = "", limit: Int = 500): List<SyncMessageEntity>

    @Query("SELECT * FROM sync_messages WHERE msgUid = :msgUid AND msgUid != '' LIMIT 1")
    suspend fun getByMsgUid(msgUid: String): SyncMessageEntity?

    @Query("SELECT * FROM sync_messages WHERE fileHash = :fileHash AND fileHash != '' AND filePath != '' LIMIT 1")
    suspend fun findByFileHash(fileHash: String): SyncMessageEntity?

    @Query("SELECT * FROM sync_messages WHERE channel = :channel ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentByChannel(channel: String, limit: Int = 100): List<SyncMessageEntity>

    @Query("SELECT * FROM sync_messages ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<SyncMessageEntity>

    @Query("SELECT * FROM sync_messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SyncMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: SyncMessageEntity): Long

    @Query("DELETE FROM sync_messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM sync_messages WHERE channel = :channel AND targetPeerId = :targetPeerId")
    suspend fun clearChannelAndTarget(channel: String, targetPeerId: String = "")

    @Query("DELETE FROM sync_messages WHERE channel = :channel")
    suspend fun clearChannel(channel: String)

    @Query("DELETE FROM sync_messages")
    suspend fun clearAll()

    // ── Project Branches / Channels ──────────────────────────────────────

    @Query("SELECT * FROM sync_channels ORDER BY createdAt ASC")
    fun observeChannels(): Flow<List<SyncChannelEntity>>

    @Query("SELECT * FROM sync_channels ORDER BY createdAt ASC")
    suspend fun getChannels(): List<SyncChannelEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChannel(channel: SyncChannelEntity)
}
