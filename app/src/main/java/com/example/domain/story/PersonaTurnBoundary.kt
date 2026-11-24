package com.example.domain.story

/**
 * Offline response-time check for explicit assistant-authored Persona speaker turns.
 *
 * Never guess intent from ordinary third-person narration ("Morax looked at Hazel"):
 * such prose may describe an already player-authored action. This guard only
 * recognizes unambiguous speaker headings and explicit structured Persona turns.
 * A hit blocks AUTO-persistence; the existing Keep/Discard Partial UI is retained
 * so the player, not the model, decides what to do with the unsaved text.
 */
object PersonaTurnBoundary {
    private val explicitRole = Regex(
        """^\s*(?:Persona|User)\s*(?:\([^)\r\n]{1,120}\))?\s*:\s*\S.*$""",
        RegexOption.IGNORE_CASE
    )
    private val structuredPersonaType = Regex(
        """^\s*<\|segment\|>\s*\{.*"type"\s*:\s*"(?:persona|user)".*$""",
        RegexOption.IGNORE_CASE
    )

    fun hasExplicitAuthoredPersonaTurn(output: String, personaNames: Collection<String>): Boolean {
        val names = personaNames.map(String::trim)
            .filter(String::isNotEmpty).distinctBy(String::lowercase)
        for (line in output.lineSequence()) {
            if (explicitRole.matches(line) || structuredPersonaType.matches(line)) return true
            if (names.any { name ->
                val escaped = Regex.escape(name)
                Regex("""^\s*$escaped\s*:\s*\S.*$""", RegexOption.IGNORE_CASE).matches(line) ||
                    Regex(
                        """^\s*(?:NPC|Character|Persona|User)\s*\(\s*$escaped\s*\)\s*:\s*\S.*$""",
                        RegexOption.IGNORE_CASE
                    ).matches(line) ||
                    Regex(
                        """^\s*<\|segment\|>\s*\{.*"speaker_name"\s*:\s*"$escaped"\s*[,}].*$""",
                        RegexOption.IGNORE_CASE
                    ).matches(line)
            }) return true
        }
        return false
    }
}
