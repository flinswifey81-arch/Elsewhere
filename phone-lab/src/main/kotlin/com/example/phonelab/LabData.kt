package com.example.phonelab

import android.content.Context
import com.example.phonelab.systems.*
import java.io.InputStream
import java.util.UUID

internal data class LabContact(val id: String, val name: String, val role: ContactRole, val opening: String, val reply: String)
internal data class LabStory(val scope: PhoneScope, val world: String, val title: String, val persona: String,
    val scene: String, val contacts: List<LabContact>, val demoPack: String? = null, val chapter: Int = 2)
internal val labStories = listOf(
    LabStory(PhoneScope("hero-society","first-response","lillypad"), "Hero Society", "Lillypad: First Response", "Lillypad",
        "The last elevator chimes. Beyond the agency windows, the city looks almost quiet.",
        listOf(LabContact("bakugo","Bakugo",ContactRole.LEAD,"Rooftop training garden. Eight.","Got it. See you up there."),
            LabContact("mika","Mika · Dispatch",ContactRole.NPC,"Dispatch confirmed the rescue drill starts at nine.","Copy that. I’ll let dispatch know.")), "HeroConnect.json"),
    LabStory(PhoneScope("idol-au","blue-hour","jina"), "Afterglow Idol AU", "Blue Hour", "Jina",
        "The teaser hits the timeline just as the floor manager calls places.",
        listOf(LabContact("minseo","Minseo",ContactRole.LEAD,"Studio B after the broadcast? I want you to hear the bridge.","I’ll see you after the broadcast."),
            LabContact("dae","Dae · Tour manager",ContactRole.NPC,"Call time moved to 6:30.","Got it. I’ll update the schedule.")), "Afterglow.json"),
    LabStory(PhoneScope("hero-society","alternate","lillypad"), "Hero Society", "Alternate Timeline", "Lillypad",
        "A different first day begins at the agency. Nothing from First Response happened here.",
        listOf(LabContact("bakugo","Bakugo",ContactRole.LEAD,"Dispatch board first. Then we’ll talk.","I’ll be by the board."),
            LabContact("mika","Mika · Dispatch",ContactRole.NPC,"Welcome. Your schedule is still open.","I’ll update dispatch.")))
)
internal data class LabReply(val author: String, val text: String, val rumor: Boolean)
internal data class LabPost(val platform: String, val author: String, val text: String, val rumor: Boolean,
    val replies: List<LabReply>)
internal data class LabFeed(val worldTemplates: Int, val posts: List<LabPost>)
internal data class LabStoryState(val memories: List<String>, val lore: List<String>, val drafts: List<String>)

internal class LabData(private val context: Context) : AutoCloseable {
    private val phone = PhoneStore(context)
    private val pulse = PulseImportStore(context.noBackupFilesDir.toPath().resolve("pulse-lab"), object : PulseImportStore.Membership {
        override fun worldExists(worldId: String) = labStories.any { it.scope.worldId == worldId }
        override fun campaignBelongsToWorld(worldId: String, campaignId: String) = labStories.any {
            it.scope.worldId == worldId && it.scope.campaignId == campaignId
        }
    })
    private val prefs = context.getSharedPreferences("phone-lab-preferences",Context.MODE_PRIVATE)
    private fun threadKey(story: LabStory, contact: LabContact): ThreadKey {
        require(contact in story.contacts) { "Contact is not in this playthrough." }
        return ThreadKey(story.scope, contact.id)
    }
    fun initialize(story: LabStory) {
        story.contacts.forEach { contact ->
            val thread = threadKey(story,contact)
            phone.registerContact(thread,contact.role,contact.name)
            phone.saveContactReply(thread,"demo-opening",1,contact.opening,0)
        }
        story.demoPack?.let { fixture ->
            if (!prefs.getBoolean("seed:${story.scope.campaignId}",false)) {
                val pack = context.assets.open(fixture).use { preview(story,it) }
                applyPack(pack,true)
                prefs.edit().putBoolean("seed:${story.scope.campaignId}",true).apply()
            }
        }
    }
    fun inboundStory(story: LabStory) = LabStoryState(
        phone.approvedStoryMemory(story.scope,story.scope.personaId,story.chapter),
        phone.approvedPlaythroughLore(story.scope,story.chapter),
        story.contacts.flatMap { phone.pendingWorldDrafts(threadKey(story,it),story.chapter) }.map { it.approvedText }
    )
    fun history(story: LabStory, contact: LabContact) = phone.history(threadKey(story,contact),story.chapter,120)
    fun sendDemo(story: LabStory, contact: LabContact, text: String): List<PhoneMessage> {
        val key=threadKey(story,contact)
        val id=UUID.randomUUID().toString()
        phone.saveDemoExchange(key,id,story.chapter,text,contact.reply,System.currentTimeMillis())
        return history(story,contact)
    }
    fun save(story: LabStory, contact: LabContact, ids: List<String>, note: String, destination: Destination) =
        phone.saveApproved(threadKey(story,contact),UUID.randomUUID().toString(),story.chapter,destination,note,ids)

