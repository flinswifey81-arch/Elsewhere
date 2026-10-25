package com.example.domain.story

import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import java.security.MessageDigest
import java.util.Locale
import okio.Buffer

enum class StorySegmentType { NARRATION, CHARACTER, NPC }

data class StorySegment(
    val type: StorySegmentType,
    val content: String,
    val speakerId: String? = null,
    val speakerName: String? = null
)

data class StoryResponse(
    val schemaVersion: Int = 1,
    val segments: List<StorySegment>
)

data class StorySpeakerIdentity(val id: String, val name: String)

data class StoryParseResult(
    val response: StoryResponse,
    val usedStructuredFormat: Boolean,
    val warning: String? = null
)

internal data class StorySegmentHeader(
    val type: String,
    val speaker_id: String? = null,
    val speaker_name: String? = null
)

object StoryResponseCodec {
    private const val PREFIX = "\u001eelsewhere-story-v1\n"
    private val adapter by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Moshi.Builder().build().adapter(Any::class.java)
    }

    fun encode(response: StoryResponse): String {
        val payload = mapOf(
            "schemaVersion" to response.schemaVersion,
            "segments" to response.segments.filter { it.content.isNotBlank() }.map { segment ->
                mapOf(
                    "type" to segment.type.name,
                    "content" to segment.content,
                    "speakerId" to segment.speakerId,
                    "speakerName" to segment.speakerName
                )
            }
        )
        return PREFIX + adapter.toJson(payload)
    }

    fun decode(content: String): StoryResponse? {
        if (!content.startsWith(PREFIX)) return null
        return runCatching {
            val root = adapter.fromJson(content.removePrefix(PREFIX)) as? Map<*, *> ?: return null
            val schemaVersion = (root["schemaVersion"] as? Number)?.toInt() ?: return null
            if (schemaVersion != 1) return null
            val rawSegments = root["segments"] as? List<*> ?: return null
            val segments = rawSegments.mapNotNull { raw ->
                val value = raw as? Map<*, *> ?: return@mapNotNull null
                val typeName = value["type"] as? String ?: return@mapNotNull null
                val type = runCatching {
                    StorySegmentType.valueOf(typeName.uppercase(Locale.ROOT))
                }.getOrNull() ?: return@mapNotNull null
                val text = (value["content"] as? String)?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                StorySegment(
                    type = type,
                    content = text,
                    speakerId = value["speakerId"] as? String,
                    speakerName = value["speakerName"] as? String
                )
            }
            StoryResponse(schemaVersion = schemaVersion, segments = segments)
        }.getOrNull()
    }

    fun isEncoded(content: String): Boolean = content.startsWith(PREFIX)

    fun visibleText(content: String): String = decode(content)?.let(::readableTranscript)
        ?: content.removePrefix(PREFIX)

    fun promptTranscript(content: String): String = decode(content)?.segments
        ?.joinToString("\n\n") { segment ->
            when (segment.type) {
                StorySegmentType.NARRATION -> "Narrator: ${segment.content}"
                StorySegmentType.CHARACTER -> "Character (${segment.speakerName ?: "Character"}): ${segment.content}"
                StorySegmentType.NPC -> "NPC (${segment.speakerName ?: "Supporting character"}): ${segment.content}"
            }
        }
        ?: content.removePrefix(PREFIX)

    fun readableTranscript(response: StoryResponse): String = response.segments
        .joinToString("\n\n") { segment ->
            when (segment.type) {
                StorySegmentType.NARRATION -> segment.content
                StorySegmentType.CHARACTER, StorySegmentType.NPC ->
                    segment.speakerName?.let { "$it: ${segment.content}" } ?: segment.content
            }
        }

    fun append(
        existing: String,
        addition: String,
        primaryCharacter: StorySpeakerIdentity
    ): String {
        val existingResponse = decode(existing)
        val additionResponse = decode(addition)
        if (existingResponse == null && additionResponse == null) return existing + addition
        val existingSegments = existingResponse?.segments ?: listOf(
            StorySegment(
                type = StorySegmentType.CHARACTER,
                content = existing,
                speakerId = primaryCharacter.id,
                speakerName = primaryCharacter.name
            )
        )
        val additionSegments = additionResponse?.segments ?: listOf(
            StorySegment(
                type = StorySegmentType.CHARACTER,
                content = addition,
                speakerId = primaryCharacter.id,
                speakerName = primaryCharacter.name
            )
        )
        return encode(StoryResponse(segments = mergeAdjacent(existingSegments + additionSegments)))
    }

    internal fun mergeAdjacent(segments: List<StorySegment>): List<StorySegment> =
        segments.filter { it.content.isNotBlank() }.fold(mutableListOf()) { merged, segment ->
            val previous = merged.lastOrNull()
            if (
                previous != null &&
                previous.type == segment.type &&
                previous.speakerId == segment.speakerId &&
                previous.speakerName == segment.speakerName
            ) {
                merged[merged.lastIndex] = previous.copy(
                    content = previous.content.trimEnd() + "\n\n" + segment.content.trimStart()
                )
            } else {
                merged += segment.copy(content = segment.content.trim())
            }
            merged
        }
}

