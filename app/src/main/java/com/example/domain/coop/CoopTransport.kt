package com.example.domain.coop

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

sealed interface CoopTransportEvent {
    data class Listening(val port: Int, val suggestedAddresses: List<String>) : CoopTransportEvent
    data object ConnectedToHost : CoopTransportEvent
    data class Envelope(
        val value: CoopWireEnvelope,
        val authenticatedParticipant: CoopAuthenticatedParticipant? = null
    ) : CoopTransportEvent
    data class PeerDisconnected(val participantId: String) : CoopTransportEvent
    data object HostDisconnected : CoopTransportEvent
    data class Failure(val message: String) : CoopTransportEvent
}

interface CoopTransport {
    val events: SharedFlow<CoopTransportEvent>
    suspend fun startHost(bindAddress: String, port: Int, security: CoopHostTransportSecurity)
    suspend fun connect(host: String, port: Int, hello: CoopWireEnvelope, security: CoopClientTransportSecurity)
    suspend fun acceptPeer(participantId: String, acceptedEnvelope: CoopWireEnvelope)
    suspend fun rejectPeer(participantId: String)
    suspend fun send(envelope: CoopWireEnvelope)
    suspend fun disconnectPeer(participantId: String)
    suspend fun stop()
}

data class CoopHostTransportSecurity(
    val roomId: String,
    val hostParticipantId: String,
    val inviteSecret: String,
    val identity: CoopRoomIdentity
)

data class CoopClientTransportSecurity(
    val roomId: String,
    val participantId: String,
    val hostParticipantId: String,
    val inviteSecret: String,
    val pinnedHostPublicKey: ByteArray,
    val participantIdentity: CoopRoomIdentity
)

