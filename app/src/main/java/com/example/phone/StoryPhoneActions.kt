package com.example.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.model.WorldContinuityEventEntity
import com.example.data.model.CampaignContinuityItemEntity
import com.example.phonelab.PulseImportStore
import com.example.phonelab.PulseRefreshPlanner
import com.example.phonelab.systems.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Layout-independent actions for the Lab variant. The existing Elsewhere DB is resolved
 * by the application, never opened by the offline proof of concept. No network or key access here.
 */
class StoryPhoneActions(private val database: AppDatabase, context: Context) : AutoCloseable {
    private val phone = PhoneStore(context)
    private val pulseDirectory = context.noBackupFilesDir.toPath().resolve("phone-pulse")

    data class Target(val chatId: String, val personaId: String)
    data class Contact(val id: String, val name: String, val role: ContactRole, val introducedIn: String? = null)
    data class Inbox(val worldId: String, val campaignId: String, val chapterId: String, val contacts: List<Contact>)
    data class SceneContext(val participantMemory: List<String>, val playthroughLore: List<String>)
    data class PendingText internal constructor(
        val target: Target, val contactId: String, val messageId: String, val timestamp: Long,
        val request: PhoneTextRequest, internal val binding: String, internal val portrayal: String
    )

    class FeedPreview internal constructor(
        internal val target: Target,
        internal val store: PulseImportStore,
        val content: PulseImportStore.Preview,
    )

    private data class Resolved(
        val scope: PhoneScope, val chapterId: String, val ordinal: Int,
        val available: List<Contact>, val currentActors: Set<String>
    )

    /** A phone requires an actual chapter and a Persona attached to it. */
    suspend fun inbox(target: Target): Inbox = local {
        val state = resolve(target)
        Inbox(state.scope.worldId,state.scope.campaignId,state.chapterId,state.available)
    }

    suspend fun messages(target: Target, contactId: String): List<PhoneMessage> = local {
        val state=resolve(target)
        phone.history(state.thread(contactId),state.ordinal,120)
    }

    /** Persists the outgoing text and prepares one contained request, but does not send it. */
    suspend fun prepareText(target: Target, contactId: String, messageId: String, text: String, timestamp: Long): PendingText = local {
        val state=resolve(target)
        val thread=register(state,contactId)
        phone.savePersonaText(thread,messageId,state.ordinal,text,timestamp)
        prepareExistingReply(state, target, contactId, messageId, timestamp)
    }

    suspend fun retryText(target: Target, contactId: String, pendingId: String): PendingText = local {
        val state = resolve(target)
        val current = phone.history(state.thread(contactId), state.ordinal, 1).singleOrNull()
        require(current != null && current.id == pendingId && current.speaker == Speaker.PERSONA && current.chapter == state.ordinal) {
            "This text can't be retried from this chapter."
        }
        prepareExistingReply(state, target, contactId, pendingId, current.timestamp)
    }

    private suspend fun prepareExistingReply(
        state: Resolved, target: Target, contactId: String, messageId: String, timestamp: Long
    ): PendingText {
        val thread = state.thread(contactId)
        val portrayal = when {
            contactId.startsWith("character:") -> database.characterDao().getCharacterById(contactId.substringAfter(':'))?.let {
                listOf("Name: ${it.characterName}", it.personalityJson, it.voiceJson)
            }
            contactId.startsWith("npc:") -> database.npcDao().getDossier(contactId.substringAfter(':'))?.let {
                listOf("Name: ${it.displayName}", it.role, it.personality)
            }
            else -> null
        }.orEmpty().filterNotNull().filter(String::isNotBlank).joinToString("\n").take(2400)
        return PendingText(target, contactId, messageId, timestamp, phone.prepareReply(thread,state.ordinal,messageId),
            state.chapterId, portrayal)
    }

    /** For mock/testing or a future explicitly invoked provider adapter; never writes a Persona reply. */
    suspend fun storeContactReply(pending: PendingText, replyId: String, text: String, timestamp: Long) = local {
        val state=resolve(pending.target)
        require(pending.binding == state.chapterId) { "The chapter changed before this reply arrived." }
        val thread=register(state,pending.contactId)
        phone.commitContactReply(thread,pending.messageId,replyId,state.ordinal,text,timestamp)
    }

    suspend fun saveSelected(
        target: Target, contactId: String, approvedId: String, selectedIds: List<String>,
        reviewedText: String, destination: Destination
    ): PhoneCarry = local {
        val state=resolve(target)
        val thread=state.thread(contactId)
        phone.saveApproved(thread,approvedId,state.ordinal,destination,reviewedText,selectedIds)
    }

