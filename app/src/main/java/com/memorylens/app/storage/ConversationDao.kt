package com.memorylens.app.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO for [ConversationEntity]. Retrieves recent summaries for LLM prompt construction.
 */
@Dao
interface ConversationDao {

    /** Persist a completed conversation session. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity): Long

    /**
     * Return the 3 most recent conversation summaries for [personId].
     * Used to populate the LLM prompt template.
     */
    @Query("SELECT * FROM conversations WHERE personId = :personId ORDER BY occurredAt DESC LIMIT 3")
    suspend fun getRecentSummaries(personId: Int): List<ConversationEntity>

    /** Return all conversations for a person (used in the person detail screen). */
    @Query("SELECT * FROM conversations WHERE personId = :personId ORDER BY occurredAt DESC")
    suspend fun getAllForPerson(personId: Int): List<ConversationEntity>
}
