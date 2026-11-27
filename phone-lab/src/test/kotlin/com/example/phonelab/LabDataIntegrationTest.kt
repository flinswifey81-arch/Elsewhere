package com.example.phonelab

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.phonelab.systems.Destination
import com.example.phonelab.systems.Speaker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LabDataIntegrationTest {
    @Test fun proofOfConceptPathsWriteAndReloadOnlyLabData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hero = labStories[0]
        val idol = labStories[1]
        val alternate = labStories[2]
        val lead=hero.contacts[0]
        val npc=hero.contacts[1]
        val onlyInText="private rehearsal notebook"
        LabData(context).use { lab ->
            for (story in labStories) lab.initialize(story)
            lab.storeDraft(hero,"Return to the elevator")
            val thread=lab.sendDemo(hero,lead,onlyInText)
            assertEquals(listOf(Speaker.CONTACT,Speaker.PERSONA,Speaker.CONTACT),thread.map { it.speaker })
            lab.save(hero,lead,thread.takeLast(2).map { it.id },"Meet after dispatch",Destination.STORY_MEMORY)
            lab.save(hero,npc,lab.history(hero,npc).map { it.id },"Drills take place on the roof",Destination.PLAYTHROUGH_LORE)
            lab.setPulse(hero,false)
            assertFalse(lab.pulseEnabled(hero))
            assertEquals(1,lab.history(hero,npc).size)
        }
        LabData(context).use { lab ->
            lab.initialize(hero)
            assertEquals("Return to the elevator",lab.loadDraft(hero))
            assertEquals("",lab.loadDraft(alternate))
            assertTrue(lab.history(hero,lead).any { onlyInText in it.text })
            assertTrue(lab.history(alternate,alternate.contacts[0]).none { onlyInText in it.text })
            assertEquals(listOf("Meet after dispatch"),lab.inboundStory(hero).memories)
            assertEquals(listOf("Drills take place on the roof"),lab.inboundStory(hero).lore)
            assertTrue(lab.inboundStory(alternate).memories.isEmpty())
            assertEquals("HeroConnect",lab.feed(hero).posts.single().platform)
            assertEquals("Encore",lab.feed(idol).posts.single().platform)
            assertTrue(lab.feed(alternate).posts.isEmpty())
            assertTrue(lab.feed(alternate).worldTemplates > 0)
            assertTrue(onlyInText !in lab.firstRefreshPreview(hero))
            val preview=context.assets.open("Afterglow.json").use { lab.preview(alternate,it) }
            assertTrue(lab.feed(alternate).posts.isEmpty())
            lab.applyPack(preview,false)
            assertTrue(lab.feed(alternate).posts.isEmpty())
            lab.applyPack(preview,true)
            assertEquals("Encore",lab.feed(alternate).posts.single().platform)
            assertEquals("HeroConnect",lab.feed(hero).posts.single().platform)
        }
    }
}
