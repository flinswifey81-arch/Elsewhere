package com.example.domain.repository

import com.example.domain.coop.CoopLanAddressPolicy
import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.model.ChatParticipantEntity
import com.example.data.model.ChatType
import com.example.data.model.CoopConnectionState
import com.example.data.model.CoopEventEntity
import com.example.data.model.CoopEventType
import com.example.data.model.CoopParticipantEntity
import com.example.data.model.CoopPayerMode
import com.example.data.model.CoopMessageTombstoneEntity
import com.example.data.model.CoopPersonaOwnershipEntity
import com.example.data.model.CoopRole
import com.example.data.model.CoopRoomEntity
import com.example.data.model.MessageEntity
import com.example.data.model.MessageAttachmentEntity
import com.example.data.model.SpeakerType
import com.example.domain.coop.CoopCharacterRosterPayload
import com.example.domain.coop.CoopCharacterRosterItem
import com.example.domain.coop.CoopCodec
import com.example.domain.coop.CoopEventDto
import com.example.domain.coop.CoopGenerationCompletionPayload
import com.example.domain.coop.CoopGenerationApprovalPayload
import com.example.domain.coop.CoopGenerationExecutionPayload
import com.example.domain.coop.CoopGenerationLifecycle
import com.example.domain.coop.CoopGenerationResultCode
import com.example.domain.coop.CoopGenerationRequestPayload
import com.example.domain.coop.CoopGenerationStage
import com.example.domain.coop.CoopMessageDeletePayload
import com.example.domain.coop.CoopMessagePayload
import com.example.domain.coop.CoopParticipantDto
import com.example.domain.coop.CoopPersonaOwnershipDto
import com.example.domain.coop.CoopProtocol
import com.example.domain.coop.CoopProtocolException
import com.example.domain.coop.CoopSequenceGapException
import com.example.domain.coop.CoopSessionCrypto
import com.example.domain.coop.CoopSnapshotPayload
import com.squareup.moshi.Moshi
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import com.example.domain.npc.NpcStructuredOutputParser

data class CoopHostInvite(
    val room: CoopRoomEntity,
    val participant: CoopParticipantEntity,
    val pairingCode: String
)

data class CoopAppendHostEventResult(
    val event: CoopEventEntity,
    val inserted: Boolean
)

