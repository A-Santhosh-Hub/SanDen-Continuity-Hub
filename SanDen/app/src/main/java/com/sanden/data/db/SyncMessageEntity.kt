package com.sanden.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_messages",
    indices = [Index(value = ["msgUid"])]
)
data class SyncMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val msgUid: String = "",
    val channel: String = "General",
    val sender: String,       // "PHONE" or "LAPTOP" or peer device name
    val targetPeerId: String = "", // "" = All Group broadcast; non-empty = private 1-on-1 peer scope
    val type: String,         // "TEXT", "CLIPBOARD", "FILE"
    val textContent: String = "",
    val fileName: String = "",
    val filePath: String = "",
    val fileSize: Long = 0L,
    val fileHash: String = "",
    val mimeType: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "sync_channels")
data class SyncChannelEntity(
    @PrimaryKey
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)
