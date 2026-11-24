package com.sidpatchy.Starling

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

private const val RESCAN_DEBOUNCE_MILLIS = 2_000L
private const val RESULT_ADVANCE_MILLIS = 1_000L

/** The "Connect over" part of an RNode card: connection type and the device, host, or port. */
@Composable
internal fun RNodeConnectionEditor(
    radio: ReticulumRNodeInterface,
    onRadioChange: (ReticulumRNodeInterface) -> Unit,
    locked: Boolean,
    supportsBleRNodes: Boolean,
    picker: RNodeBlePicker?,
    upgradeNote: String?,
    onDismissKeyboard: () -> Unit,
) {
    val type = rnodeEditorConnectionType(radio.port, supportsBleRNodes)
    val options = if (supportsBleRNodes) {
        listOf(RNodeConnectionType.BLUETOOTH to "Bluetooth", RNodeConnectionType.WIFI to "Wi-Fi")
    } else {
        listOf(RNodeConnectionType.SERIAL to "Serial port", RNodeConnectionType.WIFI to "Wi-Fi")
    }
    Text("Connect over", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    Row(Modifier.fillMaxWidth().selectableGroup()) {
        options.forEach { (option, label) ->
            Row(
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                    .selectable(selected = type == option, enabled = !locked, role = Role.RadioButton) {
                        onDismissKeyboard()
                        if (type != option) {
                            // Only the connection is cleared; the radio parameters stay.
                            onRadioChange(radio.copy(port = rnodeEmptyPort(option), bleName = null, pairedByStarling = false))
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = type == option, onClick = null, enabled = !locked)
                Text(label, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
    when (type) {
        RNodeConnectionType.WIFI -> OutlinedTextField(
            value = rnodeWifiHost(radio.port),
            onValueChange = { value -> onRadioChange(radio.copy(port = RNODE_TCP_PREFIX + value.trim())) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("RNode address (host or IP)") },
            supportingText = { Text("Starling uses the RNode's default port, 7633.", color = Muted, fontSize = 10.sp) },
            singleLine = true,
            enabled = !locked,
        )
        RNodeConnectionType.SERIAL -> OutlinedTextField(
            value = radio.port,
            onValueChange = { value -> onRadioChange(radio.copy(port = value)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Serial port") },
            supportingText = {
                // Word joiners keep each example path on one line.
                Text(
                    "For example /\u2060dev/\u2060ttyUSB0, COM3 or /\u2060dev/\u2060cu.usbserial-0001",
                    color = Muted,
                    fontSize = 10.sp,
                )
            },
            singleLine = true,
            enabled = !locked,
        )
        RNodeConnectionType.BLUETOOTH ->
            if (picker != null) RNodeBluetoothDevice(radio, onRadioChange, locked, picker, upgradeNote, onDismissKeyboard)
    }
    if (locked) Text("Stop the node to change the RNode.", color = Muted, fontSize = 11.sp)
}

private data class RNodePickerRequest(val pairDeviceId: String? = null, val pairDeviceName: String = "")

@Composable
private fun RNodeBluetoothDevice(
    radio: ReticulumRNodeInterface,
    onRadioChange: (ReticulumRNodeInterface) -> Unit,
    locked: Boolean,
    picker: RNodeBlePicker,
    upgradeNote: String?,
    onDismissKeyboard: () -> Unit,
) {
    var request by remember { mutableStateOf<RNodePickerRequest?>(null) }
    var forgotten by remember { mutableStateOf(false) }
    // Pairing can change in the dialog or in system settings; look again after either.
    var pairingChecks by remember { mutableStateOf(0) }
    val isBleValue = rnodeConnectionType(radio.port) == RNodeConnectionType.BLUETOOTH
    val target = rnodeBleTarget(radio.port)
    val kind = rnodeBleTargetKind(target)
    val android = picker.platform == RNodeBlePlatform.ANDROID
    val find = { onDismissKeyboard(); request = RNodePickerRequest() }
    LaunchedEffect(picker) {
        picker.pairingChanges.collect { pairingChecks++ }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            !isBleValue -> {
                Text("No RNode chosen", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("Turn on your RNode and tap Find RNode.", color = Muted, fontSize = 11.sp)
                if (!locked) TextButton(onClick = find) { Text("Find RNode", color = Accent) }
            }
            kind == RNodeBleTargetKind.ANY -> {
                Text(
                    "Starling connects to the first RNode it finds. Choose a specific RNode to avoid connecting to the wrong one.",
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
                if (!locked) TextButton(onClick = find) { Text("Find RNode", color = Accent) }
            }
            else -> {
                val savedId = target.takeIf { kind == RNodeBleTargetKind.ID }
                val name = radio.bleName?.takeIf(String::isNotBlank)
                    ?: savedId?.let { id -> remember(id, pairingChecks) { picker.knownName(id) } }
                    ?: target.takeIf { kind == RNodeBleTargetKind.NAME }
                    ?: "RNode"
                val paired = if (android && savedId != null) {
                    remember(savedId, pairingChecks) { picker.isPaired(savedId) }
                } else {
                    null
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, modifier = Modifier.weight(1f), color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    when {
                        paired == true -> Text("Paired", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        // Pairing is optional, so a missing pairing is not shown as an error.
                        paired == false -> Text("Not paired", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        !android && savedId != null ->
                            Text("Saved", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (android && savedId != null) {
                    Text(savedId.uppercase(), color = Muted, fontSize = 10.sp)
                } else if (!android && savedId != null) {
                    Text("Starling connects to this RNode only.", color = Muted, fontSize = 11.sp)
                }
                if (!locked) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (paired == false && savedId != null) {
                            TextButton(onClick = {
                                onDismissKeyboard()
                                request = RNodePickerRequest(pairDeviceId = savedId, pairDeviceName = name)
                            }) { Text("Pair", color = Accent) }
                        }
                        TextButton(onClick = find) { Text("Change", color = Accent) }
                        TextButton(onClick = {
                            onDismissKeyboard()
                            forgotten = true
                            onRadioChange(
                                radio.copy(
                                    port = rnodeEmptyPort(RNodeConnectionType.BLUETOOTH),
                                    bleName = null,
                                    pairedByStarling = false,
                                ),
                            )
                        }) { Text("Forget", color = Muted) }
                    }
                }
            }
        }
        if (forgotten && !isBleValue) {
            Text(
                "This only removes the RNode from Starling. To unpair it, use Bluetooth settings.",
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            if (android) {
                TextButton(onClick = { picker.openBluetoothSettings() }) { Text("Open Bluetooth settings", color = Accent) }
            }
        }
        if (upgradeNote != null && kind == RNodeBleTargetKind.ID) {
            Text(upgradeNote, color = Muted, fontSize = 11.sp)
        }
    }

    request?.let { current ->
        RNodeScanDialog(
            picker = picker,
            savedId = target.takeIf { isBleValue && kind == RNodeBleTargetKind.ID },
            pairDeviceId = current.pairDeviceId,
            pairDeviceName = current.pairDeviceName,
            onChosen = { id, name, pairedByStarling ->
                forgotten = false
                request = null
                pairingChecks++
                onRadioChange(
                    radio.copy(
                        port = rnodeBlePort(id),
                        bleName = name.ifBlank { null },
                        // Re-pairing the saved device keeps what Starling already knew about it.
                        pairedByStarling = pairedByStarling ||
                            (radio.pairedByStarling && target.equals(id, ignoreCase = true) && picker.isPaired(id) == true),
                    ),
                )
            },
            onDismiss = {
                request = null
                pairingChecks++
            },
        )
    }
}

/**
 * Finds nearby RNodes and pairs with the one that is picked. The current
 * RNode stays saved until [onChosen] reports a new one.
 */
@Composable
private fun RNodeScanDialog(
    picker: RNodeBlePicker,
    savedId: String?,
    pairDeviceId: String?,
    pairDeviceName: String,
    onChosen: (id: String, name: String, pairedByStarling: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val android = picker.platform == RNodeBlePlatform.ANDROID
    val scanStatus by picker.scanStatus.collectAsState()
    val pairing by picker.pairing.collectAsState()
    val clock = remember { TimeSource.Monotonic.markNow() }
    val list = remember { RNodeScanList() }
    val pairedIds = remember { mutableMapOf<String, Boolean>() }
    var rows by remember { mutableStateOf(emptyList<RNodeScanRow>()) }
    var selected by remember { mutableStateOf(pairDeviceId?.let { it to pairDeviceName }) }
    var lastSearchAt by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(0L) }

    fun search() {
        lastSearchAt = clock.elapsedNow().inWholeMilliseconds
        picker.startScan()
    }

    DisposableEffect(picker) {
        onDispose {
            picker.stopScan()
            picker.cancelPairing()
        }
    }
    LaunchedEffect(picker) {
        if (pairDeviceId != null) picker.pair(pairDeviceId, pairDeviceName) else search()
    }
    LaunchedEffect(scanStatus) {
        if (scanStatus == RNodeBleScanStatus.Scanning) list.beginSearch(clock.elapsedNow().inWholeMilliseconds)
    }
    LaunchedEffect(picker) {
        picker.sightings.collect { sighting ->
            // Other devices offer the same UART service; only RNodes are listed.
            if (!list.accepts(sighting)) return@collect
            val key = sighting.id.uppercase()
            val paired = android && pairedIds.getOrPut(key) { picker.isPaired(sighting.id) == true }
            val pinned = paired || (savedId != null && savedId.equals(sighting.id, ignoreCase = true))
            val at = clock.elapsedNow().inWholeMilliseconds
            list.observe(sighting, pinned, at)
            rows = list.rows(at)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            now = clock.elapsedNow().inWholeMilliseconds
            rows = list.rows(now)
        }
    }
    LaunchedEffect(pairing) {
        val device = selected ?: return@LaunchedEffect
        when (pairing) {
            is RNodeBlePairingState.AlreadyPaired, is RNodeBlePairingState.Ready -> {
                delay(RESULT_ADVANCE_MILLIS)
                onChosen(device.first, device.second, false)
            }
            is RNodeBlePairingState.Paired -> {
                delay(RESULT_ADVANCE_MILLIS)
                onChosen(device.first, device.second, true)
            }
            else -> Unit
        }
    }

    val device = selected
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (device == null) "Choose your RNode" else "Pair with ${device.second.ifBlank { "RNode" }}", color = Ink)
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (device == null) {
                    Text("Turn the RNode on and keep it close.", color = Muted, fontSize = 12.sp)
                    if (!android && savedId == null) {
                        Text(
                            "iOS may ask to pair with the RNode the first time Starling connects to it. If iOS asks, " +
                                "enter the PIN shown on the RNode's screen. Starling can't start pairing itself or tell " +
                                "whether the RNode is already paired.",
                            color = Muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                    rows.forEach { row ->
                        RNodeScanRowItem(
                            row = row,
                            marker = when {
                                android && pairedIds[row.id] == true -> "Paired"
                                !android && savedId != null && savedId.equals(row.id, ignoreCase = true) -> "Saved"
                                else -> null
                            },
                            onClick = {
                                picker.stopScan()
                                selected = row.id to row.name
                                picker.pair(row.id, row.name)
                            },
                        )
                    }
                    RNodeBleText.scanStatusMessage(scanStatus, picker.platform, rows.isNotEmpty())?.let { message ->
                        val blocked = scanStatus != RNodeBleScanStatus.Scanning && scanStatus != RNodeBleScanStatus.Finished
                        Text(
                            message,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = if (scanStatus is RNodeBleScanStatus.Failed) ErrorText else if (blocked) Ink else Muted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                    RNodeBleText.scanBlockerAction(scanStatus, picker.platform)?.let { action ->
                        TextButton(onClick = { picker.resolveScanBlocker() }) { Text(action, color = Accent) }
                    }
                } else {
                    RNodePairingStep(device.second.ifBlank { "RNode" }, pairing, picker.platform)
                }
            }
        },
        confirmButton = {
            if (device != null && pairing is RNodeBlePairingState.Failed) {
                // Stacked, so three buttons fit a narrow screen or a large font.
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { picker.pair(device.first, device.second) }) { Text("Try again", color = Accent) }
                    if (android) {
                        // Pairing is not required to save an RNode; it may accept connections without it.
                        TextButton(onClick = { onChosen(device.first, device.second, false) }) { Text("Skip pairing") }
                    }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (device == null) {
                        if (scanStatus == RNodeBleScanStatus.Scanning) {
                            TextButton(onClick = { picker.stopScan() }) { Text("Stop", color = Accent) }
                        } else if (scanStatus != RNodeBleScanStatus.Unsupported) {
                            TextButton(
                                enabled = now - lastSearchAt >= RESCAN_DEBOUNCE_MILLIS,
                                onClick = { search() },
                            ) { Text("Rescan") }
                        }
                    }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        },
    )
}

@Composable
private fun RNodePairingStep(name: String, pairing: RNodeBlePairingState, platform: RNodeBlePlatform) {
    val android = platform == RNodeBlePlatform.ANDROID
    Column(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (pairing) {
            RNodeBlePairingState.Idle, is RNodeBlePairingState.Waiting -> if (android) {
                Text(
                    "If Android asks, enter the PIN shown on your RNode's screen, then tap Pair.",
                    color = Ink,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Text("Waiting for pairing...", color = Muted, fontSize = 12.sp)
            } else {
                Text(
                    "Connecting to $name. If iOS asks for a PIN, enter the one shown on your RNode's screen.",
                    color = Ink,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            is RNodeBlePairingState.AlreadyPaired -> Text("Already paired", color = Ink, fontSize = 13.sp)
            is RNodeBlePairingState.Paired -> Text("Paired with $name.", color = Ink, fontSize = 13.sp)
            is RNodeBlePairingState.Ready -> Text("Connected to $name. This RNode is ready.", color = Ink, fontSize = 13.sp)
            is RNodeBlePairingState.Failed ->
                Text(RNodeBleText.pairingFailureMessage(pairing, platform), color = ErrorText, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun RNodeScanRowItem(row: RNodeScanRow, marker: String?, onClick: () -> Unit) {
    val level = rnodeSignalLevel(row.rssi)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.name.ifBlank { "Unnamed RNode" }, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            row.idSuffix?.let { Text(it, color = Muted, fontSize = 10.sp) }
            marker?.let { Text(it, color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Medium) }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
                for (bar in 1..4) {
                    Box(
                        Modifier.width(4.dp).height((3 + bar * 3).dp).clip(RoundedCornerShape(1.dp))
                            .background(if (bar <= level.bars) Accent else Line),
                    )
                }
            }
            Text(level.label, color = Muted, fontSize = 10.sp)
        }
    }
}
