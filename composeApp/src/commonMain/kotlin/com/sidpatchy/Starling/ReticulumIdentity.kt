package com.sidpatchy.Starling

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64

data class ReticulumIdentityInfo(val hash: String, val deliveryAddress: String)

sealed interface ReticulumIdentityState {
    data object Loading : ReticulumIdentityState
    data object Missing : ReticulumIdentityState
    data class Available(val identity: ReticulumIdentityInfo) : ReticulumIdentityState
    data class Failed(val message: String) : ReticulumIdentityState
}

/** Private-key operations stay out of chat persistence and UI state. */
fun interface ReticulumIdentityStore {
    suspend fun invoke(command: String): String
}

/** Platform document dialogs; cancellation returns null/false rather than an error. */
interface ReticulumIdentityFiles {
    suspend fun importFile(): ByteArray?
    suspend fun exportFile(bytes: ByteArray, suggestedName: String): Boolean
}

class ReticulumIdentityManager(
    private val store: ReticulumIdentityStore,
    private val files: ReticulumIdentityFiles,
    private val nodeController: ReticulumNodeController,
    private val onIdentityChanged: (ReticulumIdentityInfo?) -> Unit = {},
) {
    private val operations = Mutex()
    private val _state = MutableStateFlow<ReticulumIdentityState>(ReticulumIdentityState.Loading)
    val state: StateFlow<ReticulumIdentityState> = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    suspend fun refresh() = operation {
        try {
            update(store.invoke("IDENTITY_STATUS"))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _state.value = ReticulumIdentityState.Failed(error.message ?: "Could not load the identity.")
            onIdentityChanged(null)
            throw error
        }
        true
    }

    suspend fun create() = operation {
        requireStoppedNode()
        update(store.invoke("IDENTITY_CREATE"))
        true
    }

    suspend fun importIdentity() = operation {
        requireStoppedNode()
        val bytes = files.importFile() ?: return@operation false
        try {
            require(bytes.size == PRIVATE_IDENTITY_BYTES) { "Choose a standard 64-byte Reticulum private identity file." }
            requireStoppedNode()
            update(store.invoke("IDENTITY_IMPORT\t${Base64.encode(bytes)}"))
            true
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun exportIdentity() = operation {
        val identity = (_state.value as? ReticulumIdentityState.Available)?.identity
            ?: error("There is no valid identity to export.")
        val response = store.invoke("IDENTITY_EXPORT")
        checkIdentityError(response)
        val fields = response.split('\t')
        check(fields.size == 2 && fields[0] == "IDENTITY_EXPORT") { "Invalid identity export response." }
        val bytes = Base64.decode(fields[1])
        try {
            check(bytes.size == PRIVATE_IDENTITY_BYTES) { "The saved identity has an invalid size." }
            files.exportFile(bytes, "starling-${identity.hash}.rnsidentity")
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun delete() = operation {
        requireStoppedNode()
        update(store.invoke("IDENTITY_DELETE"))
        true
    }

    private suspend fun requireStoppedNode() {
        nodeController.refreshState()
        check(nodeController.state.value == ReticulumNodeState.Stopped) {
            "Disconnect the Reticulum node before creating, importing, or deleting an identity."
        }
    }

    private fun update(response: String) {
        val identity = parseReticulumIdentity(response)
        _state.value = identity?.let(ReticulumIdentityState::Available) ?: ReticulumIdentityState.Missing
        onIdentityChanged(identity)
    }

    private suspend fun operation(action: suspend () -> Boolean): Boolean = operations.withLock {
        _busy.value = true
        _error.value = null
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _error.value = error.message ?: "Could not complete the identity operation."
            false
        } finally {
            _busy.value = false
        }
    }
}

internal const val PRIVATE_IDENTITY_BYTES = 64

internal fun parseReticulumIdentity(response: String): ReticulumIdentityInfo? {
    checkIdentityError(response)
    if (response == "IDENTITY_MISSING") return null
    val fields = response.split('\t')
    check(fields.size == 3 && fields[0] == "MANAGED_IDENTITY" && fields.drop(1).all { field ->
        field.length == 32 && field.all { it in "0123456789abcdef" }
    }) { "Invalid identity status response." }
    return ReticulumIdentityInfo(fields[1], fields[2])
}

private fun checkIdentityError(response: String) {
    if (response.startsWith("ERROR\t")) {
        throw IllegalStateException(response.substringAfter('\t').decodeBridgeBase64().ifBlank { "Identity operation failed." })
    }
}
