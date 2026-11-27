package com.sidpatchy.Starling

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class SettingsSection(val label: String, val summary: String) {
    APPEARANCE("Appearance", "Theme and chat display"),
    NETWORK("Network", "Preview, node, and interface settings"),
    MESSAGING("Messaging", "RRC rooms and LXMF direct messages"),
    IDENTITY("Identity", "Identity keys and nicknames"),
    OPEN_SOURCE("Open source", "Licenses and acknowledgements"),
}

internal enum class SettingsBackTarget {
    SETTINGS_INDEX,
    EXIT_SETTINGS,
}

internal fun settingsBackTarget(isWideLayout: Boolean, showSectionIndex: Boolean): SettingsBackTarget =
    if (!isWideLayout && !showSectionIndex) SettingsBackTarget.SETTINGS_INDEX else SettingsBackTarget.EXIT_SETTINGS

internal val SettingsTwoPaneMinWidth = 900.dp

@Composable
private fun SettingsBackButton(label: String, onClick: () -> Unit) {
    val isIos = LocalStarlingUiStyle.current == StarlingUiStyle.IOS
    if (isIos) {
        Row(
            modifier = Modifier.heightIn(min = 44.dp).clickable(
                role = Role.Button,
                indication = null,
                interactionSource = null,
                onClick = onClick,
            ).padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBackIos, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
            Text(label, color = Accent, fontSize = 15.sp, lineHeight = 19.sp)
        }
        return
    }
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = Accent),
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/** Decorative switch; the whole settings row owns the toggle and accessibility semantics. */
@Composable
private fun SettingsSwitch(checked: Boolean, enabled: Boolean = true) {
    if (LocalStarlingUiStyle.current != StarlingUiStyle.IOS) {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        return
    }
    val offset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = if (LocalReducedMotion.current) snap() else tween(160),
        label = "Switch thumb",
    )
    Box(
        Modifier.size(50.dp, 30.dp).alpha(if (enabled) 1f else 0.5f)
            .background(if (checked) Accent else Muted.copy(alpha = 0.38f), CircleShape),
    ) {
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = offset).size(26.dp)
                .background(Color.White, CircleShape),
        )
    }
}

