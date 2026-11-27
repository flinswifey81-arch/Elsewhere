package com.example.phonelab

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Feed Pack v1 accepts public material only. Private threads and lore are not importable. */
class PulseFeedPack private constructor(
    val name: String,
    config: Map<String, Any?>,
    starter: Map<String, Any?>?,
    val platforms: Int,
    val accounts: Int,
    val publicEvents: Int,
    val posts: Int,
    val replies: Int,
) {
    val templateJson = PulseJson.canonical(config)
    val starterJson = starter?.let(PulseJson::canonical)
    val templateId = pulseDigest(templateJson)
    val starterId = starterJson?.let { pulseDigest("$templateId:$it") }

    companion object {
        const val MAX_BYTES = 1_048_576
        private val ID = Regex("[a-z0-9][a-z0-9_-]{0,63}")
        private const val FORMAT = "elsewhere.world-pulse.feed-pack"

        fun parse(bytes: ByteArray): PulseFeedPack {
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) bad("$", "Choose a UTF-8 JSON file no larger than 1 MiB.")
            val source = try {
                StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) { bad("$", "Invalid UTF-8.") }
            val root = obj(PulseJson.parse(source), "$", "format version name platforms accounts", "starterState")
            val version = (root["version"] as? PulseJson.Number)?.value
            val isVersionOne = version != null && version.length <= 24 &&
                version.toBigDecimalOrNull()?.compareTo(BigDecimal.ONE) == 0
            if (root["format"] != FORMAT || !isVersionOne)
                bad("$", "Expected a World Pulse Feed Pack version 1.")
            root["version"] = PulseJson.Number("1")
            val name = text(root, "name", 120, "$")
            val platforms = array(root["platforms"], "$.platforms", 1, 12)
            val platformIds = hashSetOf<String>()
            platforms.forEachIndexed { i, v ->
                val path = "$.platforms[$i]"
                val entry = obj(v, path, "id name style", "culture")
                unique(platformIds, id(entry, "id", path), path)
                text(entry, "name", 80, path)
                oneOf(entry, "style", path, "timeline", "photo_feed", "bulletin", "forum")
                optional(entry, "culture", 1600, path)
            }

            val accounts = array(root["accounts"], "$.accounts", 0, 200)
            val accountPlatforms = hashMapOf<String, String>()
            val handles = hashSetOf<Pair<String, String>>()
            accounts.forEachIndexed { i, v ->
                val path = "$.accounts[$i]"
                val entry = obj(v, path, "id platformId handle displayName role", "publicBio voice")
                val platform = id(entry, "platformId", path)
                if (platform !in platformIds) bad(path, "Unknown platform.")
                if (accountPlatforms.put(id(entry, "id", path), platform) != null)
                    bad(path, "Duplicate account ID.")
                val handle = text(entry, "handle", 60, path).trim().lowercase().removePrefix("@")
                if (handle.isBlank() || !handles.add(platform to handle))
                    bad(path, "A handle must be unique on its platform.")
                text(entry, "displayName", 100, path)
                text(entry, "role", 80, path)
                optional(entry, "publicBio", 500, path)
                optional(entry, "voice", 400, path)
            }

            var starter: Map<String, Any?>? = null
            var eventCount = 0
            var postCount = 0
            var replyCount = 0
            if (root.containsKey("starterState")) {
                starter = obj(root.remove("starterState"), "$.starterState", "publicEvents posts")
                val events = array(starter["publicEvents"], "$.starterState.publicEvents", 0, 100)
                val eventIds = hashSetOf<String>()
                events.forEachIndexed { i, v ->
                    val path = "$.starterState.publicEvents[$i]"
                    val entry = obj(v, path, "id visibility summary")
                    unique(eventIds, id(entry, "id", path), path)
                    oneOf(entry, "visibility", path, "public")
                    text(entry, "summary", 1600, path)
                }
                val posts = array(starter["posts"], "$.starterState.posts", 0, 200)
                val postIds = hashSetOf<String>()
                val replyIds = hashSetOf<String>()
                posts.forEachIndexed { i, v ->
                    val path = "$.starterState.posts[$i]"
                    val entry = obj(v, path, "id accountId kind text", "eventId replies")
                    unique(postIds, id(entry, "id", path), path)
                    val platform = accountPlatforms[id(entry, "accountId", path)] ?: bad(path, "Unknown post account.")
                    val kind = oneOf(entry, "kind", path, "announcement", "reaction", "rumor", "ambient")
                    text(entry, "text", 2400, path)
                    if (kind == "announcement" || entry.containsKey("eventId")) {
                        if (id(entry, "eventId", path) !in eventIds) bad(path, "Unknown public event.")
                    }
                    if (entry.containsKey("replies")) {
                        val replies = array(entry["replies"], "$path.replies", 0, 40)
                        replies.forEachIndexed { j, item ->
                            val rp = "$path.replies[$j]"
                            val reply = obj(item, rp, "id accountId kind text")
                            unique(replyIds, id(reply, "id", rp), rp)
                            if (platform != accountPlatforms[id(reply, "accountId", rp)])
                                bad(rp, "Reply account must exist on the post's platform.")
                            oneOf(reply, "kind", rp, "reaction", "rumor")
                            text(reply, "text", 1000, rp)
                            replyCount++
                        }
                    }
                }
                eventCount = events.size
                postCount = posts.size
            }
            return PulseFeedPack(name, root, starter, platforms.size, accounts.size, eventCount, postCount, replyCount)
        }

        @Suppress("UNCHECKED_CAST")
        private fun obj(value: Any?, path: String, required: String, optional: String = ""): MutableMap<String, Any?> {
            val entries = value as? MutableMap<String, Any?> ?: bad(path, "Expected an object.")
            val requiredFields = required.split(' ')
            for (key in requiredFields) if (!entries.containsKey(key)) bad("$path.$key", "Field is required.")
            val allowed = (requiredFields + optional.split(' ').filter { it.isNotEmpty() }).toSet()
            if (entries.keys.any { it !in allowed }) bad(path, "Unexpected field.")
            return entries
        }

        private fun array(value: Any?, path: String, min: Int, max: Int): List<*> {
            val list = value as? List<*> ?: bad(path, "Expected an array.")
            if (list.size !in min..max) bad(path, "Expected $min to $max entries.")
            return list
        }

        private fun text(m: Map<String, Any?>, field: String, max: Int, path: String): String {
            val value = m[field] as? String ?: bad("$path.$field", "Expected text.")
            if (value.isBlank() || value.codePointCount(0, value.length) > max)
                bad("$path.$field", "Expected non-blank text up to $max characters.")
            if ('\u0000' in value) bad("$path.$field", "Text cannot contain NUL.")
            return value
        }

        private fun optional(m: Map<String, Any?>, field: String, max: Int, path: String) {
            if (m.containsKey(field)) text(m, field, max, path)
        }

        private fun id(m: Map<String, Any?>, field: String, path: String): String =
            text(m, field, 64, path).also { if (!ID.matches(it)) bad("$path.$field", "Invalid package-local ID.") }

        private fun oneOf(m: Map<String, Any?>, field: String, path: String, vararg allowed: String): String {
            val value = m[field] as? String
            if (value == null || value !in allowed) bad("$path.$field", "Expected ${allowed.joinToString(", ")}.")
            return value
        }

        private fun unique(known: MutableSet<String>, id: String, path: String) {
            if (!known.add(id)) bad(path, "Duplicate ID.")
        }
    }
}

