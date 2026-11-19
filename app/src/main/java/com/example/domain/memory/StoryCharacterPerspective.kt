package com.example.domain.memory

/**
 * The player's explicit in-fiction viewpoint for a specific character and
 * story memory. This is NOT the recipient authorization list (KNOWS rows).
 *
 * A belief/suspicion is never made authoritative by an assistant inference.
 * UNAWARE is a hard deny even if an old permission row remains present.
 */
enum class StoryCharacterPerspective {
    KNOWS,
    BELIEVES,
    SUSPECTS,
    UNAWARE
}

/** Only eligible Character retrieval receives this label; never World canon. */
fun StoryCharacterPerspective.wrapForCharacter(content: String): String = when (this) {
    StoryCharacterPerspective.KNOWS -> content
    StoryCharacterPerspective.BELIEVES ->
        "Character belief (subjective; possibly mistaken, NOT established fact): $content"
    StoryCharacterPerspective.SUSPECTS ->
        "Character suspicion (unconfirmed; NOT established fact): $content"
    StoryCharacterPerspective.UNAWARE -> error("Unaware memories must be excluded before formatting.")
}
