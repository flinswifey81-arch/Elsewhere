package com.example.data

import androidx.room.*
import com.example.data.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ScenarioDao {
    @Query("SELECT * FROM scenarios ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ScenarioEntity>>
    @Query("SELECT * FROM scenarios ORDER BY scenarioId")
    suspend fun getAll(): List<ScenarioEntity>
    @Query("SELECT * FROM scenarios WHERE scenarioId = :id")
    suspend fun get(id: String): ScenarioEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(scenario: ScenarioEntity)
    @Query("SELECT * FROM scenario_sessions WHERE chatId = :chatId")
    suspend fun session(chatId: String): ScenarioSessionEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: ScenarioSessionEntity)
    @Query("SELECT * FROM scenario_sessions ORDER BY chatId")
    suspend fun sessions(): List<ScenarioSessionEntity>
    @Query("DELETE FROM scenario_sessions")
    suspend fun clearSessions()
    @Query("DELETE FROM scenarios")
    suspend fun clearScenarios()
}
