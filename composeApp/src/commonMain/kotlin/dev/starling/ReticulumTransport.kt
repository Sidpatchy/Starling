package dev.starling

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

data class ReticulumNodeConfig(
    val mode: ReticulumRuntimeMode = ReticulumRuntimeMode.EMBEDDED,
    val tcpPeers: List<ReticulumTcpPeer> = emptyList(),
    val existingInstanceRpcKeyHex: String? = null,
) {
    fun ifacValidationError(): String? {
        if (mode != ReticulumRuntimeMode.EMBEDDED && tcpPeers.isNotEmpty()) {
            return "IFAC can only be configured for Starling's embedded TCP interface."
        }
        if (mode == ReticulumRuntimeMode.EMBEDDED && tcpPeers.isEmpty()) {
            return "Add at least one TCP peer before starting the embedded node."
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
        return null
    }
}

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
    ) : ReticulumNodeState {
        val tcpInterfaceOnline: Boolean get() = onlineTcpPeerIds.isNotEmpty()
    }
    data class Failed(val message: String) : ReticulumNodeState
    data class Unavailable(val reason: String) : ReticulumNodeState
}

/** Platform-specific lifecycle for the local RNS runtime. */
interface ReticulumNodeController {
    val state: StateFlow<ReticulumNodeState>
    val isSupported: Boolean
    suspend fun start(config: ReticulumNodeConfig)
    suspend fun disconnect()
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
    data class LocalMemberChanged(val member: ChatMember) : ReticulumEvent
    data class RoomsChanged(val rooms: List<RrcRoom>) : ReticulumEvent
    data class RoomJoined(val roomId: String) : ReticulumEvent
    data class RoomLeft(val roomId: String) : ReticulumEvent
    data class MemberJoined(val roomId: String, val member: ChatMember) : ReticulumEvent
    data class MemberLeft(val roomId: String, val memberId: String) : ReticulumEvent
    data class RoomMessageReceived(val message: RrcRoomMessage) : ReticulumEvent
    data class DirectMessage(val message: LxmfDirectMessage) : ReticulumEvent
    data class DeliveryStatus(val messageId: String, val address: String, val state: String) : ReticulumEvent
    data class MembersChanged(val roomId: String, val members: List<ChatMember>) : ReticulumEvent
    data class Error(val message: String) : ReticulumEvent
}

/**
 * Chat-protocol seam for RRC and LXMF. Node lifecycle and underlay interface
 * management are handled separately by [ReticulumNodeController]; platform
 * implementations own wire encoding while common code stays independent of
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
}

/** Factory boundary for platform-specific chat transports. */
fun interface ReticulumTransportFactory {
    fun create(): ReticulumTransport
}
