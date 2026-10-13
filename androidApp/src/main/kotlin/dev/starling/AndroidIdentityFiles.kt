package dev.starling

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class AndroidIdentityFiles(private val activity: ComponentActivity) : ReticulumIdentityFiles {
    private var pendingImport: CancellableContinuation<Uri?>? = null
    private var pendingExport: CancellableContinuation<Uri?>? = null
    private val importLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingImport?.takeIf { it.isActive }?.resume(uri)
        pendingImport = null
    }
    private val exportLauncher = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        pendingExport?.takeIf { it.isActive }?.resume(uri)
        pendingExport = null
    }

    override suspend fun importFile(): ByteArray? {
        val uri = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Uri?> { continuation ->
                pendingImport = continuation
                continuation.invokeOnCancellation { pendingImport = null }
                importLauncher.launch(arrayOf("*/*"))
            }
        } ?: return null
        return withContext(Dispatchers.IO) {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(65)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                buffer.copyOf(count).also { buffer.fill(0) }
            } ?: error("Could not read the identity file.")
        }
    }

    override suspend fun exportFile(bytes: ByteArray, suggestedName: String): Boolean {
        val uri = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Uri?> { continuation ->
                pendingExport = continuation
                continuation.invokeOnCancellation { pendingExport = null }
                exportLauncher.launch(suggestedName)
            }
        } ?: return false
        withContext(Dispatchers.IO) {
            activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: error("Could not write the identity backup.")
        }
        return true
    }
}
