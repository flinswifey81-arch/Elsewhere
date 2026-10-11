package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ChatEntity
import com.example.data.model.ChatType
import com.example.data.model.ChatParticipantEntity
import com.example.data.model.CoopEventType
import com.example.domain.coop.CoopDeviceIdentity
import com.example.domain.coop.CoopCodec
import com.example.domain.coop.CoopGenerationRequestPayload
import com.example.domain.coop.CoopWireEnvelope
import com.example.domain.coop.CoopSessionManager
import com.example.domain.coop.CoopSessionCrypto
import com.example.domain.coop.CoopHostTransportSecurity
import com.example.domain.coop.CoopInviteBundle
import com.example.domain.coop.CoopClientTransportSecurity
import com.example.domain.coop.CoopRoomIdentity
import com.example.domain.coop.LanCoopTransport
import com.example.domain.repository.CoopRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CoopSecurityGateTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: CoopRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = CoopRepository(
            database,
            Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun disabledBuildCannotCreateRoomOrOpenListener() = runBlocking {
        database.chatDao().insertChat(ChatEntity("chat", "Room", ChatType.GROUP))
        var transportFactoryCalls = 0
        val manager = CoopSessionManager(
            repository = repository,
            deviceIdentity = CoopDeviceIdentity(context),
            networkingEnabled = { false },
            transportFactory = {
                transportFactoryCalls++
                error("Transport must not be constructed while networking is disabled")
            }
        )

        val error = runCatching {
            manager.host("chat", "Host", "persona", "Persona")
        }.exceptionOrNull()

        assertTrue(error?.message?.contains("disabled") == true)
        assertEquals(0, transportFactoryCalls)
        assertNull(repository.getRoomForChat("chat"))
    }

    @Test
    fun joinResumeAndGenerationRemainBlockedWithoutTransportOrActiveRequest() = runBlocking {
        database.chatDao().insertChat(ChatEntity("join-chat", "Join", ChatType.GROUP))
        var transportFactoryCalls = 0
        val manager = CoopSessionManager(
            repository = repository,
            deviceIdentity = CoopDeviceIdentity(context),
            networkingEnabled = { false },
            transportFactory = {
                transportFactoryCalls++
                error("Transport must remain unreachable")
            }
        )
        assertTrue(runCatching {
            manager.join(
                chatId = "join-chat",
                roomId = "room",
                hostParticipantId = "host",
                hostAddress = "127.0.0.1",
                port = 42424,
                pairingCode = "unused",
                participantDisplayName = "Client",
                personaId = "persona-client",
                personaDisplayName = "Client Persona"
            )
        }.exceptionOrNull()?.message?.contains("disabled") == true)
        assertNull(repository.getRoomForChat("join-chat"))

        database.chatDao().insertChat(ChatEntity("host-chat", "Host", ChatType.GROUP))
        val invite = repository.createHostRoom(
            "host-chat", "room-device", "Host", "persona-host", "Host Persona"
        )
        assertTrue(runCatching { manager.resumeHosting(invite.room.roomId) }.isFailure)
        assertTrue(runCatching { manager.requestGeneration("host-chat", "character") }.isFailure)
        assertNull(repository.getRoom(invite.room.roomId)?.activeGenerationRequestId)
        assertEquals(0, transportFactoryCalls)
    }

    @Test
    fun deviceIdentityUsesStableRoomScopedPseudonyms() {
        val identity = CoopDeviceIdentity(context)
        val first = identity.roomScopedId("room-a")
        assertEquals(first, identity.roomScopedId("room-a"))
        assertNotEquals(first, identity.roomScopedId("room-b"))
        assertTrue(first.length <= 32)
    }

    @Test
    fun directTransportCannotOpenListenerWhileGateIsDisabled() = runBlocking {
        val codec = CoopCodec(Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build())
        val transport = LanCoopTransport(codec, networkingEnabled = { false })
        val envelope = CoopWireEnvelope(
            kind = "HELLO",
            roomId = "room",
            senderParticipantId = "participant",
            clientMessageId = "message"
        )
        val pair = CoopSessionCrypto.generateSigningKeyPair()
        val identity = CoopRoomIdentity(
            pair,
            pair.public.encoded,
            CoopSessionCrypto.hostFingerprint(pair.public.encoded)
        )
        val invite = CoopSessionCrypto.newInviteSecret()
        assertTrue(runCatching {
            transport.startHost(
                "192.168.1.10",
                42424,
                CoopHostTransportSecurity("room", "host", invite, identity)
            )
        }.exceptionOrNull()?.message?.contains("disabled") == true)
        assertTrue(runCatching {
            transport.connect(
                "127.0.0.1",
                42424,
                envelope,
                CoopClientTransportSecurity(
                    "room",
                    "participant",
                    "host",
                    invite,
                    pair.public.encoded,
                    identity
                )
            )
        }.exceptionOrNull()?.message?.contains("disabled") == true)
        assertTrue(runCatching { transport.send(envelope) }.exceptionOrNull()?.message?.contains("disabled") == true)
        assertTrue(runCatching { transport.disconnectPeer("participant") }.exceptionOrNull()?.message?.contains("disabled") == true)
        assertTrue(runCatching { transport.rejectPeer("participant") }.exceptionOrNull()?.message?.contains("disabled") == true)
    }

    @Test
    fun generationWirePayloadContainsFingerprintButNoCredentialOrPrivateContextFields() {
        val codec = CoopCodec(Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build())
        val json = codec.generationToJson(
            CoopGenerationRequestPayload(
                requestId = "request",
                initiatorParticipantId = "initiator",
                payerParticipantId = "payer",
                characterId = "character",
                operation = "NORMAL",
                targetMessageId = null,
                requestedAt = 1L,
                providerRoutingMode = "PREFER",
                providerEndpoint = "example/provider",
                contextFingerprint = "sha256-only"
            )
        )
        assertTrue(json.contains("sha256-only"))
        assertTrue(json.contains("PREFER"))
        assertTrue(json.contains("example/provider"))
        listOf("apiKey", "authorization", "encrypted", "privateNotes", "authorNotes", "lorebookContent")
            .forEach { forbidden -> assertFalse(json.contains(forbidden, ignoreCase = true)) }
    }

    @Test
    fun versionedInviteBundleRoundTripsAsOneBoundedSensitiveValue() {
        val codec = CoopCodec(Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build())
        val pair = CoopSessionCrypto.generateSigningKeyPair()
        val encodedKey = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(pair.public.encoded)
        val fingerprint = CoopSessionCrypto.hostFingerprint(pair.public.encoded)
        val secret = CoopSessionCrypto.newInviteSecret()
        val bundle = CoopInviteBundle(
            hostAddress = "192.168.1.40",
            port = 42424,
            roomId = "room",
            hostParticipantId = "host",
            inviteSecret = secret,
            hostPublicKey = encodedKey,
            hostKeyFingerprint = fingerprint
        )

        val text = codec.inviteBundleToText(bundle)

        assertTrue(text.startsWith("ELSEWHERE-COOP-1:"))
        assertFalse(text.contains(secret))
        assertEquals(bundle, codec.inviteBundleFromText(text))
        assertTrue(runCatching { codec.inviteBundleFromText("not-an-invite") }.isFailure)
    }

    @Test
    fun inviteBundleRejectsPublicLoopbackHostnameAndMalformedAddresses() {
        val codec = CoopCodec(Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build())
        val pair = CoopSessionCrypto.generateSigningKeyPair()
        val encodedKey = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(pair.public.encoded)
        val fingerprint = CoopSessionCrypto.hostFingerprint(pair.public.encoded)
        val secret = CoopSessionCrypto.newInviteSecret()
        fun invite(address: String) = CoopInviteBundle(
            hostAddress = address,
            port = 42424,
            roomId = "room",
            hostParticipantId = "host",
            inviteSecret = secret,
            hostPublicKey = encodedKey,
            hostKeyFingerprint = fingerprint
        )

        listOf("8.8.8.8", "127.0.0.1", "169.254.1.2", "224.0.0.1", "example.com", "0.0.0.0", "192.168.1.999")
            .forEach { address ->
                assertTrue("Expected rejection for $address", runCatching {
                    codec.inviteBundleToText(invite(address))
                }.isFailure)
            }
        listOf("10.0.0.5", "172.16.0.1", "172.31.255.254", "192.168.50.4")
            .forEach { address ->
                assertTrue(codec.inviteBundleToText(invite(address)).startsWith("ELSEWHERE-COOP-1:"))
            }
    }

    @Test
    fun oneDurableExecutionCanBeClaimedByOnlyOneViewModelConsumer() = runBlocking {
        val manager = CoopSessionManager(repository, CoopDeviceIdentity(context))

        val claims = coroutineScope {
            List(32) {
                async(Dispatchers.Default) {
                    manager.tryClaimGenerationExecution("execution-one")
                }
            }.awaitAll()
        }
        assertEquals(1, claims.count { it })
        assertFalse(manager.tryClaimGenerationExecution("execution-one"))
        assertTrue(manager.tryClaimGenerationExecution("execution-two"))
    }

    @Test
    fun persistedAssignedGenerationRestoresAsPendingApprovalInsteadOfAutoExecuting() = runBlocking {
        database.chatDao().insertChat(ChatEntity("approval-chat", "Approval", ChatType.GROUP))
        val invite = repository.createHostRoom(
            "approval-chat", "device", "Host", "persona", "Persona"
        )
        database.chatDao().insertChatParticipant(ChatParticipantEntity("approval-chat", "character"))
        val request = CoopGenerationRequestPayload(
            requestId = "approval-request",
            initiatorParticipantId = invite.participant.participantId,
            payerParticipantId = invite.participant.participantId,
            characterId = "character",
            operation = "NORMAL",
            targetMessageId = null,
            requestedAt = System.currentTimeMillis(),
            modelId = "mock/model",
            contextFingerprint = "a".repeat(64)
        )
        repository.appendHostEvent(
            invite.room.roomId,
            request.requestId,
            invite.participant.participantId,
            CoopEventType.GENERATION_REQUESTED,
            repository.codec.generationToJson(request)
        )
        val manager = CoopSessionManager(repository, CoopDeviceIdentity(context))

        manager.load("approval-chat")

        assertEquals("approval-request", manager.state.value.pendingPayerRequest?.request?.requestId)
        assertTrue(runCatching { manager.approvePendingGeneration("approval-request") }.isFailure)
        assertEquals("approval-request", repository.getRoom(invite.room.roomId)?.activeGenerationRequestId)
    }

    @Test
    fun stalePersistedGenerationIsFailedByHostReconciliation() = runBlocking {
        database.chatDao().insertChat(ChatEntity("timeout-chat", "Timeout", ChatType.GROUP))
        val invite = repository.createHostRoom(
            "timeout-chat", "device", "Host", "persona", "Persona"
        )
        database.chatDao().insertChatParticipant(ChatParticipantEntity("timeout-chat", "character"))
        val request = CoopGenerationRequestPayload(
            requestId = "stale-request",
            initiatorParticipantId = invite.participant.participantId,
            payerParticipantId = invite.participant.participantId,
            characterId = "character",
            operation = "NORMAL",
            targetMessageId = null,
            requestedAt = System.currentTimeMillis() - com.example.domain.coop.CoopProtocol.GENERATION_APPROVAL_TIMEOUT_MS - 1,
            modelId = "mock/model",
            contextFingerprint = "b".repeat(64)
        )
        repository.appendHostEvent(
            invite.room.roomId,
            request.requestId,
            invite.participant.participantId,
            CoopEventType.GENERATION_REQUESTED,
            repository.codec.generationToJson(request)
        )
        val manager = CoopSessionManager(repository, CoopDeviceIdentity(context))

        manager.load("timeout-chat")

        assertNull(repository.getRoom(invite.room.roomId)?.activeGenerationRequestId)
        assertNull(manager.state.value.pendingPayerRequest)
        assertTrue(
            repository.buildSnapshot(invite.room.roomId, 0).events
                .any { it.eventType == CoopEventType.GENERATION_FAILED }
        )
    }
}
