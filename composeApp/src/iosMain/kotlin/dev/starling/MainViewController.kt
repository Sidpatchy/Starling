package dev.starling

import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.runtime.remember
import platform.UIKit.UIViewController

/** iOS host for the shared preview and live RNS/RRC/LXMF repository. */
fun MainViewController(runtime: IosPythonRuntime): UIViewController = ComposeUIViewController {
    val nodeController = remember(runtime) { IosReticulumNodeController(runtime) }
    val transport = remember(nodeController) { IosReticulumTransport(nodeController) }
    val repository = remember(transport) {
        ReticulumChatRepository(
            preview = LocalPreviewChatRepository(),
            transport = transport,
            stateStore = IosChatStateStore(runtime.dataDirectory),
        )
    }
    StarlingApp(
        repository = repository,
        nodeController = nodeController,
        liveChatRepository = repository,
    )
}
