package com.sidpatchy.Starling

/** Which side opens the GATT connection between two ble-reticulum v2.2 peers. */
enum class BlePeerConnectionDirection {
    /** The local address is lower: connect to the peer as the central. */
    INITIATE,

    /** The local address is higher: keep advertising and let the peer connect. */
    WAIT,

    /**
     * The local address is not known, so the addresses cannot be ordered.
     * Either side may connect; duplicate links are resolved by peer identity.
     */
    UNKNOWN,
}

/** Android reports this placeholder instead of the adapter address. */
const val BLE_UNDISCLOSED_ADDRESS = "02:00:00:00:00:00"

/** Parses a colon-separated 48-bit Bluetooth address, or null when it is not one. */
fun parseBleAddress(address: String?): Long? {
    val octets = address?.trim()?.split(":") ?: return null
    if (octets.size != 6 || octets.any { it.length != 2 }) return null
    var value = 0L
    for (octet in octets) {
        value = (value shl 8) or (octet.toIntOrNull(16) ?: return null).toLong()
    }
    return value
}

/**
 * Applies the protocol's address ordering: the lower address initiates.
 * An undisclosed, zero, or malformed address on either side, or two equal
 * addresses, cannot be ordered and yield [BlePeerConnectionDirection.UNKNOWN].
 */
fun blePeerConnectionDirection(localAddress: String?, peerAddress: String?): BlePeerConnectionDirection {
    val local = parseBleAddress(localAddress)
    val peer = parseBleAddress(peerAddress)
    if (local == null || peer == null || local == 0L || local == parseBleAddress(BLE_UNDISCLOSED_ADDRESS)) {
        return BlePeerConnectionDirection.UNKNOWN
    }
    return when {
        local < peer -> BlePeerConnectionDirection.INITIATE
        local > peer -> BlePeerConnectionDirection.WAIT
        else -> BlePeerConnectionDirection.UNKNOWN
    }
}

/**
 * Bytes of one write or notification that fit in an ATT MTU. Android
 * truncates longer values and Bluetooth caps an attribute value at 512
 * bytes, so this is the fragment size given to the protocol.
 */
fun blePeerPayloadSize(attMtu: Int): Int =
    (attMtu - BLE_ATT_HEADER_SIZE).coerceIn(BLE_MINIMUM_PAYLOAD, BLE_MAXIMUM_ATTRIBUTE_LENGTH)

/**
 * How long to wait before starting a failed scan again: two seconds after
 * the first failure, doubling after each further one, up to a minute.
 */
fun blePeerScanRetryDelayMs(attempt: Int): Long {
    val doublings = (attempt - 1).coerceIn(0, 16)
    return (BLE_SCAN_RETRY_BASE_MS shl doublings).coerceAtMost(BLE_SCAN_RETRY_MAX_MS)
}

private const val BLE_SCAN_RETRY_BASE_MS = 2_000L
private const val BLE_SCAN_RETRY_MAX_MS = 60_000L

/** The one-line Bluetooth peering status shown for a running node. */
fun blePeerStatusText(peerCount: Int, bluetoothOff: Boolean): String =
    if (bluetoothOff) "Bluetooth is off; Bluetooth peers will connect when it is turned on"
    else "$peerCount Bluetooth ${if (peerCount == 1) "peer" else "peers"} connected"

const val BLE_DEFAULT_ATT_MTU = 23
const val BLE_REQUESTED_ATT_MTU = 517
private const val BLE_ATT_HEADER_SIZE = 3
private const val BLE_MAXIMUM_ATTRIBUTE_LENGTH = 512
private const val BLE_MINIMUM_PAYLOAD = BLE_DEFAULT_ATT_MTU - BLE_ATT_HEADER_SIZE

/**
 * Runs GATT operations strictly one at a time, in submission order.
 *
 * Android accepts a single outstanding GATT request per connection and drops
 * others without reporting them, so every read, write, MTU request, and
 * notification goes through this queue. It is not thread-safe; the owner
 * guards each call with its own lock and starts the operations it is handed.
 */
class BlePeerOperationQueue<T : Any> {
    private val pending = ArrayDeque<T>()

    /** The operation that has been started and not yet finished. */
    var current: T? = null
        private set

    val size: Int get() = pending.size + if (current == null) 0 else 1

    /** Queues [operation]; returns it when it should be started now. */
    fun submit(operation: T): T? {
        pending.addLast(operation)
        return startNext()
    }

    /**
     * Marks [operation] finished and returns the next one to start, if any.
     * A completion for anything but the running operation is ignored, which
     * makes a late callback after a timeout harmless.
     */
    fun finish(operation: T): T? {
        if (current != operation) return null
        current = null
        return startNext()
    }

    /**
     * Drops queued operations matching [predicate] and returns them so their
     * callers can be told. The running operation is never dropped here.
     */
    fun cancelPending(predicate: (T) -> Boolean): List<T> {
        val cancelled = pending.filter(predicate)
        pending.removeAll(cancelled)
        return cancelled
    }

    /** Empties the queue and returns everything that was queued or running. */
    fun clear(): List<T> {
        val dropped = listOfNotNull(current) + pending
        current = null
        pending.clear()
        return dropped
    }

    private fun startNext(): T? {
        if (current != null) return null
        val next = pending.removeFirstOrNull() ?: return null
        current = next
        return next
    }
}
