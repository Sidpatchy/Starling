package dev.starling

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class IdentityConfirmation(val title: String, val message: String, val actionLabel: String) {
    IMPORT(
        title = "Import identity?",
        message = "Choose a standard Reticulum private identity backup to use on this device.",
        actionLabel = "Choose file",
    ),
    REPLACE(
        title = "Replace identity?",
        message = "This replaces the identity currently saved in Starling and changes your delivery address. Export the current identity first if you may need another copy. Existing backups remain usable.",
        actionLabel = "Replace",
    ),
    EXPORT(
        title = "Export private identity?",
        message = "The exported file contains your unencrypted private keys. Anyone with it can impersonate you. Save it somewhere secure, not in a public or shared folder.",
        actionLabel = "Export",
    ),
    DELETE(
        title = "Delete identity?",
        message = "This removes the private identity from this app. Without a backup it cannot be recovered. Contacts and chat history are kept, and existing backups remain usable.",
        actionLabel = "Delete",
    ),
}

@Composable
internal fun IdentityPage(
    previewMode: Boolean,
    nodeState: ReticulumNodeState,
    rrcState: ReticulumConnectionState,
    identityHash: String?,
    localDeliveryAddress: String?,
    identityState: ReticulumIdentityState,
    identityBusy: Boolean,
    identityError: String?,
    identityNotice: String?,
    identitySupported: Boolean,
    onCreateIdentity: () -> Unit,
    onImportIdentity: () -> Unit,
    onExportIdentity: () -> Unit,
    onDeleteIdentity: () -> Unit,
    onDisconnectNode: () -> Unit,
    globalNickname: String,
    networks: List<ChatNetwork>,
    onGlobalNicknameChange: (String) -> Unit,
    onNetworkNicknameChange: (String, String?) -> Unit,
    compact: Boolean = false,
    showBackButton: Boolean = false,
    onBack: () -> Unit = {},
    showNavigationButton: Boolean = false,
    onOpenNavigation: () -> Unit = {},
    onManageNetwork: () -> Unit,
) {
    var globalNicknameDraft by rememberSaveable { mutableStateOf(globalNickname) }
    LaunchedEffect(globalNickname) { globalNicknameDraft = globalNickname }
    val normalizedGlobalNickname = normalizeStarlingNickname(globalNicknameDraft)
    var identityConfirmation by remember { mutableStateOf<IdentityConfirmation?>(null) }
    val identityAvailable = identityState is ReticulumIdentityState.Available
    val identityConfigured = identityAvailable || (!identitySupported && !identityHash.isNullOrBlank())
    val identityMutable = identitySupported && !identityBusy && nodeState == ReticulumNodeState.Stopped
    val identityActionHeight = 56.dp

    Column(Modifier.fillMaxSize().background(Canvas)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(68.dp).background(Paper)
                .padding(horizontal = if (compact) 8.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBackButton) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to settings", tint = Accent)
                }
                Spacer(Modifier.width(6.dp))
            } else if (compact || showNavigationButton) {
                NavigationToggle(onClick = onOpenNavigation)
                Spacer(Modifier.width(6.dp))
            }
            Text("Identity", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .preferredFrameRate(120f).padding(horizontal = 24.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("DEVICE IDENTITY", color = Muted, fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Paper)
                    .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text("Reticulum identity", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (previewMode) {
                    Text(
                        "Live identity details are hidden while isolated preview is active.",
                        color = Muted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                } else {
                    when {
                        identityState == ReticulumIdentityState.Loading -> {
                            Text("Checking for a saved identity…", color = Muted, fontSize = 12.sp)
                        }
                        identityState is ReticulumIdentityState.Failed -> {
                            Text("Identity could not be loaded", color = Color(0xFFC44545), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("You can import a valid backup to replace the unreadable identity.", color = Muted, fontSize = 11.sp)
                        }
                        identityConfigured -> {
                            Text("IDENTITY HASH", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                            SelectionContainer {
                                Text(identityHash.orEmpty(), color = Accent, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            }
                            Text(
                                "This is your public identity hash. Your private key stays in app-private storage and is never displayed.",
                                color = Muted,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("LXMF DELIVERY ADDRESS", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                            if (localDeliveryAddress.isNullOrBlank()) {
                                Text("Not available right now.", color = Muted, fontSize = 12.sp)
                            } else {
                                SelectionContainer {
                                    Text("lxmf://$localDeliveryAddress", color = Accent, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                        else -> {
                            Text("No identity set up yet", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "Create an identity for this device or import an existing Reticulum backup. You’ll need one before starting live networking.",
                                color = Muted,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                            )
                        }
                    }

                    if (identitySupported) {
                        Spacer(Modifier.height(6.dp))
                        when (identityState) {
                            ReticulumIdentityState.Missing -> Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = onCreateIdentity,
                                    modifier = Modifier.weight(1f).height(identityActionHeight),
                                    enabled = identityMutable,
                                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                                ) { Text("Create identity", textAlign = TextAlign.Center) }
                                OutlinedButton(
                                    onClick = { identityConfirmation = IdentityConfirmation.IMPORT },
                                    modifier = Modifier.weight(1f).height(identityActionHeight),
                                    enabled = identityMutable,
                                ) { Text("Import backup", textAlign = TextAlign.Center) }
                            }
                            is ReticulumIdentityState.Available -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = { identityConfirmation = IdentityConfirmation.EXPORT },
                                        modifier = Modifier.weight(1f).height(identityActionHeight),
                                        enabled = !identityBusy,
                                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                                    ) { Text("Export backup", textAlign = TextAlign.Center) }
                                    OutlinedButton(
                                        onClick = { identityConfirmation = IdentityConfirmation.REPLACE },
                                        modifier = Modifier.weight(1f).height(identityActionHeight),
                                        enabled = identityMutable,
                                    ) { Text("Replace identity", textAlign = TextAlign.Center) }
                                }
                                TextButton(
                                    onClick = { identityConfirmation = IdentityConfirmation.DELETE },
                                    enabled = identityMutable,
                                ) { Text("Delete identity", color = Color(0xFFC44545)) }
                            }
                            is ReticulumIdentityState.Failed -> OutlinedButton(
                                onClick = { identityConfirmation = IdentityConfirmation.IMPORT },
                                enabled = identityMutable,
                            ) { Text("Import identity backup") }
                            ReticulumIdentityState.Loading -> Unit
                        }
                        if (nodeState != ReticulumNodeState.Stopped && identityState != ReticulumIdentityState.Loading) {
                            Text("Disconnect the node before creating, replacing, or deleting an identity.", color = Muted, fontSize = 11.sp)
                            TextButton(onClick = onDisconnectNode, enabled = !identityBusy) { Text("Disconnect node") }
                        }
                        if (identityBusy) Text("Identity operation in progress…", color = Muted, fontSize = 11.sp)
                        identityError?.let { Text(it, color = Color(0xFFC44545), fontSize = 12.sp) }
                        identityNotice?.let { Text(it, color = Muted, fontSize = 12.sp) }
                        Text(
                            "Identity backups are unencrypted private-key files. Anyone with a copy can use your identity, so keep backups somewhere secure.",
                            color = Muted, fontSize = 11.sp, lineHeight = 16.sp,
                        )
                    } else {
                        Text("Identity management isn’t available here.", color = Muted, fontSize = 11.sp)
                    }
                }
            }

            Text("NICKNAMES", color = Muted, fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Paper)
                    .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Global nickname", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Used for your LXMF identity in direct messages and as the default nickname for RRC hubs.",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                OutlinedTextField(
                    value = globalNicknameDraft,
                    onValueChange = { globalNicknameDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Global nickname") },
                    singleLine = true,
                )
                Button(
                    enabled = normalizedGlobalNickname != globalNickname,
                    onClick = { onGlobalNicknameChange(normalizedGlobalNickname) },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) {
                    Text("Save global nickname")
                }
                if (previewMode) {
                    Text(
                        "This preference is saved locally and applied when Local preview is turned off. Live hub nicknames are hidden in preview.",
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                } else {
                    val configuredHubs = networks.filterNot { it.isPreview }
                    Text("RRC hub nicknames", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Set an override for a specific hub. Leave it blank to use the global nickname. Changing an active hub's nickname reconnects it and rejoins your channels.",
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    if (configuredHubs.isEmpty()) {
                        Text("Add an RRC hub in Network settings to configure a hub-specific nickname.", color = Muted, fontSize = 11.sp)
                    } else {
                        configuredHubs.forEach { network ->
                            HubNicknameEditor(
                                network = network,
                                globalNickname = globalNickname,
                                onSave = { onNetworkNicknameChange(network.id, it) },
                            )
                        }
                    }
                }
            }

            Text("CONNECTION STATUS", color = Muted, fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Paper)
                    .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text("Reticulum node", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(if (previewMode) "Stopped in isolated preview" else reticulumNodeSummary(nodeState), color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                Text("RRC hub", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(if (previewMode) "Live hub status is hidden in preview" else rrcConnectionSummary(rrcState), color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            }

            Text(
                "Manage identity keys here. Node interfaces and hub selection are managed in Network settings.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Button(onClick = onManageNetwork, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text("Network settings")
            }
        }
    }
    identityConfirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { identityConfirmation = null },
            title = { Text(action.title) },
            text = { Text(action.message) },
            confirmButton = {
                TextButton(onClick = {
                    identityConfirmation = null
                    when (action) {
                        IdentityConfirmation.DELETE -> onDeleteIdentity()
                        IdentityConfirmation.EXPORT -> onExportIdentity()
                        IdentityConfirmation.IMPORT -> onImportIdentity()
                        IdentityConfirmation.REPLACE -> onImportIdentity()
                    }
                }, enabled = !identityBusy) { Text(action.actionLabel) }
            },
            dismissButton = { TextButton(onClick = { identityConfirmation = null }) { Text("Cancel") } },
        )
    }
}

private fun reticulumNodeSummary(state: ReticulumNodeState): String = when (state) {
    ReticulumNodeState.Stopped -> "Stopped."
    is ReticulumNodeState.Starting -> "Starting the ${state.mode.name.lowercase().replace('_', ' ')} node…"
    is ReticulumNodeState.PreparingRuntime -> state.detail
    is ReticulumNodeState.Failed -> state.message
    is ReticulumNodeState.Unavailable -> state.reason
    is ReticulumNodeState.Running -> when {
        state.sharedInstanceConnected -> "Attached to the existing local RNS shared instance."
        else -> {
            val online = state.onlineTcpPeerIds.size + state.onlineRNodePorts.size
            val total = state.tcpPeers.size + state.rnodeInterfaces.size
            if (total == 0) "Running without a configured interface."
            else "$online of $total configured interfaces online."
        }
    }
}

private fun rrcConnectionSummary(state: ReticulumConnectionState): String = when (state) {
    ReticulumConnectionState.Disconnected -> "Disconnected from an RRC hub."
    ReticulumConnectionState.Starting -> "Preparing the RRC session…"
    ReticulumConnectionState.Connecting -> "Connecting to the selected RRC hub…"
    is ReticulumConnectionState.Connected -> "Connected${state.hubName?.takeIf(String::isNotBlank)?.let { " to $it" }.orEmpty()}."
    is ReticulumConnectionState.Failed -> state.message
}

@Composable
private fun HubNicknameEditor(
    network: ChatNetwork,
    globalNickname: String,
    onSave: (String?) -> Unit,
) {
    var nicknameDraft by rememberSaveable(network.id) { mutableStateOf(network.rrcNickname.orEmpty()) }
    LaunchedEffect(network.rrcNickname) { nicknameDraft = network.rrcNickname.orEmpty() }
    val normalizedNickname = nicknameDraft.trim().takeIf(String::isNotEmpty)?.let(::normalizeStarlingNickname)

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Canvas).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(network.name, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (normalizedNickname == null) "Using global nickname · $globalNickname" else "Hub nickname · $normalizedNickname",
            color = Muted,
            fontSize = 10.sp,
        )
        OutlinedTextField(
            value = nicknameDraft,
            onValueChange = { nicknameDraft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Hub-specific nickname") },
            placeholder = { Text("$globalNickname (global)") },
            singleLine = true,
        )
        Button(
            enabled = normalizedNickname != network.rrcNickname,
            onClick = { onSave(normalizedNickname) },
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
        ) {
            Text(if (normalizedNickname == null) "Use global nickname" else "Save hub nickname")
        }
    }
}
