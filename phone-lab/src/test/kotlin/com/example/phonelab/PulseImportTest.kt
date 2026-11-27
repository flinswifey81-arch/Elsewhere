package com.example.phonelab

import java.nio.file.Files
import org.junit.Test
import java.util.Comparator

private inline fun rejects(label: String, run: () -> Any?) {
    try { run(); error("Accepted $label") }
    catch (_: IllegalArgumentException) { /* expected */ }
}

class PulseImportTest {
@Test fun verifiesSchemaPrivacyAndDurableScopedImports() {
    val sample = checkNotNull(javaClass.getResourceAsStream("/PulseAfterglow.feed-pack.json"))
        .bufferedReader().use { it.readText() }
    fun parse(source: String) = PulseFeedPack.parse(source.toByteArray())
    val pack = parse(sample)
    check(pack.platforms == 1 && pack.accounts == 3 && pack.posts == 1 && pack.replies == 2 && pack.publicEvents == 1)
    check("\"rumor\"" in requireNotNull(pack.starterJson))
    check("publicEvents" !in pack.templateJson)
    check(pack.starterId == parse(" $sample  ").starterId)
    check(pack.starterId == parse(sample.replace("\"version\": 1", "\"version\": 1.0")).starterId)

    rejects("private event") { parse(sample.replace("\"public\"", "\"private\"")) }
    rejects("string version") { parse(sample.replace("\"version\": 1", "\"version\": \"1\"")) }
    rejects("wrong version") { parse(sample.replace("\"version\": 1", "\"version\": 2")) }
    rejects("private notes") { parse(sample.replaceFirst("{", "{\"privateNotes\":[], ")) }
    rejects("duplicate key") { parse(sample.replaceFirst("{", "{\"name\":\"first\", ")) }
    rejects("dangling public event") { parse(sample.replace("\"eventId\": \"teaser\"", "\"eventId\": \"not-found\"")) }
    rejects("dangling reply owner") { parse(sample.replace("\"accountId\": \"glow_subs\"", "\"accountId\": \"not-found\"")) }
    val otherPlatform = sample
        .replace("\"platforms\": [", "\"platforms\": [{\"id\":\"other\",\"name\":\"Other\",\"style\":\"forum\"},")
        .replace("\"id\": \"glow_subs\",\n      \"platformId\": \"encore\"", "\"id\": \"glow_subs\",\n      \"platformId\": \"other\"")
    check("\"id\": \"glow_subs\",\n      \"platformId\": \"other\"" in otherPlatform)
    rejects("cross-platform reply") { parse(otherPlatform) }
    rejects("trailing JSON root") { parse("$sample{}") }
    rejects("malformed UTF-8") { PulseFeedPack.parse(byteArrayOf(0xc3.toByte(), 0x28)) }
    rejects("oversize file") { PulseFeedPack.parse(ByteArray(PulseFeedPack.MAX_BYTES + 1)) }
    rejects("unpaired surrogate") { parse(sample.replace("Afterglow Idol AU", "\\ude00")) }

    val path = Files.createTempDirectory("phone-lab-pulse-smoke")
    try {
        val ownership = object : PulseImportStore.Membership {
            override fun worldExists(worldId: String) = worldId in setOf("a", "b")
            override fun campaignBelongsToWorld(worldId: String, campaignId: String) =
                if (worldId == "a") campaignId in setOf("one", "two") else campaignId == "three"
        }
        val store = PulseImportStore(path, ownership)
        val preview = store.preview(sample.toByteArray(), "a", "one")
        check(store.templates("a").isEmpty())
        rejects("foreign campaign") { store.preview(sample.toByteArray(), "b", "one") }
        rejects("unknown World") { store.preview(sample.toByteArray(), "missing") }
        rejects("starter without campaign") { store.apply(store.preview(sample.toByteArray(), "a"), true) }
        val template = store.apply(preview)
        check(template.addedTemplate && !template.addedStarter && store.campaignStarters("a", "one").isEmpty())
        val starter = store.apply(preview, true)
        check(!starter.addedTemplate && starter.addedStarter)
        check(!store.apply(preview, true).addedStarter)
        val reopen = PulseImportStore(path, ownership)
        check(reopen.templates("a").size == 1)
        check(reopen.campaignStarters("a", "one").size == 1)
        val row = reopen.campaignStarters("a", "one").getValue(requireNotNull(starter.starterId))
        check(row.templateId == pack.templateId && row.stateJson == pack.starterJson && row.templateId in reopen.templates("a"))
        check(reopen.campaignStarters("a", "two").isEmpty() && reopen.campaignStarters("b", "three").isEmpty() && reopen.templates("b").isEmpty())
        val other = store.preview(sample.toByteArray(), "a", "two")
        check(store.apply(other, true).addedStarter)
        check(store.deleteCampaignStarters("a", "one") && store.campaignStarters("a", "one").isEmpty()
            && store.campaignStarters("a", "two").size == 1 && store.templates("a").size == 1)
    } finally {
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
    println("Pulse import smoke passed: format, boundary, idempotence, scope, persistence and cleanup.")
}
}