    fun loadDraft(story: LabStory) = prefs.getString("draft:${story.scope.campaignId}","").orEmpty()
    fun storeDraft(story: LabStory, draft: String) { prefs.edit().putString("draft:${story.scope.campaignId}",draft).apply() }
    fun pulseEnabled(story: LabStory) = prefs.getBoolean("pulse:${story.scope.campaignId}",true)
    fun setPulse(story: LabStory, enabled: Boolean) {
        prefs.edit().putBoolean("pulse:${story.scope.campaignId}",enabled).apply()
    }
    fun preview(story: LabStory, input: InputStream): PulseImportStore.Preview {
        val bytes = input.readNBytes(PulseFeedPack.MAX_BYTES + 1)
        return pulse.preview(bytes, story.scope.worldId, story.scope.campaignId)
    }
    fun applyPack(preview: PulseImportStore.Preview, publicStarter: Boolean) = pulse.apply(preview,publicStarter)
    fun feed(story: LabStory): LabFeed {
        val templates = pulse.templates(story.scope.worldId)
        val starters = pulse.campaignStarters(story.scope.worldId,story.scope.campaignId)
        return LabFeed(templates.size,starters.values.flatMap { starter ->
            val settings = PulseJson.parse(templates.getValue(starter.templateId)) as Map<*,*>
            val state = PulseJson.parse(starter.stateJson) as Map<*,*>
            val platforms = settings.rows("platforms").associate { it.str("id") to it.str("name") }
            val accounts = settings.rows("accounts").associateBy { it.str("id") }
            fun name(id: String) = accounts.getValue(id).str("displayName")
            state.rows("posts").map { post ->
                val authorId=post.str("accountId")
                LabPost(platforms.getValue(accounts.getValue(authorId).str("platformId")),name(authorId),
                    post.str("text"),post.str("kind")=="rumor",
                    post.rows("replies").map { LabReply(name(it.str("accountId")),it.str("text"),it.str("kind")=="rumor") })
            }
        })
    }
    fun firstRefreshPreview(story: LabStory): String {
        val starters = pulse.campaignStarters(story.scope.worldId, story.scope.campaignId)
        val templates = pulse.templates(story.scope.worldId)
        val pick = starters.entries.firstOrNull {
            (PulseJson.parse(it.value.stateJson) as Map<*,*>).rows("publicEvents").isNotEmpty()
        } ?: return "No approved public events yet."
        val events = (PulseJson.parse(pick.value.stateJson) as Map<*,*>).rows("publicEvents")
        val platform = (PulseJson.parse(templates.getValue(pick.value.templateId)) as Map<*,*>).rows("platforms").first()
        return PulseRefreshPlanner(pulse).prepareManualRefresh(story.scope.worldId,story.scope.campaignId,
            pick.key,platform.str("id"),listOf(events.first().str("id"))).events.single().summary
    }
    private fun Map<*,*>.rows(name: String): List<Map<*,*>> =
        (get(name) as? List<*>)?.map { it as Map<*,*> }.orEmpty()
    private fun Map<*,*>.str(name: String) = get(name) as String
    override fun close() = phone.close()
}
