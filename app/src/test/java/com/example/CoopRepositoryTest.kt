package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ChatEntity
import com.example.data.model.ChatType
import com.example.data.model.CoopEventEntity
import com.example.data.model.CoopEventType
import com.example.data.model.CoopParticipantEntity
import com.example.data.model.CoopPayerMode
import com.example.data.model.MessageEntity
import com.example.data.model.MessageAttachmentEntity
import com.example.data.model.NpcDossierEntity
import com.example.data.model.NpcDossierUpdateEntity
import com.example.data.model.SpeakerType
import com.example.domain.coop.CoopMessagePayload
import com.example.domain.coop.CoopMessageDeletePayload
import com.example.domain.coop.CoopGenerationApprovalPayload
import com.example.domain.coop.CoopGenerationCompletionPayload
import com.example.domain.coop.CoopGenerationExecutionPayload
import com.example.domain.coop.CoopGenerationRequestPayload
import com.example.domain.coop.CoopParticipantDto
import com.example.domain.coop.CoopPayerPolicyPayload
import com.example.domain.coop.CoopProtocol
import com.example.domain.coop.CoopSnapshotPayload
import com.example.domain.coop.CoopSequenceGapException
import com.example.domain.coop.CoopSessionCrypto
import com.example.domain.repository.CoopRepository
import com.example.domain.repository.MessageAttachmentRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
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
import java.security.KeyPair
import java.util.Base64
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CoopRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: CoopRepository

    private suspend fun appendSignedParticipantEvent(
        roomId: String,
        clientEventId: String,
        participantId: String,
        identity: KeyPair,
        eventType: String,
        payloadJson: String
    ) = repository.appendHostEventResult(
        roomId = roomId,
        clientEventId = clientEventId,
        originParticipantId = participantId,
        eventType = eventType,
        payloadJson = payloadJson,
        originPayloadJson = payloadJson,
        originSignature = CoopSessionCrypto.signParticipantEvent(
            identity,
            roomId,
            clientEventId,
            participantId,
            eventType,
            payloadJson
        )
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = CoopRepository(
            database,
            Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build(),
            MessageAttachmentRepository(context, database)
        )
    }

    @Test
    fun hostCannotForgeRemotePayerApprovalWithoutMembershipSignature() = runBlocking {
        database.chatDao().insertChat(ChatEntity("signed-approval-chat", "Signed", ChatType.GROUP))
        database.chatDao().insertChatParticipant(
            com.example.data.model.ChatParticipantEntity("signed-approval-chat", "character")
        )
        val hostIdentity = CoopSessionCrypto.generateSigningKeyPair()
        val invite = repository.createHostRoom(
            "signed-approval-chat",
            CoopSessionCrypto.hostFingerprint(hostIdentity.public.encoded),
            "Host",
            "host-persona",
            "Host Persona",
            hostPublicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(hostIdentity.public.encoded),
            hostKeyFingerprint = CoopSessionCrypto.hostFingerprint(hostIdentity.public.encoded)
        )
        val payerIdentity = CoopSessionCrypto.generateSigningKeyPair()
        val payer = CoopParticipantEntity(
            participantId = "remote-payer",
            roomId = invite.room.roomId,
            deviceId = CoopSessionCrypto.hostFingerprint(payerIdentity.public.encoded),
            identityPublicKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payerIdentity.public.encoded),
            displayName = "Remote Payer",
            isConnected = true,
            sortPosition = 1
        )
        repository.upsertParticipant(payer)
        val request = CoopGenerationRequestPayload(
            requestId = "signed-request",
            initiatorParticipantId = invite.participant.participantId,
            payerParticipantId = payer.participantId,
            characterId = "character",
            operation = "NORMAL",
            targetMessageId = null,
            requestedAt = System.currentTimeMillis(),
            modelId = "mock/model",
            contextFingerprint = "f".repeat(64)
        )
        repository.appendHostEvent(
            invite.room.roomId,
            request.requestId,
            invite.participant.participantId,
            CoopEventType.GENERATION_REQUESTED,
            repository.codec.generationToJson(request)
        )
        val approvalJson = repository.codec.approvalToJson(CoopGenerationApprovalPayload(request.requestId))
        val forgedByHost = CoopSessionCrypto.signParticipantEvent(
            hostIdentity,
            invite.room.roomId,
            "approval:${request.requestId}",
            payer.participantId,
            CoopEventType.GENERATION_APPROVED,
            approvalJson
        )
        val latestRoom = requireNotNull(repository.getRoom(invite.room.roomId))
        val forged = CoopEventEntity(
            eventId = "forged-approval",
            roomId = latestRoom.roomId,
            sequence = latestRoom.lastAppliedSequence + 1,
            clientEventId = "approval:${request.requestId}",
            originParticipantId = payer.participantId,
            eventType = CoopEventType.GENERATION_APPROVED,
            payloadJson = approvalJson,
            originPayloadJson = approvalJson,
            originSignature = forgedByHost
        )

        val error = runCatching { repository.applyOrderedEvent(forged) }.exceptionOrNull()

        assertTrue(error?.message?.contains("signature") == true)
        assertEquals(
            com.example.domain.coop.CoopGenerationStage.PENDING_APPROVAL,
            repository.getGenerationLifecycle(invite.room.roomId)?.stage
        )
    }

    @Test
    fun synchronizedDeleteRemovesFilesNpcEffectsAndPromotesSurvivingVariant() = runBlocking {
        val chatId = "synchronized-delete-chat"
        database.chatDao().insertChat(ChatEntity(chatId, "Delete", ChatType.GROUP))
        val unrelatedChatId = "unrelated-private-chat"
        database.chatDao().insertChat(ChatEntity(unrelatedChatId, "Private", ChatType.GROUP))
        val primary = MessageEntity(
            messageId = "delete-primary",
            chatId = chatId,
            speakerType = SpeakerType.CHARACTER,
            speakerId = "character",
            speakerDisplayNameSnapshot = "Character",
            content = "Primary",
            orderIndex = 1L,
            variantGroupId = "delete-variants",
            isPrimaryVariant = true
        )
        val survivor = primary.copy(
            messageId = "delete-survivor",
            content = "Survivor",
            isPrimaryVariant = false,
            createdAt = primary.createdAt + 1
        )
        database.messageDao().insertMessage(primary)
        database.messageDao().insertMessage(survivor)
        val invite = repository.createHostRoom(
            chatId, "host-fingerprint", "Host", "host-persona", "Host Persona"
        )
        val attachmentDirectory = File(context.filesDir, "message_attachments").apply { mkdirs() }
        val attachmentFile = File(attachmentDirectory, "co-op-delete-${System.nanoTime()}.png")
        attachmentFile.writeBytes(byteArrayOf(1, 2, 3))
        val unrelatedFile = File(attachmentDirectory, "unrelated-${System.nanoTime()}.png")
        unrelatedFile.writeBytes(byteArrayOf(4, 5, 6))
        database.messageAttachmentDao().insert(
            MessageAttachmentEntity(
                attachmentId = "delete-attachment",
                chatId = chatId,
                messageId = primary.messageId,
                relativePath = "message_attachments/${attachmentFile.name}",
                mimeType = "image/png",
                byteSize = 3,
                pixelWidth = 1,
                pixelHeight = 1,
                sha256 = "unused",
                sortPosition = 0
            )
        )
        database.messageAttachmentDao().insert(
            MessageAttachmentEntity(
                attachmentId = "unrelated-attachment",
                chatId = unrelatedChatId,
                messageId = null,
                relativePath = "message_attachments/${unrelatedFile.name}",
                mimeType = "image/png",
                byteSize = 3,
                pixelWidth = 1,
                pixelHeight = 1,
                sha256 = "unrelated",
                sortPosition = 0
            )
        )
        database.npcDao().insertDossier(
            NpcDossierEntity(npcId = "delete-npc", chatId = chatId, displayName = "NPC")
        )
        database.npcDao().insertDossier(
            NpcDossierEntity(npcId = "unrelated-npc", chatId = unrelatedChatId, displayName = "Private NPC")
        )
        database.npcDao().insertUpdates(
            listOf(
                NpcDossierUpdateEntity(
                    updateId = "primary-update",
                    chatId = chatId,
                    npcId = "delete-npc",
                    sourceMessageId = primary.messageId,
                    variantGroupId = primary.variantGroupId,
                    sourceOrderIndex = primary.orderIndex,
                    sourceRevision = 0,
                    fieldName = "role",
                    oldValue = null,
                    newValue = "Primary role",
                    isApplied = true
                ),
                NpcDossierUpdateEntity(
                    updateId = "survivor-update",
                    chatId = chatId,
                    npcId = "delete-npc",
                    sourceMessageId = survivor.messageId,
                    variantGroupId = survivor.variantGroupId,
                    sourceOrderIndex = survivor.orderIndex,
                    sourceRevision = 0,
                    fieldName = "role",
                    oldValue = null,
                    newValue = "Survivor role",
                    isApplied = false
                )
            )
        )

        repository.appendHostEvent(
            invite.room.roomId,
            "delete:${primary.messageId}",
            invite.participant.participantId,
            CoopEventType.MESSAGE_DELETE,
            repository.codec.deleteToJson(com.example.domain.coop.CoopMessageDeletePayload(primary.messageId))
        )
        // A retry of the same durable event and a new idempotent delete both leave the
        // deletion boundary intact.
        repository.appendHostEvent(
            invite.room.roomId,
            "delete:${primary.messageId}",
            invite.participant.participantId,
            CoopEventType.MESSAGE_DELETE,
            repository.codec.deleteToJson(com.example.domain.coop.CoopMessageDeletePayload(primary.messageId))
        )
        repository.appendHostEvent(
            invite.room.roomId,
            "delete-retry:${primary.messageId}",
            invite.participant.participantId,
            CoopEventType.MESSAGE_DELETE,
            repository.codec.deleteToJson(com.example.domain.coop.CoopMessageDeletePayload(primary.messageId))
        )

        val staleReplayError = runCatching {
            repository.appendHostEvent(
                invite.room.roomId,
                "stale-upsert:${primary.messageId}",
                invite.participant.participantId,
                CoopEventType.MESSAGE_UPSERT,
                repository.codec.messageToJson(CoopMessagePayload.from(primary.copy(content = "Stale replay")))
            )
        }.exceptionOrNull()

        assertNull(database.messageDao().getMessageById(primary.messageId))
        assertTrue(database.coopDao().isMessageTombstoned(invite.room.roomId, primary.messageId))
        assertTrue(staleReplayError?.message?.contains("cannot be recreated") == true)
        assertTrue(requireNotNull(database.messageDao().getMessageById(survivor.messageId)).isPrimaryVariant)
        assertTrue(database.messageAttachmentDao().getForMessage(primary.messageId).isEmpty())
        assertFalse(attachmentFile.exists())
        assertTrue(database.npcDao().getUpdatesForMessage(primary.messageId).isEmpty())
        assertTrue(database.npcDao().getUpdatesForMessage(survivor.messageId).single().isApplied)
        assertTrue(unrelatedFile.exists())
        assertEquals(1, database.messageAttachmentDao().getForChat(unrelatedChatId).size)
        assertEquals("Private NPC", database.npcDao().getDossier("unrelated-npc")?.displayName)
    }

    @Test
    fun roomEntryRejectsUnsupportedPrivateStateAndNonemptyClientChat() = runBlocking {
        val attachmentChat = "attachment-host-chat"
        database.chatDao().insertChat(ChatEntity(attachmentChat, "Attachment", ChatType.GROUP))
        val attachmentDirectory = File(context.filesDir, "message_attachments").apply { mkdirs() }
        val attachmentFile = File(attachmentDirectory, "host-preflight-${System.nanoTime()}.png")
        attachmentFile.writeBytes(byteArrayOf(1))
        database.messageAttachmentDao().insert(
            MessageAttachmentEntity(
                attachmentId = "host-preflight-attachment",
                chatId = attachmentChat,
                messageId = null,
                relativePath = "message_attachments/${attachmentFile.name}",
                mimeType = "image/png",
                byteSize = 1,
                pixelWidth = 1,
                pixelHeight = 1,
                sha256 = "one",
                sortPosition = 0
            )
        )
        val attachmentError = runCatching {
            repository.createHostRoom(attachmentChat, "host", "Host", "persona", "Persona")
        }.exceptionOrNull()
        assertTrue(attachmentError?.message?.contains("image attachments") == true)

        val npcChat = "npc-host-chat"
        database.chatDao().insertChat(ChatEntity(npcChat, "NPC", ChatType.GROUP))
        database.messageDao().insertMessage(
            message("npc-control-message", 1L).copy(
                chatId = npcChat,
                content = "Visible\n<elsewhere_npc_updates>{\"schema_version\":1,\"updates\":[]}</elsewhere_npc_updates>"
            )
        )
        val npcError = runCatching {
            repository.createHostRoom(npcChat, "host", "Host", "persona", "Persona")
        }.exceptionOrNull()
        assertTrue(npcError?.message?.contains("NPC control data") == true)

        val nonemptyClientChat = "nonempty-client-chat"
        database.chatDao().insertChat(ChatEntity(nonemptyClientChat, "Client", ChatType.GROUP))
        database.messageDao().insertMessage(message("local-only", 1L).copy(chatId = nonemptyClientChat))
        val clientError = runCatching {
            repository.createOrUpdateClientRoom(
                chatId = nonemptyClientChat,
                roomId = "remote-room",
                hostParticipantId = "remote-host",
                localParticipantId = "local-client",
                deviceId = "client-device",
                participantDisplayName = "Client",
                personaId = "client-persona",
                personaDisplayName = "Client Persona",
                hostAddress = "192.0.2.1",
                port = 42424
            )
        }.exceptionOrNull()
        assertTrue(clientError?.message?.contains("empty disposable Group chat") == true)
    }

    @Test
    fun hostSeedsSelectedPrimaryVariantBeforeItsAlternates() = runBlocking {
        val chatId = "variant-seed-chat"
        val variantGroupId = "variant-seed-group"
        database.chatDao().insertChat(ChatEntity(chatId, "Variant seed", ChatType.GROUP))
        val alternate = message("older-alternate", 7L).copy(
            chatId = chatId,
            speakerType = SpeakerType.CHARACTER,
            speakerId = "character",
            speakerDisplayNameSnapshot = "Character",
            content = "Old alternate",
            variantGroupId = variantGroupId,
            isPrimaryVariant = false,
            createdAt = 1L
        )
        val primary = alternate.copy(
            messageId = "selected-primary",
            content = "Selected response",
            isPrimaryVariant = true,
            createdAt = 2L
        )
        database.messageDao().insertMessage(alternate)
        database.messageDao().insertMessage(primary)

        val invite = repository.createHostRoom(
            chatId,
            "device-host",
            "Host",
            "persona-host",
            "Host Persona"
        )
        val seededVariants = repository.buildSnapshot(invite.room.roomId, 0).events
            .filter { it.eventType == CoopEventType.MESSAGE_UPSERT }
            .map { repository.codec.messageFromJson(it.payloadJson) }
            .filter { it.variantGroupId == variantGroupId }

        assertEquals(listOf("selected-primary", "older-alternate"), seededVariants.map { it.messageId })
        assertEquals(listOf(true, false), seededVariants.map { it.isPrimaryVariant })
    }

    @Test
    fun newerPrimaryVariantAtomicallyDemotesEarlierPrimary() = runBlocking {
        val invite = createEmptyHostRoom()
        val first = message("variant-first", 1L).copy(
            speakerType = SpeakerType.CHARACTER,
            speakerId = "character",
            variantGroupId = "variant-group",
            isPrimaryVariant = true
        )
        val second = first.copy(messageId = "variant-second", content = "Second")

        repository.appendHostEvent(
            invite.room.roomId,
            "upsert:first",
            invite.participant.participantId,
            CoopEventType.MESSAGE_UPSERT,
            repository.codec.messageToJson(CoopMessagePayload.from(first))
        )
        repository.appendHostEvent(
            invite.room.roomId,
            "upsert:second",
            invite.participant.participantId,
            CoopEventType.MESSAGE_UPSERT,
            repository.codec.messageToJson(CoopMessagePayload.from(second))
        )

        val variants = database.messageDao().getVariantsOnce("variant-group")
        assertEquals(1, variants.count { it.isPrimaryVariant })
        assertTrue(variants.single { it.messageId == second.messageId }.isPrimaryVariant)

        val removeOnlyPrimary = runCatching {
            repository.appendHostEvent(
                invite.room.roomId,
                "upsert:remove-primary",
                invite.participant.participantId,
                CoopEventType.MESSAGE_UPSERT,
                repository.codec.messageToJson(
                    CoopMessagePayload.from(second.copy(isPrimaryVariant = false))
                )
            )
        }.exceptionOrNull()
        assertTrue(removeOnlyPrimary?.message?.contains("primary-variant") == true)
        assertEquals(
            1,
            database.messageDao().getVariantsOnce("variant-group").count { it.isPrimaryVariant }
        )

        val firstNonPrimary = runCatching {
            repository.appendHostEvent(
                invite.room.roomId,
                "upsert:first-non-primary",
                invite.participant.participantId,
                CoopEventType.MESSAGE_UPSERT,
                repository.codec.messageToJson(
                    CoopMessagePayload.from(
                        first.copy(
                            messageId = "orphan-variant",
                            variantGroupId = "orphan-group",
                            isPrimaryVariant = false
                        )
                    )
                )
            )
        }.exceptionOrNull()
        assertTrue(firstNonPrimary?.message?.contains("begin with a primary") == true)
        assertTrue(database.messageDao().getVariantsOnce("orphan-group").isEmpty())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun hostRoomSeedsHistoryAndStoresOnlyHashedPairingCode() = runBlocking {
        database.chatDao().insertChat(ChatEntity("chat", "Room", ChatType.GROUP))
        database.messageDao().insertMessage(
            message("m1", 10L).copy(
                responsibleParticipantId = "prior-room-member",
                responsibleDeviceId = "prior-room-fingerprint"
            )
        )

        val invite = repository.createHostRoom(
            chatId = "chat",
            deviceId = "device-host",
            participantDisplayName = "Host",
            personaId = "persona-host",
            personaDisplayName = "Host Persona"
        )

        assertTrue(invite.pairingCode.length >= 22)
        assertTrue(java.util.Base64.getUrlDecoder().decode(invite.pairingCode).size >= 16)
        assertTrue(repository.verifyPairingCode(invite.room.roomId, invite.pairingCode))
        assertFalse(repository.verifyPairingCode(invite.room.roomId, CoopSessionCrypto.newInviteSecret()))
        val stored = repository.getRoom(invite.room.roomId)!!
        assertNotEquals(invite.pairingCode, stored.pairingCodeHash)
        assertFalse(stored.pairingCodeHash.contains(invite.pairingCode))
        val snapshot = repository.buildSnapshot(invite.room.roomId, 0)
        assertEquals(listOf("seed:m1", "seed:character-roster"), snapshot.events.map { it.clientEventId })
        assertEquals(2L, snapshot.lastSequence)
        val seedPayload = snapshot.events.single { it.clientEventId == "seed:m1" }.payloadJson
        assertFalse(seedPayload.contains("prior-room-member"))
        assertFalse(seedPayload.contains("prior-room-fingerprint"))
        assertEquals(
            "prior-room-member",
            database.messageDao().getMessageById("m1")?.responsibleParticipantId
        )
    }

    @Test
    fun hostEventOrderingIsMonotonicAndClientEventIdsAreIdempotent() = runBlocking {
        val invite = createEmptyHostRoom()
        val participant = CoopParticipantEntity(
            participantId = "peer",
            roomId = invite.room.roomId,
            deviceId = "device-peer",
            displayName = "Peer",
            isConnected = true,
            sortPosition = 1
        )
        val payload = repository.codec.participantToJson(CoopParticipantDto.from(participant))

        val first = repository.appendHostEvent(
            invite.room.roomId, "client-event", invite.participant.participantId,
            CoopEventType.PARTICIPANT_UPSERT, payload
        )
        val duplicate = repository.appendHostEvent(
            invite.room.roomId, "client-event", invite.participant.participantId,
            CoopEventType.PARTICIPANT_UPSERT, payload
        )
        val second = repository.appendHostEvent(
            invite.room.roomId, "next-event", invite.participant.participantId,
            CoopEventType.PARTICIPANT_UPSERT, payload
        )

        assertEquals(first.eventId, duplicate.eventId)
        assertEquals(first.sequence + 1, second.sequence)
        assertEquals(3, repository.buildSnapshot(invite.room.roomId, 0).events.size)
    }

    @Test
    fun orderedEventGapIsRejectedWithoutAdvancingRoom() = runBlocking {
        val invite = createEmptyHostRoom()
        val payload = repository.codec.messageToJson(CoopMessagePayload.from(message("late", 20L)))
        val gap = CoopEventEntity(
            roomId = invite.room.roomId,
            sequence = 3,
            clientEventId = "late-event",
            originParticipantId = invite.participant.participantId,
            eventType = CoopEventType.MESSAGE_UPSERT,
            payloadJson = payload
        )

        var thrown: CoopSequenceGapException? = null
        try {
            repository.applyOrderedEvent(gap)
        } catch (error: CoopSequenceGapException) {
            thrown = error
        }

        assertEquals(2L, thrown?.expected)
        assertEquals(1L, repository.getRoom(invite.room.roomId)?.lastAppliedSequence)
        assertEquals(
            listOf("seed:character-roster"),
            repository.buildSnapshot(invite.room.roomId, 0).events.map { it.clientEventId }
        )
    }

    @Test
    fun payerModesResolveOnlyConnectedRoomParticipants() = runBlocking {
        val invite = createEmptyHostRoom()
        val peer = CoopParticipantEntity(
            participantId = "peer",
            roomId = invite.room.roomId,
            deviceId = "device-peer",
            displayName = "Peer",
            isConnected = true
        )
        repository.upsertParticipant(peer)

        assertEquals("peer", repository.resolvePayer(invite.room.roomId, "peer"))
        setPayer(invite.room.roomId, invite.participant.participantId, CoopPayerMode.HOST, null)
        assertEquals(invite.participant.participantId, repository.resolvePayer(invite.room.roomId, "peer"))
        setPayer(invite.room.roomId, invite.participant.participantId, CoopPayerMode.SELECTED_PARTICIPANT, "peer")
        assertEquals("peer", repository.resolvePayer(invite.room.roomId, invite.participant.participantId))

        repository.setParticipantConnected("peer", false)
        val rejected = runCatching {
            repository.resolvePayer(invite.room.roomId, invite.participant.participantId)
        }.exceptionOrNull()
        assertTrue(rejected?.message?.contains("not connected") == true)
    }

    @Test
    fun conflictingDuplicateEventIdIsRejected() = runBlocking {
        val invite = createEmptyHostRoom()
        val first = CoopParticipantEntity(
            participantId = "peer",
            roomId = invite.room.roomId,
            deviceId = "peer-device",
            displayName = "Peer",
            isConnected = true
        )
        repository.appendHostEvent(
            invite.room.roomId,
            "duplicate-id",
            invite.participant.participantId,
            CoopEventType.PARTICIPANT_UPSERT,
            repository.codec.participantToJson(CoopParticipantDto.from(first))
        )

        val error = runCatching {
            repository.appendHostEvent(
                invite.room.roomId,
                "duplicate-id",
                invite.participant.participantId,
                CoopEventType.PARTICIPANT_UPSERT,
                repository.codec.participantToJson(CoopParticipantDto.from(first.copy(displayName = "Changed")))
            )
        }.exceptionOrNull()

        assertTrue(error?.message?.contains("cannot be reused") == true)
        assertEquals("Peer", repository.getParticipant("peer")?.displayName)
    }

    @Test
    fun peerCannotInjectSystemMessageOrOverwriteAnotherChatMessageId() = runBlocking {
        val invite = createEmptyHostRoom()
        val systemPayload = CoopMessagePayload.from(
            message("system", 1L).copy(speakerType = SpeakerType.SYSTEM, speakerId = "system")
        )
        assertTrue(runCatching {
            repository.appendHostEvent(
                invite.room.roomId, "system-injection", invite.participant.participantId,
                CoopEventType.MESSAGE_UPSERT, repository.codec.messageToJson(systemPayload)
            )
        }.isFailure)

        database.chatDao().insertChat(ChatEntity("other-chat", "Other", ChatType.GROUP))
        database.messageDao().insertMessage(message("shared-id", 2L).copy(chatId = "other-chat"))
        val collision = CoopMessagePayload.from(message("shared-id", 3L))
        assertTrue(runCatching {
            repository.appendHostEvent(
                invite.room.roomId, "cross-chat", invite.participant.participantId,
                CoopEventType.MESSAGE_UPSERT, repository.codec.messageToJson(collision)
            )
        }.isFailure)
        assertEquals("other-chat", database.messageDao().getMessageById("shared-id")?.chatId)
    }

    @Test
    fun snapshotRejectsNestedRoomMismatchBeforeChangingState() = runBlocking {
        val invite = createEmptyHostRoom()
        val before = repository.getParticipants(invite.room.roomId)
        val snapshot = CoopSnapshotPayload(
            roomId = invite.room.roomId,
            hostParticipantId = invite.participant.participantId,
            payerMode = CoopPayerMode.HOST,
            selectedPayerParticipantId = null,
            lastSequence = 0,
            participants = listOf(CoopParticipantDto.from(invite.participant.copy(roomId = "other-room"))),
            personaOwnerships = emptyList(),
            events = emptyList(),
            hasMoreEvents = false
        )

        assertTrue(runCatching { repository.applySnapshot(snapshot) }.isFailure)
        assertEquals(before, repository.getParticipants(invite.room.roomId))
    }

    @Test
    fun invalidSnapshotEventRollsBackParticipantAndPayerChangesAtomically() = runBlocking {
        val invite = createEmptyHostRoom()
        val injected = message("system-from-snapshot", 10L).copy(
            speakerType = SpeakerType.SYSTEM,
            speakerId = "system"
        )
        val event = CoopEventEntity(
            roomId = invite.room.roomId,
            sequence = 2,
            clientEventId = "snapshot-injection",
            originParticipantId = invite.participant.participantId,
            eventType = CoopEventType.MESSAGE_UPSERT,
            payloadJson = repository.codec.messageToJson(CoopMessagePayload.from(injected))
        )
        val snapshot = CoopSnapshotPayload(
            roomId = invite.room.roomId,
            hostParticipantId = invite.participant.participantId,
            payerMode = CoopPayerMode.HOST,
            selectedPayerParticipantId = null,
            lastSequence = 2,
            participants = listOf(CoopParticipantDto.from(invite.participant.copy(displayName = "Tampered"))),
            personaOwnerships = repository.getOwnerships(invite.room.roomId).map(
                com.example.domain.coop.CoopPersonaOwnershipDto::from
            ),
            events = listOf(com.example.domain.coop.CoopEventDto.from(event)),
            hasMoreEvents = false
        )

        assertTrue(runCatching { repository.applySnapshot(snapshot) }.isFailure)
        assertEquals("Host", repository.getParticipant(invite.participant.participantId)?.displayName)
        assertEquals(CoopPayerMode.EACH_PARTICIPANT, repository.getRoom(invite.room.roomId)?.payerMode)
        assertEquals(
            listOf("seed:character-roster"),
            repository.buildSnapshot(invite.room.roomId, 0).events.map { it.clientEventId }
        )
        assertEquals(null, database.messageDao().getMessageById("system-from-snapshot"))
    }

    @Test
    fun reconnectCannotReplacePinnedHostIdentity() = runBlocking {
        database.chatDao().insertChat(ChatEntity("client-chat", "Client", ChatType.GROUP))
        val first = CoopSessionCrypto.generateSigningKeyPair().public.encoded
        val second = CoopSessionCrypto.generateSigningKeyPair().public.encoded
        val firstEncoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(first)
        val secondEncoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(second)
        repository.createOrUpdateClientRoom(
            "client-chat", "room", "host", "client", "device", "Client",
            "persona", "Persona", "127.0.0.1", 42424,
            firstEncoded, CoopSessionCrypto.hostFingerprint(first)
        )

        val error = runCatching {
            repository.createOrUpdateClientRoom(
                "client-chat", "room", "host", "client", "device", "Client",
                "persona", "Persona", "127.0.0.1", 42424,
                secondEncoded, CoopSessionCrypto.hostFingerprint(second)
            )
        }.exceptionOrNull()

        assertTrue(error?.message?.contains("cannot change") == true)
        assertEquals(firstEncoded, repository.getRoom("room")?.hostPublicKey)
    }

    @Test
    fun snapshotPagesBoundTheEntireEncodedRosterAndEventPayload() = runBlocking {
        database.chatDao().insertChat(ChatEntity("chat", "Room", ChatType.GROUP))
        repeat(4) { index ->
            database.messageDao().insertMessage(
                message("large-$index", index.toLong()).copy(content = "\"".repeat(30_000))
            )
        }
        val invite = repository.createHostRoom(
            "chat",
            "device-host",
            "Host",
            "persona-host",
            "Host Persona"
        )

        val first = repository.buildSnapshot(invite.room.roomId, 0)
        val firstBytes = repository.codec.snapshotToJson(first).toByteArray(Charsets.UTF_8).size

        assertTrue(firstBytes <= CoopProtocol.MAX_SNAPSHOT_JSON_BYTES)
        assertTrue(first.events.isNotEmpty())
        assertTrue(first.events.size < 4)
        assertTrue(first.hasMoreEvents)
        var count = first.events.size
        var page = first
        while (page.hasMoreEvents) {
            page = repository.buildSnapshot(invite.room.roomId, page.events.last().sequence)
            assertTrue(
                repository.codec.snapshotToJson(page).toByteArray(Charsets.UTF_8).size <=
                    CoopProtocol.MAX_SNAPSHOT_JSON_BYTES
            )
            assertTrue(page.events.isNotEmpty())
            count += page.events.size
        }
        assertEquals(5, count)
    }

    @Test
    fun hostileSnapshotAndLeaveCannotOverwriteParticipantFromAnotherRoom() = runBlocking {
        database.chatDao().insertChat(ChatEntity("room-one-chat", "One", ChatType.GROUP))
        database.chatDao().insertChat(ChatEntity("room-two-chat", "Two", ChatType.GROUP))
        val first = repository.createHostRoom(
            "room-one-chat", "fingerprint-one", "First Host", "persona-one", "Persona One"
        )
        val second = repository.createHostRoom(
            "room-two-chat", "fingerprint-two", "Second Host", "persona-two", "Persona Two"
        )
        val victim = first.participant
        val maliciousVictim = CoopParticipantDto.from(
            victim.copy(
                roomId = second.room.roomId,
                deviceId = "attacker-fingerprint",
                displayName = "Overwritten",
                isHost = false,
                sortPosition = 1
            )
        )
        val snapshot = CoopSnapshotPayload(
            roomId = second.room.roomId,
            hostParticipantId = second.participant.participantId,
            payerMode = CoopPayerMode.EACH_PARTICIPANT,
            selectedPayerParticipantId = null,
            lastSequence = second.room.lastAppliedSequence,
            participants = listOf(CoopParticipantDto.from(second.participant), maliciousVictim),
            personaOwnerships = emptyList(),
            events = emptyList(),
            hasMoreEvents = false
        )

        val snapshotError = runCatching { repository.applySnapshot(snapshot) }.exceptionOrNull()
        assertTrue(snapshotError?.message?.contains("collides") == true)
        assertEquals(first.room.roomId, repository.getParticipant(victim.participantId)?.roomId)
        assertEquals(victim.deviceId, repository.getParticipant(victim.participantId)?.deviceId)

        val leaveError = runCatching {
            repository.appendHostEvent(
                second.room.roomId,
                "malicious-cross-room-leave",
                second.participant.participantId,
                CoopEventType.PARTICIPANT_LEFT,
                repository.codec.participantToJson(maliciousVictim)
            )
        }.exceptionOrNull()
        assertTrue(leaveError?.message?.contains("immutable") == true)
        assertEquals(first.room.roomId, repository.getParticipant(victim.participantId)?.roomId)
        assertEquals(victim.deviceId, repository.getParticipant(victim.participantId)?.deviceId)
    }

    @Test
    fun approvedDifferentPayerCanContinueExistingCharacterMessageAndComplete() = runBlocking {
        database.chatDao().insertChat(ChatEntity("continue-chat", "Continue", ChatType.GROUP))
        database.chatDao().insertChatParticipant(
            com.example.data.model.ChatParticipantEntity("continue-chat", "character")
        )
        val original = MessageEntity(
            messageId = "continued-message",
            chatId = "continue-chat",
            speakerType = SpeakerType.CHARACTER,
            speakerId = "character",
            speakerDisplayNameSnapshot = "Character",
            content = "First paragraph.",
            orderIndex = 1L,
            responsibleParticipantId = "placeholder-host"
        )
        database.messageDao().insertMessage(original)
        val invite = repository.createHostRoom(
            "continue-chat", "host-fingerprint", "Host", "persona-host", "Host Persona"
        )
        database.messageDao().updateMessage(
            requireNotNull(database.messageDao().getMessageById(original.messageId)).copy(
                responsibleParticipantId = invite.participant.participantId
            )
        )
        val payerIdentity = CoopSessionCrypto.generateSigningKeyPair()
        val payerPublicKey = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payerIdentity.public.encoded)
        val payer = CoopParticipantEntity(
            participantId = "different-payer",
            roomId = invite.room.roomId,
            deviceId = CoopSessionCrypto.hostFingerprint(payerIdentity.public.encoded),
            identityPublicKey = payerPublicKey,
            displayName = "Different Payer",
            isConnected = true,
            sortPosition = 1
        )
        repository.upsertParticipant(payer)
        val request = CoopGenerationRequestPayload(
            requestId = "continue-request",
            initiatorParticipantId = invite.participant.participantId,
            payerParticipantId = payer.participantId,
            characterId = "character",
            operation = "CONTINUE",
            targetMessageId = original.messageId,
            requestedAt = System.currentTimeMillis(),
            modelId = "mock/model",
            contextFingerprint = "c".repeat(64)
        )
        repository.appendHostEvent(
            invite.room.roomId,
            request.requestId,
            invite.participant.participantId,
            CoopEventType.GENERATION_REQUESTED,
            repository.codec.generationToJson(request)
        )
        val continued = requireNotNull(database.messageDao().getMessageById(original.messageId)).copy(
            content = "First paragraph. Second paragraph.",
            responsibleParticipantId = payer.participantId
        )
        val resultClientEventId = CoopProtocol.generationResultClientEventId(request.requestId)
        val resultPayload = repository.codec.messageToJson(
            CoopMessagePayload.from(continued, request.requestId)
        )
        val beforeApproval = runCatching {
            appendSignedParticipantEvent(
                invite.room.roomId,
                resultClientEventId,
                payer.participantId,
                payerIdentity,
                CoopEventType.MESSAGE_UPSERT,
                resultPayload
            )
        }
        assertTrue(beforeApproval.exceptionOrNull()?.message?.contains("durable executing") == true)
        appendSignedParticipantEvent(
            invite.room.roomId,
            "approval:${request.requestId}",
            payer.participantId,
            payerIdentity,
            CoopEventType.GENERATION_APPROVED,
            repository.codec.approvalToJson(CoopGenerationApprovalPayload(request.requestId))
        )
        val beforeExecution = runCatching {
            appendSignedParticipantEvent(
                invite.room.roomId,
                resultClientEventId,
                payer.participantId,
                payerIdentity,
                CoopEventType.MESSAGE_UPSERT,
                resultPayload
            )
        }
        assertTrue(beforeExecution.exceptionOrNull()?.message?.contains("durable executing") == true)
        appendSignedParticipantEvent(
            invite.room.roomId,
            "execution:${request.requestId}",
            payer.participantId,
            payerIdentity,
            CoopEventType.GENERATION_EXECUTING,
            repository.codec.executionToJson(
                CoopGenerationExecutionPayload(request.requestId, request.requestId)
            )
        )
        val editedDuringContinue = requireNotNull(
            database.messageDao().getMessageById(original.messageId)
        ).copy(content = "First paragraph, edited.")
        val frozenEdit = runCatching {
            repository.appendHostEvent(
                invite.room.roomId,
                "host-edit-during-continue",
                invite.participant.participantId,
                CoopEventType.MESSAGE_UPSERT,
                repository.codec.messageToJson(CoopMessagePayload.from(editedDuringContinue))
            )
        }.exceptionOrNull()
        assertTrue(frozenEdit?.message?.contains("frozen") == true)
        val frozenDelete = runCatching {
            repository.appendHostEvent(
                invite.room.roomId,
                "host-delete-during-continue",
                invite.participant.participantId,
                CoopEventType.MESSAGE_DELETE,
                repository.codec.deleteToJson(CoopMessageDeletePayload(original.messageId))
            )
        }.exceptionOrNull()
        assertTrue(frozenDelete?.message?.contains("frozen") == true)

        // Defense in depth: reject a stale Continue result even if a local writer bypassed co-op.
        database.messageDao().updateMessage(
            editedDuringContinue.copy(editedAt = System.currentTimeMillis())
        )
        val staleResult = runCatching {
            appendSignedParticipantEvent(
                invite.room.roomId,
                resultClientEventId,
                payer.participantId,
                payerIdentity,
                CoopEventType.MESSAGE_UPSERT,
                resultPayload
            )
        }.exceptionOrNull()
        assertTrue(staleResult?.message?.contains("Continue target changed") == true)

        val currentEdited = requireNotNull(database.messageDao().getMessageById(original.messageId))
        val continuedAfterEdit = currentEdited.copy(
            content = "${currentEdited.content} Second paragraph.",
            responsibleParticipantId = payer.participantId
        )
        val currentResultPayload = repository.codec.messageToJson(
            CoopMessagePayload.from(continuedAfterEdit, request.requestId)
        )
        val insertedResult = appendSignedParticipantEvent(
            invite.room.roomId,
            resultClientEventId,
            payer.participantId,
            payerIdentity,
            CoopEventType.MESSAGE_UPSERT,
            currentResultPayload
        )
        assertTrue(insertedResult.inserted)
        assertFalse(appendSignedParticipantEvent(
            invite.room.roomId,
            resultClientEventId,
            payer.participantId,
            payerIdentity,
            CoopEventType.MESSAGE_UPSERT,
            currentResultPayload
        ).inserted)
        assertTrue(runCatching {
            appendSignedParticipantEvent(
                invite.room.roomId,
                resultClientEventId,
                payer.participantId,
                payerIdentity,
                CoopEventType.MESSAGE_UPSERT,
                repository.codec.messageToJson(
                    CoopMessagePayload.from(
                        continuedAfterEdit.copy(content = "Conflicting second continuation."),
                        request.requestId
                    )
                )
            )
        }.exceptionOrNull()?.message?.contains("cannot be reused") == true)
        assertTrue(runCatching {
            appendSignedParticipantEvent(
                invite.room.roomId,
                "alternate-result-id",
                payer.participantId,
                payerIdentity,
                CoopEventType.MESSAGE_UPSERT,
                currentResultPayload
            )
        }.exceptionOrNull()?.message?.contains("not bound") == true)
        appendSignedParticipantEvent(
            invite.room.roomId,
            "completion:${request.requestId}",
            payer.participantId,
            payerIdentity,
            CoopEventType.GENERATION_FINISHED,
            repository.codec.completionToJson(
                CoopGenerationCompletionPayload(
                    requestId = request.requestId,
                    executionId = request.requestId,
                    messageId = original.messageId
                )
            )
        )

        assertEquals(
            "First paragraph, edited. Second paragraph.",
            database.messageDao().getMessageById(original.messageId)?.content
        )
        assertEquals(invite.participant.participantId,
            database.messageDao().getMessageById(original.messageId)?.responsibleParticipantId)
        assertNull(repository.getGenerationLifecycle(invite.room.roomId))
    }

    private suspend fun createEmptyHostRoom() = repository.createHostRoom(
        chatId = "chat".also {
            database.chatDao().insertChat(ChatEntity(it, "Room", ChatType.GROUP))
        },
        deviceId = "device-host",
        participantDisplayName = "Host",
        personaId = "persona-host",
        personaDisplayName = "Host Persona"
    )

    private suspend fun setPayer(roomId: String, hostId: String, mode: String, selected: String?) {
        repository.appendHostEvent(
            roomId = roomId,
            clientEventId = "payer:$mode:$selected",
            originParticipantId = hostId,
            eventType = CoopEventType.PAYER_POLICY_SET,
            payloadJson = repository.codec.payerToJson(CoopPayerPolicyPayload(mode, selected))
        )
    }

    private fun message(id: String, order: Long) = MessageEntity(
        messageId = id,
        chatId = "chat",
        speakerType = SpeakerType.PERSONA,
        speakerId = "persona-host",
        speakerDisplayNameSnapshot = "Host Persona",
        content = "Hello",
        orderIndex = order
    )
}
