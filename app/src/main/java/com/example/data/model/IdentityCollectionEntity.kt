package com.example.data.model

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "identity_collections")
data class IdentityCollectionEntity(@PrimaryKey val collectionId: String, val label: String)

@Entity(
    tableName = "identity_collection_characters",
    primaryKeys = ["collectionId", "characterId"],
    foreignKeys = [
        ForeignKey(entity = IdentityCollectionEntity::class, parentColumns = ["collectionId"],
            childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CharacterEntity::class, parentColumns = ["characterId"],
            childColumns = ["characterId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("characterId")]
)
data class IdentityCollectionCharacterEntity(val collectionId: String, val characterId: String)

@Entity(
    tableName = "identity_collection_personas",
    primaryKeys = ["collectionId", "personaId"],
    foreignKeys = [
        ForeignKey(entity = IdentityCollectionEntity::class, parentColumns = ["collectionId"],
            childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PersonaEntity::class, parentColumns = ["personaId"],
            childColumns = ["personaId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("personaId")]
)
data class IdentityCollectionPersonaEntity(val collectionId: String, val personaId: String)

data class IdentityCollection(
    @Embedded val collection: IdentityCollectionEntity,
    @Relation(parentColumn = "collectionId", entityColumn = "collectionId")
    val characters: List<IdentityCollectionCharacterEntity>,
    @Relation(parentColumn = "collectionId", entityColumn = "collectionId")
    val personas: List<IdentityCollectionPersonaEntity>
) {
    fun contains(kind: CollectionIdentityKind, id: String): Boolean = when (kind) {
        CollectionIdentityKind.CHARACTER -> characters.any { it.characterId == id }
        CollectionIdentityKind.PERSONA -> personas.any { it.personaId == id }
    }
}

enum class CollectionIdentityKind { CHARACTER, PERSONA }
