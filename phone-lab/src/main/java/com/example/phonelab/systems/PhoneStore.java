package com.example.phonelab.systems;

import static com.example.phonelab.systems.PhonePolicy.*;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Private Phone Lab store. The production database and the offline mockup are not modified. */
public final class PhoneStore extends SQLiteOpenHelper {
    public record Contact(String id, ContactRole role, String name) {}
    private static final String THREAD = "world=? AND campaign=? AND persona=? AND contact=?";
    private static final String SCHEMA_CONTACTS = "CREATE TABLE phone_contact (world TEXT NOT NULL,campaign TEXT NOT NULL,persona TEXT NOT NULL,contact TEXT NOT NULL,role TEXT NOT NULL CHECK(role IN ('LEAD','NPC')),name TEXT NOT NULL,PRIMARY KEY(world,campaign,persona,contact))";
    private static final String SCHEMA_TEXTS = "CREATE TABLE phone_text (seq INTEGER PRIMARY KEY AUTOINCREMENT,world TEXT NOT NULL,campaign TEXT NOT NULL,persona TEXT NOT NULL,contact TEXT NOT NULL,id TEXT NOT NULL,chapter INTEGER NOT NULL CHECK(chapter >= 1),speaker TEXT NOT NULL CHECK(speaker IN ('PERSONA','CONTACT')),body TEXT NOT NULL,stamp INTEGER NOT NULL,UNIQUE(world,campaign,persona,contact,id),FOREIGN KEY(world,campaign,persona,contact) REFERENCES phone_contact(world,campaign,persona,contact) ON DELETE CASCADE)";
    private static final String SCHEMA_CARRY = "CREATE TABLE phone_carry (world TEXT NOT NULL,campaign TEXT NOT NULL,persona TEXT NOT NULL,contact TEXT NOT NULL,id TEXT NOT NULL,dest TEXT NOT NULL CHECK(dest IN ('STORY_MEMORY','PLAYTHROUGH_LORE','WORLD_CANON_DRAFT')),body TEXT NOT NULL,chapter INTEGER NOT NULL CHECK(chapter >= 1),PRIMARY KEY(world,campaign,persona,contact,id),FOREIGN KEY(world,campaign,persona,contact) REFERENCES phone_contact(world,campaign,persona,contact) ON DELETE CASCADE)";
    private static final String SCHEMA_SOURCE = "CREATE TABLE phone_source (world TEXT NOT NULL,campaign TEXT NOT NULL,persona TEXT NOT NULL,contact TEXT NOT NULL,carry TEXT NOT NULL,message TEXT NOT NULL,position INTEGER NOT NULL,PRIMARY KEY(world,campaign,persona,contact,carry,message),FOREIGN KEY(world,campaign,persona,contact,carry) REFERENCES phone_carry(world,campaign,persona,contact,id) ON DELETE CASCADE,FOREIGN KEY(world,campaign,persona,contact,message) REFERENCES phone_text(world,campaign,persona,contact,id))";
    private static final String SCHEMA_TIMELINE = "CREATE INDEX phone_timeline ON phone_text(world,campaign,persona,contact,chapter,seq)";
    private static final String SCHEMA_PROJECTION = "CREATE INDEX phone_projection ON phone_carry(world,campaign,dest,chapter,persona,contact)";

    public PhoneStore(Context context) { super(context.getApplicationContext(), "elsewhere-phone-lab-private.db", null, 1); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        for (String statement : List.of(SCHEMA_CONTACTS, SCHEMA_TEXTS, SCHEMA_CARRY, SCHEMA_SOURCE,
            SCHEMA_TIMELINE, SCHEMA_PROJECTION)) db.execSQL(statement);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
        throw new IllegalStateException("No Phone Lab migration from " + from + " to " + to);
    }

