package dev.starling

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Desktop node owner. RNS runs in a child Python process so it has an isolated
 * lifecycle and can use RNS's process-level signal handling. The pinned RNS
 * package is installed in a Starling-private venv on first explicit connect.
 */
class DesktopReticulumNodeController : ReticulumNodeController {
    private val _state = MutableStateFlow<ReticulumNodeState>(ReticulumNodeState.Stopped)
    override val state: StateFlow<ReticulumNodeState> = _state.asStateFlow()
    override val isSupported: Boolean = true
    override val supportsRNodeInterfaces: Boolean = true

    private val operations = Mutex()
    private val commandMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _bridgeEvents = MutableSharedFlow<String>(extraBufferCapacity = 128)
    val bridgeEvents: SharedFlow<String> = _bridgeEvents
    private var process: Process? = null
    private var processWriter: java.io.BufferedWriter? = null
    private var processReaderJob: kotlinx.coroutines.Job? = null
    private var activeConfig: ReticulumNodeConfig? = null

    override suspend fun start(config: ReticulumNodeConfig) = operations.withLock {
        if (config.mode == ReticulumRuntimeMode.EXISTING_SHARED_INSTANCE && !validRpcKey(config.existingInstanceRpcKeyHex.orEmpty())) {
            _state.value = ReticulumNodeState.Failed("Enter the existing local RNS instance's RPC key as at least 32 hexadecimal characters.")
            return@withLock
        }
        config.validationError()?.let {
            _state.value = ReticulumNodeState.Failed(it)
            return@withLock
        }

        if (process?.isAlive == true) {
            if (activeConfig == config) return@withLock
            _state.value = ReticulumNodeState.Failed("Disconnect the running node before changing its configuration.")
            return@withLock
        }

        try {
            withContext(Dispatchers.IO) { startProcess(config) }
        } catch (error: Exception) {
            destroyProcess()
            _state.value = ReticulumNodeState.Failed(error.message ?: "Could not start the desktop Reticulum runtime.")
        }
    }

    override suspend fun disconnect() = operations.withLock {
        withContext(Dispatchers.IO) {
            val running = process
            val writer = processWriter
            if (running != null && running.isAlive && writer != null) {
                runCatching {
                    writer.write("STOP\n")
                    writer.flush()
                }
                if (!running.waitFor(8, TimeUnit.SECONDS)) {
                    running.destroy()
                    if (!running.waitFor(2, TimeUnit.SECONDS)) running.destroyForcibly()
                }
            } else {
                running?.destroyForcibly()
            }
            processReaderJob?.cancel()
            processReaderJob = null
            processWriter = null
            process = null
            activeConfig = null
            _state.value = ReticulumNodeState.Stopped
        }
    }

    /** Called by the desktop window close handler to release the child process. */
    suspend fun close() {
        disconnect()
        scope.cancel()
    }

