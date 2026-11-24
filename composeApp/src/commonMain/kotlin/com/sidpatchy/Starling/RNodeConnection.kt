package com.sidpatchy.Starling

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

const val RNODE_BLE_PREFIX = "ble://"
const val RNODE_TCP_PREFIX = "tcp://"

/** How an RNode is attached. It is derived from the saved connection, never stored. */
enum class RNodeConnectionType { BLUETOOTH, WIFI, SERIAL }

/** The connection type a saved value describes, by its prefix alone. */
fun rnodeConnectionType(port: String): RNodeConnectionType {
    val trimmed = port.trim()
    return when {
        trimmed.startsWith(RNODE_BLE_PREFIX, ignoreCase = true) -> RNodeConnectionType.BLUETOOTH
        trimmed.startsWith(RNODE_TCP_PREFIX, ignoreCase = true) -> RNodeConnectionType.WIFI
        else -> RNodeConnectionType.SERIAL
    }
}

/**
 * The connection type the editor shows. Phones have no serial ports, so a
 * value without a prefix is a Bluetooth RNode that has not been chosen yet;
 * desktop never offers Bluetooth.
 */
fun rnodeEditorConnectionType(port: String, supportsBleRNodes: Boolean): RNodeConnectionType =
    when (rnodeConnectionType(port)) {
        RNodeConnectionType.WIFI -> RNodeConnectionType.WIFI
        else -> if (supportsBleRNodes) RNodeConnectionType.BLUETOOTH else RNodeConnectionType.SERIAL
    }

/** The saved value for a connection type with nothing entered or chosen yet. */
fun rnodeEmptyPort(type: RNodeConnectionType): String =
    if (type == RNodeConnectionType.WIFI) RNODE_TCP_PREFIX else ""

/** The host part of a Wi-Fi RNode value. */
fun rnodeWifiHost(port: String): String {
    val trimmed = port.trim()
    return if (trimmed.startsWith(RNODE_TCP_PREFIX, ignoreCase = true)) trimmed.drop(RNODE_TCP_PREFIX.length) else ""
}

/** What follows `ble://`: a device id, a device name, or nothing. */
fun rnodeBleTarget(port: String): String {
    val trimmed = port.trim()
    return if (trimmed.startsWith(RNODE_BLE_PREFIX, ignoreCase = true)) trimmed.drop(RNODE_BLE_PREFIX.length).trim() else ""
}

enum class RNodeBleTargetKind {
    /** A bare `ble://`: the first RNode found. */
    ANY,

    /** A platform device id: a Bluetooth address on Android, a peripheral UUID on iOS. */
    ID,

    /** An advertised device name saved by an older version. */
    NAME,
}

/** Tells a saved device id from a device name by its format. */
fun rnodeBleTargetKind(target: String): RNodeBleTargetKind {
    val trimmed = target.trim()
    return when {
        trimmed.isEmpty() -> RNodeBleTargetKind.ANY
        isBleDeviceId(trimmed) -> RNodeBleTargetKind.ID
        else -> RNodeBleTargetKind.NAME
    }
}

fun isBleDeviceId(value: String): Boolean = isBleMacAddress(value) || isBlePeripheralUuid(value)

/** True for a colon-separated 48-bit Bluetooth address. */
private fun isBleMacAddress(value: String): Boolean {
    val octets = value.trim().split(":")
    return octets.size == 6 && octets.all { it.length == 2 && it.toIntOrNull(16) != null }
}

/** True for the 8-4-4-4-12 hexadecimal form CoreBluetooth uses for peripherals. */
fun isBlePeripheralUuid(value: String): Boolean {
    val groups = value.trim().split("-")
    if (groups.map { it.length } != listOf(8, 4, 4, 4, 12)) return false
    return groups.all { group -> group.all { it.digitToIntOrNull(16) != null } }
}

/** The saved value for a chosen Bluetooth device. */
fun rnodeBlePort(deviceId: String): String = RNODE_BLE_PREFIX + deviceId.trim()

/** A name for an RNode that never exposes a peripheral UUID or a URL-style value. */
fun rnodeDisplayName(radio: ReticulumRNodeInterface): String = when (rnodeConnectionType(radio.port)) {
    RNodeConnectionType.BLUETOOTH -> {
        val target = rnodeBleTarget(radio.port)
        radio.bleName?.takeIf(String::isNotBlank)
            ?: target.takeIf { rnodeBleTargetKind(it) == RNodeBleTargetKind.NAME }
            ?: "Bluetooth RNode"
    }
    RNodeConnectionType.WIFI -> rnodeWifiHost(radio.port).ifBlank { "Wi-Fi RNode" }
    RNodeConnectionType.SERIAL -> radio.port.trim()
}

