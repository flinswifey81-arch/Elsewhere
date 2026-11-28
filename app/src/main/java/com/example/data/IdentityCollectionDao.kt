package com.example.data

import androidx.room.*
import com.example.data.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface IdentityCollectionDao {
    @Transaction
    @Query("SELECT * FROM identity_collections ORDER BY label COLLATE NOCASE, collectionId")
    fun observe(): Flow<List<IdentityCollection>>

    @Query("SELECT * FROM identity_collections")
    suspend fun collections(): List<IdentityCollectionEntity>

    @Query("SELECT * FROM identity_collection_characters")
    suspend fun characters(): List<IdentityCollectionCharacterEntity>

    @Query("SELECT * FROM identity_collection_personas")
    suspend fun personas(): List<IdentityCollectionPersonaEntity>

    @Upsert
    suspend fun saveCollections(rows: List<IdentityCollectionEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addCharacters(rows: List<IdentityCollectionCharacterEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addPersonas(rows: List<IdentityCollectionPersonaEntity>)

    @Delete
    suspend fun removeCharacters(rows: List<IdentityCollectionCharacterEntity>)

    @Delete
    suspend fun removePersonas(rows: List<IdentityCollectionPersonaEntity>)

    @Query("DELETE FROM identity_collections WHERE collectionId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM identity_collections")
    suspend fun clear()
}
