package com.sidpatchy.Starling

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** How Starling obtains access to Reticulum. */
enum class ReticulumRuntimeMode {
    /** Starling owns and configures its own RNS instance. */
    EMBEDDED,

    /** Advanced: attach to an RNS shared instance already running on this device. */
    EXISTING_SHARED_INSTANCE,
}

/** A TCP client interface to an existing Reticulum peer or gateway. */
data class ReticulumTcpPeer(
    val host: String,
    val port: Int,
    val ifacNetworkName: String? = null,
    val ifacPassphrase: String? = null,
) {
    val id: String get() = "${host.trim().lowercase()}:$port"

    fun validationError(): String? = when {
        host.isBlank() -> "Enter a host name or IP address."
        host.any { !(it.isLetterOrDigit() || it in "._:-") } -> "Use only letters, numbers, dots, dashes, underscores, and colons in the host."
        port !in 1..65535 -> "Port must be between 1 and 65535."
        else -> null
    }
}

/** A host-controlled RNode radio attached over serial, TCP, or BLE. */
data class ReticulumRNodeInterface(
    val port: String,
    val frequencyHz: Long,
    val bandwidthHz: Int,
    val txPowerDbm: Int,
    val spreadingFactor: Int,
    val codingRate: Int,
) {
    val id: String get() = port.trim().lowercase()

    fun validationError(): String? = when {
        port.isBlank() -> "Enter a serial port, tcp://host, or ble://device name."
        port.any { it.code < 32 || it.code == 127 } -> "The RNode connection cannot contain control characters."
        frequencyHz !in 137_000_000L..3_000_000_000L -> "RNode frequency must be between 137 MHz and 3 GHz."
        bandwidthHz !in 7_800..1_625_000 -> "RNode bandwidth must be between 7.8 kHz and 1.625 MHz."
        txPowerDbm !in 0..37 -> "RNode transmit power must be between 0 and 37 dBm."
        spreadingFactor !in 5..12 -> "RNode spreading factor must be between 5 and 12."
        codingRate !in 5..8 -> "RNode coding rate must be between 5 and 8."
        else -> null
    }
}

data class ReticulumNodeConfig(
    val mode: ReticulumRuntimeMode = ReticulumRuntimeMode.EMBEDDED,
    val tcpPeers: List<ReticulumTcpPeer> = emptyList(),
    val rnodeInterfaces: List<ReticulumRNodeInterface> = emptyList(),
    val existingInstanceRpcKeyHex: String? = null,
) {
    fun validationError(): String? {
        if (mode == ReticulumRuntimeMode.EXISTING_SHARED_INSTANCE && !isValidReticulumRpcKey(existingInstanceRpcKeyHex.orEmpty())) {
            return "Enter the existing local RNS instance's RPC key as at least 32 hexadecimal characters."
        }
        if (mode != ReticulumRuntimeMode.EMBEDDED && (tcpPeers.isNotEmpty() || rnodeInterfaces.isNotEmpty())) {
            return "Interfaces can only be configured for Starling's embedded node."
        }
        if (mode == ReticulumRuntimeMode.EMBEDDED && tcpPeers.isEmpty() && rnodeInterfaces.isEmpty()) {
            return "Add a TCP peer or an RNode interface before starting the embedded node."
        }
        val invalidPeer = tcpPeers.firstOrNull { it.validationError() != null }
        if (invalidPeer != null) return invalidPeer.validationError()
        if (tcpPeers.map { it.id }.distinct().size != tcpPeers.size) {
            return "Each TCP peer must use a unique host and port."
        }
        for (peer in tcpPeers) {
            val networkName = peer.ifacNetworkName.orEmpty()
            if (networkName.length > 128 || networkName.any { it.code !in 32..126 }) {
                return "IFAC network name must be at most 128 printable ASCII characters."
            }
            val passphrase = peer.ifacPassphrase.orEmpty()
            if (passphrase.length > 128 || passphrase.any { it.code !in 32..126 }) {
                return "IFAC passphrase must be at most 128 printable ASCII characters on one line."
            }
            if ((networkName.isNotBlank() || passphrase.isNotBlank()) && (networkName.isBlank() || passphrase.isBlank())) {
                return "For each peer, enter both IFAC values or leave both blank."
            }
        }
        val invalidRNode = rnodeInterfaces.firstOrNull { it.validationError() != null }
        if (invalidRNode != null) return invalidRNode.validationError()
        if (rnodeInterfaces.map { it.id }.distinct().size != rnodeInterfaces.size) {
            return "Each RNode interface must use a unique connection."
        }
        return null
    }
}

/** Shared-instance RPC keys contain at least 16 bytes, represented as whole hexadecimal pairs. */
fun isValidReticulumRpcKey(key: String): Boolean =
    key.length >= 32 && key.length % 2 == 0 && key.all { it.digitToIntOrNull(16) != null }

sealed interface ReticulumNodeState {
    data object Stopped : ReticulumNodeState
    data class Starting(val mode: ReticulumRuntimeMode) : ReticulumNodeState
    data class PreparingRuntime(val detail: String) : ReticulumNodeState
    /** Node startup succeeded; online peer IDs reflect TCP links, not RRC chat readiness. */
    data class Running(
        val mode: ReticulumRuntimeMode,
        val tcpPeers: List<ReticulumTcpPeer>,
        val onlineTcpPeerIds: Set<String>,
        val sharedInstanceConnected: Boolean = false,
        val rnodeInterfaces: List<ReticulumRNodeInterface> = emptyList(),
        val onlineRNodePorts: Set<String> = emptySet(),
    ) : ReticulumNodeState {
        val tcpInterfaceOnline: Boolean get() = onlineTcpPeerIds.isNotEmpty()
        val rnodeInterfaceOnline: Boolean get() = onlineRNodePorts.isNotEmpty()
        val interfaceOnline: Boolean get() = tcpInterfaceOnline || rnodeInterfaceOnline || sharedInstanceConnected
    }
    data class Failed(val message: String) : ReticulumNodeState
    data class Unavailable(val reason: String) : ReticulumNodeState
}

