package dev.starling

import androidx.compose.runtime.Composable

@Composable
internal actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) = Unit
