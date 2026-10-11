package com.example.domain.npc

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi

data class ParsedNpcOutput(
    val storyText: String,
    val updates: List<NpcStructuredUpdate> = emptyList(),
    val warning: String? = null
)

data class NpcStructuredUpdate(
    val npcId: String?,
    val displayName: String?,
    val aliases: List<String>?,
    val role: String?,
    val appearance: String?,
    val personality: String?,
    val relationshipToPersona: String?,
    val goals: String?,
    val secrets: String?
)

@JsonClass(generateAdapter = true)
internal data class NpcUpdateEnvelope(
    @Json(name = "schema_version") val schemaVersion: Int,
    val updates: List<NpcUpdateJson>
)

@JsonClass(generateAdapter = true)
internal data class NpcUpdateJson(
    @Json(name = "npc_id") val npcId: String? = null,
    @Json(name = "display_name") val displayName: String? = null,
    val aliases: List<String>? = null,
    val role: String? = null,
    val appearance: String? = null,
    val personality: String? = null,
    @Json(name = "relationship_to_persona") val relationshipToPersona: String? = null,
    val goals: String? = null,
    val secrets: String? = null
)

class NpcStructuredOutputParser(moshi: Moshi) {
    private val adapter = moshi.adapter(NpcUpdateEnvelope::class.java).failOnUnknown()

    fun parse(output: String): ParsedNpcOutput {
        val markerIndex = terminalControlRegionStart(
            output,
            allowShortStreamingPrefix = false,
            allowExactMarkerAnywhere = false
        )
            ?: return ParsedNpcOutput(output)
        val story = output.substring(0, markerIndex).trimEnd()
        if (!output.regionMatches(markerIndex, OPEN_MARKER, 0, OPEN_MARKER.length)) {
            return ParsedNpcOutput(story, warning = "Malformed NPC metadata was removed and ignored.")
        }
        if (output.length - markerIndex > MAX_TRAILING_REGION_CHARS) {
            return ParsedNpcOutput(story, warning = "NPC metadata was too large and was ignored.")
        }
        val closeIndex = output.indexOf(CLOSE_MARKER, markerIndex + OPEN_MARKER.length)
        if (closeIndex < 0) {
            return ParsedNpcOutput(story, warning = "Malformed NPC metadata was removed and ignored.")
        }
        val trailing = output.substring(closeIndex + CLOSE_MARKER.length)
        if (trailing.isNotBlank()) {
            return ParsedNpcOutput(story, warning = "NPC metadata was not terminal and was removed and ignored.")
        }
        val json = output.substring(markerIndex + OPEN_MARKER.length, closeIndex).trim()
        if (json.length > MAX_JSON_CHARS) {
            return ParsedNpcOutput(story, warning = "NPC metadata was too large and was ignored.")
        }
        return try {
            val envelope = requireNotNull(adapter.fromJson(json)) { "Empty NPC metadata" }
            require(envelope.schemaVersion == SCHEMA_VERSION) { "Unsupported NPC metadata schema" }
            require(envelope.updates.size <= MAX_UPDATES) { "Too many NPC updates" }
            val updates = envelope.updates.map(::validated)
            ParsedNpcOutput(story, updates)
        } catch (_: Exception) {
            ParsedNpcOutput(story, warning = "Malformed NPC metadata was removed and ignored.")
        }
    }

    private fun validated(value: NpcUpdateJson): NpcStructuredUpdate {
        val npcId = value.npcId?.trim()?.takeIf(String::isNotEmpty)?.also {
            require(it.length <= 80 && SAFE_ID.matches(it)) { "Invalid NPC id" }
        }
        val name = clean(value.displayName, 120)
        require(npcId != null || name != null) { "A new NPC needs a display name" }
        if (npcId != null) {
            require(
                value.displayName == null &&
                    value.aliases == null &&
                    value.appearance == null &&
                    value.personality == null
            ) { "Existing NPC identity and core profile require an explicit manual edit" }
        }
        val aliases = value.aliases?.also { list ->
            require(list.size <= 20) { "Too many aliases" }
            list.forEach { require(it.isNotBlank() && it.length <= 120) { "Invalid alias" } }
        }?.map(String::trim)?.distinct()
        val result = NpcStructuredUpdate(
            npcId = npcId,
            displayName = name,
            aliases = aliases,
            role = clean(value.role),
            appearance = clean(value.appearance),
            personality = clean(value.personality),
            relationshipToPersona = clean(value.relationshipToPersona),
            goals = clean(value.goals),
            secrets = clean(value.secrets)
        )
        require(
            listOf(
                result.displayName,
                result.aliases,
                result.role,
                result.appearance,
                result.personality,
                result.relationshipToPersona,
                result.goals,
                result.secrets
            ).any { it != null }
        ) { "Empty NPC update" }
        return result
    }

    private fun clean(value: String?, max: Int = MAX_FIELD_CHARS): String? =
        value?.trim()?.takeIf(String::isNotEmpty)?.also { require(it.length <= max) { "NPC field is too long" } }

