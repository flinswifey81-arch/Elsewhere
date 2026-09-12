package com.example.data.model

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

data class ChatSummary(
    @Embedded val chat: ChatEntity,
    
    @Relation(
        parentColumn = "chatId",
        entityColumn = "characterId",
        associateBy = Junction(ChatParticipantEntity::class)
    )
    val characters: List<CharacterEntity>,
    
    @Relation(
        parentColumn = "chatId",
        entityColumn = "personaId",
        associateBy = Junction(ChatPersonaParticipantEntity::class)
    )
    val personas: List<PersonaEntity>
)
