package dev.starling

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

data class ChatRoom(
    val id: String,
    val networkId: String,
    val name: String,
    val topic: String,
    val members: Int,
)

data class ChatNetwork(
    val id: String,
    val name: String,
    val hubDestination: String? = null,
    val isPreview: Boolean = false,
    val rrcNickname: String? = null,
)

enum class ChatSystemEventKind {
    JOIN,
    MODE,
    NICK,
    LEAVE,
    NOTICE,
    ERROR,
    COMMAND_ERROR,
    HELP,
    OTHER,
}

data class ChatSystemEvent(
    val kind: ChatSystemEventKind,
    val text: String,
    val time: String,
)

data class ChatMessage(
    val id: String,
    val author: String,
    val text: String,
    val time: String,
    val isMine: Boolean = false,
    val isAction: Boolean = false,
    val deliveryState: String? = null,
    val authorId: String? = null,
    val isSystem: Boolean = false,
    val systemEvents: List<ChatSystemEvent> = emptyList(),
)

internal fun collapseRepeatedSystemMessage(
    history: List<ChatMessage>,
    message: ChatMessage,
): List<ChatMessage> {
    if (history.any { it.id == message.id }) return history
    if (!message.isSystem) return history + message

    val newEvents = message.systemEvents.ifEmpty {
        listOf(ChatSystemEvent(ChatSystemEventKind.OTHER, message.text, message.time))
    }
    val isStandaloneSystemMessage = newEvents.any {
        it.kind == ChatSystemEventKind.ERROR ||
            it.kind == ChatSystemEventKind.COMMAND_ERROR ||
            it.kind == ChatSystemEventKind.HELP
    }
    if (isStandaloneSystemMessage) return history + message.copy(systemEvents = newEvents)

    val previous = history.lastOrNull()?.takeIf { prior ->
        prior.isSystem && prior.systemEvents.none {
            it.kind == ChatSystemEventKind.ERROR ||
                it.kind == ChatSystemEventKind.COMMAND_ERROR ||
                it.kind == ChatSystemEventKind.HELP
        }
    }
    if (previous == null) {
        return history + message.copy(
            systemEvents = newEvents,
            text = summarizeSystemEvents(newEvents),
        )
    }

    val groupedEvents = previous.systemEvents.ifEmpty {
        listOf(ChatSystemEvent(ChatSystemEventKind.OTHER, previous.text, previous.time))
    } + newEvents
    return history.dropLast(1) + previous.copy(
        text = summarizeSystemEvents(groupedEvents),
        time = message.time.ifBlank { previous.time },
        systemEvents = groupedEvents,
    )
}

internal fun summarizeSystemEvents(events: List<ChatSystemEvent>): String {
    if (events.size == 1) return events.single().text

    val counts = events.groupingBy(ChatSystemEvent::kind).eachCount()
    val summaries = listOf(
        counts[ChatSystemEventKind.JOIN]?.let { it.eventCount("user", "users", "has joined", "have joined") },
        counts[ChatSystemEventKind.MODE]?.let { it.eventCount("mode", "modes", "was set", "were set") },
        counts[ChatSystemEventKind.NICK]?.let { it.eventCount("user", "users", "has changed nick", "have changed nick") },
        counts[ChatSystemEventKind.LEAVE]?.let { it.eventCount("user", "users", "has left", "have left") },
        counts[ChatSystemEventKind.NOTICE]?.let { it.eventCount("server notice", "server notices") },
        counts[ChatSystemEventKind.ERROR]?.let { it.eventCount("hub error", "hub errors") },
        counts[ChatSystemEventKind.COMMAND_ERROR]?.let { it.eventCount("command error", "command errors") },
        counts[ChatSystemEventKind.HELP]?.let { it.eventCount("help message", "help messages") },
        counts[ChatSystemEventKind.OTHER]?.let { it.eventCount("system event", "system events") },
    ).filterNotNull()
    if (summaries.isEmpty()) return "${events.size} system events"
    if (summaries.size == 1) return summaries.single()
    return summaries.dropLast(1).joinToString(", ") + ", and " + summaries.last()
}

