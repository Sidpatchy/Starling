package dev.starling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ChatStateCodecTest {
    @Test
    fun encodingKeepsTheVersionedStorageFormat() {
        val state = PersistedChatState(previewMode = false, preferredHubNetworkId = "hub")

        assertEquals(
            "STARLING_CHAT_STATE_V7\nP\tMA==\nU\taHVi\nG\tU3Rhcmxpbmc=\n",
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
        )
        val state = PersistedChatState(
            previewMode = false,
            networks = listOf(ChatNetwork("hub", "Mesh 🌍", "0123456789abcdef0123456789abcdef", rrcNickname = "Operator")),
            rooms = listOf(ChatRoom("room", "hub", "general", text, 2)),
            members = listOf(member, ChatMember("self", "You", isSelf = true)),
            contacts = setOf(member.id),
            blockedMembers = setOf("blocked"),
            contactRequests = listOf(ChatContactRequest("request", member, text, "12:35", isPreview = true)),
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
    fun legacyHeadersAndMessagesWithoutAuthorIdsRemainReadable() {
        for (version in 1..6) {
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
    fun unpaddedFieldsRemainReadableButInvalidCharactersAreRejected() {
        assertEquals("abc", ChatStateCodec.decode("STARLING_CHAT_STATE_V7\nJ\tYWJj\n")?.localIdentityHash)
        assertNull(ChatStateCodec.decode("STARLING_CHAT_STATE_V7\nJ\tYW!Jj\n")?.localIdentityHash)
    }

    @Test
    fun unknownHeadersAreRejectedAndUnknownOrIncompleteRecordsAreIgnored() {
        assertNull(ChatStateCodec.decode(""))
        assertNull(ChatStateCodec.decode("STARLING_CHAT_STATE_V8\n"))
        assertNull(ChatStateCodec.decode("not a chat state"))
        assertEquals(
            PersistedChatState(),
            ChatStateCodec.decode("STARLING_CHAT_STATE_V7\nUNKNOWN\tYWJj\nM\tYWJj\nR\tYWJj\n"),
        )
    }
}
