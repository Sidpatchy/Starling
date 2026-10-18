package com.sidpatchy.Starling

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import android.os.Bundle
import android.hardware.display.DisplayManager
import android.view.Display
import android.os.Build
import android.content.pm.PackageManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private var nodeController: AndroidReticulumNodeController? = null
    private var transport: AndroidReticulumTransport? = null
    private var chatRepository: ReticulumChatRepository? = null
    private var pendingBluetoothPermission: CompletableDeferred<Unit>? = null
    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        pendingBluetoothPermission?.complete(Unit)
        pendingBluetoothPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        requestHighRefreshRate()
        val controller = AndroidReticulumNodeController(applicationContext) {
            requestRNodeBluetoothPermissions()
        }
        val reticulumTransport = AndroidReticulumTransport(applicationContext)
        val chat = ReticulumChatRepository(
            LocalPreviewChatRepository(),
            reticulumTransport,
            AndroidChatStateStore(applicationContext),
        )
        nodeController = controller
        transport = reticulumTransport
        chatRepository = chat
        val identityManager = ReticulumIdentityManager(
            store = AndroidIdentityStore(applicationContext),
            files = AndroidIdentityFiles(this),
            nodeController = controller,
            onIdentityChanged = { chat.updateManagedIdentity(it?.hash) },
        )
        setContent {
            StarlingApp(
                repository = chat,
                nodeController = controller,
                liveChatRepository = chat,
                identityManager = identityManager,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        requestHighRefreshRate()
    }

    override fun onDestroy() {
        nodeController?.dispose()
        transport?.dispose()
        chatRepository?.close()
        nodeController = null
        transport = null
        chatRepository = null
        super.onDestroy()
    }

    private fun requestHighRefreshRate() {
        val highestSupportedRate = getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
            ?.supportedModes
            ?.maxOfOrNull { it.refreshRate }
        if (highestSupportedRate != null && highestSupportedRate > 60f) {
            window.attributes = window.attributes.apply {
                preferredRefreshRate = highestSupportedRate
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                window.decorView.setRequestedFrameRate(highestSupportedRate)
            }
        }
    }

    private suspend fun requestRNodeBluetoothPermissions(): Boolean = withContext(Dispatchers.Main.immediate) {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isEmpty()) return@withContext true

        val response = pendingBluetoothPermission ?: CompletableDeferred<Unit>().also { pending ->
            pendingBluetoothPermission = pending
            bluetoothPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
        val completed = withTimeoutOrNull(BLUETOOTH_PERMISSION_TIMEOUT_MS) { response.await() } != null
        if (pendingBluetoothPermission === response) pendingBluetoothPermission = null
        completed && requiredPermissions.all {
            ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private companion object {
        const val BLUETOOTH_PERMISSION_TIMEOUT_MS = 60_000L
    }
}