private fun Int.eventCount(
    singular: String,
    plural: String,
    singularVerb: String? = null,
    pluralVerb: String? = null,
): String {
    val noun = if (this == 1) singular else plural
    val verb = if (this == 1) singularVerb else pluralVerb
    return "$this $noun" + (verb?.let { " $it" } ?: "")
}

/** Boundary between shared chat UI and either local preview or a live session. */
interface ChatRepository {
    val networks: StateFlow<List<ChatNetwork>>
    val rooms: StateFlow<List<ChatRoom>>
    val previewMode: StateFlow<Boolean>
    val knownMembers: StateFlow<List<ChatMember>>
    val contactMemberIds: StateFlow<Set<String>>
    val blockedMemberIds: StateFlow<Set<String>>
    val contactRequests: StateFlow<List<ChatContactRequest>>
    fun messages(roomId: String): Flow<List<ChatMessage>>
    fun directMessages(memberId: String): Flow<List<ChatMessage>>
    fun members(roomId: String): Flow<List<ChatMember>>
    fun sharedRooms(memberId: String): List<ChatRoom>
    fun addNetwork(name: String, hubDestination: String): ChatNetwork
    fun restoreNetwork(network: ChatNetwork)
    fun restoreRoom(room: ChatRoom)
    fun removeRoom(roomId: String)
    fun updateNetworkNickname(networkId: String, nickname: String?)
    fun setPreviewMode(enabled: Boolean)
    fun addRoom(networkId: String, name: String, topic: String): ChatRoom
    fun addContact(member: ChatMember)
    fun toggleContact(memberId: String)
    fun acceptContactRequest(requestId: String)
    fun ignoreContactRequest(requestId: String)
    fun blockContactRequest(requestId: String)
    suspend fun send(roomId: String, text: String)
    suspend fun sendDirectMessage(memberId: String, text: String)
}

class LocalPreviewChatRepository : ChatRepository {
    private val allNetworks = MutableStateFlow(
        listOf(
            ChatNetwork("testnet", "Reticulum Testnet", isPreview = true),
            ChatNetwork("local-mesh", "Local Mesh", isPreview = true),
        ),
    )
    private val _previewMode = MutableStateFlow(true)
    override val previewMode: StateFlow<Boolean> = _previewMode.asStateFlow()
    private val _networks = MutableStateFlow(allNetworks.value)
    override val networks: StateFlow<List<ChatNetwork>> = _networks.asStateFlow()

    private val allRooms = MutableStateFlow(
        listOf(
            ChatRoom("general", "testnet", "general", "A little room for everyone", 4),
            ChatRoom("reticulum", "testnet", "reticulum", "Reticulum, RRC, and the mesh", 4),
            ChatRoom("field-notes", "local-mesh", "field-notes", "Signals from out in the field", 3),
        ),
    )
    private val _rooms = MutableStateFlow(allRooms.value)
    override val rooms: StateFlow<List<ChatRoom>> = _rooms.asStateFlow()

    private val _knownMembers = MutableStateFlow(
        listOf(
            ChatMember("moss", "moss"),
            ChatMember("juniper", "juniper"),
            ChatMember("birch", "birch"),
            ChatMember("quill", "quill"),
            ChatMember("you", "you", isSelf = true),
        ),
    )
    override val knownMembers: StateFlow<List<ChatMember>> = _knownMembers.asStateFlow()
    private val knownMembersById get() = _knownMembers.value.associateBy { it.id }

    private val membersByRoom = mapOf(
        "general" to listOf("moss", "juniper", "birch", "you"),
        "reticulum" to listOf("birch", "moss", "quill", "you"),
        "field-notes" to listOf("juniper", "moss", "you"),
    )

    private val _contactMemberIds = MutableStateFlow<Set<String>>(emptySet())
    override val contactMemberIds: StateFlow<Set<String>> = _contactMemberIds.asStateFlow()
    private val _blockedMemberIds = MutableStateFlow<Set<String>>(emptySet())
    override val blockedMemberIds: StateFlow<Set<String>> = _blockedMemberIds.asStateFlow()