class CoopRepository(
    private val database: AppDatabase,
    moshi: Moshi,
    private val attachmentRepository: MessageAttachmentRepository? = null,
    private val dataCoordinator: AppDataMaintenanceCoordinator = AppDataMaintenanceCoordinator.Global
) {
    private val coopDao = database.coopDao()
    private val chatDao = database.chatDao()
    private val messageDao = database.messageDao()
    val codec = CoopCodec(moshi)

    fun observeRoom(chatId: String): Flow<CoopRoomEntity?> = coopDao.observeRoomForChat(chatId)
    fun observeParticipants(roomId: String) = coopDao.observeParticipants(roomId)
    fun observePersonaOwnerships(roomId: String) = coopDao.observePersonaOwnerships(roomId)

    suspend fun getRoomForChat(chatId: String) = withContext(Dispatchers.IO) {
        coopDao.getRoomForChat(chatId)
    }

    suspend fun getRoom(roomId: String) = withContext(Dispatchers.IO) { coopDao.getRoom(roomId) }
    suspend fun getParticipants(roomId: String) = withContext(Dispatchers.IO) { coopDao.getParticipants(roomId) }
    suspend fun getOwnerships(roomId: String) = withContext(Dispatchers.IO) { coopDao.getPersonaOwnerships(roomId) }
    suspend fun getParticipant(participantId: String) = withContext(Dispatchers.IO) {
        coopDao.getParticipant(participantId)
    }

    suspend fun getMessage(messageId: String) = withContext(Dispatchers.IO) {
        messageDao.getMessageById(messageId)
    }

    suspend fun getEventByClientId(roomId: String, clientEventId: String) = withContext(Dispatchers.IO) {
        coopDao.getEventByClientId(roomId, clientEventId)
    }

    suspend fun getActiveGenerationRequest(roomId: String): CoopGenerationRequestPayload? = withContext(Dispatchers.IO) {
        val room = coopDao.getRoom(roomId) ?: return@withContext null
        val activeId = room.activeGenerationRequestId ?: return@withContext null
        coopDao.getLatestEventOfType(roomId, CoopEventType.GENERATION_REQUESTED)
            ?.let { codec.generationFromJson(it.payloadJson) }
            ?.takeIf { it.requestId == activeId }
    }

    suspend fun getGenerationLifecycle(roomId: String): CoopGenerationLifecycle? = withContext(Dispatchers.IO) {
        val room = coopDao.getRoom(roomId) ?: return@withContext null
        getGenerationLifecycleLocked(room)
    }

    suspend fun reconcileDisconnectedParticipants(roomId: String) = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
            coopDao.markRoomParticipantsDisconnected(roomId, System.currentTimeMillis())
        }
    }

    suspend fun createHostRoom(
        chatId: String,
        deviceId: String,
        participantDisplayName: String,
        personaId: String,
        personaDisplayName: String,
        port: Int = CoopProtocol.DEFAULT_PORT,
        roomId: String = UUID.randomUUID().toString(),
        hostPublicKey: String = "",
        hostKeyFingerprint: String = "",
        hostAddress: String? = null
    ): CoopHostInvite = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
        require(participantDisplayName.isNotBlank()) { "Participant name is required" }
        require(participantDisplayName.length <= CoopProtocol.MAX_PARTICIPANT_NAME_CHARS) {
            "Participant name is too long"
        }
        require(deviceId.isNotBlank() && deviceId.length <= CoopProtocol.MAX_DEVICE_ID_CHARS) {
            "Participant membership fingerprint is invalid"
        }
        require(personaId.isNotBlank() && personaId.length <= CoopProtocol.MAX_ID_CHARS) {
            "Persona ID is invalid"
        }
        require(personaDisplayName.isNotBlank() && personaDisplayName.length <= CoopProtocol.MAX_DISPLAY_NAME_CHARS) {
            "Persona name is invalid"
        }
        require(port in 1024..65535) { "LAN port must be between 1024 and 65535" }
        hostAddress?.let(CoopLanAddressPolicy::requirePrivateIpv4)
        val chat = chatDao.getChatById(chatId) ?: throw CoopProtocolException("Chat not found")
        require(chat.chatType == ChatType.GROUP) { "Only Group chats can become co-op rooms" }
        require(coopDao.getRoomForChat(chatId) == null) { "This chat already has a co-op room" }

        require(hostPublicKey.isBlank() == hostKeyFingerprint.isBlank()) {
            "Host key and fingerprint must be provided together"
        }
        val pairingCode = CoopSessionCrypto.newInviteSecret()
        val saltBytes = ByteArray(16).also(SecureRandom()::nextBytes)
        val salt = Base64.getEncoder().withoutPadding().encodeToString(saltBytes)
        val participant = CoopParticipantEntity(
            roomId = "pending",
            deviceId = deviceId,
            identityPublicKey = hostPublicKey,
            displayName = participantDisplayName.trim(),
            isHost = true,
            isConnected = true
        )
        val messages = messageDao.getMessagesForChatOnce(chatId).sortedWith(
            compareBy<MessageEntity> { it.orderIndex }
                // A regenerated response shares its order index with its alternates. Seed the
                // selected primary first so a joining empty database never temporarily observes
                // an orphaned non-primary variant group.
                .thenByDescending { it.isPrimaryVariant }
                .thenBy { it.createdAt }
                .thenBy { it.messageId }
        )
        require(database.messageAttachmentDao().getForChat(chatId).isEmpty()) {
            "Remove this chat's image attachments before starting local co-op; images are not synchronized."
        }
        require(messages.none { NpcStructuredOutputParser.finalVisibleStoryText(it.content) != it.content }) {
            "This chat contains private NPC control data in saved message text and cannot be shared in co-op."
        }
        val characterEntities = chatDao.getChatSummaryById(chatId)?.characters.orEmpty().associateBy { it.characterId }
        val initialRoster = CoopCharacterRosterPayload(
            chatDao.getParticipantsForChatOnce(chatId).sortedBy { it.sortPosition }.map { membership ->
                CoopCharacterRosterItem(
                    characterId = membership.characterId,
                    displayNameSnapshot = characterEntities[membership.characterId]?.displayName
                        ?: membership.characterId,
                    isActive = membership.isActive,
                    sortPosition = membership.sortPosition
                )
            }
        )
        require(initialRoster.characters.size <= CoopProtocol.MAX_CHARACTERS) {
            "This Group has too many Characters for a co-op room"
        }
        val rosterEventCount = 1
        require(messages.size.toLong() < CoopProtocol.MAX_RETAINED_EVENTS) {
            "This chat is too large to host safely without archive compaction"
        }
        val host = participant.copy(roomId = roomId)
        val orderBase = (messages.maxOfOrNull { it.orderIndex } ?: System.currentTimeMillis()) + 1_000L
        val room = CoopRoomEntity(
            roomId = roomId,
            chatId = chatId,
            localRole = CoopRole.HOST,
            hostParticipantId = host.participantId,
            localParticipantId = host.participantId,
            hostAddress = hostAddress,
            hostPort = port,
            hostPublicKey = hostPublicKey,
            hostKeyFingerprint = hostKeyFingerprint,
            pairingSalt = salt,
            pairingCodeHash = pairingHash(salt, pairingCode),
            connectionState = CoopConnectionState.CONNECTED,
            orderBase = orderBase,
            nextHostSequence = messages.size + rosterEventCount + 1L,
            lastAppliedSequence = messages.size + rosterEventCount.toLong()
        )

        database.withTransaction {
            coopDao.upsertRoom(room)
            require(coopDao.getParticipant(host.participantId) == null) {
                "Generated host participant ID already belongs to another room"
            }
            coopDao.upsertParticipant(host)
            coopDao.upsertPersonaOwnership(
                CoopPersonaOwnershipEntity(
                    roomId = roomId,
                    personaId = personaId,
                    participantId = host.participantId,
                    personaDisplayNameSnapshot = personaDisplayName,
                    sortPosition = 0
                )
            )
            messages.forEachIndexed { index, message ->
                val event = CoopEventEntity(
                    roomId = roomId,
                    sequence = index + 1L,
                    clientEventId = "seed:${message.messageId}",
                    originParticipantId = host.participantId,
                    eventType = CoopEventType.MESSAGE_UPSERT,
                    payloadJson = codec.messageToJson(
                        CoopMessagePayload.from(
                            message.copy(
                                content = NpcStructuredOutputParser.finalVisibleStoryText(message.content),
                                responsibleParticipantId = null,
                                responsibleDeviceId = null
                            )
                        )
                    )
                )
                require(encodedEventBytes(event) <= CoopProtocol.MAX_EVENT_JSON_BYTES) {
                    "A saved message is too large to synchronize safely in a co-op room"
                }
                coopDao.insertEvent(event)
            }
            val rosterEvent = CoopEventEntity(
                roomId = roomId,
                sequence = messages.size + 1L,
                clientEventId = "seed:character-roster",
                originParticipantId = host.participantId,
                eventType = CoopEventType.CHARACTER_ROSTER_SET,
                payloadJson = codec.rosterToJson(initialRoster)
            )
            require(encodedEventBytes(rosterEvent) <= CoopProtocol.MAX_EVENT_JSON_BYTES) {
                "The initial Character roster is too large to synchronize safely"
            }
            check(coopDao.insertEvent(rosterEvent) != -1L) { "Unable to seed the Character roster" }
        }
        CoopHostInvite(room, host, pairingCode)
        }
    }

    suspend fun createOrUpdateClientRoom(
        chatId: String,
        roomId: String,
        hostParticipantId: String,
        localParticipantId: String,
        deviceId: String,
        participantDisplayName: String,
        personaId: String,
        personaDisplayName: String,
        hostAddress: String,
        port: Int,
        hostPublicKey: String = "",
        hostKeyFingerprint: String = "",
        identityPublicKey: String = ""
    ): CoopRoomEntity = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
        val chat = chatDao.getChatById(chatId) ?: throw CoopProtocolException("Chat not found")
        require(chat.chatType == ChatType.GROUP) { "Only Group chats can join a co-op room" }
        require(hostAddress.isNotBlank()) { "Host address is required" }
        require(localParticipantId.isNotBlank() && localParticipantId.length <= CoopProtocol.MAX_ID_CHARS) {
            "Participant ID is invalid"
        }
        require(deviceId.isNotBlank() && deviceId.length <= CoopProtocol.MAX_DEVICE_ID_CHARS) {
            "Participant membership fingerprint is invalid"
        }
        require(participantDisplayName.isNotBlank() &&
            participantDisplayName.length <= CoopProtocol.MAX_PARTICIPANT_NAME_CHARS) {
            "Participant name is invalid"
        }
        require(personaId.isNotBlank() && personaId.length <= CoopProtocol.MAX_ID_CHARS) {
            "Persona ID is invalid"
        }
        require(personaDisplayName.isNotBlank() && personaDisplayName.length <= CoopProtocol.MAX_DISPLAY_NAME_CHARS) {
            "Persona name is invalid"
        }
        require(port in 1024..65535) { "LAN port must be between 1024 and 65535" }
        require(hostPublicKey.isBlank() == hostKeyFingerprint.isBlank()) {
            "Host key and fingerprint must be provided together"
        }
        if (hostPublicKey.isNotBlank()) {
            val decoded = runCatching { Base64.getUrlDecoder().decode(hostPublicKey) }
                .getOrElse { throw CoopProtocolException("Host public key is malformed") }
            require(CoopSessionCrypto.hostFingerprint(decoded) == hostKeyFingerprint) {
                "Host key fingerprint does not match the invite"
            }
        }
        val existing = coopDao.getRoomForChat(chatId)
        require(existing == null || existing.roomId == roomId) { "This chat belongs to another co-op room" }
        if (existing == null) {
            require(messageDao.getMessagesForChatOnce(chatId).isEmpty()) {
                "Join co-op from an empty disposable Group chat so the host snapshot remains authoritative."
            }
            require(database.messageAttachmentDao().getForChat(chatId).isEmpty()) {
                "Remove local image drafts before joining co-op; images are not synchronized."
            }
        }
        if (existing?.hostPublicKey?.isNotBlank() == true) {
            require(existing.hostPublicKey == hostPublicKey && existing.hostKeyFingerprint == hostKeyFingerprint) {
                "Pinned host identity cannot change when reconnecting"
            }
        }
        val room = (existing ?: CoopRoomEntity(
            roomId = roomId,
            chatId = chatId,
            localRole = CoopRole.CLIENT,
            hostParticipantId = hostParticipantId,
            localParticipantId = localParticipantId
        )).copy(
            hostAddress = hostAddress.trim(),
            hostPort = port,
            hostPublicKey = hostPublicKey.ifBlank { existing?.hostPublicKey.orEmpty() },
            hostKeyFingerprint = hostKeyFingerprint.ifBlank { existing?.hostKeyFingerprint.orEmpty() },
            connectionState = CoopConnectionState.CONNECTING,
            updatedAt = System.currentTimeMillis()
        )
        database.withTransaction {
            coopDao.upsertRoom(room)
            val existingParticipant = coopDao.getParticipant(localParticipantId)
            require(existingParticipant == null || existingParticipant.roomId == roomId) {
                "Participant ID belongs to another room"
            }
            coopDao.upsertParticipant(
                (existingParticipant ?: CoopParticipantEntity(
                    participantId = localParticipantId,
                    roomId = roomId,
                    deviceId = deviceId,
                    identityPublicKey = identityPublicKey,
                    displayName = participantDisplayName.trim(),
                    isConnected = false
                )).copy(
                    deviceId = deviceId,
                    identityPublicKey = identityPublicKey.ifBlank { existingParticipant?.identityPublicKey.orEmpty() },
                    displayName = participantDisplayName.trim(),
                    isConnected = false,
                    lastSeenAt = System.currentTimeMillis()
                )
            )
            coopDao.upsertPersonaOwnership(
                CoopPersonaOwnershipEntity(
                    roomId = roomId,
                    personaId = personaId,
                    participantId = localParticipantId,
                    personaDisplayNameSnapshot = personaDisplayName
                )
            )
        }
        room
        }
    }

    suspend fun rotatePairingCode(roomId: String): String = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
        val room = requireHostRoom(roomId)
        val code = CoopSessionCrypto.newInviteSecret()
        val salt = Base64.getEncoder().withoutPadding().encodeToString(
            ByteArray(16).also(SecureRandom()::nextBytes)
        )
        coopDao.updateRoom(
            room.copy(
                pairingSalt = salt,
                pairingCodeHash = pairingHash(salt, code),
                updatedAt = System.currentTimeMillis()
            )
        )
        code
        }
    }

    suspend fun verifyPairingCode(roomId: String, code: String): Boolean = withContext(Dispatchers.IO) {
        val room = coopDao.getRoom(roomId) ?: return@withContext false
        if (room.localRole != CoopRole.HOST || code.length !in 22..128) return@withContext false
        MessageDigest.isEqual(
            room.pairingCodeHash.toByteArray(Charsets.UTF_8),
            pairingHash(room.pairingSalt, code).toByteArray(Charsets.UTF_8)
        )
    }

    suspend fun upsertParticipant(participant: CoopParticipantEntity) = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                coopDao.getParticipant(participant.participantId)?.let { existing ->
                    require(existing.roomId == participant.roomId &&
                        existing.deviceId == participant.deviceId &&
                        (existing.identityPublicKey.isBlank() ||
                            existing.identityPublicKey == participant.identityPublicKey)) {
                        "Participant membership identity is immutable"
                    }
                }
                coopDao.upsertParticipant(participant)
            }
        }
    }

    suspend fun upsertOwnership(ownership: CoopPersonaOwnershipEntity) = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) { coopDao.upsertPersonaOwnership(ownership) }
    }

    suspend fun updateConnectionState(roomId: String, state: String) = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
            val room = coopDao.getRoom(roomId)
            if (room != null) {
                coopDao.updateRoom(room.copy(connectionState = state, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    suspend fun setParticipantConnected(participantId: String, connected: Boolean) = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
            coopDao.setParticipantConnected(participantId, connected, System.currentTimeMillis())
        }
    }

    suspend fun appendHostEvent(
        roomId: String,
        clientEventId: String,
        originParticipantId: String,
        eventType: String,
        payloadJson: String,
        originPayloadJson: String = "",
        originSignature: String = ""
    ): CoopEventEntity = appendHostEventResult(
        roomId,
        clientEventId,
        originParticipantId,
        eventType,
        payloadJson,
        originPayloadJson,
        originSignature
    ).event

    suspend fun appendHostEventResult(
        roomId: String,
        clientEventId: String,
        originParticipantId: String,
        eventType: String,
        payloadJson: String,
        originPayloadJson: String = "",
        originSignature: String = ""
    ): CoopAppendHostEventResult = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
            val pendingAttachmentDeletes = mutableListOf<MessageAttachmentEntity>()
            val result = database.withTransaction {
                appendHostEventLocked(
                    roomId,
                    clientEventId,
                    originParticipantId,
                    eventType,
                    payloadJson,
                    originPayloadJson,
                    originSignature,
                    pendingAttachmentDeletes
                )
            }
            cleanDeletedAttachmentFiles(pendingAttachmentDeletes)
            result
        }
    }

    suspend fun commitAcceptedMembership(
        roomId: String,
        joinClientMessageId: String,
        participant: CoopParticipantEntity,
        ownership: CoopPersonaOwnershipEntity
    ): List<CoopEventEntity> = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
        database.withTransaction {
            val room = requireHostRoom(roomId)
            require(participant.roomId == roomId && ownership.roomId == roomId) {
                "Accepted membership belongs to a different room"
            }
            require(ownership.participantId == participant.participantId) {
                "Accepted Persona ownership does not match the member"
            }
            val participants = coopDao.getParticipants(roomId)
            require(participants.none {
                (it.participantId == participant.participantId &&
                    (it.deviceId != participant.deviceId ||
                        (it.identityPublicKey.isNotBlank() &&
                            it.identityPublicKey != participant.identityPublicKey))) ||
                    (it.deviceId == participant.deviceId && it.participantId != participant.participantId)
            }) { "Participant/device identity conflict" }
            require(participants.none {
                it.participantId == participant.participantId && it.isConnected
            }) { "Participant is already connected" }
            require(coopDao.getPersonaOwnerships(roomId).none {
                it.personaId == ownership.personaId && it.participantId != participant.participantId
            }) { "Persona is already owned in this room" }

            val disconnected = participant.copy(isConnected = false)
            val connected = participant.copy(isConnected = true)
            listOf(
                appendHostEventLocked(
                    roomId,
                    CoopProtocol.admissionClientEventId(
                        "participant",
                        participant.participantId,
                        joinClientMessageId
                    ),
                    room.hostParticipantId,
                    CoopEventType.PARTICIPANT_UPSERT,
                    codec.participantToJson(CoopParticipantDto.from(disconnected))
                ).event,
                appendHostEventLocked(
                    roomId,
                    CoopProtocol.admissionClientEventId(
                        "persona",
                        ownership.personaId,
                        joinClientMessageId
                    ),
                    room.hostParticipantId,
                    CoopEventType.PERSONA_OWNERSHIP_SET,
                    codec.ownershipToJson(CoopPersonaOwnershipDto.from(ownership))
                ).event,
                appendHostEventLocked(
                    roomId,
                    CoopProtocol.admissionClientEventId(
                        "connected",
                        participant.participantId,
                        joinClientMessageId
                    ),
                    room.hostParticipantId,
                    CoopEventType.PARTICIPANT_UPSERT,
                    codec.participantToJson(CoopParticipantDto.from(connected))
                ).event
            )
        }
        }
    }

    private suspend fun appendHostEventLocked(
        roomId: String,
        clientEventId: String,
        originParticipantId: String,
        eventType: String,
        payloadJson: String,
        originPayloadJson: String = "",
        originSignature: String = "",
        pendingAttachmentDeletes: MutableList<MessageAttachmentEntity> = mutableListOf()
    ): CoopAppendHostEventResult {
        coopDao.getEventByClientId(roomId, clientEventId)?.let { existing ->
            require(
                existing.originParticipantId == originParticipantId &&
                    existing.eventType == eventType &&
                    existing.payloadJson == payloadJson &&
                    existing.originPayloadJson == originPayloadJson
            ) { "A duplicate co-op event ID cannot be reused for different content" }
            return CoopAppendHostEventResult(existing, inserted = false)
        }
        val room = requireHostRoom(roomId)
        require(coopDao.countEvents(roomId) < CoopProtocol.MAX_RETAINED_EVENTS) {
            "This co-op room reached its durable event limit; export or archive it before continuing"
        }
        if (eventType == CoopEventType.GENERATION_REQUESTED && room.activeGenerationRequestId != null) {
            throw CoopProtocolException("Another co-op generation is already active")
        }
        val event = CoopEventEntity(
            roomId = roomId,
            sequence = room.nextHostSequence,
            clientEventId = clientEventId,
            originParticipantId = originParticipantId,
            eventType = eventType,
            payloadJson = payloadJson,
            originPayloadJson = originPayloadJson,
            originSignature = originSignature
        )
        validateEventEnvelope(room, event)
        validateGenerationTransition(room, event)
        check(coopDao.insertEvent(event) != -1L) { "Unable to persist co-op event" }
        applyPayloadLocked(room, event, pendingAttachmentDeletes)
        val eventRoom = roomAfterEvent(room, event)
        val generationId = activeGenerationAfterEvent(eventRoom, event)
        coopDao.updateRoom(
            eventRoom.copy(
                nextHostSequence = event.sequence + 1,
                lastAppliedSequence = event.sequence,
                activeGenerationRequestId = generationId,
                updatedAt = System.currentTimeMillis()
            )
        )
        return CoopAppendHostEventResult(event, inserted = true)
    }

    suspend fun applyOrderedEvent(event: CoopEventEntity): Boolean = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
        val pendingAttachmentDeletes = mutableListOf<MessageAttachmentEntity>()
        val applied = database.withTransaction {
            coopDao.getEventByClientId(event.roomId, event.clientEventId)?.let { existing ->
                require(existing == event) { "Conflicting replay for an existing co-op event ID" }
                return@withTransaction false
            }
            val room = coopDao.getRoom(event.roomId) ?: throw CoopProtocolException("Unknown co-op room")
            require(coopDao.countEvents(event.roomId) < CoopProtocol.MAX_RETAINED_EVENTS) {
                "This co-op room reached its durable event limit"
            }
            val expected = room.lastAppliedSequence + 1
            if (event.sequence != expected) throw CoopSequenceGapException(expected, event.sequence)
            validateEventEnvelope(room, event)
            validateGenerationTransition(room, event)
            check(coopDao.insertEvent(event) != -1L) { "Unable to persist ordered co-op event" }
            applyPayloadLocked(room, event, pendingAttachmentDeletes)
            val eventRoom = roomAfterEvent(room, event)
            val activeGeneration = activeGenerationAfterEvent(eventRoom, event)
            coopDao.updateRoom(
                eventRoom.copy(
                    lastAppliedSequence = event.sequence,
                    nextHostSequence = maxOf(room.nextHostSequence, event.sequence + 1),
                    activeGenerationRequestId = activeGeneration,
                    updatedAt = System.currentTimeMillis()
                )
            )
            true
        }
        if (applied) cleanDeletedAttachmentFiles(pendingAttachmentDeletes)
        applied
        }
    }

    suspend fun buildSnapshot(
        roomId: String,
        afterSequence: Long,
        stagedParticipant: CoopParticipantEntity? = null,
        stagedOwnership: CoopPersonaOwnershipEntity? = null
    ): CoopSnapshotPayload = withContext(Dispatchers.IO) {
        val room = coopDao.getRoom(roomId) ?: throw CoopProtocolException("Unknown co-op room")
        val candidates = coopDao.getEventsAfter(roomId, afterSequence, CoopProtocol.MAX_EVENT_PAGE)
        require((stagedParticipant == null) == (stagedOwnership == null)) {
            "A staged admission requires both participant and Persona ownership"
        }
        val participants = (
            coopDao.getParticipants(roomId).filterNot {
                it.participantId == stagedParticipant?.participantId
            } + listOfNotNull(stagedParticipant)
            ).sortedBy { it.sortPosition }.map(CoopParticipantDto::from)
        val ownerships = (
            coopDao.getPersonaOwnerships(roomId).filterNot {
                it.personaId == stagedOwnership?.personaId
            } + listOfNotNull(stagedOwnership)
            ).sortedBy { it.sortPosition }.map(CoopPersonaOwnershipDto::from)
        validateSnapshotRoster(room, participants, ownerships)
        val participantIds = participants.map { it.participantId }.toSet()
        require(ownerships.all { it.participantId in participantIds }) {
            "Snapshot Persona owner is not a room participant"
        }
        fun payload(events: List<CoopEventEntity>, hasMore: Boolean) = CoopSnapshotPayload(
            roomId = room.roomId,
            hostParticipantId = room.hostParticipantId,
            payerMode = room.payerMode,
            selectedPayerParticipantId = room.selectedPayerParticipantId,
            lastSequence = room.lastAppliedSequence,
            participants = participants,
            personaOwnerships = ownerships,
            events = events.map(CoopEventDto::from),
            hasMoreEvents = hasMore
        )
        val base = payload(emptyList(), candidates.isNotEmpty())
        require(encodedSnapshotBytes(base) <= CoopProtocol.MAX_SNAPSHOT_JSON_BYTES) {
            "The room roster is too large to synchronize safely"
        }
        val selected = mutableListOf<CoopEventEntity>()
        for (event in candidates) {
            require(encodedEventBytes(event) <= CoopProtocol.MAX_EVENT_JSON_BYTES) {
                "A co-op event exceeds the synchronization byte limit"
            }
            val trial = selected + event
            val hasMore = event.sequence < room.lastAppliedSequence
            if (encodedSnapshotBytes(payload(trial, hasMore)) > CoopProtocol.MAX_SNAPSHOT_JSON_BYTES) break
            selected += event
        }
        require(selected.isNotEmpty() || candidates.isEmpty()) {
            "A co-op event cannot fit in a bounded snapshot page"
        }
        payload(
            selected,
            selected.lastOrNull()?.sequence?.let { it < room.lastAppliedSequence } == true
        )
    }

    suspend fun applySnapshot(snapshot: CoopSnapshotPayload) = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
        val room = coopDao.getRoom(snapshot.roomId) ?: throw CoopProtocolException("Unknown co-op room")
        require(snapshot.hostParticipantId == room.hostParticipantId) { "Snapshot host identity changed" }
        require(snapshot.participants.size in 1..CoopProtocol.MAX_PARTICIPANTS) { "Invalid participant count" }
        require(snapshot.personaOwnerships.size <= CoopProtocol.MAX_PERSONAS) { "Invalid Persona ownership count" }
        require(snapshot.events.size <= CoopProtocol.MAX_EVENT_PAGE) { "Snapshot event page is too large" }
        require(encodedSnapshotBytes(snapshot) <= CoopProtocol.MAX_SNAPSHOT_JSON_BYTES) {
            "Snapshot exceeds the total byte limit"
        }
        require(snapshot.events.all { encodedEventBytes(it.toEntity()) <= CoopProtocol.MAX_EVENT_JSON_BYTES }) {
            "Snapshot contains an oversized event"
        }
        validateSnapshotRoster(room, snapshot.participants, snapshot.personaOwnerships)
        require(snapshot.participants.all { it.roomId == room.roomId }) { "Snapshot participant room mismatch" }
        require(snapshot.personaOwnerships.all { it.roomId == room.roomId }) { "Snapshot Persona room mismatch" }
        require(snapshot.events.all { it.roomId == room.roomId }) { "Snapshot event room mismatch" }
        require(snapshot.participants.map { it.participantId }.distinct().size == snapshot.participants.size) {
            "Snapshot has duplicate participants"
        }
        val participantIds = snapshot.participants.map { it.participantId }.toSet()
        require(snapshot.hostParticipantId in participantIds) { "Snapshot omitted the host" }
        require(snapshot.participants.count { it.isHost } == 1 &&
            snapshot.participants.single { it.isHost }.participantId == snapshot.hostParticipantId
        ) { "Snapshot host role is invalid" }
        require(snapshot.personaOwnerships.all { it.participantId in participantIds }) {
            "Snapshot Persona owner is not a room participant"
        }
        if (snapshot.payerMode == CoopPayerMode.SELECTED_PARTICIPANT) {
            require(snapshot.selectedPayerParticipantId in participantIds) { "Snapshot selected payer is invalid" }
        }
        require(snapshot.payerMode in CoopPayerMode.all) { "Snapshot payer mode is invalid" }
        require(snapshot.lastSequence >= room.lastAppliedSequence) { "Snapshot sequence moved backwards" }
        val newEvents = snapshot.events.sortedBy { it.sequence }
            .filter { it.sequence > room.lastAppliedSequence }
        newEvents.zipWithNext().forEach { (left, right) ->
            require(right.sequence == left.sequence + 1) { "Snapshot event page is not contiguous" }
        }
        newEvents.firstOrNull()?.let {
            require(it.sequence == room.lastAppliedSequence + 1) { "Snapshot event page starts after a gap" }
        }
        val pageLastSequence = newEvents.lastOrNull()?.sequence ?: room.lastAppliedSequence
        if (snapshot.hasMoreEvents) {
            require(newEvents.isNotEmpty() && pageLastSequence < snapshot.lastSequence) {
                "Snapshot continuation marker is invalid"
            }
        } else {
            require(pageLastSequence == snapshot.lastSequence) {
                "Snapshot final page does not reach the advertised sequence"
            }
        }
        require(coopDao.countEvents(room.roomId) + newEvents.size <= CoopProtocol.MAX_RETAINED_EVENTS) {
            "This co-op room reached its durable event limit"
        }
        val pendingAttachmentDeletes = mutableListOf<MessageAttachmentEntity>()
        database.withTransaction {
            snapshot.participants.forEach { participant ->
                coopDao.getParticipant(participant.participantId)?.let { existing ->
                    require(existing.roomId == room.roomId &&
                        existing.deviceId == participant.deviceId &&
                        (existing.identityPublicKey.isBlank() ||
                            existing.identityPublicKey == participant.identityPublicKey)) {
                        "Snapshot participant ID collides with another room or membership identity"
                    }
                }
            }
            var workingRoom = room.copy(
                hostParticipantId = snapshot.hostParticipantId,
                payerMode = snapshot.payerMode,
                selectedPayerParticipantId = snapshot.selectedPayerParticipantId,
                connectionState = CoopConnectionState.CONNECTED,
                updatedAt = System.currentTimeMillis()
            )
            coopDao.deleteOwnerships(room.roomId)
            coopDao.deleteParticipants(room.roomId)
            coopDao.upsertParticipants(snapshot.participants.map { it.toEntity() })
            coopDao.upsertPersonaOwnerships(snapshot.personaOwnerships.map { it.toEntity() })
            snapshot.events.sortedBy { it.sequence }.forEach { dto ->
                val event = dto.toEntity()
                if (event.sequence <= workingRoom.lastAppliedSequence) {
                    coopDao.getEventByClientId(event.roomId, event.clientEventId)?.let { existing ->
                        require(existing == event) { "Conflicting replay in snapshot" }
                    }
                    return@forEach
                }
                require(event.sequence == workingRoom.lastAppliedSequence + 1) {
                    "Snapshot event page contains a sequence gap"
                }
                validateEventEnvelope(workingRoom, event)
                coopDao.getEventByClientId(event.roomId, event.clientEventId)?.let { existing ->
                    require(existing == event) { "Conflicting replay in snapshot" }
                    return@forEach
                }
                validateGenerationTransition(workingRoom, event)
                check(coopDao.insertEvent(event) != -1L) { "Unable to persist snapshot event" }
                applyPayloadLocked(workingRoom, event, pendingAttachmentDeletes)
                val eventRoom = roomAfterEvent(workingRoom, event)
                val activeGeneration = activeGenerationAfterEvent(eventRoom, event)
                workingRoom = eventRoom.copy(
                    lastAppliedSequence = event.sequence,
                    nextHostSequence = maxOf(workingRoom.nextHostSequence, event.sequence + 1),
                    activeGenerationRequestId = activeGeneration,
                    updatedAt = System.currentTimeMillis()
                )
            }
            coopDao.updateRoom(workingRoom)
        }
        cleanDeletedAttachmentFiles(pendingAttachmentDeletes)
        }
    }

    suspend fun resolvePayer(roomId: String, initiatorParticipantId: String): String = withContext(Dispatchers.IO) {
        val room = coopDao.getRoom(roomId) ?: throw CoopProtocolException("Unknown co-op room")
        val participantId = when (room.payerMode) {
            CoopPayerMode.EACH_PARTICIPANT -> initiatorParticipantId
            CoopPayerMode.HOST -> room.hostParticipantId
            CoopPayerMode.SELECTED_PARTICIPANT -> room.selectedPayerParticipantId
                ?: throw CoopProtocolException("Select a payer before generating")
            else -> throw CoopProtocolException("Unsupported payer mode")
        }
        val participant = coopDao.getParticipant(participantId)
            ?: throw CoopProtocolException("The assigned payer is not a room participant")
        require(participant.roomId == roomId && participant.isConnected) {
            "The assigned payer is not connected"
        }
        participantId
    }

    private suspend fun requireHostRoom(roomId: String): CoopRoomEntity {
        val room = coopDao.getRoom(roomId) ?: throw CoopProtocolException("Unknown co-op room")
        require(room.localRole == CoopRole.HOST) { "Only the host can order room events" }
        return room
    }

    private fun validateSnapshotRoster(
        room: CoopRoomEntity,
        participants: List<CoopParticipantDto>,
        ownerships: List<CoopPersonaOwnershipDto>
    ) {
        require(participants.size in 1..CoopProtocol.MAX_PARTICIPANTS) { "Invalid participant count" }
        require(ownerships.size <= CoopProtocol.MAX_PERSONAS) { "Invalid Persona ownership count" }
        require(participants.all { participant ->
            participant.roomId == room.roomId &&
                participant.participantId.isNotBlank() &&
                participant.participantId.length <= CoopProtocol.MAX_ID_CHARS &&
                participant.deviceId.isNotBlank() &&
                participant.deviceId.length <= CoopProtocol.MAX_DEVICE_ID_CHARS &&
                participant.identityPublicKey.length <= CoopProtocol.MAX_PUBLIC_KEY_CHARS &&
                (participant.identityPublicKey.isBlank() || runCatching {
                    val publicKey = Base64.getUrlDecoder().decode(participant.identityPublicKey)
                    CoopSessionCrypto.hostFingerprint(publicKey) == participant.deviceId
                }.getOrDefault(false)) &&
                participant.displayName.isNotBlank() &&
                participant.displayName.length <= CoopProtocol.MAX_PARTICIPANT_NAME_CHARS &&
                participant.sortPosition in 0 until CoopProtocol.MAX_PARTICIPANTS
        }) { "Snapshot contains invalid participant fields" }
        require(ownerships.all { ownership ->
            ownership.roomId == room.roomId &&
                ownership.personaId.isNotBlank() &&
                ownership.personaId.length <= CoopProtocol.MAX_ID_CHARS &&
                ownership.participantId.isNotBlank() &&
                ownership.participantId.length <= CoopProtocol.MAX_ID_CHARS &&
                ownership.personaDisplayNameSnapshot.isNotBlank() &&
                ownership.personaDisplayNameSnapshot.length <= CoopProtocol.MAX_DISPLAY_NAME_CHARS &&
                ownership.sortPosition in 0 until CoopProtocol.MAX_PERSONAS
        }) { "Snapshot contains invalid Persona ownership fields" }
        require(participants.map { it.deviceId }.distinct().size == participants.size) {
            "Snapshot reuses a participant membership identity"
        }
        require(participants.map { it.sortPosition }.distinct().size == participants.size) {
            "Snapshot contains duplicate participant positions"
        }
        require(ownerships.map { it.personaId }.distinct().size == ownerships.size) {
            "Snapshot contains duplicate Persona ownerships"
        }
        require(ownerships.map { it.sortPosition }.distinct().size == ownerships.size) {
            "Snapshot contains duplicate Persona positions"
        }
    }

    private fun encodedEventBytes(event: CoopEventEntity): Int =
        codec.eventToJson(CoopEventDto.from(event)).toByteArray(Charsets.UTF_8).size

    private fun encodedSnapshotBytes(snapshot: CoopSnapshotPayload): Int =
        codec.snapshotToJson(snapshot).toByteArray(Charsets.UTF_8).size

    private suspend fun applyPayloadLocked(
        room: CoopRoomEntity,
        event: CoopEventEntity,
        pendingAttachmentDeletes: MutableList<MessageAttachmentEntity> = mutableListOf()
    ) {
        when (event.eventType) {
            CoopEventType.MESSAGE_UPSERT -> {
                val payload = codec.messageFromJson(event.payloadJson)
                require(payload.chatId == room.chatId) { "Message does not belong to this room chat" }
                require(payload.messageId.isNotBlank() && payload.messageId.length <= CoopProtocol.MAX_ID_CHARS) {
                    "Invalid message ID"
                }
                require(payload.speakerId.isNotBlank() && payload.speakerId.length <= CoopProtocol.MAX_ID_CHARS) {
                    "Invalid speaker ID"
                }
                require(payload.variantGroupId.isNotBlank() && payload.variantGroupId.length <= CoopProtocol.MAX_ID_CHARS) {
                    "Invalid variant group ID"
                }
                require(payload.content.length <= CoopProtocol.MAX_MESSAGE_CHARS) { "Message is too large" }
                require(NpcStructuredOutputParser.visibleStoryText(payload.content) == payload.content) {
                    "Private NPC control data cannot be synchronized in co-op message text"
                }
                require(payload.speakerDisplayNameSnapshot.length <= 200) { "Speaker name is too large" }
                require(payload.generationExecutionId == null ||
                    payload.generationExecutionId.length <= CoopProtocol.MAX_ID_CHARS) {
                    "Generation execution binding is invalid"
                }
                require(!messageWasDeleted(room.roomId, payload.messageId)) {
                    "A deleted co-op message ID cannot be recreated"
                }
                val speakerType = runCatching { SpeakerType.valueOf(payload.speakerType) }
                    .getOrElse { throw CoopProtocolException("Unsupported message speaker type") }
                require(speakerType == SpeakerType.PERSONA || speakerType == SpeakerType.CHARACTER) {
                    "Co-op peers cannot inject SYSTEM or DIRECTOR messages"
                }
                val existing = messageDao.getMessageById(payload.messageId)
                val activeLifecycle = if (speakerType == SpeakerType.CHARACTER &&
                    room.activeGenerationRequestId != null
                ) {
                    getGenerationLifecycleLocked(room)
                } else {
                    null
                }
                val generationResultLifecycle = if (activeLifecycle?.request?.payerParticipantId ==
                    event.originParticipantId
                ) {
                    requireExecutingGenerationResult(room, event, payload, existing)
                } else {
                    require(payload.generationExecutionId == null) {
                        "Only the assigned executing payer can bind a generation result"
                    }
                    null
                }
                require(room.activeGenerationRequestId == null || generationResultLifecycle != null) {
                    "Room transcript changes are frozen while a co-op generation is active"
                }
                if (existing != null) {
                    val authorizedContinue = generationResultLifecycle?.request?.operation == "CONTINUE"
                    require(existing.chatId == room.chatId) { "Message ID already belongs to another chat" }
                    require(
                        existing.speakerType == speakerType &&
                            existing.speakerId == payload.speakerId &&
                            existing.speakerDisplayNameSnapshot == payload.speakerDisplayNameSnapshot &&
                            existing.variantGroupId == payload.variantGroupId
                    ) { "Message speaker identity and variant group are immutable" }
                    require(
                        existing.responsibleParticipantId == null ||
                            payload.responsibleParticipantId == null ||
                            existing.responsibleParticipantId == payload.responsibleParticipantId ||
                            authorizedContinue ||
                            event.originParticipantId == room.hostParticipantId
                    ) { "Responsible participant is immutable" }
                    require(payload.isPrimaryVariant == existing.isPrimaryVariant) {
                        "Message primary-variant state cannot be changed by an upsert"
                    }
                    if (authorizedContinue) {
                        require(
                            payload.editedAt == existing.editedAt &&
                                payload.content.length > existing.content.length &&
                                payload.content.startsWith(existing.content)
                        ) {
                            "Continue target changed after generation started"
                        }
                    }
                    if (speakerType == SpeakerType.PERSONA) {
                        val owner = coopDao.getPersonaOwnerships(room.roomId)
                            .firstOrNull { it.personaId == payload.speakerId && it.isActive }
                        require(owner?.participantId == event.originParticipantId) {
                            "Only the Persona owner can edit that message"
                        }
                    } else {
                        require(
                            event.originParticipantId == room.hostParticipantId ||
                                existing.responsibleParticipantId == event.originParticipantId ||
                                authorizedContinue
                        ) { "Only the responsible payer, approved Continue payer, or host can edit Character output" }
                    }
                } else if (!event.clientEventId.startsWith("seed:")) {
                    if (speakerType == SpeakerType.PERSONA) {
                        val ownership = coopDao.getPersonaOwnerships(room.roomId)
                            .firstOrNull { it.personaId == payload.speakerId && it.isActive }
                        require(ownership?.participantId == event.originParticipantId) {
                            "Persona message origin does not own that Persona"
                        }
                        require(payload.variantGroupId == payload.messageId) {
                            "Persona messages cannot join a Character variant group"
                        }
                    } else if (generationResultLifecycle == null &&
                        event.originParticipantId != room.hostParticipantId
                    ) {
                        throw CoopProtocolException("Character output requires an executing assigned payer")
                    }
                }
                if (existing == null && !payload.isPrimaryVariant) {
                    require(
                        messageDao.getVariantsOnce(payload.variantGroupId).any { it.isPrimaryVariant }
                    ) { "A new variant group must begin with a primary message" }
                }
                val orderIndex = if (event.clientEventId.startsWith("seed:")) {
                    payload.originalOrderIndex
                } else {
                    existing?.orderIndex ?: room.orderBase + event.sequence
                }
                val persisted = MessageEntity(
                        messageId = payload.messageId,
                        chatId = room.chatId,
                        speakerType = speakerType,
                        speakerId = payload.speakerId,
                        speakerDisplayNameSnapshot = payload.speakerDisplayNameSnapshot,
                        content = payload.content,
                        createdAt = existing?.createdAt ?: if (event.clientEventId.startsWith("seed:")) payload.createdAt else event.createdAt,
                        editedAt = if (existing == null) payload.editedAt else event.createdAt,
                        orderIndex = orderIndex,
                        variantGroupId = payload.variantGroupId,
                        isPrimaryVariant = payload.isPrimaryVariant,
                        responsibleParticipantId = existing?.responsibleParticipantId
                            ?: payload.responsibleParticipantId
                            ?: event.originParticipantId,
                        responsibleDeviceId = existing?.responsibleDeviceId ?: payload.responsibleDeviceId,
                        coOpEventId = event.eventId
                    )
                if (payload.isPrimaryVariant) {
                    messageDao.clearPrimaryVariants(payload.variantGroupId)
                }
                if (existing == null) {
                    check(messageDao.insertMessageIfAbsent(persisted) != -1L) {
                        "Message ID conflict"
                    }
                } else {
                    messageDao.updateMessage(persisted)
                }
            }
            CoopEventType.MESSAGE_DELETE -> {
                require(room.activeGenerationRequestId == null) {
                    "Room transcript changes are frozen while a co-op generation is active"
                }
                val payload = codec.deleteFromJson(event.payloadJson)
                coopDao.insertMessageTombstone(
                    CoopMessageTombstoneEntity(
                        roomId = room.roomId,
                        messageId = payload.messageId,
                        deletedSequence = event.sequence,
                        deletedAt = event.createdAt
                    )
                )
                val message = messageDao.getMessageById(payload.messageId)
                if (message == null) {
                    pendingAttachmentDeletes += database.messageAttachmentDao().getForMessage(payload.messageId)
                    database.npcDao().deleteUpdatesForMessage(payload.messageId)
                    messageDao.deleteGenerationMetadata(payload.messageId)
                    messageDao.deleteAttachmentsForMessage(payload.messageId)
                    return
                }
                require(message.chatId == room.chatId) { "Message does not belong to this room chat" }
                require(
                    event.originParticipantId == room.hostParticipantId ||
                        message.responsibleParticipantId == event.originParticipantId
                ) { "Event origin is not allowed to delete this message" }
                pendingAttachmentDeletes += database.messageAttachmentDao().getForMessage(payload.messageId)
                database.npcDao().deleteUpdatesForMessage(payload.messageId)
                messageDao.deleteMessageAndRepair(message)
                if (message.isPrimaryVariant) {
                    messageDao.getVariantsOnce(message.variantGroupId)
                        .firstOrNull { it.isPrimaryVariant }
                        ?.let { survivor ->
                            database.npcDao().setMessageUpdatesApplied(survivor.messageId, true)
                        }
                }
            }
            CoopEventType.PARTICIPANT_UPSERT -> {
                val participant = codec.participantFromJson(event.payloadJson).toEntity()
                require(participant.roomId == room.roomId) { "Participant room mismatch" }
                require(participant.participantId.isNotBlank() &&
                    participant.participantId.length <= CoopProtocol.MAX_ID_CHARS) { "Participant ID is invalid" }
                require(participant.deviceId.isNotBlank() &&
                    participant.deviceId.length <= CoopProtocol.MAX_DEVICE_ID_CHARS) {
                    "Participant membership fingerprint is invalid"
                }
                require(participant.identityPublicKey.length <= CoopProtocol.MAX_PUBLIC_KEY_CHARS &&
                    (participant.identityPublicKey.isBlank() || runCatching {
                        val publicKey = Base64.getUrlDecoder().decode(participant.identityPublicKey)
                        CoopSessionCrypto.hostFingerprint(publicKey) == participant.deviceId
                    }.getOrDefault(false))) {
                    "Participant membership public key is invalid"
                }
                require(participant.displayName.isNotBlank() &&
                    participant.displayName.length <= CoopProtocol.MAX_PARTICIPANT_NAME_CHARS) {
                    "Invalid participant name"
                }
                require(!participant.isHost || participant.participantId == room.hostParticipantId) {
                    "Only the pinned host participant can hold the host role"
                }
                coopDao.getParticipant(participant.participantId)?.let { existing ->
                    require(existing.roomId == room.roomId &&
                        existing.deviceId == participant.deviceId &&
                        (existing.identityPublicKey.isBlank() ||
                            existing.identityPublicKey == participant.identityPublicKey)) {
                        "Participant membership identity is immutable"
                    }
                }
                require(coopDao.getParticipants(room.roomId).size < CoopProtocol.MAX_PARTICIPANTS ||
                    coopDao.getParticipant(participant.participantId) != null) { "Room participant limit reached" }
                coopDao.upsertParticipant(participant)
            }
            CoopEventType.PARTICIPANT_LEFT -> {
                val participant = codec.participantFromJson(event.payloadJson).toEntity()
                require(participant.roomId == room.roomId) { "Participant room mismatch" }
                val existing = coopDao.getParticipant(participant.participantId)
                    ?: throw CoopProtocolException("Participant leaving event references an unknown member")
                require(existing.roomId == room.roomId &&
                    existing.deviceId == participant.deviceId &&
                    (existing.identityPublicKey.isBlank() ||
                        existing.identityPublicKey == participant.identityPublicKey)) {
                    "Participant membership identity is immutable"
                }
                coopDao.upsertParticipant(participant.copy(isConnected = false, lastSeenAt = event.createdAt))
            }
            CoopEventType.PERSONA_OWNERSHIP_SET -> {
                val ownership = codec.ownershipFromJson(event.payloadJson).toEntity()
                require(ownership.roomId == room.roomId) { "Persona ownership room mismatch" }
                require(coopDao.getParticipant(ownership.participantId)?.roomId == room.roomId) {
                    "Persona owner is not a room participant"
                }
                require(ownership.personaId.length <= CoopProtocol.MAX_ID_CHARS) { "Persona ID is too large" }
                require(ownership.personaDisplayNameSnapshot.isNotBlank() &&
                    ownership.personaDisplayNameSnapshot.length <= CoopProtocol.MAX_DISPLAY_NAME_CHARS) {
                    "Persona display name is invalid"
                }
                require(coopDao.getPersonaOwnerships(room.roomId).size < CoopProtocol.MAX_PERSONAS ||
                    coopDao.getPersonaOwnerships(room.roomId).any { it.personaId == ownership.personaId }) {
                    "Room Persona limit reached"
                }
                coopDao.upsertPersonaOwnership(ownership)
            }
            CoopEventType.CHARACTER_ROSTER_SET -> {
                val roster = codec.rosterFromJson(event.payloadJson)
                require(roster.characters.size <= CoopProtocol.MAX_CHARACTERS) { "Room Character limit reached" }
                require(roster.characters.map { it.characterId }.distinct().size == roster.characters.size) {
                    "Character roster contains duplicate IDs"
                }
                require(roster.characters.all {
                    it.characterId.isNotBlank() && it.characterId.length <= CoopProtocol.MAX_ID_CHARS &&
                        it.displayNameSnapshot.isNotBlank() && it.displayNameSnapshot.length <= 200 &&
                        it.sortPosition in 0 until CoopProtocol.MAX_CHARACTERS
                }) { "Character roster contains invalid fields" }
                require(roster.characters.map { it.sortPosition }.distinct().size == roster.characters.size) {
                    "Character roster contains duplicate positions"
                }
                chatDao.deleteCharacterParticipantsForChat(room.chatId)
                roster.characters.sortedBy { it.sortPosition }.forEach { character ->
                    chatDao.insertChatParticipant(
                        ChatParticipantEntity(
                            chatId = room.chatId,
                            characterId = character.characterId,
                            isActive = character.isActive,
                            sortPosition = character.sortPosition
                        )
                    )
                }
            }
            CoopEventType.PAYER_POLICY_SET -> {
                val payer = codec.payerFromJson(event.payloadJson)
                require(payer.payerMode in CoopPayerMode.all) { "Unsupported payer mode" }
                if (payer.payerMode == CoopPayerMode.SELECTED_PARTICIPANT) {
                    val participantId = requireNotNull(payer.selectedPayerParticipantId) { "Selected payer is required" }
                    val participant = coopDao.getParticipant(participantId)
                    require(participant?.roomId == room.roomId) { "Selected payer must belong to this room" }
                }
            }
            CoopEventType.GENERATION_REQUESTED -> {
                val request = codec.generationFromJson(event.payloadJson)
                require(request.requestId.isNotBlank() && request.requestId.length <= CoopProtocol.MAX_ID_CHARS) {
                    "Invalid generation request ID"
                }
                require(request.operation in setOf("NORMAL", "REGENERATE", "CONTINUE")) {
                    "Unsupported generation operation"
                }
                require(request.contextFingerprint?.matches(Regex("[0-9a-f]{64}")) == true) {
                    "Generation context fingerprint is missing or invalid"
                }
                require(request.modelId?.isNotBlank() == true && request.modelId.length <= 300) {
                    "Generation model is missing or invalid"
                }
                require(request.providerRoutingMode in setOf("AUTO", "PREFER", "LOCK")) {
                    "Generation provider routing is missing or invalid"
                }
                require(request.providerEndpoint == null || request.providerEndpoint.length <= 500) {
                    "Generation provider endpoint is invalid"
                }
                if (request.providerRoutingMode == "LOCK") {
                    require(!request.providerEndpoint.isNullOrBlank()) {
                        "Locked generation provider endpoint is missing"
                    }
                }
                require(coopDao.getParticipant(request.initiatorParticipantId)?.roomId == room.roomId) {
                    "Generation initiator is not a room participant"
                }
                require(coopDao.getParticipant(request.payerParticipantId)?.roomId == room.roomId) {
                    "Generation payer is not a room participant"
                }
                require(chatDao.getChatParticipant(room.chatId, request.characterId)?.isActive == true) {
                    "Generation Character is not active in this room"
                }
                if (request.operation == "NORMAL") {
                    require(request.targetMessageId == null) { "Normal generation cannot specify a target" }
                } else {
                    require(!request.targetMessageId.isNullOrBlank()) { "Generation target is required" }
                    val target = messageDao.getMessageById(request.targetMessageId)
                        ?: throw CoopProtocolException("Generation target is unavailable")
                    require(target.chatId == room.chatId) {
                        "Generation target does not belong to this room"
                    }
                    require(target.speakerType == SpeakerType.CHARACTER && target.speakerId == request.characterId) {
                        "Generation target does not belong to the requested Character"
                    }
                }
            }
            CoopEventType.GENERATION_APPROVED -> {
                val approval = codec.approvalFromJson(event.payloadJson)
                require(approval.requestId.isNotBlank() && approval.requestId.length <= CoopProtocol.MAX_ID_CHARS) {
                    "Invalid generation approval ID"
                }
            }
            CoopEventType.GENERATION_EXECUTING -> {
                val execution = codec.executionFromJson(event.payloadJson)
                require(execution.requestId.isNotBlank() && execution.requestId.length <= CoopProtocol.MAX_ID_CHARS) {
                    "Invalid generation execution request ID"
                }
                require(execution.executionId.isNotBlank() && execution.executionId.length <= CoopProtocol.MAX_ID_CHARS) {
                    "Invalid generation execution ID"
                }
            }
            CoopEventType.GENERATION_FINISHED, CoopEventType.GENERATION_FAILED -> {
                val completion = codec.completionFromJson(event.payloadJson)
                require(completion.requestId.isNotBlank() && completion.requestId.length <= CoopProtocol.MAX_ID_CHARS) {
                    "Invalid generation completion ID"
                }
                require(completion.errorMessage == null || completion.errorMessage in CoopGenerationResultCode.all) {
                    "Generation completion contains an unsupported public error"
                }
                require(!completion.executionId.isNullOrBlank() &&
                    completion.executionId.length <= CoopProtocol.MAX_ID_CHARS) {
                    "Generation completion execution ID is missing or invalid"
                }
                if (event.eventType == CoopEventType.GENERATION_FINISHED) {
                    require(completion.errorMessage == null && !completion.messageId.isNullOrBlank()) {
                        "Successful generation must identify its result message"
                    }
                } else {
                    require(completion.errorMessage != null && completion.messageId == null && completion.metadata == null) {
                        "Failed generation cannot attach a result or metadata"
                    }
                }
                completion.messageId?.let { messageId ->
                    val lifecycle = getGenerationLifecycleLocked(room)
                        ?: throw CoopProtocolException("Generation result has no active request")
                    val result = messageDao.getMessageById(messageId)
                        ?: throw CoopProtocolException("Generation result message is unavailable")
                    val resultEvent = result.coOpEventId?.let { coopDao.getEventById(it) }
                        ?: throw CoopProtocolException("Generation result is not backed by a room event")
                    val resultPayload = codec.messageFromJson(resultEvent.payloadJson)
                    requireExecutingGenerationResult(
                        room,
                        resultEvent,
                        resultPayload,
                        result.takeIf { lifecycle.request.operation == "CONTINUE" }
                    )
                    require(
                            result.chatId == room.chatId &&
                            result.speakerType == SpeakerType.CHARACTER &&
                            result.speakerId == lifecycle.request.characterId &&
                            (lifecycle.request.operation == "CONTINUE" ||
                                result.responsibleParticipantId == lifecycle.request.payerParticipantId) &&
                            resultEvent.roomId == room.roomId &&
                            resultEvent.eventType == CoopEventType.MESSAGE_UPSERT &&
                            resultEvent.originParticipantId == lifecycle.request.payerParticipantId &&
                            resultPayload.generationExecutionId == lifecycle.executionId
                    ) { "Generation result is not owned by the authorized Character payer" }
                    when (lifecycle.request.operation) {
                        "NORMAL" -> require(result.variantGroupId == result.messageId) {
                            "Normal generation result has an invalid variant group"
                        }
                        "REGENERATE" -> {
                            val targetId = lifecycle.request.targetMessageId
                                ?: throw CoopProtocolException("Regeneration target is unavailable")
                            val target = messageDao.getMessageById(targetId)
                                ?: throw CoopProtocolException("Regeneration target is unavailable")
                            require(result.variantGroupId == target.variantGroupId) {
                                "Regeneration result does not match the target variant group"
                            }
                        }
                        "CONTINUE" -> require(result.messageId == lifecycle.request.targetMessageId) {
                            "Continue must complete the targeted Character message"
                        }
                    }
                }
                completion.metadata?.let { metadata ->
                    require(metadata.messageId == completion.messageId) {
                        "Generation metadata message does not match the result"
                    }
                    require(listOfNotNull(
                        metadata.promptTokens,
                        metadata.completionTokens,
                        metadata.totalTokens,
                        metadata.reasoningTokens,
                        metadata.cachedTokens
                    ).all { it >= 0 }) { "Generation token counts cannot be negative" }
                    require(metadata.reportedCost == null ||
                        (metadata.reportedCost.isFinite() && metadata.reportedCost >= 0.0)) {
                        "Generation cost is invalid"
                    }
                    require(metadata.generationTimeMs == null || metadata.generationTimeMs >= 0L) {
                        "Generation duration is invalid"
                    }
                    require(metadata.requestedModelId == null || metadata.requestedModelId.length <= 300) {
                        "Requested model metadata is too large"
                    }
                    require(metadata.resolvedModelId == null || metadata.resolvedModelId.length <= 300) {
                        "Resolved model metadata is too large"
                    }
                    require(metadata.provider == null || metadata.provider.length <= 200) {
                        "Provider metadata is too large"
                    }
                    require(metadata.finishReason == null || metadata.finishReason.length <= 100) {
                        "Finish reason metadata is too large"
                    }
                    messageDao.insertGenerationMetadata(metadata.toEntity())
                }
            }
            else -> throw CoopProtocolException("Unsupported co-op event type: ${event.eventType}")
        }
    }

    private suspend fun cleanDeletedAttachmentFiles(attachments: List<MessageAttachmentEntity>) {
        val repository = attachmentRepository ?: return
        repository.deleteFilesAndReconcile(attachments.distinctBy { it.attachmentId })
    }

    private suspend fun messageWasDeleted(roomId: String, messageId: String): Boolean =
        coopDao.isMessageTombstoned(roomId, messageId)

    suspend fun deleteRoomKeepingChat(roomId: String) = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) { coopDao.deleteRoom(roomId) }
    }

    private suspend fun requireExecutingGenerationResult(
        room: CoopRoomEntity,
        event: CoopEventEntity,
        payload: CoopMessagePayload,
        existing: MessageEntity?
    ): CoopGenerationLifecycle {
        val lifecycle = getGenerationLifecycleLocked(room)
            ?: throw CoopProtocolException("Character output requires an active generation")
        require(lifecycle.stage == CoopGenerationStage.EXECUTING) {
            "Character output requires a durable executing generation"
        }
        val executionId = lifecycle.executionId
            ?: throw CoopProtocolException("Executing generation is missing its execution ID")
        val executionEvent = coopDao.getLatestEventOfType(room.roomId, CoopEventType.GENERATION_EXECUTING)
            ?: throw CoopProtocolException("Generation execution event is unavailable")
        val execution = codec.executionFromJson(executionEvent.payloadJson)
        require(
            execution.requestId == lifecycle.request.requestId &&
                execution.executionId == executionId &&
                payload.generationExecutionId == executionId &&
                event.clientEventId == CoopProtocol.generationResultClientEventId(executionId) &&
                event.sequence > executionEvent.sequence &&
                event.originParticipantId == lifecycle.request.payerParticipantId &&
                payload.speakerId == lifecycle.request.characterId
        ) { "Character result is not bound to the active execution" }
        when (lifecycle.request.operation) {
            "NORMAL" -> require(existing == null && payload.variantGroupId == payload.messageId) {
                "Normal generation must create one new result and variant group"
            }
            "REGENERATE" -> {
                require(existing == null) { "Regeneration must create one new result variant" }
                val target = lifecycle.request.targetMessageId?.let { messageDao.getMessageById(it) }
                    ?: throw CoopProtocolException("Regeneration target is unavailable")
                require(payload.variantGroupId == target.variantGroupId) {
                    "Regeneration output used the wrong variant group"
                }
            }
            "CONTINUE" -> require(existing?.messageId == lifecycle.request.targetMessageId) {
                "Continue must update the targeted Character message"
            }
        }
        return lifecycle
    }

    private fun roomAfterEvent(room: CoopRoomEntity, event: CoopEventEntity): CoopRoomEntity =
        if (event.eventType == CoopEventType.PAYER_POLICY_SET) {
            val payer = codec.payerFromJson(event.payloadJson)
            room.copy(
                payerMode = payer.payerMode,
                selectedPayerParticipantId = payer.selectedPayerParticipantId
            )
        } else {
            room
        }

    private suspend fun validateGenerationTransition(room: CoopRoomEntity, event: CoopEventEntity) {
        when (event.eventType) {
            CoopEventType.GENERATION_REQUESTED -> require(room.activeGenerationRequestId == null) {
                "Another co-op generation is already active"
            }
            CoopEventType.GENERATION_APPROVED -> {
                val approval = codec.approvalFromJson(event.payloadJson)
                val lifecycle = getGenerationLifecycleLocked(room)
                    ?: throw CoopProtocolException("Generation approval has no active request")
                require(approval.requestId == lifecycle.request.requestId &&
                    lifecycle.stage == CoopGenerationStage.PENDING_APPROVAL) {
                    "Generation approval does not match a pending request"
                }
                require(event.originParticipantId == lifecycle.request.payerParticipantId) {
                    "Only the assigned payer can approve generation"
                }
            }
            CoopEventType.GENERATION_EXECUTING -> {
                val execution = codec.executionFromJson(event.payloadJson)
                val lifecycle = getGenerationLifecycleLocked(room)
                    ?: throw CoopProtocolException("Generation execution has no active request")
                require(execution.requestId == lifecycle.request.requestId &&
                    lifecycle.stage == CoopGenerationStage.APPROVED) {
                    "Generation execution does not match an approved request"
                }
                require(execution.executionId == execution.requestId) {
                    "Generation execution ID must be stable for idempotency"
                }
                require(event.originParticipantId == lifecycle.request.payerParticipantId) {
                    "Only the assigned payer can begin generation"
                }
            }
            CoopEventType.GENERATION_FINISHED, CoopEventType.GENERATION_FAILED -> {
                val completion = codec.completionFromJson(event.payloadJson)
                val lifecycle = getGenerationLifecycleLocked(room)
                    ?: throw CoopProtocolException("Generation completion has no active request")
                val hostFailure = event.eventType == CoopEventType.GENERATION_FAILED &&
                    event.originParticipantId == room.hostParticipantId &&
                    when (completion.errorMessage) {
                        CoopGenerationResultCode.APPROVAL_TIMEOUT ->
                            lifecycle.stage == CoopGenerationStage.PENDING_APPROVAL
                        CoopGenerationResultCode.EXECUTION_NOT_STARTED ->
                            lifecycle.stage == CoopGenerationStage.APPROVED
                        CoopGenerationResultCode.EXECUTION_TIMEOUT ->
                            lifecycle.stage == CoopGenerationStage.EXECUTING
                        CoopGenerationResultCode.PAYER_DISCONNECTED ->
                            lifecycle.stage != CoopGenerationStage.EXECUTING
                        else -> false
                    }
                val payerCompletion = event.originParticipantId == lifecycle.request.payerParticipantId &&
                    lifecycle.stage == CoopGenerationStage.EXECUTING &&
                    completion.executionId == lifecycle.executionId
                val payerDecline = event.eventType == CoopEventType.GENERATION_FAILED &&
                    event.originParticipantId == lifecycle.request.payerParticipantId &&
                    completion.errorMessage == CoopGenerationResultCode.PAYER_DECLINED &&
                    lifecycle.stage != CoopGenerationStage.EXECUTING
                require(room.activeGenerationRequestId == completion.requestId &&
                    completion.executionId == completion.requestId &&
                    (hostFailure || payerCompletion || payerDecline)) {
                    "Generation completion does not match the active request"
                }
            }
        }
    }

    private suspend fun getGenerationLifecycleLocked(room: CoopRoomEntity): CoopGenerationLifecycle? {
        val activeId = room.activeGenerationRequestId ?: return null
        val requestEvent = coopDao.getLatestEventOfType(room.roomId, CoopEventType.GENERATION_REQUESTED)
            ?: throw CoopProtocolException("Active generation request event is missing")
        val request = codec.generationFromJson(requestEvent.payloadJson)
        require(request.requestId == activeId) { "Active generation request identity is inconsistent" }
        val transition = coopDao.getLatestEventOfTypes(
            room.roomId,
            listOf(CoopEventType.GENERATION_APPROVED, CoopEventType.GENERATION_EXECUTING)
        )?.takeIf { it.sequence > requestEvent.sequence }
        return when (transition?.eventType) {
            CoopEventType.GENERATION_APPROVED -> {
                val approval = codec.approvalFromJson(transition.payloadJson)
                require(approval.requestId == activeId) { "Generation approval identity is inconsistent" }
                CoopGenerationLifecycle(request, CoopGenerationStage.APPROVED, transition.createdAt)
            }
            CoopEventType.GENERATION_EXECUTING -> {
                val execution = codec.executionFromJson(transition.payloadJson)
                require(execution.requestId == activeId) { "Generation execution identity is inconsistent" }
                CoopGenerationLifecycle(
                    request,
                    CoopGenerationStage.EXECUTING,
                    transition.createdAt,
                    execution.executionId
                )
            }
            else -> CoopGenerationLifecycle(
                request,
                CoopGenerationStage.PENDING_APPROVAL,
                request.requestedAt
            )
        }
    }

    private suspend fun validateEventEnvelope(room: CoopRoomEntity, event: CoopEventEntity) {
        require(event.roomId == room.roomId) { "Event room mismatch" }
        require(event.sequence > 0) { "Event sequence must be positive" }
        require(event.eventId.isNotBlank() && event.eventId.length <= CoopProtocol.MAX_ID_CHARS) {
            "Invalid event ID"
        }
        require(event.clientEventId.isNotBlank() && event.clientEventId.length <= CoopProtocol.MAX_ID_CHARS) {
            "Invalid client event ID"
        }
        require(event.originParticipantId.isNotBlank() && event.originParticipantId.length <= CoopProtocol.MAX_ID_CHARS) {
            "Invalid event origin"
        }
        require(event.eventType in CoopEventType.all) { "Unsupported co-op event type" }
        require(encodedEventBytes(event) <= CoopProtocol.MAX_EVENT_JSON_BYTES) {
            "Event exceeds the synchronization byte limit"
        }
        val origin = coopDao.getParticipant(event.originParticipantId)
            ?.takeIf { it.roomId == room.roomId }
            ?: throw CoopProtocolException("Event origin is not a room participant")
        if (event.originParticipantId != room.hostParticipantId) {
            require(event.originPayloadJson.isNotBlank() &&
                event.originPayloadJson.toByteArray(Charsets.UTF_8).size <= CoopProtocol.MAX_EVENT_JSON_BYTES) {
                "Participant-origin event authorization payload is invalid"
            }
            require(event.originSignature.isNotBlank() &&
                event.originSignature.length <= CoopProtocol.MAX_SIGNATURE_CHARS) {
                "Participant-origin event signature is invalid"
            }
            require(origin.identityPublicKey.isNotBlank() &&
                origin.identityPublicKey.length <= CoopProtocol.MAX_PUBLIC_KEY_CHARS) {
                "Participant membership public key is unavailable"
            }
            val publicKey = runCatching { Base64.getUrlDecoder().decode(origin.identityPublicKey) }
                .getOrElse { throw CoopProtocolException("Participant membership public key is malformed") }
            require(CoopSessionCrypto.hostFingerprint(publicKey) == origin.deviceId) {
                "Participant membership public key fingerprint changed"
            }
            require(
                CoopSessionCrypto.verifyParticipantEvent(
                    publicKey,
                    event.originSignature,
                    event.roomId,
                    event.clientEventId,
                    event.originParticipantId,
                    event.eventType,
                    event.originPayloadJson
                )
            ) { "Participant-origin event signature is invalid" }
            validateOriginAuthorization(event)
        }
        if (event.eventType in HOST_ONLY_EVENT_TYPES) {
            require(event.originParticipantId == room.hostParticipantId) {
                "Only the host can order this event type"
            }
        }
    }

    private fun validateOriginAuthorization(event: CoopEventEntity) {
        when (event.eventType) {
            CoopEventType.MESSAGE_UPSERT -> {
                val origin = codec.messageFromJson(event.originPayloadJson)
                val ordered = codec.messageFromJson(event.payloadJson)
                require(
                    origin.copy(
                        chatId = ordered.chatId,
                        responsibleParticipantId = ordered.responsibleParticipantId,
                        responsibleDeviceId = ordered.responsibleDeviceId
                    ) == ordered
                ) { "Host changed signed message content or speaker identity" }
            }
            CoopEventType.MESSAGE_DELETE -> require(
                codec.deleteFromJson(event.originPayloadJson) == codec.deleteFromJson(event.payloadJson)
            ) { "Host changed a signed message deletion" }
            CoopEventType.GENERATION_REQUESTED -> {
                val origin = codec.generationFromJson(event.originPayloadJson)
                val ordered = codec.generationFromJson(event.payloadJson)
                require(
                    origin.copy(
                        initiatorParticipantId = ordered.initiatorParticipantId,
                        payerParticipantId = ordered.payerParticipantId,
                        requestedAt = ordered.requestedAt,
                        modelId = ordered.modelId,
                        providerRoutingMode = ordered.providerRoutingMode,
                        providerEndpoint = ordered.providerEndpoint,
                        contextFingerprint = ordered.contextFingerprint
                    ) == ordered
                ) { "Host changed signed generation request intent" }
            }
            CoopEventType.GENERATION_APPROVED -> require(
                codec.approvalFromJson(event.originPayloadJson) == codec.approvalFromJson(event.payloadJson)
            ) { "Host changed signed payer approval" }
            CoopEventType.GENERATION_EXECUTING -> require(
                codec.executionFromJson(event.originPayloadJson) == codec.executionFromJson(event.payloadJson)
            ) { "Host changed signed payer execution acknowledgement" }
            CoopEventType.GENERATION_FINISHED, CoopEventType.GENERATION_FAILED -> require(
                codec.completionFromJson(event.originPayloadJson) == codec.completionFromJson(event.payloadJson)
            ) { "Host changed signed payer completion" }
            else -> throw CoopProtocolException("A participant cannot originate ${event.eventType}")
        }
    }

    private fun activeGenerationAfterEvent(room: CoopRoomEntity, event: CoopEventEntity): String? =
        when (event.eventType) {
            CoopEventType.GENERATION_REQUESTED -> codec.generationFromJson(event.payloadJson).requestId
            CoopEventType.GENERATION_FINISHED, CoopEventType.GENERATION_FAILED -> null
            else -> room.activeGenerationRequestId
        }

    companion object {
        private val HOST_ONLY_EVENT_TYPES = setOf(
            CoopEventType.PARTICIPANT_UPSERT,
            CoopEventType.PARTICIPANT_LEFT,
            CoopEventType.PERSONA_OWNERSHIP_SET,
            CoopEventType.CHARACTER_ROSTER_SET,
            CoopEventType.PAYER_POLICY_SET
        )
    }

    private fun pairingHash(salt: String, code: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$salt:$code".toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().withoutPadding().encodeToString(bytes)
    }
}
