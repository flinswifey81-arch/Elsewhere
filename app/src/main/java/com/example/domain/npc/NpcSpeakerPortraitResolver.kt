package com.example.domain.npc

import com.example.data.model.NpcDossier
import java.util.Locale

/** Maps old attributed NPC segments to the matching dossier in this chat. */
object NpcSpeakerPortraitResolver {
    fun resolveId(speakerId: String?, speakerName: String?, dossiers: List<NpcDossier>): String? {
        val name = speakerName?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (name.isNotEmpty() && name != "supporting character") {
            val exact = dossiers.filter { it.displayName.trim().lowercase(Locale.ROOT) == name }
            if (exact.size == 1) return exact.single().npcId
            if (exact.isNotEmpty()) return null
            val aliases = dossiers.filter { npc ->
                npc.aliases.any { it.trim().lowercase(Locale.ROOT) == name }
            }
            return aliases.singleOrNull()?.npcId
        }
        return dossiers.singleOrNull { it.npcId == speakerId }?.npcId
    }
}