private fun bad(path: String, cause: String): Nothing = throw IllegalArgumentException("$path: $cause")

internal fun pulseDigest(text: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(64) {
        for (signed in bytes) {
            val byte = signed.toInt() and 255
            append(hex[byte shr 4]).append(hex[byte and 15])
        }
    }
}

/** Bounded strict JSON reader; duplicates and ambiguous scalar coercion fail closed. */
class PulseJson private constructor(private val source: String) {
    private var i = 0
    data class Number(val value: String)

    companion object {
        private val NUMBER = java.util.regex.Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[Ee][+-]?[0-9]+)?")

        fun parse(source: String): Any? = PulseJson(source).run {
            val result = value(0)
            space()
            if (i != source.length) syntax()
            result
        }

        fun canonical(value: Any?): String = buildString { emit(value) }

        @Suppress("UNCHECKED_CAST")
        private fun StringBuilder.emit(value: Any?) {
            when (value) {
                is Map<*, *> -> {
                    append('{')
                    (value as Map<String, Any?>).toSortedMap().entries.forEachIndexed { index, entry ->
                        if (index > 0) append(',')
                        quote(entry.key); append(':'); emit(entry.value)
                    }
                    append('}')
                }
                is List<*> -> {
                    append('[')
                    value.forEachIndexed { index, item ->
                        if (index > 0) append(',')
                        emit(item)
                    }
                    append(']')
                }
                is String -> quote(value)
                is Number -> append(value.value)
                is Boolean, null -> append(value)
                else -> error("Unsupported JSON value.")
            }
        }

