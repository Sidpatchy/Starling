package dev.starling

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.os.Bundle

class MainActivity : ComponentActivity() {
    private var nodeController: AndroidReticulumNodeController? = null
    private var transport: AndroidReticulumTransport? = null
    private var chatRepository: ReticulumChatRepository? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        setContent {
            StarlingApp(
                repository = chat,
                nodeController = controller,
                liveChatRepository = chat,
            )
        }
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
}
