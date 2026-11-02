package com.example.domain.npc

import com.example.data.model.MessageEntity
import com.example.data.model.NpcDossier
import com.example.data.model.NpcSceneMode
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
        val recent = recentMessages.filter { it.isPrimaryVariant }.takeLast(24)
            .map { StoryResponseCodec.promptTranscript(NpcStructuredOutputParser.finalVisibleStoryText(it.content)) }
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

    private fun infer(npc: NpcDossier, recent: List<String>): NpcScenePresence {
        val aliases = (listOf(npc.displayName) + npc.aliases)
            .filter(String::isNotBlank).distinctBy { it.lowercase(Locale.ROOT) }
        for (content in recent.asReversed()) {
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
        }
        return NpcScenePresence.UNKNOWN
    }
}
