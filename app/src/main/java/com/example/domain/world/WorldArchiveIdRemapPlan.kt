package com.example.domain.world

import com.example.data.model.ScenarioCodec
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Namespaces in a selective World import are deliberately separate. A Character and a Persona
 * with the same old ID are NOT the same role or person. Names are never identity keys.
 */
/** Length prefixes prevent collisions when custom Scenario/NPC IDs contain separators. */
private fun scopedScenarioNpcKey(scenarioId: String, npcId: String): String =
    "${scenarioId.length}:$scenarioId${npcId.length}:$npcId"

enum class WorldArchiveIdKind {
    WORLD,
    WORLD_CAST,
    CONTINUITY_EVENT,
    CHARACTER,
    PERSONA,
    SCENARIO,
    SCENARIO_NPC,
    CHAT,
    MESSAGE,
    MEMORY,
    STORY_MEMORY,
    VARIANT_GROUP,
    RELATIONSHIP,
    LOREBOOK,
    LOREBOOK_ENTRY,
    NPC_DOSSIER,
    NPC_REVISION,
    NPC_LIBRARY_BANK,
    NPC_LIBRARY_ENTRY,
    MESSAGE_ATTACHMENT
}

data class WorldArchiveIdRemapPlan(
    val namespace: String,
    val mappings: Map<WorldArchiveIdKind, Map<String, String>>
) {
    fun get(kind: WorldArchiveIdKind, sourceId: String): String =
        requireNotNull(mappings[kind]?.get(sourceId)) {
            "No validated World archive ID mapping for $kind"
        }

    /** A generation metadata record has the SAME identity as its source message. */
    fun metadataMessageId(sourceMessageId: String): String = get(WorldArchiveIdKind.MESSAGE, sourceMessageId)

    /** Some older variants use the original message ID for their variant group identity. */
    fun variantGroupId(sourceGroupId: String): String = get(WorldArchiveIdKind.VARIANT_GROUP, sourceGroupId)

    /** Scenario NPC IDs only have meaning within their own Scenario template. */
    fun scenarioNpcId(scenarioId: String, npcId: String): String =
        get(WorldArchiveIdKind.SCENARIO_NPC, scopedScenarioNpcKey(scenarioId, npcId))
}

/**
 * Pure mapping PLAN ONLY. It does not mutate Room entities, edit story-segment JSON, rewrite
 * Scenario NPC source references, import images or establish cross-world same-person identity.
 *
 * Once the user chooses "Import as new World" or an explicit merge resolution, generate ONE
 * new unpredictable namespace for that operation and save it with the staged import plan.
 * Re-planning the same operation must yield identical IDs, even after interruption.
 *
 * The importer must apply every mapping to typed and embedded JSON references atomically
 * after validation; it must never do global text search-and-replace in RP prose.
 */
object WorldArchiveIdPlanner {
    private val extensionAdapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    private fun origin(json: String?): String? =
        json?.let { runCatching { extensionAdapter.fromJson(it) }.getOrNull() }
            ?.get("_elsewhere_promoted_npc_id") as? String

