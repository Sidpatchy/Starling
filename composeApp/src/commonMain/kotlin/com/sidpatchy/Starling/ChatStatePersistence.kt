package com.sidpatchy.Starling

/** Platform-backed storage for the shared chat repository's durable state. */
interface ChatStateStore {
    fun read(): String?
    fun write(value: String)
}

data class PersistedChatState(
    val previewMode: Boolean = true,
    val networks: List<ChatNetwork> = emptyList(),
    val rooms: List<ChatRoom> = emptyList(),
    val members: List<ChatMember> = emptyList(),
    val contacts: Set<String> = emptySet(),
    val blockedMembers: Set<String> = emptySet(),
    val contactRequests: List<ChatContactRequest> = emptyList(),
    val roomMessages: Map<String, List<ChatMessage>> = emptyMap(),
    val directMessages: Map<String, List<ChatMessage>> = emptyMap(),
    val nodeConfig: ReticulumNodeConfig? = null,
    val preferredHubNetworkId: String? = null,
    val activeHubNetworkId: String? = null,
    val localIdentityHash: String? = null,
    val globalNickname: String = "Starling",
)

/** Small versioned text codec to avoid platform-specific serialization APIs. */
object ChatStateCodec {
    private const val HEADER = "STARLING_CHAT_STATE_V9"
    private val LEGACY_HEADERS = setOf(
        "STARLING_CHAT_STATE_V1", "STARLING_CHAT_STATE_V2", "STARLING_CHAT_STATE_V3", "STARLING_CHAT_STATE_V4",
        "STARLING_CHAT_STATE_V5", "STARLING_CHAT_STATE_V6", "STARLING_CHAT_STATE_V7", "STARLING_CHAT_STATE_V8",
    )

    fun encode(state: PersistedChatState): String = buildString {
        appendLine(HEADER)
        appendRecord("P", state.previewMode.bit())
        state.preferredHubNetworkId?.let { appendRecord("U", it) }
        state.activeHubNetworkId?.let { appendRecord("L", it) }
        state.localIdentityHash?.let { appendRecord("J", it) }
        appendRecord("G", state.globalNickname)
        state.nodeConfig?.let { config ->
            appendRecord("V", config.mode.name)
            config.tcpPeers.forEach { peer ->
                appendRecord("T", peer.host, peer.port.toString(), peer.ifacNetworkName.orEmpty(), peer.ifacPassphrase.orEmpty())
            }
            config.rnodeInterfaces.forEach { radio ->
                appendRecord(
                    "I", radio.port, radio.frequencyHz.toString(), radio.bandwidthHz.toString(), radio.txPowerDbm.toString(),
                    radio.spreadingFactor.toString(), radio.codingRate.toString(),
                )
            }
        }
        state.networks.forEach { network ->
            appendRecord("N", network.id, network.name, network.hubDestination.orEmpty(), network.rrcNickname.orEmpty())
        }
        state.rooms.forEach { room ->
            appendRecord("H", room.id, room.networkId, room.name, room.topic, room.members.toString())
        }
        state.members.forEach { member ->
            appendRecord("M", member.id, member.displayName, member.isSelf.bit(), member.address.orEmpty())
        }
        state.contacts.forEach { appendRecord("C", it) }
        state.blockedMembers.forEach { appendRecord("B", it) }
        state.contactRequests.forEach { request ->
            appendRecord(
                "Q", request.id, request.sender.id, request.sender.displayName,
                request.sender.isSelf.bit(), request.sender.address.orEmpty(), request.message, request.time,
                request.isPreview.bit(),
            )
        }
        state.roomMessages.forEach { (roomId, messages) ->
            messages.forEach { appendMessage("R", roomId, it) }
        }
        state.directMessages.forEach { (memberId, messages) ->
            messages.forEach { appendMessage("D", memberId, it) }
        }
    }

