package com.example.di

import android.content.Context
import androidx.room.Room
import com.example.data.AppDatabase
import com.example.domain.repository.AppearanceRepository
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.CoopRepository
import com.example.domain.repository.MessageRepository
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.MessageAttachmentRepository
import com.example.domain.repository.NpcRepository
import com.example.domain.repository.LorebookRepository
import com.example.domain.repository.PersonaRepository
import com.example.domain.repository.ProfileImageRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.coop.CoopDeviceIdentity
import com.example.domain.coop.CoopSessionManager
import com.example.domain.coop.AndroidCoopRoomIdentityStore
import com.example.domain.coop.AndroidCoopParticipantIdentityStore
import com.example.domain.npc.NpcStructuredOutputParser
import com.example.domain.backup.BackupRepository
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
    val memoryRepository: MemoryRepository
    val lorebookRepository: LorebookRepository? get() = null
    val coopRepository: CoopRepository? get() = null
    val coopSessionManager: CoopSessionManager? get() = null
    val messageAttachmentRepository: MessageAttachmentRepository? get() = null
    val profileImageRepository: ProfileImageRepository? get() = null
    val npcRepository: NpcRepository? get() = null
    val npcOutputParser: NpcStructuredOutputParser? get() = null
    val backupRepository: BackupRepository? get() = null
}

class DefaultAppContainer(private val context: Context) : AppContainer {
    private val dataCoordinator = AppDataMaintenanceCoordinator.Global

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
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8
            )
            .build()
    }

    override val characterRepository: CharacterRepository by lazy {
        CharacterRepository(database.characterDao(), moshi, profileImageRepository, dataCoordinator)
    }

    override val personaRepository: PersonaRepository by lazy {
        PersonaRepository(database.personaDao(), moshi, profileImageRepository, dataCoordinator)
    }

    override val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(context)
    }

    override val appearanceRepository: AppearanceRepository by lazy {
        AppearanceRepository(context, dataCoordinator)
    }

    override val chatRepository: ChatRepository by lazy {
        ChatRepository(database.chatDao(), database.messageDao(), dataCoordinator)
    }

    override val modelProvider: com.example.domain.provider.ModelProvider by lazy {
        com.example.data.remote.OpenRouterModelProvider(okHttpClient, moshi, settingsRepository)
    }

    override val messageRepository: MessageRepository by lazy {
        MessageRepository(database.messageDao(), dataCoordinator)
    }

    override val memoryRepository: MemoryRepository by lazy {
        MemoryRepository(database.memoryDao(), dataCoordinator)
    }

    override val lorebookRepository: LorebookRepository by lazy {
        LorebookRepository(database.lorebookDao(), moshi, dataCoordinator)
    }

    override val messageAttachmentRepository: MessageAttachmentRepository by lazy {
        MessageAttachmentRepository(context, database, dataCoordinator)
    }

    override val profileImageRepository: ProfileImageRepository by lazy {
        ProfileImageRepository(context, dataCoordinator)
    }

    override val npcRepository: NpcRepository by lazy {
        NpcRepository(database, moshi, dataCoordinator)
    }

    override val npcOutputParser: NpcStructuredOutputParser by lazy {
        NpcStructuredOutputParser(moshi)
    }

    override val backupRepository: BackupRepository by lazy {
        BackupRepository(
            context = context,
            database = database,
            appearanceRepository = appearanceRepository,
            messageAttachmentRepository = messageAttachmentRepository,
            profileImageRepository = profileImageRepository,
            moshi = moshi,
            dataCoordinator = dataCoordinator,
            beforeDataReplacement = { coopSessionManager.stopForDataReplacement() }
        )
    }

    override val coopRepository: CoopRepository by lazy {
        CoopRepository(database, moshi, messageAttachmentRepository, dataCoordinator)
    }

    override val coopSessionManager: CoopSessionManager by lazy {
        CoopSessionManager(
            coopRepository,
            CoopDeviceIdentity(context),
            AndroidCoopRoomIdentityStore(),
            AndroidCoopParticipantIdentityStore()
        )
    }
}
