package com.example.domain.world

import com.example.data.model.*
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Exact, World-scoped, portable data snapshot. This is NOT a full-app backup.
 *
 * The snapshot preserves *all* selected message variants, sources, per-chat memories,
 * scene/NPC revisions, Scenario playthrough snapshots and Lorebook overrides. No provider
 * keys, global settings, co-op rooms or unrelated chats belong here.
 *
 * Binary files live in the encrypted archive, not JSON. In particular, device-private
 * MessageAttachmentEntity.relativePath MUST NEVER appear in this DTO.
 *
 * Import/export IO and database mutations are deliberately out of scope for this contract.
 */
@JsonClass(generateAdapter = true)
data class WorldArchiveStateV1(
    val format: String,
    @Json(name = "schema_version") val schemaVersion: Int,
    @Json(name = "world_id") val worldId: String,
    val world: WorldEntity,
    val memberships: List<WorldMembershipEntity> = emptyList(),
    val cast: List<WorldCastEntity> = emptyList(),
    val continuity: List<WorldContinuityEventEntity> = emptyList(),
    val characters: List<CharacterEntity> = emptyList(),
    val personas: List<PersonaEntity> = emptyList(),
    val scenarios: List<ScenarioEntity> = emptyList(),
    @Json(name = "scenario_sessions") val scenarioSessions: List<ScenarioSessionEntity> = emptyList(),
    val chats: List<ChatEntity> = emptyList(),
    @Json(name = "chat_participants") val chatParticipants: List<ChatParticipantEntity> = emptyList(),
    @Json(name = "chat_persona_participants") val chatPersonaParticipants: List<ChatPersonaParticipantEntity> = emptyList(),
    val messages: List<MessageEntity> = emptyList(),
    @Json(name = "chat_settings") val chatSettings: List<ChatSettingsEntity> = emptyList(),
    @Json(name = "generation_metadata") val generationMetadata: List<GenerationMetadataEntity> = emptyList(),
    @Json(name = "roleplay_memories") val roleplayMemories: List<RoleplayMemoryEntity> = emptyList(),
    @Json(name = "conversation_summaries") val conversationSummaries: List<ConversationSummaryEntity> = emptyList(),
    val relationships: List<RelationshipEntity> = emptyList(),
    val lorebooks: List<LorebookEntity> = emptyList(),
    @Json(name = "lorebook_entries") val lorebookEntries: List<LorebookEntryEntity> = emptyList(),
    @Json(name = "chat_lorebook_attachments") val chatLorebookAttachments: List<ChatLorebookAttachmentEntity> = emptyList(),
    @Json(name = "chat_lorebook_overlays") val chatLorebookOverlays: List<ChatLorebookEntryOverlayEntity> = emptyList(),
    @Json(name = "npc_dossiers") val npcDossiers: List<NpcDossierEntity> = emptyList(),
    @Json(name = "npc_bank_settings") val npcBankSettings: List<NpcBankSettingsEntity> = emptyList(),
    @Json(name = "npc_updates") val npcUpdates: List<NpcDossierUpdateEntity> = emptyList(),
    @Json(name = "npc_library_banks") val npcLibraryBanks: List<NpcLibraryBankEntity> = emptyList(),
    @Json(name = "npc_library_entries") val npcLibraryEntries: List<NpcLibraryEntryEntity> = emptyList(),
    @Json(name = "message_attachments") val messageAttachments: List<WorldArchiveMessageAttachmentV1> = emptyList()
)

/**
 * Portable attachment metadata without the local filesystem path.
 * The assetPath MUST resolve to an authenticated, hash-checked archive member.
 */
@JsonClass(generateAdapter = true)
data class WorldArchiveMessageAttachmentV1(
    val attachmentId: String,
    val chatId: String,
    val messageId: String? = null,
    val assetPath: String,
    val mimeType: String,
    val byteSize: Long,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val sha256: String,
    val sortPosition: Int,
    val createdAt: Long
)
