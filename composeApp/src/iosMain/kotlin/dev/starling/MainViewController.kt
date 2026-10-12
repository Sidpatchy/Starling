package dev.starling

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** iOS UI shim: shared preview works while native Reticulum transport is unimplemented. */
fun MainViewController(): UIViewController = ComposeUIViewController {
    StarlingApp(
        repository = LocalPreviewChatRepository(),
        nodeController = UnsupportedReticulumNodeController(
            "Reticulum connectivity is not available in this iOS shim yet.",
        ),
    )
}
