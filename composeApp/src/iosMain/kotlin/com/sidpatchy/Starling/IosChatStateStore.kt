@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.sidpatchy.Starling

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.FILE
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.ferror
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.fsync
import platform.posix.chmod
import platform.posix.rename
import platform.posix.remove

/** Atomic app-private file storage matching the desktop/Android chat-state contract. */
class IosChatStateStore(dataDirectory: String) : ChatStateStore {
    private val statePath = "$dataDirectory/chat-state-v1.txt"
    private val temporaryPath = "$statePath.tmp"

    override fun read(): String? = memScoped {
        val file = fopen(statePath.cstr.ptr, "rb".cstr.ptr) ?: return@memScoped null
        try {
            if (fseek(file, 0, SEEK_SET) != 0) return@memScoped null
            val length = ftell(file)
            if (length < 0 || length > MAX_STATE_BYTES) return@memScoped null
            if (fseek(file, 0, SEEK_SET) != 0) return@memScoped null
            val bytes = ByteArray(length.toInt())
            if (bytes.isNotEmpty()) {
                val count = bytes.usePinned { fread(it.addressOf(0), 1uL, bytes.size.toULong(), file) }
                if (count != bytes.size.toULong() || ferror(file) != 0) return@memScoped null
            }
            bytes.decodeToString(throwOnInvalidSequence = false)
        } finally {
            fclose(file)
        }
    }

    override fun write(value: String) = memScoped {
        val bytes = value.encodeToByteArray()
        val file = fopen(temporaryPath.cstr.ptr, "wb".cstr.ptr)
            ?: throw IllegalStateException("Could not open the iOS chat-state file for writing.")
        var writeSucceeded = false
        try {
            val count = if (bytes.isEmpty()) 0uL else bytes.usePinned {
                fwrite(it.addressOf(0), 1uL, bytes.size.toULong(), file)
            }
            if (count != bytes.size.toULong() || ferror(file) != 0 || fsync(fileno(file)) != 0) {
                throw IllegalStateException("Could not write the iOS chat-state file.")
            }
            writeSucceeded = true
        } finally {
            fclose(file)
            if (!writeSucceeded) remove(temporaryPath.cstr.ptr)
        }
        chmod(temporaryPath.cstr.ptr, 0x180.convert())
        if (rename(temporaryPath.cstr.ptr, statePath.cstr.ptr) != 0) {
            remove(temporaryPath.cstr.ptr)
            throw IllegalStateException("Could not replace the iOS chat-state file.")
        }
    }

    private companion object {
        const val MAX_STATE_BYTES = 64L * 1024 * 1024
    }
}
