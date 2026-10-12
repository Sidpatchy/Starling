package dev.starling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** RRC 1.0 client control path over the Android Reticulum service. */
class AndroidReticulumTransport(context: Context) : ReticulumTransport {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<ReticulumConnectionState>(ReticulumConnectionState.Disconnected)
    override val state: StateFlow<ReticulumConnectionState> = _state.asStateFlow()
    private val _localDeliveryAddress = MutableStateFlow<String?>(null)
    override val localDeliveryAddress = _localDeliveryAddress.asStateFlow()
    private val _nodeReady = MutableStateFlow(false)
    override val nodeReady = _nodeReady.asStateFlow()
    private val _events = MutableSharedFlow<ReticulumEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<ReticulumEvent> = _events.asSharedFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ReticulumNodeService.ACTION_STATE) {
                when (intent.getStringExtra(ReticulumNodeService.EXTRA_STATE)) {
                    ReticulumNodeService.STATE_RUNNING -> _nodeReady.value = true
                    ReticulumNodeService.STATE_STOPPED -> {
                        _nodeReady.value = false
                        _localDeliveryAddress.value = null
                        _state.value = ReticulumConnectionState.Disconnected
                    }
                    ReticulumNodeService.STATE_FAILED -> {
                        _nodeReady.value = false
                        _localDeliveryAddress.value = null
                        _state.value = ReticulumConnectionState.Disconnected
                    }
                }
                return
            }
            if (intent?.action != ReticulumNodeService.ACTION_RRC_EVENT) return
            val parsed = intent.getStringExtra(ReticulumNodeService.EXTRA_RRC_EVENT)
                ?.let(::parseRrcBridgeEvent) ?: return
            parsed.connectionState?.let { _state.value = it }
            parsed.localDeliveryAddress?.let { _localDeliveryAddress.value = it.ifBlank { null } }
            parsed.event?.let {
                _events.tryEmit(it)
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter().apply {
                addAction(ReticulumNodeService.ACTION_RRC_EVENT)
                addAction(ReticulumNodeService.ACTION_STATE)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // The service periodically publishes its current RRC state so a new
        // Activity instance can restore the connection status.
    }

    override suspend fun connect(config: ReticulumConnectionConfig) {
        val destination = config.hubDestination.trim()
        if (destination.length != 32 || destination.any { it.digitToIntOrNull(16) == null }) {
            _state.value = ReticulumConnectionState.Failed("RRC hub destination must be a 32-character hexadecimal hash.")
            return
        }
        _state.value = ReticulumConnectionState.Connecting
        sendCommand(
            ReticulumNodeService.ACTION_RRC_CONNECT,
            ReticulumNodeService.EXTRA_HUB_DESTINATION to destination,
            ReticulumNodeService.EXTRA_DISPLAY_NAME to config.displayName.trim().take(32),
        )
    }

    override suspend fun disconnect() {
        val shouldTellService = _nodeReady.value || _state.value != ReticulumConnectionState.Disconnected
        _state.value = ReticulumConnectionState.Disconnected
        if (shouldTellService) sendCommand(ReticulumNodeService.ACTION_RRC_DISCONNECT)
    }

    override suspend fun joinRoom(roomId: String) {
        sendCommand(ReticulumNodeService.ACTION_RRC_JOIN, ReticulumNodeService.EXTRA_ROOM to roomId)
    }

    override suspend fun leaveRoom(roomId: String) {
        sendCommand(ReticulumNodeService.ACTION_RRC_PART, ReticulumNodeService.EXTRA_ROOM to roomId)
    }

    override suspend fun sendRoomMessage(roomId: String, body: String, isAction: Boolean) {
        sendCommand(
            ReticulumNodeService.ACTION_RRC_SEND,
            ReticulumNodeService.EXTRA_ROOM to roomId,
            ReticulumNodeService.EXTRA_BODY to body,
            ReticulumNodeService.EXTRA_ACTION to isAction.toString(),
        )
    }

    override suspend fun sendDirectMessage(address: String, body: String) {
        if (_localDeliveryAddress.value == null) {
            throw IllegalStateException("Start the Reticulum node before sending LXMF messages.")
        }
        sendCommand(
            ReticulumNodeService.ACTION_LXMF_SEND,
            ReticulumNodeService.EXTRA_LXMF_ADDRESS to address,
            ReticulumNodeService.EXTRA_BODY to body,
        )
    }

    override suspend fun setLocalDisplayName(displayName: String) {
        if (!_nodeReady.value) return
        sendCommand(
            ReticulumNodeService.ACTION_LXMF_SET_NAME,
            ReticulumNodeService.EXTRA_DISPLAY_NAME to displayName.take(32),
        )
    }

    fun dispose() {
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    private fun sendCommand(action: String, vararg extras: Pair<String, String>) {
        val intent = Intent(appContext, ReticulumNodeService::class.java).setAction(action)
        extras.forEach { (key, value) -> intent.putExtra(key, value) }
        try {
            appContext.startService(intent)
        } catch (error: Exception) {
            val message = error.message ?: "Could not contact the Reticulum service."
            _state.value = ReticulumConnectionState.Failed(message)
            _events.tryEmit(ReticulumEvent.Error(message))
        }
    }
}
