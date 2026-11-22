package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.CampaignChapterEntity
import com.example.data.model.CampaignEntity
import com.example.data.model.CampaignJournalEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CampaignDao {
    @Query("SELECT * FROM campaigns WHERE worldId = :worldId ORDER BY createdAt, campaignId")
    fun observeForWorld(worldId: String): Flow<List<CampaignEntity>>

    @Query("SELECT * FROM campaigns WHERE worldId = :worldId ORDER BY createdAt, campaignId")
    suspend fun forWorld(worldId: String): List<CampaignEntity>

    @Query("SELECT * FROM campaigns ORDER BY worldId, createdAt, campaignId")
    suspend fun getAllCampaigns(): List<CampaignEntity>

    @Query("SELECT * FROM campaigns WHERE campaignId = :campaignId LIMIT 1")
    suspend fun getCampaign(campaignId: String): CampaignEntity?

    @Query("SELECT * FROM campaign_chapters WHERE campaignId = :campaignId ORDER BY ordinal, chapterId")
    fun observeChapters(campaignId: String): Flow<List<CampaignChapterEntity>>

    @Query("SELECT * FROM campaign_chapters WHERE campaignId = :campaignId ORDER BY ordinal, chapterId")
    suspend fun chapters(campaignId: String): List<CampaignChapterEntity>

    @Query("SELECT * FROM campaign_chapters ORDER BY campaignId, ordinal, chapterId")
    suspend fun getAllChapters(): List<CampaignChapterEntity>

    @Query("SELECT * FROM campaign_chapters WHERE chapterId = :chapterId LIMIT 1")
    suspend fun getChapter(chapterId: String): CampaignChapterEntity?

    @Query("SELECT * FROM campaign_chapters WHERE chatId = :chatId LIMIT 1")
    suspend fun chapterForChat(chatId: String): CampaignChapterEntity?

    @Query("SELECT COALESCE(MAX(ordinal), 0) FROM campaign_chapters WHERE campaignId = :campaignId")
    suspend fun lastOrdinal(campaignId: String): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCampaign(campaign: CampaignEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChapter(chapter: CampaignChapterEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCampaigns(campaigns: List<CampaignEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChapters(chapters: List<CampaignChapterEntity>)

    @Update
    suspend fun updateCampaign(campaign: CampaignEntity): Int

    @Update
    suspend fun updateChapter(chapter: CampaignChapterEntity): Int

    @Query("DELETE FROM campaign_chapters WHERE chapterId = :chapterId")
    suspend fun deleteChapter(chapterId: String): Int

    @Query("DELETE FROM campaigns WHERE campaignId = :campaignId")
    suspend fun deleteCampaign(campaignId: String): Int

    @Query("SELECT * FROM campaign_journal_entries WHERE campaignId = :campaignId ORDER BY createdAt, journalEntryId")
    fun observeJournal(campaignId: String): Flow<List<CampaignJournalEntryEntity>>

    @Query("SELECT * FROM campaign_journal_entries WHERE campaignId = :campaignId ORDER BY createdAt, journalEntryId")
    suspend fun journal(campaignId: String): List<CampaignJournalEntryEntity>

    @Query("SELECT * FROM campaign_journal_entries ORDER BY campaignId, createdAt, journalEntryId")
    suspend fun getAllJournalEntries(): List<CampaignJournalEntryEntity>

    @Query("SELECT * FROM campaign_journal_entries WHERE journalEntryId = :entryId LIMIT 1")
    suspend fun getJournalEntry(entryId: String): CampaignJournalEntryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJournalEntry(entry: CampaignJournalEntryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJournalEntries(entries: List<CampaignJournalEntryEntity>)

    @Update
    suspend fun updateJournalEntry(entry: CampaignJournalEntryEntity): Int

    @Query("DELETE FROM campaign_journal_entries WHERE journalEntryId = :entryId")
    suspend fun deleteJournalEntry(entryId: String): Int

    @Query("DELETE FROM campaign_journal_entries")
    suspend fun clearJournalEntries()

    @Query("DELETE FROM campaign_chapters")
    suspend fun clearChapters()

    @Query("DELETE FROM campaigns")
    suspend fun clearCampaigns()
}
