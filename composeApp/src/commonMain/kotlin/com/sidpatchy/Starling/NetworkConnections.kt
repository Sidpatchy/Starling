package com.sidpatchy.Starling

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class NetworkConnectionKind(
    val label: String,
    val description: String,
    val radioType: RNodeConnectionType? = null,
) {
    TCP("TCP peer", "Connect to a Reticulum peer by host and port."),
    BLUETOOTH("Bluetooth RNode", "Find a nearby RNode radio.", RNodeConnectionType.BLUETOOTH),
    WIFI("Wi-Fi RNode", "Enter the network address of an RNode radio.", RNodeConnectionType.WIFI),
    SERIAL("Serial RNode", "Connect to a radio plugged into this computer.", RNodeConnectionType.SERIAL),
}

internal fun networkConnectionKinds(supportsRNodes: Boolean, supportsBleRNodes: Boolean) = buildList {
    add(NetworkConnectionKind.TCP)
    if (supportsRNodes) {
        add(if (supportsBleRNodes) NetworkConnectionKind.BLUETOOTH else NetworkConnectionKind.SERIAL)
        add(NetworkConnectionKind.WIFI)
    }
}

internal fun List<ReticulumTcpPeer>.withoutEmptyDefaultTcpPeer(): List<ReticulumTcpPeer> =
    if (singleOrNull() == ReticulumTcpPeer("", 4242)) emptyList() else this

internal enum class NetworkConnectionStatus(val label: String) {
    ONLINE("Online"),
    RETRYING("Retrying"),
    SAVED("Saved for next start"),
}

internal fun networkConnectionStatus(id: String, activeIds: Set<String>, onlineIds: Set<String>) = when (id) {
    in onlineIds -> NetworkConnectionStatus.ONLINE
    in activeIds -> NetworkConnectionStatus.RETRYING
    else -> NetworkConnectionStatus.SAVED
}

internal fun newTcpConnectionError(peer: ReticulumTcpPeer, configured: List<ReticulumTcpPeer>): String? =
    if (configured.any { it.id == peer.id && it.host.isNotBlank() }) "That TCP peer is already configured."
    else ReticulumNodeConfig(tcpPeers = listOf(peer)).validationError()

internal fun newRNodeConnectionError(
    radio: ReticulumRNodeInterface,
    configured: List<ReticulumRNodeInterface>,
    supportsBleRNodes: Boolean,
): String? = when {
    configured.any { it.id == radio.id && it.port.isNotBlank() } -> "That RNode is already configured."
    rnodeConnectionType(radio.port) == RNodeConnectionType.BLUETOOTH &&
        configured.any { rnodeConnectionType(it.port) == RNodeConnectionType.BLUETOOTH } ->
        "Only one Bluetooth RNode can be configured at a time."
    rnodeConnectionType(radio.port) == RNodeConnectionType.WIFI &&
        rnodeWifiHost(radio.port).any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it !in "._:-" } ->
        "Enter an RNode host name or IP address, without a URL scheme."
    else -> radio.validationError(supportsBleRNodes)
}

internal fun activeRNodeConnections(
    running: ReticulumNodeState.Running,
    resolvedBleRNode: ResolvedBleRNode?,
): Map<String, Pair<ReticulumRNodeInterface, NetworkConnectionStatus>> =
    running.rnodeInterfaces.associate { radio ->
        // A legacy Bluetooth name can be migrated in saved settings while the
        // running node still reports the original connection ID.
        val display = resolvedBleRNode?.let { upgradeRNodeBleInterface(radio, it)?.radio } ?: radio
        val status = if (radio.id in running.onlineRNodePorts) NetworkConnectionStatus.ONLINE else NetworkConnectionStatus.RETRYING
        display.id to (display to status)
    }

@Composable
internal fun RunningConnectionList(
    running: ReticulumNodeState.Running,
    savedPeers: List<ReticulumTcpPeer>,
    savedRadios: List<ReticulumRNodeInterface>,
    resolvedBleRNode: ResolvedBleRNode?,
    onRemoveSavedPeer: (ReticulumTcpPeer) -> Unit,
    onRemoveSavedRadio: (ReticulumRNodeInterface) -> Unit,
) {
    val activePeers = running.tcpPeers.mapTo(mutableSetOf()) { it.id }
    val activeRadios = activeRNodeConnections(running, resolvedBleRNode)
    (savedPeers.filter { it.host.isNotBlank() } + running.tcpPeers).distinctBy { it.id }.forEach { peer ->
        val status = networkConnectionStatus(peer.id, activePeers, running.onlineTcpPeerIds)
        ConnectionStatusRow(
            name = "${peer.host}:${peer.port}",
            type = NetworkConnectionKind.TCP.label,
            status = status,
            onRemoveSaved = if (status == NetworkConnectionStatus.SAVED) ({ onRemoveSavedPeer(peer) }) else null,
        )
    }
    val displayRadios = savedRadios.map { radio ->
        resolvedBleRNode?.let { upgradeRNodeBleInterface(radio, it)?.radio } ?: radio
    }
    (displayRadios + activeRadios.values.map { it.first }).distinctBy { it.id }.forEach { radio ->
        val kind = when (rnodeConnectionType(radio.port)) {
            RNodeConnectionType.BLUETOOTH -> NetworkConnectionKind.BLUETOOTH
            RNodeConnectionType.WIFI -> NetworkConnectionKind.WIFI
            else -> NetworkConnectionKind.SERIAL
        }
        val status = activeRadios[radio.id]?.second ?: NetworkConnectionStatus.SAVED
        ConnectionStatusRow(
            name = rnodeDisplayName(radio),
            type = kind.label,
            status = status,
            onRemoveSaved = if (status == NetworkConnectionStatus.SAVED) ({ onRemoveSavedRadio(radio) }) else null,
        )
    }
}

