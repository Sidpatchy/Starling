package dev.starling

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Standalone identity cryptography in the UI process, without starting an RNS node. */
class AndroidIdentityStore(context: Context) : ReticulumIdentityStore {
    private val appContext = context.applicationContext
    private val directory = appContext.noBackupFilesDir.resolve("reticulum")
    private val operations = Mutex()

    override suspend fun invoke(command: String): String = operations.withLock {
        withContext(Dispatchers.IO) {
            if (command == "IDENTITY_STATUS" && !directory.resolve("starling_identity").exists()) {
                return@withContext "IDENTITY_MISSING"
            }
            if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
            Python.getInstance().getModule("starling_identity").callAttr("invoke", directory.path, command).toString()
        }
    }
}