    public void registerContact(ThreadKey key, ContactRole role, String name) {
        if (key.scope().personaId().equals(key.contactId())) throw new IllegalArgumentException("Cannot text yourself");
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = values(key);
        v.put("role", Objects.requireNonNull(role).name()); v.put("name", boundedText(name, 120));
        db.beginTransaction();
        try {
            if (db.update("phone_contact", v, THREAD, args(key)) == 0) {
                db.insertOrThrow("phone_contact", null, v);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public List<Contact> contacts(Scope scope) {
        List<Contact> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT contact,role,name FROM phone_contact WHERE world=? AND campaign=? AND persona=? ORDER BY role,name,contact",
                new String[]{scope.worldId(),scope.campaignId(),scope.personaId()})) {
            while (c.moveToNext()) result.add(new Contact(c.getString(0), ContactRole.valueOf(c.getString(1)), c.getString(2)));
        }
        return List.copyOf(result);
    }

    public void savePersonaText(ThreadKey key, String messageId, int chapter, String text, long timestamp) {
        append(key, messageId, chapter, Speaker.PERSONA, text, timestamp);
    }
    public void saveContactReply(ThreadKey key, String messageId, int chapter, String text, long timestamp) {
        append(key, messageId, chapter, Speaker.CONTACT, text, timestamp);
    }
    private void append(ThreadKey key, String messageId, int ordinal, Speaker speaker, String body, long stamp) {
        Message candidate = new Message(id(messageId), chapter(ordinal), speaker, boundedText(body, 4000), stamp);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Message existing = find(db, key, candidate.id());
            if (existing != null) {
                if (!existing.equals(candidate)) throw new IllegalArgumentException("Message ID already used");
            } else {
                ContentValues v = values(key);
                v.put("id", candidate.id()); v.put("chapter", ordinal); v.put("speaker", speaker.name());
                v.put("body", body); v.put("stamp", stamp);
                db.insertOrThrow("phone_text", null, v);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** In story order; a previous chapter cannot read later texts. */
    public List<Message> history(ThreadKey key, int chapterCutoff, int maxItems) {
        if (maxItems < 1 || maxItems > 200) throw new IllegalArgumentException("Invalid history limit");
        List<Message> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,chapter,speaker,body,stamp FROM phone_text WHERE "
                + THREAD + " AND chapter<=? ORDER BY chapter DESC,seq DESC LIMIT ?",
                args(key, Integer.toString(chapter(chapterCutoff)), Integer.toString(maxItems)))) {
            while (c.moveToNext()) result.add(message(c));
        }
        java.util.Collections.reverse(result);
        return List.copyOf(result);
    }

    /** Exact quote or edited note, never a whole thread automatically. Repeating a save ID is safe. */
    public Carry saveApproved(ThreadKey key, String saveId, int ordinal, Destination dest, String approved,
                               List<String> selectedIds) {
        List<String> selected = List.copyOf(selectedIds);
        if (selected.isEmpty() || selected.size() > 32 || new HashSet<>(selected).size() != selected.size()) {
            throw new IllegalArgumentException("Select 1 to 32 distinct source messages");
        }
        Carry candidate = new Carry(id(saveId), key, Objects.requireNonNull(dest),
            boundedText(approved, 12000), selected, chapter(ordinal));
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Carry existing = carryById(db, key, saveId);
            if (existing != null) {
                if (!existing.equals(candidate)) throw new IllegalArgumentException("Save ID already used");
            } else {
                for (String sourceId : selected) {
                    Message source = find(db, key, id(sourceId));
                    if (source == null || source.chapter() > ordinal) throw new IllegalArgumentException("Source is outside this thread or chapter");
                }
                ContentValues v = values(key); v.put("id",saveId); v.put("dest",dest.name());
                v.put("body",approved); v.put("chapter",ordinal);
                db.insertOrThrow("phone_carry",null,v);
                for (int i=0;i<selected.size();i++) {
                    ContentValues p = values(key); p.put("carry",saveId); p.put("message",selected.get(i)); p.put("position",i);
                    db.insertOrThrow("phone_source",null,p);
                }
            }
            db.setTransactionSuccessful();
            return candidate;
        } finally { db.endTransaction(); }
    }

