package com.example.domain.npc

import com.example.domain.compiler.RecentPrimaryMessageWindow
import com.example.data.model.MessageEntity
import com.example.data.model.NpcDossier
import com.example.data.model.NpcSceneMode
import com.example.data.model.SpeakerType
import com.example.domain.story.StorySpeakerIdentity
import com.example.domain.story.StoryResponseCodec
import java.util.Locale

enum class NpcScenePresence { PRESENT, AWAY, UNKNOWN }

data class NpcSceneMember(
    val dossier: NpcDossier,
    val presence: NpcScenePresence,
    val manualOverride: Boolean
)

/** Conservative scene presence. Remembered NPCs do not become participants by default. */
object NpcSceneCastResolver {
    fun resolve(dossiers: List<NpcDossier>, recentMessages: List<MessageEntity>): List<NpcSceneMember> {
        val recent = RecentPrimaryMessageWindow.take(recentMessages, 24)
            .map { message ->
                val content = StoryResponseCodec.promptTranscript(
                    NpcStructuredOutputParser.finalVisibleStoryText(message.content)
                )
                // Reuse the same explicit spoken-NPC evidence that powers
                // the Bank's name-only dossier creation. Compute once per
                // recent message, not once per NPC, to bound prompt overhead.
                val attributedSpeakers = if (message.speakerType == SpeakerType.CHARACTER) {
                    NpcProseDiscovery.namedSpeakers(content, emptyList())
                        .map { it.lowercase(Locale.ROOT) }.toSet()
                } else emptySet()
                RecentSceneText(message, content, attributedSpeakers)
            }
        return dossiers.map { npc ->
            val mode = runCatching { NpcSceneMode.valueOf(npc.sceneMode) }.getOrDefault(NpcSceneMode.AUTO)
            val presence = when (mode) {
                NpcSceneMode.HERE -> NpcScenePresence.PRESENT
                NpcSceneMode.AWAY -> NpcScenePresence.AWAY
                NpcSceneMode.AUTO -> infer(npc, recent)
            }
            NpcSceneMember(npc, presence, mode != NpcSceneMode.AUTO)
        }
    }

    private data class RecentSceneText(
        val message: MessageEntity,
        val content: String,
        val attributedSpeakers: Set<String>
    )

    private fun infer(npc: NpcDossier, recent: List<RecentSceneText>): NpcScenePresence {
        val aliases = (listOf(npc.displayName) + npc.aliases)
            .filter(String::isNotBlank).distinctBy { it.lowercase(Locale.ROOT) }
        for ((message, content, attributedSpeakers) in recent.asReversed()) {
            val cues = aliases.flatMap { alias ->
                val name = "(?<![\\p{L}\\p{N}_])" + Regex.escape(alias) + "(?![\\p{L}\\p{N}_])"
                val gone = Regex(
                    "$name\\s+(?:leaves|left|exits|exited|departed|departs|walks?\\s+(?:away|out)|steps?\\s+out|went\\s+(?:home|away)|is\\s+(?:away|absent|gone|not\\s+here)|isn't\\s+here|has\\s+(?:left|gone))\\b",
                    RegexOption.IGNORE_CASE
                )
                val here = Regex(
                    "$name\\s+(?:is\\s+(?:here|present|with\\s+(?:us|them|me))|arrives?|arrived|enters?|entered|joins?|joined|comes?\\s+in|walks?\\s+in|steps?\\s+in|sits?|stands?|speaks?|says?|asks?|replies?|nods?|smiles?|laughs?)\\b",
                    RegexOption.IGNORE_CASE
                )
                gone.findAll(content).map { it.range.first to NpcScenePresence.AWAY }.toList() +
                    here.findAll(content).map { it.range.first to NpcScenePresence.PRESENT }.toList()
            }
            val latest = cues.maxByOrNull { it.first }?.second
            if (latest != null) return latest
            // Direct speech attribution is evidence of physical participation
            // even when the model skipped structured NPC speaker markers.
            if (aliases.any { it.lowercase(Locale.ROOT) in attributedSpeakers }) {
                return NpcScenePresence.PRESENT
            }
            // An attributed, persisted NPC dialogue segment confirms participation.
            // Plain mentions and Character/Narrator prose do not.
            val segments = if (message.speakerType == SpeakerType.CHARACTER) {
                StoryResponseCodec.recoverForDisplay(
                    message.content,
                    StorySpeakerIdentity(message.speakerId.orEmpty(), message.speakerDisplayNameSnapshot),
                    listOf(StorySpeakerIdentity(npc.npcId, npc.displayName))
                )?.segments.orEmpty()
            } else StoryResponseCodec.decode(message.content)?.segments.orEmpty()
            if (segments.any { segment ->
                segment.type == com.example.domain.story.StorySegmentType.NPC &&
                    (segment.speakerId == npc.npcId ||
                        aliases.any { it.equals(segment.speakerName, ignoreCase = true) })
            }) return NpcScenePresence.PRESENT
        }
        return NpcScenePresence.UNKNOWN
    }
}
