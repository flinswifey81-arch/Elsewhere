package com.example.domain.repository

import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.model.*
import java.util.UUID

class IdentityCollectionRepository(
    private val database: AppDatabase,
    private val maintenance: AppDataMaintenanceCoordinator = AppDataMaintenanceCoordinator.Global
) {
    private val dao = database.identityCollectionDao()
    fun observe() = dao.observe()

    suspend fun save(
        id: String?,
        name: String,
        kind: CollectionIdentityKind,
        add: Set<String>,
        remove: Set<String>
    ) = maintenance.withMutation {
        val label = name.trim()
        require(label.length in 1..80) { "Enter a collection name of up to 80 characters." }
        database.withTransaction {
            val existing = dao.collections()
            require(id == null || existing.any { it.collectionId == id }) { "This collection was removed." }
            require(existing.none { it.collectionId != id && it.label.equals(label, ignoreCase = true) }) {
                "A collection with this name already exists."
            }
            val collectionId = id ?: UUID.randomUUID().toString()
            dao.saveCollections(listOf(IdentityCollectionEntity(collectionId, label)))
            when (kind) {
                CollectionIdentityKind.CHARACTER -> {
                    dao.removeCharacters(remove.map { IdentityCollectionCharacterEntity(collectionId, it) })
                    dao.addCharacters(add.map { IdentityCollectionCharacterEntity(collectionId, it) })
                }
                CollectionIdentityKind.PERSONA -> {
                    dao.removePersonas(remove.map { IdentityCollectionPersonaEntity(collectionId, it) })
                    dao.addPersonas(add.map { IdentityCollectionPersonaEntity(collectionId, it) })
                }
            }
        }
    }

    suspend fun delete(id: String) = maintenance.withMutation { dao.delete(id) }
}
