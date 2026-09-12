package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ProviderRoutingMode { AUTO, PREFER, LOCK }
enum class ResponseLengthProfile { SHORT, NORMAL, LONG, CUSTOM }

@Entity(tableName = "chat_settings")
data class ChatSettingsEntity(
    @PrimaryKey val chatId: String,
    val selectedModelId: String? = null,
    val providerRoutingMode: ProviderRoutingMode = ProviderRoutingMode.AUTO,
    val providerEndpoint: String? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val responseLengthProfile: ResponseLengthProfile = ResponseLengthProfile.NORMAL,
    val customMin: Int? = null,
    val customTargetMax: Int? = null,
    val customHardMax: Int? = null,
    val draftMessage: String? = null
)
