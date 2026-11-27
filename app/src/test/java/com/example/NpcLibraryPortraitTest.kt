package com.example

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.NpcDossierEntity
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.NpcRepository
import com.example.domain.repository.ProfileImageKind
import com.example.domain.repository.ProfileImageRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NpcLibraryPortraitTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var database: AppDatabase
    private lateinit var repository: NpcRepository
    private lateinit var images: ProfileImageRepository
    private val sources = mutableListOf<File>()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val coordinator = AppDataMaintenanceCoordinator()
        images = ProfileImageRepository(context, coordinator)
        repository = NpcRepository(
            database, Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build(), coordinator, images
        )
    }

    @After
    fun tearDown() {
        database.close()
        sources.forEach { it.delete() }
        File(context.filesDir, "profile_images").deleteRecursively()
    }

    @Test
    fun chatPortraitFollowsSavedNpcIntoNewChatWithDifferentStableId() = runBlocking {
        database.npcDao().insertDossier(NpcDossierEntity(npcId = "origin", chatId = "episode-1", displayName = "Mara"))
        repository.replaceNpcPortrait("episode-1", "origin", source(12))
        val bank = repository.saveChatToLibrary("episode-1", "The cast")
        val entry = repository.getLibraryBank(bank)!!.entries.single()
        assertTrue(repository.hasLibraryPortraitLink("origin"))
        assertArrayEquals(portrait("origin"), libraryPortrait(entry.libraryNpcId))

        assertEquals(1, repository.addLibraryNpcsToChat("episode-2", bank))
        val reused = repository.getProjectedDossiers("episode-2").single()
        assertNotEquals("origin", reused.npcId)
        assertArrayEquals(portrait("origin"), portrait(reused.npcId))
        assertFalse(repository.exportJson("episode-2", includePrivateFields = true).contains(entry.libraryNpcId))
    }

    @Test
    fun pickingFromImportedChatUpdatesOnlyTheExactLibraryEntryForFutureCopies() = runBlocking {
        val bank = repository.importLibraryBank("Duplicate labels", """
            {"schema_version":1,"npcs":[{"npc_id":"one","display_name":"Mara","role":"Archivist"},
            {"npc_id":"two","display_name":"Mara","role":"Scout"}]}
        """.trimIndent())
        val entries = repository.getLibraryBank(bank)!!.entries
        val archivist = entries.single { it.role == "Archivist" }
        val scout = entries.single { it.role == "Scout" }
        images.replaceImage(ProfileImageKind.NPC_LIBRARY, scout.libraryNpcId, source(13))

        repository.addLibraryNpcsToChat("prior", bank, setOf(archivist.libraryNpcId))
        repository.addLibraryNpcsToChat("origin", bank, setOf(archivist.libraryNpcId))
        val origin = repository.getProjectedDossiers("origin").single()
        repository.saveManual(origin.copy(displayName = "Mara of the Archive"))
        repository.replaceNpcPortrait("origin", origin.npcId, source(16))
        val expected = portrait(origin.npcId)
        assertArrayEquals(expected, libraryPortrait(archivist.libraryNpcId))
        assertFalse(expected.contentEquals(libraryPortrait(scout.libraryNpcId)))
        val prior = repository.getProjectedDossiers("prior").single()
        assertNull(images.resolveImage(ProfileImageKind.NPC, prior.npcId))

        assertEquals(2, repository.addLibraryNpcsToChat("episode-2", bank))
        val reused = repository.getProjectedDossiers("episode-2")
        assertArrayEquals(expected, portrait(reused.single { it.role == "Archivist" }.npcId))
        assertArrayEquals(libraryPortrait(scout.libraryNpcId), portrait(reused.single { it.role == "Scout" }.npcId))
        repository.deleteLibraryBank(bank)
        assertNull(images.resolveImage(ProfileImageKind.NPC_LIBRARY, archivist.libraryNpcId))
        assertArrayEquals(expected, portrait(reused.single { it.role == "Archivist" }.npcId))
    }

    private fun portrait(id: String): ByteArray =
        requireNotNull(images.resolveImage(ProfileImageKind.NPC, id)).readBytes()

    private fun libraryPortrait(id: String): ByteArray =
        requireNotNull(images.resolveImage(ProfileImageKind.NPC_LIBRARY, id)).readBytes()

    private fun source(width: Int): Uri {
        val file = File.createTempFile("npc-portrait-", ".png", context.cacheDir)
        sources += file
        val bitmap = Bitmap.createBitmap(width, 8, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return Uri.fromFile(file)
    }
}
