package com.example.domain.coop

import com.example.data.model.CoopConnectionState
import com.example.data.model.CoopEventEntity
import com.example.data.model.CoopEventType
import com.example.data.model.CoopParticipantEntity
import com.example.data.model.CoopPayerMode
import com.example.data.model.CoopPersonaOwnershipEntity
import com.example.data.model.CoopRole
import com.example.data.model.CoopRoomEntity
import com.example.data.model.MessageEntity
import com.example.domain.repository.CoopHostInvite
import com.example.domain.repository.CoopRepository
import java.util.UUID
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CoopPendingJoin(
    val participantId: String,
    val participantDisplayName: String,
    val personaDisplayName: String,
    val membershipFingerprint: String,
    val requestedAt: Long
)

private data class PendingJoinRecord(
    val joinClientMessageId: String,
    val participant: CoopParticipantEntity,
    val ownership: CoopPersonaOwnershipEntity,
    val lastAppliedSequence: Long,
    val requestedAt: Long = System.currentTimeMillis()
)

private data class GenerationContextAuthorizerRegistration(
    val token: String,
    val authorizer: suspend (CoopGenerationRequestPayload) -> CoopGenerationAuthorization
)

data class CoopSessionState(
    val room: CoopRoomEntity? = null,
    val participants: List<CoopParticipantEntity> = emptyList(),
    val personaOwnerships: List<CoopPersonaOwnershipEntity> = emptyList(),
    val pairingCode: String? = null,
    val suggestedAddresses: List<String> = emptyList(),
    val pendingPayerRequest: CoopGenerationCommand? = null,
    val payerRequestStage: String? = null,
    val pendingJoins: List<CoopPendingJoin> = emptyList(),
    val statusMessage: String? = null,
    val errorMessage: String? = null
) {
    val hostParticipant: CoopParticipantEntity?
        get() = participants.firstOrNull { it.participantId == room?.hostParticipantId }
    val localParticipant: CoopParticipantEntity?
        get() = participants.firstOrNull { it.participantId == room?.localParticipantId }
    val authoritativeActionsAllowed: Boolean
        get() = room?.connectionState == CoopConnectionState.CONNECTED
}

