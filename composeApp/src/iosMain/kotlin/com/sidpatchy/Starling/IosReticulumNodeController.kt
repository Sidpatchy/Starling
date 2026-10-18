package com.sidpatchy.Starling

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/** Synchronous Swift/CPython seam. Python calls are serialized by the iOS runtime. */
interface IosPythonRuntime {
    val dataDirectory: String
    fun invoke(command: String): String
    /** Start a CoreBluetooth UART bridge; an empty name accepts the first RNode. */
    fun startRNodeBleProxy(deviceName: String): String?
    fun stopRNodeBleProxy()
}

/** RNS/LXMF node lifecycle backed by the bundled reference Python modules. */
class IosReticulumNodeController(
    private val runtime: IosPythonRuntime,
) : ReticulumNodeController {
    private val operations = Mutex()
    private val commands = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<ReticulumNodeState>(ReticulumNodeState.Stopped)
    private val _bridgeEvents = MutableSharedFlow<String>(extraBufferCapacity = 128)
    override val state: StateFlow<ReticulumNodeState> = _state.asStateFlow()
    override val isSupported: Boolean = true
    override val supportsRNodeInterfaces: Boolean = true
    val bridgeEvents: SharedFlow<String> = _bridgeEvents.asSharedFlow()

    private var activeConfig: ReticulumNodeConfig? = null
    private var pollJob: Job? = null
    private var runtimeUsed = false
    private var bleProxyStarted = false

    override suspend fun start(config: ReticulumNodeConfig) = operations.withLock {
        config.validationError()?.let {
            _state.value = ReticulumNodeState.Failed(it)
            return@withLock
        }
        val bleInterfaces = config.rnodeInterfaces.filter { it.port.startsWith(BLE_PREFIX, ignoreCase = true) }
        if (bleInterfaces.size > 1) {
            _state.value = ReticulumNodeState.Failed("Only one BLE RNode can be connected on iOS at a time.")
            return@withLock
        }
        val unsupportedInterface = config.rnodeInterfaces.firstOrNull { radio ->
            !radio.port.startsWith(BLE_PREFIX, ignoreCase = true) &&
                !radio.port.startsWith(TCP_PREFIX, ignoreCase = true)
        }
        if (unsupportedInterface != null) {
            _state.value = ReticulumNodeState.Failed(
                "iOS supports RNodes over BLE or TCP/Wi-Fi; direct USB/serial ports are not available.",
            )
            return@withLock
        }
        val invalidTcpRNode = config.rnodeInterfaces.firstOrNull { radio ->
            if (!radio.port.startsWith(TCP_PREFIX, ignoreCase = true)) return@firstOrNull false
            val host = radio.port.drop(TCP_PREFIX.length)
            host.isBlank() || host.any { !(it.isLetterOrDigit() || it in "._:-") }
        }
        if (invalidTcpRNode != null) {
            _state.value = ReticulumNodeState.Failed("Use tcp:// followed by an RNode TCP/Wi-Fi host name or IP address.")
            return@withLock
        }
        if (activeConfig != null) {
            if (activeConfig == config && _state.value is ReticulumNodeState.Running) return@withLock
            _state.value = ReticulumNodeState.Failed("Disconnect the running Reticulum node before changing its configuration.")
            return@withLock
        }

        _state.value = ReticulumNodeState.Starting(config.mode)
        val bleInterface = bleInterfaces.singleOrNull()
        if (bleInterface != null) {
            val error = runCatching {
                withContext(Dispatchers.Default) {
                    runtime.startRNodeBleProxy(bleInterface.port.removePrefixIgnoreCase(BLE_PREFIX).trim())
                }
            }.getOrElse { it.message ?: "Could not start the iOS BLE RNode bridge." }
            if (error != null) {
                runCatching { withContext(Dispatchers.Default) { runtime.stopRNodeBleProxy() } }
                _state.value = ReticulumNodeState.Failed(error)
                return@withLock
            }
            bleProxyStarted = true
        }
        runtimeUsed = true
        val response = try {
            withContext(Dispatchers.Default) { runtime.invoke(startCommand(config)) }
        } catch (error: Exception) {
            _state.value = ReticulumNodeState.Failed(error.message ?: "Could not start the iOS Reticulum runtime.")
            runCatching { withContext(Dispatchers.Default) { runtime.invoke("STOP") } }
            runtimeUsed = false
            stopBleProxy()
            return@withLock
        }
        val receivedStatus = consumeResponse(response, config)
        if (!receivedStatus || _state.value is ReticulumNodeState.Failed) {
            if (_state.value !is ReticulumNodeState.Failed) {
                _state.value = ReticulumNodeState.Failed("The iOS Python runtime did not report startup status.")
            }
            runCatching { withContext(Dispatchers.Default) { runtime.invoke("STOP") } }
            runtimeUsed = false
            stopBleProxy()
            return@withLock
        }

        activeConfig = config
        pollJob?.cancel()
        pollJob = scope.launch { pollRuntime(config) }
    }

    override suspend fun disconnect() = operations.withLock {
        if (!runtimeUsed) {
            _state.value = ReticulumNodeState.Stopped
            return@withLock
        }
        pollJob?.cancel()
        pollJob?.join()
        pollJob = null
        runCatching {
            withContext(Dispatchers.Default) {
                commands.withLock { runtime.invoke("STOP") }
            }
        }
        stopBleProxy()
        runtimeUsed = false
        activeConfig = null
        _state.value = ReticulumNodeState.Stopped
    }

    override suspend fun refreshState() {
        val config = activeConfig ?: return
        val response = runCatching {
            withContext(Dispatchers.Default) { runtime.invoke("STATUS") }
        }.getOrNull() ?: return
        consumeResponse(response, config)
    }

    suspend fun sendBridgeCommand(command: String) = commands.withLock {
        if (activeConfig == null || _state.value !is ReticulumNodeState.Running) {
            throw IllegalStateException("The iOS Reticulum node is not running.")
        }
        val response = withContext(Dispatchers.Default) { runtime.invoke(command) }
        consumeResponse(response, activeConfig ?: return@withLock)
    }

    suspend fun close() {
        disconnect()
        scope.cancel()
    }

    private suspend fun pollRuntime(config: ReticulumNodeConfig) {
        val clock = TimeSource.Monotonic.markNow()
        var nextStatusAt = 0L
        while (scope.isActive && activeConfig == config) {
            val eventResponse = runCatching {
                withContext(Dispatchers.Default) {
                    commands.withLock { runtime.invoke("POLL") }
                }
            }.getOrNull()
            if (eventResponse != null) consumeResponse(eventResponse, config)

            val now = clock.elapsedNow().inWholeMilliseconds
            if (now >= nextStatusAt) {
                val statusResponse = runCatching {
                    withContext(Dispatchers.Default) {
                        commands.withLock { runtime.invoke("STATUS") }
                    }
                }.getOrNull()
                if (statusResponse != null) consumeResponse(statusResponse, config)
                nextStatusAt = now + 2_000
            }
            delay(200)
        }
    }

    private fun consumeResponse(response: String, requestedConfig: ReticulumNodeConfig): Boolean {
        var receivedStatus = false
        response.lineSequence().filter(String::isNotBlank).forEach { line ->
            if (line.startsWith("EVENT\t")) {
                _bridgeEvents.tryEmit(line.removePrefix("EVENT\t"))
            } else if (applyServiceLine(line, requestedConfig)) {
                receivedStatus = true
            }
        }
        return receivedStatus
    }

    private fun applyServiceLine(line: String, requestedConfig: ReticulumNodeConfig): Boolean {
        val state = parseReticulumNodeBridgeState(
            line, requestedConfig, defaultFailureMessage = "The iOS Reticulum runtime failed.",
        ) ?: return false
        _state.value = state
        return true
    }

    private fun startCommand(config: ReticulumNodeConfig): String {
        val fields = mutableListOf(
            "IOS_START",
            encodeBridgeBase64("${runtime.dataDirectory}/reticulum"),
            config.mode.name,
            encodeBridgeBase64(config.existingInstanceRpcKeyHex.orEmpty()),
            "4",
            config.tcpPeers.size.toString(),
            config.rnodeInterfaces.size.toString(),
        )
        config.tcpPeers.forEach { peer ->
            fields += encodeBridgeBase64(peer.host.trim())
            fields += peer.port.toString()
            fields += encodeBridgeBase64(peer.ifacNetworkName.orEmpty())
            fields += encodeBridgeBase64(peer.ifacPassphrase.orEmpty())
        }
        config.rnodeInterfaces.forEach { radio ->
            fields += encodeBridgeBase64(radio.port)
            fields += radio.frequencyHz.toString()
            fields += radio.bandwidthHz.toString()
            fields += radio.txPowerDbm.toString()
            fields += radio.spreadingFactor.toString()
            fields += radio.codingRate.toString()
        }
        return fields.joinToString("\t")
    }

    private suspend fun stopBleProxy() {
        if (!bleProxyStarted) return
        bleProxyStarted = false
        runCatching { withContext(Dispatchers.Default) { runtime.stopRNodeBleProxy() } }
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) drop(prefix.length) else this

    private companion object {
        const val BLE_PREFIX = "ble://"
        const val TCP_PREFIX = "tcp://"
    }
}
