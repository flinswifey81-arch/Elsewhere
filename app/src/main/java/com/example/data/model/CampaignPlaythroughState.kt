package com.example.data.model

import java.util.Locale

/** Narrative facts, not stats or rules. Stored in existing Campaign continuity rows. */
enum class CampaignPlaythroughField(val label: String) {
    LOCATION("Location"),
    CAST_PRESENCE("Cast presence"),
    PROGRESSION("Progression"),
    FORM("Form"),
    ABILITY("Ability"),
    INVENTORY("Inventory"),
    TRANSFORMATION("Transformation");

    val itemType: String get() = "PLAYTHROUGH_$name"

    companion object {
        fun fromItemType(value: String): CampaignPlaythroughField? =
            entries.firstOrNull { it.itemType == value }
    }
}

internal fun playthroughSubject(value: String): String =
    value.trim().lowercase(Locale.ROOT)

/**
 * Show what the player recorded as of this episode. A clear entry hides the
 * earlier value without rewriting history. A removed episode invalidates its
 * revisions rather than silently leaking unanchored state into another episode.
 */
internal fun campaignPlaythroughStateAt(
    target: CampaignChapterEntity,
    chapters: List<CampaignChapterEntity>,
    items: List<CampaignContinuityItemEntity>
): List<CampaignContinuityItemEntity> {
    val ordinals = chapters.filter { it.campaignId == target.campaignId && it.ordinal <= target.ordinal }
        .associate { it.chapterId to it.ordinal }
    return items.asSequence()
        .filter { it.campaignId == target.campaignId && it.openedChapterId?.let(ordinals::containsKey) == true }
        .filter { CampaignPlaythroughField.fromItemType(it.itemType) != null }
        .sortedWith(compareByDescending<CampaignContinuityItemEntity> { it.openedChapterId?.let(ordinals::get) }
            .thenByDescending { it.updatedAt }
            .thenByDescending { it.itemId })
        .distinctBy { it.itemType to playthroughSubject(it.title) }
        .filter { it.status == "OPEN" && it.details.isNotBlank() }
        .sortedWith(compareBy<CampaignContinuityItemEntity> {
            CampaignPlaythroughField.fromItemType(it.itemType)?.ordinal
        }.thenBy { playthroughSubject(it.title) })
        .toList()
}
