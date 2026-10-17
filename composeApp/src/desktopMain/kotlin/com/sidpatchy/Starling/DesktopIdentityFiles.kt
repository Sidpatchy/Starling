package com.sidpatchy.Starling

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DesktopIdentityFiles : ReticulumIdentityFiles {
    override suspend fun importFile(): ByteArray? {
        val path = chooseFile(FileDialog.LOAD, "Import Reticulum private identity") ?: return null
        return withContext(Dispatchers.IO) {
            Files.newInputStream(path).use { it.readNBytes(PRIVATE_IDENTITY_BYTES + 1) }
        }
    }

    override suspend fun exportFile(bytes: ByteArray, suggestedName: String): Boolean {
        val path = chooseFile(FileDialog.SAVE, "Export Reticulum private identity", suggestedName) ?: return false
        withContext(Dispatchers.IO) {
            // Restrict permissions before writing private material where the filesystem supports it.
            if (!Files.exists(path)) Files.createFile(path)
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
            }
            Files.write(path, bytes)
        }
        return true
    }

    private suspend fun chooseFile(mode: Int, title: String, suggestedName: String? = null): Path? =
        suspendCancellableCoroutine { continuation ->
            var dialog: FileDialog? = null
            continuation.invokeOnCancellation { EventQueue.invokeLater { dialog?.dispose() } }
            EventQueue.invokeLater {
                try {
                    if (continuation.isActive) {
                        val chooser = FileDialog(null as Frame?, title, mode)
                        dialog = chooser
                        suggestedName?.let { chooser.file = it }
                        chooser.isVisible = true
                        val path = chooser.file?.let { Path.of(chooser.directory, it) }
                        if (continuation.isActive) continuation.resume(path)
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    dialog?.dispose()
                }
            }
        }
}
