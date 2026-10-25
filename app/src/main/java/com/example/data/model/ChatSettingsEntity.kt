package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

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
    val draftMessage: String? = null,
    @ColumnInfo(defaultValue = "0") val useGlobalEngineDefaults: Boolean = false,
    @ColumnInfo(defaultValue = "0") val narratorEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "''") val narratorInstructions: String = ""
)

/** The single application-wide template inherited by newly created chats. */
@Entity(tableName = "engine_defaults")
data class EngineDefaultsEntity(
    @PrimaryKey val defaultsId: String = GLOBAL_ID,
    val selectedModelId: String? = null,
    val providerRoutingMode: ProviderRoutingMode = ProviderRoutingMode.AUTO,
    val providerEndpoint: String? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val responseLengthProfile: ResponseLengthProfile = ResponseLengthProfile.NORMAL,
    val customMin: Int? = null,
    val customTargetMax: Int? = null,
    val customHardMax: Int? = null
) {
    companion object { const val GLOBAL_ID = "global" }
}

fun ChatSettingsEntity.withEngineDefaults(defaults: EngineDefaultsEntity): ChatSettingsEntity = copy(
    selectedModelId = defaults.selectedModelId,
    providerRoutingMode = defaults.providerRoutingMode,
    providerEndpoint = defaults.providerEndpoint,
    temperature = defaults.temperature,
    topP = defaults.topP,
    responseLengthProfile = defaults.responseLengthProfile,
    customMin = defaults.customMin,
    customTargetMax = defaults.customTargetMax,
    customHardMax = defaults.customHardMax
)
