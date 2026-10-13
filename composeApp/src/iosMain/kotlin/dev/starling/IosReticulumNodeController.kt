@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.starling

import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.pointed
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.convert
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.posix.AF_UNSPEC
import platform.posix.EINPROGRESS
import platform.posix.ETIMEDOUT
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.IPPROTO_TCP
import platform.posix.O_NONBLOCK
import platform.posix.POLLOUT
import platform.posix.SO_ERROR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.addrinfo
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.freeaddrinfo
import platform.posix.gai_strerror
import platform.posix.getaddrinfo
import platform.posix.getsockopt
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.socket
import platform.posix.strerror

private const val TCP_CONNECT_TIMEOUT_MILLIS = 8_000

/** iOS currently verifies outbound TCP reachability; it does not implement the RNS wire protocol. */
class IosReticulumNodeController : ReticulumNodeController {
    private val operations = Mutex()
    private val _state = MutableStateFlow<ReticulumNodeState>(ReticulumNodeState.Stopped)
    override val state: StateFlow<ReticulumNodeState> = _state
    override val isSupported: Boolean = true
    override val supportsReticulumProtocol: Boolean = false

    private var openSockets: Map<String, Int> = emptyMap()

    override suspend fun start(config: ReticulumNodeConfig) = operations.withLock {
        config.validationError()?.let {
            _state.value = ReticulumNodeState.Failed(it)
            return@withLock
        }
        if (config.mode != ReticulumRuntimeMode.EMBEDDED) {
            _state.value = ReticulumNodeState.Failed("Shared RNS instances are not supported on iOS.")
            return@withLock
        }
        if (config.rnodeInterfaces.isNotEmpty()) {
            _state.value = ReticulumNodeState.Failed("RNode interfaces are not supported on iOS yet.")
            return@withLock
        }
        if (config.tcpPeers.any { !it.ifacNetworkName.isNullOrBlank() || !it.ifacPassphrase.isNullOrBlank() }) {
            _state.value = ReticulumNodeState.Failed("IFAC and Reticulum traffic are not supported by the iOS TCP check yet.")
            return@withLock
        }
        if (openSockets.isNotEmpty()) {
            _state.value = ReticulumNodeState.Failed("Disconnect the existing TCP check before starting another.")
            return@withLock
        }

        _state.value = ReticulumNodeState.Starting(ReticulumRuntimeMode.EMBEDDED)
        val connected = mutableMapOf<String, Int>()
        val failures = mutableListOf<String>()
        withContext(Dispatchers.Default) {
            config.tcpPeers.forEach { peer ->
                runCatching { openTcpSocket(peer) }
                    .onSuccess { connected[peer.id] = it }
                    .onFailure { error ->
                        failures += "${peer.host}:${peer.port}: ${error.message ?: "TCP connection failed"}"
                    }
            }
        }

        if (connected.isEmpty()) {
            _state.value = ReticulumNodeState.Failed(
                failures.firstOrNull() ?: "Could not connect to the configured TCP peer.",
            )
        } else {
            openSockets = connected
            _state.value = ReticulumNodeState.Running(
                mode = ReticulumRuntimeMode.EMBEDDED,
                tcpPeers = config.tcpPeers,
                onlineTcpPeerIds = connected.keys,
            )
        }
    }

    override suspend fun disconnect() = operations.withLock {
        val sockets = openSockets.values.toList()
        openSockets = emptyMap()
        withContext(Dispatchers.Default) {
            sockets.forEach { close(it) }
        }
        _state.value = ReticulumNodeState.Stopped
    }

    private fun openTcpSocket(peer: ReticulumTcpPeer): Int = memScoped {
        val hints = alloc<addrinfo>()
        hints.ai_flags = 0
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_STREAM
        hints.ai_protocol = IPPROTO_TCP
        hints.ai_addrlen = 0u
        hints.ai_addr = null
        hints.ai_canonname = null
        hints.ai_next = null

        val addresses = alloc<CPointerVar<addrinfo>>()
        addresses.value = null
        val host = peer.host.cstr
        val service = peer.port.toString().cstr
        val lookupResult = getaddrinfo(host.ptr, service.ptr, hints.ptr, addresses.ptr)
        if (lookupResult != 0) {
            val reason = gai_strerror(lookupResult)?.toKString() ?: "name lookup failed"
            throw IllegalStateException("Could not resolve ${peer.host}: $reason")
        }

        val firstAddress = addresses.value
            ?: throw IllegalStateException("No TCP address found for ${peer.host}.")
        var address: kotlinx.cinterop.CPointer<addrinfo>? = firstAddress
        var lastError = "no address accepted the connection"
        try {
            while (address != null) {
                val target = address.pointed
                val descriptor = socket(target.ai_family, target.ai_socktype, target.ai_protocol)
                if (descriptor >= 0) {
                    val flags = fcntl(descriptor, F_GETFL)
                    if (flags >= 0 && fcntl(descriptor, F_SETFL, flags or O_NONBLOCK) == 0) {
                        val result = connect(descriptor, target.ai_addr, target.ai_addrlen)
                        val connectionError = when {
                            result == 0 -> 0
                            errno == EINPROGRESS -> awaitConnect(descriptor)
                            else -> errno
                        }
                        if (connectionError == 0) {
                            fcntl(descriptor, F_SETFL, flags)
                            return@memScoped descriptor
                        }
                        lastError = strerror(connectionError)?.toKString() ?: "socket error $connectionError"
                    } else {
                        val socketError = errno
                        lastError = strerror(socketError)?.toKString() ?: "socket error $socketError"
                    }
                    close(descriptor)
                } else {
                    val socketError = errno
                    lastError = strerror(socketError)?.toKString() ?: "socket error $socketError"
                }
                address = target.ai_next
            }
        } finally {
            freeaddrinfo(firstAddress)
        }
        throw IllegalStateException("Could not connect to ${peer.host}:${peer.port}: $lastError")
    }

    private fun awaitConnect(descriptor: Int): Int = memScoped {
        val event = alloc<pollfd>()
        event.fd = descriptor
        event.events = POLLOUT.toShort()
        event.revents = 0
        val pollResult = poll(event.ptr, 1u, TCP_CONNECT_TIMEOUT_MILLIS)
        if (pollResult == 0) return@memScoped ETIMEDOUT
        if (pollResult < 0) return@memScoped errno

        val socketError = alloc<IntVar>()
        val errorLength = alloc<UIntVar>()
        errorLength.value = sizeOf<IntVar>().convert()
        if (getsockopt(descriptor, SOL_SOCKET, SO_ERROR, socketError.ptr, errorLength.ptr) == 0) {
            socketError.value
        } else {
            errno
        }
    }
}
