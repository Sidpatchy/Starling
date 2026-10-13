package dev.starling

import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.runtime.remember
import platform.UIKit.UIViewController

/** iOS host for the shared preview and live RNS/RRC/LXMF repository. */
fun MainViewController(runtime: IosPythonRuntime, documents: IosIdentityDocuments): UIViewController = ComposeUIViewController {
    val nodeController = remember(runtime) { IosReticulumNodeController(runtime) }
    val transport = remember(nodeController) { IosReticulumTransport(nodeController) }
    val repository = remember(transport) {
        ReticulumChatRepository(
            preview = LocalPreviewChatRepository(),
            transport = transport,
            stateStore = IosChatStateStore(runtime.dataDirectory),
        )
    }
    val identityManager = remember(runtime, documents) {
        ReticulumIdentityManager(
            store = ReticulumIdentityStore { command ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    runtime.invoke("IDENTITY\t${encodeBridgeBase64("${runtime.dataDirectory}/reticulum")}\t${encodeBridgeBase64(command)}")
                }
            },
            files = IosIdentityFiles(documents),
            nodeController = nodeController,
            onIdentityChanged = { repository.updateManagedIdentity(it?.hash) },
        )
    }
    StarlingApp(
        repository = repository,
        nodeController = nodeController,
        liveChatRepository = repository,
        identityManager = identityManager,
    )
}