/** How node-start messages refer to a Bluetooth RNode. */
fun rnodeBleLabel(port: String, bleName: String?): String {
    val target = rnodeBleTarget(port)
    return bleName?.trim()?.takeIf(String::isNotEmpty)
        ?: target.takeIf { rnodeBleTargetKind(it) == RNodeBleTargetKind.NAME }
        ?: RNodeBleText.FALLBACK_NAME
}

/** The device a Bluetooth RNode bridge connected to at node start. */
data class ResolvedBleRNode(val id: String, val name: String)

/** The outcome of matching a connected device against a saved RNode. */
data class RNodeBleUpgrade(val radio: ReticulumRNodeInterface, val note: String?)

/**
 * Brings a saved Bluetooth RNode up to date after a successful connection.
 *
 * A device name saved by an older version becomes the device id, with the
 * name kept for display. A saved id only gains a missing name. A bare
 * `ble://` is left alone: it means "the first RNode found" and is never
 * pinned to a device without the user choosing it. Radio parameters are
 * never changed. Returns null when there is nothing to change.
 */
fun upgradeRNodeBleInterface(radio: ReticulumRNodeInterface, resolved: ResolvedBleRNode): RNodeBleUpgrade? {
    if (rnodeConnectionType(radio.port) != RNodeConnectionType.BLUETOOTH) return null
    val id = resolved.id.trim()
    if (!isBleDeviceId(id)) return null
    val target = rnodeBleTarget(radio.port)
    val name = resolved.name.trim()
    return when (rnodeBleTargetKind(target)) {
        RNodeBleTargetKind.ANY -> null
        RNodeBleTargetKind.NAME -> {
            val savedName = name.ifBlank { target }
            RNodeBleUpgrade(
                radio.copy(port = rnodeBlePort(id), bleName = savedName),
                "Saved $savedName as your radio.",
            )
        }
        RNodeBleTargetKind.ID ->
            if (!target.equals(id, ignoreCase = true) || name.isBlank() || !radio.bleName.isNullOrBlank()) null
            else RNodeBleUpgrade(radio.copy(bleName = name), null)
    }
}

/** Applies [upgradeRNodeBleInterface] to the one Bluetooth RNode a node can have. */
fun upgradeRNodeBleInterfaces(
    radios: List<ReticulumRNodeInterface>,
    resolved: ResolvedBleRNode,
): Pair<List<ReticulumRNodeInterface>, String?>? {
    var note: String? = null
    var changed = false
    val upgraded = radios.map { radio ->
        val upgrade = if (changed) null else upgradeRNodeBleInterface(radio, resolved)
        if (upgrade == null) radio else {
            changed = true
            note = upgrade.note
            upgrade.radio
        }
    }
    return if (changed) upgraded to note else null
}

/** Signal strength as one of four levels, weakest first. */
enum class RNodeSignalLevel(val bars: Int, val label: String) {
    WEAK(1, "Weak signal"),
    FAIR(2, "Fair signal"),
    GOOD(3, "Good signal"),
    STRONG(4, "Strong signal"),
}

fun rnodeSignalLevel(rssi: Int): RNodeSignalLevel = when {
    rssi >= -60 -> RNodeSignalLevel.STRONG
    rssi >= -70 -> RNodeSignalLevel.GOOD
    rssi >= -80 -> RNodeSignalLevel.FAIR
    else -> RNodeSignalLevel.WEAK
}

/** One advertisement seen while searching for RNodes. */
data class RNodeBleSighting(val id: String, val name: String, val rssi: Int)

data class RNodeScanRow(
    val id: String,
    val name: String,
    val rssi: Int,
    /** The saved device, or one already paired with this phone. */
    val pinned: Boolean,
    /** The last four characters of the id, set when another row has the same name. */
    val idSuffix: String?,
)

/**
 * The rows of the search dialog.
 *
 * Devices are told apart by platform id, never by name. Pinned devices come
 * first, then stronger signals, then names. Once the search has run for
 * [freezeAfterMillis] the order stops changing so a row cannot move under a
 * finger: signal updates in place and later devices are added at the end.
 * A new search keeps the previous rows for [staleAfterMillis] and then drops
 * the ones that were not seen again. Not thread-safe.
 */
