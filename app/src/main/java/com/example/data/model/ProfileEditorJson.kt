package com.example.data.model

import com.squareup.moshi.Moshi

/** Shared read-only lens for the supported card fields. Unknown JSON stays in its original record. */
internal object ProfileEditorJson {
    private val adapter = Moshi.Builder().build().adapter(Any::class.java)
    fun decode(json: String?): Any? = json?.let { runCatching { adapter.fromJson(it) }.getOrNull() }
    fun rootLayers(json: String?): List<Map<*, *>> {
        val root = decode(json) as? Map<*, *> ?: return emptyList()
        return listOf(root) + listOf("data","character","card").mapNotNull { root[it] as? Map<*, *> }
    }
    fun nestedSection(layers: List<Map<*, *>>, section: String): Any? =
        layers.firstNotNullOfOrNull { it[section] }
    fun rootText(layers: List<Map<*, *>>, vararg aliases: String): String =
        layers.firstNotNullOfOrNull { map -> aliases.firstNotNullOfOrNull { text(map[it]) } }.orEmpty()
    fun field(section: Any?, vararg aliases: String, plain: Boolean = false, structured: Boolean = false): String {
        val objectSection = section as? Map<*, *>
        if (objectSection != null) {
            return aliases.firstNotNullOfOrNull { key -> text(objectSection[key]) }
                ?: if (structured && objectSection.isNotEmpty()) adapter.toJson(objectSection) else ""
        }
        return if (plain) text(section).orEmpty() else ""
    }
    private fun text(value: Any?): String? = when (value) {
        is String -> value
        is Double -> if (value.isFinite() && value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is Number, is Boolean -> value.toString()
        is List<*> -> if (value.all { it is String }) value.joinToString("\n") else adapter.toJson(value)
        is Map<*, *> -> adapter.toJson(value)
        else -> null
    }
    fun examplesWithoutOpeningChoices(json: String?, editorOwnsExamples: Boolean): String? {
        val parsed = decode(json)
        if (parsed is String) return null // Historical plain examples field is a primary greeting.
        if (parsed !is Map<*, *>) return json
        val openingKeys = setOf("initial_message", "first_message", "first_mes", "greeting", "opening_message",
            "alternate_greetings", "alternateGreetings", "greetings")
        val exampleKeys = setOf("conversational_examples", "examples", "mes_example", "dialogue_examples")
        fun filter(map: Map<*, *>): Map<String, Any?> = buildMap {
            map.forEach { (key, value) ->
                if (key is String && key !in openingKeys && (!editorOwnsExamples || key !in exampleKeys)) {
                    if (key in setOf("data", "character", "card") && value is Map<*, *>) {
                        val nested = filter(value)
                        if (nested.isNotEmpty()) put(key, nested)
                    } else put(key, value)
                }
            }
        }
        val remainder = filter(parsed)
        return if (remainder.isEmpty()) null else if (remainder == parsed) json else adapter.toJson(remainder)
    }
    fun withoutMapped(json: String?, keys: Set<String>, consumePlain: Boolean = false,
                      consumeStructuredFallback: Boolean = false): String? {
        if (json == null) return null
        val parsed = decode(json)
        if (parsed !is Map<*, *>) return if (consumePlain) null else json
        if (consumeStructuredFallback && parsed.keys.none { it in keys }) return null
        val remainder = parsed.filterKeys { it !in keys }
        return when {
            remainder.isEmpty() -> null
            remainder.size == parsed.size -> json
            else -> adapter.toJson(remainder)
        }
    }
}