    private suspend fun startProcess(config: ReticulumNodeConfig) {
        val dataDirectory = appDataDirectory()
        Files.createDirectories(dataDirectory)
        val logFile = dataDirectory.resolve("starling-rns.log").toFile()
        _state.value = ReticulumNodeState.PreparingRuntime("Looking for a Python 3 interpreter…")
        val basePython = findPython(logFile)
        val packagedPython = System.getenv("STARLING_PYTHON_PACKAGED") == "1"
        val venvDirectory = dataDirectory.resolve("python-env")
        val venvPython = venvPythonPath(venvDirectory)
        val runtimePython: List<String>

        if (packagedPython) {
            runtimePython = basePython
            if (!hasPinnedRuntime(runtimePython, logFile)) {
                throw IllegalStateException("The packaged Python runtime is missing RNS 1.5.7 or LXMF 1.2.0.")
            }
        } else {
            if (!Files.isRegularFile(venvPython)) {
                _state.value = ReticulumNodeState.PreparingRuntime("Creating Starling’s private Python environment…")
                runChecked(basePython + listOf("-m", "venv", venvDirectory.toString()), logFile, "Could not create a private Python environment")
            }

            runtimePython = listOf(venvPython.toString())
            if (!hasPinnedRuntime(runtimePython, logFile)) {
                _state.value = ReticulumNodeState.PreparingRuntime("Installing reference RNS and LXMF in Starling’s private Python environment (first run only)…")
                runChecked(
                    runtimePython + listOf(
                        "-m", "pip", "install", "--disable-pip-version-check", "--no-input", "rns==1.5.7", "lxmf==1.2.0",
                    ),
                    logFile,
                    "Could not install RNS 1.5.7 and LXMF 1.2.0. Check the Starling log and your internet connection",
                    timeoutSeconds = 600,
                )
            }
        }

        val bridgeDirectory = dataDirectory.resolve("python-bridge")
        Files.createDirectories(bridgeDirectory)
        extractBridgeResource("python/starling_rns.py", bridgeDirectory.resolve("starling_rns.py"))
        extractBridgeResource("python/starling_rrc.py", bridgeDirectory.resolve("starling_rrc.py"))
        extractBridgeResource("python/starling_lxmf.py", bridgeDirectory.resolve("starling_lxmf.py"))
        extractBridgeResource("python/starling_rns_service.py", bridgeDirectory.resolve("starling_rns_service.py"))

        _state.value = ReticulumNodeState.Starting(config.mode)
        val child = ProcessBuilder(
            runtimePython + listOf(
                "-u", bridgeDirectory.resolve("starling_rns_service.py").toString(),
                dataDirectory.resolve("reticulum").toString(),
            ),
        ).redirectError(ProcessBuilder.Redirect.appendTo(logFile)).start()
        val writer = child.outputStream.bufferedWriter(Charsets.UTF_8)
        val reader = child.inputStream.bufferedReader(Charsets.UTF_8)

        process = child
        processWriter = writer
        activeConfig = config
        writer.write(startCommand(config))
        writer.newLine()
        writer.flush()

        val firstLine = reader.readLine()
        if (firstLine == null) {
            val exitCode = if (child.isAlive) "still running" else "exit code ${child.exitValue()}"
            throw IllegalStateException("The RNS bridge exited before reporting status ($exitCode). See ${logFile.absolutePath}.")
        }
        if (!applyServiceLine(firstLine, config)) {
            throw IllegalStateException("The RNS bridge returned an invalid startup response. See ${logFile.absolutePath}.")
        }
        if (_state.value is ReticulumNodeState.Failed) {
            val failure = (_state.value as ReticulumNodeState.Failed).message
            throw IllegalStateException("$failure (log: ${logFile.absolutePath})")
        }

        processReaderJob = scope.launch {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("EVENT\t")) {
                        _bridgeEvents.emit(line.removePrefix("EVENT\t"))
                        continue
                    }
                    applyServiceLine(line, config)
                    if (line == "STOPPED") break
                }
                if (_state.value !is ReticulumNodeState.Failed && _state.value !is ReticulumNodeState.Stopped) {
                    _state.value = ReticulumNodeState.Failed("The RNS process exited. See ${logFile.absolutePath}.")
                }
            } catch (error: Exception) {
                if (_state.value !is ReticulumNodeState.Stopped) {
                    _state.value = ReticulumNodeState.Failed(error.message ?: "Lost the RNS process connection.")
                }
            }
        }
    }

    suspend fun sendBridgeCommand(command: String) = commandMutex.withLock {
        val writer = processWriter
        if (process?.isAlive != true || writer == null) {
            throw IllegalStateException("The desktop Reticulum node is not running.")
        }
        writer.write(command)
        writer.newLine()
        writer.flush()
    }

    private fun applyServiceLine(line: String, requestedConfig: ReticulumNodeConfig): Boolean {
        val fields = line.split('\t')
        when {
            fields.firstOrNull() == "ERROR" -> {
                val message = fields.getOrNull(1)?.let(::decodeBase64).orEmpty().ifBlank { "The RNS runtime failed." }
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
                val onlineIds = fields.getOrNull(3)?.let(::decodeBase64).orEmpty()
                    .split(';')
                    .mapNotNull { peerStatus ->
                        val statusFields = peerStatus.split(',')
                        val host = statusFields.getOrNull(0)?.let(::decodeBase64).orEmpty()
                        val port = statusFields.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                        if (statusFields.getOrNull(2) == "1") "${host.lowercase()}:$port" else null
                    }
                    .toSet()
                val onlineRNodePorts = fields.getOrNull(6)?.let(::decodeBase64).orEmpty()
                    .split(';')
                    .mapNotNull { radioStatus ->
                        val statusFields = radioStatus.split(',')
                        val port = statusFields.getOrNull(0)?.let(::decodeBase64).orEmpty()
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
            "START",
            config.mode.name,
            encodeBase64(config.existingInstanceRpcKeyHex.orEmpty()),
            "4",
            config.tcpPeers.size.toString(),
            config.rnodeInterfaces.size.toString(),
        )
        config.tcpPeers.forEach { peer ->
            fields += encodeBase64(peer.host.trim())
            fields += peer.port.toString()
            fields += encodeBase64(peer.ifacNetworkName.orEmpty())
            fields += encodeBase64(peer.ifacPassphrase.orEmpty())
        }
        config.rnodeInterfaces.forEach { radio ->
            fields += encodeBase64(radio.port.trim())
            fields += radio.frequencyHz.toString()
            fields += radio.bandwidthHz.toString()
            fields += radio.txPowerDbm.toString()
            fields += radio.spreadingFactor.toString()
            fields += radio.codingRate.toString()
        }
        return fields.joinToString("\t")
    }

    private fun findPython(logFile: File): List<String> {
        val configured = System.getProperty("starling.python") ?: System.getenv("STARLING_PYTHON")
        val candidates = buildList {
            if (!configured.isNullOrBlank()) add(listOf(configured))
            if (isWindows()) add(listOf("py", "-3"))
            add(if (isWindows()) listOf("python") else listOf("python3"))
            add(listOf("python"))
        }.distinct()

        for (candidate in candidates) {
            try {
                val check = ProcessBuilder(candidate + "--version").redirectErrorStream(true).start()
                val text = check.inputStream.bufferedReader().readText().trim()
                if (!check.waitFor(10, TimeUnit.SECONDS)) {
                    check.destroyForcibly()
                    continue
                }
                if (check.exitValue() == 0 && isSupportedPythonVersion(text)) return candidate
            } catch (_: Exception) {
                // Try the next conventional interpreter name.
            }
        }
        logFile.parentFile?.mkdirs()
        throw IllegalStateException("Python 3.8 or newer is required for the desktop RNS runtime. Install Python, then retry (or set STARLING_PYTHON).")
    }

    private fun runChecked(
        command: List<String>,
        logFile: File,
        failureMessage: String,
        timeoutSeconds: Long = 120,
    ) {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(logFile)).start()
        } catch (error: Exception) {
            throw IllegalStateException("$failureMessage: ${error.message ?: error.javaClass.simpleName}", error)
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("$failureMessage (timed out). See ${logFile.absolutePath}.")
        }
        if (process.exitValue() != 0) {
            throw IllegalStateException("$failureMessage. See ${logFile.absolutePath}.")
        }
    }

    private fun hasPinnedRuntime(python: List<String>, logFile: File): Boolean {
        val check = try {
            ProcessBuilder(
                python + listOf(
                    "-c",
                    "import importlib.metadata as m; import RNS, LXMF; assert m.version('rns') == '1.5.7' and m.version('lxmf') == '1.2.0'",
                ),
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(logFile)).start()
        } catch (_: Exception) {
            return false
        }
        return check.waitFor(30, TimeUnit.SECONDS) && check.exitValue() == 0
    }

    private fun extractBridgeResource(resource: String, destination: Path) {
        val input = javaClass.classLoader.getResourceAsStream(resource)
            ?: throw IllegalStateException("Desktop RNS bridge resource '$resource' was not packaged.")
        input.use { Files.copy(it, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun venvPythonPath(directory: Path): Path = if (isWindows()) {
        directory.resolve("Scripts").resolve("python.exe")
    } else {
        directory.resolve("bin").resolve("python")
    }

    private fun appDataDirectory(): Path {
        val os = System.getProperty("os.name").lowercase()
        val home = Paths.get(System.getProperty("user.home"))
        return when {
            os.contains("win") -> Paths.get(System.getenv("APPDATA") ?: home.resolve("AppData/Roaming").toString(), "Starling")
            os.contains("mac") -> home.resolve("Library/Application Support/Starling")
            else -> Paths.get(System.getenv("XDG_DATA_HOME") ?: home.resolve(".local/share").toString()).resolve("Starling")
        }
    }

    private fun destroyProcess() {
        process?.let { child ->
            if (child.isAlive) child.destroyForcibly()
        }
        process = null
        processWriter = null
        activeConfig = null
    }

    private fun validRpcKey(key: String): Boolean =
        key.length >= 32 && key.length % 2 == 0 && key.all { it.digitToIntOrNull(16) != null }

    private fun encodeBase64(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeBase64(value: String): String = runCatching {
        String(Base64.getDecoder().decode(value), Charsets.UTF_8)
    }.getOrDefault("")

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

    private fun isSupportedPythonVersion(version: String): Boolean {
        val match = Regex("Python (\\d+)\\.(\\d+)").find(version) ?: return false
        val major = match.groupValues[1].toIntOrNull() ?: return false
        val minor = match.groupValues[2].toIntOrNull() ?: return false
        return major > 3 || (major == 3 && minor >= 8)
    }
}
