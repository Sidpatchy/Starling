package com.sidpatchy.Starling

import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableSharedFlow
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIViewController

/** iOS host for the shared preview and live RNS/RRC/LXMF repository. */
fun MainViewController(runtime: IosPythonRuntime, documents: IosIdentityDocuments): UIViewController = ComposeUIViewController {
    val foregroundEvents = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    DisposableEffect(Unit) {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationDidBecomeActiveNotification,
            null,
            NSOperationQueue.mainQueue,
        ) {
            foregroundEvents.tryEmit(Unit)
        }
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
    }
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
        foregroundEvents = foregroundEvents,
    )
}
