package dev.starling

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Swift-owned document pickers keep UIKit presentation outside shared Compose code. */
interface IosIdentityDocuments {
    fun importIdentity(completion: (ByteArray?, String?) -> Unit)
    /** A non-null saved marker indicates success; null indicates document-picker cancellation. */
    fun exportIdentity(bytes: ByteArray, suggestedName: String, completion: (String?, String?) -> Unit)
    fun cancel()
}

class IosIdentityFiles(private val documents: IosIdentityDocuments) : ReticulumIdentityFiles {
    override suspend fun importFile(): ByteArray? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<ByteArray?> { continuation ->
            continuation.invokeOnCancellation { documents.cancel() }
            documents.importIdentity { bytes, error ->
                if (continuation.isActive) {
                    if (error != null) continuation.resumeWithException(IllegalStateException(error))
                    else continuation.resume(bytes)
                } else bytes?.fill(0)
            }
        }
    }

    override suspend fun exportFile(bytes: ByteArray, suggestedName: String): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Boolean> { continuation ->
            continuation.invokeOnCancellation { documents.cancel() }
            documents.exportIdentity(bytes, suggestedName) { saved, error ->
                if (continuation.isActive) {
                    if (error != null) continuation.resumeWithException(IllegalStateException(error))
                    else continuation.resume(saved != null)
                }
            }
        }
    }
}