class LanCoopTransport(
    private val codec: CoopCodec,
    private val networkingEnabled: () -> Boolean = { CoopSessionManager.LAN_NETWORKING_ENABLED }
) : CoopTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableEvents = MutableSharedFlow<CoopTransportEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<CoopTransportEvent> = mutableEvents.asSharedFlow()
    private val peers = ConcurrentHashMap<String, FramedConnection>()
    private val pendingPeers = ConcurrentHashMap<String, FramedConnection>()
    private val pendingHandshakes = AtomicInteger(0)
    private val handshakeFailures = ConcurrentHashMap<String, HandshakeAttemptWindow>()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var hostConnection: FramedConnection? = null
    @Volatile private var hostMode = false
    private var acceptJob: Job? = null

    override suspend fun startHost(
        bindAddress: String,
        port: Int,
        security: CoopHostTransportSecurity
    ) = withContext(Dispatchers.IO) {
        check(networkingEnabled()) { "Local Co-op networking is disabled" }
        require(port in 1024..65535) { "LAN port must be between 1024 and 65535" }
        check(serverSocket == null && hostConnection == null) { "Transport is already running" }
        val privateBindAddress = CoopLanAddressPolicy.requirePrivateIpv4(bindAddress)
        val canonicalBindAddress = requireNotNull(privateBindAddress.hostAddress) {
            "LAN IPv4 address could not be normalized"
        }
        require(localLanAddresses().contains(canonicalBindAddress)) {
            "The selected host address is not active on this device"
        }
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(privateBindAddress, port))
        }
        serverSocket = server
        hostMode = true
        mutableEvents.emit(CoopTransportEvent.Listening(port, listOf(canonicalBindAddress)))
        acceptJob = scope.launch {
            while (!server.isClosed) {
                try {
                    val socket = server.accept().apply { tcpNoDelay = true; keepAlive = true }
                    val remoteAddress = socket.inetAddress.hostAddress.orEmpty()
                    if (!CoopLanAddressPolicy.isPrivateIpv4(remoteAddress) ||
                        !handshakeAllowed(remoteAddress) ||
                        peers.size + pendingHandshakes.get() >= CoopProtocol.MAX_PARTICIPANTS
                    ) {
                        socket.close()
                    } else {
                        pendingHandshakes.incrementAndGet()
                        startReader(
                            FramedConnection(socket),
                            isHostConnection = false,
                            hostSecurity = security
                        )
                    }
                } catch (_: EOFException) {
                    break
                } catch (error: Exception) {
                    if (!server.isClosed) mutableEvents.emit(
                        CoopTransportEvent.Failure(error.message ?: "LAN accept failed")
                    )
                }
            }
        }
    }

    override suspend fun connect(
        host: String,
        port: Int,
        hello: CoopWireEnvelope,
        security: CoopClientTransportSecurity
    ) = withContext(Dispatchers.IO) {
        check(networkingEnabled()) { "Local Co-op networking is disabled" }
        require(host.isNotBlank()) { "Host address is required" }
        check(serverSocket == null && hostConnection == null) { "Transport is already running" }
        val privateHostAddress = CoopLanAddressPolicy.requirePrivateIpv4(host)
        val socket = Socket().apply {
            tcpNoDelay = true
            keepAlive = true
            connect(InetSocketAddress(privateHostAddress, port), 5_000)
        }
        val connection = FramedConnection(socket)
        hostMode = false
        try {
            require(security.roomId == hello.roomId && security.participantId == hello.senderParticipantId) {
                "Client security binding does not match HELLO"
            }
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val clientHandshake = CoopSessionCrypto.startClientHandshake(
                security.roomId,
                security.participantId,
                security.inviteSecret,
                security.pinnedHostPublicKey,
                security.participantIdentity.keyPair
            )
            connection.writePlain(codec.secureClientHelloToJson(clientHandshake.hello))
            val hostHello = codec.secureHostHelloFromJson(connection.readPlain())
            connection.installSecureChannel(CoopSessionCrypto.finishClientHandshake(clientHandshake, hostHello))
            socket.soTimeout = ACTIVE_READ_TIMEOUT_MS
            hostConnection = connection
            connection.write(hello)
            startReader(connection, isHostConnection = true, clientSecurity = security)
            mutableEvents.emit(CoopTransportEvent.ConnectedToHost)
        } catch (error: Exception) {
            connection.close()
            throw error
        }
    }

    override suspend fun acceptPeer(
        participantId: String,
        acceptedEnvelope: CoopWireEnvelope
    ) = withContext(Dispatchers.IO) {
        check(networkingEnabled()) { "Local Co-op networking is disabled" }
        if (!hostMode) throw CoopProtocolException("Only the host can accept a peer")
        require(
            acceptedEnvelope.kind == CoopWireKind.JOIN_ACCEPTED &&
                acceptedEnvelope.targetParticipantId == participantId
        ) { "Peer admission requires a targeted JOIN_ACCEPTED frame" }
        val connection = pendingPeers[participantId]
            ?: throw CoopProtocolException("Participant does not have a pending join")
        try {
            connection.write(acceptedEnvelope)
        } catch (error: Exception) {
            pendingPeers.remove(participantId, connection)
            connection.rejectAdmission()
            connection.close()
            throw error
        }
        check(pendingPeers.remove(participantId, connection)) {
            "Pending participant changed during admission"
        }
        val existing = peers.putIfAbsent(participantId, connection)
        if (existing != null && existing !== connection) {
            connection.rejectAdmission()
            connection.close()
            throw CoopProtocolException("Participant already has an accepted connection")
        }
        connection.acceptAdmission()
    }

    override suspend fun rejectPeer(participantId: String) = withContext(Dispatchers.IO) {
        check(networkingEnabled()) { "Local Co-op networking is disabled" }
        if (!hostMode) throw CoopProtocolException("Only the host can reject a peer")
        pendingPeers.remove(participantId)?.let { connection ->
            connection.rejectAdmission()
            connection.close()
        }
        Unit
    }

    override suspend fun send(envelope: CoopWireEnvelope) = withContext(Dispatchers.IO) {
        check(networkingEnabled()) { "Local Co-op networking is disabled" }
        if (hostMode) {
            val target = envelope.targetParticipantId
            if (target != null) {
                val pending = pendingPeers[target]
                val accepted = peers[target]
                when {
                    pending != null && envelope.kind == CoopWireKind.JOIN_REJECTED -> pending.write(envelope)
                    accepted != null -> accepted.write(envelope)
                    else -> throw CoopProtocolException("Participant is not connected")
                }
            } else {
                val failures = mutableListOf<String>()
                peers.forEach { (participantId, connection) ->
                    runCatching { connection.write(envelope) }
                        .onFailure { failures += participantId }
                }
                failures.forEach { participantId ->
                    peers.remove(participantId)?.close()
                    mutableEvents.emit(CoopTransportEvent.PeerDisconnected(participantId))
                }
            }
        } else {
            hostConnection?.write(envelope)
                ?: throw CoopProtocolException("Host is not connected")
        }
    }

    override suspend fun disconnectPeer(participantId: String) = withContext(Dispatchers.IO) {
        check(networkingEnabled()) { "Local Co-op networking is disabled" }
        if (!hostMode) throw CoopProtocolException("Only the host can disconnect a peer")
        peers.remove(participantId)?.let { connection ->
            connection.close()
            mutableEvents.emit(CoopTransportEvent.PeerDisconnected(participantId))
        }
        Unit
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        acceptJob?.cancel()
        serverSocket?.close()
        serverSocket = null
        hostConnection?.close()
        hostConnection = null
        peers.values.forEach(FramedConnection::close)
        peers.clear()
        pendingPeers.values.forEach { connection ->
            connection.rejectAdmission()
            connection.close()
        }
        pendingPeers.clear()
        scope.cancel()
    }

    private fun startReader(
        connection: FramedConnection,
        isHostConnection: Boolean,
        hostSecurity: CoopHostTransportSecurity? = null,
        clientSecurity: CoopClientTransportSecurity? = null
    ) {
        scope.launch {
            var participantId: String? = null
            var pendingHandshake = !isHostConnection
            var authenticatedParticipant: CoopAuthenticatedParticipant? = null
            var awaitingJoinAcceptance = isHostConnection
            try {
                if (!isHostConnection) {
                    val security = hostSecurity
                        ?: throw CoopProtocolException("Host transport security is missing")
                    connection.socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                    val clientHello = codec.secureClientHelloFromJson(connection.readPlain())
                    require(clientHello.roomId == security.roomId) { "Secure handshake room mismatch" }
                    val accepted = CoopSessionCrypto.acceptClientHandshake(
                        clientHello,
                        security.inviteSecret,
                        security.identity.keyPair
                    )
                    connection.writePlain(codec.secureHostHelloToJson(accepted.hello))
                    connection.installSecureChannel(accepted.channel)
                    connection.socket.soTimeout = ACTIVE_READ_TIMEOUT_MS
                    authenticatedParticipant = accepted.authenticatedParticipant
                    participantId = authenticatedParticipant.participantId
                    if (peers.containsKey(participantId)) {
                        throw CoopProtocolException("Participant already has an accepted connection")
                    }
                    val old = pendingPeers.putIfAbsent(participantId, connection)
                    if (old != null && old !== connection) {
                        throw CoopProtocolException("Participant already has a pending connection")
                    }
                    handshakeFailures.remove(connection.remoteAddress)
                    val firstEnvelope = connection.read()
                    validateHostInboundEnvelope(firstEnvelope, security, participantId)
                    require(firstEnvelope.kind == CoopWireKind.HELLO) {
                        "The first authenticated frame must be HELLO"
                    }
                    mutableEvents.emit(
                        CoopTransportEvent.Envelope(firstEnvelope, authenticatedParticipant)
                    )
                    val admitted = withTimeout(APPLICATION_JOIN_TIMEOUT_MS) {
                        connection.awaitAdmission()
                    }
                    if (!admitted) return@launch
                    pendingHandshakes.updateAndGet { value -> maxOf(0, value - 1) }
                    pendingHandshake = false
                }
                while (true) {
                    val envelope = connection.read()
                    if (envelope.protocolVersion != CoopProtocol.VERSION) {
                        throw CoopProtocolException("Unsupported co-op protocol version")
                    }
                    if (!isHostConnection) {
                        val security = hostSecurity
                            ?: throw CoopProtocolException("Host transport binding is missing")
                        validateHostInboundEnvelope(envelope, security, participantId)
                        require(envelope.kind != CoopWireKind.HELLO) {
                            "HELLO is only valid as the first authenticated frame"
                        }
                    } else {
                        val security = clientSecurity
                            ?: throw CoopProtocolException("Client transport binding is missing")
                        if (envelope.roomId != security.roomId ||
                            envelope.senderParticipantId != security.hostParticipantId
                        ) {
                            throw CoopProtocolException("Authenticated host binding mismatch")
                        }
                        if (envelope.targetParticipantId != null &&
                            envelope.targetParticipantId != security.participantId
                        ) {
                            throw CoopProtocolException("Authenticated host used an invalid target")
                        }
                        if (awaitingJoinAcceptance) {
                            require(envelope.kind == CoopWireKind.JOIN_ACCEPTED ||
                                envelope.kind == CoopWireKind.JOIN_REJECTED) {
                                "The first authenticated host frame must decide join admission"
                            }
                        } else {
                            require(envelope.kind != CoopWireKind.JOIN_ACCEPTED &&
                                envelope.kind != CoopWireKind.JOIN_REJECTED) {
                                "Join admission can only be decided once"
                            }
                        }
                    }
                    mutableEvents.emit(
                        CoopTransportEvent.Envelope(
                            envelope,
                            if (isHostConnection) null else authenticatedParticipant
                        )
                    )
                    if (isHostConnection && awaitingJoinAcceptance) {
                        if (envelope.kind == CoopWireKind.JOIN_REJECTED) return@launch
                        awaitingJoinAcceptance = false
                    }
                }
            } catch (_: EOFException) {
                // Normal disconnect.
            } catch (error: Exception) {
                if (pendingHandshake) recordHandshakeFailure(connection.remoteAddress)
                mutableEvents.emit(CoopTransportEvent.Failure(error.message ?: "LAN connection failed"))
            } finally {
                if (pendingHandshake) pendingHandshakes.updateAndGet { value -> maxOf(0, value - 1) }
                connection.close()
                if (isHostConnection) {
                    hostConnection = null
                    mutableEvents.emit(CoopTransportEvent.HostDisconnected)
                } else if (participantId != null) {
                    pendingPeers.remove(participantId, connection)
                    if (peers.remove(participantId, connection)) {
                        mutableEvents.emit(CoopTransportEvent.PeerDisconnected(participantId))
                    }
                }
            }
        }
    }

    private inner class FramedConnection(val socket: Socket) : Closeable {
        val remoteAddress: String get() = socket.inetAddress.hostAddress.orEmpty()
        private val input = DataInputStream(socket.getInputStream().buffered())
        private val output = DataOutputStream(socket.getOutputStream().buffered())
        private val writeLock = Any()
        @Volatile private var secureChannel: CoopSecureChannel? = null
        private val admission = CompletableDeferred<Boolean>()

        fun installSecureChannel(channel: CoopSecureChannel) {
            check(secureChannel == null) { "Secure channel is already installed" }
            secureChannel = channel
        }

        suspend fun awaitAdmission(): Boolean = admission.await()

        fun acceptAdmission() {
            admission.complete(true)
        }

        fun rejectAdmission() {
            admission.complete(false)
        }

        fun readPlain(): String = readBounded(HANDSHAKE_MAX_BYTES, "handshake")

        private fun readBounded(maxBytes: Int, label: String): String {
            val size = input.readInt()
            if (size <= 0 || size > maxBytes) {
                throw CoopProtocolException("Invalid $label frame size")
            }
            val bytes = ByteArray(size)
            input.readFully(bytes)
            return String(bytes, StandardCharsets.UTF_8)
        }

        fun writePlain(json: String) = writeBounded(json, HANDSHAKE_MAX_BYTES, "Handshake")

        private fun writeBounded(json: String, maxBytes: Int, label: String) {
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            require(bytes.size in 1..maxBytes) { "$label frame is too large" }
            synchronized(writeLock) {
                output.writeInt(bytes.size)
                output.write(bytes)
                output.flush()
            }
        }

        fun read(): CoopWireEnvelope {
            val channel = secureChannel ?: throw CoopProtocolException("Secure channel is not established")
            val encrypted = readBounded(ENCRYPTED_FRAME_MAX_BYTES, "encrypted")
            val plaintext = channel.decrypt(codec.secureFrameFromJson(encrypted))
            return codec.wireFromJson(String(plaintext, StandardCharsets.UTF_8))
        }

        fun write(envelope: CoopWireEnvelope) {
            val channel = secureChannel ?: throw CoopProtocolException("Secure channel is not established")
            val plaintext = codec.wireToJson(envelope).toByteArray(StandardCharsets.UTF_8)
            writeBounded(
                codec.secureFrameToJson(channel.encrypt(plaintext)),
                ENCRYPTED_FRAME_MAX_BYTES,
                "Encrypted"
            )
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val APPLICATION_JOIN_TIMEOUT_MS = 130_000L
        private const val ACTIVE_READ_TIMEOUT_MS = 120_000
        private const val HANDSHAKE_MAX_BYTES = 16_384
        private const val ENCRYPTED_FRAME_MAX_BYTES = 1_400_000
        private const val HANDSHAKE_ATTEMPT_WINDOW_MS = 60_000L
        private const val MAX_HANDSHAKE_FAILURES_PER_WINDOW = 5
        fun localLanAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filter { it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress?.substringBefore('%') }
                .filter { ':' !in it }
                .distinct()
                .sorted()
        }.getOrDefault(emptyList())

        fun newClientMessageId(): String = UUID.randomUUID().toString()
    }

    private fun handshakeAllowed(address: String): Boolean {
        val now = System.currentTimeMillis()
        val window = handshakeFailures[address] ?: return true
        if (now - window.startedAt >= HANDSHAKE_ATTEMPT_WINDOW_MS) {
            handshakeFailures.remove(address, window)
            return true
        }
        return window.failures < MAX_HANDSHAKE_FAILURES_PER_WINDOW
    }

    private fun recordHandshakeFailure(address: String) {
        val now = System.currentTimeMillis()
        handshakeFailures.compute(address) { _, current ->
            if (current == null || now - current.startedAt >= HANDSHAKE_ATTEMPT_WINDOW_MS) {
                HandshakeAttemptWindow(now, 1)
            } else {
                current.copy(failures = current.failures + 1)
            }
        }
    }

    private data class HandshakeAttemptWindow(val startedAt: Long, val failures: Int)

    private fun validateHostInboundEnvelope(
        envelope: CoopWireEnvelope,
        security: CoopHostTransportSecurity,
        authenticatedParticipantId: String?
    ) {
        if (envelope.protocolVersion != CoopProtocol.VERSION) {
            throw CoopProtocolException("Unsupported co-op protocol version")
        }
        val claimedParticipantId = envelope.senderParticipantId.takeIf(String::isNotBlank)
            ?: throw CoopProtocolException("Missing participant identity")
        if (authenticatedParticipantId != claimedParticipantId) {
            throw CoopProtocolException("Participant identity cannot change on an open connection")
        }
        if (envelope.roomId != security.roomId) {
            throw CoopProtocolException("Authenticated peer attempted cross-room traffic")
        }
        if (envelope.targetParticipantId != null &&
            envelope.targetParticipantId != security.hostParticipantId
        ) {
            throw CoopProtocolException("Authenticated peer used an invalid target")
        }
    }
}
