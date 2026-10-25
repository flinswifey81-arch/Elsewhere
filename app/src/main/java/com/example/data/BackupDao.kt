package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.*

/**
 * Exact logical snapshot access for local backup and restore.
 *
 * This DAO deliberately contains no API-key, AndroidKeyStore, filesystem, or network-session
 * state. Restore orchestration validates an entire archive before using these mutation methods.
 */
@Dao
interface BackupDao {
    @Query("SELECT * FROM characters ORDER BY characterId")
    suspend fun getCharacters(): List<CharacterEntity>

    @Query("SELECT * FROM personas ORDER BY personaId")
    suspend fun getPersonas(): List<PersonaEntity>

    @Query("SELECT * FROM chats ORDER BY chatId")
    suspend fun getChats(): List<ChatEntity>

    @Query("SELECT * FROM chat_participants ORDER BY chatId, sortPosition, characterId")
    suspend fun getChatParticipants(): List<ChatParticipantEntity>

    @Query("SELECT * FROM chat_persona_participants ORDER BY chatId, sortPosition, personaId")
    suspend fun getChatPersonaParticipants(): List<ChatPersonaParticipantEntity>

    @Query("SELECT * FROM messages ORDER BY chatId, orderIndex, createdAt, messageId")
    suspend fun getMessages(): List<MessageEntity>

    @Query("SELECT * FROM relationships ORDER BY relationshipId")
    suspend fun getRelationships(): List<RelationshipEntity>

    @Query("SELECT * FROM chat_settings ORDER BY chatId")
    suspend fun getChatSettings(): List<ChatSettingsEntity>

    @Query("SELECT * FROM generation_metadata ORDER BY messageId")
    suspend fun getGenerationMetadata(): List<GenerationMetadataEntity>

    @Query("SELECT * FROM roleplay_memories ORDER BY chatId, characterId, createdAt, memoryId")
    suspend fun getRoleplayMemories(): List<RoleplayMemoryEntity>

    @Query("SELECT * FROM conversation_summaries ORDER BY chatId")
    suspend fun getConversationSummaries(): List<ConversationSummaryEntity>

    @Query("SELECT * FROM lorebooks ORDER BY lorebookId")
    suspend fun getLorebooks(): List<LorebookEntity>

    @Query("SELECT * FROM lorebook_entries ORDER BY lorebookId, priority DESC, entryId")
    suspend fun getLorebookEntries(): List<LorebookEntryEntity>

    @Query("SELECT * FROM chat_lorebook_attachments ORDER BY chatId, sortPosition, lorebookId")
    suspend fun getChatLorebookAttachments(): List<ChatLorebookAttachmentEntity>

    @Query("SELECT * FROM chat_lorebook_entry_overlays ORDER BY chatId, entryId")
    suspend fun getChatLorebookEntryOverlays(): List<ChatLorebookEntryOverlayEntity>

    @Query("SELECT * FROM coop_rooms ORDER BY roomId")
    suspend fun getCoopRooms(): List<CoopRoomEntity>

    @Query("SELECT * FROM coop_participants ORDER BY roomId, sortPosition, participantId")
    suspend fun getCoopParticipants(): List<CoopParticipantEntity>

    @Query("SELECT * FROM coop_persona_ownership ORDER BY roomId, sortPosition, personaId")
    suspend fun getCoopPersonaOwnerships(): List<CoopPersonaOwnershipEntity>

    @Query("SELECT * FROM coop_events ORDER BY roomId, sequence, eventId")
    suspend fun getCoopEvents(): List<CoopEventEntity>

    @Query("SELECT * FROM coop_message_tombstones ORDER BY roomId, messageId")
    suspend fun getCoopMessageTombstones(): List<CoopMessageTombstoneEntity>

    @Query("SELECT * FROM message_attachments ORDER BY chatId, createdAt, sortPosition, attachmentId")
    suspend fun getMessageAttachments(): List<MessageAttachmentEntity>

    @Query("SELECT * FROM npc_dossiers ORDER BY chatId, npcId")
    suspend fun getNpcDossiers(): List<NpcDossierEntity>

    @Query("SELECT * FROM npc_bank_settings ORDER BY chatId")
    suspend fun getNpcBankSettings(): List<NpcBankSettingsEntity>

    @Query("SELECT * FROM npc_dossier_updates ORDER BY chatId, sourceOrderIndex, sourceRevision, updateId")
    suspend fun getNpcDossierUpdates(): List<NpcDossierUpdateEntity>

