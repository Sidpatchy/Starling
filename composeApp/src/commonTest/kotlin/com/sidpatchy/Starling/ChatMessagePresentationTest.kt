package com.sidpatchy.Starling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatMessagePresentationTest {
    @Test
    fun connectedConversationsOnlyShowANoticeWhenThereIsAnError() {
        for (kind in ConversationKind.entries) {
            assertNull(conversationNotice(kind, previewMode = false, isLive = true, error = null))
            assertNull(conversationNotice(kind, previewMode = false, isLive = true, error = " "))
            assertEquals(
                ConversationNotice("Error", "Could not send.", isError = true),
                conversationNotice(kind, previewMode = false, isLive = true, error = "Could not send."),
            )
        }
    }

    @Test
    fun offlineNoticesDescribeDifferentSendBehaviorAndDoNotSayPreview() {
        val channel = conversationNotice(ConversationKind.CHANNEL, previewMode = false, isLive = false, error = null)
        val dm = conversationNotice(ConversationKind.DIRECT_MESSAGE, previewMode = false, isLive = false, error = null)
        assertEquals("Offline", channel?.label)
        assertEquals("Offline", dm?.label)
        assertTrue(channel?.message?.contains("queued") == true)
        assertTrue(dm?.message?.contains("can’t be sent") == true)
    }

    @Test
    fun previewNeverExposesLiveConnectionErrors() {
        for (kind in ConversationKind.entries) {
            val notice = conversationNotice(kind, previewMode = true, isLive = false, error = "Private hub failed.")
            assertEquals(ConversationNotice("Preview", "Messages stay on this device in Local preview."), notice)
        }
    }

    private fun message(seconds: Long, author: String = "juniper", authorId: String? = "peer") =
        ChatMessage(seconds.toString(), author, "Message", "09:00", authorId = authorId, timestampMillis = seconds * 1_000)

    @Test
    fun adjacentMessagesFromOneSenderGroupWithinTwoMinutesOfTheFirst() {
        val messages = listOf(message(0), message(60), message(120), message(121), message(140))
        assertEquals(
            listOf(BubblePosition.FIRST, BubblePosition.MIDDLE, BubblePosition.LAST, BubblePosition.FIRST, BubblePosition.LAST),
            chatBubblePositions(messages, listOf("Today", null, null, null, null)),
        )
    }

    @Test
    fun dayDividersSystemRowsActionsAndReorderedTimesBreakGroups() {
        val messages = listOf(
            message(0), message(5), message(10).copy(isSystem = true), message(15),
            message(20).copy(isAction = true), message(25), message(24),
        )
        assertEquals(
            List(messages.size) { BubblePosition.SINGLE },
            chatBubblePositions(messages, listOf("Yesterday", "Today", null, null, null, null, null)),
        )
    }

    @Test
    fun senderIdentityMattersMoreThanNicknameAndNeverGroupsIncomingWithOutgoing() {
        val messages = listOf(
            message(1),
            message(2, author = "updated nickname"),
            message(3, author = "updated nickname", authorId = "different-peer"),
            message(4, authorId = "different-peer").copy(isMine = true),
            message(5, "you", authorId = null).copy(isMine = true),
        )
        assertEquals(
            listOf(BubblePosition.FIRST, BubblePosition.LAST, BubblePosition.SINGLE, BubblePosition.FIRST, BubblePosition.LAST),
            chatBubblePositions(messages, emptyList()),
        )
    }

    @Test
    fun legacyMessagesWithoutKnownTimesStaySeparate() {
        val messages = listOf(
            message(1).copy(timestampMillis = null),
            message(2).copy(timestampMillis = null),
            message(3),
        )
        assertEquals(List(3) { BubblePosition.SINGLE }, chatBubblePositions(messages, emptyList()))
    }

    @Test
    fun statusChangesDoNotSplitBubbleGroups() {
        val messages = listOf(message(1).copy(isMine = true), message(2).copy(isMine = true, deliveryState = "queued"))
        val positions = listOf(BubblePosition.FIRST, BubblePosition.LAST)
        assertEquals(positions, chatBubblePositions(messages, emptyList()))
        assertEquals(
            positions,
            chatBubblePositions(listOf(messages.first(), messages.last().copy(deliveryState = "failed")), emptyList()),
        )
    }

    @Test
    fun statusDetailsDistinguishSendingFromConfirmedDelivery() {
        val statuses = listOf("queued", "sending", "sent", "delivered", "failed", "rejected")
        assertEquals(ChatDeliveryStatus.entries, statuses.map(::chatDeliveryStatus))
        assertTrue(ChatDeliveryStatus.SENT.detail.contains("hasn’t been confirmed"))
        assertNull(chatDeliveryStatus("received"))
        assertNull(chatDeliveryStatus(null))
    }
}