    /** Stores the reviewed note on this playthrough's Campaign, not a narrator-wide or World prompt. */
    suspend fun saveCampaignNote(target: Target, contactId: String, carryId: String, title: String): String = local {
        require(title.isNotBlank() && title.length <= 160) { "Use a title of 1 to 160 characters." }
        val state = resolve(target)
        val source = requireNotNull(phone.playthroughNote(state.thread(contactId), carryId, state.ordinal)) {
            "Select a reviewed note from this thread."
        }
        val itemId = "phone-note-" + digest(listOf(state.scope.worldId,state.scope.campaignId,state.scope.personaId,contactId,carryId))
        database.withTransaction {
            val dao = database.campaignDao()
            if (dao.getContinuityItem(itemId) == null) dao.insertContinuityItem(CampaignContinuityItemEntity(
                itemId = itemId, campaignId = state.scope.campaignId, openedChapterId = state.chapterId,
                itemType = "DISCOVERY", title = title, details = source.approvedText
            ))
        }
        itemId
    }

    suspend fun pendingCanonDraftIds(target: Target, contactId: String): List<String> = local {
        val state=resolve(target)
        phone.pendingWorldDrafts(state.thread(contactId),state.ordinal).map { it.id }
    }

    /**
     * Copies only the specifically reviewed text to the existing World review UI as a DRAFT.
     * It never accepts canon, and a repeat of the same approved item does not duplicate it.
     */
    suspend fun queueWorldReview(target: Target, contactId: String, approvedId: String, title: String): String = local {
        require(title.isNotBlank() && title.length <= 160) { "Use a title of 1 to 160 characters." }
        val state=resolve(target)
        val source=requireNotNull(phone.pendingWorldDrafts(state.thread(contactId),state.ordinal).find { it.id==approvedId }) {
            "Select a saved World Canon draft from this contact."
        }
        val eventId="phone-draft-" + digest(listOf(state.scope.worldId,state.scope.campaignId,state.scope.personaId,contactId,approvedId))
        database.withTransaction {
            val dao=database.worldDao()
            val existing=dao.getEvent(eventId)
            if(existing==null) dao.insertEvent(WorldContinuityEventEntity(eventId=eventId,
                worldId=state.scope.worldId,title=title.trim(),facts=source.approvedText,status="DRAFT",sourceChatId=target.chatId))
            else require(existing.worldId == state.scope.worldId) { "Draft identifier is already in use." }
        }
        eventId
    }

    /** No narrator-wide memory. An actor must be actually present in the current chapter roster. */
    suspend fun sceneContext(target: Target, actorContactId: String?, replay: Boolean = false): SceneContext = local {
        if (replay) return@local SceneContext(emptyList(),emptyList())
        val state=resolve(target)
        val memory = if (actorContactId != null && actorContactId in state.currentActors)
            phone.approvedStoryMemory(state.scope,actorContactId,state.ordinal) else emptyList()
        SceneContext(memory,phone.approvedPlaythroughLore(state.scope,state.ordinal))
    }

    suspend fun previewFeedPack(target: Target, bytes: ByteArray): FeedPreview = local {
        val state=resolve(target)
        val store=storeFor(state)
        FeedPreview(target,store,store.preview(bytes,state.scope.worldId,state.scope.campaignId))
    }

    suspend fun importReviewedPack(preview: FeedPreview, useStarter: Boolean): PulseImportStore.Receipt = local {
        val state=resolve(preview.target)
        require(state.scope.worldId==preview.content.worldId && state.scope.campaignId==preview.content.campaignId) {
            "The chapter changed after preview. Open the import again."
        }
        preview.store.apply(preview.content,useStarter)
    }

    suspend fun manualFeedPreview(target: Target, starter: String, platform: String, events: List<String>) = local {
        val state=resolve(target)
        PulseRefreshPlanner(storeFor(state)).prepareManualRefresh(state.scope.worldId,state.scope.campaignId,starter,platform,events)
    }

    data class FeedReply(val byline: String, val kind: String, val text: String)
    data class FeedPost(val id: String, val byline: String, val kind: String, val text: String, val replies: List<FeedReply>)
    data class FeedChannel(val id: String, val name: String, val posts: List<FeedPost>)
    data class FeedPack(val id: String, val name: String, val channels: List<FeedChannel>)

