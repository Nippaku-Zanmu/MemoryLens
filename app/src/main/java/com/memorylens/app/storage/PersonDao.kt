package com.memorylens.app.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [PersonEntity]. All suspension functions run on the Room executor.
 */
@Dao
interface PersonDao {

    /** Insert a new enrolled person. Returns the generated row ID. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(person: PersonEntity): Long

    /** Load all enrolled persons (used for embedding comparison). */
    @Query("SELECT * FROM persons")
    suspend fun getAllPersons(): List<PersonEntity>

    /** Observe enrolled persons list for the EnrolledPersonsActivity. */
    @Query("SELECT * FROM persons ORDER BY enrolledAt DESC")
    fun getAllPersonsFlow(): Flow<List<PersonEntity>>

    /** Look up a single person by primary key. */
    @Query("SELECT * FROM persons WHERE id = :personId LIMIT 1")
    suspend fun getPersonById(personId: Int): PersonEntity?

    /** Delete an enrolled person (cascade deletes their conversations). */
    @Delete
    suspend fun delete(person: PersonEntity)
}