@Composable
private fun RuntimeModeSelection(
    selected: ReticulumRuntimeMode,
    enabled: Boolean,
    onDismissKeyboard: () -> Unit,
    onSelect: (ReticulumRuntimeMode) -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        listOf(ReticulumRuntimeMode.EMBEDDED, ReticulumRuntimeMode.EXISTING_SHARED_INSTANCE).forEach { mode ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                    .selectable(selected = selected == mode, enabled = enabled, role = Role.RadioButton) {
                        onDismissKeyboard()
                        onSelect(mode)
                    },
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
    onDismissKeyboard: () -> Unit,
    onSelected: (ChatNetwork) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).background(Canvas)
                .border(1.dp, Line, RoundedCornerShape(8.dp))
                .clickable(enabled = enabled && networks.isNotEmpty(), role = Role.Button) {
                    onDismissKeyboard()
                    expanded = true
                }
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
    isWideLayout: Boolean,
    selectedSection: SettingsSection,
    onSectionSelected: (SettingsSection) -> Unit,
    showSectionIndex: Boolean,
    themePreference: ThemePreference,
    onThemeChange: (ThemePreference) -> Unit,
    bubbleStyle: Boolean,
    onBubbleStyleChange: (Boolean) -> Unit,
    denseMessages: Boolean,
    onDenseMessagesChange: (Boolean) -> Unit,
    reduceMotion: Boolean,
    onReduceMotionChange: (Boolean) -> Unit,
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
    identityContent: @Composable (Modifier, Dp) -> Unit,
    supportsReticulumProtocol: Boolean,
    supportsRNodeInterfaces: Boolean,
    supportsBleRNodes: Boolean = false,
    rnodeBlePicker: RNodeBlePicker? = null,
    resolvedBleRNode: ResolvedBleRNode? = null,
    rnodeUpgradeNote: String? = null,
    supportsBlePeering: Boolean = false,
    rrcState: ReticulumConnectionState,
    rrcError: String?,
    localDeliveryAddress: String?,
    onDismissKeyboard: () -> Unit = {},
    onStartNode: (ReticulumNodeConfig) -> Unit,
    onDisconnectNode: () -> Unit,
    onConnectHub: (ReticulumConnectionConfig) -> Unit,
    onDisconnectHub: () -> Unit,
    onOpenNetworkDiagnostics: () -> Unit,
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
    ) { mutableStateOf(nodeConfig?.tcpPeers.orEmpty().withoutEmptyDefaultTcpPeer()) }
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
                        radio.bleName.orEmpty(),
                        if (radio.pairedByStarling) "1" else "0",
                    )
                }
            },
            restore = { saved: List<String> ->
                saved.chunked(8).mapNotNull { fields ->
                    if (fields.size != 8) null else ReticulumRNodeInterface(
                        port = fields[0],
                        frequencyHz = fields[1].toLongOrNull() ?: 0L,
                        bandwidthHz = fields[2].toIntOrNull() ?: 0,
                        txPowerDbm = fields[3].toIntOrNull() ?: -1,
                        spreadingFactor = fields[4].toIntOrNull() ?: 0,
                        codingRate = fields[5].toIntOrNull() ?: 0,
                        bleName = fields[6].ifBlank { null },
                        pairedByStarling = fields[7] == "1",
                    )
                }
            },
        ),
    ) { mutableStateOf(nodeConfig?.rnodeInterfaces.orEmpty()) }
    var blePeeringEnabled by rememberSaveable { mutableStateOf(nodeConfig?.blePeeringEnabled ?: false) }
    var existingRpcKey by remember { mutableStateOf("") }
    var selectedHubNetworkId by rememberSaveable { mutableStateOf(preferredHubNetworkId.orEmpty()) }
    var connectionInputError by rememberSaveable { mutableStateOf<String?>(null) }
    var showAddConnection by rememberSaveable { mutableStateOf(false) }
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
            blePeeringEnabled = supportsBlePeering && blePeeringEnabled,
        )
        ReticulumRuntimeMode.EXISTING_SHARED_INSTANCE -> ReticulumNodeConfig(
            mode = runtimeMode,
            existingInstanceRpcKeyHex = existingRpcKey,
        )
    }
    val nodeConfigValidationError = nodeConfigDraft.validationError(supportsBleRNodes)
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
                if (state.blePeeringEnabled) blePeeringEnabled = true
            }
            is ReticulumNodeState.Starting -> runtimeModeName = state.mode.name
            is ReticulumNodeState.PreparingRuntime -> Unit
            else -> Unit
        }
    }
    LaunchedEffect(resolvedBleRNode) {
        // The node connected to a Bluetooth RNode saved by name: keep its id from now on.
        val resolved = resolvedBleRNode ?: return@LaunchedEffect
        upgradeRNodeBleInterfaces(rnodeInterfaces, resolved)?.let { (upgraded, _) -> rnodeInterfaces = upgraded }
    }
    LaunchedEffect(runtimeModeName, tcpPeers, rnodeInterfaces, blePeeringEnabled) {
        onNodeConfigChange(
            ReticulumNodeConfig(
                mode = runCatching { ReticulumRuntimeMode.valueOf(runtimeModeName) }
                    .getOrDefault(ReticulumRuntimeMode.EMBEDDED),
                tcpPeers = tcpPeers,
                rnodeInterfaces = rnodeInterfaces,
                blePeeringEnabled = supportsBlePeering && blePeeringEnabled,
            ),
        )
    }

    Column(Modifier.fillMaxSize().background(Canvas)) {
        val isIos = LocalStarlingUiStyle.current == StarlingUiStyle.IOS
        val showingCompactSection = settingsBackTarget(isWideLayout, showSectionIndex) == SettingsBackTarget.SETTINGS_INDEX
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = if (isIos) 44.dp else 68.dp).background(Paper)
                .padding(horizontal = if (isWideLayout) 20.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsBackButton(if (showingCompactSection) "Back to Settings" else "Back to Chat", onBack)
            Spacer(Modifier.width(10.dp))
            Text(
                if (showingCompactSection) selectedSection.label else "Settings",
                modifier = Modifier.weight(1f).semantics { heading() },
                color = Ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isIos) HorizontalDivider(color = Line)

        val sectionContent: @Composable (Modifier, Dp) -> Unit = { contentModifier, contentHorizontalPadding ->
        Column(
            modifier = contentModifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .preferredFrameRate(120f)
                .padding(horizontal = contentHorizontalPadding, vertical = 26.dp),
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
                                        ) {
                                            onDismissKeyboard()
                                            onThemeChange(option)
                                        },
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
                            .toggleable(value = bubbleStyle, role = Role.Switch) {
                                onDismissKeyboard()
                                onBubbleStyleChange(it)
                            }
                            .semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Bubble messages", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Use a chat-bubble layout instead of the IRC-style timeline.", color = Muted, fontSize = 11.sp)
                        }
                        SettingsSwitch(checked = bubbleStyle)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(value = denseMessages, role = Role.Switch) {
                                onDismissKeyboard()
                                onDenseMessagesChange(it)
                            }
                            .semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Dense messages", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Reduce vertical spacing in room and direct-message history.", color = Muted, fontSize = 11.sp)
                        }
                        SettingsSwitch(checked = denseMessages)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(value = reduceMotion, role = Role.Switch) {
                                onDismissKeyboard()
                                onReduceMotionChange(it)
                            }
                            .semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Reduce motion", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Turn off slide and fade animations when showing panels and scrolling messages.", color = Muted, fontSize = 11.sp)
                    }
                    SettingsSwitch(checked = reduceMotion)
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
                                    ) {
                                        onDismissKeyboard()
                                        onTimestampTimeZoneChange(option)
                                    },
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
                                onDismissKeyboard()
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
                        SettingsSwitch(checked = previewMode)
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

                        TextButton(
                            onClick = onOpenNetworkDiagnostics,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Open network diagnostics", color = Accent)
                            }
                        }

                        if (supportsReticulumProtocol) {
                            RuntimeModeSelection(
                                selected = runtimeMode,
                                enabled = !isRunning && !isStarting && nodeSupported,
                                onDismissKeyboard = onDismissKeyboard,
                                onSelect = { runtimeModeName = it.name; connectionInputError = null },
                            )
                        }

                        if (runtimeMode == ReticulumRuntimeMode.EMBEDDED) {
                            Text(
                                if (!supportsReticulumProtocol) {
                                    "This opens a plain TCP socket only; it does not exchange Reticulum traffic."
                                } else if (supportsRNodeInterfaces) {
                                    "Starling can use TCP peers and RNodes together. It stays a client and does not route traffic for other nodes."
                                } else {
                                    "Manage outbound TCP client interfaces. Starling stays a client; Reticulum transport routing remains disabled."
                                },
                                color = Muted,
                                fontSize = 11.sp,
                            )
                            val runningNode = nodeState as? ReticulumNodeState.Running
                            Text(
                                "Connections",
                                modifier = Modifier.semantics { heading() },
                                color = Ink,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (runningNode != null) {
                                RunningConnectionList(
                                    runningNode,
                                    tcpPeers,
                                    rnodeInterfaces,
                                    resolvedBleRNode,
                                    onRemoveSavedPeer = { peer -> tcpPeers = tcpPeers.filterNot { it.id == peer.id } },
                                    onRemoveSavedRadio = { radio -> rnodeInterfaces = rnodeInterfaces.filterNot { it.id == radio.id } },
                                )
                            }
                            if (runningNode == null && tcpPeers.isEmpty() && rnodeInterfaces.isEmpty()) {
                                Text("No TCP peers or RNode radios configured yet.", color = Muted, fontSize = 11.sp)
                            }
                            if (runningNode == null) tcpPeers.forEachIndexed { index, peer ->
                                Column(
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Canvas)
                                        .border(1.dp, Line, RoundedCornerShape(10.dp)).padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("TCP connection ${index + 1}", modifier = Modifier.weight(1f), color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        if (!isStarting) {
                                            TextButton(onClick = {
                                                onDismissKeyboard()
                                                tcpPeers = tcpPeers.filterIndexed { i, _ -> i != index }
                                                connectionInputError = null
                                            }) {
                                                Text("Remove", color = ErrorText)
                                            }
                                        }
                                    }
                                    TcpConnectionFields(peer, supportsReticulumProtocol, enabled = !isStarting) { changed ->
                                        tcpPeers = tcpPeers.mapIndexed { i, old -> if (i == index) changed else old }
                                        connectionInputError = null
                                    }
                                }
                            }
                            if (runningNode == null && tcpPeers.isNotEmpty() && supportsReticulumProtocol) {
                                Text("IFAC values are per connection and must match that peer. Leave both blank to disable IFAC.", color = Muted, fontSize = 10.sp)
                            }
                            if (runningNode == null && supportsRNodeInterfaces) {
                                rnodeInterfaces.forEachIndexed { index, radio ->
                                    Column(
                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Canvas)
                                            .border(1.dp, Line, RoundedCornerShape(10.dp)).padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(7.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("RNode radio ${index + 1}", modifier = Modifier.weight(1f), color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                            if (!isStarting) {
                                                TextButton(onClick = {
                                                    onDismissKeyboard()
                                                    rnodeInterfaces = rnodeInterfaces.filterIndexed { radioIndex, _ -> radioIndex != index }
                                                    connectionInputError = null
                                                }) {
                                                    Text("Remove", color = ErrorText)
                                                }
                                            }
                                        }
                                        RNodeConnectionEditor(
                                            radio = radio,
                                            onRadioChange = { changed ->
                                                rnodeInterfaces = rnodeInterfaces.mapIndexed { radioIndex, old ->
                                                    if (radioIndex == index) changed else old
                                                }
                                                connectionInputError = null
                                            },
                                            locked = isStarting,
                                            supportsBleRNodes = supportsBleRNodes,
                                            picker = rnodeBlePicker,
                                            upgradeNote = rnodeUpgradeNote,
                                            onDismissKeyboard = onDismissKeyboard,
                                        )
                                        RNodeRadioFields(radio, enabled = !isStarting) { changed ->
                                            rnodeInterfaces = rnodeInterfaces.mapIndexed { radioIndex, old ->
                                                if (radioIndex == index) changed else old
                                            }
                                            connectionInputError = null
                                        }
                                        val bandwidthError = rnodeBandwidthValidationError(radio.bandwidthHz)
                                        // An RNode that is not chosen yet is not an error until a start is tried.
                                        radio.validationError(supportsBleRNodes)?.takeIf { validationError ->
                                            val showConnectionError = validationError != RNodeBleText.CHOOSE_BEFORE_START ||
                                                validationError == connectionInputError ||
                                                validationError == (nodeState as? ReticulumNodeState.Failed)?.message
                                            validationError != bandwidthError && showConnectionError
                                        }?.let { validationError ->
                                            Text(validationError, color = ErrorText, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                            TextButton(
                                enabled = nodeSupported && !isStarting,
                                onClick = {
                                    onDismissKeyboard()
                                    showAddConnection = true
                                },
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Add connection")
                            }
                            Text(
                                when {
                                    isRunning && supportsRNodeInterfaces -> "Set up a TCP peer or RNode for the next node start."
                                    isRunning -> "Set up a TCP peer for the next node start."
                                    supportsRNodeInterfaces -> "Set up a TCP peer or an RNode radio."
                                    else -> "Set up a TCP peer using its host and port."
                                },
                                color = Muted,
                                fontSize = 11.sp,
                            )
                            if (showAddConnection && !isStarting) {
                                AddNetworkConnectionDialog(
                                    tcpPeers = nodeConfigDraft.tcpPeers + runningNode?.tcpPeers.orEmpty(),
                                    radios = nodeConfigDraft.rnodeInterfaces + runningNode?.rnodeInterfaces.orEmpty(),
                                    supportsReticulumProtocol = supportsReticulumProtocol,
                                    supportsRNodeInterfaces = supportsRNodeInterfaces,
                                    supportsBleRNodes = supportsBleRNodes,
                                    picker = rnodeBlePicker,
                                    nodeRunning = isRunning,
                                    onAddPeer = { peer ->
                                        tcpPeers = tcpPeers.withoutEmptyDefaultTcpPeer() + peer
                                        connectionInputError = null
                                        showAddConnection = false
                                    },
                                    onAddRadio = { radio ->
                                        tcpPeers = tcpPeers.withoutEmptyDefaultTcpPeer()
                                        rnodeInterfaces = rnodeInterfaces + radio
                                        connectionInputError = null
                                        showAddConnection = false
                                    },
                                    onDismiss = { showAddConnection = false },
                                )
                            }
                            if (supportsBlePeering) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                        .toggleable(
                                            value = blePeeringEnabled,
                                            enabled = !isRunning && !isStarting,
                                            role = Role.Switch,
                                        ) { enabled ->
                                            onDismissKeyboard()
                                            if (enabled) tcpPeers = tcpPeers.withoutEmptyDefaultTcpPeer()
                                            blePeeringEnabled = enabled
                                            connectionInputError = null
                                        }
                                        .semantics(mergeDescendants = true) {},
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Nearby Bluetooth peers", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        Text("Peer directly with nearby Reticulum devices, without an RNode radio.", color = Muted, fontSize = 11.sp)
                                    }
                                    SettingsSwitch(checked = blePeeringEnabled, enabled = !isRunning && !isStarting)
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
                                state.rnodeInterfaces.isNotEmpty() -> "${state.onlineRNodePorts.size} of ${state.rnodeInterfaces.size} RNode radios online."
                                state.tcpPeers.isNotEmpty() -> "${state.onlineTcpPeerIds.size} of ${state.tcpPeers.size} TCP connections online."
                                state.blePeeringEnabled -> ""
                                else -> "RNS node is running without a configured interface."
                            }.let { interfaces ->
                                if (!state.blePeeringEnabled) interfaces
                                else listOf(
                                    interfaces,
                                    blePeerStatusText(state.blePeerCount, state.blePeeringBluetoothOff) + ".",
                                ).filter(String::isNotBlank).joinToString(" ")
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
                                    TextButton(onClick = {
                                        onDismissKeyboard()
                                        onSectionSelected(SettingsSection.IDENTITY)
                                    }) { Text("Set up identity") }
                                }
                            }
                        }

                        Button(
                            enabled = nodeActionEnabled,
                            onClick = {
                                onDismissKeyboard()
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
                            onDismissKeyboard = onDismissKeyboard,
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
                                onDismissKeyboard()
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
            if (selectedSection == SettingsSection.OPEN_SOURCE) {
                OpenSourceAcknowledgements()
            }
        }
        }
        val selectedSectionContent = if (selectedSection == SettingsSection.IDENTITY) identityContent else sectionContent
        if (isWideLayout) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                SettingsSectionNavigation(
                    modifier = Modifier.width(250.dp).fillMaxHeight(),
                    selectedSection = selectedSection,
                    onSectionSelected = onSectionSelected,
                    onDismissKeyboard = onDismissKeyboard,
                )
                Column(Modifier.weight(1f).fillMaxHeight().background(Canvas)) {
                    Text(
                        selectedSection.label,
                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp),
                        color = Ink,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    selectedSectionContent(Modifier.weight(1f), 24.dp)
                }
            }
        } else if (showSectionIndex) {
            SettingsSectionNavigation(
                modifier = Modifier.fillMaxWidth().weight(1f),
                selectedSection = selectedSection,
                onSectionSelected = onSectionSelected,
                onDismissKeyboard = onDismissKeyboard,
                highlightSelected = !isIos,
            )
        } else {
            selectedSectionContent(Modifier.fillMaxWidth().weight(1f), 16.dp)
        }
    }
}