class RNodeScanList(
    private val freezeAfterMillis: Long = 3_000,
    private val staleAfterMillis: Long = 2_000,
) {
    private class Entry(var name: String, var rssi: Int, var pinned: Boolean, var lastSeenAt: Long)

    private val entries = linkedMapOf<String, Entry>()
    private var firstSearchAt: Long? = null
    private var searchAt: Long? = null
    private var frozenOrder: MutableList<String>? = null

    /** Marks the start of a search, the first or a repeated one. */
    fun beginSearch(nowMillis: Long) {
        if (firstSearchAt == null) firstSearchAt = nowMillis
        searchAt = nowMillis
    }

    fun observe(sighting: RNodeBleSighting, pinned: Boolean, nowMillis: Long) {
        val key = sighting.id.trim().uppercase()
        if (key.isEmpty()) return
        val entry = entries[key]
        if (entry == null) {
            entries[key] = Entry(sighting.name.trim(), sighting.rssi, pinned, nowMillis)
        } else {
            // Some advertisements omit the name; keep the one already seen.
            if (sighting.name.isNotBlank()) entry.name = sighting.name.trim()
            entry.rssi = sighting.rssi
            entry.pinned = pinned
            entry.lastSeenAt = nowMillis
        }
    }

    fun rows(nowMillis: Long): List<RNodeScanRow> {
        val searchStart = searchAt
        if (searchStart != null && nowMillis - searchStart >= staleAfterMillis) {
            val stale = entries.filterValues { it.lastSeenAt < searchStart }.keys.toList()
            stale.forEach(entries::remove)
            frozenOrder?.removeAll(stale)
        }
        val first = firstSearchAt
        val order = if (first == null || nowMillis - first < freezeAfterMillis) {
            sortedIds(entries.keys)
        } else {
            val frozen = frozenOrder ?: sortedIds(entries.keys).toMutableList().also { frozenOrder = it }
            frozen += sortedIds(entries.keys.filter { it !in frozen })
            frozen
        }
        val sharedNames = entries.values.groupingBy { it.name.lowercase() }.eachCount().filterValues { it > 1 }.keys
        return order.mapNotNull { id ->
            val entry = entries[id] ?: return@mapNotNull null
            RNodeScanRow(
                id = id,
                name = entry.name,
                rssi = entry.rssi,
                pinned = entry.pinned,
                idSuffix = if (entry.name.lowercase() in sharedNames) id.takeLast(4) else null,
            )
        }
    }

    private fun sortedIds(ids: Collection<String>): List<String> = ids.sortedWith(
        compareByDescending<String> { entries.getValue(it).pinned }
            .thenByDescending { entries.getValue(it).rssi }
            .thenBy { entries.getValue(it).name.lowercase() }
            .thenBy { it },
    )
}

/**
 * How long to wait before starting another search. Android silently returns
 * nothing once an app starts more than [maxStarts] searches in [windowMillis],
 * so the last allowed start is held back until the oldest one leaves the window.
 */
fun rnodeScanStartDelayMillis(
    previousStartsMillis: List<Long>,
    nowMillis: Long,
    maxStarts: Int = 4,
    windowMillis: Long = 30_000,
): Long {
    val recent = previousStartsMillis.filter { nowMillis - it < windowMillis }.sorted()
    if (recent.size < maxStarts) return 0
    return (recent[recent.size - maxStarts] + windowMillis - nowMillis).coerceAtLeast(0)
}

enum class RNodeBlePlatform { ANDROID, IOS }

sealed interface RNodeBleScanStatus {
    data object Idle : RNodeBleScanStatus
    data object Scanning : RNodeBleScanStatus

    /** The search ran its time or was stopped. */
    data object Finished : RNodeBleScanStatus
    data object BluetoothOff : RNodeBleScanStatus
    data object PermissionNotAsked : RNodeBleScanStatus
    data object PermissionDenied : RNodeBleScanStatus
    data object PermissionPermanentlyDenied : RNodeBleScanStatus

    /** Android 11 and older cannot search with Location turned off. */
    data object LocationOff : RNodeBleScanStatus
    data class Failed(val code: Int) : RNodeBleScanStatus
    data object Unsupported : RNodeBleScanStatus
}

enum class RNodeBlePairingFailure { WRONG_PIN, TIMEOUT, STALE_BOND, OTHER }

/** Progress of pairing on Android, or of the first trial connection on iOS. */
sealed interface RNodeBlePairingState {
    data object Idle : RNodeBlePairingState
    data class Waiting(val name: String) : RNodeBlePairingState
    data class AlreadyPaired(val name: String) : RNodeBlePairingState
    data class Paired(val name: String) : RNodeBlePairingState

    /** The RNode accepted a connection without a new pairing. */
    data class Ready(val name: String) : RNodeBlePairingState
    data class Failed(val name: String, val reason: RNodeBlePairingFailure, val code: Int = 0) : RNodeBlePairingState
}

/** Platform Bluetooth access for choosing an RNode in Settings. */
interface RNodeBlePicker {
    val platform: RNodeBlePlatform
    val scanStatus: StateFlow<RNodeBleScanStatus>
    val sightings: Flow<RNodeBleSighting>
    val pairing: StateFlow<RNodeBlePairingState>

