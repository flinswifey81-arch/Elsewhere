package com.example.phonelab.systems

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Private Lab store. No access to the production database or model provider. */
class PhoneStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "elsewhere-phone-lab-private.db", null, 1) {
    data class Contact(val id: String, val role: ContactRole, val name: String)
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) { SCHEMA.forEach { db.execSQL(it) } }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("No Phone Lab migration from $oldVersion to $newVersion")
    }

    fun registerContact(key: ThreadKey, role: ContactRole, name: String) {
        val value = key.values().apply { put("role", role.name); put("name", boundedText(name, 120)) }
        writableDatabase.inTransaction {
            if (update("phone_contact", value, THREAD, key.args()) == 0) insertOrThrow("phone_contact", null, value)
        }
    }
    fun contacts(scope: PhoneScope): List<Contact> = readableDatabase.queryList(
        "SELECT contact,role,name FROM phone_contact WHERE world=? AND campaign=? AND persona=? ORDER BY role,name,contact",
        arrayOf(scope.worldId, scope.campaignId, scope.personaId)
    ) { Contact(getString(0), ContactRole.valueOf(getString(1)), getString(2)) }

    fun savePersonaText(key: ThreadKey, messageId: String, ordinal: Int, text: String, timestamp: Long) {
        append(key, messageId, ordinal, Speaker.PERSONA, text, timestamp)
    }
    fun saveContactReply(key: ThreadKey, messageId: String, ordinal: Int, text: String, timestamp: Long) {
        append(key, messageId, ordinal, Speaker.CONTACT, text, timestamp)
    }
    private fun append(key: ThreadKey, messageId: String, ordinal: Int, speaker: Speaker, text: String, stamp: Long) {
        val candidate = PhoneMessage(stableId(messageId), chapter(ordinal), speaker, boundedText(text, 4000), stamp)
        writableDatabase.inTransaction {
            val previous = find(key, messageId)
            if (previous == null) insertOrThrow("phone_text", null, key.values().apply {
                put("id", messageId); put("chapter", ordinal); put("speaker", speaker.name); put("body", text); put("stamp", stamp)
            }) else require(previous == candidate) { "Message ID already used" }
        }
    }
    fun prepareReply(key: ThreadKey, ordinal: Int, pendingPersonaMessageId: String): PhoneTextRequest =
        readableDatabase.inTransaction {
            val contact = requireNotNull(contacts(key.scope).find { it.id == key.contactId }) { "Contact is not in this phone" }
            PhoneTextRequest.compile(contact.name, pendingPersonaMessageId, history(key, ordinal, PhoneTextRequest.MAX_ITEMS))
        }

    /** Earlier chapters cannot see later texts. */
    fun history(key: ThreadKey, cutoff: Int, maxItems: Int): List<PhoneMessage> {
        require(maxItems in 1..200) { "Invalid history limit" }
        return readableDatabase.queryList(
            "SELECT id,chapter,speaker,body,stamp FROM phone_text WHERE $THREAD AND chapter<=? ORDER BY chapter DESC,seq DESC LIMIT ?",
            key.args(chapter(cutoff).toString(), maxItems.toString()), { message() }
        ).asReversed()
    }

    /** Store only the user's explicit approved note or quote, with same-thread provenance. */
    fun saveApproved(key: ThreadKey, saveId: String, ordinal: Int, destination: Destination, text: String,
                     selectedMessageIds: List<String>): PhoneCarry {
        val ids = selectedMessageIds.map(::stableId)
        require(ids.size in 1..32 && ids.distinct().size == ids.size) { "Select 1 to 32 distinct source messages" }
        val candidate = PhoneCarry(stableId(saveId), key, destination, boundedText(text, 12000), ids, chapter(ordinal))
        return writableDatabase.inTransaction {
            val previous = carry(key, saveId)
            if (previous != null) require(previous == candidate) { "Save ID already used" }
            else {
                require(ids.all { find(key, it)?.chapter?.let { sourceChapter -> sourceChapter <= ordinal } == true }) {
                    "Source is outside this thread or chapter"
                }
                insertOrThrow("phone_carry", null, key.values().apply {
                    put("id", saveId); put("dest", destination.name); put("body", text); put("chapter", ordinal)
                })
                ids.forEachIndexed { index, message ->
                    insertOrThrow("phone_source", null, key.values().apply {
                        put("carry", saveId); put("message", message); put("position", index)
                    })
                }
            }
            candidate
        }
    }

    fun pendingWorldDrafts(key: ThreadKey, cutoff: Int): List<PhoneCarry> = readableDatabase.inTransaction {
        queryList(
            "SELECT id FROM phone_carry WHERE $THREAD AND dest='WORLD_CANON_DRAFT' AND chapter<=? ORDER BY chapter,rowid",
            key.args(chapter(cutoff).toString())
        ) { getString(0) }.mapNotNull { carry(key, it) }
    }
    fun editApprovedText(key: ThreadKey, saveId: String, newText: String): Boolean =
        writableDatabase.update("phone_carry", ContentValues().apply { put("body", boundedText(newText, 12000)) },
            "$THREAD AND id=?", key.args(stableId(saveId))) == 1
    fun removeCarry(key: ThreadKey, saveId: String): Boolean =
        writableDatabase.delete("phone_carry", "$THREAD AND id=?", key.args(stableId(saveId))) == 1

    /** Input scope and actor must be resolved from trusted story records, not user-presentable labels. */
    fun approvedStoryMemory(scope: PhoneScope, eligibleActorId: String, cutoff: Int): List<String> =
        readableDatabase.queryList(
            "SELECT body FROM phone_carry WHERE world=? AND campaign=? AND chapter<=? AND dest='STORY_MEMORY' AND persona=? AND (persona=? OR contact=?) ORDER BY chapter,rowid",
            arrayOf(scope.worldId, scope.campaignId, chapter(cutoff).toString(), scope.personaId,
                stableId(eligibleActorId), eligibleActorId)
        ) { getString(0) }

    fun approvedPlaythroughLore(scope: PhoneScope, cutoff: Int): List<String> =
        readableDatabase.queryList(
            "SELECT body FROM phone_carry WHERE world=? AND campaign=? AND chapter<=? AND dest='PLAYTHROUGH_LORE' ORDER BY chapter,rowid",
            arrayOf(scope.worldId, scope.campaignId, chapter(cutoff).toString())
        ) { getString(0) }

    private companion object {
        const val THREAD = "world=? AND campaign=? AND persona=? AND contact=?"
        const val SCHEMA_CONTACTS = "CREATE TABLE phone_contact (world TEXT NOT NULL,campaign TEXT NOT NULL,persona TEXT NOT NULL,contact TEXT NOT NULL,role TEXT NOT NULL CHECK(role IN ('LEAD','NPC')),name TEXT NOT NULL,PRIMARY KEY(world,campaign,persona,contact))"
        const val SCHEMA_TEXTS = "CREATE TABLE phone_text (seq INTEGER PRIMARY KEY AUTOINCREMENT,world TEXT NOT NULL,campaign TEXT NOT NULL,persona TEXT NOT NULL,contact TEXT NOT NULL,id TEXT NOT NULL,chapter INTEGER NOT NULL CHECK(chapter >= 1),speaker TEXT NOT NULL CHECK(speaker IN ('PERSONA','CONTACT')),body TEXT NOT NULL,stamp INTEGER NOT NULL,UNIQUE(world,campaign,persona,contact,id),FOREIGN KEY(world,campaign,persona,contact) REFERENCES phone_contact(world,campaign,persona,contact) ON DELETE CASCADE)"
        const val SCHEMA_CARRY = "CREATE TABLE phone_carry (world TEXT NOT NULL,campaign TEXT NOT NULL,persona TEXT NOT NULL,contact TEXT NOT NULL,id TEXT NOT NULL,dest TEXT NOT NULL CHECK(dest IN ('STORY_MEMORY','PLAYTHROUGH_LORE','WORLD_CANON_DRAFT')),body TEXT NOT NULL,chapter INTEGER NOT NULL CHECK(chapter >= 1),PRIMARY KEY(world,campaign,persona,contact,id),FOREIGN KEY(world,campaign,persona,contact) REFERENCES phone_contact(world,campaign,persona,contact) ON DELETE CASCADE)"
        const val SCHEMA_SOURCE = "CREATE TABLE phone_source (world TEXT NOT NULL,campaign TEXT NOT NULL,persona TEXT NOT NULL,contact TEXT NOT NULL,carry TEXT NOT NULL,message TEXT NOT NULL,position INTEGER NOT NULL,PRIMARY KEY(world,campaign,persona,contact,carry,message),FOREIGN KEY(world,campaign,persona,contact,carry) REFERENCES phone_carry(world,campaign,persona,contact,id) ON DELETE CASCADE,FOREIGN KEY(world,campaign,persona,contact,message) REFERENCES phone_text(world,campaign,persona,contact,id))"
        const val SCHEMA_TIMELINE = "CREATE INDEX phone_timeline ON phone_text(world,campaign,persona,contact,chapter,seq)"
        const val SCHEMA_PROJECTION = "CREATE INDEX phone_projection ON phone_carry(world,campaign,dest,chapter,persona,contact)"
        val SCHEMA = listOf(SCHEMA_CONTACTS, SCHEMA_TEXTS, SCHEMA_CARRY, SCHEMA_SOURCE, SCHEMA_TIMELINE, SCHEMA_PROJECTION)

        fun ThreadKey.args(vararg extra: String) = arrayOf(scope.worldId, scope.campaignId, scope.personaId, contactId, *extra)
        fun ThreadKey.values() = ContentValues().apply {
            put("world", scope.worldId); put("campaign", scope.campaignId); put("persona", scope.personaId); put("contact", contactId)
        }
        fun Cursor.message() = PhoneMessage(getString(0), getInt(1), Speaker.valueOf(getString(2)), getString(3), getLong(4))
        fun SQLiteDatabase.find(key: ThreadKey, id: String) = queryList(
            "SELECT id,chapter,speaker,body,stamp FROM phone_text WHERE $THREAD AND id=?", key.args(id), { message() }
        ).singleOrNull()
        fun SQLiteDatabase.carry(key: ThreadKey, id: String): PhoneCarry? = queryList(
            "SELECT dest,body,chapter FROM phone_carry WHERE $THREAD AND id=?", key.args(id)
        ) {
            val destination = Destination.valueOf(getString(0)); val text = getString(1); val ordinal = getInt(2)
            val links = queryList("SELECT message FROM phone_source WHERE $THREAD AND carry=? ORDER BY position", key.args(id)) { getString(0) }
            PhoneCarry(id, key, destination, text, links, ordinal)
        }.singleOrNull()
        fun <T> SQLiteDatabase.queryList(sql: String, args: Array<String>, row: Cursor.() -> T): List<T> =
            rawQuery(sql, args).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.row()) } }
        inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
            beginTransaction()
            try { return block().also { setTransactionSuccessful() } } finally { endTransaction() }
        }
    }
}
