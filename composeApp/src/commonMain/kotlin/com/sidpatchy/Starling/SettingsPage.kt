package com.sidpatchy.Starling

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class SettingsSection(val label: String, val summary: String) {
    APPEARANCE("Appearance", "Theme and chat display"),
    NETWORK("Network", "Preview, node, and interface settings"),
    MESSAGING("Messaging", "RRC rooms and LXMF direct messages"),
    IDENTITY("Identity", "Identity keys and nicknames"),
}

@Composable
private fun RuntimeModeSelection(
    selected: ReticulumRuntimeMode,
    enabled: Boolean,
    onSelect: (ReticulumRuntimeMode) -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        listOf(ReticulumRuntimeMode.EMBEDDED, ReticulumRuntimeMode.EXISTING_SHARED_INSTANCE).forEach { mode ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                    .selectable(selected = selected == mode, enabled = enabled, role = Role.RadioButton) { onSelect(mode) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == mode, onClick = null, enabled = enabled)
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(
                        if (mode == ReticulumRuntimeMode.EMBEDDED) "Embedded Starling node" else "Existing local RNS instance",
                        color = Ink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (mode == ReticulumRuntimeMode.EXISTING_SHARED_INSTANCE) {
                        Text("Advanced", color = Muted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkHubPicker(
    networks: List<ChatNetwork>,
    selectedNetwork: ChatNetwork?,
    enabled: Boolean,
    onSelected: (ChatNetwork) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).background(Canvas)
                .border(1.dp, Line, RoundedCornerShape(8.dp))
                .clickable(enabled = enabled && networks.isNotEmpty(), role = Role.Button) { expanded = true }
                .semantics {
                    stateDescription = if (expanded) "Options shown" else "Options hidden"
                }
                .padding(horizontal = 12.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(selectedNetwork?.name ?: "No RRC network configured", modifier = Modifier.weight(1f), color = Ink, fontSize = 13.sp)
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = "Choose RRC network",
                tint = if (enabled && networks.isNotEmpty()) Accent else Muted,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            networks.forEach { network ->
                DropdownMenuItem(
                    text = { Text(network.name) },
                    onClick = { onSelected(network); expanded = false },
                )
            }
        }
    }
}

@Composable
internal fun SettingsPage(
    selectedSection: SettingsSection,
    onSectionSelected: (SettingsSection) -> Unit,
    showSectionIndex: Boolean,
    onShowSectionIndex: () -> Unit,
    themePreference: ThemePreference,
    onThemeChange: (ThemePreference) -> Unit,
    bubbleStyle: Boolean,
    onBubbleStyleChange: (Boolean) -> Unit,
    denseMessages: Boolean,
    onDenseMessagesChange: (Boolean) -> Unit,
    timestampTimeZone: TimestampTimeZonePreference,
    onTimestampTimeZoneChange: (TimestampTimeZonePreference) -> Unit,
    previewMode: Boolean,
    onPreviewModeChange: (Boolean) -> Unit,
    networks: List<ChatNetwork>,
    nodeConfig: ReticulumNodeConfig?,
    onNodeConfigChange: (ReticulumNodeConfig) -> Unit,
    globalNickname: String,
    preferredHubNetworkId: String?,
    onPreferredHubNetworkChange: (String?) -> Unit,
    nodeState: ReticulumNodeState,
    nodeSupported: Boolean,
    identityAvailable: Boolean,
    identityBusy: Boolean,
    onManageIdentity: () -> Unit,
    supportsReticulumProtocol: Boolean,
    supportsRNodeInterfaces: Boolean,
    rrcState: ReticulumConnectionState,
    rrcError: String?,
    localDeliveryAddress: String?,
    showNavigationButton: Boolean = false,
    onOpenNavigation: () -> Unit = {},
    onStartNode: (ReticulumNodeConfig) -> Unit,
    onDisconnectNode: () -> Unit,
    onConnectHub: (ReticulumConnectionConfig) -> Unit,
    onDisconnectHub: () -> Unit,
    onBack: () -> Unit,
) {
    var runtimeModeName by rememberSaveable {
        mutableStateOf(nodeConfig?.mode?.name ?: ReticulumRuntimeMode.EMBEDDED.name)
    }
    var tcpPeers by rememberSaveable(
        stateSaver = listSaver(
            save = { peers: List<ReticulumTcpPeer> ->
                peers.flatMap { peer ->
                    listOf(peer.host, peer.port.toString(), peer.ifacNetworkName.orEmpty(), peer.ifacPassphrase.orEmpty())
                }
            },
            restore = { saved: List<String> ->
                saved.chunked(4).mapNotNull { fields ->
                    if (fields.size < 4) null else ReticulumTcpPeer(
                        host = fields[0],
                        port = fields[1].toIntOrNull() ?: 0,
                        ifacNetworkName = fields[2].ifBlank { null },
                        ifacPassphrase = fields[3].ifBlank { null },
                    )
                }
            },
        ),
    ) { mutableStateOf(nodeConfig?.tcpPeers ?: listOf(ReticulumTcpPeer("", 4242))) }
    var rnodeInterfaces by rememberSaveable(
        stateSaver = listSaver(
            save = { radios: List<ReticulumRNodeInterface> ->
                radios.flatMap { radio ->
                    listOf(
                        radio.port,
                        radio.frequencyHz.toString(),
                        radio.bandwidthHz.toString(),
                        radio.txPowerDbm.toString(),
                        radio.spreadingFactor.toString(),
                        radio.codingRate.toString(),
                    )
                }
            },
            restore = { saved: List<String> ->
                saved.chunked(6).mapNotNull { fields ->
                    if (fields.size != 6) null else ReticulumRNodeInterface(
                        port = fields[0],
                        frequencyHz = fields[1].toLongOrNull() ?: 0L,
                        bandwidthHz = fields[2].toIntOrNull() ?: 0,
                        txPowerDbm = fields[3].toIntOrNull() ?: -1,
                        spreadingFactor = fields[4].toIntOrNull() ?: 0,
                        codingRate = fields[5].toIntOrNull() ?: 0,
                    )
                }
            },
        ),
    ) { mutableStateOf(nodeConfig?.rnodeInterfaces.orEmpty()) }
    var existingRpcKey by remember { mutableStateOf("") }
    var selectedHubNetworkId by rememberSaveable { mutableStateOf(preferredHubNetworkId.orEmpty()) }
    var connectionInputError by rememberSaveable { mutableStateOf<String?>(null) }
    val configuredNetworks = networks.filter { !it.isPreview && !it.hubDestination.isNullOrBlank() }
    val selectedHubNetwork = configuredNetworks.firstOrNull { it.id == selectedHubNetworkId } ?: configuredNetworks.firstOrNull()
    val selectedRrcNickname = selectedHubNetwork?.rrcNickname?.takeIf(String::isNotBlank) ?: globalNickname
    LaunchedEffect(preferredHubNetworkId, configuredNetworks) {
        val selectedId = preferredHubNetworkId?.takeIf { id -> configuredNetworks.any { it.id == id } }
            ?: configuredNetworks.firstOrNull()?.id.orEmpty()
        if (selectedHubNetworkId != selectedId) selectedHubNetworkId = selectedId
        if (preferredHubNetworkId == null && selectedId.isNotBlank()) onPreferredHubNetworkChange(selectedId)
    }
    val runtimeMode = runCatching { ReticulumRuntimeMode.valueOf(runtimeModeName) }
        .getOrDefault(ReticulumRuntimeMode.EMBEDDED)
    val isRunning = nodeState is ReticulumNodeState.Running
    val isStarting = nodeState is ReticulumNodeState.Starting || nodeState is ReticulumNodeState.PreparingRuntime
    val nodeConfigDraft = when (runtimeMode) {
        ReticulumRuntimeMode.EMBEDDED -> ReticulumNodeConfig(
            mode = runtimeMode,
            tcpPeers = tcpPeers.map { peer ->
                peer.copy(
                    host = peer.host.trim(),
                    ifacNetworkName = peer.ifacNetworkName?.trim()?.ifBlank { null },
                    ifacPassphrase = peer.ifacPassphrase?.takeIf { it.isNotBlank() },
                )
            },
            rnodeInterfaces = rnodeInterfaces.map { radio -> radio.copy(port = radio.port.trim()) },
        )
        ReticulumRuntimeMode.EXISTING_SHARED_INSTANCE -> ReticulumNodeConfig(
            mode = runtimeMode,
            existingInstanceRpcKeyHex = existingRpcKey,
        )
    }
    val nodeConfigValidationError = nodeConfigDraft.validationError()
    val nodeActionEnabled = nodeSupported && !isStarting && !identityBusy &&
        (isRunning || (identityAvailable && nodeConfigValidationError == null))
    val nodeActionBlockedReason = when {
        isRunning -> null
        isStarting -> "The Reticulum node is starting."
        !nodeSupported -> "Starting a node is not supported on this device."
        identityBusy -> "Wait for the identity operation to finish."
        !identityAvailable -> "Create or import a Reticulum identity before starting the node."
        else -> nodeConfigValidationError
    }
    LaunchedEffect(nodeState) {
        when (val state = nodeState) {
            is ReticulumNodeState.Running -> {
                runtimeModeName = state.mode.name
                if (tcpPeers.all { it.host.isBlank() } && state.tcpPeers.isNotEmpty()) {
                    tcpPeers = state.tcpPeers
                }
                if (rnodeInterfaces.isEmpty() && state.rnodeInterfaces.isNotEmpty()) {
                    rnodeInterfaces = state.rnodeInterfaces
                }
            }
            is ReticulumNodeState.Starting -> runtimeModeName = state.mode.name
            is ReticulumNodeState.PreparingRuntime -> Unit
            else -> Unit
        }
    }
    LaunchedEffect(runtimeModeName, tcpPeers, rnodeInterfaces) {
        onNodeConfigChange(
            ReticulumNodeConfig(
                mode = runCatching { ReticulumRuntimeMode.valueOf(runtimeModeName) }
                    .getOrDefault(ReticulumRuntimeMode.EMBEDDED),
                tcpPeers = tcpPeers,
                rnodeInterfaces = rnodeInterfaces,
            ),
        )
    }

    Column(Modifier.fillMaxSize().background(Canvas)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(68.dp).background(Paper)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Accent)
            }
            Spacer(Modifier.width(10.dp))
            Text("Settings", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (showNavigationButton) {
                Spacer(Modifier.weight(1f))
                NavigationToggle { onOpenNavigation() }
            }
        }

        val sectionContent: @Composable (Modifier) -> Unit = { contentModifier ->
        Column(
            modifier = contentModifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .preferredFrameRate(120f)
                .padding(horizontal = 24.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (selectedSection == SettingsSection.APPEARANCE) {
                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Paper)
                            .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(18.dp),
                    ) {
                        Text("Color theme", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Column(Modifier.selectableGroup()) {
                            ThemePreference.values().forEach { option ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                                        .selectable(
                                            selected = themePreference == option,
                                            role = Role.RadioButton,
                                        ) { onThemeChange(option) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = themePreference == option, onClick = null)
                                    Text(option.label, color = Ink, fontSize = 13.sp)
                                }
                            }
                        }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(value = bubbleStyle, role = Role.Switch, onValueChange = onBubbleStyleChange)
                            .semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Bubble messages", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Use a chat-bubble layout instead of the IRC-style timeline.", color = Muted, fontSize = 11.sp)
                        }
                        Switch(checked = bubbleStyle, onCheckedChange = null)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(value = denseMessages, role = Role.Switch, onValueChange = onDenseMessagesChange)
                            .semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Dense messages", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Reduce vertical spacing in room and direct-message history.", color = Muted, fontSize = 11.sp)
                        }
                        Switch(checked = denseMessages, onCheckedChange = null)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text("Message time zone", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("Choose how message timestamps are displayed.", color = Muted, fontSize = 11.sp)
                    Column(Modifier.selectableGroup()) {
                        TimestampTimeZonePreference.entries.forEach { option ->
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                                    .selectable(
                                        selected = timestampTimeZone == option,
                                        role = Role.RadioButton,
                                    ) { onTimestampTimeZoneChange(option) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = timestampTimeZone == option, onClick = null)
                                Text(option.label, color = Ink, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            if (selectedSection == SettingsSection.NETWORK) {
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Paper)
                        .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (supportsReticulumProtocol) "Reticulum node" else "TCP connectivity",
                            modifier = Modifier.weight(1f),
                            color = Ink,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (previewMode) "ISOLATED PREVIEW" else when (nodeState) {
                                    is ReticulumNodeState.Running -> if (nodeState.interfaceOnline) "CONNECTED" else "RETRYING"
                                    is ReticulumNodeState.Starting -> "STARTING"
                                    is ReticulumNodeState.PreparingRuntime -> "PREPARING"
                                    is ReticulumNodeState.Failed -> "ERROR"
                                    is ReticulumNodeState.Unavailable -> "UNAVAILABLE"
                                    ReticulumNodeState.Stopped -> "DISCONNECTED"
                                },
                            color = if (!previewMode && nodeState is ReticulumNodeState.Failed) ErrorText else Accent,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                        )
                    }
                    Text(
                        if (previewMode) {
                            "Preview mode is isolated. It shows sample data only and stops live Reticulum connections."
                        } else {
                            if (supportsReticulumProtocol) {
                                "Starting RNS manages the network node; RRC room traffic is a separate connection below."
                            } else {
                                "iOS can open a TCP connection to a configured peer. Reticulum packets, IFAC, RRC, and LXMF are not implemented on iOS yet."
                            }
                        },
                        color = Muted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(value = previewMode, role = Role.Switch) {
                                connectionInputError = null
                                onPreviewModeChange(it)
                            }
                            .semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Local preview", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Isolated sample data; live identity and connections are hidden.", color = Muted, fontSize = 11.sp)
                        }
                        Switch(checked = previewMode, onCheckedChange = null)
                    }

                    if (previewMode) {
                        Text(
                            "Live interfaces, shared-instance keys, and RRC/LXMF addresses are hidden while preview is on.",
                            color = Muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    } else {
                        Text(
                            if (!supportsReticulumProtocol) {
                                "TCP peer settings are temporary and are not retained after the app closes."
                            } else if (runtimeMode == ReticulumRuntimeMode.EMBEDDED) {
                                "Interface settings are saved and the node starts automatically in live mode on next launch."
                            } else {
                                "The shared-instance RPC key is session-only; re-enter it after restarting the app."
                            },
                            color = Muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                        if (supportsReticulumProtocol) {
                            RuntimeModeSelection(
                                selected = runtimeMode,
                                enabled = !isRunning && !isStarting && nodeSupported,
                                onSelect = { runtimeModeName = it.name; connectionInputError = null },
                            )
                        }

                        if (runtimeMode == ReticulumRuntimeMode.EMBEDDED) {
                            Text(
                                if (!supportsReticulumProtocol) {
                                    "This opens a plain TCP socket only; it does not exchange Reticulum traffic."
                                } else if (supportsRNodeInterfaces) {
                                    "Configure TCP peers and/or an RNode radio. Use a serial port on desktop, tcp://host for a Wi-Fi RNode on port 7633, or ble://device name for BLE on iOS and Android. Bare ble:// selects the first nearby RNode. Android and iOS support one BLE RNode at a time. Starling stays a client; Reticulum transport routing remains disabled."
                                } else {
                                    "Manage outbound TCP client interfaces. Starling stays a client; Reticulum transport routing remains disabled."
                                },
                                color = Muted,
                                fontSize = 11.sp,
                            )
                            tcpPeers.forEachIndexed { index, peer ->
                                Column(
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Canvas)
                                        .border(1.dp, Line, RoundedCornerShape(10.dp)).padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("TCP connection ${index + 1}", modifier = Modifier.weight(1f), color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        if ((tcpPeers.size > 1 || rnodeInterfaces.isNotEmpty()) && !isRunning && !isStarting) {
                                            TextButton(onClick = { tcpPeers = tcpPeers.filterIndexed { i, _ -> i != index }; connectionInputError = null }) {
                                                Text("Remove", color = ErrorText)
                                            }
                                        }
                                    }
                                    OutlinedTextField(
                                        value = peer.host,
                                        onValueChange = { value -> tcpPeers = tcpPeers.mapIndexed { i, old -> if (i == index) old.copy(host = value) else old }; connectionInputError = null },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Peer host or IP") },
                                        singleLine = true,
                                        enabled = !isRunning && !isStarting,
                                    )
                                    OutlinedTextField(
                                        value = if (peer.port > 0) peer.port.toString() else "",
                                        onValueChange = { value ->
                                            if (value.all(Char::isDigit)) {
                                                tcpPeers = tcpPeers.mapIndexed { i, old -> if (i == index) old.copy(port = value.toIntOrNull() ?: 0) else old }
                                                connectionInputError = null
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("TCP port") },
                                        singleLine = true,
                                        enabled = !isRunning && !isStarting,
                                    )
                                    if (supportsReticulumProtocol) {
                                        OutlinedTextField(
                                            value = peer.ifacNetworkName.orEmpty(),
                                            onValueChange = { value -> tcpPeers = tcpPeers.mapIndexed { i, old -> if (i == index) old.copy(ifacNetworkName = value.takeIf(String::isNotBlank)) else old }; connectionInputError = null },
                                            modifier = Modifier.fillMaxWidth(),
                                            label = { Text("IFAC network name (optional)") },
                                            singleLine = true,
                                            enabled = !isRunning && !isStarting,
                                        )
                                        OutlinedTextField(
                                            value = peer.ifacPassphrase.orEmpty(),
                                            onValueChange = { value -> tcpPeers = tcpPeers.mapIndexed { i, old -> if (i == index) old.copy(ifacPassphrase = value.takeIf(String::isNotBlank)) else old }; connectionInputError = null },
                                            modifier = Modifier.fillMaxWidth(),
                                            label = { Text("IFAC passphrase (optional)") },
                                            singleLine = true,
                                            visualTransformation = PasswordVisualTransformation(),
                                            enabled = !isRunning && !isStarting,
                                        )
                                    }
                                }
                            }
                            if (supportsReticulumProtocol) {
                                Text("IFAC values are per connection and must match that peer. Leave both blank to disable IFAC.", color = Muted, fontSize = 10.sp)
                            }
                        if (!isRunning && !isStarting) {
                            TextButton(onClick = { tcpPeers = tcpPeers + ReticulumTcpPeer("", 4242) }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Add, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Add TCP connection", color = Accent)
                                }
                            }
                            }
                            if (supportsRNodeInterfaces) {
                                rnodeInterfaces.forEachIndexed { index, radio ->
                                    Column(
                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Canvas)
                                            .border(1.dp, Line, RoundedCornerShape(10.dp)).padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(7.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("RNode radio ${index + 1}", modifier = Modifier.weight(1f), color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                            if (!isRunning && !isStarting) {
                                                TextButton(onClick = {
                                                    rnodeInterfaces = rnodeInterfaces.filterIndexed { radioIndex, _ -> radioIndex != index }
                                                    connectionInputError = null
                                                }) {
                                                    Text("Remove", color = ErrorText)
                                                }
                                            }
                                        }
                                        OutlinedTextField(
                                            value = radio.port,
                                            onValueChange = { value ->
                                                rnodeInterfaces = rnodeInterfaces.mapIndexed { radioIndex, old ->
                                                    if (radioIndex == index) old.copy(port = value) else old
                                                }
                                                connectionInputError = null
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            label = { Text("Serial port, tcp://host, or ble://device name") },
                                            singleLine = true,
                                            enabled = !isRunning && !isStarting,
                                        )
                                        OutlinedTextField(
                                            value = radio.frequencyHz.takeIf { it > 0 }?.toString().orEmpty(),
                                            onValueChange = { value ->
                                                if (value.all(Char::isDigit)) {
                                                    rnodeInterfaces = rnodeInterfaces.mapIndexed { radioIndex, old ->
                                                        if (radioIndex == index) old.copy(frequencyHz = value.toLongOrNull() ?: 0L) else old
                                                    }
                                                    connectionInputError = null
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            label = { Text("Radio frequency (Hz)") },
                                            singleLine = true,
                                            enabled = !isRunning && !isStarting,
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(
                                                value = radio.bandwidthHz.toString(),
                                                onValueChange = { value ->
                                                    if (value.all(Char::isDigit)) {
                                                        rnodeInterfaces = rnodeInterfaces.mapIndexed { radioIndex, old ->
                                                            if (radioIndex == index) old.copy(bandwidthHz = value.toIntOrNull() ?: 0) else old
                                                        }
                                                        connectionInputError = null
                                                    }
                                                },
                                                modifier = Modifier.weight(1f),
                                                label = { Text("Bandwidth (Hz)") },
                                                singleLine = true,
                                                enabled = !isRunning && !isStarting,
                                            )
                                            OutlinedTextField(
                                                value = radio.txPowerDbm.toString(),
                                                onValueChange = { value ->
                                                    if (value.all(Char::isDigit)) {
                                                        rnodeInterfaces = rnodeInterfaces.mapIndexed { radioIndex, old ->
                                                            if (radioIndex == index) old.copy(txPowerDbm = value.toIntOrNull() ?: 0) else old
                                                        }
                                                        connectionInputError = null
                                                    }
                                                },
                                                modifier = Modifier.weight(1f),
                                                label = { Text("TX power (dBm)") },
                                                singleLine = true,
                                                enabled = !isRunning && !isStarting,
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(
                                                value = radio.spreadingFactor.toString(),
                                                onValueChange = { value ->
                                                    if (value.all(Char::isDigit)) {
                                                        rnodeInterfaces = rnodeInterfaces.mapIndexed { radioIndex, old ->
                                                            if (radioIndex == index) old.copy(spreadingFactor = value.toIntOrNull() ?: 0) else old
                                                        }
                                                        connectionInputError = null
                                                    }
                                                },
                                                modifier = Modifier.weight(1f),
                                                label = { Text("Spreading factor") },
                                                singleLine = true,
                                                enabled = !isRunning && !isStarting,
                                            )
                                            OutlinedTextField(
                                                value = radio.codingRate.toString(),
                                                onValueChange = { value ->
                                                    if (value.all(Char::isDigit)) {
                                                        rnodeInterfaces = rnodeInterfaces.mapIndexed { radioIndex, old ->
                                                            if (radioIndex == index) old.copy(codingRate = value.toIntOrNull() ?: 0) else old
                                                        }
                                                        connectionInputError = null
                                                    }
                                                },
                                                modifier = Modifier.weight(1f),
                                                label = { Text("Coding rate (5–8)") },
                                                singleLine = true,
                                                enabled = !isRunning && !isStarting,
                                            )
                                        }
                                        radio.validationError()?.let { validationError ->
                                            Text(validationError, color = ErrorText, fontSize = 10.sp)
                                        }
                                    }
                                }
                                Text(
                                    "Set frequency, bandwidth, power, and spreading factor to match your RNode network and local radio rules. These settings control the radio itself.",
                                    color = Muted,
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp,
                                )
                                if (!isRunning && !isStarting) {
                                    TextButton(onClick = {
                                        if (tcpPeers.size == 1 && tcpPeers.single().host.isBlank()) {
                                            tcpPeers = emptyList()
                                        }
                                        rnodeInterfaces = rnodeInterfaces + ReticulumRNodeInterface(
                                            port = "",
                                            frequencyHz = 0L,
                                            bandwidthHz = 125_000,
                                            txPowerDbm = 0,
                                            spreadingFactor = 7,
                                            codingRate = 5,
                                        )
                                        connectionInputError = null
                            }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Add, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Add RNode interface", color = Accent)
                                }
                            }
                                }
                            }
                        } else {
                            Text(
                                "Advanced: attach to an RNS shared instance already running on this device. Its RPC key must match; default ports are 37428/37429.",
                                color = Muted,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            )
                            OutlinedTextField(
                                value = existingRpcKey,
                                onValueChange = { value -> existingRpcKey = value.filter { it.digitToIntOrNull(16) != null }; connectionInputError = null },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Shared instance RPC key (hex)") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                enabled = !isRunning && !isStarting,
                            )
                        }

                        val stateMessage = when (val state = nodeState) {
                            is ReticulumNodeState.Running -> when {
                                state.sharedInstanceConnected -> "Using the existing local Reticulum shared instance."
                                state.rnodeInterfaces.isNotEmpty() && state.tcpPeers.isNotEmpty() ->
                                    "${state.onlineTcpPeerIds.size} of ${state.tcpPeers.size} TCP connections online; " +
                                        "${state.onlineRNodePorts.size} of ${state.rnodeInterfaces.size} RNode radios online."
                                state.rnodeInterfaces.isNotEmpty() -> "${state.onlineRNodePorts.size} of ${state.rnodeInterfaces.size} RNode radios online. " +
                                    state.rnodeInterfaces.joinToString { radio -> "${radio.port} ${if (radio.id in state.onlineRNodePorts) "(online)" else "(retrying)"}" }
                                state.tcpPeers.isNotEmpty() -> "${state.onlineTcpPeerIds.size} of ${state.tcpPeers.size} TCP connections online. " +
                                    (if (supportsReticulumProtocol) "" else "Socket check only; RNS traffic is not implemented on iOS. ") +
                                    state.tcpPeers.joinToString {
                                        "${it.host}:${it.port} ${if (it.id in state.onlineTcpPeerIds) "(online)" else if (supportsReticulumProtocol) "(retrying)" else "(offline)"}"
                                    }
                                else -> "RNS node is running without a configured interface."
                            }
                            is ReticulumNodeState.Starting -> "Starting the Reticulum node…"
                            is ReticulumNodeState.PreparingRuntime -> state.detail
                            is ReticulumNodeState.Failed -> state.message
                            is ReticulumNodeState.Unavailable -> state.reason
                            ReticulumNodeState.Stopped -> if (nodeSupported) {
                                if (supportsReticulumProtocol) "No Reticulum node is running. The app remains in local preview."
                                else "No TCP connection is open. Turn off local preview to test a peer."
                            } else "Embedded RNS is not available on this platform yet."
                        }
                        Text(stateMessage, color = if (nodeState is ReticulumNodeState.Failed) ErrorText else Muted, fontSize = 11.sp)
                        connectionInputError?.let {
                            Text(
                                it,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                                color = ErrorText,
                                fontSize = 11.sp,
                            )
                        }

                        if (nodeActionBlockedReason != null && !isStarting) {
                            Column(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .background(if (!identityAvailable) PaleAccent else Canvas)
                                    .border(1.dp, Line, RoundedCornerShape(10.dp)).padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    if (!identityAvailable) "Identity required to start the node" else "Node can’t start yet",
                                    color = Ink,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(nodeActionBlockedReason, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                                if (!identityAvailable) {
                                    TextButton(onClick = onManageIdentity) { Text("Set up identity") }
                                }
                            }
                        }

                        Button(
                            enabled = nodeActionEnabled,
                            onClick = {
                                if (isRunning) {
                                    onDisconnectNode()
                                } else {
                                    connectionInputError = nodeConfigValidationError
                                    if (nodeConfigValidationError == null) onStartNode(nodeConfigDraft)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        ) {
                            Text(
                                if (isRunning) {
                                    if (supportsReticulumProtocol) "Disconnect node" else "Disconnect TCP"
                                } else if (isStarting) "Connecting…" else if (supportsReticulumProtocol) "Start node" else "Connect TCP",
                            )
                        }
                    }
                }
            }
            if (selectedSection == SettingsSection.MESSAGING) {
                if (!supportsReticulumProtocol) {
                    Text(
                        "RRC room chat and LXMF direct messaging are not implemented on iOS yet. The TCP check above only opens a socket.",
                        color = Muted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                } else if (previewMode) {
                    Text(
                        "Preview messages are simulated locally. RRC hub details and the LXMF delivery address are hidden.",
                        color = Muted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Paper)
                            .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Room chat", modifier = Modifier.weight(1f), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                when (rrcState) {
                                    ReticulumConnectionState.Disconnected -> "DISCONNECTED"
                                    ReticulumConnectionState.Starting -> "STARTING"
                                    ReticulumConnectionState.Connecting -> "CONNECTING"
                                    is ReticulumConnectionState.Connected -> "CONNECTED"
                                    is ReticulumConnectionState.Failed -> "ERROR"
                                },
                                color = if (rrcState is ReticulumConnectionState.Failed) ErrorText else Accent,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp,
                            )
                        }
                        Text("Choose one of your configured RRC networks. Starling joins its channel names; RRC does not provide room enumeration or history.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                        NetworkHubPicker(
                            networks = configuredNetworks,
                            selectedNetwork = selectedHubNetwork,
                            enabled = rrcState !is ReticulumConnectionState.Connected && rrcState !is ReticulumConnectionState.Connecting,
                            onSelected = {
                                selectedHubNetworkId = it.id
                                onPreferredHubNetworkChange(it.id)
                            },
                        )
                        if (selectedHubNetwork != null) {
                            Text("Destination · ${selectedHubNetwork.hubDestination}", color = Muted, fontSize = 10.sp)
                        } else {
                            Text("Add a network with an RRC hub destination using + in the Networks list.", color = Muted, fontSize = 11.sp)
                        }
                        Text("Your nickname on this hub: $selectedRrcNickname", color = Muted, fontSize = 11.sp)
                        val rrcStatusText = when (val state = rrcState) {
                            ReticulumConnectionState.Disconnected -> "Room messages stay in local preview until an RRC hub welcomes this client."
                            ReticulumConnectionState.Starting -> "Preparing the RRC session…"
                            ReticulumConnectionState.Connecting -> "Establishing a Reticulum Link and waiting for WELCOME…"
                            is ReticulumConnectionState.Connected -> "Connected${state.hubName?.takeIf { it.isNotBlank() }?.let { " to $it" }.orEmpty()}. Room messages now use RRC."
                            is ReticulumConnectionState.Failed -> state.message
                        }
                        Text(rrcStatusText, color = if (rrcState is ReticulumConnectionState.Failed) ErrorText else Muted, fontSize = 11.sp)
                        rrcError?.let {
                            Text(
                                it,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                                color = ErrorText,
                                fontSize = 11.sp,
                            )
                        }
                        val rrcBusy = rrcState is ReticulumConnectionState.Starting || rrcState is ReticulumConnectionState.Connecting
                        val rrcActive = rrcState is ReticulumConnectionState.Connected
                        Button(
                            enabled = if (rrcActive || rrcBusy) true else nodeState is ReticulumNodeState.Running && selectedHubNetwork != null,
                            onClick = {
                                if (rrcActive || rrcBusy) onDisconnectHub()
                                else selectedHubNetwork?.hubDestination?.let { destination ->
                                    onConnectHub(ReticulumConnectionConfig(destination, selectedRrcNickname, selectedHubNetwork.id))
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        ) {
                            Text(if (rrcActive) "Disconnect from hub" else if (rrcBusy) "Cancel connection" else "Connect to hub")
                        }
                    }
                    SettingsCard(
                        title = "Direct messages",
                        subtitle = "LXMF",
                        description = if (localDeliveryAddress == null) {
                            "Start the Reticulum node to receive a shareable LXMF address and use direct delivery. Unknown senders appear in Requests."
                        } else {
                            "Your LXMF delivery destination is active. Messages from contacts open in their conversation; unknown senders go to Requests."
                        },
                    )
                    if (localDeliveryAddress != null) {
                        Column(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Paper)
                                .border(1.dp, Line, RoundedCornerShape(12.dp)).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("YOUR LXMF ADDRESS · SELECT TO COPY", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                            SelectionContainer {
                                Text("lxmf://$localDeliveryAddress", color = Accent, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        if (maxWidth >= 900.dp) {
            Row(Modifier.fillMaxSize()) {
                SettingsSectionNavigation(
                    modifier = Modifier.width(250.dp).fillMaxHeight(),
                    selectedSection = selectedSection,
                    onSectionSelected = onSectionSelected,
                    onManageIdentity = onManageIdentity,
                )
                Column(Modifier.weight(1f).fillMaxHeight().background(Canvas)) {
                    Text(
                        selectedSection.label,
                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp),
                        color = Ink,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    sectionContent(Modifier.weight(1f))
                }
            }
        } else if (showSectionIndex) {
            SettingsSectionNavigation(
                modifier = Modifier.fillMaxSize(),
                selectedSection = selectedSection,
                onSectionSelected = onSectionSelected,
                onManageIdentity = onManageIdentity,
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp).background(Paper),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onShowSectionIndex) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to settings", tint = Accent)
                    }
                    Text(selectedSection.label, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                sectionContent(Modifier.weight(1f))
            }
        }
        }
    }
}

@Composable
private fun SettingsSectionNavigation(
    modifier: Modifier,
    selectedSection: SettingsSection,
    onSectionSelected: (SettingsSection) -> Unit,
    onManageIdentity: () -> Unit,
) {
    Column(
        modifier = modifier.background(Paper).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("SETTINGS", color = Muted, fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        SettingsSection.entries.forEach { section ->
            val isSelected = section == selectedSection
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) PaleAccent else Color.Transparent)
                    .clickable(role = Role.Button) {
                        if (section == SettingsSection.IDENTITY) onManageIdentity()
                        else onSectionSelected(section)
                    }
                    .semantics { selected = isSelected }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        section.label,
                        color = if (isSelected) Accent else Ink,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                    Text(section.summary, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, subtitle: String, description: String, status: String? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Paper)
            .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            status?.let { Text(it, color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp) }
        }
        Text(subtitle, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(description, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}
