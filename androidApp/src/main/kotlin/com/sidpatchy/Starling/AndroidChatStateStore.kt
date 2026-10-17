package com.sidpatchy.Starling

import android.content.Context
import java.io.File

class AndroidChatStateStore(context: Context) : ChatStateStore {
    private val stateFile = File(context.applicationContext.noBackupFilesDir, "chat-state-v1.txt")

    @Synchronized
    override fun read(): String? = runCatching { stateFile.takeIf(File::isFile)?.readText(Charsets.UTF_8) }.getOrNull()

    @Synchronized
    override fun write(value: String) {
        stateFile.parentFile?.mkdirs()
        val temporary = File(stateFile.parentFile, stateFile.name + ".tmp")
        temporary.writeText(value, Charsets.UTF_8)
        if (!temporary.renameTo(stateFile)) {
            stateFile.writeText(value, Charsets.UTF_8)
            temporary.delete()
        }
    }
}