    companion object {
        const val OPEN_MARKER = "<elsewhere_npc_updates>"
        const val CLOSE_MARKER = "</elsewhere_npc_updates>"
        const val SCHEMA_VERSION = 1
        const val MAX_UPDATES = 24
        const val MAX_FIELD_CHARS = 4000
        const val MAX_JSON_CHARS = 32_768
        private const val MAX_TRAILING_REGION_CHARS = 40_000
        private val SAFE_ID = Regex("[A-Za-z0-9._:-]+")
        private const val OPEN_TAG_NAME = "elsewhere_npc_updates"
        private const val MIN_FINAL_TRUNCATED_TAG_CHARS = 15

        /**
         * Hides only a terminal, line-delimited NPC control region. Exact complete tags are
         * parsed by [parse]; misspelled or truncated lookalikes are display-only sanitation
         * and can never produce updates.
         */
        fun visibleStoryText(output: String): String =
            terminalControlRegionStart(
                output,
                allowShortStreamingPrefix = true,
                allowExactMarkerAnywhere = true
            )
                ?.let { output.substring(0, it).trimEnd() }
                ?: output

        /** Final/persisted cleanup does not hide ambiguous one-character streaming prefixes. */
        fun finalVisibleStoryText(output: String): String =
            terminalControlRegionStart(
                output,
                allowShortStreamingPrefix = false,
                allowExactMarkerAnywhere = false
            )
                ?.let { output.substring(0, it).trimEnd() }
                ?: output

        private fun terminalControlRegionStart(
            output: String,
            allowShortStreamingPrefix: Boolean,
            allowExactMarkerAnywhere: Boolean
        ): Int? {
            if (allowExactMarkerAnywhere) {
                val exactMarkerIndex = output.lastIndexOf(OPEN_MARKER)
                if (exactMarkerIndex >= 0 && looksLikeTerminalControlRegion(
                        output.substring(exactMarkerIndex),
                        allowShortStreamingPrefix
                    )
                ) {
                    return exactMarkerIndex
                }
            }
            var lineStart = 0
            while (lineStart < output.length) {
                var markerStart = lineStart
                while (markerStart < output.length &&
                    (output[markerStart] == ' ' || output[markerStart] == '\t')
                ) {
                    markerStart++
                }
                if (looksLikeTerminalControlRegion(
                        output.substring(markerStart),
                        allowShortStreamingPrefix
                    )
                ) {
                    return markerStart
                }
                val nextLine = output.indexOf('\n', lineStart)
                if (nextLine < 0) break
                lineStart = nextLine + 1
            }
            return null
        }

        private fun looksLikeTerminalControlRegion(
            region: String,
            allowShortStreamingPrefix: Boolean
        ): Boolean {
            if (!region.startsWith('<')) return false
            val firstLineEnd = region.indexOf('\n').let { if (it < 0) region.length else it }
            val firstLine = region.substring(0, firstLineEnd).trimEnd('\r', ' ', '\t')
            val tagEnd = firstLine.indexOf('>')
            if (tagEnd < 0) {
                val token = firstLine.lowercase()
                if (region.length != firstLineEnd || token.any(Char::isWhitespace)) return false
                return (allowShortStreamingPrefix && OPEN_MARKER.startsWith(token, ignoreCase = true)) ||
                    (token.startsWith("<elsew") &&
                        token.length <= OPEN_MARKER.length + 4 &&
                        (allowShortStreamingPrefix || token.length >= MIN_FINAL_TRUNCATED_TAG_CHARS))
            }

            val rawTagName = firstLine.substring(1, tagEnd)
            val isClosing = rawTagName.startsWith('/')
            val tagName = rawTagName.removePrefix("/").lowercase()
            if (!looksLikeReservedTagName(tagName)) return false

            val afterTag = region.substring(tagEnd + 1).trimStart()
            if (afterTag.isEmpty()) return true
            if (isClosing) return false
            return afterTag.startsWith('{') || afterTag.startsWith("</")
        }

        private fun looksLikeReservedTagName(name: String): Boolean {
            if (!name.startsWith("elsew")) return false
            if (name == OPEN_TAG_NAME || OPEN_TAG_NAME.startsWith(name)) return true
            return name.endsWith("_npc_updates") || editDistance(name, OPEN_TAG_NAME) <= 3
        }

        private fun editDistance(left: String, right: String): Int {
            var previous = IntArray(right.length + 1) { it }
            left.forEachIndexed { leftIndex, leftChar ->
                val current = IntArray(right.length + 1)
                current[0] = leftIndex + 1
                right.forEachIndexed { rightIndex, rightChar ->
                    current[rightIndex + 1] = minOf(
                        current[rightIndex] + 1,
                        previous[rightIndex + 1] + 1,
                        previous[rightIndex] + if (leftChar == rightChar) 0 else 1
                    )
                }
                previous = current
            }
            return previous[right.length]
        }

        val OUTPUT_INSTRUCTION: String = """
            --- NPC BANK UPDATE CONTRACT ---
            Silently track named supporting characters who have a distinct voice and a story stake. Do not treat the active Persona or active AI Characters as NPCs.
            After the visible roleplay response, you may append exactly one terminal metadata block using this form:
            $OPEN_MARKER
            {"schema_version":1,"updates":[{"npc_id":null,"display_name":"Name","aliases":[],"role":"Story role","appearance":"Only established details","personality":"Established traits","relationship_to_persona":"Relationship to the active Persona","goals":"Current goals","secrets":"Narrative secrets established by this response"}]}
            $CLOSE_MARKER
            Spell both metadata tags exactly as shown. If you cannot complete the entire valid block, omit it. Creation and update are distinct. For a newly established NPC, omit npc_id, provide display_name, and optionally provide aliases, appearance, and personality. For an existing NPC, use the supplied npc_id and never include display_name, aliases, appearance, or personality; those identity/core-profile fields change only through explicit manual edits. Existing NPC automation may update only role, relationship_to_persona, goals, and secrets. A secrets value records storyteller continuity only and does not grant that knowledge to any Character. Never include private author notes. Never put prose after the closing marker. Do not mention this metadata block in the visible story.
        """.trimIndent()
    }
}
