package com.sidpatchy.Starling

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

@Composable
internal fun rememberDismissKeyboardAction(): () -> Unit {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    return remember(focusManager, keyboardController) {
        {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }
}

/** Hides the software keyboard when the user scrolls content upward. */
@Composable
internal fun Modifier.dismissKeyboardOnUpwardScroll(): Modifier {
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val dismissKeyboard = rememberDismissKeyboardAction()
    val currentKeyboardVisible = rememberUpdatedState(keyboardVisible)
    val currentDismissKeyboard = rememberUpdatedState(dismissKeyboard)
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y < 0f && currentKeyboardVisible.value) {
                    currentDismissKeyboard.value()
                }
                return Offset.Zero
            }
        }
    }
    return nestedScroll(connection)
}
