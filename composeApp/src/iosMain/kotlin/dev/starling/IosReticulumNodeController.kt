package dev.starling

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
    val bridgeEvents: SharedFlow<String> = _bridgeEvents.asSharedFlow()

    private var activeConfig: ReticulumNodeConfig? = null
    private var pollJob: Job? = null
    private var runtimeUsed = false

    override suspend fun start(config: ReticulumNodeConfig) = operations.withLock {
        if (config.mode == ReticulumRuntimeMode.EXISTING_SHARED_INSTANCE && !validRpcKey(config.existingInstanceRpcKeyHex.orEmpty())) {
            _state.value = ReticulumNodeState.Failed("Enter the existing local RNS instance's RPC key as at least 32 hexadecimal characters.")
            return@withLock
        }
        config.validationError()?.let {
            _state.value = ReticulumNodeState.Failed(it)
            return@withLock
        }
        if (config.rnodeInterfaces.isNotEmpty()) {
            _state.value = ReticulumNodeState.Failed("Direct RNode interfaces are not available on iOS yet.")
            return@withLock
        }
        if (activeConfig != null) {
            if (activeConfig == config && _state.value is ReticulumNodeState.Running) return@withLock
            _state.value = ReticulumNodeState.Failed("Disconnect the running Reticulum node before changing its configuration.")
            return@withLock
        }

        _state.value = ReticulumNodeState.Starting(config.mode)
        runtimeUsed = true
        val response = try {
            withContext(Dispatchers.Default) { runtime.invoke(startCommand(config)) }
        } catch (error: Exception) {
            _state.value = ReticulumNodeState.Failed(error.message ?: "Could not start the iOS Reticulum runtime.")
            return@withLock
        }
        val receivedStatus = consumeResponse(response, config)
        if (!receivedStatus || _state.value is ReticulumNodeState.Failed) {
            if (_state.value !is ReticulumNodeState.Failed) {
                _state.value = ReticulumNodeState.Failed("The iOS Python runtime did not report startup status.")
            }
            runCatching { withContext(Dispatchers.Default) { runtime.invoke("STOP") } }
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
        val fields = line.split('\t')
        when {
            fields.firstOrNull() == "ERROR" -> {
                val message = fields.getOrNull(1)?.decodeIosBase64().orEmpty().ifBlank { "The iOS Reticulum runtime failed." }
                _state.value = ReticulumNodeState.Failed(message)
                return true
            }
            fields.firstOrNull() == "STOPPED" -> {
                _state.value = ReticulumNodeState.Stopped
                return true
            }
            (fields.firstOrNull() == "READY" || fields.firstOrNull() == "STATUS") && fields.getOrNull(1) == "RUNNING" -> {
                val mode = runCatching { ReticulumRuntimeMode.valueOf(fields.getOrNull(2).orEmpty()) }
                    .getOrDefault(requestedConfig.mode)
                val onlineIds = fields.getOrNull(3)?.decodeIosBase64().orEmpty()
                    .split(';')
                    .mapNotNull { peerStatus ->
                        val statusFields = peerStatus.split(',')
                        val host = statusFields.getOrNull(0)?.decodeIosBase64().orEmpty()
                        val port = statusFields.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                        if (statusFields.getOrNull(2) == "1") "${host.lowercase()}:$port" else null
                    }
                    .toSet()
                val onlineRNodePorts = fields.getOrNull(6)?.decodeIosBase64().orEmpty()
                    .split(';')
                    .mapNotNull { radioStatus ->
                        val statusFields = radioStatus.split(',')
                        val port = statusFields.getOrNull(0)?.decodeIosBase64().orEmpty()
                        port.takeIf { it.isNotBlank() && statusFields.getOrNull(1) == "1" }?.lowercase()
                    }
                    .toSet()
                _state.value = ReticulumNodeState.Running(
                    mode = mode,
                    tcpPeers = requestedConfig.tcpPeers,
                    onlineTcpPeerIds = onlineIds,
                    sharedInstanceConnected = fields.getOrNull(5) == "1",
                    rnodeInterfaces = requestedConfig.rnodeInterfaces,
                    onlineRNodePorts = onlineRNodePorts,
                )
                return true
            }
            fields.firstOrNull() == "READY" && fields.getOrNull(1) == "STOPPED" -> {
                _state.value = ReticulumNodeState.Failed("Reticulum started without a running shared instance.")
                return true
            }
        }
        return false
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
        return fields.joinToString("\t")
    }

    private fun String.decodeIosBase64(): String {
        if (isEmpty()) return ""
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val output = ByteArray(length * 3 / 4)
        var outputIndex = 0
        var buffer = 0
        var bits = 0
        for (character in this) {
            if (character == '=') break
            val value = alphabet.indexOf(character)
            if (value < 0) continue
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                if (outputIndex < output.size) output[outputIndex++] = ((buffer shr bits) and 0xff).toByte()
            }
        }
        return output.copyOf(outputIndex).decodeToString(throwOnInvalidSequence = false)
    }

    private fun validRpcKey(key: String): Boolean =
        key.length >= 32 && key.length % 2 == 0 && key.all { it.digitToIntOrNull(16) != null }
}
