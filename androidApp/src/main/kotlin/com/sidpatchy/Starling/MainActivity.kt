package com.sidpatchy.Starling

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.core.app.ActivityCompat
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
    private var rnodeBlePicker: AndroidRNodeBlePicker? = null
    private var pendingBluetoothPermission: CompletableDeferred<Unit>? = null
    private var pendingBluetoothEnable: CompletableDeferred<Unit>? = null
    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        pendingBluetoothEnable?.complete(Unit)
        pendingBluetoothEnable = null
    }
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
        val picker = AndroidRNodeBlePicker(
            applicationContext,
            requestPermissions = { permissions -> requestBluetoothPermissionResult(permissions) },
            requestEnableBluetooth = { requestEnableBluetooth() },
            startActivity = { intent -> startActivity(intent) },
        )
        rnodeBlePicker = picker
        val controller = AndroidReticulumNodeController(
            applicationContext,
            requestBluetoothPermissions = { requestBluetoothPermissions(rnodeBluetoothPermissions()) },
            rnodeBlePicker = picker,
        )
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
        rnodeBlePicker?.onResume()
    }

    override fun onDestroy() {
        nodeController?.dispose()
        rnodeBlePicker?.dispose()
        rnodeBlePicker = null
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

    private fun rnodeBluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private suspend fun requestBluetoothPermissions(requiredPermissions: Array<String>): Boolean =
        requestBluetoothPermissionResult(requiredPermissions) == BluetoothPermissionResult.GRANTED

    /**
     * Asks for the permissions that are missing and reports how the request ended.
     * Android does not say when a permission is permanently denied; a refusal
     * after which it would show no rationale is the documented way to tell.
     */
    private suspend fun requestBluetoothPermissionResult(
        requiredPermissions: Array<String>,
    ): BluetoothPermissionResult = withContext(Dispatchers.Main.immediate) {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isEmpty()) return@withContext BluetoothPermissionResult.GRANTED

        val response = pendingBluetoothPermission ?: CompletableDeferred<Unit>().also { pending ->
            pendingBluetoothPermission = pending
            bluetoothPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
        val completed = withTimeoutOrNull(BLUETOOTH_PERMISSION_TIMEOUT_MS) { response.await() } != null
        if (pendingBluetoothPermission === response) pendingBluetoothPermission = null
        val stillMissing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
        }
        when {
            stillMissing.isEmpty() -> BluetoothPermissionResult.GRANTED
            completed && stillMissing.any {
                !ActivityCompat.shouldShowRequestPermissionRationale(this@MainActivity, it)
            } -> BluetoothPermissionResult.PERMANENTLY_DENIED
            else -> BluetoothPermissionResult.DENIED
        }
    }

    /** Shows the system request to turn Bluetooth on and waits for the answer. */
    private suspend fun requestEnableBluetooth() = withContext(Dispatchers.Main.immediate) {
        val response = pendingBluetoothEnable ?: CompletableDeferred<Unit>().also { pending ->
            pendingBluetoothEnable = pending
            bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
        withTimeoutOrNull(BLUETOOTH_PERMISSION_TIMEOUT_MS) { response.await() }
        if (pendingBluetoothEnable === response) pendingBluetoothEnable = null
    }

    private companion object {
        const val BLUETOOTH_PERMISSION_TIMEOUT_MS = 60_000L
    }
}
