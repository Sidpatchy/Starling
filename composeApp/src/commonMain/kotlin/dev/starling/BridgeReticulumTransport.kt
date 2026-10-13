package dev.starling

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** RRC/LXMF chat behavior shared by the desktop sidecar and embedded iOS bridge. */
internal class BridgeReticulumTransport(
    private val nodeState: StateFlow<ReticulumNodeState>,
    bridgeEvents: Flow<String>,
    private val sendCommand: suspend (String) -> Unit,
    dispatcher: CoroutineDispatcher,
) : ReticulumTransport {
    private val _state = MutableStateFlow<ReticulumConnectionState>(ReticulumConnectionState.Disconnected)
    override val state: StateFlow<ReticulumConnectionState> = _state.asStateFlow()
    private val _localDeliveryAddress = MutableStateFlow<String?>(null)
    override val localDeliveryAddress: StateFlow<String?> = _localDeliveryAddress.asStateFlow()
    private val _nodeReady = MutableStateFlow(false)
    override val nodeReady: StateFlow<Boolean> = _nodeReady.asStateFlow()
    private val _events = MutableSharedFlow<ReticulumEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<ReticulumEvent> = _events.asSharedFlow()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    init {
        scope.launch {
            bridgeEvents.collect { line ->
                val parsed = parseRrcBridgeEvent(line) ?: return@collect
                parsed.connectionState?.let { _state.value = it }
                parsed.localDeliveryAddress?.let { _localDeliveryAddress.value = it.ifBlank { null } }
                parsed.event?.let { event ->
                    _events.emit(event)
                }
            }
        }
        scope.launch {
            nodeState.collect { state ->
                when (state) {
                    ReticulumNodeState.Stopped -> {
                        _nodeReady.value = false
                        _state.value = ReticulumConnectionState.Disconnected
                        _localDeliveryAddress.value = null
                    }
                    is ReticulumNodeState.Failed -> {
                        _nodeReady.value = false
                        _localDeliveryAddress.value = null
                        if (_state.value !is ReticulumConnectionState.Disconnected) {
                            _state.value = ReticulumConnectionState.Failed(state.message)
                        }
                    }
                    is ReticulumNodeState.Running -> _nodeReady.value = true
                    else -> _nodeReady.value = false
                }
            }
        }
    }

    override suspend fun connect(config: ReticulumConnectionConfig) {
        val destination = config.hubDestination.trim()
        if (destination.length != 32 || destination.any { it.digitToIntOrNull(16) == null }) {
            _state.value = ReticulumConnectionState.Failed("RRC hub destination must be a 32-character hexadecimal hash.")
            return
        }
        if (nodeState.value !is ReticulumNodeState.Running) {
            _state.value = ReticulumConnectionState.Failed("Start the Reticulum node before connecting to an RRC hub.")
            return
        }
        _state.value = ReticulumConnectionState.Connecting
        try {
            sendCommand(
                listOf("RRC_CONNECT", encodeBridgeBase64(destination), encodeBridgeBase64(config.displayName.trim().take(32)))
                    .joinToString("\t"),
            )
        } catch (error: Exception) {
            _state.value = ReticulumConnectionState.Failed(error.message ?: "Could not connect to the RRC hub.")
        }
    }

    override suspend fun disconnect() {
        _state.value = ReticulumConnectionState.Disconnected
        runCatching { sendCommand("RRC_DISCONNECT") }
    }

    override suspend fun joinRoom(roomId: String) {
        requireConnectedOrConnecting()
        send("RRC_JOIN\t${encodeBridgeBase64(roomId)}")
    }

    override suspend fun leaveRoom(roomId: String) {
        requireConnectedOrConnecting()
        send("RRC_PART\t${encodeBridgeBase64(roomId)}")
    }

    override suspend fun sendRoomMessage(roomId: String, body: String, isAction: Boolean) {
        if (_state.value !is ReticulumConnectionState.Connected) {
            throw IllegalStateException("Connect to an RRC hub before sending room messages.")
        }
        send("RRC_SEND\t${encodeBridgeBase64(roomId)}\t${encodeBridgeBase64(body)}\t${if (isAction) 1 else 0}")
    }

    override suspend fun sendDirectMessage(address: String, body: String) {
        if (_localDeliveryAddress.value == null) {
            throw IllegalStateException("Start the Reticulum node before sending LXMF messages.")
        }
        sendCommand("LXMF_SEND\t${encodeBridgeBase64(address)}\t${encodeBridgeBase64(body)}")
    }

    override suspend fun setLocalDisplayName(displayName: String) {
        if (nodeState.value !is ReticulumNodeState.Running) return
        sendCommand("LXMF_SET_NAME\t${encodeBridgeBase64(displayName)}")
    }

    suspend fun close() {
        runCatching { disconnect() }
        scope.cancel()
    }

    private fun requireConnectedOrConnecting() {
        if (_state.value !is ReticulumConnectionState.Connected && _state.value !is ReticulumConnectionState.Connecting) {
            throw IllegalStateException("Connect to an RRC hub first.")
        }
    }

    private suspend fun send(command: String) {
        try {
            sendCommand(command)
        } catch (error: Exception) {
            val message = error.message ?: "Could not send the RRC command."
            _state.value = ReticulumConnectionState.Failed(message)
            _events.emit(ReticulumEvent.Error(message))
        }
    }
}
