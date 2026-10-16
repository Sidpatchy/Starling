package dev.starling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RrcBridgeProtocolTest {
    @Test
    fun sharedBase64EncoderMatchesBridgeWireFormat() {
        val vectors = mapOf(
            "" to "",
            "f" to "Zg==",
            "fo" to "Zm8=",
            "foo" to "Zm9v",
            "foob" to "Zm9vYg==",
            "fooba" to "Zm9vYmE=",
            "foobar" to "Zm9vYmFy",
            "hello world" to "aGVsbG8gd29ybGQ=",
            "🌍🌍" to "8J+MjfCfjI0=",
        )

        for ((text, encoded) in vectors) {
            assertEquals(encoded, encodeBridgeBase64(text), text)
            assertEquals(text, parseRrcBridgeEvent("LOCAL_ADDRESS\t$encoded")?.localDeliveryAddress, encoded)
        }
    }

    @Test
    fun lxmfBridgeErrorsDecodeAsSharedEvents() {
        val parsed = parseRrcBridgeEvent("LXMF_ERROR\t${encodeBridgeBase64("IFAC rejected the peer")}\t")

        assertEquals(ReticulumEvent.Error("IFAC rejected the peer"), parsed?.event)
    }

    @Test
    fun bridgeDecodingKeepsItsLenientHandlingOfInvalidCharactersAndPadding() {
        assertEquals("hello", parseRrcBridgeEvent("LOCAL_ADDRESS\taG Vs!bG8=ignored")?.localDeliveryAddress)
        assertEquals("hello", parseRrcBridgeEvent("LOCAL_ADDRESS\taGVsbG8")?.localDeliveryAddress)
        assertEquals("\uFFFD", parseRrcBridgeEvent("LOCAL_ADDRESS\t/w==")?.localDeliveryAddress)
    }

    @Test
    fun connectionEventsDecodeHubNamesAndFailureMessages() {
        assertEquals(
            ReticulumConnectionState.Connected("Mesh 🌍"),
            parseRrcBridgeEvent("CONNECTION\tconnected\t${encodeBridgeBase64("Mesh 🌍")}")?.connectionState,
        )
        assertEquals(
            ReticulumConnectionState.Failed("RRC connection failed."),
            parseRrcBridgeEvent("CONNECTION\tfailed\t\t")?.connectionState,
        )
    }

    @Test
    fun roomMessagesKeepUnicodeDelimitersAndMessageFlags() {
        val body = "Hello 🌍\twith tabs\nand lines"
        val line = listOf(
            "MESSAGE", encodeBridgeBase64("message"), encodeBridgeBase64("general"),
            encodeBridgeBase64("sender"), encodeBridgeBase64("Mesh 🌍"), encodeBridgeBase64(body),
            "123456", "1", "0", "1", encodeBridgeBase64("address"),
        ).joinToString("\t")

        assertEquals(
            ReticulumEvent.RoomMessageReceived(
                RrcRoomMessage(
                    "message", "general", "sender", "Mesh 🌍", body, 123456,
                    isAction = true, isMine = true, senderLxmfAddress = "address",
                ),
            ),
            parseRrcBridgeEvent(line)?.event,
        )
    }

    @Test
    fun noticesKeepTheirSystemFlagAndMayBeDeliveredWithoutARoom() {
        val line = listOf(
            "MESSAGE", encodeBridgeBase64("notice-1"), "", encodeBridgeBase64("hub"),
            encodeBridgeBase64("RRC Hub"), encodeBridgeBase64("Room is unregistered"),
            "123456", "0", "1", "0", "",
        ).joinToString("\t")

        assertEquals(
            RrcRoomMessage(
                id = "notice-1",
                roomId = "",
                senderId = "hub",
                senderName = "RRC Hub",
                body = "Room is unregistered",
                timestampMillis = 123456,
                isNotice = true,
            ),
            (parseRrcBridgeEvent(line)?.event as ReticulumEvent.RoomMessageReceived).message,
        )
    }

    @Test
    fun membershipAndCommandErrorsPreserveRoomAndTimestamp() {
        assertEquals(
            ReticulumEvent.MemberJoined("general", ChatMember("member", "Moss"), 123456),
            parseRrcBridgeEvent(
                "MEMBER_JOINED\t${encodeBridgeBase64("general")}\t${encodeBridgeBase64("member")}\t${encodeBridgeBase64("Moss")}\t\t123456",
            )?.event,
        )
        assertEquals(
            ReticulumEvent.Error("not authorized", "general", 123456),
            parseRrcBridgeEvent("ERROR\t${encodeBridgeBase64("not authorized")}\t${encodeBridgeBase64("general")}\t123456")?.event,
        )
    }

    @Test
    fun directMessagesPreserveDeliveryStateAndInvalidTimestampsFallBackToZero() {
        val fields = listOf("DIRECT_MESSAGE", "bWVzc2FnZQ==", "YWRkcmVzcw==", "TW9zcw==", "aGVsbG8=", "invalid")

        assertEquals(
            ReticulumEvent.DirectMessage(
                LxmfDirectMessage("message", "address", "Moss", "hello", 0, isMine = false, deliveryState = "received"),
            ),
            parseRrcBridgeEvent((fields + "0").joinToString("\t"))?.event,
        )
        assertEquals(
            ReticulumEvent.DirectMessage(
                LxmfDirectMessage("message", "address", "Moss", "hello", 0, isMine = true, deliveryState = "queued"),
            ),
            parseRrcBridgeEvent((fields + "1").joinToString("\t"))?.event,
        )
    }

    @Test
    fun unknownAndIncompleteMessagesAreIgnored() {
        assertNull(parseRrcBridgeEvent("UNKNOWN\tvalue"))
        assertNull(parseRrcBridgeEvent("MESSAGE\tbWVzc2FnZQ=="))
        assertNull(parseRrcBridgeEvent("DIRECT_MESSAGE\tbWVzc2FnZQ=="))
        assertNull(parseRrcBridgeEvent("IDENTITY\t"))
        assertNull(parseRrcBridgeEvent("SELF\t"))
    }
}
