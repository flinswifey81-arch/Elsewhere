package com.example.domain.world

/**
 * A read-only view of a fully authenticated World archive. No imported records are saved
 * until a separate, explicit, transactional restore path is available.
 *
 * Do NOT display private NPC notes, message text, draft content or secrets in a notification
 * or generic file-picker preview. The UI can show these inclusion flags after unlock.
 */
data class WorldArchiveImportPreview(
    val worldId: String,
    val worldTitle: String,
    val chats: Int,
    val messages: Int,
    val primaryResponses: Int,
    val alternativeVariants: Int,
    val characters: Int,
    val personas: Int,
    val scenarios: Int,
    val npcDossiers: Int,
    val npcRevisions: Int,
    val lorebooks: Int,
    val continuityEvents: Int,
    val messageAttachments: Int,
    val otherImageFiles: Int,
    val uncompressedArchiveBytes: Long,
    val containsUnsentDrafts: Boolean,
    val containsPrivateNotes: Boolean,
    val containsNpcSecrets: Boolean
) {
    companion object {
        fun from(state: WorldArchiveStateV1, manifest: WorldArchiveManifestV1): WorldArchiveImportPreview {
            WorldArchiveStateCodec.validateWithManifest(state, manifest)
            return WorldArchiveImportPreview(
                worldId = state.worldId,
                worldTitle = state.world.title,
                chats = state.chats.size,
                messages = state.messages.size,
                primaryResponses = state.messages.count { it.isPrimaryVariant },
                alternativeVariants = state.messages.count { !it.isPrimaryVariant },
                characters = state.characters.size,
                personas = state.personas.size,
                scenarios = state.scenarios.size,
                npcDossiers = state.npcDossiers.size,
                npcRevisions = state.npcUpdates.size,
                lorebooks = state.lorebooks.size,
                continuityEvents = state.continuity.size,
                messageAttachments = state.messageAttachments.size,
                otherImageFiles = manifest.entries.count {
                    it.kind in setOf(
                        WorldArchiveEntryKind.CHARACTER_AVATAR,
                        WorldArchiveEntryKind.PERSONA_AVATAR,
                        WorldArchiveEntryKind.SCENARIO_AVATAR,
                        WorldArchiveEntryKind.NPC_PORTRAIT
                    )
                },
                uncompressedArchiveBytes = manifest.entries.sumOf { it.byteSize },
                containsUnsentDrafts = state.chatSettings.any { !it.draftMessage.isNullOrBlank() } ||
                    state.messageAttachments.any { it.messageId == null },
                containsPrivateNotes = state.characters.any { !it.authorNotesJson.isNullOrBlank() } ||
                    state.personas.any { !it.privateNotesJson.isNullOrBlank() } ||
                    state.npcDossiers.any { it.authorNotes.isNotBlank() } ||
                    state.npcLibraryEntries.any { it.authorNotes.isNotBlank() } ||
                    state.lorebookEntries.any { it.notes.isNotBlank() },
                containsNpcSecrets = state.npcDossiers.any { it.secrets.isNotBlank() } ||
                    state.npcLibraryEntries.any { it.secrets.isNotBlank() }
            )
        }
    }
}
