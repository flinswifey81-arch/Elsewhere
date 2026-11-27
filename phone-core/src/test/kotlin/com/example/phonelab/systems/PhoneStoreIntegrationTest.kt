package com.example.phonelab.systems

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PhoneStoreIntegrationTest {
    @Test fun twoContactsAndTwoPlaythroughsKeepSeparateHistoryAndCarryAcrossChapters() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val phrase = "private office nickname"
        val a = PhoneScope("world","chaptered","lillypad")
        val b = a.copy(campaignId = "alternate")
        val otherPersona = a.copy(personaId = "another-persona")
        val lead = ThreadKey(a,"bakugo")
        val npc = ThreadKey(a,"dispatch")
        val otherLead = ThreadKey(b,"bakugo")

        PhoneStore(context).use { phone ->
            phone.registerContact(lead, ContactRole.LEAD, "Same display name")
            phone.registerContact(npc, ContactRole.NPC, "Same display name")
            phone.registerContact(otherLead, ContactRole.LEAD, "Same display name")
            phone.savePersonaText(lead,"a1",1,"What time?",1)
            phone.saveContactReply(lead,"a2",1,"Eight. $phrase",2)
            phone.savePersonaText(lead,"a3",3,"Are we still on?",3)
            phone.saveContactReply(npc,"n1",1,"Drill starts at nine.",4)
            phone.saveContactReply(otherLead,"a2",1,"Different timeline",5)
            phone.saveContactReply(otherLead,"a2",1,"Different timeline",5)
            assertEquals(setOf("bakugo","dispatch"), phone.contacts(a).map { it.id }.toSet())
            assertEquals(2, phone.history(lead,2,20).size)
            assertEquals(1, phone.history(otherLead,5,20).size)
            assertEquals(listOf("What time?", "Eight. $phrase", "Are we still on?"),
                phone.prepareReply(lead,3,"a3").history.map { it.text })
            assertThrows(IllegalArgumentException::class.java) {
                phone.prepareReply(lead,2,"a3")
            }
            assertThrows(IllegalArgumentException::class.java) {
                phone.saveApproved(lead,"invalid",1,Destination.STORY_MEMORY,"Not allowed",listOf("n1"))
            }
            assertThrows(IllegalArgumentException::class.java) {
                phone.saveApproved(lead,"invalid",1,Destination.STORY_MEMORY,"Not allowed",listOf("a3"))
            }
            val approved = phone.saveApproved(lead,"memory",2,Destination.STORY_MEMORY,"Meet at eight",listOf("a1","a2"))
            assertEquals(approved, phone.saveApproved(lead,"memory",2,Destination.STORY_MEMORY,"Meet at eight",listOf("a1","a2")))
            phone.saveApproved(npc,"memory",2,Destination.STORY_MEMORY,"Drill at nine",listOf("n1"))
            phone.saveApproved(lead,"lore",2,Destination.PLAYTHROUGH_LORE,"There is a rooftop garden",listOf("a2"))
            phone.saveApproved(lead,"draft",2,Destination.WORLD_CANON_DRAFT,"Unreviewed",listOf("a2"))
        }
        PhoneStore(context).use { phone ->
            assertEquals(listOf("Meet at eight"), phone.approvedStoryMemory(a,"bakugo",3))
            assertEquals(listOf("Drill at nine"), phone.approvedStoryMemory(a,"dispatch",3))
            assertEquals(listOf("Meet at eight","Drill at nine"), phone.approvedStoryMemory(a,"lillypad",3))
            assertTrue(phone.approvedStoryMemory(a,"bakugo",1).isEmpty())
            assertTrue(phone.approvedStoryMemory(b,"bakugo",3).isEmpty())
            assertTrue(phone.approvedStoryMemory(otherPersona,"bakugo",3).isEmpty())
            assertEquals(listOf("There is a rooftop garden"), phone.approvedPlaythroughLore(a,3))
            assertTrue(phone.approvedPlaythroughLore(b,3).isEmpty())
            assertEquals("Unreviewed", phone.pendingWorldDrafts(lead,3).single().approvedText)
            assertTrue(phone.editApprovedText(lead,"memory","Meet at the agency at eight"))
            assertEquals(listOf("Meet at the agency at eight"), phone.approvedStoryMemory(a,"bakugo",3))
            assertTrue(phone.removeCarry(lead,"memory"))
            assertTrue(phone.approvedStoryMemory(a,"bakugo",3).isEmpty())
            assertTrue(phone.history(lead,3,20).any { phrase in it.text })
        }
    }
}
