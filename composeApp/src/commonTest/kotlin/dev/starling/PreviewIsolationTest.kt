package dev.starling

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewIsolationTest {
    @Test
    fun previewHidesLiveStateAndPersistsItsSelection() = runBlocking {
        val liveNetwork = ChatNetwork(
            id = "private-network",
            name = "Private network",
            hubDestination = "0123456789abcdef0123456789abcdef",
        )
        val liveRoom = ChatRoom("private-room", liveNetwork.id, "ops", "", 0)
        val liveMember = ChatMember("private-member", "Private contact", address = "abcdef0123456789abcdef0123456789")
        val liveRequest = ChatContactRequest(
            id = "private-request",
            sender = liveMember,
            message = "Private message",
            time = "12:00",
        )
        val store = MemoryChatStateStore(
            ChatStateCodec.encode(
                PersistedChatState(
                    previewMode = true,
                    networks = listOf(liveNetwork),
                    rooms = listOf(liveRoom),
                    members = listOf(liveMember),
                    contacts = setOf(liveMember.id),
                    contactRequests = listOf(liveRequest),
                    localIdentityHash = "11223344556677889900aabbccddeeff",
                    roomMessages = mapOf(
                        liveRoom.id to listOf(ChatMessage("private-message", "Private", "Hidden body", "12:01")),
                    ),
                ),
            ),
        )
        val transport = FakeReticulumTransport()
        val repository = ReticulumChatRepository(LocalPreviewChatRepository(), transport, store)

        assertTrue(repository.networks.first().all { it.isPreview })
        assertFalse(repository.networks.first().any { it.id == liveNetwork.id })
        assertFalse(repository.knownMembers.first().any { it.id == liveMember.id })
        assertTrue(repository.contactRequests.first().all { it.isPreview })
        assertFalse(repository.contactRequests.first().any { it.id == liveRequest.id })
        assertTrue(repository.contactMemberIds.first().isEmpty())
        assertNull(repository.localDeliveryAddress.first())
        assertNull(repository.localIdentityHash.first())
        assertEquals(ReticulumConnectionState.Disconnected, repository.connectionState.first())
        assertEquals(ReticulumConnectionState.Disconnected, transport.state.first { it == ReticulumConnectionState.Disconnected })
        assertEquals(emptyList(), repository.messages(liveRoom.id).first())

        val previewRoom = repository.rooms.first().first()
        val demoNetwork = repository.addNetwork("Demo mesh", "")
        val demoRoom = repository.addRoom(demoNetwork.id, "friends", "")
        repository.send(previewRoom.id, "This stays in preview")
        repository.addContact(ChatMember("preview-contact", "Demo contact"))
        repository.sendDirectMessage("preview-contact", "Also local only")
        assertTrue(repository.messages(previewRoom.id).first { messages -> messages.any { it.text == "This stays in preview" } }.isNotEmpty())
        assertEquals(
            "Also local only",
            repository.directMessages("preview-contact").first { messages -> messages.any { it.text == "Also local only" } }.single().text,
        )
        assertTrue(repository.rooms.first { list -> list.any { it.id == demoRoom.id } }.any { it.id == demoRoom.id })
        assertEquals(0, transport.roomMessagesSent)
        assertEquals(0, transport.directMessagesSent)

        repository.setPreviewMode(false)
        assertTrue(repository.networks.first { it.any { network -> network.id == liveNetwork.id } }.none { it.isPreview })
        assertTrue(repository.knownMembers.first { it.any { member -> member.id == liveMember.id } }.any { it.id == liveMember.id })
        assertEquals(setOf(liveMember.id), repository.contactMemberIds.first { it.isNotEmpty() })
        assertEquals(listOf(liveRequest.id), repository.contactRequests.first { it.any { request -> request.id == liveRequest.id } }.map { it.id })
        assertFalse(repository.contactRequests.first().any { it.isPreview })
        assertEquals(transport.localDeliveryAddress.value, repository.localDeliveryAddress.first { it != null })
        assertEquals("11223344556677889900aabbccddeeff", repository.localIdentityHash.first { it != null })
        assertEquals(ReticulumConnectionState.Disconnected, repository.connectionState.first())
        transport.state.value = ReticulumConnectionState.Connected("Private hub")
        assertEquals(
            ReticulumConnectionState.Connected("Private hub"),
            repository.connectionState.first { it is ReticulumConnectionState.Connected },
        )
        assertEquals("Hidden body", repository.messages(liveRoom.id).first { it.isNotEmpty() }.single().text)
        assertEquals(emptyList(), repository.messages(previewRoom.id).first())
        assertTrue(repository.networks.first { it.none { network -> network.id == demoNetwork.id } }.none { it.id == demoNetwork.id })
        assertEquals(emptyList(), repository.directMessages("preview-contact").first())

        repository.close()
        assertEquals(false, ChatStateCodec.decode(store.value!!)?.previewMode)
        assertFalse(ChatStateCodec.decode(store.value!!)!!.contacts.contains("preview-contact"))
        assertFalse(ChatStateCodec.decode(store.value!!)!!.networks.any { it.id == demoNetwork.id })

        val restoredRepository = ReticulumChatRepository(LocalPreviewChatRepository(), FakeReticulumTransport(), store)
        assertFalse(restoredRepository.previewMode.value)
        assertTrue(restoredRepository.networks.value.any { it.id == liveNetwork.id })
        assertFalse(restoredRepository.networks.value.any { it.isPreview })
        restoredRepository.close()
    }

    @Test
    fun channelNamesAreLowercaseAndRemovalIsPersisted() = runBlocking {
        val local = LocalPreviewChatRepository()
        val previewRoom = local.addRoom(local.networks.value.first().id, "#My-Channel", "demo")
        assertEquals("my-channel", previewRoom.name)
        local.send(previewRoom.id, "temporary")
        local.removeRoom(previewRoom.id)
        assertFalse(local.rooms.value.any { it.id == previewRoom.id })
        assertEquals(emptyList(), local.messages(previewRoom.id).first())

        val network = ChatNetwork("live", "Live", "0123456789abcdef0123456789abcdef")
        val legacyRoom = ChatRoom("legacy-room", network.id, "UPPERCASE", "", 0)
        val store = MemoryChatStateStore(
            ChatStateCodec.encode(
                PersistedChatState(previewMode = false, networks = listOf(network), rooms = listOf(legacyRoom)),
            ),
        )
        val repository = ReticulumChatRepository(LocalPreviewChatRepository(), FakeReticulumTransport(), store)
        assertEquals("uppercase", repository.rooms.value.single().name)
        repository.removeRoom(legacyRoom.id)
        repository.close()
        assertFalse(ChatStateCodec.decode(store.value!!)!!.rooms.any { it.id == legacyRoom.id })
    }

    @Test
    fun nodeInterfacePreferencesSurviveRepositoryRestartWithoutSavingRpcKey() = runBlocking {
        val config = ReticulumNodeConfig(
            tcpPeers = listOf(
                ReticulumTcpPeer(
                    host = "mesh.example",
                    port = 4242,
                    ifacNetworkName = "private mesh",
                    ifacPassphrase = "secret passphrase",
                ),
            ),
            rnodeInterfaces = listOf(
                ReticulumRNodeInterface("/dev/ttyUSB0", 915_000_000L, 125_000, 17, 7, 5),
            ),
        )
        val hub = ChatNetwork("hub", "Saved hub", "0123456789abcdef0123456789abcdef")
        val identityHash = "fedcba9876543210fedcba9876543210"
        val store = MemoryChatStateStore(
            ChatStateCodec.encode(
                PersistedChatState(
                    previewMode = false,
                    networks = listOf(hub),
                    localIdentityHash = identityHash,
                ),
            ),
        )
        val repository = ReticulumChatRepository(LocalPreviewChatRepository(), FakeReticulumTransport(), store)
        repository.saveNodeConfig(config.copy(existingInstanceRpcKeyHex = "aabbccdd"))
        repository.savePreferredHubNetworkId(hub.id)
        repository.connectToHub(ReticulumConnectionConfig(hub.hubDestination!!, "Starling", hub.id))
        repository.close()
        assertEquals(identityHash, ChatStateCodec.decode(store.value!!)!!.localIdentityHash)

        val restored = ReticulumChatRepository(LocalPreviewChatRepository(), FakeReticulumTransport(), store)
        assertEquals(config, restored.nodeConfig.value)
        assertEquals(null, restored.nodeConfig.value?.existingInstanceRpcKeyHex)
        assertEquals(hub.id, restored.preferredHubNetworkId.value)
        assertEquals(hub.id, restored.activeHubNetworkId.first { it != null })
        assertEquals(identityHash, restored.localIdentityHash.value)
        restored.close()
    }

    @Test
    fun receivedIdentityHashAppearsInIdentityStateAndPersists() = runBlocking {
        val store = MemoryChatStateStore(ChatStateCodec.encode(PersistedChatState(previewMode = false)))
        val transport = FakeReticulumTransport()
        val repository = ReticulumChatRepository(LocalPreviewChatRepository(), transport, store)
        val identityHash = "0123456789abcdef0123456789abcdef"

        transport.events.emit(ReticulumEvent.LocalIdentityChanged(identityHash))
        assertEquals(identityHash, repository.localIdentityHash.first { it == identityHash })
        repository.close()

        assertEquals(identityHash, ChatStateCodec.decode(store.value!!)!!.localIdentityHash)
    }

    @Test
    fun globalAndHubNicknamesPersistAndNormalize() = runBlocking {
        val hub = ChatNetwork("hub", "Field hub", "0123456789abcdef0123456789abcdef")
        val store = MemoryChatStateStore(
            ChatStateCodec.encode(PersistedChatState(previewMode = false, networks = listOf(hub))),
        )
        val repository = ReticulumChatRepository(LocalPreviewChatRepository(), FakeReticulumTransport(), store)

        repository.saveGlobalNickname("  Field Operator  ")
        repository.updateNetworkNickname(hub.id, "  North Station  ")
        repository.close()

        val persisted = ChatStateCodec.decode(store.value!!)!!
        assertEquals("Field Operator", persisted.globalNickname)
        assertEquals("North Station", persisted.networks.single().rrcNickname)
        assertEquals("Starling", normalizeStarlingNickname(""))
        assertEquals(32, normalizeStarlingNickname("🌐".repeat(20)).encodeToByteArray().size)
    }

    @Test
    fun liveRoomsAndDirectMessagesNormalizeOutgoingTextConsistently() = runBlocking {
        val network = ChatNetwork("hub", "Mesh", "0123456789abcdef0123456789abcdef")
        val room = ChatRoom("room", network.id, "general", "", 0)
        val member = ChatMember("contact", "Moss", address = "abcdef0123456789abcdef0123456789")
        val store = MemoryChatStateStore(
            ChatStateCodec.encode(
                PersistedChatState(
                    previewMode = false,
                    networks = listOf(network),
                    rooms = listOf(room),
                    members = listOf(member),
                    contacts = setOf(member.id),
                    activeHubNetworkId = network.id,
                ),
            ),
        )
        val transport = FakeReticulumTransport()
        val repository = ReticulumChatRepository(LocalPreviewChatRepository(), transport, store)
        try {
            repository.send(room.id, " \t\n ")
            repository.sendDirectMessage(member.id, " \t\n ")
            assertEquals(0, transport.roomMessagesSent)
            assertEquals(0, transport.directMessagesSent)

            repository.send(room.id, " /ME   waves 🌍 ")
            repository.sendDirectMessage(member.id, " /ME   waves 🌍 ")
            assertEquals(listOf(Triple(room.name, "waves 🌍", true)), transport.sentRoomMessages)
            assertEquals(listOf(member.address to "waves 🌍"), transport.sentDirectMessages)
        } finally {
            repository.close()
        }
    }

    @Test
    fun connectionsUseSavedNicknamesAndChangesReconnectTheActiveHub() = runBlocking {
        val hub = ChatNetwork("hub", "Mesh", "0123456789abcdef0123456789abcdef")
        val store = MemoryChatStateStore(ChatStateCodec.encode(PersistedChatState(
            previewMode = false, networks = listOf(hub), globalNickname = "Global operator",
        )))
        val transport = FakeReticulumTransport().apply { state.value = ReticulumConnectionState.Disconnected }
        val repository = ReticulumChatRepository(LocalPreviewChatRepository(), transport, store)
        try {
            repository.connectToHub(ReticulumConnectionConfig(hub.hubDestination!!, "Starling", hub.id))
            assertEquals("Global operator", transport.connections.value.single().displayName)
            repository.updateNetworkNickname(hub.id, "Hub operator")
            assertEquals("Hub operator", transport.connections.first { it.size == 2 }.last().displayName)
            repository.saveGlobalNickname("Updated global")
            assertEquals(2, transport.connections.value.size)
            repository.updateNetworkNickname(hub.id, "   ")
            assertEquals("Updated global", transport.connections.first { it.size == 3 }.last().displayName)
            repository.saveGlobalNickname("Current global")
            assertEquals("Current global", transport.connections.first { it.size == 4 }.last().displayName)
        } finally {
            repository.close()
        }
    }

    @Test
    fun identityDeletionClearsCachedSelfWithoutDeletingHistory() = runBlocking {
        val hash = "0123456789abcdef0123456789abcdef"
        val store = MemoryChatStateStore(ChatStateCodec.encode(PersistedChatState(
            previewMode = false,
            localIdentityHash = hash,
            members = listOf(ChatMember(hash, "Me", isSelf = true)),
            roomMessages = mapOf("room" to listOf(ChatMessage("message", "Me", "Saved history", "12:00", isMine = true))),
        )))
        val repository = ReticulumChatRepository(LocalPreviewChatRepository(), FakeReticulumTransport(), store)
        repository.updateManagedIdentity(null)
        repository.close()
        val state = ChatStateCodec.decode(store.value!!)!!
        assertNull(state.localIdentityHash)
        assertTrue(state.members.none { it.isSelf })
        assertEquals("Saved history", state.roomMessages["room"]?.single()?.text)
    }

    @Test
    fun staleRuntimeEventsCannotRestoreADeletedIdentity() = runBlocking {
        val transport = FakeReticulumTransport()
        val store = MemoryChatStateStore(ChatStateCodec.encode(PersistedChatState(previewMode = false)))
        val repository = ReticulumChatRepository(LocalPreviewChatRepository(), transport, store)
        repository.updateManagedIdentity(null)
        transport.events.subscriptionCount.first { it > 0 }
        transport.events.emit(ReticulumEvent.LocalIdentityChanged("0123456789abcdef0123456789abcdef"))
        transport.events.emit(ReticulumEvent.LocalMemberChanged(ChatMember("old-self", "Old self", isSelf = true)))
        // Wait until the event collector processes an error sent after both stale events.
        transport.events.emit(ReticulumEvent.Error("processed"))
        repository.lastError.first { it == "processed" }
        repository.close()
        val state = ChatStateCodec.decode(store.value!!)!!
        assertNull(state.localIdentityHash)
        assertTrue(state.members.none { it.isSelf })
    }

    @Test
    fun nicknameByteLimitNeverSplitsASurrogatePair() {
        assertEquals("a".repeat(30), normalizeStarlingNickname("a".repeat(30) + "🌍"))
        assertEquals("a".repeat(28) + "🌍", normalizeStarlingNickname("a".repeat(28) + "🌍"))
    }
}