    private val allContactRequests = MutableStateFlow(
        listOf(
            ChatContactRequest(
                id = "request-sparrow",
                sender = ChatMember("sparrow", "sparrow", address = "a1b2c3d4e5f6"),
                message = "Hi! We were both listening on the local mesh. Mind if I add you?",
                time = "now",
                isPreview = true,
            ),
        ),
    )
    private val _contactRequests = MutableStateFlow(allContactRequests.value)
    override val contactRequests: StateFlow<List<ChatContactRequest>> = _contactRequests.asStateFlow()

    private val allMessagesByRoom = MutableStateFlow(
        mapOf(
            "general" to listOf(
                ChatMessage("g1", "moss", "Morning all. The local mesh is unusually lively today.", "09:38"),
                ChatMessage("g2", "juniper", "I can see three new nodes from the hilltop.", "09:41"),
                ChatMessage("g3", "you", "Nice! I’ll try to get Starling talking to a hub next.", "09:44", isMine = true),
                ChatMessage("g4", "moss", "That would be lovely. Keeping the client simple is a good start.", "09:46"),
            ),
            "field-notes" to listOf(
                ChatMessage("f1", "juniper", "Packet reception is much better near the north window.", "08:12"),
                ChatMessage("f2", "moss", "Noted. I’ll bring the RNode over this afternoon.", "08:19"),
            ),
            "reticulum" to listOf(
                ChatMessage("r1", "birch", "Has anyone tried the latest RRC hub build?", "10:02"),
                ChatMessage("r2", "you", "I’m starting with the client UI, then I’ll tackle connectivity.", "10:05", isMine = true),
            ),
        ),
    )
    private val messagesByRoom = MutableStateFlow(allMessagesByRoom.value)
    private val directMessagesByMember = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())

    private var nextId = 0

    override fun messages(roomId: String): Flow<List<ChatMessage>> =
        messagesByRoom.asStateFlow().map { it[roomId].orEmpty() }

    override fun directMessages(memberId: String): Flow<List<ChatMessage>> = combine(
        directMessagesByMember.asStateFlow(),
        previewMode,
    ) { messages, enabled -> if (enabled) messages[memberId].orEmpty() else emptyList() }

    override fun members(roomId: String): Flow<List<ChatMember>> = flowOf(
        membersByRoom[roomId].orEmpty().mapNotNull(knownMembersById::get),
    )

    override fun sharedRooms(memberId: String): List<ChatRoom> =
        _rooms.value.filter { memberId in membersByRoom[it.id].orEmpty() }

    override fun addNetwork(name: String, hubDestination: String): ChatNetwork {
        require(name.isNotBlank()) { "Enter a network name." }
        if (_previewMode.value) {
            val cleanName = name.trim()
            allNetworks.value.firstOrNull { it.isPreview && it.name.equals(cleanName, ignoreCase = true) }?.let { return it }
            val network = ChatNetwork("preview-local-${nextId++}", cleanName, isPreview = true)
            allNetworks.value = allNetworks.value + network
            refreshVisibleConfiguration()
            return network
        }

        val normalizedDestination = hubDestination.trim().lowercase()
        require(normalizedDestination.length == 32 && normalizedDestination.all { it in "0123456789abcdef" }) {
            "RRC hub destination must be a 32-character hexadecimal hash."
        }
        val existing = allNetworks.value.firstOrNull { it.hubDestination == normalizedDestination }
        if (existing != null) return existing
        val network = ChatNetwork("rrc-$normalizedDestination", name.trim(), normalizedDestination)
        allNetworks.value = allNetworks.value + network
        refreshVisibleConfiguration()
        return network
    }

    override fun restoreNetwork(network: ChatNetwork) {
        val destination = network.hubDestination?.trim()?.lowercase().orEmpty()
        if (destination.length == 32 && destination.all { it in "0123456789abcdef" } &&
            allNetworks.value.none { it.id == network.id || it.hubDestination == destination }
        ) {
            allNetworks.value = allNetworks.value + network.copy(hubDestination = destination, isPreview = false)
            refreshVisibleConfiguration()
        }
    }

    override fun restoreRoom(room: ChatRoom) {
        val normalizedRoom = room.copy(name = normalizeChannelName(room.name))
        if (allRooms.value.none { it.id == room.id }) {
            allRooms.value = allRooms.value + normalizedRoom
            refreshVisibleConfiguration()
        }
    }

    override fun removeRoom(roomId: String) {
        allRooms.value = allRooms.value.filterNot { it.id == roomId }
        allMessagesByRoom.value = allMessagesByRoom.value - roomId
        refreshVisibleConfiguration()
    }

    override fun updateNetworkNickname(networkId: String, nickname: String?) {
        if (allNetworks.value.none { it.id == networkId }) return
        val normalized = nickname?.trim()?.takeIf(String::isNotEmpty)?.let(::normalizeStarlingNickname)
        allNetworks.value = allNetworks.value.map { network ->
            if (network.id == networkId) network.copy(rrcNickname = normalized) else network
        }
        refreshVisibleConfiguration()
    }

    override fun setPreviewMode(enabled: Boolean) {
        if (_previewMode.value == enabled) return
        _previewMode.value = enabled
        refreshVisibleConfiguration()
        refreshVisibleRequests()
    }

    override fun addRoom(networkId: String, name: String, topic: String): ChatRoom {
        require(allNetworks.value.any { it.id == networkId }) { "Choose an existing network first." }
        val cleanName = normalizeChannelName(name)
        require(cleanName.isNotBlank()) { "Enter a channel name." }
        val existing = allRooms.value.firstOrNull {
            it.networkId == networkId && it.name.equals(cleanName, ignoreCase = true)
        }
        if (existing != null) return existing
        val room = ChatRoom("$networkId:room:$cleanName", networkId, cleanName, topic.trim(), 0)
        allRooms.value = allRooms.value + room
        refreshVisibleConfiguration()
        return room
    }

    override fun addContact(member: ChatMember) {
        if (member.isSelf || member.id.isBlank()) return
        if (knownMembersById[member.id] == null) {
            _knownMembers.value = _knownMembers.value + member
        }
        _contactMemberIds.value = _contactMemberIds.value + member.id
    }

    override fun toggleContact(memberId: String) {
        if (knownMembersById[memberId]?.isSelf == true) return
        _contactMemberIds.value = if (memberId in _contactMemberIds.value) {
            _contactMemberIds.value - memberId
        } else {
            _contactMemberIds.value + memberId
        }
    }

    override fun acceptContactRequest(requestId: String) {
        val request = allContactRequests.value.firstOrNull { it.id == requestId } ?: return
        addContact(request.sender)
        val incomingMessage = ChatMessage(
            id = request.id,
            author = request.sender.id,
            text = request.message,
            time = request.time,
        )
        directMessagesByMember.value = directMessagesByMember.value +
            (request.sender.id to (directMessagesByMember.value[request.sender.id].orEmpty() + incomingMessage))
        allContactRequests.value = allContactRequests.value.filterNot { it.id == requestId }
        refreshVisibleRequests()
    }

    override fun ignoreContactRequest(requestId: String) {
        allContactRequests.value = allContactRequests.value.filterNot { it.id == requestId }
        refreshVisibleRequests()
    }

    override fun blockContactRequest(requestId: String) {
        val request = allContactRequests.value.firstOrNull { it.id == requestId } ?: return
        _contactMemberIds.value = _contactMemberIds.value - request.sender.id
        _blockedMemberIds.value = _blockedMemberIds.value + request.sender.id
        allContactRequests.value = allContactRequests.value.filterNot { it.id == requestId }
        refreshVisibleRequests()
    }

    override suspend fun send(roomId: String, text: String) {
        val outgoing = parseOutgoingChatMessage(text) ?: return

        if (isLocalHelpCommand(outgoing.body)) {
            val help = ChatMessage(
                id = "local-system-${nextId++}",
                author = "System",
                text = RRC_COMMAND_HELP,
                time = "now",
                isSystem = true,
                systemEvents = listOf(ChatSystemEvent(ChatSystemEventKind.HELP, RRC_COMMAND_HELP, "now")),
            )
            allMessagesByRoom.value = allMessagesByRoom.value +
                (roomId to collapseRepeatedSystemMessage(allMessagesByRoom.value[roomId].orEmpty(), help))
            refreshVisibleMessages()
            return
        }

        val message = ChatMessage(
            id = "local-${nextId++}",
            author = "you",
            text = outgoing.body,
            time = "now",
            isMine = true,
            isAction = outgoing.isAction,
        )
        allMessagesByRoom.value = allMessagesByRoom.value +
            (roomId to (allMessagesByRoom.value[roomId].orEmpty() + message))
        refreshVisibleMessages()
    }

    override suspend fun sendDirectMessage(memberId: String, text: String) {
        val outgoing = parseOutgoingChatMessage(text) ?: return

        val message = ChatMessage(
            id = "local-dm-${nextId++}",
            author = "you",
            text = outgoing.body,
            time = "now",
            isMine = true,
            isAction = outgoing.isAction,
        )
        directMessagesByMember.value = directMessagesByMember.value +
            (memberId to (directMessagesByMember.value[memberId].orEmpty() + message))
    }

    private fun refreshVisibleConfiguration() {
        val visibleNetworks = allNetworks.value.filter { it.isPreview == _previewMode.value }
        val visibleNetworkIds = visibleNetworks.mapTo(mutableSetOf()) { it.id }
        _networks.value = visibleNetworks
        _rooms.value = allRooms.value.filter { it.networkId in visibleNetworkIds }
        refreshVisibleMessages()
    }

    private fun refreshVisibleMessages() {
        val previewNetworkIds = allNetworks.value.filter { it.isPreview }.mapTo(mutableSetOf()) { it.id }
        val previewRoomIds = allRooms.value.filter { it.networkId in previewNetworkIds }.mapTo(mutableSetOf()) { it.id }
        messagesByRoom.value = if (_previewMode.value) allMessagesByRoom.value.filterKeys { it in previewRoomIds }
            else allMessagesByRoom.value.filterKeys { it !in previewRoomIds }
    }

    private fun refreshVisibleRequests() {
        _contactRequests.value = allContactRequests.value.filter { it.isPreview == _previewMode.value }
    }

    internal fun persistedNetworks(): List<ChatNetwork> =
        allNetworks.value.filter { !it.isPreview && !it.hubDestination.isNullOrBlank() }

    internal fun persistedRooms(): List<ChatRoom> = allRooms.value.filter { room ->
        allNetworks.value.any { it.id == room.networkId && !it.isPreview }
    }

    internal fun isPreviewMember(memberId: String): Boolean =
        _knownMembers.value.any { it.id == memberId } || allContactRequests.value.any { it.isPreview && it.sender.id == memberId }
}

internal fun normalizeChannelName(name: String): String = name.trim().removePrefix("#").trim().lowercase()

internal fun normalizeStarlingNickname(value: String): String {
    var nickname = value.trim()
    if (nickname.isEmpty()) return "Starling"
    while (nickname.encodeToByteArray().size > 32) {
        val count = if (nickname.length >= 2 && nickname.last().isLowSurrogate() && nickname[nickname.lastIndex - 1].isHighSurrogate()) 2 else 1
        nickname = nickname.dropLast(count)
    }
    return nickname.ifBlank { "Starling" }
}

data class ChatMember(
    val id: String,
    val displayName: String,
    val isSelf: Boolean = false,
    val address: String? = null,
)

data class ChatContactRequest(
    val id: String,
    val sender: ChatMember,
    val message: String,
    val time: String,
    val isPreview: Boolean = false,
)
