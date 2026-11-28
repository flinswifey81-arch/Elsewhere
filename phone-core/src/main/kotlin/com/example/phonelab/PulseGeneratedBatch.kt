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
        val root = PulseJson.parse(json) as? Map<*, *> ?: error("Expected one JSON object.")
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
}
