package dev.starling

/** Platform-backed storage for the shared chat repository's durable state. */
interface ChatStateStore {
    fun read(): String?
    fun write(value: String)
}

data class PersistedChatState(
    val networks: List<ChatNetwork> = emptyList(),
    val rooms: List<ChatRoom> = emptyList(),
    val members: List<ChatMember> = emptyList(),
    val contacts: Set<String> = emptySet(),
    val blockedMembers: Set<String> = emptySet(),
    val contactRequests: List<ChatContactRequest> = emptyList(),
    val roomMessages: Map<String, List<ChatMessage>> = emptyMap(),
    val directMessages: Map<String, List<ChatMessage>> = emptyMap(),
)

/** Small versioned text codec to avoid platform-specific serialization APIs. */
object ChatStateCodec {
    private const val HEADER = "STARLING_CHAT_STATE_V3"
    private val LEGACY_HEADERS = setOf("STARLING_CHAT_STATE_V1", "STARLING_CHAT_STATE_V2")

    fun encode(state: PersistedChatState): String = buildString {
        appendLine(HEADER)
        state.networks.forEach { network ->
            appendRecord("N", network.id, network.name, network.hubDestination.orEmpty())
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

        while (lines.hasNext()) {
            val fields = lines.next().split('\t')
            val decoded = fields.drop(1).map(::decodeField)
            when (fields.firstOrNull()) {
                "N" -> if (decoded.size >= 3 && decoded[0].isNotBlank() && decoded[2].isNotBlank()) {
                    val network = ChatNetwork(decoded[0], decoded[1], decoded[2])
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
                    val message = ChatMessage(
                        id = decoded[1],
                        author = decoded[2],
                        text = decoded[3],
                        time = decoded[4],
                        isMine = decoded[5] == "1",
                        isAction = decoded[6] == "1",
                        deliveryState = decoded[7].ifBlank { null },
                        authorId = decoded.getOrNull(8)?.ifBlank { null },
                    )
                    val bucket = if (fields[0] == "R") rooms else direct
                    bucket.getOrPut(decoded[0]) { mutableListOf() }.add(message)
                }
            }
        }
        return PersistedChatState(
            networks = networks.values.toList(),
            rooms = configuredRooms.values.toList(),
            members = members.values.toList(),
            contacts = contacts,
            blockedMembers = blocked,
            contactRequests = requests.values.toList(),
            roomMessages = rooms,
            directMessages = direct,
        )
    }

    private fun StringBuilder.appendMessage(kind: String, ownerId: String, message: ChatMessage) {
        appendRecord(
            kind, ownerId, message.id, message.author, message.text, message.time,
            message.isMine.bit(), message.isAction.bit(), message.deliveryState.orEmpty(), message.authorId.orEmpty(),
        )
    }

    private fun StringBuilder.appendRecord(kind: String, vararg values: String) {
        append(kind)
        values.forEach { append('\t').append(encodeField(it)) }
        append('\n')
    }

    private fun Boolean.bit(): String = if (this) "1" else "0"

    private fun encodeField(value: String): String {
        val bytes = value.encodeToByteArray()
        val result = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index++].toInt() and 0xff
            val hasSecond = index < bytes.size
            val second = if (hasSecond) bytes[index++].toInt() and 0xff else 0
            val hasThird = index < bytes.size
            val third = if (hasThird) bytes[index++].toInt() and 0xff else 0
            result.append(BASE64[(first ushr 2) and 0x3f])
            result.append(BASE64[((first shl 4) or (second ushr 4)) and 0x3f])
            result.append(if (hasSecond) BASE64[((second shl 2) or (third ushr 6)) and 0x3f] else '=')
            result.append(if (hasThird) BASE64[third and 0x3f] else '=')
        }
        return result.toString()
    }

    private fun decodeField(value: String): String {
        val bytes = ByteArray(value.length * 3 / 4)
        var outputIndex = 0
        var buffer = 0
        var bits = 0
        for (character in value) {
            if (character == '=') break
            val digit = BASE64.indexOf(character)
            if (digit < 0) return ""
            buffer = (buffer shl 6) or digit
            bits += 6
            if (bits >= 8) {
                bits -= 8
                if (outputIndex < bytes.size) bytes[outputIndex++] = ((buffer shr bits) and 0xff).toByte()
            }
        }
        return bytes.copyOf(outputIndex).decodeToString(throwOnInvalidSequence = false)
    }

    private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
}