    fun decode(value: String): PersistedChatState? {
        val lines = value.lineSequence().filter { it.isNotBlank() }.iterator()
        if (!lines.hasNext()) return null
        val header = lines.next()
        if (header != HEADER && header !in LEGACY_HEADERS) return null
        val networks = linkedMapOf<String, ChatNetwork>()
        val configuredRooms = linkedMapOf<String, ChatRoom>()
        val members = linkedMapOf<String, ChatMember>()
        val contacts = linkedSetOf<String>()
        val blocked = linkedSetOf<String>()
        val requests = linkedMapOf<String, ChatContactRequest>()
        val rooms = linkedMapOf<String, MutableList<ChatMessage>>()
        val direct = linkedMapOf<String, MutableList<ChatMessage>>()
        var nodeMode: ReticulumRuntimeMode? = null
        val tcpPeers = mutableListOf<ReticulumTcpPeer>()
        val rnodeInterfaces = mutableListOf<ReticulumRNodeInterface>()
        var preferredHubNetworkId: String? = null
        var activeHubNetworkId: String? = null
        var localIdentityHash: String? = null
        var globalNickname = "Starling"
        var previewMode = true

        while (lines.hasNext()) {
            val fields = lines.next().split('\t')
            val decoded = fields.drop(1).map { Base64TextCodec.decode(it) }
            when (fields.firstOrNull()) {
                "P" -> previewMode = decoded.firstOrNull() == "1"
                "U" -> preferredHubNetworkId = decoded.firstOrNull()?.ifBlank { null }
                "L" -> activeHubNetworkId = decoded.firstOrNull()?.ifBlank { null }
                "J" -> localIdentityHash = decoded.firstOrNull()?.ifBlank { null }
                "G" -> globalNickname = decoded.firstOrNull()?.let(::normalizeStarlingNickname) ?: "Starling"
                "V" -> nodeMode = decoded.firstOrNull()?.let { name ->
                    runCatching { ReticulumRuntimeMode.valueOf(name) }.getOrNull()
                }
                "T" -> if (decoded.size >= 4) {
                    tcpPeers += ReticulumTcpPeer(
                        host = decoded[0],
                        port = decoded[1].toIntOrNull() ?: 0,
                        ifacNetworkName = decoded[2].ifBlank { null },
                        ifacPassphrase = decoded[3].ifBlank { null },
                    )
                }
                "I" -> if (decoded.size >= 6) {
                    rnodeInterfaces += ReticulumRNodeInterface(
                        port = decoded[0],
                        frequencyHz = decoded[1].toLongOrNull() ?: 0L,
                        bandwidthHz = decoded[2].toIntOrNull() ?: 0,
                        txPowerDbm = decoded[3].toIntOrNull() ?: 0,
                        spreadingFactor = decoded[4].toIntOrNull() ?: 0,
                        codingRate = decoded[5].toIntOrNull() ?: 0,
                    )
                }
                "N" -> if (decoded.size >= 3 && decoded[0].isNotBlank() && decoded[2].isNotBlank()) {
                    val network = ChatNetwork(
                        id = decoded[0],
                        name = decoded[1],
                        hubDestination = decoded[2],
                        rrcNickname = decoded.getOrNull(3)?.trim()?.takeIf(String::isNotEmpty)?.let(::normalizeStarlingNickname),
                    )
                    networks[network.id] = network
                }
                "H" -> if (decoded.size >= 5 && decoded[0].isNotBlank() && decoded[1].isNotBlank()) {
                    val room = ChatRoom(decoded[0], decoded[1], decoded[2], decoded[3], decoded[4].toIntOrNull() ?: 0)
                    configuredRooms[room.id] = room
                }
                "M" -> if (decoded.size >= 4) {
                    val member = ChatMember(decoded[0], decoded[1], decoded[2] == "1", decoded[3].ifBlank { null })
                    members[member.id] = member
                }
                "C" -> decoded.firstOrNull()?.takeIf { it.isNotBlank() }?.let(contacts::add)
                "B" -> decoded.firstOrNull()?.takeIf { it.isNotBlank() }?.let(blocked::add)
                "Q" -> if (decoded.size >= 7) {
                    val request = ChatContactRequest(
                        id = decoded[0],
                        sender = ChatMember(decoded[1], decoded[2], decoded[3] == "1", decoded[4].ifBlank { null }),
                        message = decoded[5],
                        time = decoded[6],
                        isPreview = decoded.getOrNull(7) == "1",
                    )
                    requests[request.id] = request
                }
                "R", "D" -> if (decoded.size >= 8) {
                    val isSystem = decoded.getOrNull(9) == "1"
                    val systemEvents = when {
                        !isSystem -> emptyList()
                        header == HEADER -> {
                            val eventCount = decoded.getOrNull(10)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                            decoded.drop(11).chunked(3).take(eventCount).mapNotNull { event ->
                                if (event.size < 3) null else runCatching {
                                    ChatSystemEvent(ChatSystemEventKind.valueOf(event[0]), event[2], event[1])
                                }.getOrNull()
                            }
                        }
                        else -> List(decoded.getOrNull(10)?.toIntOrNull()?.coerceIn(1, 1000) ?: 1) {
                            val kind = if (decoded.getOrNull(8) == "client-help") {
                                ChatSystemEventKind.OTHER
                            } else {
                                ChatSystemEventKind.NOTICE
                            }
                            ChatSystemEvent(kind, decoded[3], decoded[4])
                        }
                    }
                    val messageText = if (header != HEADER && systemEvents.size > 1) {
                        summarizeSystemEvents(systemEvents)
                    } else {
                        decoded[3]
                    }
                    val message = ChatMessage(
                        id = decoded[1],
                        author = decoded[2],
                        text = messageText,
                        time = decoded[4],
                        isMine = decoded[5] == "1",
                        isAction = decoded[6] == "1",
                        deliveryState = decoded[7].ifBlank { null },
                        authorId = decoded.getOrNull(8)?.ifBlank { null },
                        isSystem = isSystem,
                        systemEvents = systemEvents,
                    )
                    val bucket = if (fields[0] == "R") rooms else direct
                    bucket.getOrPut(decoded[0]) { mutableListOf() }.add(message)
                }
            }
        }
        return PersistedChatState(
            previewMode = previewMode,
            networks = networks.values.toList(),
            rooms = configuredRooms.values.toList(),
            members = members.values.toList(),
            contacts = contacts,
            blockedMembers = blocked,
            contactRequests = requests.values.toList(),
            roomMessages = rooms,
            directMessages = direct,
            nodeConfig = nodeMode?.let { mode ->
                ReticulumNodeConfig(mode = mode, tcpPeers = tcpPeers, rnodeInterfaces = rnodeInterfaces)
            },
            preferredHubNetworkId = preferredHubNetworkId,
            activeHubNetworkId = activeHubNetworkId,
            localIdentityHash = localIdentityHash,
            globalNickname = globalNickname,
        )
    }

    private fun StringBuilder.appendMessage(kind: String, ownerId: String, message: ChatMessage) {
        val systemEvents = message.systemEvents.ifEmpty {
            if (message.isSystem) listOf(ChatSystemEvent(ChatSystemEventKind.OTHER, message.text, message.time)) else emptyList()
        }
        val eventFields = systemEvents.flatMap { event -> listOf(event.kind.name, event.time, event.text) }
        appendRecord(
            kind, ownerId, message.id, message.author, message.text, message.time,
            message.isMine.bit(), message.isAction.bit(), message.deliveryState.orEmpty(), message.authorId.orEmpty(),
            message.isSystem.bit(), systemEvents.size.toString(), *eventFields.toTypedArray(),
        )
    }

    private fun StringBuilder.appendRecord(kind: String, vararg values: String) {
        append(kind)
        values.forEach { append('\t').append(Base64TextCodec.encode(it)) }
        append('\n')
    }

    private fun Boolean.bit(): String = if (this) "1" else "0"
}