/** Platform-specific lifecycle for the local RNS runtime. */
interface ReticulumNodeController {
    val state: StateFlow<ReticulumNodeState>
    val isSupported: Boolean
    /** False when the platform can only test a TCP socket, not exchange RNS packets. */
    val supportsReticulumProtocol: Boolean get() = true
    /** Whether this platform exposes RNode connection settings. */
    val supportsRNodeInterfaces: Boolean get() = false
    suspend fun start(config: ReticulumNodeConfig)
    suspend fun disconnect()
    suspend fun refreshState() = Unit
}

class UnsupportedReticulumNodeController(reason: String) : ReticulumNodeController {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<ReticulumNodeState>(ReticulumNodeState.Unavailable(reason))
    override val state: StateFlow<ReticulumNodeState> = _state
    override val isSupported: Boolean = false
    override suspend fun start(config: ReticulumNodeConfig) {
        _state.value = ReticulumNodeState.Unavailable("The embedded RNS runtime is not available on this platform yet.")
    }
    override suspend fun disconnect() { _state.value = ReticulumNodeState.Unavailable("The embedded RNS runtime is not available on this platform yet.") }
}

/**
 * Settings needed by the shared session layer to find an RRC hub. Reticulum's
 * node/interface configuration and identity storage stay behind the platform
 * transport implementation.
 */
data class ReticulumConnectionConfig(
    val hubDestination: String,
    val displayName: String,
    val networkId: String,
)

sealed interface ReticulumConnectionState {
    data object Disconnected : ReticulumConnectionState
    data object Starting : ReticulumConnectionState
    data object Connecting : ReticulumConnectionState
    data class Connected(val hubName: String? = null) : ReticulumConnectionState
    data class Failed(val message: String) : ReticulumConnectionState
}

/** The room snapshot announced by an RRC hub. */
data class RrcRoom(
    val id: String,
    val name: String,
    val topic: String = "",
    val memberCount: Int = 0,
)

/** A room message as delivered by RRC, before it is mapped into UI state. */
data class RrcRoomMessage(
    val id: String,
    val roomId: String,
    val senderId: String,
    val senderName: String,
    val body: String,
    val timestampMillis: Long,
    val isAction: Boolean = false,
    val isNotice: Boolean = false,
    val isMine: Boolean = false,
    val senderLxmfAddress: String? = null,
)

data class LxmfDirectMessage(
    val id: String,
    val address: String,
    val senderName: String,
    val body: String,
    val timestampMillis: Long,
    val isMine: Boolean,
    val deliveryState: String = "",
)

sealed interface ReticulumEvent {
    data class LocalIdentityChanged(val identityHash: String) : ReticulumEvent
    data class LocalMemberChanged(val member: ChatMember) : ReticulumEvent
    data class RoomsChanged(val rooms: List<RrcRoom>) : ReticulumEvent
    data class RoomJoined(val roomId: String) : ReticulumEvent
    data class RoomLeft(val roomId: String) : ReticulumEvent
    data class MemberJoined(
        val roomId: String,
        val member: ChatMember,
        val timestampMillis: Long = 0,
    ) : ReticulumEvent
    data class MemberLeft(
        val roomId: String,
        val memberId: String,
        val timestampMillis: Long = 0,
    ) : ReticulumEvent
    data class RoomMessageReceived(val message: RrcRoomMessage) : ReticulumEvent
    data class DirectMessage(val message: LxmfDirectMessage) : ReticulumEvent
    data class DeliveryStatus(val messageId: String, val address: String, val state: String) : ReticulumEvent
    data class LxmfError(val message: String, val address: String? = null) : ReticulumEvent
    data class MembersChanged(val roomId: String, val members: List<ChatMember>) : ReticulumEvent
    data class Error(
        val message: String,
        val roomId: String? = null,
        val timestampMillis: Long = 0,
    ) : ReticulumEvent
}

/**
 * Chat-protocol seam for RRC and LXMF. Node lifecycle and underlay interface
 * management are handled separately by [ReticulumNodeController]; platform
 * implementations own runtime communication while common code stays independent of
 * platform and Python APIs.
 *
 * RRC room traffic and LXMF direct delivery are implemented by platform adapters.
 */
interface ReticulumTransport {
    val state: StateFlow<ReticulumConnectionState>
    val nodeReady: StateFlow<Boolean>
    val localDeliveryAddress: StateFlow<String?>
    val events: Flow<ReticulumEvent>

    suspend fun connect(config: ReticulumConnectionConfig)
    suspend fun disconnect()
    suspend fun joinRoom(roomId: String)
    suspend fun leaveRoom(roomId: String)
    suspend fun sendRoomMessage(roomId: String, body: String, isAction: Boolean = false)
    suspend fun sendDirectMessage(address: String, body: String)
    suspend fun setLocalDisplayName(displayName: String)
}

/** Factory boundary for platform-specific chat transports. */
fun interface ReticulumTransportFactory {
    fun create(): ReticulumTransport
}
