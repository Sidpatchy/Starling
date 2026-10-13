package dev.starling

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import android.os.Bundle
import android.hardware.display.DisplayManager
import android.view.Display
import android.os.Build

class MainActivity : ComponentActivity() {
    private var nodeController: AndroidReticulumNodeController? = null
    private var transport: AndroidReticulumTransport? = null
    private var chatRepository: ReticulumChatRepository? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        requestHighRefreshRate()
        val controller = AndroidReticulumNodeController(applicationContext)
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
}
