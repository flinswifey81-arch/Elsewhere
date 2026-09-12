package com.example.data

import androidx.room.TypeConverter
import com.example.data.model.ChatType
import com.example.data.model.SpeakerType
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

class Converters {
    private val moshi = Moshi.Builder().build()
    private val listType = Types.newParameterizedType(List::class.java, String::class.java)
    private val listAdapter = moshi.adapter<List<String>>(listType)

    @TypeConverter
    fun fromStringList(list: List<String>?): String? {
        return list?.let { listAdapter.toJson(it) }
    }

    @TypeConverter
    fun toStringList(json: String?): List<String>? {
        return json?.let { listAdapter.fromJson(it) }
    }
    
    @TypeConverter
    fun fromChatType(value: ChatType) = value.name
    
    @TypeConverter
    fun toChatType(value: String) = enumValueOf<ChatType>(value)
    
    @TypeConverter
    fun fromSpeakerType(value: SpeakerType) = value.name
    
    @TypeConverter
    fun toSpeakerType(value: String) = enumValueOf<SpeakerType>(value)
}

    @TypeConverter
    fun fromProviderRoutingMode(value: com.example.data.model.ProviderRoutingMode) = value.name
    
    @TypeConverter
    fun toProviderRoutingMode(value: String) = enumValueOf<com.example.data.model.ProviderRoutingMode>(value)
    
    @TypeConverter
    fun fromResponseLengthProfile(value: com.example.data.model.ResponseLengthProfile) = value.name
    
    @TypeConverter
    fun toResponseLengthProfile(value: String) = enumValueOf<com.example.data.model.ResponseLengthProfile>(value)
