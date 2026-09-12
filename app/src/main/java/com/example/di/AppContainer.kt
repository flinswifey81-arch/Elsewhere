package com.example.di

import android.content.Context
import androidx.room.Room
import com.example.data.AppDatabase
import com.example.domain.repository.AppearanceRepository
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.MessageRepository
import com.example.domain.repository.PersonaRepository
import com.example.domain.repository.SettingsRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

interface AppContainer {
    val modelProvider: com.example.domain.provider.ModelProvider
    val characterRepository: CharacterRepository
    val personaRepository: PersonaRepository
    val settingsRepository: SettingsRepository
    val appearanceRepository: AppearanceRepository
    val chatRepository: ChatRepository
    val messageRepository: MessageRepository
}

class DefaultAppContainer(private val context: Context) : AppContainer {
    private val okHttpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    private val database: AppDatabase by lazy {
        Room.databaseBuilder(context, AppDatabase::class.java, "elsewhere_database")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()
    }

    override val characterRepository: CharacterRepository by lazy {
        CharacterRepository(database.characterDao(), moshi)
    }

    override val personaRepository: PersonaRepository by lazy {
        PersonaRepository(database.personaDao(), moshi)
    }

    override val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(context)
    }

    override val appearanceRepository: AppearanceRepository by lazy {
        AppearanceRepository(context)
    }

    override val chatRepository: ChatRepository by lazy {
        ChatRepository(database.chatDao())
    }

    override val modelProvider: com.example.domain.provider.ModelProvider by lazy {
        com.example.data.remote.OpenRouterModelProvider(okHttpClient, moshi, settingsRepository)
    }

    override val messageRepository: MessageRepository by lazy {
        MessageRepository(database.messageDao())
    }
}
