package dev.starling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** Starts and observes the isolated Android RNS service without blocking Compose. */
class AndroidReticulumNodeController(context: Context) : ReticulumNodeController {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<ReticulumNodeState>(ReticulumNodeState.Stopped)
    override val state: StateFlow<ReticulumNodeState> = _state.asStateFlow()
    override val isSupported: Boolean = true
    private var pendingStateQuery: CompletableDeferred<ReticulumNodeState>? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ReticulumNodeService.ACTION_STATE) return
            val state = stateFromIntent(intent)
            _state.value = state
            pendingStateQuery?.complete(state)
        }
    }

    init {
        val filter = IntentFilter(ReticulumNodeService.ACTION_STATE)
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override suspend fun refreshState() {
        val response = CompletableDeferred<ReticulumNodeState>()
        pendingStateQuery = response
        try {
            appContext.startService(Intent(appContext, ReticulumNodeService::class.java).apply {
                action = ReticulumNodeService.ACTION_NODE_STATUS
            })
            withTimeoutOrNull(2_000) { response.await() }
        } catch (_: Exception) {
            // Keep the last known state; startup code can still make a guarded attempt.
        } finally {
            if (pendingStateQuery === response) pendingStateQuery = null
        }
    }

    override suspend fun start(config: ReticulumNodeConfig) {
        if (config.rnodeInterfaces.isNotEmpty()) {
            _state.value = ReticulumNodeState.Failed(
                "Direct RNode USB/Bluetooth access is not available in Starling's Android runtime yet. Use an RNS TCP gateway for now.",
            )
            return
        }

        config.validationError()?.let {
            _state.value = ReticulumNodeState.Failed(it)
            return
        }

        _state.value = ReticulumNodeState.Starting(config.mode)
        val peersJson = JSONArray()
        config.tcpPeers.forEach { peer ->
            peersJson.put(
                JSONObject()
                    .put("host", peer.host.trim())
                    .put("port", peer.port)
                    .put("ifac_network_name", peer.ifacNetworkName.orEmpty())
                    .put("ifac_passphrase", peer.ifacPassphrase.orEmpty()),
            )
        }
        val intent = Intent(appContext, ReticulumNodeService::class.java).apply {
            action = ReticulumNodeService.ACTION_START
            putExtra(ReticulumNodeService.EXTRA_MODE, config.mode.name)
            putExtra(ReticulumNodeService.EXTRA_TCP_PEERS, peersJson.toString())
            putExtra(ReticulumNodeService.EXTRA_RPC_KEY, config.existingInstanceRpcKeyHex.orEmpty())
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appContext.startForegroundService(intent)
            else appContext.startService(intent)
        } catch (error: Exception) {
            _state.value = ReticulumNodeState.Failed(error.message ?: "Could not start the Reticulum service.")
        }
    }

    override suspend fun disconnect() {
        val intent = Intent(appContext, ReticulumNodeService::class.java).apply {
            action = ReticulumNodeService.ACTION_STOP
        }
        try {
            appContext.startService(intent)
        } catch (_: Exception) {
            _state.value = ReticulumNodeState.Stopped
        }
    }

    fun dispose() {
        try {
            appContext.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
    }

    private fun stateFromIntent(intent: Intent): ReticulumNodeState {
        val mode = runCatching {
            ReticulumRuntimeMode.valueOf(intent.getStringExtra(ReticulumNodeService.EXTRA_MODE).orEmpty())
        }.getOrNull() ?: ReticulumRuntimeMode.EMBEDDED
        return when (intent.getStringExtra(ReticulumNodeService.EXTRA_STATE)) {
            ReticulumNodeService.STATE_STARTING -> ReticulumNodeState.Starting(mode)
            ReticulumNodeService.STATE_RUNNING -> {
                val tcpPeers = mutableListOf<ReticulumTcpPeer>()
                val onlineIds = mutableSetOf<String>()
                val peers = runCatching {
                    JSONArray(intent.getStringExtra(ReticulumNodeService.EXTRA_TCP_STATUS).orEmpty())
                }.getOrElse { JSONArray() }
                for (index in 0 until peers.length()) {
                    val peer = peers.optJSONObject(index) ?: continue
                    val tcpPeer = ReticulumTcpPeer(peer.optString("host"), peer.optInt("port"))
                    tcpPeers += tcpPeer
                    if (peer.optBoolean("online")) onlineIds += tcpPeer.id
                }
                ReticulumNodeState.Running(
                    mode = mode,
                    tcpPeers = tcpPeers,
                    onlineTcpPeerIds = onlineIds,
                    sharedInstanceConnected = intent.getBooleanExtra(ReticulumNodeService.EXTRA_SHARED, false),
                )
            }
            ReticulumNodeService.STATE_FAILED -> ReticulumNodeState.Failed(
                intent.getStringExtra(ReticulumNodeService.EXTRA_MESSAGE) ?: "Reticulum failed to start.",
            )
            else -> ReticulumNodeState.Stopped
        }
    }
}
