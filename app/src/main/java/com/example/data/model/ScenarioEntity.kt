package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.UUID

enum class ScenarioExperience { QUICK_SCENE, SANDBOX }

@JsonClass(generateAdapter = true)
data class ScenarioNpc(
    val npcId: String = UUID.randomUUID().toString(),
    val name: String = "",
    val appearance: String = "",
    val personality: String = "",
    val background: String = "",
    val motivations: String = "",
    val relationships: String = "",
    val narrativeRole: String = "",
    val instructions: String = "",
    // Reserved stable reference for future library selection; never mutates a Character.
    val sourceCharacterId: String? = null
)

@JsonClass(generateAdapter = true)
data class ScenarioDefinition(
    val experience: ScenarioExperience = ScenarioExperience.QUICK_SCENE,
    val title: String = "",
    val displayTitle: String = "",
    val publicDescription: String = "",
    val premise: String = "",
    val setting: String = "",
    val narrationStyle: String = "",
    val openingSituation: String = "",
    val openingMessage: String = "",
    val npcs: List<ScenarioNpc> = emptyList(),
    val locations: String = "",
    val factions: String = "",
    val worldRules: String = "",
    val instructions: String = "",
    val categories: String = "",
    val tags: String = ""
) {
    fun validate() {
        require(title.isNotBlank()) { "Give the scenario a title." }
        require(premise.isNotBlank()) { "Add a premise or scene setup." }
        require(npcs.all { it.name.isNotBlank() }) { "Give each NPC a name or remove the empty NPC." }
        require(npcs.map { it.npcId }.distinct().size == npcs.size) { "NPC identifiers must be unique." }
    }
}

object ScenarioCodec {
    private val adapter = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build().adapter(ScenarioDefinition::class.java)
    fun encode(value: ScenarioDefinition): String = adapter.toJson(value)
    fun decode(value: String): ScenarioDefinition = requireNotNull(adapter.fromJson(value)) { "Invalid scenario configuration." }
}

@Entity(tableName = "scenarios")
data class ScenarioEntity(
    @PrimaryKey val scenarioId: String = UUID.randomUUID().toString(),
    val definitionJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

// A playthrough owns its initial configuration; template edits cannot rewrite its world.
@Entity(tableName = "scenario_sessions", foreignKeys = [ForeignKey(
    entity = ChatEntity::class, parentColumns = ["chatId"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE
)])
data class ScenarioSessionEntity(
    @PrimaryKey val chatId: String,
    val scenarioId: String,
    val definitionJson: String
)