class CoopSessionManager(
    private val repository: CoopRepository,
    private val deviceIdentity: CoopDeviceIdentity,
    private val roomIdentityStore: CoopRoomIdentityStore? = null,
    private val participantIdentityStore: CoopParticipantIdentityStore? = null,
    private val networkingEnabled: () -> Boolean = { LAN_NETWORKING_ENABLED },
    private val autoAcceptJoinsForTests: Boolean = false,
    private val localLanAddresses: () -> List<String> = { LanCoopTransport.localLanAddresses() },
    private val transportFactory: (CoopCodec) -> CoopTransport = { LanCoopTransport(it) }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(CoopSessionState())
    val state: StateFlow<CoopSessionState> = mutableState.asStateFlow()
    private val mutableGenerationCommands = MutableSharedFlow<CoopGenerationCommand>(extraBufferCapacity = 16)
    val generationCommands: SharedFlow<CoopGenerationCommand> = mutableGenerationCommands.asSharedFlow()
    private var transport: CoopTransport? = null
    private var transportJob: Job? = null
    private var reconciliationJob: Job? = null
    private val proposalWindows = mutableMapOf<String, ProposalWindow>()
    private val controlWindows = mutableMapOf<String, ProposalWindow>()
    private val resyncContinuations = mutableMapOf<String, Long>()
    private val outstandingResyncAfter = mutableMapOf<String, Long>()
    private val claimedGenerationExecutions = ConcurrentHashMap.newKeySet<String>()
    private val pendingJoinRecords = ConcurrentHashMap<String, PendingJoinRecord>()
    private val pendingProposalResults = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val sessionStartMutex = Mutex()
    private val generationContextAuthorizers =
        ConcurrentHashMap<String, GenerationContextAuthorizerRegistration>()

    fun registerGenerationContextAuthorizer(
        chatId: String,
        authorizer: suspend (CoopGenerationRequestPayload) -> CoopGenerationAuthorization
    ): String {
        require(chatId.isNotBlank()) { "Chat ID is required for co-op context authorization" }
        val token = UUID.randomUUID().toString()
        generationContextAuthorizers[chatId] = GenerationContextAuthorizerRegistration(token, authorizer)
        return token
    }

    fun unregisterGenerationContextAuthorizer(chatId: String, token: String) {
        generationContextAuthorizers.computeIfPresent(chatId) { _, current ->
            current.takeUnless { it.token == token }
        }
    }

    /** Process-wide single-owner gate for one durable paid execution. */
    fun tryClaimGenerationExecution(executionId: String): Boolean {
        require(executionId.isNotBlank() && executionId.length <= CoopProtocol.MAX_ID_CHARS) {
            "Invalid generation execution ID"
        }
        return claimedGenerationExecutions.add(executionId)
    }

    fun exportInviteBundle(): String {
        val state = mutableState.value
        val room = state.room?.takeIf { it.localRole == CoopRole.HOST }
            ?: throw CoopProtocolException("Only the active host can create an invite bundle")
        val secret = state.pairingCode
            ?: throw CoopProtocolException("Resume hosting to rotate and create a current invite")
        val hostAddress = room.hostAddress
            ?: throw CoopProtocolException("This room has no private LAN bind address; end it and host again")
        CoopLanAddressPolicy.requirePrivateIpv4(hostAddress)
        return repository.codec.inviteBundleToText(
            CoopInviteBundle(
                hostAddress = hostAddress,
                port = room.hostPort,
                roomId = room.roomId,
                hostParticipantId = room.hostParticipantId,
                inviteSecret = secret,
                hostPublicKey = room.hostPublicKey,
                hostKeyFingerprint = room.hostKeyFingerprint
            )
        )
    }

    fun parseInviteBundle(text: String): CoopInviteBundle = repository.codec.inviteBundleFromText(text)

    suspend fun approvePendingGeneration(requestId: String) {
        val command = mutableState.value.pendingPayerRequest
            ?.takeIf { it.request.requestId == requestId }
            ?: throw CoopProtocolException("Generation approval is no longer pending")
        val room = repository.getRoom(command.roomId)
            ?: throw CoopProtocolException("Co-op room is unavailable")
        requireAuthoritativeConnection(room)
        require(command.request.payerParticipantId == room.localParticipantId) {
            "Only the assigned payer can approve this generation"
        }
        val lifecycle = repository.getGenerationLifecycle(room.roomId)
            ?: throw CoopProtocolException("Generation approval is no longer active")
        when (lifecycle.stage) {
            CoopGenerationStage.PENDING_APPROVAL -> proposeOrOrder(
                room,
                CoopEventType.GENERATION_APPROVED,
                repository.codec.approvalToJson(CoopGenerationApprovalPayload(requestId)),
                clientEventId = "approval:$requestId"
            )
            CoopGenerationStage.APPROVED -> proposeOrOrder(
                room,
                CoopEventType.GENERATION_EXECUTING,
                repository.codec.executionToJson(
                    CoopGenerationExecutionPayload(requestId, executionId = requestId)
                ),
                clientEventId = "execution:$requestId"
            )
            else -> throw CoopProtocolException(
                "Generation execution already started; paid work is never dispatched twice"
            )
        }
    }

    suspend fun declinePendingGeneration(requestId: String) {
        val command = mutableState.value.pendingPayerRequest
            ?.takeIf { it.request.requestId == requestId }
            ?: throw CoopProtocolException("Generation approval is no longer pending")
        finishGeneration(command.chatId, requestId, messageId = null, error = CoopGenerationResultCode.PAYER_DECLINED)
    }

    suspend fun load(chatId: String) = sessionStartMutex.withLock {
        val visibleRoom = mutableState.value.room
        if (visibleRoom != null && visibleRoom.chatId != chatId && transport != null) {
            disconnectLocked()
        }
        val room = repository.getRoomForChat(chatId)
        if (room == null) {
            mutableState.value = CoopSessionState()
        } else {
            if (transport == null) {
                repository.reconcileDisconnectedParticipants(room.roomId)
                repository.updateConnectionState(
                    room.roomId,
                    if (room.localRole == CoopRole.HOST) CoopConnectionState.DISCONNECTED
                    else CoopConnectionState.SUSPENDED_HOST_OFFLINE
                )
            }
            refresh(room.roomId)
            reconcileActiveGeneration(room.roomId)
        }
    }

    suspend fun host(
        chatId: String,
        participantDisplayName: String,
        personaId: String,
        personaDisplayName: String,
        port: Int = CoopProtocol.DEFAULT_PORT,
        hostAddress: String? = null
    ): CoopHostInvite = withSingleFlightSessionStart {
        requireNetworkingEnabled()
        stopExistingTransportForSessionStart()
        val bindAddress = hostAddress?.trim()?.takeIf(String::isNotBlank)
            ?: localLanAddresses().firstOrNull()
            ?: throw CoopProtocolException("No private Wi-Fi/LAN IPv4 address is active on this device")
        CoopLanAddressPolicy.requirePrivateIpv4(bindAddress)
        val roomId = UUID.randomUUID().toString()
        val identity = roomIdentityStore?.getOrCreate(roomId)
            ?: throw CoopProtocolException("Secure room identity storage is unavailable")
        val invite = repository.createHostRoom(
            chatId = chatId,
            deviceId = identity.fingerprint,
            participantDisplayName = participantDisplayName,
            personaId = personaId,
            personaDisplayName = personaDisplayName,
            port = port,
            hostAddress = bindAddress,
            roomId = roomId,
            hostPublicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(identity.publicKeyEncoded),
            hostKeyFingerprint = identity.fingerprint
        )
        val next = transportFactory(repository.codec)
        attachTransport(next)
        try {
            next.startHost(
                bindAddress,
                port,
                CoopHostTransportSecurity(
                    invite.room.roomId,
                    invite.room.hostParticipantId,
                    invite.pairingCode,
                    identity
                )
            )
            repository.updateConnectionState(invite.room.roomId, CoopConnectionState.CONNECTED)
            refresh(invite.room.roomId, pairingCode = invite.pairingCode)
            invite
        } catch (error: Throwable) {
            cleanupFailedSessionStart(
                invite.room.roomId,
                CoopConnectionState.DISCONNECTED,
                pairingCode = null,
                error
            )
            throw error
        }
    }

    suspend fun resumeHosting(roomId: String): String {
        return withSingleFlightSessionStart {
            requireNetworkingEnabled()
            stopExistingTransportForSessionStart()
            val room = repository.getRoom(roomId) ?: throw CoopProtocolException("Unknown co-op room")
            require(room.localRole == CoopRole.HOST) { "Only the host can resume hosting" }
            val bindAddress = room.hostAddress
                ?: throw CoopProtocolException("This room has no private LAN bind address; end it and host again")
            CoopLanAddressPolicy.requirePrivateIpv4(bindAddress)
            val identity = roomIdentityStore?.get(roomId)
                ?: throw CoopProtocolException("This device no longer has the room host identity")
            require(identity.fingerprint == room.hostKeyFingerprint) { "Stored room host identity changed" }
            repository.getParticipant(room.localParticipantId)?.let { localHost ->
                repository.upsertParticipant(
                    localHost.copy(
                        deviceId = identity.fingerprint,
                        identityPublicKey = Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(identity.publicKeyEncoded)
                    )
                )
            }
            repository.reconcileDisconnectedParticipants(roomId)
            val code = repository.rotatePairingCode(roomId)
            val next = transportFactory(repository.codec)
            attachTransport(next)
            try {
                next.startHost(
                    bindAddress,
                    room.hostPort,
                    CoopHostTransportSecurity(room.roomId, room.hostParticipantId, code, identity)
                )
                repository.setParticipantConnected(room.localParticipantId, true)
                repository.updateConnectionState(roomId, CoopConnectionState.CONNECTED)
                refresh(roomId, pairingCode = code)
                code
            } catch (error: Throwable) {
                cleanupFailedSessionStart(
                    roomId,
                    CoopConnectionState.DISCONNECTED,
                    pairingCode = null,
                    error
                )
                throw error
            }
        }
    }

    suspend fun join(
        chatId: String,
        roomId: String,
        hostParticipantId: String,
        hostAddress: String,
        port: Int,
        pairingCode: String,
        hostPublicKey: String = "",
        hostKeyFingerprint: String = "",
        participantId: String? = null,
        participantDisplayName: String,
        personaId: String,
        personaDisplayName: String
    ) = withSingleFlightSessionStart<Unit> {
        requireNetworkingEnabled()
        require(pairingCode.length in 22..128) { "Invite secret is invalid" }
        require(hostPublicKey.isNotBlank() && hostKeyFingerprint.isNotBlank()) {
            "A pinned host identity is required"
        }
        stopExistingTransportForSessionStart()
        val existingRoom = repository.getRoomForChat(chatId)?.takeIf { it.roomId == roomId }
        val resolvedParticipantId = existingRoom?.localParticipantId
            ?: participantId
            ?: UUID.randomUUID().toString()
        val identityStore = participantIdentityStore
            ?: throw CoopProtocolException("Secure participant identity storage is unavailable")
        val participantIdentity = if (existingRoom != null) {
            identityStore.get(roomId, resolvedParticipantId)
                ?: throw CoopProtocolException(
                    "This device no longer has the participant reconnect identity. Join as a new room member instead."
                )
        } else {
            identityStore.getOrCreate(roomId, resolvedParticipantId)
        }
        val room = repository.createOrUpdateClientRoom(
            chatId, roomId, hostParticipantId, resolvedParticipantId, participantIdentity.fingerprint,
            participantDisplayName, personaId, personaDisplayName, hostAddress, port,
            hostPublicKey, hostKeyFingerprint,
            identityPublicKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(participantIdentity.publicKeyEncoded)
        )
        val next = transportFactory(repository.codec)
        attachTransport(next)
        val hello = CoopHelloPayload(
            participantId = resolvedParticipantId,
            deviceId = participantIdentity.fingerprint,
            participantDisplayName = participantDisplayName,
            personaId = personaId,
            personaDisplayName = personaDisplayName,
            lastAppliedSequence = room.lastAppliedSequence
        )
        try {
            next.connect(
                hostAddress,
                port,
                CoopWireEnvelope(
                    kind = CoopWireKind.HELLO,
                    roomId = roomId,
                    senderParticipantId = resolvedParticipantId,
                    clientMessageId = LanCoopTransport.newClientMessageId(),
                    payloadJson = repository.codec.helloToJson(hello)
                ),
                CoopClientTransportSecurity(
                    roomId = roomId,
                    participantId = resolvedParticipantId,
                    hostParticipantId = hostParticipantId,
                    inviteSecret = pairingCode,
                    pinnedHostPublicKey = Base64.getUrlDecoder().decode(hostPublicKey),
                    participantIdentity = participantIdentity
                )
            )
            refresh(roomId, status = "Connected; waiting for host approval.")
        } catch (error: Throwable) {
            cleanupFailedSessionStart(
                roomId,
                CoopConnectionState.SUSPENDED_HOST_OFFLINE,
                pairingCode = null,
                error
            )
            throw error
        }
    }

    suspend fun publishMessage(message: MessageEntity, generationExecutionId: String? = null) {
        val room = activeRoomForChat(message.chatId) ?: return
        requireAuthoritativeConnection(room)
        val local = repository.getParticipant(room.localParticipantId)
            ?: throw CoopProtocolException("Local participant is missing")
        val payload = CoopMessagePayload.from(
            message.copy(
                responsibleParticipantId = local.participantId,
                responsibleDeviceId = local.deviceId
            ),
            generationExecutionId
        )
        proposeOrOrder(
            room,
            CoopEventType.MESSAGE_UPSERT,
            repository.codec.messageToJson(payload),
            clientEventId = generationExecutionId?.let(CoopProtocol::generationResultClientEventId)
                ?: LanCoopTransport.newClientMessageId()
        )
    }

    suspend fun publishMessageDelete(chatId: String, messageId: String): Boolean {
        val room = activeRoomForChat(chatId) ?: return false
        requireAuthoritativeConnection(room)
        proposeOrOrder(
            room,
            CoopEventType.MESSAGE_DELETE,
            repository.codec.deleteToJson(CoopMessageDeletePayload(messageId))
        )
        return true
    }

    suspend fun publishCharacterRoster(chatId: String, roster: CoopCharacterRosterPayload) {
        val room = activeRoomForChat(chatId) ?: return
        require(room.localRole == CoopRole.HOST) { "Only the host can change the co-op Character roster" }
        proposeOrOrder(room, CoopEventType.CHARACTER_ROSTER_SET, repository.codec.rosterToJson(roster))
    }

    suspend fun setPayerPolicy(chatId: String, mode: String, selectedParticipantId: String?) {
        require(mode in CoopPayerMode.all) { "Unsupported payer mode" }
        if (mode == CoopPayerMode.SELECTED_PARTICIPANT) requireNotNull(selectedParticipantId)
        val room = activeRoomForChat(chatId) ?: throw CoopProtocolException("This chat is not a co-op room")
        require(room.localRole == CoopRole.HOST) { "Only the host can change the payer policy" }
        proposeOrOrder(
            room,
            CoopEventType.PAYER_POLICY_SET,
            repository.codec.payerToJson(CoopPayerPolicyPayload(mode, selectedParticipantId))
        )
    }

    suspend fun requestGeneration(
        chatId: String,
        characterId: String,
        operation: String = "NORMAL",
        targetMessageId: String? = null
    ): Boolean {
        val room = activeRoomForChat(chatId) ?: return false
        requireAuthoritativeConnection(room)
        val request = CoopGenerationRequestPayload(
            requestId = UUID.randomUUID().toString(),
            initiatorParticipantId = room.localParticipantId,
            payerParticipantId = "HOST_ASSIGNS",
            characterId = characterId,
            operation = operation,
            targetMessageId = targetMessageId,
            requestedAt = System.currentTimeMillis()
        )
        proposeOrOrder(
            room,
            CoopEventType.GENERATION_REQUESTED,
            repository.codec.generationToJson(request),
            clientEventId = request.requestId
        )
        return true
    }

    suspend fun finishGeneration(
        chatId: String,
        requestId: String,
        messageId: String?,
        error: String? = null,
        metadata: CoopGenerationMetadataPayload? = null
    ) {
        val room = activeRoomForChat(chatId) ?: return
        val type = if (error == null) CoopEventType.GENERATION_FINISHED else CoopEventType.GENERATION_FAILED
        val completion = CoopGenerationCompletionPayload(
            requestId = requestId,
            executionId = requestId,
            messageId = messageId,
            errorMessage = error?.let(::publicGenerationError),
            metadata = metadata
        )
        val lifecycle = repository.getGenerationLifecycle(room.roomId)
            ?.takeIf { it.request.requestId == requestId }
        if (lifecycle == null) {
            val existing = repository.getEventByClientId(room.roomId, "completion:$requestId")
                ?: throw CoopProtocolException("Generation request is no longer active")
            val canonicalPayload = repository.codec.completionToJson(completion)
            require(
                existing.eventType == type &&
                    existing.payloadJson == canonicalPayload
            ) { "A completed generation cannot be acknowledged with different content" }
            return
        }
        proposeOrOrder(
            room,
            type,
            repository.codec.completionToJson(
                completion.copy(executionId = lifecycle.executionId ?: requestId)
            ),
            clientEventId = "completion:$requestId"
        )
    }

    suspend fun disconnect() = sessionStartMutex.withLock {
        disconnectLocked()
    }

    private suspend fun disconnectLocked() {
        val room = mutableState.value.room
        stopTransportOnly()
        if (room != null) {
            val state = if (room.localRole == CoopRole.HOST) {
                CoopConnectionState.DISCONNECTED
            } else {
                CoopConnectionState.SUSPENDED_HOST_OFFLINE
            }
            repository.updateConnectionState(room.roomId, state)
            repository.setParticipantConnected(room.localParticipantId, false)
            refresh(room.roomId, pairingCode = null, status = "Disconnected")
        }
    }

    suspend fun stopForBackground() = sessionStartMutex.withLock {
        if (transport != null) disconnectLocked()
    }

    /**
     * Stops process-local work before Room and its related files are replaced.
     * This deliberately does not persist a disconnect transition into the data set
     * that is about to be replaced.
     */
    suspend fun stopForDataReplacement() = sessionStartMutex.withLock {
        stopTransportOnly()
        proposalWindows.clear()
        controlWindows.clear()
        resyncContinuations.clear()
        synchronized(outstandingResyncAfter) { outstandingResyncAfter.clear() }
        claimedGenerationExecutions.clear()
        mutableState.value = CoopSessionState(
            statusMessage = "Local co-op stopped because app data was replaced."
        )
    }

    /** Ends local co-op configuration while retaining every chat message and local private record. */
    suspend fun leaveRoom(chatId: String) = sessionStartMutex.withLock {
        val room = repository.getRoomForChat(chatId)
            ?: run {
                return@withLock
            }
        val isVisibleRoom = mutableState.value.room?.roomId == room.roomId
        if (isVisibleRoom) stopTransportOnly()
        repository.deleteRoomKeepingChat(room.roomId)
        roomIdentityStore?.delete(room.roomId)
        participantIdentityStore?.delete(room.roomId, room.localParticipantId)
        if (isVisibleRoom) {
            mutableState.value = CoopSessionState(statusMessage = "Local co-op ended; chat history was retained.")
        }
    }

    private suspend fun proposeOrOrder(
        room: CoopRoomEntity,
        eventType: String,
        payloadJson: String,
        clientEventId: String = LanCoopTransport.newClientMessageId()
    ) {
        if (room.localRole == CoopRole.HOST) {
            val sanitizedPayload = sanitizeHostPayload(room, room.localParticipantId, eventType, payloadJson)
            val append = repository.appendHostEventResult(
                room.roomId, clientEventId, room.localParticipantId, eventType, sanitizedPayload
            )
            broadcastEvent(room, append.event)
            if (append.inserted) emitGenerationIfLocal(room, append.event)
            refresh(room.roomId)
        } else {
            val identity = participantIdentityStore?.get(room.roomId, room.localParticipantId)
                ?: throw CoopProtocolException("This device no longer has the participant membership identity")
            val signature = CoopSessionCrypto.signParticipantEvent(
                identity.keyPair,
                room.roomId,
                clientEventId,
                room.localParticipantId,
                eventType,
                payloadJson
            )
            val result = CompletableDeferred<Unit>()
            require(pendingProposalResults.putIfAbsent(clientEventId, result) == null) {
                "A room action with this event ID is already pending"
            }
            try {
                transport?.send(
                    CoopWireEnvelope(
                        kind = CoopWireKind.PROPOSE_EVENT,
                        roomId = room.roomId,
                        senderParticipantId = room.localParticipantId,
                        clientMessageId = clientEventId,
                        payloadJson = repository.codec.proposedToJson(
                            CoopProposedEventPayload(eventType, payloadJson, signature)
                        )
                    )
                ) ?: throw CoopProtocolException("Host is not connected")
                withTimeout(PROPOSAL_ACK_TIMEOUT_MS) { result.await() }
            } finally {
                pendingProposalResults.remove(clientEventId, result)
            }
        }
    }

    private suspend fun handleTransportEvent(event: CoopTransportEvent) {
        when (event) {
            is CoopTransportEvent.Listening -> mutableState.value = mutableState.value.copy(
                suggestedAddresses = event.suggestedAddresses,
                statusMessage = "Hosting on port ${event.port}",
                errorMessage = null
            )
            CoopTransportEvent.ConnectedToHost -> Unit
            is CoopTransportEvent.Envelope -> handleEnvelope(
                event.value,
                event.authenticatedParticipant
            )
            is CoopTransportEvent.PeerDisconnected -> {
                synchronized(proposalWindows) { proposalWindows.remove(event.participantId) }
                synchronized(resyncContinuations) { resyncContinuations.remove(event.participantId) }
                mutableState.value.room?.let { room ->
                    val participant = repository.getParticipant(event.participantId)
                    if (room.localRole == CoopRole.HOST && participant?.isConnected == true) {
                        val disconnected = participant.copy(
                            isConnected = false,
                            lastSeenAt = System.currentTimeMillis()
                        )
                        val ordered = repository.appendHostEvent(
                            room.roomId,
                            "disconnect:${participant.participantId}:${disconnected.lastSeenAt}",
                            room.localParticipantId,
                            CoopEventType.PARTICIPANT_LEFT,
                            repository.codec.participantToJson(CoopParticipantDto.from(disconnected))
                        )
                        broadcastEvent(repository.getRoom(room.roomId) ?: room, ordered)
                        failGenerationIfAssignedPayerDisconnected(room, participant.participantId)
                    } else {
                        repository.setParticipantConnected(event.participantId, false)
                    }
                    refresh(room.roomId, status = "${participant?.displayName ?: "A participant"} disconnected.")
                }
            }
            CoopTransportEvent.HostDisconnected -> {
                mutableState.value.room?.let { room ->
                    synchronized(outstandingResyncAfter) { outstandingResyncAfter.remove(room.roomId) }
                    repository.updateConnectionState(room.roomId, CoopConnectionState.SUSPENDED_HOST_OFFLINE)
                    mutableState.value = mutableState.value.copy(pendingPayerRequest = null)
                    refresh(room.roomId, status = "Host offline. Sending and generation are suspended until reconnect.")
                }
            }
            is CoopTransportEvent.Failure -> mutableState.value = mutableState.value.copy(errorMessage = event.message)
        }
    }

    private suspend fun handleEnvelope(
        envelope: CoopWireEnvelope,
        authenticatedParticipant: CoopAuthenticatedParticipant?
    ) {
        if (envelope.protocolVersion != CoopProtocol.VERSION) return
        val room = repository.getRoom(envelope.roomId) ?: return
        if (room.localRole == CoopRole.HOST) {
            require(authenticatedParticipant?.participantId == envelope.senderParticipantId) {
                "Host received a frame without authenticated participant binding"
            }
        }
        val allowedKinds = if (room.localRole == CoopRole.HOST) HOST_INBOUND_KINDS else CLIENT_INBOUND_KINDS
        require(envelope.kind in allowedKinds) { "Wire message kind is not allowed for this room role" }
        if (room.localRole == CoopRole.CLIENT && room.connectionState != CoopConnectionState.CONNECTED) {
            require(envelope.kind == CoopWireKind.JOIN_ACCEPTED || envelope.kind == CoopWireKind.JOIN_REJECTED) {
                "Host sent room data before join acceptance"
            }
        }
        if (envelope.kind == CoopWireKind.PING || envelope.kind == CoopWireKind.PONG) {
            val payloadIsValid = envelope.payloadJson.isEmpty()
            val withinRateLimit = consumeControlAllowance(
                    envelope.senderParticipantId,
                    envelope.kind,
                    MAX_CONTROL_FRAMES_PER_WINDOW
                )
            if (!payloadIsValid || !withinRateLimit) {
                if (room.localRole == CoopRole.HOST) {
                    transport?.disconnectPeer(envelope.senderParticipantId)
                } else {
                    transport?.stop()
                    repository.updateConnectionState(room.roomId, CoopConnectionState.SUSPENDED_HOST_OFFLINE)
                    refresh(room.roomId, error = "Host sent an invalid or excessive control frame")
                }
                return
            }
        }
        when (envelope.kind) {
            CoopWireKind.HELLO -> if (room.localRole == CoopRole.HOST) {
                handleHello(room, envelope, requireNotNull(authenticatedParticipant))
            }
            CoopWireKind.JOIN_ACCEPTED -> if (room.localRole == CoopRole.CLIENT) {
                requireSnapshotPayloadBound(envelope.payloadJson)
                val snapshot = repository.codec.snapshotFromJson(envelope.payloadJson)
                repository.applySnapshot(snapshot)
                repository.setParticipantConnected(room.localParticipantId, true)
                repository.updateConnectionState(room.roomId, CoopConnectionState.CONNECTED)
                refresh(room.roomId, status = "Joined room.")
                if (snapshot.hasMoreEvents) requestResync(room, snapshot.events.lastOrNull()?.sequence ?: room.lastAppliedSequence)
            }
            CoopWireKind.JOIN_REJECTED -> if (room.localRole == CoopRole.CLIENT) {
                repository.updateConnectionState(room.roomId, CoopConnectionState.SUSPENDED_HOST_OFFLINE)
                refresh(room.roomId, error = envelope.payloadJson.ifBlank { "Host rejected the join request." })
            }
            CoopWireKind.PROPOSE_EVENT -> if (room.localRole == CoopRole.HOST) {
                handleProposal(room, envelope, requireNotNull(authenticatedParticipant))
            }
            CoopWireKind.PROPOSAL_REJECTED -> if (room.localRole == CoopRole.CLIENT) {
                val rejection = repository.codec.proposalRejectionFromJson(envelope.payloadJson)
                pendingProposalResults.remove(rejection.clientEventId)?.completeExceptionally(
                    CoopProtocolException("The host rejected this room action: ${rejection.code}")
                )
                mutableState.value = mutableState.value.copy(
                    errorMessage = when (rejection.code) {
                        "CONTEXT_UNAVAILABLE" -> "Host could not authorize the generation context. Keep the Group chat open on the host."
                        "RATE_LIMIT" -> "Room event rate limit reached. Wait before trying again."
                        "UNAUTHORIZED" -> "The host rejected an unauthorized room action."
                        else -> "The host rejected an invalid room action."
                    }
                )
            }
            CoopWireKind.ORDERED_EVENT -> {
                val ordered = repository.codec.eventFromJson(envelope.payloadJson).toEntity()
                try {
                    if (repository.applyOrderedEvent(ordered)) emitGenerationIfLocal(room, ordered)
                    pendingProposalResults.remove(ordered.clientEventId)?.complete(Unit)
                    refresh(room.roomId)
                } catch (gap: CoopSequenceGapException) {
                    requestResync(room, gap.expected - 1)
                } catch (error: Exception) {
                    pendingProposalResults.remove(ordered.clientEventId)?.completeExceptionally(error)
                    throw error
                }
            }
            CoopWireKind.RESYNC_REQUEST -> if (room.localRole == CoopRole.HOST) {
                val participant = repository.getParticipant(envelope.senderParticipantId)
                require(participant?.roomId == room.roomId && participant.isConnected) {
                    "Only an accepted room participant can request resync"
                }
                val after = repository.codec.resyncFromJson(envelope.payloadJson).afterSequence
                require(after in 0..room.lastAppliedSequence) { "Invalid resync sequence" }
                val expectedContinuation = synchronized(resyncContinuations) {
                    resyncContinuations[participant.participantId]
                }
                val allowed = if (expectedContinuation != null) {
                    after == expectedContinuation
                } else {
                    consumeControlAllowance(
                        participant.participantId,
                        CoopWireKind.RESYNC_REQUEST,
                        MAX_RESYNC_CHAINS_PER_WINDOW
                    )
                }
                if (!allowed) {
                    transport?.disconnectPeer(participant.participantId)
                    throw CoopProtocolException("Participant sent a repeated or excessive resync request")
                }
                sendSnapshot(room, envelope.senderParticipantId, after)
            }
            CoopWireKind.SNAPSHOT -> if (room.localRole == CoopRole.CLIENT) {
                requireSnapshotPayloadBound(envelope.payloadJson)
                val snapshot = repository.codec.snapshotFromJson(envelope.payloadJson)
                synchronized(outstandingResyncAfter) { outstandingResyncAfter.remove(room.roomId) }
                repository.applySnapshot(snapshot)
                snapshot.events.forEach { dto ->
                    pendingProposalResults.remove(dto.clientEventId)?.complete(Unit)
                }
                refresh(room.roomId, status = "Room state synchronized.")
                if (snapshot.hasMoreEvents) requestResync(room, snapshot.events.lastOrNull()?.sequence ?: room.lastAppliedSequence)
            }
            CoopWireKind.PING -> {
                transport?.send(
                    envelope.copy(
                        kind = CoopWireKind.PONG,
                        senderParticipantId = room.localParticipantId,
                        targetParticipantId = envelope.senderParticipantId,
                        clientMessageId = LanCoopTransport.newClientMessageId(),
                        payloadJson = ""
                    )
                )
            }
        }
    }

    private suspend fun handleHello(
        room: CoopRoomEntity,
        envelope: CoopWireEnvelope,
        authenticatedParticipant: CoopAuthenticatedParticipant
    ) {
        val hello = runCatching { repository.codec.helloFromJson(envelope.payloadJson) }.getOrNull()
        val rejection = when {
            hello == null -> "Malformed join request."
            hello.participantId != envelope.senderParticipantId -> "Participant identity mismatch."
            hello.participantId != authenticatedParticipant.participantId -> "Authenticated membership mismatch."
            hello.deviceId != authenticatedParticipant.keyFingerprint -> "Participant reconnect proof mismatch."
            hello.participantDisplayName.isBlank() ||
                hello.participantDisplayName.length > CoopProtocol.MAX_PARTICIPANT_NAME_CHARS -> "Invalid participant name."
            hello.personaId.isBlank() || hello.personaId.length > CoopProtocol.MAX_ID_CHARS ||
                hello.personaDisplayName.isBlank() ||
                hello.personaDisplayName.length > CoopProtocol.MAX_DISPLAY_NAME_CHARS -> "A valid Persona is required."
            hello.lastAppliedSequence !in 0..room.lastAppliedSequence -> "Invalid synchronization sequence."
            else -> null
        }
        if (rejection != null || hello == null) {
            transport?.send(
                CoopWireEnvelope(
                    kind = CoopWireKind.JOIN_REJECTED,
                    roomId = room.roomId,
                    senderParticipantId = room.localParticipantId,
                    targetParticipantId = envelope.senderParticipantId,
                    clientMessageId = LanCoopTransport.newClientMessageId(),
                    payloadJson = rejection ?: "Join rejected."
                )
            )
            transport?.rejectPeer(envelope.senderParticipantId)
            return
        }
        val participants = repository.getParticipants(room.roomId)
        val ownerships = repository.getOwnerships(room.roomId)
        val alreadyConnected = participants.any {
            it.participantId == hello.participantId && it.isConnected
        }
        val authenticatedPublicKey = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(authenticatedParticipant.publicKeyEncoded)
        val identityConflict = participants.any {
            (it.participantId == hello.participantId &&
                (it.deviceId != hello.deviceId ||
                    (it.identityPublicKey.isNotBlank() && it.identityPublicKey != authenticatedPublicKey))) ||
                (it.deviceId == hello.deviceId && it.participantId != hello.participantId)
        }
        val personaConflict = ownerships.any {
            it.personaId == hello.personaId && it.participantId != hello.participantId
        }
        val participantLimitReached = participants.none { it.participantId == hello.participantId } &&
            participants.size >= CoopProtocol.MAX_PARTICIPANTS
        val personaLimitReached = ownerships.none { it.personaId == hello.personaId } &&
            ownerships.size >= CoopProtocol.MAX_PERSONAS
        if (alreadyConnected || identityConflict || personaConflict ||
            participantLimitReached || personaLimitReached
        ) {
            transport?.send(
                CoopWireEnvelope(
                    kind = CoopWireKind.JOIN_REJECTED,
                    roomId = room.roomId,
                    senderParticipantId = room.localParticipantId,
                    targetParticipantId = hello.participantId,
                    clientMessageId = LanCoopTransport.newClientMessageId(),
                    payloadJson = when {
                        alreadyConnected -> "Participant is already connected."
                        identityConflict -> "Participant/device identity conflict."
                        participantLimitReached -> "Room participant limit reached."
                        personaLimitReached -> "Room Persona limit reached."
                        else -> "Persona is already owned in this room."
                    }
                )
            )
            transport?.rejectPeer(hello.participantId)
            return
        }
        val existingParticipant = participants.firstOrNull { it.participantId == hello.participantId }
        val participant = (existingParticipant ?: CoopParticipantEntity(
            participantId = hello.participantId,
            roomId = room.roomId,
            deviceId = authenticatedParticipant.keyFingerprint,
            identityPublicKey = authenticatedPublicKey,
            displayName = hello.participantDisplayName.trim(),
            isHost = false,
            isConnected = false,
            sortPosition = participants.size
        )).copy(
            deviceId = authenticatedParticipant.keyFingerprint,
            identityPublicKey = authenticatedPublicKey,
            displayName = hello.participantDisplayName.trim(),
            isConnected = false,
            lastSeenAt = System.currentTimeMillis()
        )
        val ownership = CoopPersonaOwnershipEntity(
            roomId = room.roomId,
            personaId = hello.personaId,
            participantId = hello.participantId,
            personaDisplayNameSnapshot = hello.personaDisplayName,
            sortPosition = participant.sortPosition
        )
        val pending = PendingJoinRecord(
            joinClientMessageId = envelope.clientMessageId,
            participant = participant,
            ownership = ownership,
            lastAppliedSequence = hello.lastAppliedSequence
        )
        require(pendingJoinRecords.putIfAbsent(hello.participantId, pending) == null) {
            "A join decision is already pending for this participant"
        }
        publishPendingJoins("Join request from ${participant.displayName}; host approval is required.")
        if (autoAcceptJoinsForTests) {
            acceptPendingJoin(hello.participantId)
        } else {
            scope.launch {
                delay(PENDING_JOIN_TIMEOUT_MS)
                if (pendingJoinRecords.remove(hello.participantId, pending)) {
                    runCatching { rejectPendingJoinInternal(room, pending, "Join request timed out.") }
                }
            }
        }
    }

    suspend fun acceptPendingJoin(participantId: String) {
        val pending = pendingJoinRecords.remove(participantId)
            ?: throw CoopProtocolException("This join request is no longer pending")
        val room = mutableState.value.room
            ?.takeIf { it.localRole == CoopRole.HOST }
            ?: throw CoopProtocolException("Only the active host can accept a join")
        val activeTransport = transport ?: throw CoopProtocolException("Co-op transport is unavailable")
        try {
            val snapshot = repository.buildSnapshot(
                room.roomId,
                pending.lastAppliedSequence,
                stagedParticipant = pending.participant,
                stagedOwnership = pending.ownership
            )
            val snapshotJson = repository.codec.snapshotToJson(snapshot)
            requireSnapshotPayloadBound(snapshotJson)
            val acceptedEnvelope = CoopWireEnvelope(
                kind = CoopWireKind.JOIN_ACCEPTED,
                roomId = room.roomId,
                senderParticipantId = room.localParticipantId,
                targetParticipantId = participantId,
                clientMessageId = LanCoopTransport.newClientMessageId(),
                payloadJson = snapshotJson
            )
            requireWireFrameFits(acceptedEnvelope)
            // Delivery succeeds before any durable membership rows/events are committed.
            activeTransport.acceptPeer(participantId, acceptedEnvelope)
            val connected = pending.participant.copy(
                isConnected = true,
                lastSeenAt = System.currentTimeMillis()
            )
            val membershipEvents = repository.commitAcceptedMembership(
                room.roomId,
                pending.joinClientMessageId,
                connected,
                pending.ownership
            )
            val latestRoom = repository.getRoom(room.roomId) ?: room
            rememberSnapshotContinuation(participantId, snapshot)
            membershipEvents.forEach { broadcastEvent(latestRoom, it) }
            refresh(room.roomId, status = "${connected.displayName} joined.")
        } catch (error: Exception) {
            runCatching { activeTransport.disconnectPeer(participantId) }
            runCatching { activeTransport.rejectPeer(participantId) }
            publishPendingJoins(error = error.message ?: "Join acceptance failed.")
            throw error
        }
    }

    suspend fun rejectPendingJoin(participantId: String) {
        val pending = pendingJoinRecords.remove(participantId)
            ?: throw CoopProtocolException("This join request is no longer pending")
        val room = mutableState.value.room
            ?.takeIf { it.localRole == CoopRole.HOST }
            ?: throw CoopProtocolException("Only the active host can reject a join")
        rejectPendingJoinInternal(room, pending, "Host rejected the join request.")
    }

    private suspend fun rejectPendingJoinInternal(
        room: CoopRoomEntity,
        pending: PendingJoinRecord,
        reason: String
    ) {
        runCatching {
            transport?.send(
                CoopWireEnvelope(
                    kind = CoopWireKind.JOIN_REJECTED,
                    roomId = room.roomId,
                    senderParticipantId = room.localParticipantId,
                    targetParticipantId = pending.participant.participantId,
                    clientMessageId = LanCoopTransport.newClientMessageId(),
                    payloadJson = reason
                )
            )
        }
        transport?.rejectPeer(pending.participant.participantId)
        publishPendingJoins("Join request rejected.")
    }

    private fun publishPendingJoins(status: String? = mutableState.value.statusMessage, error: String? = null) {
        mutableState.value = mutableState.value.copy(
            pendingJoins = pendingJoinRecords.values
                .sortedBy { it.requestedAt }
                .map {
                    CoopPendingJoin(
                        participantId = it.participant.participantId,
                        participantDisplayName = it.participant.displayName,
                        personaDisplayName = it.ownership.personaDisplayNameSnapshot,
                        membershipFingerprint = it.participant.deviceId,
                        requestedAt = it.requestedAt
                    )
                },
            statusMessage = status,
            errorMessage = error
        )
    }

    private suspend fun handleProposal(
        room: CoopRoomEntity,
        envelope: CoopWireEnvelope,
        authenticatedParticipant: CoopAuthenticatedParticipant
    ) {
        try {
            val participant = repository.getParticipant(envelope.senderParticipantId)
                ?: throw CoopProtocolException("Unknown participant")
            require(participant.isConnected && participant.roomId == room.roomId) {
                "Participant is not active in this room"
            }
            if (!consumeProposalAllowance(participant.participantId)) {
                sendProposalRejection(room, envelope, "RATE_LIMIT")
                transport?.disconnectPeer(participant.participantId)
                return
            }
            val proposed = repository.codec.proposedFromJson(envelope.payloadJson)
            require(proposed.eventType in CLIENT_EVENT_TYPES) { "Client event type is not allowed" }
            require(proposed.originSignature.isNotBlank() &&
                proposed.originSignature.length <= CoopProtocol.MAX_SIGNATURE_CHARS &&
                CoopSessionCrypto.verifyParticipantEvent(
                    authenticatedParticipant.publicKeyEncoded,
                    proposed.originSignature,
                    room.roomId,
                    envelope.clientMessageId,
                    participant.participantId,
                    proposed.eventType,
                    proposed.payloadJson
                )) { "Participant event signature is invalid" }
            val sanitized = sanitizeHostPayload(room, participant.participantId, proposed.eventType, proposed.payloadJson)
            val append = repository.appendHostEventResult(
                room.roomId,
                envelope.clientMessageId,
                participant.participantId,
                proposed.eventType,
                sanitized,
                originPayloadJson = proposed.payloadJson,
                originSignature = proposed.originSignature
            )
            broadcastEvent(repository.getRoom(room.roomId) ?: room, append.event)
            if (append.inserted) emitGenerationIfLocal(room, append.event)
            refresh(room.roomId)
        } catch (error: Exception) {
            val code = if (error.message?.contains("context", ignoreCase = true) == true) {
                "CONTEXT_UNAVAILABLE"
            } else if (error.message?.contains("own", ignoreCase = true) == true ||
                error.message?.contains("assigned payer", ignoreCase = true) == true
            ) {
                "UNAUTHORIZED"
            } else {
                "INVALID_EVENT"
            }
            sendProposalRejection(room, envelope, code)
            if (repository.getParticipant(envelope.senderParticipantId) == null) {
                transport?.disconnectPeer(envelope.senderParticipantId)
            }
        }
    }

    private suspend fun sendProposalRejection(
        room: CoopRoomEntity,
        proposal: CoopWireEnvelope,
        code: String
    ) {
        transport?.send(
            CoopWireEnvelope(
                kind = CoopWireKind.PROPOSAL_REJECTED,
                roomId = room.roomId,
                senderParticipantId = room.localParticipantId,
                targetParticipantId = proposal.senderParticipantId,
                clientMessageId = LanCoopTransport.newClientMessageId(),
                payloadJson = repository.codec.proposalRejectionToJson(
                    CoopProposalRejectionPayload(proposal.clientMessageId, code)
                )
            )
        )
    }

    private suspend fun sanitizeHostPayload(
        room: CoopRoomEntity,
        senderParticipantId: String,
        eventType: String,
        payloadJson: String
    ): String = when (eventType) {
        CoopEventType.MESSAGE_UPSERT -> {
            val participant = repository.getParticipant(senderParticipantId)
                ?: throw CoopProtocolException("Unknown participant")
            val payload = repository.codec.messageFromJson(payloadJson)
            val ownedPersonas = repository.getOwnerships(room.roomId)
                .filter { it.participantId == senderParticipantId && it.isActive }
                .map { it.personaId }
            if (payload.speakerType == "PERSONA" && payload.speakerId !in ownedPersonas) {
                throw CoopProtocolException("Participant does not own that Persona")
            }
            if (payload.speakerType == "CHARACTER") {
                val existing = repository.getMessage(payload.messageId)
                if (existing == null) {
                    val active = repository.getActiveGenerationRequest(room.roomId)
                        ?: throw CoopProtocolException("Character output requires an active generation request")
                    if (active.payerParticipantId != senderParticipantId || active.characterId != payload.speakerId) {
                        throw CoopProtocolException("Character output must come from the assigned payer for the requested Character")
                    }
                } else {
                    val active = repository.getActiveGenerationRequest(room.roomId)
                    val authorizedContinue = active?.operation == "CONTINUE" &&
                        active.targetMessageId == existing.messageId &&
                        active.characterId == existing.speakerId &&
                        active.payerParticipantId == senderParticipantId
                    if (senderParticipantId != room.hostParticipantId &&
                        existing.responsibleParticipantId != senderParticipantId &&
                        !authorizedContinue
                    ) {
                        throw CoopProtocolException("Only the responsible payer, approved Continue payer, or host can edit Character output")
                    }
                }
            }
            repository.codec.messageToJson(
                payload.copy(
                    chatId = room.chatId,
                    responsibleParticipantId = senderParticipantId,
                    responsibleDeviceId = participant.deviceId
                )
            )
        }
        CoopEventType.MESSAGE_DELETE -> {
            val payload = repository.codec.deleteFromJson(payloadJson)
            val message = repository.getMessage(payload.messageId)
                ?: throw CoopProtocolException("Message not found")
            if (senderParticipantId != room.hostParticipantId && message.responsibleParticipantId != senderParticipantId) {
                throw CoopProtocolException("Only the responsible participant or host can delete this message")
            }
            payloadJson
        }
        CoopEventType.GENERATION_REQUESTED -> {
            val requested = repository.codec.generationFromJson(payloadJson)
            val payer = repository.resolvePayer(room.roomId, senderParticipantId)
            val authoritative = requested.copy(
                initiatorParticipantId = senderParticipantId,
                payerParticipantId = payer,
                requestedAt = System.currentTimeMillis(),
                contextFingerprint = null
            )
            val authorization = generationContextAuthorizers[room.chatId]?.authorizer?.invoke(authoritative)
                ?: throw CoopProtocolException(
                    "The host must have this Group chat open to authorize generation context."
                )
            repository.codec.generationToJson(
                authoritative.copy(
                    contextFingerprint = authorization.contextFingerprint,
                    modelId = authorization.modelId,
                    providerRoutingMode = authorization.providerRoutingMode,
                    providerEndpoint = authorization.providerEndpoint
                )
            )
        }
        CoopEventType.GENERATION_APPROVED -> {
            val approval = repository.codec.approvalFromJson(payloadJson)
            val lifecycle = repository.getGenerationLifecycle(room.roomId)
                ?: throw CoopProtocolException("Generation request is missing")
            require(approval.requestId == lifecycle.request.requestId &&
                lifecycle.request.payerParticipantId == senderParticipantId) {
                "Only the assigned payer can approve this generation"
            }
            repository.codec.approvalToJson(CoopGenerationApprovalPayload(approval.requestId))
        }
        CoopEventType.GENERATION_EXECUTING -> {
            val execution = repository.codec.executionFromJson(payloadJson)
            val lifecycle = repository.getGenerationLifecycle(room.roomId)
                ?: throw CoopProtocolException("Generation request is missing")
            require(execution.requestId == lifecycle.request.requestId &&
                execution.executionId == execution.requestId &&
                lifecycle.request.payerParticipantId == senderParticipantId) {
                "Only the assigned payer can begin this generation"
            }
            repository.codec.executionToJson(
                CoopGenerationExecutionPayload(execution.requestId, execution.requestId)
            )
        }
        CoopEventType.GENERATION_FINISHED, CoopEventType.GENERATION_FAILED -> {
            val completion = repository.codec.completionFromJson(payloadJson)
            val activeRoom = repository.getRoom(room.roomId) ?: room
            require(activeRoom.activeGenerationRequestId == completion.requestId) { "Generation request is stale" }
            val active = repository.getActiveGenerationRequest(room.roomId)
                ?: throw CoopProtocolException("Generation request is missing")
            require(active.payerParticipantId == senderParticipantId) {
                "Only the assigned payer can complete this generation"
            }
            payloadJson
        }
        else -> payloadJson
    }

    private suspend fun broadcastEvent(room: CoopRoomEntity, event: CoopEventEntity) {
        transport?.send(
            CoopWireEnvelope(
                kind = CoopWireKind.ORDERED_EVENT,
                roomId = room.roomId,
                senderParticipantId = room.localParticipantId,
                clientMessageId = event.clientEventId,
                payloadJson = repository.codec.eventToJson(CoopEventDto.from(event))
            )
        )
    }

    private suspend fun emitGenerationIfLocal(room: CoopRoomEntity, event: CoopEventEntity) {
        val current = repository.getRoom(room.roomId) ?: room
        when (event.eventType) {
            CoopEventType.GENERATION_REQUESTED -> {
                val request = repository.codec.generationFromJson(event.payloadJson)
                if (request.payerParticipantId == current.localParticipantId) {
                    mutableState.value = mutableState.value.copy(
                        pendingPayerRequest = CoopGenerationCommand(
                            current.chatId,
                            current.roomId,
                            request,
                            executionId = request.requestId
                        ),
                        payerRequestStage = CoopGenerationStage.PENDING_APPROVAL,
                        statusMessage = "Generation requires approval on this payer device.",
                        errorMessage = null
                    )
                }
            }
            CoopEventType.GENERATION_APPROVED -> {
                val approval = repository.codec.approvalFromJson(event.payloadJson)
                val lifecycle = repository.getGenerationLifecycle(current.roomId)
                if (lifecycle?.request?.requestId == approval.requestId &&
                    lifecycle.request.payerParticipantId == current.localParticipantId
                ) {
                    mutableState.value = mutableState.value.copy(
                        pendingPayerRequest = CoopGenerationCommand(
                            current.chatId,
                            current.roomId,
                            lifecycle.request,
                            executionId = approval.requestId
                        ),
                        payerRequestStage = CoopGenerationStage.APPROVED,
                        statusMessage = "Approval recorded by the host; starting the one approved execution.",
                        errorMessage = null
                    )
                    // Do not wait for the next ordered acknowledgement from inside the event
                    // collector that must process it. A separate coroutine retains the
                    // host-authoritative acknowledgement boundary without deadlocking.
                    scope.launch {
                        runCatching {
                            proposeOrOrder(
                                current,
                                CoopEventType.GENERATION_EXECUTING,
                                repository.codec.executionToJson(
                                    CoopGenerationExecutionPayload(approval.requestId, approval.requestId)
                                ),
                                clientEventId = "execution:${approval.requestId}"
                            )
                        }.onFailure { error ->
                            mutableState.value = mutableState.value.copy(
                                statusMessage = "Approval is saved. Reconnect and choose Start approved generation.",
                                errorMessage = error.message
                            )
                        }
                    }
                }
            }
            CoopEventType.GENERATION_EXECUTING -> {
                val execution = repository.codec.executionFromJson(event.payloadJson)
                val lifecycle = repository.getGenerationLifecycle(current.roomId)
                if (lifecycle?.request?.requestId == execution.requestId &&
                    lifecycle.request.payerParticipantId == current.localParticipantId
                ) {
                    mutableState.value = mutableState.value.copy(
                        pendingPayerRequest = null,
                        payerRequestStage = CoopGenerationStage.EXECUTING,
                        statusMessage = "Host acknowledged the approved execution.",
                        errorMessage = null
                    )
                    mutableGenerationCommands.emit(
                        CoopGenerationCommand(
                            current.chatId,
                            current.roomId,
                            lifecycle.request,
                            execution.executionId
                        )
                    )
                }
            }
            CoopEventType.GENERATION_FINISHED, CoopEventType.GENERATION_FAILED -> {
                val completion = repository.codec.completionFromJson(event.payloadJson)
                if (mutableState.value.pendingPayerRequest?.request?.requestId == completion.requestId) {
                    mutableState.value = mutableState.value.copy(
                        pendingPayerRequest = null,
                        payerRequestStage = null
                    )
                } else if (mutableState.value.payerRequestStage == CoopGenerationStage.EXECUTING) {
                    mutableState.value = mutableState.value.copy(payerRequestStage = null)
                }
            }
        }
    }

    private suspend fun sendSnapshot(room: CoopRoomEntity, participantId: String, after: Long) {
        val snapshot = repository.buildSnapshot(room.roomId, after)
        val envelope = CoopWireEnvelope(
                kind = CoopWireKind.SNAPSHOT,
                roomId = room.roomId,
                senderParticipantId = room.localParticipantId,
                targetParticipantId = participantId,
                clientMessageId = LanCoopTransport.newClientMessageId(),
                payloadJson = repository.codec.snapshotToJson(snapshot)
            )
        requireWireFrameFits(envelope)
        transport?.send(envelope)
        rememberSnapshotContinuation(participantId, snapshot)
    }

    private fun rememberSnapshotContinuation(participantId: String, snapshot: CoopSnapshotPayload) {
        synchronized(resyncContinuations) {
            if (snapshot.hasMoreEvents) {
                resyncContinuations[participantId] = snapshot.events.last().sequence
            } else {
                resyncContinuations.remove(participantId)
            }
        }
    }

    private suspend fun requestResync(room: CoopRoomEntity, after: Long) {
        if (room.localRole != CoopRole.CLIENT) return
        val shouldSend = synchronized(outstandingResyncAfter) {
            if (outstandingResyncAfter.containsKey(room.roomId)) {
                false
            } else {
                outstandingResyncAfter[room.roomId] = after
                true
            }
        }
        if (!shouldSend) return
        try {
            transport?.send(
                CoopWireEnvelope(
                    kind = CoopWireKind.RESYNC_REQUEST,
                    roomId = room.roomId,
                    senderParticipantId = room.localParticipantId,
                    clientMessageId = LanCoopTransport.newClientMessageId(),
                    payloadJson = repository.codec.resyncToJson(CoopResyncRequestPayload(after))
                )
            )
        } catch (error: Exception) {
            synchronized(outstandingResyncAfter) { outstandingResyncAfter.remove(room.roomId) }
            throw error
        }
    }

    private suspend fun activeRoomForChat(chatId: String): CoopRoomEntity? = repository.getRoomForChat(chatId)

    private fun requireAuthoritativeConnection(room: CoopRoomEntity) {
        requireNetworkingEnabled()
        require(room.connectionState == CoopConnectionState.CONNECTED) {
            "Host is offline. Co-op sending and generation are suspended until reconnect."
        }
    }

    private fun requireWireFrameFits(envelope: CoopWireEnvelope) {
        require(
            repository.codec.wireToJson(envelope).toByteArray(Charsets.UTF_8).size <=
                CoopProtocol.MAX_FRAME_BYTES
        ) { "Bounded snapshot page still exceeds the encrypted frame limit" }
    }

    private fun requireSnapshotPayloadBound(payloadJson: String) {
        require(payloadJson.toByteArray(Charsets.UTF_8).size <= CoopProtocol.MAX_SNAPSHOT_JSON_BYTES) {
            "Snapshot exceeds the total byte limit"
        }
    }

    private fun attachTransport(next: CoopTransport) {
        transport = next
        transportJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            next.events.collect { event ->
                try {
                    handleTransportEvent(event)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    mutableState.value = mutableState.value.copy(
                        errorMessage = error.message ?: "Co-op event was rejected"
                    )
                }
            }
        }
        reconciliationJob = scope.launch {
            while (isActive) {
                delay(15_000)
                mutableState.value.room?.let { room ->
                    runCatching { reconcileActiveGeneration(room.roomId) }
                    runCatching {
                        transport?.send(
                            CoopWireEnvelope(
                                kind = CoopWireKind.PING,
                                roomId = room.roomId,
                                senderParticipantId = room.localParticipantId,
                                clientMessageId = LanCoopTransport.newClientMessageId()
                            )
                        )
                    }
                }
            }
        }
    }

    private suspend fun stopTransportOnly() = withContext(NonCancellable) {
        val currentTransport = transport
        transport = null
        runCatching { currentTransport?.stop() }
        runCatching { transportJob?.cancelAndJoin() }
        transportJob = null
        runCatching { reconciliationJob?.cancelAndJoin() }
        reconciliationJob = null
        pendingJoinRecords.clear()
        pendingProposalResults.values.forEach {
            it.completeExceptionally(CoopProtocolException("The co-op session ended before confirmation"))
        }
        pendingProposalResults.clear()
        synchronized(outstandingResyncAfter) { outstandingResyncAfter.clear() }
    }

    private suspend fun stopExistingTransportForSessionStart() = withContext(NonCancellable) {
        val previousRoom = mutableState.value.room
        stopTransportOnly()
        if (previousRoom != null) {
            val disconnectedState = if (previousRoom.localRole == CoopRole.HOST) {
                CoopConnectionState.DISCONNECTED
            } else {
                CoopConnectionState.SUSPENDED_HOST_OFFLINE
            }
            runCatching { repository.updateConnectionState(previousRoom.roomId, disconnectedState) }
            runCatching { repository.setParticipantConnected(previousRoom.localParticipantId, false) }
            runCatching {
                refresh(
                    previousRoom.roomId,
                    pairingCode = null,
                    status = "Disconnected"
                )
            }
        }
    }

    private suspend fun cleanupFailedSessionStart(
        roomId: String,
        connectionState: String,
        pairingCode: String?,
        error: Throwable
    ) = withContext(NonCancellable) {
        runCatching { stopTransportOnly() }
        runCatching { repository.updateConnectionState(roomId, connectionState) }
        runCatching {
            repository.getRoom(roomId)?.let { room ->
                repository.setParticipantConnected(room.localParticipantId, false)
            }
        }
        val publicError = if (error is CancellationException) {
            "Co-op startup was cancelled and its LAN transport was closed."
        } else {
            error.message ?: "Unable to start local co-op."
        }
        runCatching { refresh(roomId, pairingCode = pairingCode, error = publicError) }
    }

    private suspend fun reconcileActiveGeneration(roomId: String) {
        val room = repository.getRoom(roomId) ?: return
        val lifecycle = repository.getGenerationLifecycle(roomId) ?: run {
            if (mutableState.value.pendingPayerRequest?.roomId == roomId) {
                mutableState.value = mutableState.value.copy(
                    pendingPayerRequest = null,
                    payerRequestStage = null
                )
            }
            return
        }
        val elapsed = System.currentTimeMillis() - lifecycle.stateChangedAt
        val timeoutCode = when (lifecycle.stage) {
            CoopGenerationStage.PENDING_APPROVAL -> if (
                elapsed >= CoopProtocol.GENERATION_APPROVAL_TIMEOUT_MS
            ) CoopGenerationResultCode.APPROVAL_TIMEOUT else null
            CoopGenerationStage.APPROVED -> if (
                elapsed >= CoopProtocol.GENERATION_APPROVED_START_TIMEOUT_MS
            ) CoopGenerationResultCode.EXECUTION_NOT_STARTED else null
            CoopGenerationStage.EXECUTING -> if (
                elapsed >= CoopProtocol.GENERATION_EXECUTION_TIMEOUT_MS
            ) CoopGenerationResultCode.EXECUTION_TIMEOUT else null
            else -> null
        }
        if (timeoutCode != null && room.localRole == CoopRole.HOST) {
            val event = repository.appendHostEvent(
                room.roomId,
                "timeout:${lifecycle.request.requestId}",
                room.hostParticipantId,
                CoopEventType.GENERATION_FAILED,
                repository.codec.completionToJson(
                    CoopGenerationCompletionPayload(
                        requestId = lifecycle.request.requestId,
                        executionId = lifecycle.request.requestId,
                        errorMessage = timeoutCode
                    )
                )
            )
            broadcastEvent(repository.getRoom(room.roomId) ?: room, event)
            emitGenerationIfLocal(room, event)
            refresh(room.roomId, status = "Generation ended after its bounded ${lifecycle.stage.lowercase()} timeout.")
        } else if (timeoutCode == null && lifecycle.request.payerParticipantId == room.localParticipantId) {
            when (lifecycle.stage) {
                CoopGenerationStage.PENDING_APPROVAL, CoopGenerationStage.APPROVED -> {
                    mutableState.value = mutableState.value.copy(
                        pendingPayerRequest = CoopGenerationCommand(
                            room.chatId,
                            room.roomId,
                            lifecycle.request,
                            executionId = lifecycle.request.requestId
                        ),
                        payerRequestStage = lifecycle.stage,
                        statusMessage = if (lifecycle.stage == CoopGenerationStage.APPROVED) {
                            "Approval is saved. Choose Start approved generation when connected."
                        } else {
                            "Generation requires approval on this payer device."
                        }
                    )
                }
                CoopGenerationStage.EXECUTING -> {
                    // Never re-dispatch paid work from persisted state. Only the fresh, host-ordered
                    // EXECUTING event can emit a provider command in this process.
                    mutableState.value = mutableState.value.copy(
                        pendingPayerRequest = null,
                        payerRequestStage = CoopGenerationStage.EXECUTING,
                        statusMessage = "Execution was already acknowledged; it will not be retried automatically."
                    )
                }
                else -> Unit
            }
        }
    }

    private suspend fun failGenerationIfAssignedPayerDisconnected(
        room: CoopRoomEntity,
        participantId: String
    ) {
        if (room.localRole != CoopRole.HOST) return
        val lifecycle = repository.getGenerationLifecycle(room.roomId) ?: return
        if (lifecycle.request.payerParticipantId != participantId) return
        if (lifecycle.stage == CoopGenerationStage.EXECUTING) return
        val event = repository.appendHostEvent(
            room.roomId,
            "payer-disconnected:${lifecycle.request.requestId}",
            room.hostParticipantId,
            CoopEventType.GENERATION_FAILED,
            repository.codec.completionToJson(
                CoopGenerationCompletionPayload(
                    requestId = lifecycle.request.requestId,
                    executionId = lifecycle.request.requestId,
                    errorMessage = CoopGenerationResultCode.PAYER_DISCONNECTED
                )
            )
        )
        broadcastEvent(repository.getRoom(room.roomId) ?: room, event)
        emitGenerationIfLocal(room, event)
    }

    private fun publicGenerationError(error: String): String = when (error) {
        in CoopGenerationResultCode.all -> error
        else -> CoopGenerationResultCode.GENERATION_FAILED
    }

    private fun consumeProposalAllowance(participantId: String): Boolean = synchronized(proposalWindows) {
        val now = System.currentTimeMillis()
        val current = proposalWindows[participantId]
        val next = if (current == null || now - current.startedAt >= PROPOSAL_WINDOW_MS) {
            ProposalWindow(now, 1)
        } else {
            current.copy(count = current.count + 1)
        }
        proposalWindows[participantId] = next
        next.count <= MAX_PROPOSALS_PER_WINDOW
    }

    private fun consumeControlAllowance(participantId: String, kind: String, maximum: Int): Boolean =
        synchronized(controlWindows) {
            val now = System.currentTimeMillis()
            val bucket = if (kind == CoopWireKind.PING || kind == CoopWireKind.PONG) {
                "PING_PONG"
            } else {
                kind
            }
            val key = "$participantId:$bucket"
            val current = controlWindows[key]
            val next = if (current == null || now - current.startedAt >= CONTROL_WINDOW_MS) {
                ProposalWindow(now, 1)
            } else {
                current.copy(count = current.count + 1)
            }
            controlWindows[key] = next
            next.count <= maximum
        }

    private suspend fun refresh(
        roomId: String,
        pairingCode: String? = mutableState.value.pairingCode,
        status: String? = mutableState.value.statusMessage,
        error: String? = null
    ) {
        val room = repository.getRoom(roomId)
        mutableState.value = CoopSessionState(
            room = room,
            participants = repository.getParticipants(roomId),
            personaOwnerships = repository.getOwnerships(roomId),
            pairingCode = pairingCode,
            suggestedAddresses = mutableState.value.suggestedAddresses,
            pendingPayerRequest = mutableState.value.pendingPayerRequest?.takeIf { it.roomId == roomId },
            payerRequestStage = mutableState.value.payerRequestStage,
            pendingJoins = pendingJoinRecords.values.sortedBy { it.requestedAt }.map {
                CoopPendingJoin(
                    participantId = it.participant.participantId,
                    participantDisplayName = it.participant.displayName,
                    personaDisplayName = it.ownership.personaDisplayNameSnapshot,
                    membershipFingerprint = it.participant.deviceId,
                    requestedAt = it.requestedAt
                )
            },
            statusMessage = status,
            errorMessage = error
        )
    }

    companion object {
        /**
         * Enabled only for the explicitly approved, bounded user-signed two-device test path.
         * The listener still requires a confirmed Host/Resume action; source review and fake-
         * transport coverage do not substitute for real Android lifecycle/network verification.
         */
        const val LAN_NETWORKING_ENABLED = true
        private const val PROPOSAL_WINDOW_MS = 60_000L
        private const val MAX_PROPOSALS_PER_WINDOW = 120
        private const val CONTROL_WINDOW_MS = 60_000L
        private const val MAX_RESYNC_CHAINS_PER_WINDOW = 4
        private const val MAX_CONTROL_FRAMES_PER_WINDOW = 16
        private const val PENDING_JOIN_TIMEOUT_MS = 120_000L
        private const val PROPOSAL_ACK_TIMEOUT_MS = 20_000L

        private val CLIENT_EVENT_TYPES = setOf(
            CoopEventType.MESSAGE_UPSERT,
            CoopEventType.MESSAGE_DELETE,
            CoopEventType.GENERATION_REQUESTED,
            CoopEventType.GENERATION_APPROVED,
            CoopEventType.GENERATION_EXECUTING,
            CoopEventType.GENERATION_FINISHED,
            CoopEventType.GENERATION_FAILED
        )
        private val HOST_INBOUND_KINDS = setOf(
            CoopWireKind.HELLO,
            CoopWireKind.PROPOSE_EVENT,
            CoopWireKind.RESYNC_REQUEST,
            CoopWireKind.PING,
            CoopWireKind.PONG
        )
        private val CLIENT_INBOUND_KINDS = setOf(
            CoopWireKind.JOIN_ACCEPTED,
            CoopWireKind.JOIN_REJECTED,
            CoopWireKind.PROPOSAL_REJECTED,
            CoopWireKind.ORDERED_EVENT,
            CoopWireKind.SNAPSHOT,
            CoopWireKind.PING,
            CoopWireKind.PONG
        )
    }

    private fun requireNetworkingEnabled() {
        if (!networkingEnabled()) {
            throw CoopProtocolException(
                "Local Co-op networking is disabled in this build pending explicit user approval and a two-device acceptance run."
            )
        }
    }

    private suspend fun <T> withSingleFlightSessionStart(block: suspend () -> T): T {
        if (!sessionStartMutex.tryLock()) {
            throw CoopProtocolException("A co-op host, join, or reconnect action is already in progress")
        }
        return try {
            block()
        } finally {
            sessionStartMutex.unlock()
        }
    }

    private data class ProposalWindow(val startedAt: Long, val count: Int)
}