    /** Display only the selected campaign's reviewed starter. World-only templates have no posts. */
    suspend fun feed(target: Target): List<FeedPack> = local {
        val state = resolve(target)
        val store = storeFor(state)
        val templates = store.templates(state.scope.worldId)
        val starters = store.campaignStarters(state.scope.worldId, state.scope.campaignId)
        val pure = templates.filterKeys { id -> starters.values.none { it.templateId == id } }
            .map { (id, template) -> Triple(id, template, null) }
        val withPosts = starters.mapNotNull { (id, starter) ->
            templates[starter.templateId]?.let { Triple(id, it, starter.stateJson) }
        }
        (withPosts + pure).map { (id, template, starter) ->
            val config = JSONObject(template)
            val accounts = config.getJSONArray("accounts").objects().associateBy { it.getString("id") }
            val posts = starter?.let { JSONObject(it).getJSONArray("posts").objects() }.orEmpty()
            fun byline(account: JSONObject) = "${account.getString("displayName")} · @" + account.getString("handle").removePrefix("@")
            FeedPack(id, config.getString("name"), config.getJSONArray("platforms").objects().map { platform ->
                val platformId = platform.getString("id")
                FeedChannel(platformId, platform.getString("name"), posts.mapNotNull { post ->
                    val owner = accounts[post.getString("accountId")]?.takeIf { it.getString("platformId") == platformId }
                        ?: return@mapNotNull null
                    FeedPost(post.getString("id"), byline(owner), post.getString("kind"), post.getString("text"),
                        post.optJSONArray("replies")?.objects().orEmpty().mapNotNull { reply ->
                            accounts[reply.getString("accountId")]?.let { author ->
                                FeedReply(byline(author), reply.getString("kind"), reply.getString("text"))
                            }
                        })
                })
            })
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

    private suspend fun resolve(target: Target): Resolved = database.withTransaction {
        val chats=database.chatDao()
        val campaignDao=database.campaignDao()
        val chapter=requireNotNull(campaignDao.chapterForChat(target.chatId)) { "Link this chat as a campaign chapter to use the Phone." }
        val campaign=requireNotNull(campaignDao.getCampaign(chapter.campaignId))
        require(chapter.ordinal in 1..Int.MAX_VALUE.toLong()) { "Chapter number is out of range." }
        require(chats.getPersonaParticipantsForChat(target.chatId).first().any { it.personaId==target.personaId && it.isActive }) {
            "This Persona is not active in the selected chapter."
        }
        val summary=requireNotNull(chats.getChatSummaryById(target.chatId))
        val leadIds=chats.getParticipantsForChatOnce(target.chatId).filter { it.isActive }.map { it.characterId }.toSet()
        val leads=summary.characters.filter { it.characterId in leadIds }.map {
            Contact("character:${it.characterId}",it.displayName,ContactRole.LEAD)
        }
        val prior=campaignDao.chapters(campaign.campaignId).filter { it.ordinal<=chapter.ordinal }
        val npcs=prior.flatMap { earlier ->
            val eligible=earlier.chatId==target.chatId || chats.getPersonaParticipantsForChat(earlier.chatId).first()
                .any { it.personaId==target.personaId && it.isActive }
            if(!eligible) emptyList() else database.npcDao().getDossiers(earlier.chatId).map {
                Contact("npc:${it.npcId}",it.displayName,ContactRole.NPC,earlier.title)
            }
        }
        val currentNpcIds=database.npcDao().getDossiers(target.chatId).map { "npc:${it.npcId}" }
        Resolved(PhoneScope(campaign.worldId,campaign.campaignId,target.personaId),chapter.chapterId,chapter.ordinal.toInt(),
            leads+npcs,(leads.map { it.id } + currentNpcIds).toSet())
    }

    private fun Resolved.thread(contactId: String): ThreadKey {
        require(available.any { it.id==contactId }) { "This contact is not available in the selected chapter." }
        return ThreadKey(scope,contactId)
    }

    private fun register(state: Resolved, contactId: String): ThreadKey = state.thread(contactId).also { key ->
        val contact=state.available.first { it.id==contactId }
        phone.registerContact(key,contact.role,contact.name)
    }

    private fun storeFor(state: Resolved) = PulseImportStore(pulseDirectory,object:PulseImportStore.Membership {
        override fun worldExists(worldId: String) = state.scope.worldId==worldId
        override fun campaignBelongsToWorld(worldId: String, campaignId: String) =
            state.scope.worldId==worldId && state.scope.campaignId==campaignId
    })

    private fun digest(parts: List<String>): String =
        MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }

    private suspend fun <T> local(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
    override fun close() = phone.close()
}
