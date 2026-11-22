package com.example

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.data.APP_DATABASE_VERSION
import com.example.data.AppDatabase
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exact native v15 Room fixture is emitted by code compiled at pinned v15 SHA,
 * not by table-version spoofing or a recreated subset of v15 DDL.
 *
 * The v15 fixture is disposable and synthetic. Real signed-app / encrypted
 * backup compatibility requires separate physical-device QA.
 */
@RunWith(RobolectricTestRunner::class)
class FullSchemaV15ToV21MigrationTest {
    private lateinit var context: Context
    private val created = mutableListOf<String>()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After fun tearDown() {
        created.forEach(context::deleteDatabase)
    }

    private fun nativeV15Fixture(): File {
        val raw = System.getenv("ELSEWHERE_V15_FIXTURE_PATH")
        // Ordinary developer test suites may omit this two-checkout acceptance
        // test; the dedicated workflow supplies REQUIRE=1 and cannot skip.
        if (System.getenv("ELSEWHERE_REQUIRE_V15_FIXTURE") != "1") {
            assumeTrue("Run through the pinned v15→v21 acceptance workflow.", false)
        }
        val path = requireNotNull(raw) { "Missing pinned native v15 fixture." }
        val file = File(path)
        require(file.isFile && file.length() > 30_000L) {
            "Native full-schema v15 fixture is absent or unexpectedly small."
        }
        return file
    }

    private fun copyFixture(name: String): File {
        val to = context.getDatabasePath(name)
        to.parentFile?.mkdirs()
        context.deleteDatabase(name)
        Files.copy(nativeV15Fixture().toPath(), to.toPath(),
            StandardCopyOption.REPLACE_EXISTING)
        created += name
        return to
    }