@Composable
private fun ConnectionStatusRow(
    name: String,
    type: String,
    status: NetworkConnectionStatus,
    onRemoveSaved: (() -> Unit)?,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                if (name == type) status.label else "$type · ${status.label}",
                color = if (status == NetworkConnectionStatus.ONLINE) Accent else Muted,
                fontSize = 11.sp,
            )
        }
        if (onRemoveSaved != null) {
            TextButton(onClick = onRemoveSaved) { Text("Remove", color = ErrorText) }
        }
    }
}

@Composable
internal fun AddNetworkConnectionDialog(
    tcpPeers: List<ReticulumTcpPeer>,
    radios: List<ReticulumRNodeInterface>,
    supportsReticulumProtocol: Boolean,
    supportsRNodeInterfaces: Boolean,
    supportsBleRNodes: Boolean,
    picker: RNodeBlePicker?,
    nodeRunning: Boolean,
    onAddPeer: (ReticulumTcpPeer) -> Unit,
    onAddRadio: (ReticulumRNodeInterface) -> Unit,
    onDismiss: () -> Unit,
) {
    val kinds = networkConnectionKinds(supportsRNodeInterfaces, supportsBleRNodes)
    var kind by remember { mutableStateOf<NetworkConnectionKind?>(null) }
    var peer by remember { mutableStateOf(ReticulumTcpPeer("", 4242)) }
    var radio by remember {
        mutableStateOf(ReticulumRNodeInterface("", 0L, 125_000, 0, 7, 5))
    }
    var choosingBluetooth by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }

    if (choosingBluetooth && picker != null) {
        RNodeScanDialog(
            picker = picker,
            savedId = rnodeBleTarget(radio.port).takeIf(String::isNotBlank),
            pairDeviceId = null,
            pairDeviceName = "",
            onChosen = { id, name, pairedByStarling ->
                radio = radio.copy(port = rnodeBlePort(id), bleName = name.ifBlank { null }, pairedByStarling = pairedByStarling)
                choosingBluetooth = false
            },
            onDismiss = {
                choosingBluetooth = false
                if (radio.port.isBlank()) kind = null
            },
        )
        return
    }

    val choice = kind
    val cleanPeer = peer.copy(
        host = peer.host.trim(),
        ifacNetworkName = peer.ifacNetworkName?.trim()?.ifBlank { null },
        ifacPassphrase = peer.ifacPassphrase?.takeIf(String::isNotBlank),
    )
    val cleanRadio = radio.copy(port = radio.port.trim())
    val error = when (choice) {
        null -> null
        NetworkConnectionKind.TCP -> newTcpConnectionError(cleanPeer, tcpPeers)
        else -> newRNodeConnectionError(cleanRadio, radios, supportsBleRNodes)
    }
    val disposition = if (nodeRunning) {
        "Save this for the next node start without restarting the running node."
    } else {
        "This connection will be used when you start the node."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(choice?.let { "Add ${it.label}" } ?: "Add connection", color = Ink) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(disposition, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                if (choice == null) {
                    kinds.forEach { option ->
                        val unavailable = if (option == NetworkConnectionKind.BLUETOOTH) {
                            when {
                                picker == null -> "Bluetooth discovery is unavailable on this device."
                                radios.any { rnodeConnectionType(it.port) == RNodeConnectionType.BLUETOOTH } ->
                                    "Only one Bluetooth RNode can be configured at a time."
                                else -> null
                            }
                        } else null
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable(enabled = unavailable == null, role = Role.Button) {
                                    kind = option
                                    showError = false
                                    if (option == NetworkConnectionKind.BLUETOOTH) {
                                        choosingBluetooth = true
                                    } else if (option.radioType != null) {
                                        radio = radio.copy(port = rnodeEmptyPort(option.radioType), bleName = null, pairedByStarling = false)
                                    }
                                }
                                .padding(vertical = 6.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(option.label, color = if (unavailable == null) Ink else Muted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(unavailable ?: option.description, color = Muted, fontSize = 11.sp)
                        }
                    }
                } else if (choice == NetworkConnectionKind.TCP) {
                    TcpConnectionFields(peer, supportsReticulumProtocol, enabled = true) { peer = it }
                } else {
                    if (choice == NetworkConnectionKind.BLUETOOTH) {
                        Text(rnodeDisplayName(radio), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("Selected for setup — not online yet.", color = Muted, fontSize = 11.sp)
                        TextButton(onClick = { choosingBluetooth = true }) { Text("Choose another RNode") }
                    } else {
                        RNodeEndpointField(choice.radioType ?: RNodeConnectionType.SERIAL, radio, locked = false) { radio = it }
                    }
                    Text(
                        "Use settings that match your RNode network and local radio rules.",
                        color = Muted,
                        fontSize = 11.sp,
                    )
                    RNodeRadioFields(radio, enabled = true) { radio = it }
                }
                if (showError && error != null) {
                    Text(
                        error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = ErrorText,
                        fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = {
            if (choice != null) {
                TextButton(onClick = {
                    if (error != null) showError = true
                    else if (choice == NetworkConnectionKind.TCP) onAddPeer(cleanPeer)
                    else onAddRadio(cleanRadio)
                }) { Text(if (nodeRunning) "Save for next start" else "Save connection") }
            }
        },
        dismissButton = {
            if (choice == null) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            } else {
                TextButton(onClick = {
                    kind = null
                    showError = false
                    radio = radio.copy(port = "", bleName = null, pairedByStarling = false)
                }) { Text("Back") }
            }
        },
    )
}

@Composable
internal fun TcpConnectionFields(
    peer: ReticulumTcpPeer,
    showIfac: Boolean,
    enabled: Boolean,
    onChange: (ReticulumTcpPeer) -> Unit,
) {
    OutlinedTextField(
        value = peer.host,
        onValueChange = { onChange(peer.copy(host = it)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Peer host or IP") },
        singleLine = true,
        enabled = enabled,
    )
    NetworkNumberField(peer.port, "TCP port", Modifier.fillMaxWidth(), enabled) { onChange(peer.copy(port = it)) }
    if (showIfac) {
        OutlinedTextField(
            value = peer.ifacNetworkName.orEmpty(),
            onValueChange = { onChange(peer.copy(ifacNetworkName = it.takeIf(String::isNotBlank))) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("IFAC network name (optional)") },
            singleLine = true,
            enabled = enabled,
        )
        OutlinedTextField(
            value = peer.ifacPassphrase.orEmpty(),
            onValueChange = { onChange(peer.copy(ifacPassphrase = it.takeIf(String::isNotBlank))) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("IFAC passphrase (optional)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            enabled = enabled,
        )
    }
}

@Composable
internal fun RNodeRadioFields(
    radio: ReticulumRNodeInterface,
    enabled: Boolean,
    onChange: (ReticulumRNodeInterface) -> Unit,
) {
    OutlinedTextField(
        value = radio.frequencyHz.takeIf { it > 0 }?.toString().orEmpty(),
        onValueChange = {
            if (it.all(Char::isDigit)) onChange(radio.copy(frequencyHz = it.toLongOrNull() ?: 0L))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Radio frequency (Hz)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        enabled = enabled,
    )
    val bandwidthError = rnodeBandwidthValidationError(radio.bandwidthHz)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        NetworkNumberField(radio.bandwidthHz, "Bandwidth (Hz)", Modifier.weight(1f), enabled, error = bandwidthError != null) {
            onChange(radio.copy(bandwidthHz = it))
        }
        NetworkNumberField(radio.txPowerDbm, "TX power (dBm)", Modifier.weight(1f), enabled, emptyValue = -1) {
            onChange(radio.copy(txPowerDbm = it))
        }
    }
    bandwidthError?.let { Text(it, color = ErrorText, fontSize = 10.sp) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        NetworkNumberField(radio.spreadingFactor, "Spreading factor", Modifier.weight(1f), enabled) {
            onChange(radio.copy(spreadingFactor = it))
        }
        NetworkNumberField(radio.codingRate, "Coding rate (5–8)", Modifier.weight(1f), enabled) {
            onChange(radio.copy(codingRate = it))
        }
    }
}

@Composable
private fun NetworkNumberField(
    value: Int,
    label: String,
    modifier: Modifier,
    enabled: Boolean,
    error: Boolean = false,
    emptyValue: Int = 0,
    onChange: (Int) -> Unit,
) {
    OutlinedTextField(
        value = value.takeIf { it != emptyValue }?.toString().orEmpty(),
        onValueChange = { if (it.all(Char::isDigit)) onChange(it.toIntOrNull() ?: emptyValue) },
        modifier = modifier,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = error,
        singleLine = true,
        enabled = enabled,
    )
}
