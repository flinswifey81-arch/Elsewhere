package com.example.phonelab.systems

enum class ContactRole { LEAD, NPC }
enum class Speaker { PERSONA, CONTACT }
enum class Destination { STORY_MEMORY, PLAYTHROUGH_LORE, WORLD_CANON_DRAFT }

/** Resolve these stable identities from local campaign records, never from display names. */
data class PhoneScope(val worldId: String, val campaignId: String, val personaId: String) {
    init { stableId(worldId); stableId(campaignId); stableId(personaId) }
}
data class ThreadKey(val scope: PhoneScope, val contactId: String) {
    init { stableId(contactId); require(scope.personaId != contactId) { "Cannot text yourself" } }
}
data class PhoneMessage(val id: String, val chapter: Int, val speaker: Speaker, val text: String, val timestamp: Long)
data class PhoneCarry(
    val id: String, val thread: ThreadKey, val destination: Destination, val approvedText: String,
    val sourceMessageIds: List<String>, val chapter: Int
) {
    fun storyText(viewer: PhoneScope, actorId: String, cutoff: Int) = approvedText.takeIf {
        destination == Destination.STORY_MEMORY && thread.scope == viewer && chapter <= cutoff &&
            actorId in setOf(thread.scope.personaId, thread.contactId)
    }
    fun loreFor(viewer: PhoneScope, cutoff: Int) = approvedText.takeIf {
        destination == Destination.PLAYTHROUGH_LORE && chapter <= cutoff &&
            thread.scope.worldId == viewer.worldId && thread.scope.campaignId == viewer.campaignId
    }
}
internal fun stableId(value: String): String = value.also {
    require(it.isNotBlank() && it.length <= 128 && '\u0000' !in it) { "Invalid stable identifier" }
}
internal fun boundedText(value: String, maxLength: Int): String = value.also {
    require(it.isNotBlank() && it.length <= maxLength && '\u0000' !in it) {
        "Text must contain 1 to $maxLength characters"
    }
}
internal fun chapter(value: Int): Int = value.also { require(it >= 1) { "Missing chapter" } }
