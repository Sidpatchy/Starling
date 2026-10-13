package dev.starling

/** Shared parser for the private line protocol between Kotlin and the RNS bridge. */
data class ParsedRrcBridgeEvent(
    val connectionState: ReticulumConnectionState? = null,
    val localDeliveryAddress: String? = null,
    val event: ReticulumEvent? = null,
)

internal fun encodeBridgeBase64(value: String): String = Base64TextCodec.encode(value)

fun parseRrcBridgeEvent(line: String): ParsedRrcBridgeEvent? {
    val fields = line.split('\t')
    return when (fields.firstOrNull()) {
        "CONNECTION" -> {
            val hubName = fields.getOrNull(2)?.decodeBridgeBase64().orEmpty().ifBlank { null }
            val message = fields.getOrNull(3)?.decodeBridgeBase64().orEmpty()
            val state = when (fields.getOrNull(1)) {
                "connecting" -> ReticulumConnectionState.Connecting
                "connected" -> ReticulumConnectionState.Connected(hubName)
                "failed" -> ReticulumConnectionState.Failed(message.ifBlank { "RRC connection failed." })
                else -> ReticulumConnectionState.Disconnected
            }
            ParsedRrcBridgeEvent(connectionState = state)
        }
        "LOCAL_ADDRESS" -> ParsedRrcBridgeEvent(localDeliveryAddress = fields.getOrNull(1)?.decodeBridgeBase64().orEmpty())
        "IDENTITY" -> fields.getOrNull(1)?.decodeBridgeBase64().orEmpty().let { identityHash ->
            if (identityHash.isBlank()) null else ParsedRrcBridgeEvent(
                event = ReticulumEvent.LocalIdentityChanged(identityHash),
            )
        }
        "SELF" -> {
            val memberId = fields.getOrNull(1)?.decodeBridgeBase64().orEmpty()
            val displayName = fields.getOrNull(2)?.decodeBridgeBase64().orEmpty()
            if (memberId.isBlank()) null else ParsedRrcBridgeEvent(
                event = ReticulumEvent.LocalMemberChanged(ChatMember(memberId, displayName.ifBlank { "You" }, isSelf = true)),
            )
        }
        "JOINED" -> ParsedRrcBridgeEvent(event = ReticulumEvent.RoomJoined(fields.getOrNull(1)?.decodeBridgeBase64().orEmpty()))
        "LEFT" -> ParsedRrcBridgeEvent(event = ReticulumEvent.RoomLeft(fields.getOrNull(1)?.decodeBridgeBase64().orEmpty()))
        "MEMBERS" -> {
            val roomId = fields.getOrNull(1)?.decodeBridgeBase64().orEmpty()
            val members = fields.getOrNull(2).orEmpty().split(';').filter { it.isNotBlank() }.map { entry ->
                val (id, address) = entry.split('=', limit = 2).let { it.first() to it.getOrElse(1) { "" } }
                ChatMember(id, id.take(12), address = address.ifBlank { null })
            }
            ParsedRrcBridgeEvent(event = ReticulumEvent.MembersChanged(roomId, members))
        }
        "MEMBER_JOINED" -> {
            val roomId = fields.getOrNull(1)?.decodeBridgeBase64().orEmpty()
            val memberId = fields.getOrNull(2)?.decodeBridgeBase64().orEmpty()
            val name = fields.getOrNull(3)?.decodeBridgeBase64().orEmpty()
            val address = fields.getOrNull(4)?.decodeBridgeBase64().orEmpty()
            if (memberId.isBlank()) null else ParsedRrcBridgeEvent(
                event = ReticulumEvent.MemberJoined(roomId, ChatMember(memberId, name.ifBlank { memberId.take(12) }, address = address.ifBlank { null })),
            )
        }
        "MEMBER_LEFT" -> ParsedRrcBridgeEvent(
            event = ReticulumEvent.MemberLeft(
                fields.getOrNull(1)?.decodeBridgeBase64().orEmpty(),
                fields.getOrNull(2)?.decodeBridgeBase64().orEmpty(),
            ),
        )
        "ERROR" -> ParsedRrcBridgeEvent(
            event = ReticulumEvent.Error(fields.getOrNull(1)?.decodeBridgeBase64().orEmpty().ifBlank { "RRC error" }),
        )
        "LXMF_ERROR" -> ParsedRrcBridgeEvent(
            event = ReticulumEvent.Error(fields.getOrNull(1)?.decodeBridgeBase64().orEmpty().ifBlank { "LXMF error" }),
        )
        "DIRECT_MESSAGE" -> {
            if (fields.size < 7) return null
            ParsedRrcBridgeEvent(
                event = ReticulumEvent.DirectMessage(
                    LxmfDirectMessage(
                        id = fields[1].decodeBridgeBase64(),
                        address = fields[2].decodeBridgeBase64(),
                        senderName = fields[3].decodeBridgeBase64(),
                        body = fields[4].decodeBridgeBase64(),
                        timestampMillis = fields[5].toLongOrNull() ?: 0L,
                        isMine = fields[6] == "1",
                        deliveryState = if (fields[6] == "1") "queued" else "received",
                    ),
                ),
            )
        }
        "DELIVERY_STATUS" -> ParsedRrcBridgeEvent(
            event = ReticulumEvent.DeliveryStatus(
                messageId = fields.getOrNull(1)?.decodeBridgeBase64().orEmpty(),
                address = fields.getOrNull(2)?.decodeBridgeBase64().orEmpty(),
                state = fields.getOrNull(3).orEmpty(),
            ),
        )
        "MESSAGE" -> {
            if (fields.size < 10) return null
            ParsedRrcBridgeEvent(
                event = ReticulumEvent.RoomMessageReceived(
                    RrcRoomMessage(
                        id = fields[1].decodeBridgeBase64(),
                        roomId = fields[2].decodeBridgeBase64(),
                        senderId = fields[3].decodeBridgeBase64(),
                        senderName = fields[4].decodeBridgeBase64(),
                        body = fields[5].decodeBridgeBase64(),
                        timestampMillis = fields[6].toLongOrNull() ?: 0L,
                        isAction = fields[7] == "1",
                        isNotice = fields[8] == "1",
                        isMine = fields[9] == "1",
                        senderLxmfAddress = fields.getOrNull(10)?.decodeBridgeBase64()?.ifBlank { null },
                    ),
                ),
            )
        }
        else -> null
    }
}

internal fun String.decodeBridgeBase64(): String = Base64TextCodec.decode(this, ignoreInvalidCharacters = true)
