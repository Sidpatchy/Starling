package com.sidpatchy.Starling

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** Starts and observes the isolated Android RNS service without blocking Compose. */
class AndroidReticulumNodeController(
    context: Context,
    private val requestBluetoothPermissions: suspend () -> Boolean = { false },
) : ReticulumNodeController {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<ReticulumNodeState>(ReticulumNodeState.Stopped)
    override val state: StateFlow<ReticulumNodeState> = _state.asStateFlow()
    override val isSupported: Boolean = true
    override val supportsRNodeInterfaces: Boolean = true
    private var pendingStateQuery: CompletableDeferred<ReticulumNodeState>? = null
    private var watchingServiceProcess = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ReticulumNodeService.ACTION_STATE) return
            val state = stateFromIntent(intent)
            _state.value = state
            pendingStateQuery?.complete(state)
            if (state is ReticulumNodeState.Starting || state is ReticulumNodeState.Running) {
                watchServiceProcess()
            } else {
                unwatchServiceProcess()
            }
        }
    }

    /**
     * State only arrives as broadcasts from the service process, so a process
     * that dies without publishing a final state would leave the last one
     * showing forever. The binding exists only to observe that death.
     */
    private val serviceProcessWatcher = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit

        override fun onServiceDisconnected(name: ComponentName?) {
            if (!watchingServiceProcess) return
            unwatchServiceProcess()
            val lastState = _state.value
            if (lastState !is ReticulumNodeState.Starting && lastState !is ReticulumNodeState.Running) return
            // Ask for the real state instead of guessing: a fresh service
            // instance reports stopped, and a restarted node reports itself.
            try {
                appContext.startService(Intent(appContext, ReticulumNodeService::class.java).apply {
                    action = ReticulumNodeService.ACTION_NODE_STATUS
                })
            } catch (_: Exception) {
                _state.value = ReticulumNodeState.Stopped
            }
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
        val bleInterfaces = config.rnodeInterfaces.filter { it.port.startsWith(BLE_PREFIX, ignoreCase = true) }
        if (bleInterfaces.size > 1) {
            _state.value = ReticulumNodeState.Failed(
                "Only one BLE RNode can be connected on Android at a time.",
            )
            return
        }

        val unsupportedInterface = config.rnodeInterfaces.firstOrNull { radio ->
            !radio.port.startsWith(BLE_PREFIX, ignoreCase = true) &&
                !radio.port.startsWith(TCP_PREFIX, ignoreCase = true)
        }
        if (unsupportedInterface != null) {
            _state.value = ReticulumNodeState.Failed(
                "Android supports RNodes over BLE or TCP/Wi-Fi; direct USB/serial ports are not available.",
            )
            return
        }
        val invalidTcpRNode = config.rnodeInterfaces.firstOrNull { radio ->
            if (!radio.port.startsWith(TCP_PREFIX, ignoreCase = true)) return@firstOrNull false
            val host = radio.port.drop(TCP_PREFIX.length)
            host.isBlank() || host.any { !(it.isLetterOrDigit() || it in "._:-") }
        }
        if (invalidTcpRNode != null) {
            _state.value = ReticulumNodeState.Failed("Use tcp:// followed by an RNode TCP/Wi-Fi host name or IP address.")
            return
        }
        if (config.rnodeInterfaces.any { it.bandwidthHz > ANDROID_MAX_RNODE_BANDWIDTH_HZ }) {
            _state.value = ReticulumNodeState.Failed(
                "The Android RNS RNode adapter supports radio bandwidths up to 500 kHz.",
            )
            return
        }

        config.validationError()?.let {
            _state.value = ReticulumNodeState.Failed(it)
            return
        }

        if (!appContext.noBackupFilesDir.resolve("reticulum/starling_identity").isFile) {
            _state.value = ReticulumNodeState.Failed("Create or import an identity on the Identity page before starting the node.")
            return
        }

        if (bleInterfaces.isNotEmpty() && !requestBluetoothPermissions()) {
            _state.value = ReticulumNodeState.Failed(
                "Bluetooth permission is required to connect to a BLE RNode. Grant it and try again.",
            )
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
        val radiosJson = JSONArray()
        config.rnodeInterfaces.forEach { radio ->
            radiosJson.put(
                JSONObject()
                    .put("port", radio.port.trim())
                    .put("frequency_hz", radio.frequencyHz)
                    .put("bandwidth_hz", radio.bandwidthHz)
                    .put("tx_power_dbm", radio.txPowerDbm)
                    .put("spreading_factor", radio.spreadingFactor)
                    .put("coding_rate", radio.codingRate),
            )
        }
        val intent = Intent(appContext, ReticulumNodeService::class.java).apply {
            action = ReticulumNodeService.ACTION_START
            putExtra(ReticulumNodeService.EXTRA_MODE, config.mode.name)
            putExtra(ReticulumNodeService.EXTRA_TCP_PEERS, peersJson.toString())
            putExtra(ReticulumNodeService.EXTRA_RNODE_INTERFACES, radiosJson.toString())
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
        unwatchServiceProcess()
    }

    private fun watchServiceProcess() {
        if (watchingServiceProcess) return
        watchingServiceProcess = true
        // No BIND_AUTO_CREATE: the binding must neither start the service nor
        // keep it alive after it stops itself.
        val bound = runCatching {
            appContext.bindService(Intent(appContext, ReticulumNodeService::class.java), serviceProcessWatcher, 0)
        }.getOrDefault(false)
        if (!bound) unwatchServiceProcess()
    }

    private fun unwatchServiceProcess() {
        if (!watchingServiceProcess) return
        watchingServiceProcess = false
        runCatching { appContext.unbindService(serviceProcessWatcher) }
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
                val rnodeInterfaces = mutableListOf<ReticulumRNodeInterface>()
                val onlineRNodePorts = mutableSetOf<String>()
                val radios = runCatching {
                    JSONArray(intent.getStringExtra(ReticulumNodeService.EXTRA_RNODE_STATUS).orEmpty())
                }.getOrElse { JSONArray() }
                for (index in 0 until radios.length()) {
                    val radio = radios.optJSONObject(index) ?: continue
                    val port = radio.optString("port").takeIf(String::isNotBlank) ?: continue
                    rnodeInterfaces += ReticulumRNodeInterface(
                        port = port,
                        frequencyHz = radio.optLong("frequency_hz"),
                        bandwidthHz = radio.optInt("bandwidth_hz"),
                        txPowerDbm = radio.optInt("tx_power_dbm"),
                        spreadingFactor = radio.optInt("spreading_factor"),
                        codingRate = radio.optInt("coding_rate"),
                    )
                    if (radio.optBoolean("online")) onlineRNodePorts += port.lowercase()
                }
                ReticulumNodeState.Running(
                    mode = mode,
                    tcpPeers = tcpPeers,
                    onlineTcpPeerIds = onlineIds,
                    sharedInstanceConnected = intent.getBooleanExtra(ReticulumNodeService.EXTRA_SHARED, false),
                    rnodeInterfaces = rnodeInterfaces,
                    onlineRNodePorts = onlineRNodePorts,
                )
            }
            ReticulumNodeService.STATE_FAILED -> ReticulumNodeState.Failed(
                intent.getStringExtra(ReticulumNodeService.EXTRA_MESSAGE) ?: "Reticulum failed to start.",
            )
            else -> ReticulumNodeState.Stopped
        }
    }

    private companion object {
        const val BLE_PREFIX = "ble://"
        const val TCP_PREFIX = "tcp://"
        const val ANDROID_MAX_RNODE_BANDWIDTH_HZ = 500_000
    }
}
