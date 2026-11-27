package com.sidpatchy.Starling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatTranscript(
    messages: List<ChatMessage>,
    compact: Boolean,
    itemSpacing: Dp,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: @Composable (Int, ChatMessage) -> Unit,
) {
    val latestId = messages.lastOrNull()?.id
    val anchor = remember(state) { TranscriptAnchor(latestId) }
    SideEffect {
        if (latestId != null && latestId != anchor.latestId && !state.canScrollBackward) {
            // Keep index zero on the next layout instead of following the old last message's key.
            // When reading history, let the list keep that visible message's key as usual.
            state.requestScrollToItem(0)
        }
        anchor.latestId = latestId
    }

    // Start measuring from the newest message so keyboard and row resizes keep the bottom fixed.
    LazyColumn(
        modifier = modifier,
        state = state,
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = if (compact) 12.dp else 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(itemSpacing, Alignment.Bottom),
    ) {
        items(count = messages.size, key = { messages[messages.lastIndex - it].id }) { reverseIndex ->
            val index = messages.lastIndex - reverseIndex
            content(index, messages[index])
        }
    }
}

private class TranscriptAnchor(var latestId: String?)
