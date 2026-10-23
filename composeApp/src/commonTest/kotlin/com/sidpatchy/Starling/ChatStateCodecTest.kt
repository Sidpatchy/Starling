package com.sidpatchy.Starling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ChatStateCodecTest {
    @Test
    fun encodingKeepsTheVersionedStorageFormat() {
        val state = PersistedChatState(previewMode = false, preferredHubNetworkId = "hub")

        assertEquals(
            "STARLING_CHAT_STATE_V13\nP\tMA==\nU\taHVi\nG\tU3Rhcmxpbmc=\nA\tU1lTVEVN\tMA==\tMA==\tU1lTVEVN\tMA==\n",
            ChatStateCodec.encode(state),
        )
    }

    @Test
    fun allDurableFieldsRoundTripIncludingUnicodeAndRecordDelimiters() {
        val text = "Hello 🌍\twith tabs\nand lines\rtoo"
        val member = ChatMember("member", "Mesh 🌍", address = "abcdef0123456789abcdef0123456789")
        val message = ChatMessage(
            id = "message",
            author = member.displayName,
            text = text,
            time = "12:34",
            isMine = true,
            isAction = true,
            deliveryState = "delivered",
            authorId = member.id,
            isSystem = true,
            systemEvents = listOf(
                ChatSystemEvent(ChatSystemEventKind.NOTICE, "Hub message", "12:33", 45_180_000L),
                ChatSystemEvent(ChatSystemEventKind.NOTICE, text, "12:34", 45_240_000L),
            ),
            timestampMillis = 45_240_000L,
        )
        val state = PersistedChatState(
            previewMode = false,
            networks = listOf(ChatNetwork("hub", "Mesh 🌍", "0123456789abcdef0123456789abcdef", rrcNickname = "Operator")),
            rooms = listOf(ChatRoom("room", "hub", "general", text, 2, ChatRoomModes(true, true, false, true, false, true, true))),
            members = listOf(member, ChatMember("self", "You", isSelf = true)),
            contacts = setOf(member.id),
            blockedMembers = setOf("blocked"),
            contactRequests = listOf(
                ChatContactRequest("request", member, text, "12:35", isPreview = true, timestampMillis = 45_300_000L),
            ),
            roomMessages = mapOf("room" to listOf(message)),
            directMessages = mapOf(member.id to listOf(message.copy(id = "direct", deliveryState = null, authorId = null))),
            nodeConfig = ReticulumNodeConfig(
                tcpPeers = listOf(ReticulumTcpPeer("mesh.example", 4242, "private mesh", "passphrase")),
                rnodeInterfaces = listOf(ReticulumRNodeInterface("/dev/ttyUSB0", 915_000_000L, 125_000, 17, 7, 5)),
            ),
            preferredHubNetworkId = "hub",
            activeHubNetworkId = "hub",
            localIdentityHash = "11223344556677889900aabbccddeeff",
            globalNickname = "Mesh 🌍",
            roomMemberIds = mapOf("room" to setOf(member.id, "self")),
            uiSettings = StarlingUiSettings(
                ThemePreference.DARK,
                bubbleStyle = true,
                denseMessages = true,
                timestampTimeZone = TimestampTimeZonePreference.UTC,
            ),
        )

        assertEquals(state, ChatStateCodec.decode(ChatStateCodec.encode(state)))
    }

    @Test
    fun sharedInstanceRpcKeyIsNeverEncoded() {
        val rpcKey = "11223344556677889900aabbccddeeff"
        val config = ReticulumNodeConfig(
            mode = ReticulumRuntimeMode.EXISTING_SHARED_INSTANCE,
            existingInstanceRpcKeyHex = rpcKey,
        )
        val encoded = ChatStateCodec.encode(PersistedChatState(nodeConfig = config))

        assertEquals(config.copy(existingInstanceRpcKeyHex = null), ChatStateCodec.decode(encoded)?.nodeConfig)
        assertFalse(encoded.contains(rpcKey))
        assertFalse(encoded.contains(encodeBridgeBase64(rpcKey)))
    }

    @Test
    fun consecutiveSystemEventsCollapseIntoOneExpandableSummary() {
        val firstSystem = ChatMessage(
            id = "system-1", author = "System", text = "Room is unregistered", time = "20:00",
            authorId = "hub", isSystem = true,
            systemEvents = listOf(ChatSystemEvent(ChatSystemEventKind.NOTICE, "Room is unregistered", "20:00", 72_000_000L)),
            timestampMillis = 72_000_000L,
        )
        val secondSystem = ChatMessage(
            id = "system-2", author = "System", text = "Alice joined #general", time = "20:01",
            authorId = "alice", isSystem = true,
            systemEvents = listOf(ChatSystemEvent(ChatSystemEventKind.JOIN, "Alice joined #general", "20:01", 72_060_000L)),
            timestampMillis = 72_060_000L,
        )

        val collapsed = collapseRepeatedSystemMessage(listOf(firstSystem), secondSystem)

        assertEquals(1, collapsed.size)
        assertEquals("1 user has joined, and 1 server notice", collapsed.single().text)
        assertEquals(listOf("Room is unregistered", "Alice joined #general"), collapsed.single().systemEvents.map { it.text })
        assertEquals(72_060_000L, collapsed.single().timestampMillis)
    }

    @Test
    fun unrecognizedCommandErrorsStayStandaloneBetweenSystemGroups() {
        val serverNotice = ChatMessage(
            id = "notice", author = "System", text = "Room information", time = "20:00", isSystem = true,
            systemEvents = listOf(ChatSystemEvent(ChatSystemEventKind.NOTICE, "Room information", "20:00")),
        )
        val commandError = ChatMessage(
            id = "command-error", author = "System", text = "Unrecognized command: /foo", time = "20:01", isSystem = true,
            systemEvents = listOf(ChatSystemEvent(ChatSystemEventKind.COMMAND_ERROR, "Unrecognized command: /foo", "20:01")),
        )

        val afterError = collapseRepeatedSystemMessage(listOf(serverNotice), commandError)
        val afterNextNotice = collapseRepeatedSystemMessage(afterError, serverNotice.copy(id = "notice-2", time = "20:02"))

        assertEquals(listOf(serverNotice, commandError, serverNotice.copy(id = "notice-2", time = "20:02")), afterNextNotice)
    }

    @Test
    fun legacyHeadersAndMessagesWithoutAuthorIdsRemainReadable() {
        for (version in 1..10) {
            val state = ChatStateCodec.decode(
                "STARLING_CHAT_STATE_V$version\n" +
                    "P\tMA==\n" +
                    "R\tcm9vbQ==\tbWVzc2FnZQ==\tbW9zcw==\taGVsbG8=\tMTI6MzQ=\tMA==\tMA==\t\n",
            )

            assertEquals(false, state?.previewMode, "version $version")
            assertEquals(listOf(ChatMessage("message", "moss", "hello", "12:34")), state?.roomMessages?.get("room"))
        }
    }

    @Test
    fun versionElevenRoomRecordsWithoutModeStateRemainReadable() {
        val roomRecord = listOf("H", "room", "hub", "general", "old topic", "2")
            .joinToString("\t") { value -> if (value == "H") value else Base64TextCodec.encode(value) }

        val room = ChatStateCodec.decode("STARLING_CHAT_STATE_V11\n$roomRecord\n")?.rooms?.single()

        assertEquals(ChatRoom("room", "hub", "general", "old topic", 2), room)
    }

    @Test
    fun versionTwelveStateMigratesWithRoomModesButWithoutCachedMembers() {
        val roomRecord = listOf("H", "room", "hub", "general", "topic", "3", "1100100")
            .joinToString("\t") { value -> if (value == "H") value else Base64TextCodec.encode(value) }

        val state = ChatStateCodec.decode("STARLING_CHAT_STATE_V12\n$roomRecord\n")

        assertEquals(ChatRoom("room", "hub", "general", "topic", 3, ChatRoomModes(true, true, false, false, true)), state?.rooms?.single())
        assertEquals(emptyMap(), state?.roomMemberIds)
    }

    @Test
    fun versionEightSystemRepeatCountsMigrateToExpandableNotices() {
        val oldRecord = listOf(
            "R", "room", "message", "Hub", "Room is unregistered", "20:47",
            "0", "0", "", "hub", "1", "3",
        ).joinToString("\t") { value -> if (value == "R") value else Base64TextCodec.encode(value) }
        val message = ChatStateCodec.decode("STARLING_CHAT_STATE_V8\n$oldRecord\n")
            ?.roomMessages?.get("room")?.single()

        assertEquals("3 server notices", message?.text)
        assertEquals(3, message?.systemEvents?.size)
    }

    @Test
    fun unpaddedFieldsRemainReadableButInvalidCharactersAreRejected() {
        assertEquals("abc", ChatStateCodec.decode("STARLING_CHAT_STATE_V9\nJ\tYWJj\n")?.localIdentityHash)
        assertNull(ChatStateCodec.decode("STARLING_CHAT_STATE_V9\nJ\tYW!Jj\n")?.localIdentityHash)
        assertEquals(
            StarlingUiSettings(),
            ChatStateCodec.decode("STARLING_CHAT_STATE_V9\nP\tMA==\n")?.uiSettings,
        )
    }

    @Test
    fun unknownHeadersAreRejectedAndUnknownOrIncompleteRecordsAreIgnored() {
        assertNull(ChatStateCodec.decode(""))
        assertNull(ChatStateCodec.decode("STARLING_CHAT_STATE_V14\n"))
        assertNull(ChatStateCodec.decode("not a chat state"))
        assertEquals(
            PersistedChatState(),
            ChatStateCodec.decode("STARLING_CHAT_STATE_V9\nUNKNOWN\tYWJj\nM\tYWJj\nR\tYWJj\n"),
        )
    }
}
