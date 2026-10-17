package com.sidpatchy.Starling

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class DesktopChatStateStore : ChatStateStore {
    private val statePath = appDataDirectory().resolve("chat-state-v1.txt")

    @Synchronized
    override fun read(): String? = runCatching {
        if (Files.isRegularFile(statePath)) Files.readString(statePath) else null
    }.getOrNull()

    @Synchronized
    override fun write(value: String) {
        Files.createDirectories(statePath.parent)
        val temporary = statePath.resolveSibling(statePath.fileName.toString() + ".tmp")
        Files.writeString(temporary, value, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        try {
            Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING)
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
}
