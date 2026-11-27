package com.sidpatchy.Starling

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class ChatTranscriptTest {
    @Test
    fun latestMessageStaysAtBottomWhenViewportOrMessageHeightChanges() {
        val transcript = TranscriptFixture(messageCount = 20)
        transcript.render {
            assertPinned("19")

            viewportHeight = 112
            assertPinned("19")

            latestHeight = 160
            assertPinned("19")

            viewportHeight = 240
            assertPinned("19")
        }
    }

    @Test
    fun newMessagesFollowOnlyWhenAlreadyAtBottom() {
        val transcript = TranscriptFixture(messageCount = 20)
        transcript.render {
            assertPinned("19")
            append()
            assertPinned("20")

            state.requestScrollToItem(4, 5)
            frame()
            assertTrue(state.canScrollBackward)
            assertEquals("16", firstVisibleId())

            append()
            frame()
            assertEquals("16", firstVisibleId())
            assertEquals(5, state.firstVisibleItemScrollOffset)

            state.requestScrollToItem(0)
            assertPinned("21")
            append()
            assertPinned("22")
        }
    }

    @Test
    fun shortTranscriptIsBottomAligned() {
        TranscriptFixture(messageCount = 1).render {
            assertPinned("0")
            append()
            assertPinned("1")
        }
    }

    private class TranscriptFixture(messageCount: Int) {
        var viewportHeight by mutableStateOf(240)
        var latestHeight by mutableStateOf(24)
        var messages by mutableStateOf(List(messageCount, ::message))
        val state = LazyListState()
        private val messageBottoms = mutableMapOf<String, Float>()
        private var frameTimeNanos = 0L
        private lateinit var scene: ImageComposeScene

        fun render(check: TranscriptFixture.() -> Unit) {
            scene = ImageComposeScene(320, 240) {
                ChatTranscript(
                    messages = messages,
                    compact = true,
                    itemSpacing = 2.dp,
                    modifier = Modifier.height(viewportHeight.dp).fillMaxWidth(),
                    state = state,
                ) { index, message ->
                    Box(
                        Modifier.fillMaxWidth()
                            .height(if (index == messages.lastIndex) latestHeight.dp else 24.dp)
                            .onGloballyPositioned { messageBottoms[message.id] = it.boundsInRoot().bottom },
                    )
                }
            }
            try {
                check()
            } finally {
                scene.close()
            }
        }

        fun append() {
            messages = messages + message(messages.size)
        }

        fun frame() {
            frameTimeNanos += 16_000_000L
            scene.render(frameTimeNanos).close()
        }

        fun firstVisibleId(): Any = state.layoutInfo.visibleItemsInfo
            .single { it.index == state.firstVisibleItemIndex }.key

        fun assertPinned(latestId: String) {
            frame()
            assertFalse(state.canScrollBackward)
            assertEquals(latestId, firstVisibleId())
            assertEquals(viewportHeight - 8f, messageBottoms[latestId])
        }
    }
}

private fun message(index: Int) = ChatMessage(index.toString(), "moss", "Message $index", "")
