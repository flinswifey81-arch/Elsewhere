package com.example.phonelab.systems;

import java.util.List;
import java.util.Objects;

public final class PhonePolicy {
    private PhonePolicy() {}
    public enum ContactRole { LEAD, NPC }
    public enum Speaker { PERSONA, CONTACT }
    public enum Destination { STORY_MEMORY, PLAYTHROUGH_LORE, WORLD_CANON_DRAFT }

    /** Owns one Persona's inbox in one campaign. Never infer this tuple from display names. */
    public record Scope(String worldId, String campaignId, String personaId) {
        public Scope { id(worldId); id(campaignId); id(personaId); }
    }

    public record ThreadKey(Scope scope, String contactId) {
        public ThreadKey { Objects.requireNonNull(scope); id(contactId); }
    }

    public record Message(String id, int chapter, Speaker speaker, String text, long timestamp) {}

    /** Internal provenance is stored locally. Only approved text leaves this object via projection. */
    public record Carry(String id, ThreadKey thread, Destination destination, String approvedText,
                        List<String> sourceMessageIds, int chapter) {
        public Carry { sourceMessageIds = List.copyOf(sourceMessageIds); }
        public String storyText(Scope viewer, String eligibleActorId, int chapterCutoff) {
            return destination == Destination.STORY_MEMORY && thread.scope().equals(viewer)
                && chapter <= chapterCutoff
                && (thread.scope().personaId().equals(eligibleActorId)
                    || thread.contactId().equals(eligibleActorId)) ? approvedText : null;
        }
        public String loreFor(Scope viewer, int chapterCutoff) {
            return destination == Destination.PLAYTHROUGH_LORE
                && thread.scope().worldId().equals(viewer.worldId())
                && thread.scope().campaignId().equals(viewer.campaignId())
                && chapter <= chapterCutoff ? approvedText : null;
        }
    }

    public static String id(String value) {
        if (value == null || value.isBlank() || value.length() > 128 || value.indexOf(0) >= 0) {
            throw new IllegalArgumentException("Invalid stable identifier");
        }
        return value;
    }
    public static String boundedText(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength || value.indexOf(0) >= 0) {
            throw new IllegalArgumentException("Text must contain 1 to " + maxLength + " characters");
        }
        return value;
    }
    public static int chapter(int value) {
        if (value < 1) throw new IllegalArgumentException("Missing chapter");
        return value;
    }
}
