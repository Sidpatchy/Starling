package com.sidpatchy.Starling

import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

class DesktopChatStateStore : ChatStateStore {
    private val statePath = appDataDirectory().resolve("chat-state-v1.txt")

    @Synchronized
    override fun read(): String? = runCatching {
        securePosixPermissions(statePath.parent, DIRECTORY_PERMISSIONS)
        securePosixPermissions(statePath, FILE_PERMISSIONS)
        if (Files.isRegularFile(statePath)) Files.readString(statePath) else null
    }.getOrNull()

    @Synchronized
    override fun write(value: String) {
        Files.createDirectories(statePath.parent)
        securePosixPermissions(statePath.parent, DIRECTORY_PERMISSIONS)
        val temporary = statePath.resolveSibling(statePath.fileName.toString() + ".tmp")
        if (!Files.exists(temporary)) {
            // Create the temporary file private from its first byte; it contains the
            // same locally stored credentials and chat history as the final file.
            try {
                Files.createFile(temporary, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS))
            } catch (_: UnsupportedOperationException) {
                // Windows filesystems use the user's inherited profile ACL instead.
                Files.createFile(temporary)
            }
        }
        securePosixPermissions(temporary, FILE_PERMISSIONS)
        Files.writeString(temporary, value, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        // Flush the completed file before replacing the last known-good state.
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
        try {
            Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING)
        }
        securePosixPermissions(statePath, FILE_PERMISSIONS)
    }

    /** POSIX permissions are available on Linux/macOS; Windows uses its profile ACLs. */
    private fun securePosixPermissions(path: Path, permissions: Set<PosixFilePermission>) {
        try {
            Files.setPosixFilePermissions(path, permissions)
        } catch (_: UnsupportedOperationException) {
            // The Windows app data directory inherits the user's profile ACL.
        }
    }

    private fun appDataDirectory(): Path {
        val os = System.getProperty("os.name").lowercase()
        val home = Path.of(System.getProperty("user.home"))
        return when {
            os.contains("win") -> Path.of(System.getenv("APPDATA") ?: home.resolve("AppData/Roaming").toString(), "Starling")
            os.contains("mac") -> home.resolve("Library/Application Support/Starling")
            else -> Path.of(System.getenv("XDG_DATA_HOME") ?: home.resolve(".local/share").toString()).resolve("Starling")
        }
    }

    private companion object {
        val DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
        val FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------")
    }
}