    public List<Carry> pendingWorldDrafts(ThreadKey key, int ordinal) {
        SQLiteDatabase db = getReadableDatabase();
        List<Carry> drafts = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id FROM phone_carry WHERE " + THREAD
                + " AND dest='WORLD_CANON_DRAFT' AND chapter<=? ORDER BY chapter,rowid",
                args(key, Integer.toString(chapter(ordinal))))) {
            while (c.moveToNext()) {
                Carry item = carryById(db,key,c.getString(0));
                if (item != null) drafts.add(item);
            }
        }
        return List.copyOf(drafts);
    }

    public boolean editApprovedText(ThreadKey key, String saveId, String newText) {
        ContentValues v = new ContentValues();
        v.put("body",boundedText(newText, 12000));
        return getWritableDatabase().update("phone_carry",v,THREAD+" AND id=?",args(key,id(saveId))) == 1;
    }

    public boolean removeCarry(ThreadKey key, String saveId) {
        return getWritableDatabase().delete("phone_carry", THREAD + " AND id=?", args(key,id(saveId))) == 1;
    }

    public List<String> approvedStoryMemory(Scope scope, String eligibleActorId, int ordinal) {
        id(eligibleActorId); chapter(ordinal);
        return projection(" AND dest='STORY_MEMORY' AND persona=? AND (persona=? OR contact=?)",
            new String[]{scope.worldId(),scope.campaignId(),Integer.toString(ordinal),scope.personaId(),eligibleActorId,eligibleActorId});
    }
    public List<String> approvedPlaythroughLore(Scope scope, int ordinal) {
        return projection(" AND dest='PLAYTHROUGH_LORE'", new String[]{scope.worldId(),scope.campaignId(),Integer.toString(chapter(ordinal))});
    }
    private List<String> projection(String conditions, String[] bind) {
        List<String> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT body FROM phone_carry WHERE world=? AND campaign=? AND chapter<=?"
                + conditions + " ORDER BY chapter,rowid",bind)) {
            while (c.moveToNext()) result.add(c.getString(0));
        }
        return List.copyOf(result);
    }

    private static Carry carryById(SQLiteDatabase db, ThreadKey key, String id) {
        try (Cursor c = db.rawQuery("SELECT dest,body,chapter FROM phone_carry WHERE "+THREAD+" AND id=?",args(key,id))) {
            if (!c.moveToFirst()) return null;
            List<String> sources = new ArrayList<>();
            try (Cursor s = db.rawQuery("SELECT message FROM phone_source WHERE "+THREAD+" AND carry=? ORDER BY position",args(key,id))) {
                while (s.moveToNext()) sources.add(s.getString(0));
            }
            return new Carry(id,key,Destination.valueOf(c.getString(0)),c.getString(1),sources,c.getInt(2));
        }
    }
    private static Message find(SQLiteDatabase db, ThreadKey key, String id) {
        try (Cursor c = db.rawQuery("SELECT id,chapter,speaker,body,stamp FROM phone_text WHERE "+THREAD+" AND id=?",args(key,id))) {
            return c.moveToFirst() ? message(c) : null;
        }
    }
    private static Message message(Cursor c) {
        return new Message(c.getString(0), c.getInt(1),Speaker.valueOf(c.getString(2)),c.getString(3),c.getLong(4));
    }
    private static ContentValues values(ThreadKey key) {
        ContentValues v = new ContentValues();
        v.put("world",key.scope().worldId()); v.put("campaign",key.scope().campaignId());
        v.put("persona",key.scope().personaId()); v.put("contact",key.contactId()); return v;
    }
    private static String[] args(ThreadKey key, String... suffix) {
        String[] a = new String[4+suffix.length]; a[0]=key.scope().worldId(); a[1]=key.scope().campaignId();
        a[2]=key.scope().personaId(); a[3]=key.contactId(); System.arraycopy(suffix,0,a,4,suffix.length); return a;
    }
}
