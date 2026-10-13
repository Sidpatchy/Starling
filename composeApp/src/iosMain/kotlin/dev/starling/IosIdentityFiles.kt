package dev.starling

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Callbacks are named interfaces rather than Kotlin lambdas so the Objective-C export has a plain
 * `void` method for Swift to call instead of a block that returns `KotlinUnit`.
 */
fun interface IosIdentityImportCallback {
    fun onImport(bytes: ByteArray?, error: String?)
}

fun interface IosIdentityExportCallback {
    /** A non-null saved marker indicates success; null indicates document-picker cancellation. */
    fun onExport(saved: String?, error: String?)
}

/** Swift-owned document pickers keep UIKit presentation outside shared Compose code. */
interface IosIdentityDocuments {
    fun importIdentity(callback: IosIdentityImportCallback)
    fun exportIdentity(bytes: ByteArray, suggestedName: String, callback: IosIdentityExportCallback)
    fun cancel()
}

class IosIdentityFiles(private val documents: IosIdentityDocuments) : ReticulumIdentityFiles {
    override suspend fun importFile(): ByteArray? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<ByteArray?> { continuation ->
            continuation.invokeOnCancellation { documents.cancel() }
            documents.importIdentity(IosIdentityImportCallback { bytes, error ->
                if (continuation.isActive) {
                    if (error != null) continuation.resumeWithException(IllegalStateException(error))
                    else continuation.resume(bytes)
                } else bytes?.fill(0)
            })
        }
    }

    override suspend fun exportFile(bytes: ByteArray, suggestedName: String): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Boolean> { continuation ->
            continuation.invokeOnCancellation { documents.cancel() }
            documents.exportIdentity(bytes, suggestedName, IosIdentityExportCallback { saved, error ->
                if (continuation.isActive) {
                    if (error != null) continuation.resumeWithException(IllegalStateException(error))
                    else continuation.resume(saved != null)
                }
            })
        }
    }
}
