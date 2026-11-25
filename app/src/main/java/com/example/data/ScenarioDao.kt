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

    /** World archive import must never overwrite an unrelated existing Scenario. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertForWorldImport(scenario: ScenarioEntity)
    @Query("SELECT * FROM scenario_sessions WHERE chatId = :chatId")
    suspend fun session(chatId: String): ScenarioSessionEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: ScenarioSessionEntity)

    /** Only the active chat's frozen snapshot changes, never the Scenario template. */
    @Query("UPDATE scenario_sessions SET definitionJson = :definitionJson WHERE chatId = :chatId")
    suspend fun updateSessionDefinition(chatId: String, definitionJson: String): Int
    @Query("SELECT * FROM scenario_sessions ORDER BY chatId")
    suspend fun sessions(): List<ScenarioSessionEntity>
    @Query("DELETE FROM scenario_sessions")
    suspend fun clearSessions()
    @Query("DELETE FROM scenarios")
    suspend fun clearScenarios()
}
