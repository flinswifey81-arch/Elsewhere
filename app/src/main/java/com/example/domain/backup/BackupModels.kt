package com.example.domain.backup

import com.example.data.model.*
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BackupManifest(
    val formatVersion: Int,
    val applicationId: String,
    val appVersionCode: Long,
    val appVersionName: String,
    val databaseVersion: Int,
    val createdAt: Long,
    val payloadSha256: String,
    val files: List<BackupFileManifest>,
    val excludedCategories: List<String>,
    val coopRestoreMode: String
)

@JsonClass(generateAdapter = true)
data class BackupFileManifest(
    val entryName: String,
    val kind: BackupFileKind,
    val ownerId: String,
    val byteSize: Long,
    val sha256: String,
    val mimeType: String? = null
)

enum class BackupFileKind {
    MESSAGE_ATTACHMENT,
    CHARACTER_AVATAR,
    PERSONA_AVATAR,
    SCENARIO_AVATAR,
    NPC_AVATAR,
    NPC_LIBRARY_AVATAR,
    CHAT_COVER,
    WORLD_COVER,
    CAMPAIGN_COVER
}

@JsonClass(generateAdapter = true)
data class BackupAppearanceSettings(
    val themeMode: String,
    val fontSize: Float,
    val colorPalette: String,
    val legacyAccentOverride: String? = null
)

@JsonClass(generateAdapter = true)
data class BackupPayload(
    val characters: List<CharacterEntity>,
    val personas: List<PersonaEntity>,
    val chats: List<ChatEntity>,
    val chatParticipants: List<ChatParticipantEntity>,
    val chatPersonaParticipants: List<ChatPersonaParticipantEntity>,
    val messages: List<MessageEntity>,
    val relationships: List<RelationshipEntity>,
    val chatSettings: List<ChatSettingsEntity>,
    val generationMetadata: List<GenerationMetadataEntity>,
    val roleplayMemories: List<RoleplayMemoryEntity>,
    val conversationSummaries: List<ConversationSummaryEntity>,
    val lorebooks: List<LorebookEntity>,
    val lorebookEntries: List<LorebookEntryEntity>,
    val chatLorebookAttachments: List<ChatLorebookAttachmentEntity>,
    val chatLorebookEntryOverlays: List<ChatLorebookEntryOverlayEntity>,
    val coopRooms: List<CoopRoomEntity>,
    val coopParticipants: List<CoopParticipantEntity>,
    val coopPersonaOwnerships: List<CoopPersonaOwnershipEntity>,
    val coopEvents: List<CoopEventEntity>,
    val coopMessageTombstones: List<CoopMessageTombstoneEntity>,
    val messageAttachments: List<MessageAttachmentEntity>,
    val npcDossiers: List<NpcDossierEntity>,
    val npcBankSettings: List<NpcBankSettingsEntity>,
    val npcDossierUpdates: List<NpcDossierUpdateEntity>,
    val appearance: BackupAppearanceSettings,
    val scenarios: List<ScenarioEntity> = emptyList(),
    val scenarioSessions: List<ScenarioSessionEntity> = emptyList(),
    val engineDefaults: List<EngineDefaultsEntity> = emptyList(),
    val npcLibraryBanks: List<NpcLibraryBankEntity> = emptyList(),
    val npcLibraryEntries: List<NpcLibraryEntryEntity> = emptyList(),
    val worlds: List<WorldEntity> = emptyList(),
    val worldMemberships: List<WorldMembershipEntity> = emptyList(),
    val worldCast: List<WorldCastEntity> = emptyList(),
    val worldContinuityEvents: List<WorldContinuityEventEntity> = emptyList(),
    // v16 addition: defaults preserve loading historical v15 backup payloads.
    val storyMemories: List<StoryMemoryEntity> = emptyList(),
    val storyMemoryActors: List<StoryMemoryActorEntity> = emptyList(),
    val storyMemoryReturns: List<StoryMemoryReturnEntity> = emptyList(),
    // v20, backward-compatible with earlier encrypted backup payloads.
    val storyMemoryKnowledgeChanges: List<StoryMemoryKnowledgeChangeEntity> = emptyList(),
    // v21 optional addition: older archives contain no synthetic perspectives.
    val storyMemoryPerspectives: List<StoryMemoryPerspectiveEntity> = emptyList(),
    // v22: Campaign organization is optional for historical encrypted backups.
    val campaigns: List<CampaignEntity> = emptyList(),
    val campaignChapters: List<CampaignChapterEntity> = emptyList(),
    // v23: source-linked private checkpoints and Campaign Journal entries.
    val campaignJournalEntries: List<CampaignJournalEntryEntity> = emptyList(),
    // v24: optional private arcs, promises, threads and relationship progress.
    val campaignContinuityItems: List<CampaignContinuityItemEntity> = emptyList(),
    // v25: explicit, source-linked Campaign memory approvals.
    val campaignMemoryShares: List<CampaignMemoryShareEntity> = emptyList(),
    val identityCollections: List<IdentityCollectionEntity> = emptyList(),
    val collectionCharacters: List<IdentityCollectionCharacterEntity> = emptyList(),
    val collectionPersonas: List<IdentityCollectionPersonaEntity> = emptyList()
)

data class BackupPreview(
    val createdAt: Long,
    val appVersionName: String,
    val databaseVersion: Int,
    val characters: Int,
    val personas: Int,
    val chats: Int,
    val messages: Int,
    val memories: Int,
    val lorebooks: Int,
    val npcDossiers: Int,
    val messageAttachments: Int,
    val profileImages: Int,
    val includesDrafts: Boolean,
    val includesCoopHistory: Boolean,
    val coopWillRestoreOffline: Boolean,
    val scenarios: Int = 0,
    val campaigns: Int = 0,
    val campaignJournalEntries: Int = 0,
    val campaignContinuityItems: Int = 0,
    val campaignMemoryShares: Int = 0
)

data class BackupInspection(
    val preview: BackupPreview,
    val encryptedSha256: String
)

data class BackupRestoreResult(
    val restoredChats: Int,
    val restoredMessages: Int,
    val restoredFiles: Int,
    val safetyBackupAvailable: Boolean
)

class BackupValidationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal data class BackupBinarySource(
    val manifest: BackupFileManifest,
    val file: java.io.File
)

internal data class BackupSnapshot(
    val manifest: BackupManifest,
    val payload: BackupPayload,
    val payloadJson: String,
    val binaries: List<BackupBinarySource>,
    val stagingDirectory: java.io.File
)

internal data class ValidatedBackup(
    val manifest: BackupManifest,
    val payload: BackupPayload,
    val extractedFiles: Map<String, java.io.File>,
    val workingDirectory: java.io.File,
    val encryptedSha256: String? = null
)

internal enum class BackupSnapshotMode {
    PORTABLE,
    EXACT_PRIVATE_RECOVERY
}

internal enum class RestoreStage {
    AFTER_SAFETY_BACKUP,
    AFTER_FILES_STAGED,
    AFTER_DATABASE_REPLACED,
    AFTER_PROFILE_IMAGES_REPLACED
}
