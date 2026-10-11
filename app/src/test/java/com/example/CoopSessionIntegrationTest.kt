package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ChatEntity
import com.example.data.model.ChatParticipantEntity
import com.example.data.model.ChatPersonaParticipantEntity
import com.example.data.model.ChatSettingsEntity
import com.example.data.model.ChatType
import com.example.data.model.CharacterEntity
import com.example.data.model.CoopConnectionState
import com.example.data.model.CoopEventEntity
import com.example.data.model.CoopEventType
import com.example.data.model.CoopParticipantEntity
import com.example.data.model.CoopPersonaOwnershipEntity
import com.example.data.model.MessageEntity
import com.example.data.model.PersonaEntity
import com.example.data.model.SpeakerType
import com.example.domain.coop.CoopAuthenticatedParticipant
import com.example.domain.coop.CoopClientTransportSecurity
import com.example.domain.coop.CoopCodec
import com.example.domain.coop.CoopDeviceIdentity
import com.example.domain.coop.CoopEventDto
import com.example.domain.coop.CoopGenerationAuthorization
import com.example.domain.coop.CoopGenerationApprovalPayload
import com.example.domain.coop.CoopGenerationMetadataPayload
import com.example.domain.coop.CoopGenerationResultCode
import com.example.domain.coop.CoopGenerationStage
import com.example.domain.coop.CoopHostTransportSecurity
import com.example.domain.coop.CoopParticipantIdentityStore
import com.example.domain.coop.CoopParticipantDto
import com.example.domain.coop.CoopPersonaOwnershipDto
import com.example.domain.coop.CoopProtocol
import com.example.domain.coop.CoopProtocolException
import com.example.domain.coop.CoopRoomIdentity
import com.example.domain.coop.CoopRoomIdentityStore
import com.example.domain.coop.CoopResyncRequestPayload
import com.example.domain.coop.CoopSessionCrypto
import com.example.domain.coop.CoopSecureChannel
import com.example.domain.coop.CoopSessionManager
import com.example.domain.coop.CoopTransport
import com.example.domain.coop.CoopTransportEvent
import com.example.domain.coop.CoopWireEnvelope
import com.example.domain.coop.CoopWireKind
import com.example.domain.repository.CoopRepository
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.MessageRepository
import com.example.domain.repository.PersonaRepository
import com.example.domain.provider.GenerationOptions
import com.example.domain.provider.ModelProvider
import com.example.domain.provider.OpenRouterModel
import com.example.domain.provider.ProviderEndpoint
import com.example.domain.provider.RoleplayMessage
import com.example.domain.provider.StreamEvent
import com.example.ui.screens.ChatDetailViewModel
import com.example.ui.screens.ChatUiState
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CoopSessionIntegrationTest {
    private lateinit var context: Context
    private val databases = mutableListOf<AppDatabase>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        databases.forEach(AppDatabase::close)
    }

    @Test
    fun joinRemainsStagedUntilHostExplicitlyAcceptsIt() = runBlocking {
        val network = FakeCoopNetwork()
        val hostFixture = fixture("manual-admission-chat")
        val clientFixture = fixture("manual-admission-chat")
        val host = manager(
            hostFixture.repository,
            network,
            MemoryRoomIdentityStore(),
            MemoryParticipantIdentityStore(),
            autoAcceptJoinsForTests = false
        )
        val client = manager(
            clientFixture.repository,
            network,
            MemoryRoomIdentityStore(),
            MemoryParticipantIdentityStore()
        )
        val invite = host.host("manual-admission-chat", "Host", "persona-host", "Host Persona")

        client.join(
            chatId = "manual-admission-chat",
            roomId = invite.room.roomId,
            hostParticipantId = invite.room.hostParticipantId,
            hostAddress = "fake.lan",
            port = invite.room.hostPort,
            pairingCode = invite.pairingCode,
            hostPublicKey = invite.room.hostPublicKey,
            hostKeyFingerprint = invite.room.hostKeyFingerprint,
            participantDisplayName = "Bella",
            personaId = "persona-bella",
            personaDisplayName = "Bella Persona"
        )

        eventually("manual join request appears") { host.state.value.pendingJoins.size == 1 }
        val pending = host.state.value.pendingJoins.single()
        assertEquals("Bella", pending.participantDisplayName)
        assertEquals("Bella Persona", pending.personaDisplayName)
        assertTrue(pending.membershipFingerprint.length >= 16)
        assertEquals(CoopConnectionState.CONNECTING, client.state.value.room?.connectionState)
        assertNull(hostFixture.repository.getParticipant(pending.participantId))

        host.acceptPendingJoin(pending.participantId)

        eventually("manual join accepted") {
            client.state.value.room?.connectionState == CoopConnectionState.CONNECTED &&
                host.state.value.pendingJoins.isEmpty()
        }
        assertTrue(hostFixture.repository.getParticipant(pending.participantId)?.isConnected == true)
        client.disconnect()
        host.disconnect()
    }

    @Test
    fun fakeLanJoinRejectsImpersonationThenReconnectsAndResyncsWithoutSharingSecrets() = runBlocking {
        val network = FakeCoopNetwork()
        val hostFixture = fixture("shared-chat")
        val clientFixture = fixture("shared-chat")
        val attackerFixture = fixture("attacker-chat")
        val hostRoomKeys = MemoryRoomIdentityStore()
        val clientMemberKeys = MemoryParticipantIdentityStore()
        val attackerMemberKeys = MemoryParticipantIdentityStore()
        val authorNotesSentinel = "AUTHOR-NOTES-SENTINEL-7fb21b"
        val privateNotesSentinel = "PERSONA-PRIVATE-SENTINEL-15c942"
        hostFixture.database.characterDao().insertCharacter(
            CharacterEntity(
                characterId = "private-character",
                displayName = "Private Character",
                characterName = "Private Character",
                aliases = emptyList(),
                importSchemaVersion = 1,
                identityJson = null,
                appearanceJson = null,
                personalityJson = null,
                voiceJson = null,
                behaviorJson = null,
                backstoryJson = null,
                knowledgeJson = null,
                worldContextJson = null,
                writingRulesJson = null,
                examplesJson = null,
                authorNotesJson = "\"$authorNotesSentinel\"",
                extensionFieldsJson = null
            )
        )
        hostFixture.database.chatDao().insertChatParticipant(
            ChatParticipantEntity("shared-chat", "private-character")
        )
        hostFixture.database.personaDao().insertPersona(
            PersonaEntity(
                personaId = "persona-host",
                displayName = "Host Persona",
                personaName = "Host Persona",
                aliases = emptyList(),
                importSchemaVersion = 1,
                identityJson = null,
                appearanceJson = null,
                personalityJson = null,
                roleplayProfileJson = null,
                backgroundJson = null,
                worldContextJson = null,
                privateNotesJson = "\"$privateNotesSentinel\"",
                extensionFieldsJson = null
            )
        )
        val host = manager(hostFixture.repository, network, hostRoomKeys, MemoryParticipantIdentityStore())
        val client = manager(clientFixture.repository, network, MemoryRoomIdentityStore(), clientMemberKeys)
        val attacker = manager(attackerFixture.repository, network, MemoryRoomIdentityStore(), attackerMemberKeys)

        val invite = host.host("shared-chat", "Host", "persona-host", "Host Persona")
        client.join(
            chatId = "shared-chat",
            roomId = invite.room.roomId,
            hostParticipantId = invite.room.hostParticipantId,
            hostAddress = "fake.lan",
            port = invite.room.hostPort,
            pairingCode = invite.pairingCode,
            hostPublicKey = invite.room.hostPublicKey,
            hostKeyFingerprint = invite.room.hostKeyFingerprint,
            participantDisplayName = "Bella",
            personaId = "persona-bella",
            personaDisplayName = "Bella Persona"
        )
        eventually("initial join: ${client.state.value}") {
            client.state.value.room?.connectionState == CoopConnectionState.CONNECTED
        }
        val memberId = requireNotNull(client.state.value.room).localParticipantId
        val originalFingerprint = requireNotNull(
            hostFixture.repository.getParticipant(memberId)
        ).deviceId

        client.disconnect()
        eventually("initial disconnect") { hostFixture.repository.getParticipant(memberId)?.isConnected == false }

        attacker.join(
            chatId = "attacker-chat",
            roomId = invite.room.roomId,
            hostParticipantId = invite.room.hostParticipantId,
            hostAddress = "fake.lan",
            port = invite.room.hostPort,
            pairingCode = invite.pairingCode,
            hostPublicKey = invite.room.hostPublicKey,
            hostKeyFingerprint = invite.room.hostKeyFingerprint,
            participantId = memberId,
            participantDisplayName = "Impostor",
            personaId = "persona-bella",
            personaDisplayName = "Bella Persona"
        )
        eventually("attacker rejection: ${attacker.state.value}") {
            attacker.state.value.room?.connectionState == CoopConnectionState.SUSPENDED_HOST_OFFLINE
        }
        assertEquals(originalFingerprint, hostFixture.repository.getParticipant(memberId)?.deviceId)
        assertFalse(hostFixture.repository.getParticipant(memberId)?.isConnected == true)

        client.join(
            chatId = "shared-chat",
            roomId = invite.room.roomId,
            hostParticipantId = invite.room.hostParticipantId,
            hostAddress = "fake.lan",
            port = invite.room.hostPort,
            pairingCode = invite.pairingCode,
            hostPublicKey = invite.room.hostPublicKey,
            hostKeyFingerprint = invite.room.hostKeyFingerprint,
            participantDisplayName = "Bella",
            personaId = "persona-bella",
            personaDisplayName = "Bella Persona"
        )
        eventually("legitimate reconnect") { hostFixture.repository.getParticipant(memberId)?.isConnected == true }

        client.disconnect()
        eventually("offline disconnect") { hostFixture.repository.getParticipant(memberId)?.isConnected == false }
        val offlineMessage = MessageEntity(
            messageId = "offline-message",
            chatId = "shared-chat",
            speakerType = SpeakerType.PERSONA,
            speakerId = "persona-host",
            speakerDisplayNameSnapshot = "Host Persona",
            content = "Arrived while the other participant was offline.",
            orderIndex = 10L
        )
        host.publishMessage(offlineMessage)
        client.join(
            chatId = "shared-chat",
            roomId = invite.room.roomId,
            hostParticipantId = invite.room.hostParticipantId,
            hostAddress = "fake.lan",
            port = invite.room.hostPort,
            pairingCode = invite.pairingCode,
            hostPublicKey = invite.room.hostPublicKey,
            hostKeyFingerprint = invite.room.hostKeyFingerprint,
            participantDisplayName = "Bella",
            personaId = "persona-bella",
            personaDisplayName = "Bella Persona"
        )
        eventually("offline resync") {
            clientFixture.database.messageDao().getMessageById("offline-message") != null
        }

        val serialized = network.serializedEnvelopes.joinToString("\n")
        listOf(
            "apiKey",
            "Authorization",
            "privateNotes",
            "authorNotes",
            "pairingCodeHash",
            "account-wide"
        ).forEach { forbidden ->
            assertFalse("Serialized a forbidden field: $forbidden", serialized.contains(forbidden, true))
        }
        assertFalse(serialized.contains(invite.pairingCode))
        assertFalse(serialized.contains(authorNotesSentinel))
        assertFalse(serialized.contains(privateNotesSentinel))

        attacker.disconnect()
        client.disconnect()
        host.disconnect()
    }

    @Test
    fun encryptedHostCannotForgeRemotePayerApprovalOrDispatchPaidWork() = runBlocking {
        val network = FakeCoopNetwork()
        val hostFixture = fixture("forged-approval-chat", characterId = "character")
        val clientFixture = fixture("forged-approval-chat")
        val hostKeys = MemoryRoomIdentityStore()
        val clientKeys = MemoryParticipantIdentityStore()
        val host = manager(hostFixture.repository, network, hostKeys, MemoryParticipantIdentityStore())
        val client = manager(clientFixture.repository, network, MemoryRoomIdentityStore(), clientKeys)
        host.registerGenerationContextAuthorizer("forged-approval-chat") {
            CoopGenerationAuthorization("a".repeat(64), "mock/model")
        }
        val invite = host.host("forged-approval-chat", "Host", "persona-host", "Host Persona")
        client.join(
            chatId = "forged-approval-chat",
            roomId = invite.room.roomId,
            hostParticipantId = invite.room.hostParticipantId,
            hostAddress = "fake.lan",
            port = invite.room.hostPort,
            pairingCode = invite.pairingCode,
            hostPublicKey = invite.room.hostPublicKey,
            hostKeyFingerprint = invite.room.hostKeyFingerprint,
            participantDisplayName = "Payer",
            personaId = "persona-payer",
            personaDisplayName = "Payer Persona"
        )
        eventually("forged approval join") {
            client.state.value.room?.connectionState == CoopConnectionState.CONNECTED
        }
        var dispatched = 0
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        collectorScope.launch { client.generationCommands.collect { dispatched++ } }
        client.requestGeneration("forged-approval-chat", "character")
        eventually("forged approval pending") {
            client.state.value.payerRequestStage == CoopGenerationStage.PENDING_APPROVAL
        }
        val clientRoom = requireNotNull(client.state.value.room)
        val requestId = requireNotNull(client.state.value.pendingPayerRequest).request.requestId
        val approvalJson = clientFixture.repository.codec.approvalToJson(
            CoopGenerationApprovalPayload(requestId)
        )
        val hostIdentity = requireNotNull(hostKeys.get(invite.room.roomId))
        val forgedSignature = CoopSessionCrypto.signParticipantEvent(
            hostIdentity.keyPair,
            invite.room.roomId,
            "approval:$requestId",
            clientRoom.localParticipantId,
            CoopEventType.GENERATION_APPROVED,
            approvalJson
        )
        val currentClientRoom = requireNotNull(clientFixture.repository.getRoom(invite.room.roomId))
        val forgedEvent = CoopEventEntity(
            eventId = "host-forged-approval",
            roomId = invite.room.roomId,
            sequence = currentClientRoom.lastAppliedSequence + 1,
            clientEventId = "approval:$requestId",
            originParticipantId = clientRoom.localParticipantId,
            eventType = CoopEventType.GENERATION_APPROVED,
            payloadJson = approvalJson,
            originPayloadJson = approvalJson,
            originSignature = forgedSignature
        )
        network.injectHostEnvelope(
            clientRoom.localParticipantId,
            CoopWireEnvelope(
                kind = CoopWireKind.ORDERED_EVENT,
                roomId = invite.room.roomId,
                senderParticipantId = invite.room.hostParticipantId,
                targetParticipantId = clientRoom.localParticipantId,
                clientMessageId = forgedEvent.clientEventId,
                payloadJson = clientFixture.repository.codec.eventToJson(CoopEventDto.from(forgedEvent))
            )
        )

        eventually("forged approval rejection") {
            client.state.value.errorMessage?.contains("signature") == true
        }
        delay(100)
        assertEquals(0, dispatched)
        assertEquals(
            CoopGenerationStage.PENDING_APPROVAL,
            clientFixture.repository.getGenerationLifecycle(invite.room.roomId)?.stage
        )
        collectorScope.cancel()
        client.disconnect()
        host.disconnect()
    }

    @Test
    fun encryptedHostCannotMutateLegitimatelySignedPersonaMessage() = runBlocking {
        val network = FakeCoopNetwork()
        val hostFixture = fixture("signed-message-chat")
        val clientFixture = fixture("signed-message-chat")
        val host = manager(
            hostFixture.repository,
            network,
            MemoryRoomIdentityStore(),
            MemoryParticipantIdentityStore()
        )
        val client = manager(
            clientFixture.repository,
            network,
            MemoryRoomIdentityStore(),
            MemoryParticipantIdentityStore()
        )
        val invite = host.host("signed-message-chat", "Host", "persona-host", "Host Persona")
        client.join(
            chatId = "signed-message-chat",
            roomId = invite.room.roomId,
            hostParticipantId = invite.room.hostParticipantId,
            hostAddress = "fake.lan",
            port = invite.room.hostPort,
            pairingCode = invite.pairingCode,
            hostPublicKey = invite.room.hostPublicKey,
            hostKeyFingerprint = invite.room.hostKeyFingerprint,
            participantDisplayName = "Bella",
            personaId = "persona-bella",
            personaDisplayName = "Bella Persona"
        )
        eventually("signed message join") {
            client.state.value.room?.connectionState == CoopConnectionState.CONNECTED
        }
        network.tamperNextOrderedPersonaMessage = true
        val message = MessageEntity(
            messageId = "signed-persona-message",
            chatId = "signed-message-chat",
            speakerType = SpeakerType.PERSONA,
            speakerId = "persona-bella",
            speakerDisplayNameSnapshot = "Bella Persona",
            content = "Authentic words.",
            orderIndex = 1L
        )

        val publishError = runCatching { client.publishMessage(message) }.exceptionOrNull()

        eventually("mutated message rejection") {
            client.state.value.errorMessage?.contains("changed signed message") == true
        }
        assertTrue(publishError?.message?.contains("changed signed message") == true)
        assertNull(clientFixture.database.messageDao().getMessageById(message.messageId))
        assertEquals(
            "Authentic words.",
            hostFixture.database.messageDao().getMessageById(message.messageId)?.content
        )
        client.disconnect()
        host.disconnect()
    }

    @Test
    fun encryptedHostSnapshotCannotSubstituteEnrolledParticipantKey() = runBlocking {
        val network = FakeCoopNetwork()
        val hostFixture = fixture("key-substitution-chat")
        val clientFixture = fixture("key-substitution-chat")
        val host = manager(
            hostFixture.repository,
            network,
            MemoryRoomIdentityStore(),
            MemoryParticipantIdentityStore()
        )
        val client = manager(
            clientFixture.repository,
            network,
            MemoryRoomIdentityStore(),
            MemoryParticipantIdentityStore()
        )
        val invite = host.host("key-substitution-chat", "Host", "persona-host", "Host Persona")
        client.join(
            chatId = "key-substitution-chat",
            roomId = invite.room.roomId,
            hostParticipantId = invite.room.hostParticipantId,
            hostAddress = "fake.lan",
            port = invite.room.hostPort,
            pairingCode = invite.pairingCode,
            hostPublicKey = invite.room.hostPublicKey,
            hostKeyFingerprint = invite.room.hostKeyFingerprint,
            participantDisplayName = "Bella",
            personaId = "persona-bella",
            personaDisplayName = "Bella Persona"
        )
        eventually("key substitution join") {
            client.state.value.room?.connectionState == CoopConnectionState.CONNECTED
        }
        val clientRoom = requireNotNull(client.state.value.room)
        val original = requireNotNull(
            clientFixture.repository.getParticipant(clientRoom.localParticipantId)
        )
        val attackerKey = CoopSessionCrypto.generateSigningKeyPair().public.encoded
        val substitutedKey = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(attackerKey)
        val snapshot = hostFixture.repository.buildSnapshot(invite.room.roomId, clientRoom.lastAppliedSequence)
        val hostileSnapshot = snapshot.copy(
            participants = snapshot.participants.map { participant ->
                if (participant.participantId == clientRoom.localParticipantId) {
                    participant.copy(
                        deviceId = CoopSessionCrypto.hostFingerprint(attackerKey),
                        identityPublicKey = substitutedKey
                    )
                } else participant
            }
        )
        network.injectHostEnvelope(
            clientRoom.localParticipantId,
            CoopWireEnvelope(
                kind = CoopWireKind.SNAPSHOT,
                roomId = invite.room.roomId,
                senderParticipantId = invite.room.hostParticipantId,
                targetParticipantId = clientRoom.localParticipantId,
                clientMessageId = "hostile-key-snapshot",
                payloadJson = clientFixture.repository.codec.snapshotToJson(hostileSnapshot)
            )
        )

        eventually("key substitution rejection") {
            client.state.value.errorMessage?.contains("collides") == true
        }
        val preserved = requireNotNull(
            clientFixture.repository.getParticipant(clientRoom.localParticipantId)
        )
        assertEquals(original.deviceId, preserved.deviceId)
        assertEquals(original.identityPublicKey, preserved.identityPublicKey)
        client.disconnect()
        host.disconnect()
    }

    @Test
    fun payerCommandRequiresApprovalIsHostAcknowledgedAndNeverRedispatchedOnLoad() = runBlocking {
        val network = FakeCoopNetwork()
        val hostFixture = fixture("payer-chat", characterId = "character")
        val clientFixture = fixture("payer-chat")
        val host = manager(
            hostFixture.repository,
            network,
            MemoryRoomIdentityStore(),
            MemoryParticipantIdentityStore()
        )
        val client = manager(
            clientFixture.repository,
            network,
            MemoryRoomIdentityStore(),
            MemoryParticipantIdentityStore()
        )
        host.registerGenerationContextAuthorizer("payer-chat") {
            CoopGenerationAuthorization("a".repeat(64), "mock/model")
        }
        val invite = host.host("payer-chat", "Host", "persona-host", "Host Persona")
        client.join(
            chatId = "payer-chat",
            roomId = invite.room.roomId,
            hostParticipantId = invite.room.hostParticipantId,
            hostAddress = "fake.lan",
            port = invite.room.hostPort,
            pairingCode = invite.pairingCode,
            hostPublicKey = invite.room.hostPublicKey,
            hostKeyFingerprint = invite.room.hostKeyFingerprint,
            participantDisplayName = "Payer",
            personaId = "persona-payer",
            personaDisplayName = "Payer Persona"
        )
        eventually("payer join") { client.state.value.room?.connectionState == CoopConnectionState.CONNECTED }
        assertTrue(clientFixture.database.chatDao().getChatParticipant("payer-chat", "character")?.isActive == true)

        var dispatched = 0
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        collectorScope.launch {
            client.generationCommands.collect { dispatched++ }
        }
        client.requestGeneration("payer-chat", "character")
        eventually("pending approval") {
            client.state.value.payerRequestStage == CoopGenerationStage.PENDING_APPROVAL
        }
        assertEquals(0, dispatched)
        val requestId = requireNotNull(client.state.value.pendingPayerRequest).request.requestId

        network.duplicateProposalEventTypes += CoopEventType.GENERATION_APPROVED
        network.duplicateProposalEventTypes += CoopEventType.GENERATION_EXECUTING
        client.approvePendingGeneration(requestId)
        eventually("execution ack") {
            hostFixture.repository.getGenerationLifecycle(invite.room.roomId)?.stage ==
                CoopGenerationStage.EXECUTING && dispatched == 1
        }
        delay(100)
        assertEquals("duplicate approval/execution must not dispatch paid work twice", 1, dispatched)
        client.load("payer-chat")
        delay(100)
        assertEquals(1, dispatched)
        assertEquals(CoopGenerationStage.EXECUTING, client.state.value.payerRequestStage)
        val payerParticipantId = requireNotNull(client.state.value.room).localParticipantId
        client.disconnect()
        eventually("executing payer disconnect") {
            hostFixture.repository.getParticipant(payerParticipantId)?.isConnected == false
        }
        assertEquals(
            CoopGenerationStage.EXECUTING,
            hostFixture.repository.getGenerationLifecycle(invite.room.roomId)?.stage
        )
        client.join(
            chatId = "payer-chat",
            roomId = invite.room.roomId,
            hostParticipantId = invite.room.hostParticipantId,
            hostAddress = "fake.lan",
            port = invite.room.hostPort,
            pairingCode = invite.pairingCode,
            hostPublicKey = invite.room.hostPublicKey,
            hostKeyFingerprint = invite.room.hostKeyFingerprint,
            participantDisplayName = "Payer",
            personaId = "persona-payer",
            personaDisplayName = "Payer Persona"
        )
        eventually("executing payer reconnect") {
            hostFixture.repository.getParticipant(payerParticipantId)?.isConnected == true
        }
        delay(100)
        assertEquals("reconnect must not retry paid execution", 1, dispatched)

        val unrelatedPersonaMessage = MessageEntity(
            messageId = "unrelated-persona-message",
            chatId = "payer-chat",
            speakerType = SpeakerType.PERSONA,
            speakerId = "persona-payer",
            speakerDisplayNameSnapshot = "Payer Persona",
            content = "This is not an authorized Character result.",
            orderIndex = 40L
        )
        val transcriptFreezeError = runCatching {
            client.publishMessage(unrelatedPersonaMessage)
        }.exceptionOrNull()
        assertTrue(transcriptFreezeError?.message?.contains("rejected", ignoreCase = true) == true)
        assertEquals(null, hostFixture.database.messageDao().getMessageById(unrelatedPersonaMessage.messageId))
        val unrelatedCompletionError = runCatching {
            client.finishGeneration("payer-chat", requestId, unrelatedPersonaMessage.messageId)
        }.exceptionOrNull()
        assertTrue(unrelatedCompletionError?.message?.contains("rejected", ignoreCase = true) == true)
        eventually("unrelated completion rejection") {
            client.state.value.errorMessage?.contains("rejected", ignoreCase = true) == true
        }
        assertEquals(
            CoopGenerationStage.EXECUTING,
            hostFixture.repository.getGenerationLifecycle(invite.room.roomId)?.stage
        )

        val resultMessage = MessageEntity(
            messageId = "generated-result",
            chatId = "payer-chat",
            speakerType = SpeakerType.CHARACTER,
            speakerId = "character",
            speakerDisplayNameSnapshot = "Character",
            content = "A fake result; no provider was called.",
            orderIndex = 50L
        )
        val resultClientEventId = CoopProtocol.generationResultClientEventId(requestId)
        network.failNextOrderedClientEventId = resultClientEventId
        val lostAcknowledgement = runCatching {
            client.publishMessage(resultMessage, generationExecutionId = requestId)
        }.exceptionOrNull()
        assertTrue(lostAcknowledgement?.message?.contains("rejected", ignoreCase = true) == true)
        assertTrue(hostFixture.repository.getEventByClientId(invite.room.roomId, resultClientEventId) != null)
        assertEquals(null, clientFixture.database.messageDao().getMessageById(resultMessage.messageId))

        client.publishMessage(resultMessage, generationExecutionId = requestId)
        eventually("result ordering") {
            hostFixture.database.messageDao().getMessageById(resultMessage.messageId) != null
        }
        assertEquals(
            2,
            network.proposedOriginPayloads[resultClientEventId]?.size
        )
        assertEquals(
            1,
            network.proposedOriginPayloads[resultClientEventId]?.distinct()?.size
        )
        val completionMetadata = CoopGenerationMetadataPayload(
            messageId = resultMessage.messageId,
            requestedModelId = "mock/model",
            resolvedModelId = "mock/model",
            promptTokens = 12,
            completionTokens = 8,
            totalTokens = 20,
            reportedCost = 0.0,
            finishReason = "stop",
            provider = "mock",
            generationTimeMs = 25L
        )
        client.finishGeneration(
            "payer-chat",
            requestId,
            messageId = resultMessage.messageId,
            metadata = completionMetadata
        )
        eventually("completion ack") { hostFixture.repository.getGenerationLifecycle(invite.room.roomId) == null }
        client.finishGeneration(
            "payer-chat",
            requestId,
            messageId = resultMessage.messageId,
            metadata = completionMetadata
        )
        val completionClientEventId = "completion:$requestId"
        val canonicalCompletion = requireNotNull(
            clientFixture.repository.getEventByClientId(invite.room.roomId, completionClientEventId)
        ).payloadJson
        clientFixture.database.openHelper.writableDatabase.execSQL(
            "UPDATE coop_events SET payloadJson = ? WHERE roomId = ? AND clientEventId = ?",
            arrayOf(" $canonicalCompletion", invite.room.roomId, completionClientEventId)
        )
        val byteDifferentCompletionError = runCatching {
            client.finishGeneration(
                "payer-chat",
                requestId,
                messageId = resultMessage.messageId,
                metadata = completionMetadata
            )
        }.exceptionOrNull()
        assertTrue(
            byteDifferentCompletionError?.message?.contains("different content", ignoreCase = true) == true
        )
        assertNull(hostFixture.repository.getRoom(invite.room.roomId)?.activeGenerationRequestId)
        assertEquals(
            20,
            hostFixture.database.messageDao().getGenerationMetadata(resultMessage.messageId)?.totalTokens
        )
        assertTrue(
            hostFixture.repository.buildSnapshot(invite.room.roomId, 0).events.any {
                it.eventType == CoopEventType.GENERATION_APPROVED
            }
        )
        assertTrue(
            hostFixture.repository.buildSnapshot(invite.room.roomId, 0).events.any {
                it.eventType == CoopEventType.GENERATION_EXECUTING
            }
        )
        collectorScope.cancel()
        client.disconnect()
        host.disconnect()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun keepPartialRetriesTheExactViewModelMessageAfterCommittedResultLosesAcknowledgement() = runBlocking {
        val testMain = UnconfinedTestDispatcher()
        Dispatchers.setMain(testMain)
        val viewModels = TestViewModelOwner()
        val network = FakeCoopNetwork()
        val chatId = "partial-retry-chat"
        val characterId = "partial-character"
        val personaId = "partial-host-persona"
        val clientPersonaId = "partial-client-persona"
        val hostFixture = fixture(chatId, characterId)
        val clientFixture = fixture(chatId, characterId)
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val character = CharacterEntity(
            characterId = characterId,
            displayName = "Partial Character",
            characterName = "Partial Character",
            aliases = emptyList(),
            importSchemaVersion = 1,
            identityJson = null,
            appearanceJson = null,
            personalityJson = null,
            voiceJson = null,
            behaviorJson = null,
            backstoryJson = null,
            knowledgeJson = null,
            worldContextJson = null,
            writingRulesJson = null,
            examplesJson = null,
            authorNotesJson = null,
            extensionFieldsJson = null
        )
        val persona = PersonaEntity(
            personaId = personaId,
            displayName = "Partial Persona",
            personaName = "Partial Persona",
            aliases = emptyList(),
            importSchemaVersion = 1,
            identityJson = null,
            appearanceJson = null,
            personalityJson = null,
            roleplayProfileJson = null,
            backgroundJson = null,
            worldContextJson = null,
            privateNotesJson = null,
            extensionFieldsJson = null
        )
        val clientPersona = persona.copy(
            personaId = clientPersonaId,
            displayName = "Payer Persona",
            personaName = "Payer Persona"
        )
        suspend fun installLocalProfile(fixture: Fixture) {
            fixture.database.characterDao().insertCharacter(character)
            fixture.database.personaDao().insertPersona(persona)
            fixture.database.personaDao().insertPersona(clientPersona)
            fixture.database.chatDao().insertChatPersonaParticipant(
                ChatPersonaParticipantEntity(chatId = chatId, personaId = personaId, sortPosition = 0)
            )
            fixture.database.chatDao().insertChatPersonaParticipant(
                ChatPersonaParticipantEntity(chatId = chatId, personaId = clientPersonaId, sortPosition = 1)
            )
            fixture.database.chatDao().insertChatSettings(
                ChatSettingsEntity(chatId = chatId, selectedModelId = "mock/model")
            )
        }
        installLocalProfile(hostFixture)
        installLocalProfile(clientFixture)
        hostFixture.database.messageDao().insertMessage(
            MessageEntity(
                messageId = "partial-source",
                chatId = chatId,
                speakerType = SpeakerType.PERSONA,
                speakerId = personaId,
                speakerDisplayNameSnapshot = persona.displayName,
                content = "Please answer, then pause.",
                createdAt = 100L,
                orderIndex = 100L
            )
        )
        val host = manager(
            hostFixture.repository,
            network,
            MemoryRoomIdentityStore(),
            MemoryParticipantIdentityStore()
        )
        val client = manager(
            clientFixture.repository,
            network,
            MemoryRoomIdentityStore(),
            MemoryParticipantIdentityStore()
        )

        fun createViewModel(
            fixture: Fixture,
            manager: CoopSessionManager,
            provider: ModelProvider
        ): ChatDetailViewModel {
            val chatRepository = ChatRepository(fixture.database.chatDao(), fixture.database.messageDao())
            return viewModels.create(ChatDetailViewModel::class.java) {
                ChatDetailViewModel(
                    chatId = chatId,
                    chatRepository = chatRepository,
                    messageRepository = MessageRepository(fixture.database.messageDao()),
                    characterRepository = CharacterRepository(fixture.database.characterDao(), moshi),
                    personaRepository = PersonaRepository(fixture.database.personaDao(), moshi),
                    modelProvider = provider,
                    memoryRepository = MemoryRepository(fixture.database.memoryDao()),
                    coopSessionManager = manager
                )
            }
        }

        try {
            val hostViewModel = createViewModel(hostFixture, host, NeverStreamingProvider())
            eventually("host chat loaded") { hostViewModel.uiState.value is ChatUiState.Success }
            val invite = host.host(chatId, "Host", personaId, persona.displayName)
            client.join(
                chatId = chatId,
                roomId = invite.room.roomId,
                hostParticipantId = invite.room.hostParticipantId,
                hostAddress = "fake.lan",
                port = invite.room.hostPort,
                pairingCode = invite.pairingCode,
                hostPublicKey = invite.room.hostPublicKey,
                hostKeyFingerprint = invite.room.hostKeyFingerprint,
                participantDisplayName = "Payer",
                personaId = clientPersonaId,
                personaDisplayName = clientPersona.displayName
            )
            eventually("partial retry join") {
                client.state.value.room?.connectionState == CoopConnectionState.CONNECTED
            }
            val clientViewModel = createViewModel(clientFixture, client, PartialUntilCancelledProvider())
            eventually("client chat loaded") {
                val state = clientViewModel.uiState.value as? ChatUiState.Success
                state?.historyLoaded == true && state.messages.any { it.messageId == "partial-source" }
            }

            assertTrue(client.requestGeneration(chatId, characterId))
            eventually("partial request approval") {
                client.state.value.payerRequestStage == CoopGenerationStage.PENDING_APPROVAL
            }
            val requestId = requireNotNull(client.state.value.pendingPayerRequest).request.requestId
            client.approvePendingGeneration(requestId)
            eventually("partial provider started") {
                (clientViewModel.uiState.value as? ChatUiState.Success)?.isGenerating == true
            }
            clientViewModel.stopGeneration()
            eventually("partial awaits resolution") {
                (clientViewModel.uiState.value as? ChatUiState.Success)?.partialStopped == true
            }

            val resultClientEventId = CoopProtocol.generationResultClientEventId(requestId)
            network.failNextOrderedClientEventId = resultClientEventId
            clientViewModel.keepPartial()
            eventually("first Keep remains unresolved") {
                val state = clientViewModel.uiState.value as? ChatUiState.Success
                state?.partialStopped == true && !state.partialResolutionInProgress &&
                    network.proposedOriginPayloads[resultClientEventId]?.size == 1
            }
            assertTrue(
                hostFixture.repository.getEventByClientId(invite.room.roomId, resultClientEventId) != null
            )

            clientViewModel.keepPartial()
            eventually("second Keep resolves exact committed result") {
                val state = clientViewModel.uiState.value as? ChatUiState.Success
                state?.partialStopped == false &&
                    hostFixture.repository.getGenerationLifecycle(invite.room.roomId) == null
            }
            val attempts = requireNotNull(network.proposedOriginPayloads[resultClientEventId])
            assertEquals(2, attempts.size)
            assertEquals(1, attempts.distinct().size)
        } finally {
            runCatching { client.disconnect() }
            runCatching { host.disconnect() }
            viewModels.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun resumeHostingClearsStaleRemotePresenceBeforeAllowingReconnect() = runBlocking {
        val network = FakeCoopNetwork()
        val hostFixture = fixture("resume-chat")
        val clientFixture = fixture("resume-chat")
        val hostKeys = MemoryRoomIdentityStore()
        val host = manager(
            hostFixture.repository,
            network,
            hostKeys,
            MemoryParticipantIdentityStore()
        )
        val client = manager(
            clientFixture.repository,
            network,
            MemoryRoomIdentityStore(),
            MemoryParticipantIdentityStore()
        )
        val invite = host.host("resume-chat", "Host", "persona-host", "Host Persona")
        client.join(
            chatId = "resume-chat",
            roomId = invite.room.roomId,
            hostParticipantId = invite.room.hostParticipantId,
            hostAddress = "fake.lan",
            port = invite.room.hostPort,
            pairingCode = invite.pairingCode,
            hostPublicKey = invite.room.hostPublicKey,
            hostKeyFingerprint = invite.room.hostKeyFingerprint,
            participantDisplayName = "Client",
            personaId = "persona-client",
            personaDisplayName = "Client Persona"
        )
        eventually("resume test join") { client.state.value.room?.connectionState == CoopConnectionState.CONNECTED }
        val remoteId = requireNotNull(client.state.value.room).localParticipantId

        host.disconnect()
        hostFixture.database.coopDao().setParticipantConnected(remoteId, true, System.currentTimeMillis())
        val rotatedInvite = host.resumeHosting(invite.room.roomId)

        assertTrue(rotatedInvite != invite.pairingCode)
        assertFalse(hostFixture.repository.getParticipant(remoteId)?.isConnected == true)
        assertTrue(hostFixture.repository.getParticipant(invite.room.localParticipantId)?.isConnected == true)
        client.join(
            chatId = "resume-chat",
            roomId = invite.room.roomId,
            hostParticipantId = invite.room.hostParticipantId,
            hostAddress = "fake.lan",
            port = invite.room.hostPort,
            pairingCode = rotatedInvite,
            hostPublicKey = invite.room.hostPublicKey,
            hostKeyFingerprint = invite.room.hostKeyFingerprint,
            participantDisplayName = "Client",
            personaId = "persona-client",
            personaDisplayName = "Client Persona"
        )
        eventually("resume reconnect") { hostFixture.repository.getParticipant(remoteId)?.isConnected == true }

        client.disconnect()
        host.disconnect()
    }

    @Test
    fun failedJoinAcceptedDeliveryLeavesNoReservationAndRetriesAfterHostRestart() = runBlocking {
        val network = FakeCoopNetwork().apply { failNextAdmissionDelivery = true }
        val hostFixture = fixture("failed-admission-chat")
        val clientFixture = fixture("failed-admission-chat")
        val hostKeys = MemoryRoomIdentityStore()
        val host = manager(
            hostFixture.repository,
            network,
            hostKeys,
            MemoryParticipantIdentityStore()
        )
        val client = manager(
            clientFixture.repository,
            network,
            MemoryRoomIdentityStore(),
            MemoryParticipantIdentityStore()
        )
        val invite = host.host("failed-admission-chat", "Host", "persona-host", "Host Persona")
        val sequenceBeforeFailure = requireNotNull(hostFixture.repository.getRoom(invite.room.roomId))
            .lastAppliedSequence

        client.join(
            chatId = "failed-admission-chat",
            roomId = invite.room.roomId,
            hostParticipantId = invite.room.hostParticipantId,
            hostAddress = "fake.lan",
            port = invite.room.hostPort,
            pairingCode = invite.pairingCode,
            hostPublicKey = invite.room.hostPublicKey,
            hostKeyFingerprint = invite.room.hostKeyFingerprint,
            participantDisplayName = "Pending Client",
            personaId = "persona-client",
            personaDisplayName = "Client Persona"
        )
        eventually("failed admission closes the client") {
            client.state.value.room?.connectionState == CoopConnectionState.SUSPENDED_HOST_OFFLINE
        }
        assertEquals(
            listOf(invite.room.hostParticipantId),
            hostFixture.repository.getParticipants(invite.room.roomId).map { it.participantId }
        )
        assertEquals(
            listOf("persona-host"),
            hostFixture.repository.getOwnerships(invite.room.roomId).map { it.personaId }
        )
        assertEquals(
            sequenceBeforeFailure,
            hostFixture.repository.getRoom(invite.room.roomId)?.lastAppliedSequence
        )

        host.disconnect()
        val rotatedInvite = host.resumeHosting(invite.room.roomId)
        client.join(
            chatId = "failed-admission-chat",
            roomId = invite.room.roomId,
            hostParticipantId = invite.room.hostParticipantId,
            hostAddress = "fake.lan",
            port = invite.room.hostPort,
            pairingCode = rotatedInvite,
            hostPublicKey = invite.room.hostPublicKey,
            hostKeyFingerprint = invite.room.hostKeyFingerprint,
            participantDisplayName = "Pending Client",
            personaId = "persona-client",
            personaDisplayName = "Client Persona"
        )
        val retriedParticipantId = requireNotNull(client.state.value.room).localParticipantId
        eventually("admission succeeds after restart") {
            hostFixture.repository.getParticipant(retriedParticipantId)?.isConnected == true
        }
        assertEquals(2, hostFixture.repository.getParticipants(invite.room.roomId).size)
        assertEquals(2, hostFixture.repository.getOwnerships(invite.room.roomId).size)

        client.disconnect()
        host.disconnect()
    }

    @Test
    fun stagedAdmissionSnapshotOverLimitIsPagedBeforeMembershipCommit() = runBlocking {
        val chatId = "snapshot-boundary-chat"
        val network = FakeCoopNetwork()
        val hostFixture = fixture(chatId)
        val clientFixture = fixture(chatId)
        repeat(6) { index ->
            hostFixture.database.messageDao().insertMessage(
                MessageEntity(
                    messageId = "boundary-seed-$index",
                    chatId = chatId,
                    speakerType = SpeakerType.PERSONA,
                    speakerId = "persona-host",
                    speakerDisplayNameSnapshot = "Host Persona",
                    content = "x".repeat(CoopProtocol.MAX_MESSAGE_CHARS),
                    orderIndex = index.toLong()
                )
            )
        }
        val host = manager(
            hostFixture.repository,
            network,
            MemoryRoomIdentityStore(),
            MemoryParticipantIdentityStore()
        )
        val client = manager(
            clientFixture.repository,
            network,
            MemoryRoomIdentityStore(),
            MemoryParticipantIdentityStore()
        )
        val invite = host.host(chatId, "Host", "persona-host", "Host Persona")
        val roomBeforeTail = requireNotNull(hostFixture.repository.getRoom(invite.room.roomId))
        val baseSnapshot = hostFixture.repository.buildSnapshot(invite.room.roomId, 0)
        assertEquals(7, baseSnapshot.events.size)
        assertFalse(baseSnapshot.hasMoreEvents)

        val fixedTime = System.currentTimeMillis()
        fun candidateSnapshot(contentLength: Int) = MessageEntity(
            messageId = "boundary-tail",
            chatId = chatId,
            speakerType = SpeakerType.PERSONA,
            speakerId = "persona-host",
            speakerDisplayNameSnapshot = "Host Persona",
            content = "y".repeat(contentLength),
            createdAt = fixedTime,
            orderIndex = 6L
        ).let { message ->
            val payloadJson = hostFixture.repository.codec.messageToJson(
                com.example.domain.coop.CoopMessagePayload.from(message)
            )
            val event = CoopEventEntity(
                eventId = "e".repeat(36),
                roomId = invite.room.roomId,
                sequence = roomBeforeTail.nextHostSequence,
                clientEventId = "boundary-tail-event",
                originParticipantId = invite.room.hostParticipantId,
                eventType = CoopEventType.MESSAGE_UPSERT,
                payloadJson = payloadJson,
                createdAt = fixedTime
            )
            baseSnapshot.copy(
                lastSequence = event.sequence,
                events = baseSnapshot.events + com.example.domain.coop.CoopEventDto.from(event),
                hasMoreEvents = false
            )
        }
        fun encodedBytes(snapshot: com.example.domain.coop.CoopSnapshotPayload): Int =
            hostFixture.repository.codec.snapshotToJson(snapshot).toByteArray(Charsets.UTF_8).size

        var low = 0
        var high = CoopProtocol.MAX_MESSAGE_CHARS
        var bestLength = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (encodedBytes(candidateSnapshot(middle)) <= CoopProtocol.MAX_SNAPSHOT_JSON_BYTES) {
                bestLength = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        assertTrue(bestLength >= 0)
        val persistedCandidate = candidateSnapshot(bestLength)
        val stagedCandidate = persistedCandidate.copy(
            participants = persistedCandidate.participants + CoopParticipantDto.from(
                CoopParticipantEntity(
                    participantId = "boundary-client",
                    roomId = invite.room.roomId,
                    deviceId = "d".repeat(64),
                    displayName = "Boundary Client",
                    joinedAt = fixedTime,
                    lastSeenAt = fixedTime,
                    sortPosition = 1
                )
            ),
            personaOwnerships = persistedCandidate.personaOwnerships + CoopPersonaOwnershipDto.from(
                CoopPersonaOwnershipEntity(
                    roomId = invite.room.roomId,
                    personaId = "boundary-persona",
                    participantId = "boundary-client",
                    personaDisplayNameSnapshot = "Boundary Persona",
                    sortPosition = 1
                )
            )
        )
        assertTrue(encodedBytes(persistedCandidate) <= CoopProtocol.MAX_SNAPSHOT_JSON_BYTES)
        assertTrue(encodedBytes(stagedCandidate) > CoopProtocol.MAX_SNAPSHOT_JSON_BYTES)

        val tailEvent = persistedCandidate.events.last().toEntity()
        hostFixture.repository.appendHostEvent(
            invite.room.roomId,
            tailEvent.clientEventId,
            tailEvent.originParticipantId,
            tailEvent.eventType,
            tailEvent.payloadJson
        )
        val persistedSnapshot = hostFixture.repository.buildSnapshot(invite.room.roomId, 0)
        assertFalse(persistedSnapshot.hasMoreEvents)
        assertTrue(encodedBytes(persistedSnapshot) <= CoopProtocol.MAX_SNAPSHOT_JSON_BYTES)
        val sequenceBeforeJoin = requireNotNull(hostFixture.repository.getRoom(invite.room.roomId))
            .lastAppliedSequence

        client.join(
            chatId = chatId,
            roomId = invite.room.roomId,
            hostParticipantId = invite.room.hostParticipantId,
            hostAddress = "fake.lan",
            port = invite.room.hostPort,
            pairingCode = invite.pairingCode,
            hostPublicKey = invite.room.hostPublicKey,
            hostKeyFingerprint = invite.room.hostKeyFingerprint,
            participantId = "boundary-client",
            participantDisplayName = "Boundary Client",
            personaId = "boundary-persona",
            personaDisplayName = "Boundary Persona"
        )
        eventually("bounded staged admission connects") {
            client.state.value.room?.connectionState == CoopConnectionState.CONNECTED
        }
        eventually("bounded admission resync reaches the final pre-join event") {
            clientFixture.database.messageDao().getMessageById("boundary-tail") != null
        }
        val acceptedEnvelope = network.serializedEnvelopes
            .map(hostFixture.repository.codec::wireFromJson)
            .single { it.kind == CoopWireKind.JOIN_ACCEPTED }
        val acceptedSnapshot = hostFixture.repository.codec.snapshotFromJson(acceptedEnvelope.payloadJson)
        assertTrue(acceptedEnvelope.payloadJson.toByteArray(Charsets.UTF_8).size <= CoopProtocol.MAX_SNAPSHOT_JSON_BYTES)
        assertTrue(acceptedSnapshot.hasMoreEvents)
        assertEquals(7, acceptedSnapshot.events.size)
        assertTrue(acceptedSnapshot.participants.any { it.participantId == "boundary-client" })
        assertTrue(acceptedSnapshot.personaOwnerships.any { it.personaId == "boundary-persona" })
        assertEquals(
            setOf(invite.room.hostParticipantId, "boundary-client"),
            hostFixture.repository.getParticipants(invite.room.roomId).map { it.participantId }.toSet()
        )
        assertEquals(
            setOf("persona-host", "boundary-persona"),
            hostFixture.repository.getOwnerships(invite.room.roomId).map { it.personaId }.toSet()
        )
        assertEquals(
            sequenceBeforeJoin + 3,
            hostFixture.repository.getRoom(invite.room.roomId)?.lastAppliedSequence
        )

        client.disconnect()
        host.disconnect()
    }

    @Test
    fun resyncFloodIsRateLimitedAndDisconnectsTheAcceptedMember() = runBlocking {
        val network = FakeCoopNetwork()
        val hostFixture = fixture("resync-rate-chat")
        val clientFixture = fixture("resync-rate-chat")
        val host = manager(
            hostFixture.repository,
            network,
            MemoryRoomIdentityStore(),
            MemoryParticipantIdentityStore()
        )
        val client = manager(
            clientFixture.repository,
            network,
            MemoryRoomIdentityStore(),
            MemoryParticipantIdentityStore()
        )
        val invite = host.host("resync-rate-chat", "Host", "persona-host", "Host Persona")
        client.join(
            chatId = "resync-rate-chat",
            roomId = invite.room.roomId,
            hostParticipantId = invite.room.hostParticipantId,
            hostAddress = "fake.lan",
            port = invite.room.hostPort,
            pairingCode = invite.pairingCode,
            hostPublicKey = invite.room.hostPublicKey,
            hostKeyFingerprint = invite.room.hostKeyFingerprint,
            participantDisplayName = "Flood Client",
            personaId = "persona-client",
            personaDisplayName = "Client Persona"
        )
        eventually("resync rate client join") {
            client.state.value.room?.connectionState == CoopConnectionState.CONNECTED
        }
        val participantId = requireNotNull(client.state.value.room).localParticipantId
        repeat(5) { index ->
            network.injectAcceptedClientEnvelope(
                participantId,
                CoopWireEnvelope(
                    kind = CoopWireKind.RESYNC_REQUEST,
                    roomId = invite.room.roomId,
                    senderParticipantId = participantId,
                    clientMessageId = "resync-flood-$index",
                    payloadJson = hostFixture.repository.codec.resyncToJson(CoopResyncRequestPayload(0))
                )
            )
        }
        eventually("resync flood disconnect") {
            hostFixture.repository.getParticipant(participantId)?.isConnected == false
        }

        client.disconnect()
        host.disconnect()
    }

    @Test
    fun longInitialResyncMakesMonotonicProgressBeyondSeventyTwoEvents() = runBlocking {
        val network = FakeCoopNetwork()
        val hostFixture = fixture("long-resync-chat")
        val clientFixture = fixture("long-resync-chat")
        repeat(100) { index ->
            hostFixture.database.messageDao().insertMessage(
                MessageEntity(
                    messageId = "long-seed-$index",
                    chatId = "long-resync-chat",
                    speakerType = SpeakerType.PERSONA,
                    speakerId = "persona-host",
                    speakerDisplayNameSnapshot = "Host Persona",
                    content = "Seed $index",
                    orderIndex = index.toLong()
                )
            )
        }
        val host = manager(
            hostFixture.repository,
            network,
            MemoryRoomIdentityStore(),
            MemoryParticipantIdentityStore()
        )
        val client = manager(
            clientFixture.repository,
            network,
            MemoryRoomIdentityStore(),
            MemoryParticipantIdentityStore()
        )
        val invite = host.host("long-resync-chat", "Host", "persona-host", "Host Persona")
        client.join(
            chatId = "long-resync-chat",
            roomId = invite.room.roomId,
            hostParticipantId = invite.room.hostParticipantId,
            hostAddress = "fake.lan",
            port = invite.room.hostPort,
            pairingCode = invite.pairingCode,
            hostPublicKey = invite.room.hostPublicKey,
            hostKeyFingerprint = invite.room.hostKeyFingerprint,
            participantDisplayName = "Catch-up Client",
            personaId = "persona-client",
            personaDisplayName = "Client Persona"
        )

        eventually("long monotonic resync", timeoutMs = 8_000) {
            clientFixture.database.messageDao().getMessageById("long-seed-99") != null
        }
        assertEquals(CoopConnectionState.CONNECTED, client.state.value.room?.connectionState)
        assertEquals(100, clientFixture.database.messageDao().getMessagesForChatOnce("long-resync-chat").size)

        client.disconnect()
        host.disconnect()
    }

    @Test
    fun malformedAndCombinedPingPongTrafficDisconnectsTheAcceptedMember() = runBlocking {
        val network = FakeCoopNetwork()
        val hostFixture = fixture("pong-rate-chat")
        val clientFixture = fixture("pong-rate-chat")
        val host = manager(hostFixture.repository, network, MemoryRoomIdentityStore(), MemoryParticipantIdentityStore())
        val client = manager(clientFixture.repository, network, MemoryRoomIdentityStore(), MemoryParticipantIdentityStore())
        val invite = host.host("pong-rate-chat", "Host", "persona-host", "Host Persona")
        client.join(
            chatId = "pong-rate-chat",
            roomId = invite.room.roomId,
            hostParticipantId = invite.room.hostParticipantId,
            hostAddress = "fake.lan",
            port = invite.room.hostPort,
            pairingCode = invite.pairingCode,
            hostPublicKey = invite.room.hostPublicKey,
            hostKeyFingerprint = invite.room.hostKeyFingerprint,
            participantDisplayName = "Pong Client",
            personaId = "persona-client",
            personaDisplayName = "Client Persona"
        )
        eventually("pong rate client join") {
            client.state.value.room?.connectionState == CoopConnectionState.CONNECTED
        }
        val participantId = requireNotNull(client.state.value.room).localParticipantId
        network.injectAcceptedClientEnvelope(
            participantId,
            CoopWireEnvelope(
                kind = CoopWireKind.PING,
                roomId = invite.room.roomId,
                senderParticipantId = participantId,
                clientMessageId = "malformed-ping",
                payloadJson = "{}"
            )
        )
        eventually("malformed ping disconnect") {
            hostFixture.repository.getParticipant(participantId)?.isConnected == false
        }
        client.join(
            chatId = "pong-rate-chat",
            roomId = invite.room.roomId,
            hostParticipantId = invite.room.hostParticipantId,
            hostAddress = "fake.lan",
            port = invite.room.hostPort,
            pairingCode = invite.pairingCode,
            hostPublicKey = invite.room.hostPublicKey,
            hostKeyFingerprint = invite.room.hostKeyFingerprint,
            participantDisplayName = "Pong Client",
            personaId = "persona-client",
            personaDisplayName = "Client Persona"
        )
        eventually("pong rate client reconnect") {
            hostFixture.repository.getParticipant(participantId)?.isConnected == true
        }
        repeat(17) { index ->
            network.injectAcceptedClientEnvelope(
                participantId,
                CoopWireEnvelope(
                    kind = if (index % 2 == 0) CoopWireKind.PING else CoopWireKind.PONG,
                    roomId = invite.room.roomId,
                    senderParticipantId = participantId,
                    clientMessageId = "pong-flood-$index"
                )
            )
        }
        eventually("pong flood disconnect") {
            hostFixture.repository.getParticipant(participantId)?.isConnected == false
        }

        client.disconnect()
        host.disconnect()
    }

    @Test
    fun manualDisconnectClearsOutstandingResyncSoReconnectCanContinue() = runBlocking {
        val network = FakeCoopNetwork()
        val hostFixture = fixture("resync-reconnect-chat")
        val clientFixture = fixture("resync-reconnect-chat")
        repeat(100) { index ->
            hostFixture.database.messageDao().insertMessage(
                MessageEntity(
                    messageId = "reconnect-seed-$index",
                    chatId = "resync-reconnect-chat",
                    speakerType = SpeakerType.PERSONA,
                    speakerId = "persona-host",
                    speakerDisplayNameSnapshot = "Host Persona",
                    content = "Reconnect seed $index",
                    orderIndex = index.toLong()
                )
            )
        }
        val host = manager(hostFixture.repository, network, MemoryRoomIdentityStore(), MemoryParticipantIdentityStore())
        val memberKeys = MemoryParticipantIdentityStore()
        val client = manager(clientFixture.repository, network, MemoryRoomIdentityStore(), memberKeys)
        val invite = host.host("resync-reconnect-chat", "Host", "persona-host", "Host Persona")
        network.dropNextHostSnapshot = true
        client.join(
            chatId = "resync-reconnect-chat",
            roomId = invite.room.roomId,
            hostParticipantId = invite.room.hostParticipantId,
            hostAddress = "fake.lan",
            port = invite.room.hostPort,
            pairingCode = invite.pairingCode,
            hostPublicKey = invite.room.hostPublicKey,
            hostKeyFingerprint = invite.room.hostKeyFingerprint,
            participantDisplayName = "Reconnect Client",
            personaId = "persona-client",
            personaDisplayName = "Client Persona"
        )
        eventually("first snapshot page") {
            client.state.value.room?.connectionState == CoopConnectionState.CONNECTED &&
                clientFixture.database.messageDao().getMessagesForChatOnce("resync-reconnect-chat").isNotEmpty()
        }
        assertEquals(null, clientFixture.database.messageDao().getMessageById("reconnect-seed-99"))

        client.disconnect()
        val participantId = requireNotNull(client.state.value.room).localParticipantId
        eventually("reconnect participant marked offline") {
            hostFixture.repository.getParticipant(participantId)?.isConnected == false
        }
        client.join(
            chatId = "resync-reconnect-chat",
            roomId = invite.room.roomId,
            hostParticipantId = invite.room.hostParticipantId,
            hostAddress = "fake.lan",
            port = invite.room.hostPort,
            pairingCode = invite.pairingCode,
            hostPublicKey = invite.room.hostPublicKey,
            hostKeyFingerprint = invite.room.hostKeyFingerprint,
            participantDisplayName = "Reconnect Client",
            personaId = "persona-client",
            personaDisplayName = "Client Persona"
        )
        eventually("resync after reconnect", timeoutMs = 8_000) {
            clientFixture.database.messageDao().getMessageById("reconnect-seed-99") != null
        }

        client.disconnect()
        host.disconnect()
    }

    @Test
    fun concurrentHostStartsAreSingleFlightAndUnrelatedLeaveDoesNotStopActiveRoom() = runBlocking {
        val network = FakeCoopNetwork()
        val fixture = fixture("single-flight-chat")
        val manager = manager(
            fixture.repository,
            network,
            MemoryRoomIdentityStore(),
            MemoryParticipantIdentityStore()
        )
        val gate = CompletableDeferred<Unit>()
        network.hostStartGate = gate

        val firstStart = async {
            manager.host("single-flight-chat", "Host", "persona-host", "Host Persona")
        }
        eventually("first host start reaches transport") { network.hasActiveHost() }

        val secondStartError = runCatching {
            manager.host("single-flight-chat", "Host", "persona-host", "Host Persona")
        }.exceptionOrNull()
        assertTrue(secondStartError?.message?.contains("already in progress") == true)

        val backgroundStop = async { manager.stopForBackground() }
        delay(50)
        assertFalse(backgroundStop.isCompleted)

        gate.complete(Unit)
        val invite = firstStart.await()
        backgroundStop.await()
        assertEquals(invite.room.roomId, manager.state.value.room?.roomId)
        assertEquals(CoopConnectionState.DISCONNECTED, manager.state.value.room?.connectionState)
        assertFalse(network.hasActiveHost())

        manager.leaveRoom("unrelated-chat")
        assertEquals(invite.room.roomId, manager.state.value.room?.roomId)
        assertFalse(network.hasActiveHost())
    }

    @Test
    fun cancelledHostStartClosesTransportAndPersistsDisconnectedState() = runBlocking {
        val network = FakeCoopNetwork()
        val fixture = fixture("cancelled-host-chat")
        val manager = manager(
            fixture.repository,
            network,
            MemoryRoomIdentityStore(),
            MemoryParticipantIdentityStore()
        )
        val gate = CompletableDeferred<Unit>()
        network.hostStartGate = gate

        val start = async {
            manager.host("cancelled-host-chat", "Host", "persona-host", "Host Persona")
        }
        eventually("host socket attached before cancellation") { network.hasActiveHost() }
        start.cancel()
        runCatching { start.await() }

        assertFalse(network.hasActiveHost())
        val room = requireNotNull(fixture.repository.getRoomForChat("cancelled-host-chat"))
        assertEquals(CoopConnectionState.DISCONNECTED, room.connectionState)
        assertFalse(fixture.repository.getParticipant(room.localParticipantId)?.isConnected == true)
    }

    @Test
    fun cancellationWhileReplacingTransportStillStopsAndPersistsPreviousRoomOffline() = runBlocking {
        val network = FakeCoopNetwork()
        val fixture = fixture("replace-cancel-chat")
        val manager = manager(
            fixture.repository,
            network,
            MemoryRoomIdentityStore(),
            MemoryParticipantIdentityStore()
        )
        val invite = manager.host("replace-cancel-chat", "Host", "persona-host", "Host Persona")
        assertTrue(network.hasActiveHost())
        val stopGate = CompletableDeferred<Unit>()
        val stopStarted = CompletableDeferred<Unit>()
        network.transportStopGate = stopGate
        network.transportStopStarted = stopStarted

        val resume = async { manager.resumeHosting(invite.room.roomId) }
        stopStarted.await()
        resume.cancel()
        delay(50)
        assertFalse("non-cancellable transport cleanup must finish before cancellation returns", resume.isCompleted)
        stopGate.complete(Unit)
        runCatching { resume.await() }

        assertFalse(network.hasActiveHost())
        val room = requireNotNull(fixture.repository.getRoom(invite.room.roomId))
        assertEquals(CoopConnectionState.DISCONNECTED, room.connectionState)
        assertFalse(fixture.repository.getParticipant(room.localParticipantId)?.isConnected == true)
    }

    private fun fixture(chatId: String, characterId: String? = null): Fixture {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        databases += database
        runBlocking {
            database.chatDao().insertChat(ChatEntity(chatId, chatId, ChatType.GROUP))
            characterId?.let {
                database.chatDao().insertChatParticipant(ChatParticipantEntity(chatId, it))
            }
        }
        return Fixture(
            database,
            CoopRepository(database, Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build())
        )
    }

    private fun manager(
        repository: CoopRepository,
        network: FakeCoopNetwork,
        roomIdentityStore: CoopRoomIdentityStore,
        participantIdentityStore: CoopParticipantIdentityStore,
        autoAcceptJoinsForTests: Boolean = true
    ) = CoopSessionManager(
        repository = repository,
        deviceIdentity = CoopDeviceIdentity(context),
        roomIdentityStore = roomIdentityStore,
        participantIdentityStore = participantIdentityStore,
        networkingEnabled = { true },
        autoAcceptJoinsForTests = autoAcceptJoinsForTests,
        localLanAddresses = { listOf("192.168.50.10") },
        transportFactory = network::newTransport
    )

    private suspend fun eventually(
        label: String,
        timeoutMs: Long = 4_000,
        condition: suspend () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("$label was not met within ${timeoutMs}ms")
            }
            delay(10)
        }
    }

    private data class Fixture(val database: AppDatabase, val repository: CoopRepository)
}

private class MemoryRoomIdentityStore : CoopRoomIdentityStore {
    private val identities = ConcurrentHashMap<String, CoopRoomIdentity>()

    override fun getOrCreate(roomId: String): CoopRoomIdentity = identities.computeIfAbsent(roomId) {
        val pair = CoopSessionCrypto.generateSigningKeyPair()
        CoopRoomIdentity(pair, pair.public.encoded, CoopSessionCrypto.hostFingerprint(pair.public.encoded))
    }

    override fun get(roomId: String): CoopRoomIdentity? = identities[roomId]

    override fun delete(roomId: String) {
        identities.remove(roomId)
    }
}

private class MemoryParticipantIdentityStore : CoopParticipantIdentityStore {
    private val identities = ConcurrentHashMap<String, CoopRoomIdentity>()

    override fun getOrCreate(roomId: String, participantId: String): CoopRoomIdentity =
        identities.computeIfAbsent("$roomId:$participantId") {
            val pair = CoopSessionCrypto.generateSigningKeyPair()
            CoopRoomIdentity(pair, pair.public.encoded, CoopSessionCrypto.hostFingerprint(pair.public.encoded))
        }

    override fun get(roomId: String, participantId: String): CoopRoomIdentity? =
        identities["$roomId:$participantId"]

    override fun delete(roomId: String, participantId: String) {
        identities.remove("$roomId:$participantId")
    }
}

private class FakeCoopNetwork {
    private var host: FakeCoopTransport? = null
    private val pending = ConcurrentHashMap<String, FakeSecureLink>()
    private val accepted = ConcurrentHashMap<String, FakeSecureLink>()
    val serializedEnvelopes = CopyOnWriteArrayList<String>()
    val duplicateProposalEventTypes = ConcurrentHashMap.newKeySet<String>()
    val proposedOriginPayloads = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
    @Volatile var failNextAdmissionDelivery: Boolean = false
    @Volatile var dropNextHostSnapshot: Boolean = false
    @Volatile var tamperNextOrderedPersonaMessage: Boolean = false
    @Volatile var hostStartGate: CompletableDeferred<Unit>? = null
    @Volatile var transportStopGate: CompletableDeferred<Unit>? = null
    @Volatile var transportStopStarted: CompletableDeferred<Unit>? = null
    @Volatile var failNextOrderedClientEventId: String? = null

    fun newTransport(codec: CoopCodec): CoopTransport = FakeCoopTransport(this, codec)

    fun hasActiveHost(): Boolean = host != null

    suspend fun startHost(transport: FakeCoopTransport, security: CoopHostTransportSecurity) {
        check(host == null) { "A fake host is already listening" }
        host = transport
        transport.hostSecurity = security
        hostStartGate?.await()
        transport.emit(CoopTransportEvent.Listening(42424, listOf("fake.lan")))
    }

    suspend fun connect(
        client: FakeCoopTransport,
        hello: CoopWireEnvelope,
        security: CoopClientTransportSecurity
    ) {
        val currentHost = host ?: throw CoopProtocolException("Fake host is offline")
        require(hello.kind == CoopWireKind.HELLO) { "Fake transport requires HELLO first" }
        require(hello.senderParticipantId == security.participantId) { "Client binding mismatch" }
        val hostSecurity = currentHost.hostSecurity
            ?: throw CoopProtocolException("Fake host security is missing")
        val clientHandshake = CoopSessionCrypto.startClientHandshake(
            security.roomId,
            security.participantId,
            security.inviteSecret,
            security.pinnedHostPublicKey,
            security.participantIdentity.keyPair
        )
        val hostHandshake = CoopSessionCrypto.acceptClientHandshake(
            clientHandshake.hello,
            hostSecurity.inviteSecret,
            hostSecurity.identity.keyPair
        )
        val link = FakeSecureLink(
            client = client,
            hostChannel = hostHandshake.channel,
            clientChannel = CoopSessionCrypto.finishClientHandshake(clientHandshake, hostHandshake.hello),
            authenticatedParticipant = hostHandshake.authenticatedParticipant
        )
        check(pending.putIfAbsent(security.participantId, link) == null) {
            "Participant already has a pending fake connection"
        }
        client.clientSecurity = security
        client.hostTransport = currentHost
        val decodedHello = decodeClientFrame(currentHost, link, hello)
        client.emit(CoopTransportEvent.ConnectedToHost)
        currentHost.emit(
            CoopTransportEvent.Envelope(
                decodedHello,
                link.authenticatedParticipant
            )
        )
    }

    suspend fun accept(
        hostTransport: FakeCoopTransport,
        participantId: String,
        acceptedEnvelope: CoopWireEnvelope
    ) {
        check(hostTransport === host) { "Only the active fake host can accept" }
        require(acceptedEnvelope.kind == CoopWireKind.JOIN_ACCEPTED &&
            acceptedEnvelope.targetParticipantId == participantId) {
            "Fake admission requires targeted JOIN_ACCEPTED"
        }
        val link = pending[participantId]
            ?: throw CoopProtocolException("No pending fake participant")
        if (failNextAdmissionDelivery) {
            failNextAdmissionDelivery = false
            pending.remove(participantId, link)
            link.client.emit(CoopTransportEvent.HostDisconnected)
            throw CoopProtocolException("Injected JOIN_ACCEPTED delivery failure")
        }
        val decoded = decodeHostFrame(link, acceptedEnvelope)
        link.client.emit(CoopTransportEvent.Envelope(decoded))
        check(pending.remove(participantId, link)) { "Pending fake participant changed" }
        check(accepted.putIfAbsent(participantId, link) == null) {
            "Participant already has an accepted fake connection"
        }
    }

    suspend fun send(sender: FakeCoopTransport, envelope: CoopWireEnvelope) {
        if (sender === host) {
            if (envelope.kind == CoopWireKind.ORDERED_EVENT &&
                failNextOrderedClientEventId == envelope.clientMessageId
            ) {
                failNextOrderedClientEventId = null
                throw CoopProtocolException("Injected ordered acknowledgement loss")
            }
            val deliveredEnvelope = tamperHostEnvelopeIfRequested(sender, envelope)
            val target = envelope.targetParticipantId
            if (target != null) {
                if (envelope.kind == CoopWireKind.SNAPSHOT && dropNextHostSnapshot) {
                    dropNextHostSnapshot = false
                    return
                }
                val link = accepted[target]
                    ?: pending[target]?.takeIf { envelope.kind == CoopWireKind.JOIN_REJECTED }
                    ?: throw CoopProtocolException("Fake participant is not connected")
                link.client.emit(CoopTransportEvent.Envelope(decodeHostFrame(link, deliveredEnvelope)))
            } else {
                accepted.values.forEach { link ->
                    link.client.emit(CoopTransportEvent.Envelope(decodeHostFrame(link, deliveredEnvelope)))
                }
            }
        } else {
            val security = sender.clientSecurity
                ?: throw CoopProtocolException("Fake client security is missing")
            val link = accepted[security.participantId]
            require(link?.client === sender) {
                "Fake participant is not accepted"
            }
            val currentHost = host ?: throw CoopProtocolException("Fake host is offline")
            if (envelope.kind == CoopWireKind.PROPOSE_EVENT) {
                val proposed = sender.codec.proposedFromJson(envelope.payloadJson)
                proposedOriginPayloads.computeIfAbsent(envelope.clientMessageId) {
                    CopyOnWriteArrayList()
                }.add(proposed.payloadJson)
            }
            val authenticated = CoopTransportEvent.Envelope(
                decodeClientFrame(currentHost, requireNotNull(link), envelope),
                link.authenticatedParticipant
            )
            currentHost.emit(authenticated)
            if (envelope.kind == CoopWireKind.PROPOSE_EVENT) {
                val eventType = sender.codec.proposedFromJson(envelope.payloadJson).eventType
                if (duplicateProposalEventTypes.remove(eventType)) {
                    currentHost.emit(
                        CoopTransportEvent.Envelope(
                            decodeClientFrame(currentHost, link, envelope),
                            link.authenticatedParticipant
                        )
                    )
                }
            }
        }
    }

    private fun tamperHostEnvelopeIfRequested(
        hostTransport: FakeCoopTransport,
        envelope: CoopWireEnvelope
    ): CoopWireEnvelope {
        if (!tamperNextOrderedPersonaMessage || envelope.kind != CoopWireKind.ORDERED_EVENT) return envelope
        val event = hostTransport.codec.eventFromJson(envelope.payloadJson)
        if (event.eventType != CoopEventType.MESSAGE_UPSERT) return envelope
        val message = hostTransport.codec.messageFromJson(event.payloadJson)
        if (message.speakerType != SpeakerType.PERSONA.name) return envelope
        tamperNextOrderedPersonaMessage = false
        val changed = event.copy(
            payloadJson = hostTransport.codec.messageToJson(
                message.copy(content = "Host-mutated words.")
            )
        )
        return envelope.copy(payloadJson = hostTransport.codec.eventToJson(changed))
    }

    suspend fun disconnectPeer(hostTransport: FakeCoopTransport, participantId: String) {
        check(hostTransport === host) { "Only the active fake host can disconnect peers" }
        accepted.remove(participantId)?.let { link ->
            link.client.emit(CoopTransportEvent.HostDisconnected)
            hostTransport.emit(CoopTransportEvent.PeerDisconnected(participantId))
        }
    }

    suspend fun rejectPeer(hostTransport: FakeCoopTransport, participantId: String) {
        check(hostTransport === host) { "Only the active fake host can reject peers" }
        pending.remove(participantId)?.client?.emit(CoopTransportEvent.HostDisconnected)
    }

    suspend fun stop(transport: FakeCoopTransport) {
        transportStopStarted?.complete(Unit)
        transportStopGate?.await()
        if (transport === host) {
            host = null
            val clients = (pending.values + accepted.values).map { it.client }.distinct()
            pending.clear()
            accepted.clear()
            clients.forEach { it.emit(CoopTransportEvent.HostDisconnected) }
        } else {
            val participantId = transport.clientSecurity?.participantId ?: return
            pending[participantId]?.takeIf { it.client === transport }?.let { pending.remove(participantId, it) }
            val acceptedLink = accepted[participantId]
            if (acceptedLink?.client === transport && accepted.remove(participantId, acceptedLink)) {
                host?.emit(CoopTransportEvent.PeerDisconnected(participantId))
            }
        }
    }

    suspend fun injectAcceptedClientEnvelope(participantId: String, envelope: CoopWireEnvelope) {
        val currentHost = host ?: throw CoopProtocolException("Fake host is offline")
        val link = accepted[participantId]
            ?: throw CoopProtocolException("Fake participant is not accepted")
        currentHost.emit(
            CoopTransportEvent.Envelope(
                decodeClientFrame(currentHost, link, envelope),
                link.authenticatedParticipant
            )
        )
    }

    suspend fun injectHostEnvelope(participantId: String, envelope: CoopWireEnvelope) {
        val link = accepted[participantId]
            ?: throw CoopProtocolException("Fake participant is not accepted")
        link.client.emit(CoopTransportEvent.Envelope(decodeHostFrame(link, envelope)))
    }

    private fun decodeClientFrame(
        hostTransport: FakeCoopTransport,
        link: FakeSecureLink,
        envelope: CoopWireEnvelope
    ): CoopWireEnvelope {
        val json = link.client.codec.wireToJson(envelope)
        serializedEnvelopes += json
        val frame = link.clientChannel.encrypt(json.toByteArray(Charsets.UTF_8))
        val plaintext = link.hostChannel.decrypt(frame)
        return hostTransport.codec.wireFromJson(plaintext.toString(Charsets.UTF_8))
    }

    private fun decodeHostFrame(link: FakeSecureLink, envelope: CoopWireEnvelope): CoopWireEnvelope {
        val hostTransport = host ?: throw CoopProtocolException("Fake host is offline")
        val json = hostTransport.codec.wireToJson(envelope)
        serializedEnvelopes += json
        val frame = link.hostChannel.encrypt(json.toByteArray(Charsets.UTF_8))
        val plaintext = link.clientChannel.decrypt(frame)
        return link.client.codec.wireFromJson(plaintext.toString(Charsets.UTF_8))
    }
}

private data class FakeSecureLink(
    val client: FakeCoopTransport,
    val hostChannel: CoopSecureChannel,
    val clientChannel: CoopSecureChannel,
    val authenticatedParticipant: CoopAuthenticatedParticipant
)

private class FakeCoopTransport(
    private val network: FakeCoopNetwork,
    val codec: CoopCodec
) : CoopTransport {
    private val mutableEvents = MutableSharedFlow<CoopTransportEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<CoopTransportEvent> = mutableEvents.asSharedFlow()
    var clientSecurity: CoopClientTransportSecurity? = null
    var hostTransport: FakeCoopTransport? = null
    var hostSecurity: CoopHostTransportSecurity? = null

    override suspend fun startHost(
        bindAddress: String,
        port: Int,
        security: CoopHostTransportSecurity
    ) {
        network.startHost(this, security)
    }

    override suspend fun connect(
        host: String,
        port: Int,
        hello: CoopWireEnvelope,
        security: CoopClientTransportSecurity
    ) {
        network.connect(this, hello, security)
    }

    override suspend fun acceptPeer(participantId: String, acceptedEnvelope: CoopWireEnvelope) {
        network.accept(this, participantId, acceptedEnvelope)
    }

    override suspend fun send(envelope: CoopWireEnvelope) {
        network.send(this, envelope)
    }

    override suspend fun disconnectPeer(participantId: String) {
        network.disconnectPeer(this, participantId)
    }

    override suspend fun rejectPeer(participantId: String) {
        network.rejectPeer(this, participantId)
    }

    override suspend fun stop() {
        network.stop(this)
    }

    suspend fun emit(event: CoopTransportEvent) {
        mutableEvents.emit(event)
    }
}

private class NeverStreamingProvider : ModelProvider {
    override suspend fun getModels(): List<OpenRouterModel> = emptyList()
    override suspend fun getEndpoints(modelId: String): List<ProviderEndpoint> = emptyList()
    override fun streamResponse(
        messages: List<RoleplayMessage>,
        options: GenerationOptions
    ): Flow<StreamEvent> = flow { awaitCancellation() }
}

private class PartialUntilCancelledProvider : ModelProvider {
    override suspend fun getModels(): List<OpenRouterModel> = emptyList()
    override suspend fun getEndpoints(modelId: String): List<ProviderEndpoint> = emptyList()
    override fun streamResponse(
        messages: List<RoleplayMessage>,
        options: GenerationOptions
    ): Flow<StreamEvent> = flow {
        emit(StreamEvent.Content("A retained partial reply."))
        awaitCancellation()
    }
}