@Composable
private fun OpenSourceAcknowledgements() {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Starling is open-source software. These acknowledgements cover Starling and selected libraries used by its clients. " +
                "Open a license link to read the full terms; an internet connection may be required.",
            color = Muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        TextButton(onClick = { uriHandler.openUri("https://github.com/Sidpatchy/Starling") }) {
            Text("View Starling source")
        }
        AcknowledgementCard(
            name = "Starling",
            license = "GNU Affero General Public License v3.0",
            detail = "Starling's source code and license are published in the project repository.",
            licenseUrl = "https://github.com/Sidpatchy/Starling/blob/main/LICENSE",
            onOpenLicense = { uriHandler.openUri(it) },
        )
        AcknowledgementCard(
            name = "Reticulum",
            license = "Reticulum License",
            detail = "Copyright © 2016–2026 Mark Qvist.",
            licenseUrl = "https://github.com/Sidpatchy/Starling/blob/main/licenses/Reticulum-LICENSE.txt",
            onOpenLicense = { uriHandler.openUri(it) },
        )
        AcknowledgementCard(
            name = "LXMF",
            license = "Reticulum License",
            detail = "Copyright © 2020–2025 Mark Qvist.",
            licenseUrl = "https://github.com/Sidpatchy/Starling/blob/main/licenses/LXMF-LICENSE.txt",
            onOpenLicense = { uriHandler.openUri(it) },
        )
        AcknowledgementCard(
            name = "ble-reticulum",
            license = "MIT License",
            detail = "Copyright © 2025 torlando-tech. Used for Bluetooth peers on Android.",
            licenseUrl = "https://github.com/Sidpatchy/Starling/blob/main/licenses/BLE-Reticulum-LICENSE.txt",
            onOpenLicense = { uriHandler.openUri(it) },
        )
        AcknowledgementCard(
            name = "pySerial",
            license = "BSD 3-Clause License",
            detail = "Copyright © 2001–2020 Chris Liechti.",
            licenseUrl = "https://github.com/Sidpatchy/Starling/blob/main/licenses/PySerial-LICENSE.txt",
            onOpenLicense = { uriHandler.openUri(it) },
        )
        AcknowledgementCard(
            name = "Python",
            license = "Python Software Foundation License 2.0",
            detail = "The Python runtime is distributed by the Python Software Foundation.",
            licenseUrl = "https://docs.python.org/3/license.html",
            onOpenLicense = { uriHandler.openUri(it) },
        )
        AcknowledgementCard(
            name = "Kotlin, Compose, AndroidX, and kotlinx.coroutines",
            license = "Apache License 2.0",
            detail = "Open-source language, UI, platform, and concurrency libraries used by Starling.",
            licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
            onOpenLicense = { uriHandler.openUri(it) },
        )
    }
}