private class MemoryChatStateStore(var value: String? = null) : ChatStateStore {
    override fun read(): String? = value
    override fun write(value: String) {
        this.value = value
    }
}

private class FakeReticulumTransport : ReticulumTransport {
    override val state = MutableStateFlow<ReticulumConnectionState>(ReticulumConnectionState.Connected("Private hub"))
    override val nodeReady = MutableStateFlow(true)
    override val localDeliveryAddress = MutableStateFlow<String?>("fedcba9876543210fedcba9876543210")
    override val events = MutableSharedFlow<ReticulumEvent>(replay = 1)
    val sentRoomMessages = mutableListOf<Triple<String, String, Boolean>>()
    val sentDirectMessages = mutableListOf<Pair<String, String>>()
    val roomMessagesSent: Int get() = sentRoomMessages.size
    val directMessagesSent: Int get() = sentDirectMessages.size
    val connections = MutableStateFlow<List<ReticulumConnectionConfig>>(emptyList())

    override suspend fun connect(config: ReticulumConnectionConfig) {
        connections.value = connections.value + config
        state.value = ReticulumConnectionState.Connecting
    }
    override suspend fun disconnect() {
        state.value = ReticulumConnectionState.Disconnected
    }
    override suspend fun joinRoom(roomId: String) = Unit
    override suspend fun leaveRoom(roomId: String) = Unit
    override suspend fun sendRoomMessage(roomId: String, body: String, isAction: Boolean) {
        sentRoomMessages += Triple(roomId, body, isAction)
    }
    override suspend fun sendDirectMessage(address: String, body: String) {
        sentDirectMessages += address to body
    }
    override suspend fun setLocalDisplayName(displayName: String) = Unit
}
