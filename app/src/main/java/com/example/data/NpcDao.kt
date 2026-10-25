package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.NpcBankSettingsEntity
import com.example.data.model.NpcDossierEntity
import com.example.data.model.NpcDossierUpdateEntity
import com.example.data.model.NpcLibraryBankEntity
import com.example.data.model.NpcLibraryEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NpcDao {
    @Query("SELECT * FROM npc_library_banks ORDER BY name COLLATE NOCASE, createdAt")
    fun observeLibraryBanks(): Flow<List<NpcLibraryBankEntity>>

    @Query("SELECT * FROM npc_library_banks ORDER BY name COLLATE NOCASE, createdAt")
    suspend fun getLibraryBanks(): List<NpcLibraryBankEntity>

    @Query("SELECT * FROM npc_library_entries WHERE bankId = :bankId ORDER BY sortPosition, displayName COLLATE NOCASE")
    suspend fun getLibraryEntries(bankId: String): List<NpcLibraryEntryEntity>

    @Query("SELECT * FROM npc_library_entries ORDER BY bankId, sortPosition, displayName COLLATE NOCASE")
    suspend fun getAllLibraryEntries(): List<NpcLibraryEntryEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLibraryBank(bank: NpcLibraryBankEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLibraryEntries(entries: List<NpcLibraryEntryEntity>)

    @Query("DELETE FROM npc_library_entries WHERE bankId = :bankId")
    suspend fun deleteLibraryEntries(bankId: String)

    @Query("DELETE FROM npc_library_banks WHERE bankId = :bankId")
    suspend fun deleteLibraryBank(bankId: String)

    @Query("SELECT * FROM npc_dossiers WHERE chatId = :chatId ORDER BY keepInScene DESC, displayName COLLATE NOCASE")
    fun observeDossiers(chatId: String): Flow<List<NpcDossierEntity>>

    @Query("SELECT * FROM npc_dossiers WHERE chatId = :chatId ORDER BY createdAt, npcId")
    suspend fun getDossiers(chatId: String): List<NpcDossierEntity>

    @Query("SELECT * FROM npc_dossiers WHERE npcId = :npcId LIMIT 1")
    suspend fun getDossier(npcId: String): NpcDossierEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDossier(dossier: NpcDossierEntity)

    @Update
    suspend fun updateDossier(dossier: NpcDossierEntity)

    @Query("DELETE FROM npc_dossiers WHERE npcId = :npcId")
    suspend fun deleteDossier(npcId: String)

    @Query("SELECT * FROM npc_dossier_updates WHERE chatId = :chatId ORDER BY createdAt, updateId")
    suspend fun getUpdates(chatId: String): List<NpcDossierUpdateEntity>

    @Query("SELECT * FROM npc_dossier_updates WHERE chatId = :chatId ORDER BY createdAt, updateId")
    fun observeUpdates(chatId: String): Flow<List<NpcDossierUpdateEntity>>

    @Query("SELECT * FROM npc_dossier_updates WHERE sourceMessageId = :messageId ORDER BY sourceRevision, createdAt")
    suspend fun getUpdatesForMessage(messageId: String): List<NpcDossierUpdateEntity>

    @Query("SELECT COALESCE(MAX(sourceRevision), -1) + 1 FROM npc_dossier_updates WHERE sourceMessageId = :messageId")
    suspend fun nextSourceRevision(messageId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUpdates(updates: List<NpcDossierUpdateEntity>)

    @Query("UPDATE npc_dossier_updates SET isApplied = :isApplied WHERE sourceMessageId = :messageId")
    suspend fun setMessageUpdatesApplied(messageId: String, isApplied: Boolean)

    @Query("UPDATE npc_dossier_updates SET isApplied = :isApplied WHERE variantGroupId = :variantGroupId")
    suspend fun setVariantUpdatesApplied(variantGroupId: String, isApplied: Boolean)

    @Query("DELETE FROM npc_dossier_updates WHERE sourceMessageId = :messageId")
    suspend fun deleteUpdatesForMessage(messageId: String)

    @Query("DELETE FROM npc_dossier_updates WHERE npcId = :npcId")
    suspend fun deleteUpdatesForDossier(npcId: String)

    @Query("SELECT * FROM npc_bank_settings WHERE chatId = :chatId LIMIT 1")
    suspend fun getSettings(chatId: String): NpcBankSettingsEntity?

    @Query("SELECT * FROM npc_bank_settings WHERE chatId = :chatId LIMIT 1")
    fun observeSettings(chatId: String): Flow<NpcBankSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: NpcBankSettingsEntity)
}