@Composable
private fun AcknowledgementCard(
    name: String,
    license: String,
    detail: String,
    licenseUrl: String,
    onOpenLicense: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Paper)
            .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(name, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(license, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(detail, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        TextButton(onClick = { onOpenLicense(licenseUrl) }) {
            Text("View license")
        }
    }
}

@Composable
private fun SettingsSectionNavigation(
    modifier: Modifier,
    selectedSection: SettingsSection,
    onSectionSelected: (SettingsSection) -> Unit,
    onDismissKeyboard: () -> Unit,
    highlightSelected: Boolean = true,
) {
    val isIos = LocalStarlingUiStyle.current == StarlingUiStyle.IOS
    Column(
        modifier = modifier.background(if (isIos) Canvas else Paper)
            .verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        if (!isIos) {
            Text("SETTINGS", color = Muted, fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
        }
        Column(
            modifier = if (isIos) Modifier.clip(RoundedCornerShape(12.dp)).background(Paper) else Modifier,
            verticalArrangement = Arrangement.spacedBy(if (isIos) 0.dp else 6.dp),
        ) {
            SettingsSection.entries.forEachIndexed { index, section ->
                if (isIos && index > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = Line)
                val isSelected = highlightSelected && section == selectedSection
                val rowShape = if (isIos) Modifier else Modifier.clip(RoundedCornerShape(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().then(rowShape)
                        .background(if (isSelected) PaleAccent else Color.Transparent)
                        .heightIn(min = 44.dp).clickable(role = Role.Button) {
                            onDismissKeyboard()
                            onSectionSelected(section)
                        }
                        .semantics { if (highlightSelected) selected = isSelected }
                        .padding(horizontal = if (isIos) 16.dp else 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            section.label,
                            color = if (isSelected) Accent else Ink,
                            fontSize = if (isIos) 17.sp else 14.sp,
                            lineHeight = if (isIos) 21.sp else TextUnit.Unspecified,
                            fontWeight = when {
                                isSelected -> FontWeight.SemiBold
                                isIos -> FontWeight.Normal
                                else -> FontWeight.Medium
                            },
                        )
                        Text(section.summary, color = Muted, fontSize = if (isIos) 13.sp else 11.sp, lineHeight = if (isIos) 17.sp else 15.sp)
                    }
                    Icon(
                        if (isIos) Icons.AutoMirrored.Filled.ArrowForwardIos else Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = Muted,
                        modifier = Modifier.padding(start = if (isIos) 8.dp else 0.dp).size(if (isIos) 14.dp else 18.dp),
                    )
                }
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