open class StoryOutputParser {
    open fun parse(
        output: String,
        primaryCharacter: StorySpeakerIdentity,
        knownNpcs: List<StorySpeakerIdentity> = emptyList(),
        final: Boolean = true
    ): StoryParseResult {
        val segments = mutableListOf<StorySegment>()
        var currentHeader: StorySegmentHeader? = null
        val currentContent = mutableListOf<String>()
        var sawStructure = false
        var sawStart = false
        var sawEnd = false
        var malformed = false

        fun flush() {
            val text = currentContent.joinToString("\n").trim()
            currentContent.clear()
            if (text.isBlank()) return
            segments += resolveSegment(currentHeader, text, primaryCharacter, knownNpcs)
            if (currentHeader == null && sawStructure) malformed = true
        }

        output.split('\n').forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed == START_MARKER -> {
                    flush()
                    sawStructure = true
                    sawStart = true
                    currentHeader = null
                }
                trimmed.startsWith(SEGMENT_MARKER) -> {
                    flush()
                    sawStructure = true
                    val payload = trimmed.removePrefix(SEGMENT_MARKER).trim()
                    val jsonEnd = payload.indexOf('}')
                    val json = if (jsonEnd >= 0) payload.substring(0, jsonEnd + 1) else payload
                    currentHeader = if (
                        json.startsWith('{') && jsonEnd >= 0 && json.length <= MAX_HEADER_CHARS
                    ) {
                        parseHeader(json)
                    } else null
                    if (currentHeader == null) malformed = true
                    val trailingProse = if (jsonEnd >= 0) payload.substring(jsonEnd + 1).trim() else {
                        payload.takeUnless { it.startsWith('{') }.orEmpty()
                    }
                    if (trailingProse.isNotBlank()) currentContent += sanitizeControlSyntax(trailingProse)
                }
                trimmed == END_MARKER -> {
                    flush()
                    sawStructure = true
                    sawEnd = true
                    currentHeader = null
                }
                looksLikeControlFragment(trimmed) -> {
                    sawStructure = true
                    malformed = true
                }
                else -> {
                    val visibleLine = sanitizeControlSyntax(line)
                    if (visibleLine != line) {
                        sawStructure = true
                        malformed = true
                    }
                    currentContent += visibleLine
                }
            }
        }
        flush()

        if (final && sawStructure && (!sawStart || !sawEnd)) malformed = true
        val normalized = StoryResponseCodec.mergeAdjacent(segments)
        val fallback = if (normalized.isEmpty()) {
            val prose = output.lineSequence()
                .map(::sanitizeControlSyntax)
                .filterNot { looksLikeControlFragment(it.trim()) }
                .joinToString("\n")
                .trim()
            if (prose.isBlank()) emptyList() else listOf(
                StorySegment(
                    type = StorySegmentType.CHARACTER,
                    content = prose,
                    speakerId = primaryCharacter.id,
                    speakerName = primaryCharacter.name
                )
            )
        } else normalized

        return StoryParseResult(
            response = StoryResponse(segments = fallback),
            usedStructuredFormat = sawStructure,
            warning = if (malformed) {
                "The response format was incomplete; all visible story text was preserved."
            } else null
        )
    }

    private fun resolveSegment(
        header: StorySegmentHeader?,
        content: String,
        primaryCharacter: StorySpeakerIdentity,
        knownNpcs: List<StorySpeakerIdentity>
    ): StorySegment {
        return when (header?.type?.trim()?.lowercase(Locale.ROOT)) {
            "narration", "narrator" -> StorySegment(StorySegmentType.NARRATION, content)
            "character", "primary_character" -> StorySegment(
                StorySegmentType.CHARACTER,
                content,
                primaryCharacter.id,
                primaryCharacter.name
            )
            "npc" -> {
                val suppliedId = header.speaker_id?.trim()?.takeIf { it.length in 1..MAX_ID_CHARS }
                val suppliedName = header.speaker_name?.trim()?.takeIf { it.length in 1..MAX_NAME_CHARS }
                val known = knownNpcs.firstOrNull { it.id == suppliedId }
                    ?: knownNpcs.firstOrNull { knownNpc ->
                        suppliedName != null && knownNpc.name.equals(suppliedName, ignoreCase = true)
                    }
                val name = known?.name ?: suppliedName ?: "Supporting character"
                val id = known?.id ?: suppliedId ?: stableNpcId(name)
                if (id == primaryCharacter.id || name.equals(primaryCharacter.name, ignoreCase = true)) {
                    StorySegment(StorySegmentType.CHARACTER, content, primaryCharacter.id, primaryCharacter.name)
                } else {
                    StorySegment(StorySegmentType.NPC, content, id, name)
                }
            }
            else -> StorySegment(
                StorySegmentType.CHARACTER,
                content,
                primaryCharacter.id,
                primaryCharacter.name
            )
        }
    }

    private fun stableNpcId(name: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(name.lowercase(Locale.ROOT).toByteArray())
            .take(8)
            .joinToString("") { byte -> "%02x".format(byte) }
        return "story-npc:$digest"
    }

    private fun parseHeader(json: String): StorySegmentHeader? = runCatching {
        val reader = JsonReader.of(Buffer().writeUtf8(json))
        var type: String? = null
        var speakerId: String? = null
        var speakerName: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.selectName(HEADER_OPTIONS)) {
                0 -> type = reader.nextString()
                1 -> speakerId = reader.nextNullableString()
                2 -> speakerName = reader.nextNullableString()
                -1 -> {
                    reader.skipName()
                    reader.skipValue()
                }
            }
        }
        reader.endObject()
        require(reader.peek() == JsonReader.Token.END_DOCUMENT)
        StorySegmentHeader(
            type = requireNotNull(type).trim().takeIf(String::isNotEmpty)
                ?: error("Missing segment type"),
            speaker_id = speakerId,
            speaker_name = speakerName
        )
    }.getOrNull()

    private fun JsonReader.nextNullableString(): String? =
        if (peek() == JsonReader.Token.NULL) nextNull() else nextString()

    private fun sanitizeControlSyntax(line: String): String {
        var visible = SEGMENT_HEADER_PATTERN.replace(line, "")
            .replace(START_MARKER, "")
            .replace(END_MARKER, "")
            .replace(SEGMENT_MARKER, "")
        val incompleteControlStart = listOf("<|elsewhere_", "<|segment", "<|end_story")
            .map(visible::indexOf)
            .filter { it >= 0 }
            .minOrNull()
        if (incompleteControlStart != null) visible = visible.substring(0, incompleteControlStart)
        return visible.trimEnd()
    }

    companion object {
        const val START_MARKER = "<|elsewhere_story_v1|>"
        const val SEGMENT_MARKER = "<|segment|>"
        const val END_MARKER = "<|end_story|>"
        private const val MAX_HEADER_CHARS = 1000
        private const val MAX_ID_CHARS = 120
        private const val MAX_NAME_CHARS = 120
        private val HEADER_OPTIONS = JsonReader.Options.of("type", "speaker_id", "speaker_name")
        private val SEGMENT_HEADER_PATTERN = Regex("<\\|segment\\|>\\s*\\{[^}\\r\\n]*\\}")

        private fun looksLikeControlFragment(line: String): Boolean =
            line.startsWith("<|elsewhere_") ||
                line.startsWith("<|segment") ||
                line.startsWith("<|end_story")

        fun fallbackVisibleText(output: String): String = output.lineSequence()
            .map { line ->
                var visible = SEGMENT_HEADER_PATTERN.replace(line, "")
                    .replace(START_MARKER, "")
                    .replace(END_MARKER, "")
                    .replace(SEGMENT_MARKER, "")
                val incompleteControlStart = listOf("<|elsewhere_", "<|segment", "<|end_story")
                    .map(visible::indexOf)
                    .filter { it >= 0 }
                    .minOrNull()
                if (incompleteControlStart != null) visible = visible.substring(0, incompleteControlStart)
                visible.trimEnd()
            }
            .filterNot { looksLikeControlFragment(it.trim()) }
            .joinToString("\n")
            .trim()

        val OUTPUT_INSTRUCTION: String = """
            --- ENSEMBLE STORY RESPONSE CONTRACT ---
            Return one coherent response using exactly this line-oriented structure. Do not use a Markdown code fence:
            $START_MARKER
            $SEGMENT_MARKER{"type":"narration"}
            Atmospheric or connective narration.
            $SEGMENT_MARKER{"type":"character"}
            The primary Character's dialogue and actions.
            $SEGMENT_MARKER{"type":"npc","speaker_id":"known NPC id when available","speaker_name":"NPC display name"}
            A supporting NPC's dialogue and actions, only when contextually relevant.
            $END_MARKER
            Segments may alternate in any natural order and each type may appear more than once. Omit unneeded NPC or narration segments. Put only metadata on a segment-header line and put story prose on following lines. Use Markdown italics for actions inside Character or NPC dialogue segments when appropriate. Never emit a Persona segment and never write dialogue, thoughts, feelings, decisions, or significant actions for the user's Persona. If an NPC Bank update contract is also present, append its terminal metadata block only after $END_MARKER. Do not mention or explain this contract.
        """.trimIndent()
    }
}
