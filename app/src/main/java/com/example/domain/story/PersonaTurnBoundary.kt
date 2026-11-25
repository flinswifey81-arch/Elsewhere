package com.example.domain.story

/**
 * Offline response-time check for explicit assistant-authored Persona speaker turns.
 *
 * Never guess intent from ordinary third-person narration ("Morax looked at Hazel"):
 * such prose may describe an already player-authored action. This guard only
 * recognizes unambiguous speaker labels and high-confidence authored Persona prose.
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
                    clearlyAuthoredPersonaActionOrSpeech(line, escaped) ||
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

    /**
     * High-confidence direct authoring outside the structured-response contract.
     * Never match a passing reference such as "Morax watched Hazel move".
     * Avoid guessing about unmarked third-person narration: it may be describing
     * the player's already-established actions rather than inventing a new turn.
     */
    private fun clearlyAuthoredPersonaActionOrSpeech(line: String, escapedName: String): Boolean {
        val verbs = "(?:said|says|asked|asks|answered|answers|replied|replies|whispered|whispers|" +
            "murmured|murmurs|shouted|shouts|exclaimed|exclaims|declared|declares)"
        val explicitItalicAction = Regex(
            """^\s*[*_]$escapedName\s+.{1,500}[*_]\s*$""",
            RegexOption.IGNORE_CASE
        )
        // Roleplay prose can use a named action beat in place of a dialogue
        // tag: “What?” *Hazel pointed at the stall.* “My breakfast?”
        // Require the player to be the subject of the beat, not its object.
        val quote = """(?:"[^"\r\n]{1,300}"|“[^”\r\n]{1,300}”)"""
        // Search within a mixed paragraph, not just when the player's beat
        // occupies a whole line. The beat must directly name the player.
        val quotedPersonaActionBeat = Regex(
            """$quote[ \t]*([*_])$escapedName[ \t]+[^*_\r\n]{1,500}\1[ \t]*$quote""",
            RegexOption.IGNORE_CASE
        )
        // A possessive speech cue can attribute a quote just as clearly as
        // a named subject. Limit it to voice/cadence verbs; a reference to
        // the player's coat, hand or earlier gesture is not a speaker tag.
        val personaVoiceCue =
            """$escapedName['’]s[ \t]+voice[ \t]+(?:rose|rises|fell|falls|cracked|cracks|sharpened|sharpens|softened|softens|dropped|drops|cut[ \t]+in|cuts[ \t]+in)(?:[ 	]+(?:slightly|sharply|softly|quietly))?[.!?]?"""
        val quoteWithPersonaVoice = Regex(
            """$quote[,!?]?[ \t]*([*_])$personaVoiceCue\1[ \t]*$quote""",
            RegexOption.IGNORE_CASE
        )
        val quotedPersonaSpeech = Regex(
            """$quote[,!?]?[ \t]+$escapedName[ \t]+$verbs(?=[ \t]*[.!?…]|[ \t]*$|[ \t]+(?:softly|quietly|calmly)\b)""",
            RegexOption.IGNORE_CASE
        )
        val personaSpeechFirst = Regex(
            """(?:^|[.!?…]["”]?[ \t]+)(?:[*_])?$escapedName[ \t]+$verbs\b[ \t]*[,.:]?[ \t]*(?:[*_][ \t]*)?$quote""",
            RegexOption.IGNORE_CASE
        )
        val quotedPersonaSpeechWithMarkdown = Regex(
            """^$quote[,!?]?[ \t]*[*_]$escapedName[ \t]+$verbs\b[^*_\r\n]*[*_][ \t]*$""",
            RegexOption.IGNORE_CASE
        )
        val leadingPersonaSpeech = Regex(
            """^\s*$escapedName\s+$verbs\b[^\r\n]*["“].*$""",
            RegexOption.IGNORE_CASE
        )
        val trailingPersonaSpeech = Regex(
            """^\s*$quote[,!?]?\s*$escapedName\s+$verbs\b.*$""",
            RegexOption.IGNORE_CASE
        )
        return explicitItalicAction.matches(line) ||
            leadingPersonaSpeech.matches(line) ||
            trailingPersonaSpeech.matches(line) ||
            quotedPersonaActionBeat.containsMatchIn(line) ||
            quoteWithPersonaVoice.containsMatchIn(line) ||
            quotedPersonaSpeech.containsMatchIn(line) ||
            personaSpeechFirst.containsMatchIn(line) ||
            quotedPersonaSpeechWithMarkdown.matches(line)
    }
}
