package com.example.phonelab

/** Builds public-only inputs. There is intentionally no DM, lore, transcript, or provider dependency. */
class PulseRefreshPlanner(private val store: PulseImportStore) {
    data class PublicEvent(val id: String, val summary: String)
    data class PublicAccount(val id: String, val name: String)
    data class RequestPreview(
        val platformName: String,
        val events: List<PublicEvent>,
        val maxPosts: Int,
        val accounts: List<PublicAccount> = emptyList(),
    )

    /**
     * Call from a future explicit refresh action. The starter must have been approved and
     * imported into this campaign; clicking or browsing a feed does not call this method.
     */
    fun prepareManualRefresh(
        worldId: String,
        campaignId: String,
        starterId: String,
        platformId: String,
        selectedEventIds: List<String>,
        maxPosts: Int = 4,
    ): RequestPreview {
        require(selectedEventIds.size in 1..8 && selectedEventIds.distinct().size == selectedEventIds.size) {
            "Select 1 to 8 different public events."
        }
        require(maxPosts in 1..8) { "Choose a batch of 1 to 8 posts." }
        val starter = requireNotNull(store.campaignStarters(worldId, campaignId)[starterId]) {
            "This starter is not approved in this playthrough."
        }
        val template = requireNotNull(store.templates(worldId)[starter.templateId]) { "World template is unavailable." }
        val config = PulseJson.parse(template)
        val platform = requireNotNull(objects(config, "platforms").firstOrNull { it["id"] == platformId }) {
            "Selected platform is not in this Feed Pack."
        }
        val events = objects(PulseJson.parse(starter.stateJson), "publicEvents")
            .filter { it["visibility"] == "public" }.associateBy { it["id"] }
        val selected = selectedEventIds.map { id ->
            val event = requireNotNull(events[id]) { "Selected event is not in the approved public starter." }
            PublicEvent(id, requireNotNull(event["summary"] as? String))
        }
        require(selected.sumOf { it.summary.length } <= 4800) { "Select fewer events to keep the request small." }
        // Deliberately exclude existing feed text, rumors, bios, arbitrary imported account voice,
        // IDs of private source material, and any main story context from this safe preview.
        val accounts = objects(config, "accounts").filter { it["platformId"] == platformId }.take(32).map {
            PublicAccount(requireNotNull(it["id"] as? String), requireNotNull(it["displayName"] as? String))
        }
        return RequestPreview(requireNotNull(platform["name"] as? String), selected, maxPosts, accounts)
    }

    private fun objects(source: Any?, key: String): List<Map<*, *>> {
        val array = (source as? Map<*, *>)?.get(key) as? List<*> ?: error("Stored Feed Pack is invalid.")
        return array.map { it as? Map<*, *> ?: error("Stored Feed Pack entry is invalid.") }
    }
}
