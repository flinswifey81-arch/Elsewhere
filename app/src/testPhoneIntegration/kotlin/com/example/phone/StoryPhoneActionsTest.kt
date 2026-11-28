package com.example.phone

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.domain.repository.CampaignRepository
import com.example.domain.repository.WorldRepository
import com.example.phonelab.systems.ContactRole
import com.example.phonelab.systems.Destination
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StoryPhoneActionsTest {
    @Test fun actualWorldCampaignChapterAndIdentityDriveThePhone() = runBlocking<Unit> {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val worlds=WorldRepository(db)
            val campaigns=CampaignRepository(db)
            val world=worlds.createWorld("Racing AU")
            val shared=campaigns.createCampaign(world.worldId,"First season")
            val sibling=campaigns.createCampaign(world.worldId,"Another timeline")
            val player=PersonaEntity("shai","Shai","Shai",emptyList(),1,null,null,null,null,null,null,null,null)
            val other=player.copy(personaId="different",displayName="Shai")
            db.personaDao().insertPersona(player)
            db.personaDao().insertPersona(other)
            val lead=CharacterEntity("lead","Alex","Alex",emptyList(),1,null,null,null,null,null,null,null,null,null,null,null,null)
            val rival=lead.copy(characterId="rival")
            db.characterDao().insertCharacter(lead)
            db.characterDao().insertCharacter(rival)
            for(id in listOf("first","second","alternate","unlinked")) {
                db.chatDao().insertChat(ChatEntity(id,id,ChatType.SOLO))
                db.chatDao().insertChatPersonaParticipant(ChatPersonaParticipantEntity(id,player.personaId))
                db.chatDao().insertChatParticipant(ChatParticipantEntity(id,lead.characterId))
                if(id=="second") db.chatDao().insertChatParticipant(ChatParticipantEntity(id,rival.characterId))
            }
            db.npcDao().insertDossier(NpcDossierEntity(npcId="mechanic",chatId="first",displayName="Alex"))
            db.npcDao().insertDossier(NpcDossierEntity(npcId="other-mechanic",chatId="alternate",displayName="Alex"))
            campaigns.addChapter(shared.campaignId,"first","On the Grid")
            campaigns.addChapter(shared.campaignId,"second","Next Race")
            campaigns.addChapter(sibling.campaignId,"alternate","Alternate")
            val first=StoryPhoneActions.Target("first",player.personaId)
            val next=first.copy(chatId="second")
            val alt=first.copy(chatId="alternate")
            StoryPhoneActions(db,context).use { phone ->
                val inbox=phone.inbox(first)
                assertEquals(shared.campaignId,inbox.campaignId)
                assertEquals(setOf(ContactRole.LEAD, ContactRole.NPC),inbox.contacts.map { it.role }.toSet())
                assertEquals(listOf("Alex","Alex"),inbox.contacts.map { it.name })
                assertTrue(runCatching { phone.inbox(first.copy(personaId=other.personaId)) }.isFailure)
                assertTrue(runCatching { phone.inbox(first.copy(chatId="unlinked")) }.isFailure)
                assertTrue(runCatching { phone.messages(first,"npc:other-mechanic") }.isFailure)
                val pending=phone.prepareText(first,"character:lead","out","Private line; see you at the garage.",1L)
                val npcPending=phone.prepareText(first,"npc:mechanic","out","What time does the garage open?",2L)
                assertTrue(npcPending.request.history.none { "Private line" in it.text })
                phone.storeContactReply(pending,"in","Eight.",3L)
                phone.storeContactReply(npcPending,"in","Workshop at ten.",4L)
                phone.saveSelected(first,"character:lead","memory",listOf("out","in"),"Meet at eight.",Destination.STORY_MEMORY)
                phone.saveSelected(first,"npc:mechanic","lore",listOf("in"),"The garage has an open workshop.",Destination.PLAYTHROUGH_LORE)
                phone.saveSelected(first,"npc:mechanic","candidate",listOf("in"),"There is a public garage.",Destination.WORLD_CANON_DRAFT)
                val note=phone.saveCampaignNote(first,"npc:mechanic","lore","Workshop")
                assertEquals(note,phone.saveCampaignNote(first,"npc:mechanic","lore","Workshop"))
                val persistedNote = db.campaignDao().getContinuityItem(note)!!
                assertEquals(shared.campaignId, persistedNote.campaignId)
                assertEquals("The garage has an open workshop.", persistedNote.details)
                assertEquals("", persistedNote.resolution)
                val draft=phone.queueWorldReview(first,"npc:mechanic","candidate","Garage")
                assertEquals(draft,phone.queueWorldReview(first,"npc:mechanic","candidate","Garage"))
                val stored=db.worldDao().getEvent(draft)!!
                assertEquals("DRAFT",stored.status)
                assertEquals("There is a public garage.",stored.facts)
                assertFalse(stored.facts.contains("Private line"))
                assertEquals(listOf("Meet at eight."),phone.sceneContext(next,"character:lead").participantMemory)
                assertTrue(phone.sceneContext(next,"character:rival").participantMemory.isEmpty())
                assertTrue(phone.sceneContext(next,null).participantMemory.isEmpty())
                assertTrue(phone.sceneContext(next,"character:lead",replay=true).participantMemory.isEmpty())
                assertEquals(listOf("The garage has an open workshop."),phone.sceneContext(next,null).playthroughLore)
                assertTrue(phone.sceneContext(alt,"character:lead").participantMemory.isEmpty())
                assertTrue(phone.sceneContext(alt,null).playthroughLore.isEmpty())
                assertEquals(2,phone.messages(next,"npc:mechanic").size)
                assertTrue(phone.messages(alt,"character:lead").isEmpty())
                val old=phone.prepareText(first,"character:lead","old","Older text",5L)
                phone.prepareText(first,"character:lead","new","Newer text",6L)
                assertTrue(runCatching { phone.storeContactReply(old,"stale","Too late",7L) }.isFailure)
                val preview=phone.previewFeedPack(first, pack.toByteArray())
                val imported=phone.importReviewedPack(preview,true)
                val visible = phone.feed(first).single()
                assertEquals("Garage Board", visible.channels.single().name)
                assertEquals(listOf("Local rumor"), visible.channels.single().posts.map { it.text })
                assertTrue(phone.feed(alt).single().channels.single().posts.isEmpty())
                val request=phone.manualFeedPreview(first,checkNotNull(imported.starterId),"garage",listOf("workshop"))
                assertEquals("A public workshop is on the calendar.", request.events.single().summary)
                assertFalse(request.toString().contains("Private line"))
                assertTrue(runCatching { phone.manualFeedPreview(alt,checkNotNull(imported.starterId),"garage",listOf("workshop")) }.isFailure)
            }
        } finally { db.close() }
    }
    private companion object {
        val pack="""{"format":"elsewhere.world-pulse.feed-pack","version":1,"name":"Garage","platforms":[{"id":"garage","name":"Garage Board","style":"forum"}],"accounts":[{"id":"shop","platformId":"garage","handle":"theshop","displayName":"The Shop","role":"Business"}],"starterState":{"publicEvents":[{"id":"workshop","visibility":"public","summary":"A public workshop is on the calendar."}],"posts":[{"id":"tip","accountId":"shop","kind":"rumor","text":"Local rumor"}]}}"""
    }
}
