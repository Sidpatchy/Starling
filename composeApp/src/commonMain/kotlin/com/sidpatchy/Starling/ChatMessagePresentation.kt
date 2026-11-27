package com.sidpatchy.Starling

internal enum class ConversationKind { CHANNEL, DIRECT_MESSAGE }

internal data class ConversationNotice(val label: String, val message: String, val isError: Boolean = false)

internal fun conversationNotice(
    kind: ConversationKind,
    previewMode: Boolean,
    isLive: Boolean,
    error: String?,
): ConversationNotice? = when {
    previewMode -> ConversationNotice("Preview", "Messages stay on this device in Local preview.")
    !error.isNullOrBlank() -> ConversationNotice("Error", error, isError = true)
    isLive -> null
    kind == ConversationKind.CHANNEL -> ConversationNotice(
        "Offline", "Messages are queued until you connect to this channel’s hub.",
    )
    else -> ConversationNotice("Offline", "Direct messages can’t be sent until Reticulum and LXMF are ready.")
}

internal enum class BubblePosition(val startsGroup: Boolean, val endsGroup: Boolean) {
    SINGLE(true, true),
    FIRST(true, false),
    MIDDLE(false, false),
    LAST(false, true),
}

private const val BubbleGroupWindowMillis = 2 * 60 * 1_000L

/** Group presentation only: preserve message order, IDs, and per-message delivery states. */
internal fun chatBubblePositions(messages: List<ChatMessage>, dayLabels: List<String?>): List<BubblePosition> {
    val continues = BooleanArray(messages.size)
    var groupStart = 0
    for (index in 1 until messages.size) {
        val previous = messages[index - 1]
        val current = messages[index]
        val firstTime = messages[groupStart].timestampMillis
        val previousTime = previous.timestampMillis
        val currentTime = current.timestampMillis
        continues[index] = dayLabels.getOrNull(index) == null &&
            previous.canGroupWith(current) &&
            firstTime != null && previousTime != null && currentTime != null &&
            currentTime >= previousTime && currentTime - firstTime in 0..BubbleGroupWindowMillis
        if (!continues[index]) groupStart = index
    }

    return List(messages.size) { index ->
        val follows = continues[index]
        val leads = index < messages.lastIndex && continues[index + 1]
        when {
            follows && leads -> BubblePosition.MIDDLE
            follows -> BubblePosition.LAST
            leads -> BubblePosition.FIRST
            else -> BubblePosition.SINGLE
        }
    }
}

private fun ChatMessage.canGroupWith(next: ChatMessage): Boolean {
    if (isSystem || next.isSystem || isAction || next.isAction || isMine != next.isMine) return false
    val firstId = authorId?.takeIf(String::isNotBlank)
    val nextId = next.authorId?.takeIf(String::isNotBlank)
    return when {
        firstId != null && nextId != null -> firstId == nextId
        isMine -> true
        firstId != null || nextId != null -> false
        else -> author.isNotBlank() && author.equals(next.author, ignoreCase = true)
    }
}

internal enum class ChatDeliveryStatus(val label: String, val detail: String) {
    QUEUED("Queued", "This message is waiting to be sent."),
    SENDING("Sending", "Starling is trying to send this message."),
    SENT("Sent", "This message was sent, but delivery hasn’t been confirmed."),
    DELIVERED("Delivered", "Delivery of this message was confirmed."),
    FAILED("Delivery failed", "Starling couldn’t deliver this message."),
    REJECTED("Delivery rejected", "This message wasn’t accepted for delivery."),
}

internal fun chatDeliveryStatus(state: String?): ChatDeliveryStatus? = when (state) {
    "queued" -> ChatDeliveryStatus.QUEUED
    "sending" -> ChatDeliveryStatus.SENDING
    "sent" -> ChatDeliveryStatus.SENT
    "delivered" -> ChatDeliveryStatus.DELIVERED
    "failed" -> ChatDeliveryStatus.FAILED
    "rejected" -> ChatDeliveryStatus.REJECTED
    else -> null
}
