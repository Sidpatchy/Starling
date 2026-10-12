package dev.starling

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.runtime.remember
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.painterResource
import dev.starling.generated.resources.Res
import dev.starling.generated.resources.starling_app_icon

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
        icon = painterResource(Res.drawable.starling_app_icon),
    ) {
        StarlingApp(
            repository = chatRepository,
            nodeController = nodeController,
            liveChatRepository = chatRepository,
        )
    }
}
