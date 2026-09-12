package com.example.domain.repository

import com.example.data.PersonaDao
import com.example.data.model.PersonaEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class PersonaRepository(
    private val personaDao: PersonaDao,
    private val moshi: Moshi
) {
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    fun getAllPersonas(): Flow<List<PersonaEntity>> = personaDao.getAllPersonas()
    
    suspend fun getPersonaById(id: String): PersonaEntity? = personaDao.getPersonaById(id)

    suspend fun archivePersona(id: String) {
        personaDao.archivePersona(id)
    }

    suspend fun duplicatePersona(originalId: String, newDisplayName: String? = null): String {
        val original = getPersonaById(originalId) ?: throw IllegalArgumentException("Persona not found")
        val newId = UUID.randomUUID().toString()
        val duplicated = original.copy(
            personaId = newId,
            displayName = newDisplayName ?: "${original.displayName} (Copy)",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            isArchived = false
        )
        personaDao.insertPersona(duplicated)
        return newId
    }

    suspend fun importPersonaJson(jsonString: String, replaceExisting: Boolean = false, importAsNew: Boolean = false): String {
        val rootMap = mapAdapter.fromJson(jsonString) ?: throw IllegalArgumentException("Invalid JSON format")
        
        val schemaVersion = (rootMap["schema_version"] as? Number)?.toInt() 
            ?: throw IllegalArgumentException("Missing required field: schema_version")
        var personaId = rootMap["persona_id"] as? String 
            ?: throw IllegalArgumentException("Missing required field: persona_id")
        val displayName = rootMap["display_name"] as? String 
            ?: throw IllegalArgumentException("Missing required field: display_name")
        val personaName = rootMap["persona_name"] as? String 
            ?: throw IllegalArgumentException("Missing required field: persona_name")
        
        val aliases = (rootMap["aliases"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        
        if (importAsNew) {
            personaId = UUID.randomUUID().toString()
        } else if (!replaceExisting) {
            val existing = getPersonaById(personaId)
            if (existing != null) {
                throw DuplicateIdException("Persona ID already exists", personaId)
            }
        }

        val knownKeys = setOf(
            "schema_version", "persona_id", "display_name", "persona_name", "aliases",
            "identity", "appearance", "personality", "roleplay_profile", "background",
            "world_context", "private_notes"
        )
        
        val extensions = rootMap.filterKeys { it !in knownKeys }
        
        val entity = PersonaEntity(
            personaId = personaId,
            displayName = displayName,
            personaName = personaName,
            aliases = aliases,
            importSchemaVersion = schemaVersion,
            identityJson = rootMap["identity"]?.let { moshi.adapter(Any::class.java).toJson(it) },
            appearanceJson = rootMap["appearance"]?.let { moshi.adapter(Any::class.java).toJson(it) },
            personalityJson = rootMap["personality"]?.let { moshi.adapter(Any::class.java).toJson(it) },
            roleplayProfileJson = rootMap["roleplay_profile"]?.let { moshi.adapter(Any::class.java).toJson(it) },
            backgroundJson = rootMap["background"]?.let { moshi.adapter(Any::class.java).toJson(it) },
            worldContextJson = rootMap["world_context"]?.let { moshi.adapter(Any::class.java).toJson(it) },
            privateNotesJson = rootMap["private_notes"]?.let { moshi.adapter(Any::class.java).toJson(it) },
            extensionFieldsJson = if (extensions.isNotEmpty()) moshi.adapter(Any::class.java).toJson(extensions) else null
        )
        
        personaDao.insertPersona(entity)
        return personaId
    }

    suspend fun exportPersonaJson(personaId: String): String {
        val entity = getPersonaById(personaId) ?: throw IllegalArgumentException("Persona not found")
        val rootMap = mutableMapOf<String, Any?>()
        
        rootMap["schema_version"] = entity.importSchemaVersion
        rootMap["persona_id"] = entity.personaId
        rootMap["display_name"] = entity.displayName
        rootMap["persona_name"] = entity.personaName
        rootMap["aliases"] = entity.aliases
        
        val anyAdapter = moshi.adapter(Any::class.java)
        entity.identityJson?.let { rootMap["identity"] = anyAdapter.fromJson(it) }
        entity.appearanceJson?.let { rootMap["appearance"] = anyAdapter.fromJson(it) }
        entity.personalityJson?.let { rootMap["personality"] = anyAdapter.fromJson(it) }
        entity.roleplayProfileJson?.let { rootMap["roleplay_profile"] = anyAdapter.fromJson(it) }
        entity.backgroundJson?.let { rootMap["background"] = anyAdapter.fromJson(it) }
        entity.worldContextJson?.let { rootMap["world_context"] = anyAdapter.fromJson(it) }
        entity.privateNotesJson?.let { rootMap["private_notes"] = anyAdapter.fromJson(it) }
        
        entity.extensionFieldsJson?.let { extJson ->
            val extensions = mapAdapter.fromJson(extJson)
            extensions?.forEach { (k, v) -> rootMap[k] = v }
        }
        
        return mapAdapter.indent("  ").toJson(rootMap)
    }
}