        private fun StringBuilder.quote(text: String) {
            append('"')
            val hex = "0123456789abcdef"
            for (c in text) {
                if (c == '"' || c == '\\') append('\\').append(c)
                else if (c.code < 32) append("\\u00").append(hex[c.code shr 4]).append(hex[c.code and 15])
                else append(c)
            }
            append('"')
        }
    }

    private fun space() {
        while (i < source.length && source[i] in " \r\n\t") i++
    }

    private fun take(c: Char): Boolean {
        space()
        if (i == source.length || source[i] != c) return false
        i++
        return true
    }

    private fun expect(c: Char) { if (!take(c)) syntax() }
    private fun syntax(): Nothing = bad("$", "Invalid JSON near character ${i + 1}.")

    private fun value(depth: Int): Any? {
        space()
        if (i == source.length || depth > 16) syntax()
        return when (source[i]) {
            '{' -> {
                i++
                val map = linkedMapOf<String, Any?>()
                if (!take('}')) {
                    do {
                        val key = string()
                        if (map.containsKey(key)) bad("$", "Duplicate JSON field near character ${i + 1}.")
                        expect(':')
                        map[key] = value(depth + 1)
                    } while (take(','))
                    expect('}')
                }
                map
            }
            '[' -> {
                i++
                val list = mutableListOf<Any?>()
                if (!take(']')) {
                    do { list.add(value(depth + 1)) } while (take(','))
                    expect(']')
                }
                list
            }
            '"' -> string()
            'n' -> { literal("null"); null }
            't' -> { literal("true"); true }
            'f' -> { literal("false"); false }
            else -> {
                val matcher = NUMBER.matcher(source).region(i, source.length)
                if (!matcher.lookingAt()) syntax()
                i = matcher.end()
                Number(matcher.group())
            }
        }
    }

    private fun literal(text: String) {
        if (!source.startsWith(text, i)) syntax()
        i += text.length
    }

    private fun string(): String {
        expect('"')
        val result = StringBuilder()
        while (i < source.length) {
            val c = source[i++]
            if (c == '"') {
                if (!StandardCharsets.UTF_8.newEncoder().canEncode(result)) syntax()
                return result.toString()
            }
            if (c.code < 32) syntax()
            if (c != '\\') { result.append(c); continue }
            if (i == source.length) syntax()
            val escape = source[i++]
            result.append(when (escape) {
                '"', '\\', '/' -> escape
                'b' -> '\b'
                'f' -> '\u000c'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    var code = 0
                    repeat(4) {
                        if (i == source.length) syntax()
                        val ch = source[i++]
                        val digit = when (ch) {
                            in '0'..'9' -> ch - '0'
                            in 'a'..'f' -> ch - 'a' + 10
                            in 'A'..'F' -> ch - 'A' + 10
                            else -> syntax()
                        }
                        code = (code shl 4) or digit
                    }
                    code.toChar()
                }
                else -> syntax()
            })
        }
        syntax()
    }
}
