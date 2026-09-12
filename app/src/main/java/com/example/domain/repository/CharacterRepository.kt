package com.example.domain.repository

import com.example.data.CharacterDao
import com.example.data.model.CharacterEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class CharacterRepository(
    private val characterDao: CharacterDao,
    private val moshi: Moshi
) {
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    fun getAllCharacters(): Flow<List<CharacterEntity>> = characterDao.getAllCharacters()
    
    suspend fun getCharacterById(id: String): CharacterEntity? = characterDao.getCharacterById(id)

    suspend fun archiveCharacter(id: String) {
        characterDao.archiveCharacter(id)
    }

    suspend fun duplicateCharacter(originalId: String, newDisplayName: String? = null): String {
        val original = getCharacterById(originalId) ?: throw IllegalArgumentException("Character not found")
        val newId = UUID.randomUUID().toString()
        val duplicated = original.copy(
            characterId = newId,
            displayName = newDisplayName ?: "${original.displayName} (Copy)",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            isArchived = false
        )
        characterDao.insertCharacter(duplicated)
        return newId
    }

    suspend fun importCharacterJson(jsonString: String, replaceExisting: Boolean = false, importAsNew: Boolean = false): String {
        val rootMap = mapAdapter.fromJson(jsonString) ?: throw IllegalArgumentException("Invalid JSON format")
        
        val schemaVersion = (rootMap["schema_version"] as? Number)?.toInt() 
            ?: throw IllegalArgumentException("Missing required field: schema_version")
        var characterId = rootMap["character_id"] as? String 
            ?: throw IllegalArgumentException("Missing required field: character_id")
        val displayName = rootMap["display_name"] as? String 
            ?: throw IllegalArgumentException("Missing required field: display_name")
        val characterName = rootMap["character_name"] as? String 
            ?: throw IllegalArgumentException("Missing required field: character_name")
        
        val aliases = (rootMap["aliases"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        
        if (importAsNew) {
            characterId = UUID.randomUUID().toString()
        } else if (!replaceExisting) {
            val existing = getCharacterById(characterId)
            if (existing != null) {
                throw DuplicateIdException("Character ID already exists", characterId)
            }
        }

        val knownKeys = setOf(
            "schema_version", "character_id", "display_name", "character_name", "aliases",
            "identity", "appearance", "personality", "voice", "behavior", "backstory",
            "knowledge", "world_context", "writing_rules", "examples", "author_notes"
        )
        
        val extensions = rootMap.filterKeys { it !in knownKeys }
        
        val entity = CharacterEntity(
            characterId = characterId,
            displayName = displayName,
            characterName = characterName,
            aliases = aliases,
            importSchemaVersion = schemaVersion,
            identityJson = rootMap["identity"]?.let { moshi.adapter(Any::class.java).toJson(it) },
            appearanceJson = rootMap["appearance"]?.let { moshi.adapter(Any::class.java).toJson(it) },
            personalityJson = rootMap["personality"]?.let { moshi.adapter(Any::class.java).toJson(it) },
            voiceJson = rootMap["voice"]?.let { moshi.adapter(Any::class.java).toJson(it) },
            behaviorJson = rootMap["behavior"]?.let { moshi.adapter(Any::class.java).toJson(it) },
            backstoryJson = rootMap["backstory"]?.let { moshi.adapter(Any::class.java).toJson(it) },
            knowledgeJson = rootMap["knowledge"]?.let { moshi.adapter(Any::class.java).toJson(it) },
            worldContextJson = rootMap["world_context"]?.let { moshi.adapter(Any::class.java).toJson(it) },
            writingRulesJson = rootMap["writing_rules"]?.let { moshi.adapter(Any::class.java).toJson(it) },
            examplesJson = rootMap["examples"]?.let { moshi.adapter(Any::class.java).toJson(it) },
            authorNotesJson = rootMap["author_notes"]?.let { moshi.adapter(Any::class.java).toJson(it) },
            extensionFieldsJson = if (extensions.isNotEmpty()) moshi.adapter(Any::class.java).toJson(extensions) else null
        )
        
        characterDao.insertCharacter(entity)
        return characterId
    }

    suspend fun exportCharacterJson(characterId: String): String {
        val entity = getCharacterById(characterId) ?: throw IllegalArgumentException("Character not found")
        val rootMap = mutableMapOf<String, Any?>()
        
        rootMap["schema_version"] = entity.importSchemaVersion
        rootMap["character_id"] = entity.characterId
        rootMap["display_name"] = entity.displayName
        rootMap["character_name"] = entity.characterName
        rootMap["aliases"] = entity.aliases
        
        val anyAdapter = moshi.adapter(Any::class.java)
        entity.identityJson?.let { rootMap["identity"] = anyAdapter.fromJson(it) }
        entity.appearanceJson?.let { rootMap["appearance"] = anyAdapter.fromJson(it) }
        entity.personalityJson?.let { rootMap["personality"] = anyAdapter.fromJson(it) }
        entity.voiceJson?.let { rootMap["voice"] = anyAdapter.fromJson(it) }
        entity.behaviorJson?.let { rootMap["behavior"] = anyAdapter.fromJson(it) }
        entity.backstoryJson?.let { rootMap["backstory"] = anyAdapter.fromJson(it) }
        entity.knowledgeJson?.let { rootMap["knowledge"] = anyAdapter.fromJson(it) }
        entity.worldContextJson?.let { rootMap["world_context"] = anyAdapter.fromJson(it) }
        entity.writingRulesJson?.let { rootMap["writing_rules"] = anyAdapter.fromJson(it) }
        entity.examplesJson?.let { rootMap["examples"] = anyAdapter.fromJson(it) }
        entity.authorNotesJson?.let { rootMap["author_notes"] = anyAdapter.fromJson(it) }
        
        entity.extensionFieldsJson?.let { extJson ->
            val extensions = mapAdapter.fromJson(extJson)
            extensions?.forEach { (k, v) -> rootMap[k] = v }
        }
        
        return mapAdapter.indent("  ").toJson(rootMap)
    }
}

class DuplicateIdException(message: String, val id: String) : Exception(message)
