package com.example.domain.compiler

import com.example.data.model.ChatParticipantEntity
import com.example.data.model.ChatType
import com.example.data.model.CharacterEntity
import com.example.domain.story.StorySpeakerIdentity

/** Natural is a local Solo option, not a change to the Group/co-op speaker contract. */
internal object NaturalSoloCast {
    fun enabled(chatType: ChatType, preference: Boolean, inCoop: Boolean): Boolean =
        chatType == ChatType.SOLO && preference && !inCoop

    fun anchor(participants: List<ChatParticipantEntity>, natural: Boolean): String? {
        val ordering = if (natural) {
            // Spotlight reorders sortPosition. Using join order prevents a prior manual
            // selection from getting extra lead context when the player switches modes.
            compareBy<ChatParticipantEntity> { it.joinedAt }.thenBy { it.characterId }
        } else compareBy<ChatParticipantEntity> { it.sortPosition }.thenBy { it.joinedAt }
        return participants.filter { it.isActive }.minWithOrNull(ordering)?.characterId
    }

    /** Only authored portrayal fields; omit card secrets, private notes and other chat data. */
    fun rosterContext(cards: List<CharacterEntity>): String? {
        if (cards.isEmpty()) return null
        return buildString {
            appendLine("[OTHER CAST MEMBERS — roster, not proof of presence]")
            appendLine("Use a cast member only when already present or following a scene-motivated entrance. " +
                "Never write the player's turn or bring in a cast member merely to rotate the spotlight.")
            cards.forEach { card ->
                appendLine("[NPC id=${card.characterId}] ${card.displayName.take(160)}")
                listOf(
                    "Identity" to card.identityJson, "Personality" to card.personalityJson,
                    "Voice" to card.voiceJson, "Behavior" to card.behaviorJson
                ).forEach { (label, value) ->
                    value?.takeIf(String::isNotBlank)?.let { appendLine("$label: ${it.take(600)}") }
                }
            }
        }.trimEnd()
    }

    fun roster(
        participants: List<ChatParticipantEntity>,
        anchorId: String,
        coveredIds: Set<String>
    ): List<String> = participants.asSequence()
        .filter { it.isActive && it.characterId != anchorId && it.characterId !in coveredIds }
        .sortedWith(compareBy<ChatParticipantEntity> { it.joinedAt }.thenBy { it.characterId })
        .map { it.characterId }
        .take(8)
        .toList()

    fun identity(card: CharacterEntity) = StorySpeakerIdentity(card.characterId, card.displayName)
}