    fun plan(
        state: WorldArchiveStateV1,
        namespace: String,
        reservedIds: Map<WorldArchiveIdKind, Set<String>> = emptyMap()
    ): WorldArchiveIdRemapPlan {
        WorldArchiveStateCodec.validate(state)
        require(namespace.length in 16..256 && namespace.none { it.code < 33 }) {
            "Import needs a unique, durable operation namespace"
        }
        // Deleted source messages and superseded memory predecessors may be retained
        // as historical provenance. Give their ORPHAN references fresh identities too,
        // without manufacturing any missing Room rows or turning them eligible.
        val messages = (
            state.messages.map { it.messageId } +
                state.storyMemories.mapNotNull { it.sourceMessageId }
        ).toSet()
        val variantGroups = (
            state.messages.map { it.variantGroupId } +
                state.storyMemories.mapNotNull { it.sourceVariantGroupId }
        ).toSet()
        val storyMemoryIds = (
            state.storyMemories.map { it.memoryId } +
                state.storyMemories.mapNotNull { it.previousStateMemoryId }
        ).toSet()
        val scenarioNpcIds = (
            state.scenarios.flatMap { scenario ->
                ScenarioCodec.decode(scenario.definitionJson).npcs.map { npc ->
                    scopedScenarioNpcKey(scenario.scenarioId, npc.npcId)
                }
            } + state.scenarioSessions.flatMap { session ->
                ScenarioCodec.decode(session.definitionJson).npcs.map { npc ->
                    scopedScenarioNpcKey(session.scenarioId, npc.npcId)
                }
            }
            ).toSet()

        val sourceIds: Map<WorldArchiveIdKind, Set<String>> = linkedMapOf(
            WorldArchiveIdKind.WORLD to setOf(state.worldId),
            WorldArchiveIdKind.WORLD_CAST to state.cast.map { it.worldCharacterId }.toSet(),
            WorldArchiveIdKind.CONTINUITY_EVENT to state.continuity.map { it.eventId }.toSet(),
            WorldArchiveIdKind.CHARACTER to (
                state.characters.map { it.characterId } +
                    state.storyMemories.filter { it.sourceSpeakerType == "CHARACTER" }
                        .mapNotNull { it.sourceSpeakerId }
            ).toSet(),
            WorldArchiveIdKind.PERSONA to (
                state.personas.map { it.personaId } +
                    state.storyMemories.filter { it.sourceSpeakerType == "PERSONA" }
                        .mapNotNull { it.sourceSpeakerId }
            ).toSet(),
            WorldArchiveIdKind.SCENARIO to state.scenarios.map { it.scenarioId }.toSet(),
            WorldArchiveIdKind.SCENARIO_NPC to scenarioNpcIds,
            WorldArchiveIdKind.CHAT to state.chats.map { it.chatId }.toSet(),
            WorldArchiveIdKind.MESSAGE to messages,
            WorldArchiveIdKind.MEMORY to state.roleplayMemories.map { it.memoryId }.toSet(),
            WorldArchiveIdKind.STORY_MEMORY to storyMemoryIds,
            WorldArchiveIdKind.RELATIONSHIP to state.relationships.map { it.relationshipId }.toSet(),
            WorldArchiveIdKind.LOREBOOK to state.lorebooks.map { it.lorebookId }.toSet(),
            WorldArchiveIdKind.LOREBOOK_ENTRY to state.lorebookEntries.map { it.entryId }.toSet(),
            WorldArchiveIdKind.NPC_DOSSIER to state.npcDossiers.map { it.npcId }.toSet(),
            WorldArchiveIdKind.NPC_REVISION to state.npcUpdates.map { it.updateId }.toSet(),
            WorldArchiveIdKind.NPC_LIBRARY_BANK to state.npcLibraryBanks.map { it.bankId }.toSet(),
            WorldArchiveIdKind.NPC_LIBRARY_ENTRY to state.npcLibraryEntries.map { it.libraryNpcId }.toSet(),
            WorldArchiveIdKind.MESSAGE_ATTACHMENT to state.messageAttachments.map { it.attachmentId }.toSet()
        )

        fun derive(kind: WorldArchiveIdKind, value: String): String =
            UUID.nameUUIDFromBytes(
                "$namespace|${kind.name}|$value".toByteArray(StandardCharsets.UTF_8)
            ).toString()

        val mappings = linkedMapOf<WorldArchiveIdKind, Map<String, String>>()
        for ((kind, originals) in sourceIds) {
            val mapped = originals.sorted().associateWith { derive(kind, it) }
            require(mapped.values.toSet().size == mapped.size) { "World import ID collision" }
            require(mapped.values.none { it in reservedIds[kind].orEmpty() }) {
                "World import would overwrite an existing $kind"
            }
            mappings[kind] = mapped
        }

        // An NPC promoted to a Character is ONE underlying identity with the
        // same source ID, explicitly attested by app-authored origin metadata.
        // Give BOTH imported tables the identical new ID so the link survives
        // without introducing a duplicate person.
        val promotedIds = state.characters.filter { character ->
            origin(character.extensionFieldsJson) == character.characterId &&
                state.npcDossiers.any { it.npcId == character.characterId }
        }.map { it.characterId }.toSet()
        if (promotedIds.isNotEmpty()) {
            val originalNpc = requireNotNull(mappings[WorldArchiveIdKind.NPC_DOSSIER])
            val originalCharacters = requireNotNull(mappings[WorldArchiveIdKind.CHARACTER])
            val aligned = originalNpc.toMutableMap()
            promotedIds.forEach { source ->
                aligned[source] = originalCharacters.getValue(source)
            }
            require(aligned.values.toSet().size == aligned.size) {
                "Promoted Character/NPC identity collision in World import"
            }
            require(aligned.values.none {
                it in reservedIds[WorldArchiveIdKind.NPC_DOSSIER].orEmpty()
            }) { "Promoted NPC identity would overwrite a destination NPC" }
            mappings[WorldArchiveIdKind.NPC_DOSSIER] = aligned
        }

        val remappedMessages = requireNotNull(mappings[WorldArchiveIdKind.MESSAGE])
        // Compute this once: a large World can contain tens of thousands of variants.
        val selfNamedVariantGroups = (
            state.messages.asSequence()
                .filter { it.messageId == it.variantGroupId }
                .map { it.variantGroupId } +
                state.storyMemories.asSequence()
                    .filter { it.sourceMessageId != null &&
                        it.sourceMessageId == it.sourceVariantGroupId }
                    .mapNotNull { it.sourceVariantGroupId }
        ).toSet()
        val variantMap = variantGroups.sorted().associateWith { original ->
            // Only alias to the mapped message when that message really belongs to this
            // variant group. An unrelated message ID with the same text is not its parent.
            if (original in selfNamedVariantGroups) remappedMessages.getValue(original)
            else derive(WorldArchiveIdKind.VARIANT_GROUP, original)
        }
        require(variantMap.values.toSet().size == variantMap.size) {
            "World variant groups would collide after ID remapping"
        }
        require(variantMap.values.none { it in reservedIds[WorldArchiveIdKind.VARIANT_GROUP].orEmpty() }) {
            "A World variant group ID already exists in the destination"
        }
        mappings[WorldArchiveIdKind.VARIANT_GROUP] = variantMap

        // A UUID-like ID used as the initial variantGroupId MUST share its newly mapped
        // message ID; all other record identities stay independently namespaced.
        val ownerOf = mutableMapOf<String, Pair<WorldArchiveIdKind, String>>()
        mappings.forEach { (kind, originals) ->
            originals.forEach { (old, fresh) ->
                val previous = ownerOf.putIfAbsent(fresh, kind to old)
                if (previous != null) {
                    require(
                        (kind == WorldArchiveIdKind.VARIANT_GROUP &&
                            previous == (WorldArchiveIdKind.MESSAGE to old)) ||
                        (kind == WorldArchiveIdKind.NPC_DOSSIER &&
                            previous == (WorldArchiveIdKind.CHARACTER to old) &&
                            old in promotedIds)
                    ) { "Two independent World archive resources would share an imported identity" }
                }
            }
        }
        return WorldArchiveIdRemapPlan(namespace, mappings)
    }
}