    /** Asks for whatever access is missing, then searches for about 20 seconds. */
    fun startScan()
    fun stopScan()

    /** Acts on the current blocking status: ask again, turn Bluetooth on, or open system settings. */
    fun resolveScanBlocker()

    fun pair(deviceId: String, name: String)
    fun cancelPairing()

    /** Whether the system has a pairing for this device, or null when that cannot be known. */
    fun isPaired(deviceId: String): Boolean?

    /** The system's name for a device it already knows. */
    fun knownName(deviceId: String): String? = null
    fun openBluetoothSettings() = Unit
}

/** User-facing text for the Bluetooth RNode picker and node start. */
object RNodeBleText {
    const val CHOOSE_BEFORE_START = "Choose an RNode for this interface before starting the node."
    const val FALLBACK_NAME = "the RNode"

    fun scanStatusMessage(status: RNodeBleScanStatus, platform: RNodeBlePlatform, hasRows: Boolean): String? = when (status) {
        RNodeBleScanStatus.Idle -> null
        RNodeBleScanStatus.Scanning -> if (hasRows) "Searching..." else "Searching for RNodes..."
        RNodeBleScanStatus.Finished -> if (hasRows) null else
            "No RNodes found. Make sure it is powered on with Bluetooth enabled, and keep it within a few metres. " +
                "An RNode that is already connected to another phone will not appear."
        RNodeBleScanStatus.BluetoothOff ->
            if (platform == RNodeBlePlatform.IOS) "Bluetooth is turned off. Turn on Bluetooth in Control Center or Settings."
            else "Bluetooth is turned off."
        RNodeBleScanStatus.PermissionNotAsked -> "Starling needs permission to find nearby devices."
        RNodeBleScanStatus.PermissionDenied -> "Starling can't search without Bluetooth permission."
        RNodeBleScanStatus.PermissionPermanentlyDenied ->
            "Bluetooth permission is turned off for Starling. Turn it on in system settings."
        RNodeBleScanStatus.LocationOff ->
            "Android needs Location turned on to search for Bluetooth devices. Starling does not use your location."
        is RNodeBleScanStatus.Failed -> "Bluetooth search failed (code ${status.code}). Try again."
        RNodeBleScanStatus.Unsupported -> "This device doesn't support Bluetooth LE."
    }

    /** The label of the button that resolves a blocking status, if it has one. */
    fun scanBlockerAction(status: RNodeBleScanStatus, platform: RNodeBlePlatform): String? = when (status) {
        RNodeBleScanStatus.BluetoothOff -> if (platform == RNodeBlePlatform.ANDROID) "Turn on Bluetooth" else null
        RNodeBleScanStatus.PermissionNotAsked, RNodeBleScanStatus.PermissionDenied -> "Allow"
        RNodeBleScanStatus.PermissionPermanentlyDenied -> "Open settings"
        RNodeBleScanStatus.LocationOff -> "Open location settings"
        else -> null
    }

    fun pairingFailureMessage(failure: RNodeBlePairingState.Failed, platform: RNodeBlePlatform): String = when {
        failure.reason == RNodeBlePairingFailure.STALE_BOND ->
            "This RNode no longer recognises this iPhone. Remove it in Settings > Bluetooth (if listed) and clear " +
                "paired devices on the RNode, then try again."
        platform == RNodeBlePlatform.IOS ->
            "iOS didn't finish pairing. Try again and, if asked, enter the PIN shown on the RNode."
        failure.reason == RNodeBlePairingFailure.WRONG_PIN ->
            "The PIN didn't match. Try again and enter the PIN currently shown on the RNode."
        failure.reason == RNodeBlePairingFailure.TIMEOUT -> "Pairing timed out. Wake the RNode's screen, then try again."
        else -> "Pairing didn't complete (reason ${failure.code}). Try again."
    }

    fun notFound(name: String) = "Couldn't reach $name. Check that it's powered on and in range, then try again."
    fun notPairedAndroid(name: String) = "$name isn't paired with this phone. Open Settings, choose Change, and pair it."
    fun notPairedIos(name: String) =
        "$name didn't accept the connection. Open Settings and reconnect it, entering the PIN shown on the RNode if asked."
    fun pairingLost(name: String) = "Pairing with $name was removed. Open Settings, choose Change, and pair it again."
    fun permission(name: String) = "Allow Starling Bluetooth access to connect to $name."
    fun heldElsewhere(name: String) = "$name may be connected to another device. Disconnect it there and try again."
    fun unknownToIos(name: String) = "iOS no longer knows $name. Choose it again in Settings."
}