    @Query("SELECT * FROM engine_defaults ORDER BY defaultsId")
    suspend fun getEngineDefaults(): List<EngineDefaultsEntity>

    @Query("SELECT * FROM npc_library_banks ORDER BY bankId")
    suspend fun getNpcLibraryBanks(): List<NpcLibraryBankEntity>

    @Query("SELECT * FROM npc_library_entries ORDER BY bankId, sortPosition, libraryNpcId")
    suspend fun getNpcLibraryEntries(): List<NpcLibraryEntryEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCharacters(values: List<CharacterEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPersonas(values: List<PersonaEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChats(values: List<ChatEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChatParticipants(values: List<ChatParticipantEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChatPersonaParticipants(values: List<ChatPersonaParticipantEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessages(values: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRelationships(values: List<RelationshipEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChatSettings(values: List<ChatSettingsEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGenerationMetadata(values: List<GenerationMetadataEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRoleplayMemories(values: List<RoleplayMemoryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConversationSummaries(values: List<ConversationSummaryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLorebooks(values: List<LorebookEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLorebookEntries(values: List<LorebookEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChatLorebookAttachments(values: List<ChatLorebookAttachmentEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChatLorebookEntryOverlays(values: List<ChatLorebookEntryOverlayEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCoopRooms(values: List<CoopRoomEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCoopParticipants(values: List<CoopParticipantEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCoopPersonaOwnerships(values: List<CoopPersonaOwnershipEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCoopEvents(values: List<CoopEventEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCoopMessageTombstones(values: List<CoopMessageTombstoneEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessageAttachments(values: List<MessageAttachmentEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNpcDossiers(values: List<NpcDossierEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNpcBankSettings(values: List<NpcBankSettingsEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNpcDossierUpdates(values: List<NpcDossierUpdateEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEngineDefaults(values: List<EngineDefaultsEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNpcLibraryBanks(values: List<NpcLibraryBankEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNpcLibraryEntries(values: List<NpcLibraryEntryEntity>)

    @Query("DELETE FROM npc_library_entries")
    suspend fun deleteNpcLibraryEntries()

    @Query("DELETE FROM npc_library_banks")
    suspend fun deleteNpcLibraryBanks()

    @Query("DELETE FROM engine_defaults")
    suspend fun deleteEngineDefaults()

    @Query("DELETE FROM generation_metadata")
    suspend fun deleteGenerationMetadata()

    @Query("DELETE FROM message_attachments")
    suspend fun deleteMessageAttachments()

    @Query("DELETE FROM npc_dossier_updates")
    suspend fun deleteNpcDossierUpdates()

    @Query("DELETE FROM npc_dossiers")
    suspend fun deleteNpcDossiers()

    @Query("DELETE FROM npc_bank_settings")
    suspend fun deleteNpcBankSettings()

    @Query("DELETE FROM coop_message_tombstones")
    suspend fun deleteCoopMessageTombstones()

    @Query("DELETE FROM coop_events")
    suspend fun deleteCoopEvents()

    @Query("DELETE FROM coop_persona_ownership")
    suspend fun deleteCoopPersonaOwnerships()

    @Query("DELETE FROM coop_participants")
    suspend fun deleteCoopParticipants()

    @Query("DELETE FROM coop_rooms")
    suspend fun deleteCoopRooms()

    @Query("DELETE FROM chat_lorebook_entry_overlays")
    suspend fun deleteChatLorebookEntryOverlays()

    @Query("DELETE FROM chat_lorebook_attachments")
    suspend fun deleteChatLorebookAttachments()

    @Query("DELETE FROM lorebook_entries")
    suspend fun deleteLorebookEntries()

    @Query("DELETE FROM lorebooks")
    suspend fun deleteLorebooks()

    @Query("DELETE FROM conversation_summaries")
    suspend fun deleteConversationSummaries()

    @Query("DELETE FROM roleplay_memories")
    suspend fun deleteRoleplayMemories()

    @Query("DELETE FROM relationships")
    suspend fun deleteRelationships()

    @Query("DELETE FROM messages")
    suspend fun deleteMessages()

    @Query("DELETE FROM chat_settings")
    suspend fun deleteChatSettings()

    @Query("DELETE FROM chat_participants")
    suspend fun deleteChatParticipants()

    @Query("DELETE FROM chat_persona_participants")
    suspend fun deleteChatPersonaParticipants()

    @Query("DELETE FROM chats")
    suspend fun deleteChats()

    @Query("DELETE FROM characters")
    suspend fun deleteCharacters()

    @Query("DELETE FROM personas")
    suspend fun deletePersonas()
}
