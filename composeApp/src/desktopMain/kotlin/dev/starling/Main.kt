package dev.starling

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.runtime.remember
import kotlinx.coroutines.runBlocking

fun main() = application {
    val nodeController = remember { DesktopReticulumNodeController() }
    val reticulumTransport = remember { DesktopReticulumTransport(nodeController) }
    val chatRepository = remember {
        ReticulumChatRepository(LocalPreviewChatRepository(), reticulumTransport, DesktopChatStateStore())
    }
    Window(
        onCloseRequest = {
            runBlocking {
                reticulumTransport.close()
                chatRepository.close()
                nodeController.close()
            }
            exitApplication()
        },
        title = "Starling",
    ) {
        StarlingApp(
            repository = chatRepository,
            nodeController = nodeController,
            liveChatRepository = chatRepository,
        )
    }
}
