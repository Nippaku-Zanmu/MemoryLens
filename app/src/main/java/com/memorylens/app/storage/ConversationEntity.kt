package com.memorylens.app.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a conversation session with an enrolled person.
 * [personId] is a foreign key referencing [PersonEntity.id].
 */
@Entity(
    tableName = "conversations",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("personId")]
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val personId: Int,
    val summaryText: String,        // max 200 chars
    val occurredAt: Long = System.currentTimeMillis(),
    val durationSeconds: Int = 0
)
