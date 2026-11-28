package com.example.phonelab

/** A candidate from the model. The caller reviews it before it can become a post. */
class PulseGeneratedPost internal constructor(
    val accountId: String,
    val eventId: String,
    val kind: String,
    val text: String
)

object PulseGeneratedBatch {
    const val MAX_OUTPUT_CHARS = 24_000

    fun parse(json: String, approved: PulseRefreshPlanner.RequestPreview): List<PulseGeneratedPost> {
        require(json.length in 2..MAX_OUTPUT_CHARS) { "The response is too large or empty." }
        val root = PulseJson.parse(unwrapSingleDocument(json)) as? Map<*, *> ?: error("Expected one JSON object.")
        require(root.keys == setOf("posts")) { "Expected only the posts field." }
        val rows = root["posts"] as? List<*> ?: error("Posts must be a list.")
        require(rows.size in 1..approved.maxPosts) { "The response has the wrong number of posts." }
        val accounts = approved.accounts.map { it.id }.toSet()
        val events = approved.events.map { it.id }.toSet()
        return rows.map { row ->
            val fields = row as? Map<*, *> ?: error("Each post must be an object.")
            require(fields.keys == setOf("accountId", "eventId", "kind", "text")) {
                "A generated post had unexpected fields."
            }
            val account = fields["accountId"] as? String
            val event = fields["eventId"] as? String
            val kind = fields["kind"] as? String
            val text = (fields["text"] as? String)?.trim().orEmpty()
            require(account != null && account in accounts) { "The response used an unknown account." }
            require(event != null && event in events) { "The response used an event you did not select." }
            require(kind in setOf("reaction", "rumor")) { "AI posts must be reactions or clearly labeled rumors." }
            require(text.isNotBlank() && text.length <= 1200 && '\u0000' !in text) { "A post's text is empty or too long." }
            PulseGeneratedPost(account, event, checkNotNull(kind), text)
        }.also { items ->
            require(items.map { it.text }.distinct().size == items.size) { "The response contains duplicate post text." }
        }
    }

    /** Models often add a display wrapper even when asked for raw JSON. Remove only known
     * wrappers around ONE complete document; the strict parser still rejects trailing junk,
     * multiple batches, duplicate keys, and any malformed post. Do not apply this to imports.
     */
    private fun unwrapSingleDocument(source: String): String {
        var body = source.trim().removePrefix("\uFEFF").trimStart()
        if (body.startsWith("<think>")) {
            val end = body.indexOf("</think>", startIndex = 7)
            require(end >= 0) { "The model didn't finish formatting its reply." }
            body = body.substring(end + "</think>".length).trimStart()
        }
        if (!body.startsWith("```")) return body
        val firstLineEnd = body.indexOf('\n')
        require(firstLineEnd >= 0) { "The model didn't finish its JSON block." }
        val header = body.substring(0, firstLineEnd).trimEnd()
        require(header == "```" || header.equals("```json", ignoreCase = true)) {
            "The model didn't return a JSON block."
        }
        val footer = body.lastIndexOf('\n')
        require(footer > firstLineEnd && body.substring(footer + 1).trim() == "```") {
            "The model didn't return one complete JSON block."
        }
        return body.substring(firstLineEnd + 1, footer).trim()
    }

}
