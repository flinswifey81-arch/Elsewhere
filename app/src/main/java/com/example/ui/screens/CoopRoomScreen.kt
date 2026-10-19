package com.example.ui.screens

import com.example.domain.coop.CoopLanAddressPolicy
import com.example.domain.coop.LanCoopTransport
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.data.model.CoopConnectionState
import com.example.data.model.CoopPayerMode
import com.example.data.model.CoopRole
import com.example.domain.coop.CoopProtocol
import com.example.domain.coop.CoopSessionManager
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.PersonaRepository
import com.example.ui.components.elsewhereCardBorder
import com.example.ui.components.elsewhereTextFieldColors
import com.example.ui.components.elsewhereTopAppBarColors
import java.util.UUID
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoopRoomScreen(
    chatId: String,
    manager: CoopSessionManager,
    chatRepository: ChatRepository,
    personaRepository: PersonaRepository,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val session by manager.state.collectAsState()
    var participantName by remember(chatId) { mutableStateOf("") }
    var selectedPersonaId by remember(chatId) { mutableStateOf<String?>(null) }
    var mode by remember(chatId) { mutableStateOf("HOST") }
    var hostAddress by remember(chatId) { mutableStateOf("") }
    var portText by remember(chatId) { mutableStateOf(CoopProtocol.DEFAULT_PORT.toString()) }
    var roomId by remember(chatId) { mutableStateOf("") }
    var hostParticipantId by remember(chatId) { mutableStateOf("") }
    var pairingCode by remember(chatId) { mutableStateOf("") }
    var hostPublicKey by remember(chatId) { mutableStateOf("") }
    var hostKeyFingerprint by remember(chatId) { mutableStateOf("") }
    var inviteBundleText by remember(chatId) { mutableStateOf("") }
    var validatedInviteBundleText by remember(chatId) { mutableStateOf<String?>(null) }
    var hostInviteAddress by remember(chatId) {
        mutableStateOf(LanCoopTransport.localLanAddresses().firstOrNull().orEmpty())
    }
    var loadedChatId by remember { mutableStateOf<String?>(null) }
    var sessionStartInProgress by remember(chatId) { mutableStateOf(false) }
    var actionError by remember(chatId) { mutableStateOf<String?>(null) }
    var pendingStartAction by remember(chatId) { mutableStateOf<String?>(null) }
    var showStopConfirm by remember(chatId) { mutableStateOf(false) }
    var showEndConfirm by remember(chatId) { mutableStateOf(false) }
    var chatPersonas by remember(chatId) { mutableStateOf(emptyList<com.example.data.model.PersonaEntity>()) }
    var chatCharacters by remember(chatId) { mutableStateOf(emptyList<com.example.data.model.CharacterEntity>()) }

    LaunchedEffect(chatId) {
        loadedChatId = null
        manager.load(chatId)
        val summary = chatRepository.getChatSummaryById(chatId)
        chatPersonas = summary?.personas.orEmpty()
        chatCharacters = summary?.characters.orEmpty()
        if (selectedPersonaId == null) selectedPersonaId = chatPersonas.firstOrNull()?.personaId
        loadedChatId = chatId
    }

    val activeRoom = session.room?.takeIf { it.chatId == chatId && loadedChatId == chatId }
    LaunchedEffect(activeRoom?.roomId, session.suggestedAddresses) {
        if (activeRoom?.localRole == CoopRole.HOST) {
            hostInviteAddress = activeRoom.hostAddress
                ?: session.suggestedAddresses.firstOrNull().orEmpty()
        }
    }

    val applyInviteBundle: (String) -> Unit = { raw ->
        runCatching { manager.parseInviteBundle(raw) }
            .onSuccess { invite ->
                val normalized = raw.trim()
                inviteBundleText = normalized
                validatedInviteBundleText = normalized
                hostAddress = invite.hostAddress
                portText = invite.port.toString()
                roomId = invite.roomId
                hostParticipantId = invite.hostParticipantId
                pairingCode = invite.inviteSecret
                hostPublicKey = invite.hostPublicKey
                hostKeyFingerprint = invite.hostKeyFingerprint
                actionError = null
            }
            .onFailure {
                validatedInviteBundleText = null
                hostAddress = ""
                roomId = ""
                hostParticipantId = ""
                pairingCode = ""
                hostPublicKey = ""
                hostKeyFingerprint = ""
                actionError = it.message ?: "Invite bundle is invalid."
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Local Co-op") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = elsewhereTopAppBarColors()
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).imePadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SharingBoundaryCard()
            Card(Modifier.fillMaxWidth(), border = elsewhereCardBorder()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Bounded LAN co-op test mode", style = MaterialTheme.typography.titleMedium)
                    Text("Networking stays inactive until you explicitly choose Host or Join and confirm the sharing boundary. Opening Elsewhere or this screen never starts a listener.")
                }
            }
            if (loadedChatId != chatId) {
                Text("Loading this chat's co-op state…", style = MaterialTheme.typography.bodyMedium)
            } else if (activeRoom == null) {
                Text("Create or join a LAN room", style = MaterialTheme.typography.titleMedium)
                if (chatPersonas.isEmpty()) {
                    Text("This Group chat needs a participating Persona before it can host or join.", color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == "HOST", onClick = { mode = "HOST" }, label = { Text("Host") })
                    FilterChip(selected = mode == "JOIN", onClick = { mode = "JOIN" }, label = { Text("Join") })
                }
                OutlinedTextField(
                    value = participantName,
                    onValueChange = { participantName = it },
                    label = { Text("Your participant/device name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = elsewhereTextFieldColors()
                )
                Text("Participating Persona", style = MaterialTheme.typography.titleSmall)
                chatPersonas.forEach { persona ->
                    FilterChip(
                        selected = selectedPersonaId == persona.personaId,
                        onClick = { selectedPersonaId = persona.personaId },
                        label = { Text(persona.displayName) }
                    )
                }
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    label = { Text("LAN port") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = mode == "HOST",
                    singleLine = true,
                    colors = elsewhereTextFieldColors()
                )
                if (mode == "HOST") {
                    OutlinedTextField(
                        value = hostInviteAddress,
                        onValueChange = { hostInviteAddress = it.trim().take(15) },
                        label = { Text("Private LAN IPv4 address") },
                        supportingText = {
                            Text("Elsewhere binds only to this device address and includes the same address in the invite.")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = elsewhereTextFieldColors()
                    )
                }
                if (mode == "JOIN") {
                    Text("Paste the single versioned invite bundle from the host. It contains a sensitive one-time invite secret; do not save it in public notes or screenshots.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = inviteBundleText,
                        onValueChange = {
                            inviteBundleText = it.take(5_500)
                            validatedInviteBundleText = null
                            hostAddress = ""
                            roomId = ""
                            hostParticipantId = ""
                            pairingCode = ""
                            hostPublicKey = ""
                            hostKeyFingerprint = ""
                            actionError = null
                        },
                        label = { Text("Elsewhere co-op invite bundle") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5,
                        colors = elsewhereTextFieldColors()
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            val pasted = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            inviteBundleText = pasted.take(5_500)
                            applyInviteBundle(inviteBundleText)
                        }, modifier = Modifier.fillMaxWidth()) { Text("Paste invite") }
                        OutlinedButton(
                            onClick = { applyInviteBundle(inviteBundleText) },
                            enabled = inviteBundleText.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Validate invite") }
                    }
                    if (hostAddress.isNotBlank()) {
                        Text("Invite ready: $hostAddress:$portText", style = MaterialTheme.typography.bodySmall)
                        Text("Pinned host: $hostKeyFingerprint", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(
                    onClick = { pendingStartAction = mode },
                    enabled = !sessionStartInProgress && CoopSessionManager.LAN_NETWORKING_ENABLED && participantName.isNotBlank() && selectedPersonaId != null && portText.toIntOrNull() in 1024..65535 &&
                        ((mode == "HOST" && CoopLanAddressPolicy.isPrivateIpv4(hostInviteAddress)) ||
                            (validatedInviteBundleText == inviteBundleText.trim() && hostAddress.isNotBlank() && roomId.isNotBlank() && hostParticipantId.isNotBlank() && pairingCode.length >= 22 && hostPublicKey.isNotBlank() && hostKeyFingerprint.isNotBlank())),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (mode == "HOST") "Host LAN Room" else "Pair & Join") }
            } else {
                RoomStatusCard(session, chatCharacters.map { it.displayName })
                if (activeRoom.localRole == CoopRole.HOST) {
                    session.pendingJoins.forEach { pending ->
                        Card(Modifier.fillMaxWidth(), border = elsewhereCardBorder()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Join approval required", style = MaterialTheme.typography.titleSmall)
                                Text("${pending.participantDisplayName} wants to join as ${pending.personaDisplayName}. Accept only if you recognize this request.")
                                Text(
                                    "Membership fingerprint: ${pending.membershipFingerprint}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        scope.launch {
                                            runCatching { manager.acceptPendingJoin(pending.participantId) }
                                                .onFailure { actionError = it.message }
                                        }
                                    }) { Text("Accept") }
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            runCatching { manager.rejectPendingJoin(pending.participantId) }
                                                .onFailure { actionError = it.message }
                                        }
                                    }) { Text("Reject") }
                                }
                            }
                        }
                    }
                    session.pairingCode
                        ?.takeIf { activeRoom.connectionState == CoopConnectionState.CONNECTED }
                        ?.let { code ->
                        Card(Modifier.fillMaxWidth(), border = elsewhereCardBorder()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Pair another device", style = MaterialTheme.typography.titleSmall)
                                Text("Addresses: ${session.suggestedAddresses.ifEmpty { listOf("Check this device's Wi-Fi details") }.joinToString()}")
                                Text("Port: ${activeRoom.hostPort}")
                                Text("Room ID: ${activeRoom.roomId}")
                                Text("Host fingerprint: ${activeRoom.hostKeyFingerprint}")
                                OutlinedTextField(
                                    value = hostInviteAddress,
                                    onValueChange = {},
                                    label = { Text("Bound private LAN address") },
                                    modifier = Modifier.fillMaxWidth(),
                                    readOnly = true,
                                    singleLine = true,
                                    colors = elsewhereTextFieldColors()
                                )
                                Text("The copied/shared bundle contains the room IDs, address, rotating invite secret, and pinned host key. Treat it like a temporary password.", style = MaterialTheme.typography.bodySmall)
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            runCatching { manager.exportInviteBundle() }
                                                .onSuccess {
                                                    copySensitiveInvite(context, it)
                                                    actionError = null
                                                }
                                                .onFailure { actionError = it.message }
                                        },
                                        enabled = hostInviteAddress.isNotBlank() && code.isNotBlank(),
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Copy invite") }
                                    OutlinedButton(
                                        onClick = {
                                            runCatching { manager.exportInviteBundle() }
                                                .onSuccess {
                                                    shareSensitiveInvite(context, it)
                                                    actionError = null
                                                }
                                                .onFailure { actionError = it.message }
                                        },
                                        enabled = hostInviteAddress.isNotBlank() && code.isNotBlank(),
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Share invite") }
                                }
                                Text("The high-entropy invite secret rotates whenever hosting resumes. The host identity stays pinned, and each enrolled member proves possession of a separate room-scoped identity key when reconnecting.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Text("Generation payer", style = MaterialTheme.typography.titleMedium)
                    listOf(
                        CoopPayerMode.EACH_PARTICIPANT to "Each participant",
                        CoopPayerMode.HOST to "Host",
                        CoopPayerMode.SELECTED_PARTICIPANT to "Selected participant"
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = activeRoom.payerMode == value,
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        manager.setPayerPolicy(
                                            chatId,
                                            value,
                                            if (value == CoopPayerMode.SELECTED_PARTICIPANT) {
                                                activeRoom.selectedPayerParticipantId ?: session.participants.firstOrNull()?.participantId
                                            } else null
                                        )
                                    }.onFailure { actionError = it.message }
                                }
                            },
                            label = { Text(label) }
                        )
                    }
                    if (activeRoom.payerMode == CoopPayerMode.SELECTED_PARTICIPANT) {
                        Text("Selected payer", style = MaterialTheme.typography.titleSmall)
                        session.participants.forEach { participant ->
                            FilterChip(
                                selected = activeRoom.selectedPayerParticipantId == participant.participantId,
                                onClick = {
                                    scope.launch {
                                        manager.setPayerPolicy(chatId, CoopPayerMode.SELECTED_PARTICIPANT, participant.participantId)
                                    }
                                },
                                label = { Text(participant.displayName) }
                            )
                        }
                    }
                }
                if (activeRoom.connectionState != CoopConnectionState.CONNECTED) {
                    if (activeRoom.localRole == CoopRole.HOST) {
                        Button(
                            onClick = { scope.launch {
                                sessionStartInProgress = true
                                try {
                                    runCatching { manager.resumeHosting(activeRoom.roomId) }
                                        .onFailure { actionError = it.message }
                                } finally {
                                    sessionStartInProgress = false
                                }
                            } },
                            enabled = !sessionStartInProgress && CoopSessionManager.LAN_NETWORKING_ENABLED,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Resume Hosting & Rotate Code") }
                    } else {
                        Text("Re-enter the current high-entropy invite secret to reconnect. This device must also prove its enrolled room identity; neither the participant ID nor invite secret alone can impersonate a member. The pinned host identity cannot change, and host migration is not supported.")
                        OutlinedTextField(pairingCode, { pairingCode = it.trim().take(128) }, label = { Text("Current invite secret") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = elsewhereTextFieldColors())
                        Button(
                            onClick = {
                                val local = session.localParticipant
                                val ownership = session.personaOwnerships.firstOrNull { it.participantId == activeRoom.localParticipantId }
                                if (local != null && ownership != null) scope.launch {
                                    sessionStartInProgress = true
                                    try {
                                        runCatching {
                                            manager.join(
                                                chatId = chatId,
                                                roomId = activeRoom.roomId,
                                                hostParticipantId = activeRoom.hostParticipantId,
                                                hostAddress = activeRoom.hostAddress.orEmpty(),
                                                port = activeRoom.hostPort,
                                                pairingCode = pairingCode,
                                                hostPublicKey = activeRoom.hostPublicKey,
                                                hostKeyFingerprint = activeRoom.hostKeyFingerprint,
                                                participantId = activeRoom.localParticipantId,
                                                participantDisplayName = local.displayName,
                                                personaId = ownership.personaId,
                                                personaDisplayName = ownership.personaDisplayNameSnapshot
                                            )
                                        }.onFailure { actionError = it.message }
                                    } finally {
                                        sessionStartInProgress = false
                                    }
                                }
                            },
                            enabled = !sessionStartInProgress && CoopSessionManager.LAN_NETWORKING_ENABLED && pairingCode.length >= 22 && !activeRoom.hostAddress.isNullOrBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Reconnect to Host") }
                    }
                }
                OutlinedButton(onClick = { showStopConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (activeRoom.localRole == CoopRole.HOST) "Stop hosting" else "Leave room")
                }
                if (activeRoom.localRole == CoopRole.HOST) {
                    TextButton(onClick = { showEndConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("End co-op mode and keep chat", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            val scopedSession = session.takeIf {
                loadedChatId == chatId && it.room?.chatId == chatId
            }
            (actionError ?: scopedSession?.errorMessage)?.let {
                Text("Error: $it", color = MaterialTheme.colorScheme.error)
            }
            scopedSession?.statusMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    pendingStartAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingStartAction = null },
            title = { Text(if (action == "HOST") "Start hosting on this LAN?" else "Join this LAN room?") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Room members receive this room's message text, speaker snapshots, participant/Persona labels, AI Character roster, payer policy, and generated results. API keys, full cards, private notes, memories, NPC records, Lorebook source/notes, images, and unrelated chats stay local. Approved generation context is sent separately from the payer device to its selected model provider."
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !sessionStartInProgress,
                    onClick = {
                    pendingStartAction = null
                    val persona = chatPersonas.firstOrNull { it.personaId == selectedPersonaId }
                    val port = portText.toIntOrNull()
                    if (persona != null && port != null && participantName.isNotBlank()) {
                        scope.launch {
                            actionError = null
                            sessionStartInProgress = true
                            try {
                                runCatching {
                                    if (action == "HOST") {
                                        manager.host(
                                            chatId,
                                            participantName,
                                            persona.personaId,
                                            persona.displayName,
                                            port,
                                            hostInviteAddress
                                        )
                                    } else {
                                        manager.join(
                                            chatId = chatId,
                                            roomId = roomId.trim(),
                                            hostParticipantId = hostParticipantId.trim(),
                                            hostAddress = hostAddress.trim(),
                                            port = port,
                                            pairingCode = pairingCode,
                                            hostPublicKey = hostPublicKey,
                                            hostKeyFingerprint = hostKeyFingerprint,
                                            participantId = UUID.randomUUID().toString(),
                                            participantDisplayName = participantName,
                                            personaId = persona.personaId,
                                            personaDisplayName = persona.displayName
                                        )
                                    }
                                }.onFailure { actionError = it.message ?: "Unable to start co-op." }
                            } finally {
                                sessionStartInProgress = false
                            }
                        }
                    }
                }) { Text(if (action == "HOST") "Confirm Host" else "Confirm Join") }
            },
            dismissButton = { TextButton(onClick = { pendingStartAction = null }) { Text("Cancel") } }
        )
    }
    if (showStopConfirm) {
        val room = session.room
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text(if (room?.localRole == CoopRole.HOST) "Stop hosting?" else "Leave room?") },
            text = { Text(if (room?.localRole == CoopRole.HOST) "The listener and peer connections will close. You can explicitly resume later with a newly rotated invite." else "The connection and this device's room membership record will be removed. Chat history stays saved.") },
            confirmButton = {
                Button(onClick = {
                    showStopConfirm = false
                    scope.launch {
                        if (room?.localRole == CoopRole.HOST) manager.disconnect() else manager.leaveRoom(chatId)
                    }
                }) { Text(if (room?.localRole == CoopRole.HOST) "Stop hosting" else "Leave room") }
            },
            dismissButton = { TextButton(onClick = { showStopConfirm = false }) { Text("Cancel") } }
        )
    }
    if (showEndConfirm) {
        AlertDialog(
            onDismissRequest = { showEndConfirm = false },
            title = { Text("End local co-op mode?") },
            text = { Text("This stops networking and removes only this device's co-op room records and room-scoped keys. Messages, characters, Personas, memories, Lorebooks, NPC records, and images stay saved.") },
            confirmButton = {
                Button(onClick = {
                    showEndConfirm = false
                    scope.launch { manager.leaveRoom(chatId) }
                }) { Text("End co-op mode") }
            },
            dismissButton = { TextButton(onClick = { showEndConfirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SharingBoundaryCard() {
    Card(Modifier.fillMaxWidth(), border = elsewhereCardBorder()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("LAN-only sharing boundary", style = MaterialTheme.typography.titleMedium)
            Text("Shared with paired room members: this room's message text and speaker snapshots, participant/device labels, Persona IDs and display names, active AI Character IDs/names/order, payer policy, and generated Character results.")
            Text("Not shared: API keys, Authorization headers, encrypted credentials, account-wide provider data, unrelated chats, full Character or Persona cards, private notes, author notes, memories, or Lorebook source/notes.")
            Text("Remote-paid generation sends only a host-approved context fingerprint. The payer compiles locally and no provider request is allowed unless its Character, Persona, Lorebook, memory, room history, and model settings match the host exactly.")
            Text("This bounded test mode pins a per-room AndroidKeyStore host identity and gives each enrolled member a separate room-scoped signing identity. Sessions are authenticated and encrypted, and the invite secret rotates when hosting resumes. Real two-phone behavior is not proven until you complete the acceptance checklist; Android security settings are not changed.")
            Text("Deletion authority: the host may delete any shared room message; otherwise only the participant responsible for that message may request deletion.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RoomStatusCard(state: com.example.domain.coop.CoopSessionState, characterNames: List<String>) {
    val room = state.room ?: return
    val payer = when (room.payerMode) {
        CoopPayerMode.HOST -> state.hostParticipant?.displayName ?: "Host"
        CoopPayerMode.SELECTED_PARTICIPANT -> state.participants.firstOrNull { it.participantId == room.selectedPayerParticipantId }?.displayName ?: "Not selected"
        else -> "Each requester"
    }
    Card(Modifier.fillMaxWidth(), border = elsewhereCardBorder()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Room status", style = MaterialTheme.typography.titleMedium)
            Text("Connection: ${friendlyConnectionState(room.connectionState)}")
            Text("Host: ${state.hostParticipant?.displayName ?: room.hostParticipantId}")
            Text("Payer: $payer")
            Text("Connected: ${state.participants.count { it.isConnected }} / ${state.participants.size}")
            if (room.localRole == CoopRole.CLIENT) {
                state.localParticipant?.deviceId?.let { fingerprint ->
                    Text("This device's membership fingerprint: $fingerprint", style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
            Text("Participating Personas", style = MaterialTheme.typography.titleSmall)
            state.personaOwnerships.forEach { ownership ->
                val owner = state.participants.firstOrNull { it.participantId == ownership.participantId }
                Text("${ownership.personaDisplayNameSnapshot} — ${owner?.displayName ?: "Unknown participant"}${if (ownership.isActive) "" else " (inactive)"}")
            }
            Text("AI speakers", style = MaterialTheme.typography.titleSmall)
            Text(characterNames.ifEmpty { listOf("No local Character cards available") }.joinToString())
        }
    }
}

private fun friendlyConnectionState(state: String): String = when (state) {
    CoopConnectionState.CONNECTED -> "Connected"
    CoopConnectionState.CONNECTING -> "Connecting / waiting for approval"
    CoopConnectionState.SUSPENDED_HOST_OFFLINE -> "Host offline"
    else -> "Disconnected"
}

private fun copySensitiveInvite(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText("Elsewhere co-op invite", text)
    clip.description.extras = PersistableBundle().apply {
        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
    }
    clipboard?.setPrimaryClip(clip)
}

private fun shareSensitiveInvite(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Elsewhere local co-op invite")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share sensitive co-op invite"))
}