    private fun migratedRoom(name: String, sabotaged: Boolean = false): AppDatabase {
        val migration18To19 = if (sabotaged) object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppDatabase.MIGRATION_18_19.migrate(db)
                // A synthetic crash AFTER several prior upgrades, but BEFORE
                // transaction commit. SQLiteOpenHelper must roll back fully.
                throw IllegalStateException("SYNTHETIC_INTERRUPTED_V15_TO_V21")
            }
        } else AppDatabase.MIGRATION_18_19

        return Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(
                AppDatabase.MIGRATION_15_16,
                AppDatabase.MIGRATION_16_17,
                AppDatabase.MIGRATION_17_18,
                migration18To19,
                AppDatabase.MIGRATION_19_20,
                AppDatabase.MIGRATION_20_21,
                AppDatabase.MIGRATION_21_22,
                AppDatabase.MIGRATION_22_23,
                AppDatabase.MIGRATION_23_24,
                AppDatabase.MIGRATION_24_25
            )
            .allowMainThreadQueries().build()
    }

    private data class LegacyTable(
        val columns: List<String>,
        val rowDigests: List<String>
    )
    private data class LegacySnapshot(
        val sqliteVersion: Int,
        val tables: Map<String, LegacyTable>
    )

    private fun quoteName(name: String): String =
        "\"" + name.replace("\"", "\"\"") + "\""

    private fun digestRows(
        sql: SQLiteDatabase, table: String, columns: List<String>
    ): List<String> {
        val projection = columns.joinToString(",") { quoteName(it) }
        val result = mutableListOf<String>()
        sql.rawQuery("SELECT $projection FROM ${quoteName(table)}", null).use { cursor ->
            while (cursor.moveToNext()) {
                val bytes = ByteArrayOutputStream()
                DataOutputStream(bytes).use { output ->
                    for (index in columns.indices) {
                        when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> output.writeByte(0)
                            Cursor.FIELD_TYPE_INTEGER -> {
                                output.writeByte(1)
                                output.writeLong(cursor.getLong(index))
                            }
                            Cursor.FIELD_TYPE_FLOAT -> {
                                output.writeByte(2)
                                output.writeLong(
                                    java.lang.Double.doubleToLongBits(cursor.getDouble(index))
                                )
                            }
                            Cursor.FIELD_TYPE_STRING -> {
                                output.writeByte(3)
                                val value = cursor.getString(index).toByteArray(Charsets.UTF_8)
                                output.writeInt(value.size)
                                output.write(value)
                            }
                            Cursor.FIELD_TYPE_BLOB -> {
                                output.writeByte(4)
                                val value = cursor.getBlob(index)
                                output.writeInt(value.size)
                                output.write(value)
                            }
                            else -> error("Unexpected SQLite value type")
                        }
                    }
                }
                result += MessageDigest.getInstance("SHA-256")
                    .digest(bytes.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }
        }
        return result.sorted()
    }

    private fun withReadOnlySql(file: File, action: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openDatabase(
            file.absolutePath, null, SQLiteDatabase.OPEN_READONLY
        ).use(action)
    }

    private fun snapshot(file: File): LegacySnapshot {
        var value: LegacySnapshot? = null
        withReadOnlySql(file) { sql ->
            val version = sql.rawQuery("PRAGMA user_version", null).use {
                assertTrue(it.moveToFirst())
                it.getInt(0)
            }
            val names = mutableListOf<String>()
            sql.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' " +
                    "AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table' " +
                    "ORDER BY name",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) names += cursor.getString(0)
            }
            val tables = names.associateWith { table ->
                val cols = mutableListOf<String>()
                sql.rawQuery("PRAGMA table_info(${quoteName(table)})", null).use { cursor ->
                    while (cursor.moveToNext()) cols += cursor.getString(1)
                }
                LegacyTable(cols, digestRows(sql, table, cols))
            }
            value = LegacySnapshot(version, tables)
        }
        return requireNotNull(value)
    }

    private fun verifyAllLegacyRowsRemain(
        original: LegacySnapshot,
        file: File
    ) {
        withReadOnlySql(file) { sql ->
            val actualVersion = sql.rawQuery("PRAGMA user_version", null).use {
                assertTrue(it.moveToFirst())
                it.getInt(0)
            }
            assertEquals(APP_DATABASE_VERSION, actualVersion)
            for ((table, legacy) in original.tables) {
                val presentColumns = mutableListOf<String>()
                sql.rawQuery("PRAGMA table_info(${quoteName(table)})", null).use { cursor ->
                    while (cursor.moveToNext()) presentColumns += cursor.getString(1)
                }
                assertTrue("Legacy table disappeared: $table", presentColumns.isNotEmpty())
                assertTrue(
                    "Old columns disappeared from $table",
                    presentColumns.containsAll(legacy.columns)
                )
                assertEquals(
                    "Original v15 data changed in $table",
                    legacy.rowDigests, digestRows(sql, table, legacy.columns)
                )
            }
            sql.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ok", cursor.getString(0))
            }
            val newTables = listOf(
                "story_memories", "story_memory_actors", "story_memory_returns",
                "story_memory_knowledge_changes", "story_memory_perspectives"
            )
            newTables.forEach { table ->
                sql.rawQuery("SELECT COUNT(*) FROM ${quoteName(table)}", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("V15 must not manufacture player decisions in $table",
                        0, cursor.getInt(0))
                }
            }
        }
    }

    private fun assertRichFixture(before: LegacySnapshot) {
        assertEquals(15, before.sqliteVersion)
        assertTrue(
            "Expected all v15 Room entities, not a partial migration stub",
            before.tables.size >= 30
        )
        for (table in listOf(
            "characters", "personas", "chats", "messages",
            "chat_participants", "chat_persona_participants",
            "chat_settings", "roleplay_memories", "conversation_summaries",
            "generation_metadata", "relationships", "lorebooks",
            "lorebook_entries", "chat_lorebook_attachments",
            "chat_lorebook_entry_overlays", "worlds", "world_memberships",
            "world_cast", "world_continuity_events", "scenarios",
            "scenario_sessions", "npc_dossiers", "npc_dossier_updates",
            "npc_library_banks", "npc_library_entries",
            "message_attachments", "coop_rooms", "coop_participants",
            "coop_persona_ownership", "coop_events", "coop_message_tombstones"
        )) assertTrue("Unseeded real v15 domain: $table",
            before.tables[table]?.rowDigests?.isNotEmpty() == true)
    }

    @Test fun realRoom15CompleteSchemaUpgradesTo21WithoutChangingAnyOldRows() = runBlocking {
        val name = "qa-v15-upgrade-success.db"
        val file = copyFixture(name)
        val before = snapshot(file)
        assertRichFixture(before)
        val room = migratedRoom(name)
        try {
            // Force Room's transactional migration and generated schema
            // validation; constructing the builder alone is insufficient.
            room.openHelper.writableDatabase
            assertEquals("Synthetic Solo",
                room.chatDao().getChatById("qa-solo")?.displayName)
            assertEquals(3L,
                room.chatDao().getChatById("qa-solo")?.contextResetOrderIndex)
            val variants = room.messageDao().getVariantsOnce("turn1-variants")
            assertEquals(2, variants.size)
            assertEquals(1, variants.count { it.isPrimaryVariant })
            assertEquals("qa-turn1-selected",
                variants.single { it.isPrimaryVariant }.messageId)
            assertEquals("The key belongs to Mara.",
                room.memoryDao().getMemoriesOnce("qa-solo", "rowan").single().content)
            assertEquals(
                "The key changed hands; the copper variant was discarded.",
                room.memoryDao().getSummaryOnce("qa-solo")?.summary
            )
        } finally {
            room.close()
        }
        verifyAllLegacyRowsRemain(before, file)
        val after = snapshot(file)
        println(
            "ROOM15_TO_21_NATIVE_FULL_SCHEMA_PASS legacyTables=${before.tables.size} " +
                "legacyRows=${before.tables.values.sumOf { it.rowDigests.size }} " +
                "currentVersion=${after.sqliteVersion} providerCalls=0"
        )
    }

    @Test fun interruptedMigrationRollsBackAllChangesAndCanRetryFromOriginalV15() {
        val name = "qa-v15-upgrade-interrupted.db"
        val file = copyFixture(name)
        val before = snapshot(file)
        assertRichFixture(before)
        val interrupted = migratedRoom(name, sabotaged = true)
        try {
            val failure = runCatching { interrupted.openHelper.writableDatabase }
                .exceptionOrNull()
            assertNotNull("Injected upgrade failure must abort migration", failure)
            assertTrue(
                "Expected synthetic interruption, not an unrelated schema error: $failure",
                generateSequence(failure) { it.cause }
                    .any { it.message.orEmpty().contains("SYNTHETIC_INTERRUPTED_V15_TO_V21") }
            )
        } finally {
            runCatching { interrupted.close() }
        }
        val rolledBack = snapshot(file)
        assertEquals(
            "SQLite must retain v15 identity after an uncommitted migration",
            15, rolledBack.sqliteVersion
        )
        assertEquals(
            "An interrupted upgrade must preserve ALL legacy table data",
            before, rolledBack
        )

        val retried = migratedRoom(name)
        try {
            retried.openHelper.writableDatabase
        } finally {
            retried.close()
        }
        verifyAllLegacyRowsRemain(before, file)
        println(
            "ROOM15_TO_21_NATIVE_ROLLBACK_PASS legacyTables=${before.tables.size} " +
                "legacyRows=${before.tables.values.sumOf { it.rowDigests.size }} " +
                "originalVersion=15 retryVersion=$APP_DATABASE_VERSION providerCalls=0"
        )
    }
}
