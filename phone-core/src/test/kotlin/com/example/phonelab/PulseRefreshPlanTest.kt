package com.example.phonelab

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PulseRefreshPlanTest {
    @Test fun onlyApprovedPublicSummariesReachASelectedCampaignPreview() {
        val home = Files.createTempDirectory("pulse-plan").toFile()
        try {
            val store = PulseImportStore(home.toPath(), object : PulseImportStore.Membership {
                override fun worldExists(worldId: String) = worldId == "world"
                override fun campaignBelongsToWorld(worldId: String, campaignId: String) =
                    worldId == "world" && campaignId in listOf("a", "b")
            })
            val bytes = checkNotNull(javaClass.getResourceAsStream("/PulseAfterglow.feed-pack.json")).use { it.readBytes() }
            val receipt = store.apply(store.preview(bytes, "world", "a"), reviewedUseStarter = true)
            val id = checkNotNull(receipt.starterId)
            val plan = PulseRefreshPlanner(store)
            val preview = plan.prepareManualRefresh("world", "a", id, "encore", listOf("teaser"))
            assertEquals("Encore", preview.platformName)
            assertEquals(4, preview.maxPosts)
            assertTrue(preview.events.single().summary.contains("released a public teaser"))
            assertFalse(preview.toString().contains("Purely a guess"))
            assertThrows(IllegalArgumentException::class.java) {
                plan.prepareManualRefresh("world","b",id,"encore",listOf("teaser"))
            }
            assertThrows(IllegalArgumentException::class.java) {
                plan.prepareManualRefresh("world","a",id,"encore",listOf("office_secret"))
            }
        } finally { home.deleteRecursively() }
    }
}
