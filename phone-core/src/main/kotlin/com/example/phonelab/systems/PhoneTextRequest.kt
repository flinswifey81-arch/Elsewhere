package com.example.phonelab.systems

/** No source from the main story, another Persona or another contact is accepted by the store adapter. */
data class PhoneTextRequest(val instructions: String, val history: List<Turn>) {
    enum class Role { USER, ASSISTANT }
    data class Turn(val role: Role, val text: String)
    internal companion object {
        const val MAX_ITEMS = 24
        private const val MAX_TEXT = 12000

        fun compile(displayName: String, pending: String, ordered: List<PhoneMessage>): PhoneTextRequest {
            val last = requireNotNull(ordered.lastOrNull()) { "No text to answer" }
            require(last.speaker == Speaker.PERSONA && last.id == stableId(pending)) {
                "The pending Persona text is no longer the last message"
            }
            var used = 0
            val included = mutableListOf<Turn>()
            for (m in ordered.asReversed().take(MAX_ITEMS)) {
                if (used + m.text.length > MAX_TEXT) break
                included += Turn(if (m.speaker == Speaker.PERSONA) Role.USER else Role.ASSISTANT, m.text)
                used += m.text.length
            }
            require(included.isNotEmpty()) { "Text exceeds the request budget" }
            return PhoneTextRequest(
                "Reply only with the text your contact sends in this one-to-one conversation. " +
                    "Do not add action beats, stage directions, narration, or the Persona's dialogue. " +
                    "Do not claim to know private events from another conversation. " +
                    "Contact display name: ${boundedText(displayName, 120)}",
                included.asReversed().dropWhile { it.role == Role.ASSISTANT }
            )
        }
    }
}
