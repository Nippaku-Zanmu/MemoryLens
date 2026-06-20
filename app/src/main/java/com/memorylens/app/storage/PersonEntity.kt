package com.memorylens.app.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity storing an enrolled person.
 * [embeddingBlob] holds 512 float32 values serialised as a ByteArray (2048 bytes).
 */
@Entity(tableName = "persons")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val relationship: String,
    val embeddingBlob: ByteArray,   // 512 × 4 bytes = 2048 bytes
    val enrolledAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PersonEntity
        return id == other.id
    }
    override fun hashCode(): Int = id
}
