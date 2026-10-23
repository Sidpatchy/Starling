package com.sidpatchy.Starling

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock

/**
 * Keeps the no-setup preview repository for local configuration, routes room
 * traffic through RRC and DMs through LXMF, and persists app-level chat state.
 */
class ReticulumChatRepository(
    private val preview: LocalPreviewChatRepository,
    private val transport: ReticulumTransport,
    private val stateStore: ChatStateStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ChatRepository by preview {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val joinMutex = Mutex()
    private val nicknameReconnectMutex = Mutex()
    private val persistenceRequests = Channel<Unit>(Channel.CONFLATED)
    private var persistenceJob: Job? = null
    private var systemMessageSequence = 0L
    private var pendingDirectMessageSequence = 0L
    private val lastServerCommandRoomId = MutableStateFlow<String?>(null)
    private val lastServerCommandName = MutableStateFlow<String?>(null)
    private val lastServerCommandText = MutableStateFlow<String?>(null)
    private val joinedRoomNames = mutableSetOf<String>()

    /** Messages typed while the hub is not reachable are kept here and flushed once connected. */
    private val MAX_QUEUED_SENDS = 32
    private val sendQueueMutex = Mutex()
    private var pendingSends = mutableListOf<PendingSend>()

    private data class PendingSend(val roomId: String, val body: String)

    private val restoredState = runCatching { stateStore.read()?.let(ChatStateCodec::decode) }.getOrNull()
    private val restoredPreviewMode = (restoredState?.previewMode ?: true).also(preview::setPreviewMode)
    private val _nodeConfig = MutableStateFlow(restoredState?.nodeConfig)
    val nodeConfig: StateFlow<ReticulumNodeConfig?> = _nodeConfig.asStateFlow()
    private val _uiSettings = MutableStateFlow(restoredState?.uiSettings ?: StarlingUiSettings())
    val uiSettings: StateFlow<StarlingUiSettings> = _uiSettings.asStateFlow()
    private val _globalNickname = MutableStateFlow(normalizeStarlingNickname(restoredState?.globalNickname ?: "Starling"))
    val globalNickname: StateFlow<String> = _globalNickname.asStateFlow()
    private val _preferredHubNetworkId = MutableStateFlow(restoredState?.preferredHubNetworkId)
    val preferredHubNetworkId: StateFlow<String?> = _preferredHubNetworkId.asStateFlow()
    private val _localIdentityHash = MutableStateFlow(restoredState?.localIdentityHash)
    private val managedIdentityLoaded = MutableStateFlow(false)
    val localIdentityHash: StateFlow<String?> = combine(
        _localIdentityHash,
        preview.previewMode,
    ) { identityHash, previewEnabled -> if (previewEnabled) null else identityHash }
        .stateIn(scope, SharingStarted.Eagerly, if (restoredPreviewMode) null else _localIdentityHash.value)
    private val liveMessages = MutableStateFlow(restoredState?.roomMessages.orEmpty())
    private val liveMembers = MutableStateFlow<Map<String, List<ChatMember>>>(emptyMap())
    private val cachedRoomMemberIds = MutableStateFlow(
        restoredState?.let { state ->
            (state.roomMemberIds.keys + state.roomMessages.keys).associateWith { roomId ->
                state.roomMemberIds[roomId].orEmpty() + state.roomMessages[roomId].orEmpty().mapNotNull { it.authorId }
            }.mapValues { (_, memberIds) -> memberIds.filterNot(preview::isPreviewMember).toSet() }
                .filterValues { it.isNotEmpty() }
        }.orEmpty(),
    )
    private val discoveredMembers = MutableStateFlow(
        mergeMembers(emptyList(), restoredState?.members.orEmpty().filterNot { preview.isPreviewMember(it.id) }),
    )
    private val localMember = MutableStateFlow(
        restoredState?.members?.firstOrNull { it.isSelf && !preview.isPreviewMember(it.id) },
    )
    private var pendingSelfNicknameChange: Pair<String, String>? = null
    private val pendingSelfNicknameRooms = mutableSetOf<String>()
    private val liveDirectMessages = MutableStateFlow(restoredState?.directMessages.orEmpty())
    private val _activeHubNetworkId = MutableStateFlow(
        restoredState?.activeHubNetworkId.takeUnless { restoredPreviewMode },
    )
    val activeHubNetworkId: StateFlow<String?> = combine(
        _activeHubNetworkId,
        preview.previewMode,
    ) { networkId, previewEnabled -> if (previewEnabled) null else networkId }
        .stateIn(scope, SharingStarted.Eagerly, null)
    private val liveContactMemberIds = MutableStateFlow(
        normalizeContactIds(
            restoredState?.contacts.orEmpty().filterNot(preview::isPreviewMember).toSet(),
            discoveredMembers.value,
        ),
    )
    private val liveBlockedMemberIds = MutableStateFlow(
        restoredState?.blockedMembers.orEmpty().filterNot(preview::isPreviewMember).toSet(),
    )
    private val _contactRequests = MutableStateFlow(
        restoredState?.contactRequests.orEmpty().filterNot { it.isPreview || preview.isPreviewMember(it.sender.id) },
    )
    private val messageByRequestId = mutableMapOf<String, LxmfDirectMessage>()
    private val _lastError = MutableStateFlow<String?>(null)
    val rrcError: StateFlow<String?> = combine(
        _lastError,
        preview.previewMode,
    ) { error, previewEnabled -> if (previewEnabled) null else error }
        .stateIn(scope, SharingStarted.Eagerly, null)
    /** Backwards-compatible name for the RRC/chat error channel. */
    val lastError: StateFlow<String?> = rrcError
    private val _directMessageError = MutableStateFlow<String?>(null)
    val directMessageError: StateFlow<String?> = combine(
        _directMessageError,
        preview.previewMode,
    ) { error, previewEnabled -> if (previewEnabled) null else error }
        .stateIn(scope, SharingStarted.Eagerly, null)
    val connectionState: StateFlow<ReticulumConnectionState> = combine(
        transport.state,
        preview.previewMode,
    ) { state, previewEnabled -> if (previewEnabled) ReticulumConnectionState.Disconnected else state }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            if (restoredPreviewMode) ReticulumConnectionState.Disconnected else transport.state.value,
        )
    val localDeliveryAddress: StateFlow<String?> = combine(
        transport.localDeliveryAddress,
        preview.previewMode,
    ) { address, previewEnabled -> if (previewEnabled) null else address }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override val knownMembers: StateFlow<List<ChatMember>> = combine(
        preview.knownMembers,
        discoveredMembers,
        localMember,
        preview.previewMode,
    ) { previewMembers, discovered, self, previewEnabled ->
        if (previewEnabled) previewMembers else mergeMembers(discovered, listOfNotNull(self))
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        if (restoredPreviewMode) preview.knownMembers.value else mergeMembers(discoveredMembers.value, listOfNotNull(localMember.value)),
    )
    override val contactMemberIds: StateFlow<Set<String>> = combine(
        preview.contactMemberIds,
        liveContactMemberIds,
        preview.previewMode,
    ) { previewContacts, liveContacts, previewEnabled -> if (previewEnabled) previewContacts else liveContacts }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            if (restoredPreviewMode) preview.contactMemberIds.value else liveContactMemberIds.value,
        )
    override val blockedMemberIds: StateFlow<Set<String>> = combine(
        preview.blockedMemberIds,
        liveBlockedMemberIds,
        preview.previewMode,
    ) { previewBlocked, liveBlocked, previewEnabled -> if (previewEnabled) previewBlocked else liveBlocked }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            if (restoredPreviewMode) preview.blockedMemberIds.value else liveBlockedMemberIds.value,
        )
    override val contactRequests: StateFlow<List<ChatContactRequest>> = combine(
        preview.contactRequests,
        _contactRequests,
        preview.previewMode,
    ) { previewRequests, liveRequests, previewEnabled ->
        if (previewEnabled) previewRequests else liveRequests.filterNot { it.isPreview }
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        if (restoredPreviewMode) preview.contactRequests.value else _contactRequests.value.filterNot { it.isPreview },
    )

    init {
        restoredState?.networks.orEmpty().forEach(preview::restoreNetwork)
        restoredState?.rooms.orEmpty().forEach(preview::restoreRoom)
        if (restoredPreviewMode && transport.state.value != ReticulumConnectionState.Disconnected) {
            scope.launch { runCatching { transport.disconnect() } }
        }
        persistenceJob = scope.launch {
            for (ignored in persistenceRequests) persistCurrentState()
        }
        scope.launch {
            connectionState.collect { state ->
                if (state is ReticulumConnectionState.Connected) {
                    if (_activeHubNetworkId.value == null) {
                        _preferredHubNetworkId.value?.let { _activeHubNetworkId.value = it }
                    }
                    joinConfiguredRooms()
                    scope.launch { flushQueuedSends() }
                } else if (state is ReticulumConnectionState.Disconnected || state is ReticulumConnectionState.Failed) {
                    lastServerCommandRoomId.value = null
                    lastServerCommandName.value = null
                    lastServerCommandText.value = null
                    pendingSelfNicknameChange = null
                    pendingSelfNicknameRooms.clear()
                    liveMembers.value = emptyMap()
                    joinMutex.withLock { joinedRoomNames.clear() }
                }
            }
        }
        scope.launch {
            combine(transport.nodeReady, _globalNickname, preview.previewMode) { nodeReady, nickname, previewEnabled ->
                Triple(nodeReady, nickname, previewEnabled)
            }.collect { (nodeReady, nickname, previewEnabled) ->
                if (nodeReady && !previewEnabled) {
                    runCatching { transport.setLocalDisplayName(nickname) }
                        .onFailure { _directMessageError.value = it.message ?: "Could not update the LXMF identity nickname." }
                }
            }
        }
        scope.launch {
            transport.events.collect { event ->
                if (preview.previewMode.value) return@collect
                when (event) {
                    is ReticulumEvent.LocalIdentityChanged -> {
                        val identityHash = event.identityHash.trim().lowercase()
                        if (managedIdentityLoaded.value && identityHash != _localIdentityHash.value) return@collect
                        if (identityHash.length == 32 && identityHash.all { it in "0123456789abcdef" } &&
                            _localIdentityHash.value != identityHash
                        ) {
                            _localIdentityHash.value = identityHash
                            persistState()
                        }
                    }
                    is ReticulumEvent.RoomJoined -> {
                        scope.launch { flushQueuedSends() }
                        joinMutex.withLock {
                            _activeHubNetworkId.value?.let { networkId ->
                                joinedRoomNames += "$networkId:${event.roomId.lowercase()}"
                                val room = preview.rooms.value.firstOrNull {
                                    it.networkId == networkId && it.name.equals(event.roomId, ignoreCase = true)
                                }
                                if (room != null) {
                                    localMember.value?.let {
                                        addLiveMember(room.id, it)
                                        cacheRoomMembers(room.id, listOf(it))
                                    }
                                    pendingSelfNicknameChange?.let { (oldName, newName) ->
                                        if (pendingSelfNicknameRooms.remove(room.id)) {
                                            appendSystemMessage(
                                                room,
                                                "$oldName is now known as $newName",
                                                localMember.value?.id,
                                                kind = ChatSystemEventKind.NICK,
                                            )
                                            if (pendingSelfNicknameRooms.isEmpty()) pendingSelfNicknameChange = null
                                        }
                                    }
                                    appendSystemMessage(room, "You joined #${room.name}", localMember.value?.id, kind = ChatSystemEventKind.JOIN)
                                }
                            }
                        }
                    }
                    is ReticulumEvent.RoomMessageReceived -> receive(event.message)
                    is ReticulumEvent.DirectMessage -> receiveDirect(event.message)
                    is ReticulumEvent.LocalMemberChanged -> {
                        if (managedIdentityLoaded.value && event.member.id != _localIdentityHash.value) return@collect
                        val self = event.member.copy(isSelf = true, address = localMember.value?.address)
                        val previousSelf = localMember.value
                        if (previousSelf?.id == self.id && previousSelf.displayName != self.displayName) {
                            pendingSelfNicknameChange = previousSelf.displayName to self.displayName
                            pendingSelfNicknameRooms.clear()
                            _activeHubNetworkId.value?.let { networkId ->
                                pendingSelfNicknameRooms += preview.rooms.value.filter { it.networkId == networkId }.map { it.id }
                            }
                            if (pendingSelfNicknameRooms.isEmpty()) pendingSelfNicknameChange = null
                        }
                        val changed = localMember.value != self
                        localMember.value = self
                        if (changed) discoveredMembers.value = mergeMembers(discoveredMembers.value, listOf(self))
                        joinMutex.withLock {
                            val networkId = _activeHubNetworkId.value
                            if (networkId != null) {
                                preview.rooms.value.filter {
                                    it.networkId == networkId && "${it.networkId}:${it.name.lowercase()}" in joinedRoomNames
                                }.forEach {
                                    addLiveMember(it.id, self)
                                    cacheRoomMembers(it.id, listOf(self))
                                }
                            }
                        }
                        if (changed) persistState()
                    }
                    is ReticulumEvent.RoomLeft -> joinMutex.withLock {
                        _activeHubNetworkId.value?.let { networkId ->
                            joinedRoomNames -= "$networkId:${event.roomId.lowercase()}"
                            preview.rooms.value.firstOrNull {
                                it.networkId == networkId && it.name.equals(event.roomId, ignoreCase = true)
                            }?.let { room ->
                                liveMembers.value = liveMembers.value - room.id
                                appendSystemMessage(room, "You left #${room.name}", localMember.value?.id, kind = ChatSystemEventKind.LEAVE)
                            }
                        }
                    }
                    is ReticulumEvent.MembersChanged -> {
                        val room = matchingRoom(event.roomId) ?: return@collect
                        val chatRoomId = room.id
                        val current = liveMembers.value[chatRoomId].orEmpty().associateBy { it.id }
                        val members = event.members.map(::markSelf).map { member ->
                            val existing = current[member.id]
                            if (existing != null && member.displayName == member.id.take(12)) {
                                existing.copy(address = member.address ?: existing.address)
                            } else {
                                member
                            }
                        }
                        members.forEach { member ->
                            val existing = current[member.id] ?: return@forEach
                            if (existing.displayName != member.displayName &&
                                existing.displayName != existing.id.take(12) &&
                                member.displayName != member.id.take(12)
                            ) {
                                appendSystemMessage(
                                    room,
                                    "${existing.displayName} is now known as ${member.displayName}",
                                    member.id,
                                    kind = ChatSystemEventKind.NICK,
                                )
                            }
                        }
                        val merged = current.toMutableMap().apply { putAll(members.associateBy { it.id }) }
                        localMember.value?.let { if (it.id !in merged) merged[it.id] = it }
                        liveMembers.value = liveMembers.value + (chatRoomId to merged.values.toList())
                        cacheRoomMembers(chatRoomId, merged.values)
                        discoveredMembers.value = mergeMembers(discoveredMembers.value, members)
                        persistState()
                    }
                    is ReticulumEvent.MemberJoined -> {
                        val room = matchingRoom(event.roomId) ?: return@collect
                        val chatRoomId = room.id
                        val current = liveMembers.value[chatRoomId].orEmpty().associateBy { it.id }
                        val member = markSelf(event.member)
                        val previous = current[member.id]
                        liveMembers.value = liveMembers.value + (chatRoomId to (current + (member.id to member)).values.toList())
                        cacheRoomMembers(chatRoomId, listOf(member))
                        discoveredMembers.value = mergeMembers(discoveredMembers.value, listOf(member))
                        val systemEvent = when {
                            previous == null -> ChatSystemEventKind.JOIN to "${member.displayName} joined #${room.name}"
                            previous.displayName == member.displayName -> null
                            previous.displayName == previous.id.take(12) -> ChatSystemEventKind.JOIN to "${member.displayName} joined #${room.name}"
                            else -> ChatSystemEventKind.NICK to "${previous.displayName} is now known as ${member.displayName}"
                        }
                        systemEvent?.let { (kind, text) ->
                            appendSystemMessage(room, text, member.id, event.timestampMillis, kind)
                        }
                        persistState()
                    }
                    is ReticulumEvent.MemberLeft -> {
                        val room = matchingRoom(event.roomId) ?: return@collect
                        val currentMembers = liveMembers.value[room.id].orEmpty()
                        val departingMember = currentMembers.firstOrNull { it.id == event.memberId }
                        liveMembers.value = liveMembers.value + (room.id to currentMembers.filterNot { it.id == event.memberId })
                        departingMember?.let { cacheRoomMembers(room.id, listOf(it)) }
                        appendSystemMessage(
                            room,
                            "${departingMember?.displayName ?: event.memberId.take(12)} left #${room.name}",
                            event.memberId,
                            event.timestampMillis,
                            ChatSystemEventKind.LEAVE,
                        )
                        persistState()
                    }
                    is ReticulumEvent.Error -> {
                        val room = event.roomId?.let(::matchingRoom)
                            ?: lastServerCommandRoomId.value?.let { roomId -> preview.rooms.value.firstOrNull { it.id == roomId } }
                        if (room != null) {
                            val isPendingHelpReply = lastServerCommandName.value == "help" && lastServerCommandRoomId.value == room.id
                            if (isPendingHelpReply && classifyCommandError(event.message) == ChatSystemEventKind.COMMAND_ERROR) {
                                appendSystemMessage(room, RRC_COMMAND_HELP, authorId = "client-help", kind = ChatSystemEventKind.HELP)
                            } else {
                                appendSystemMessage(
                                    room,
                                    event.message,
                                    authorId = "server",
                                    timestampMillis = event.timestampMillis,
                                    kind = classifyCommandError(event.message) ?: ChatSystemEventKind.ERROR,
                                )
                            }
                            lastServerCommandText.value = null
                            lastServerCommandName.value = null
                            lastServerCommandRoomId.value = null
                            _lastError.value = null
                        } else {
                            _lastError.value = event.message
                        }
                    }
                    is ReticulumEvent.LxmfError -> {
                        markPendingDirectMessages(event.address, "failed")
                        _directMessageError.value = event.message
                    }
                    is ReticulumEvent.DeliveryStatus -> updateDeliveryStatus(event)
                    is ReticulumEvent.RoomsChanged -> Unit // RRC core intentionally has no room enumeration command.
                }
            }
        }
    }

    override fun messages(roomId: String): Flow<List<ChatMessage>> = combine(
        preview.messages(roomId),
        liveMessages,
        connectionState,
        preview.previewMode,
        _activeHubNetworkId,
    ) { local, live, state, previewEnabled, activeNetworkId ->
        val liveHistory = live[roomId].orEmpty()
        if (previewEnabled) local
        else {
            val room = preview.rooms.value.firstOrNull { it.id == roomId }
            if ((state is ReticulumConnectionState.Connected && room?.networkId == activeNetworkId) || liveHistory.isNotEmpty()) {
                liveHistory
            } else emptyList()
        }
    }

    override fun members(roomId: String): Flow<List<ChatMember>> = combine(
        preview.members(roomId),
        liveMembers,
        connectionState,
        preview.previewMode,
        _activeHubNetworkId,
    ) { local, live, state, previewEnabled, activeNetworkId ->
        val roomMembers = live[roomId].orEmpty()
        val room = preview.rooms.value.firstOrNull { it.id == roomId }
        if (previewEnabled) local
        else if ((state is ReticulumConnectionState.Connected && room?.networkId == activeNetworkId) || roomMembers.isNotEmpty()) {
            roomMembers
        } else emptyList()
    }

    override fun offlineMembers(roomId: String): Flow<List<ChatMember>> = combine(
        cachedRoomMemberIds,
        knownMembers,
        members(roomId),
        preview.previewMode,
    ) { cachedByRoom, known, present, previewEnabled ->
        if (previewEnabled) {
            emptyList()
        } else {
            val presentIds = present.mapTo(mutableSetOf()) { it.id }
            val knownById = known.associateBy { it.id }
            cachedByRoom[roomId].orEmpty().asSequence()
                .filterNot { it in presentIds }
                .mapNotNull(knownById::get)
                .filterNot { it.isSelf }
                .distinctBy { it.id }
                .sortedBy { it.displayName.lowercase() }
                .toList()
        }
    }

    override fun directMessages(memberId: String): Flow<List<ChatMessage>> = combine(
        preview.directMessages(memberId),
        liveDirectMessages,
        preview.previewMode,
    ) { local, live, previewEnabled ->
        val liveHistory = live[memberId].orEmpty()
        if (previewEnabled) local else liveHistory
    }

    override fun addRoom(networkId: String, name: String, topic: String): ChatRoom {
        val room = preview.addRoom(networkId, name, topic)
        if (!preview.previewMode.value) {
            persistState()
            scope.launch { joinRoomIfConnected(room) }
        }
        return room
    }

    override fun addNetwork(name: String, hubDestination: String): ChatNetwork =
        preview.addNetwork(name, hubDestination).also { if (!preview.previewMode.value) persistState() }

    override fun removeNetwork(networkId: String) {
        if (preview.networks.value.none { it.id == networkId }) return
        val removedRoomIds = preview.rooms.value.filter { it.networkId == networkId }.mapTo(mutableSetOf()) { it.id }
        val wasActiveHub = _activeHubNetworkId.value == networkId

        preview.removeNetwork(networkId)
        liveMessages.value = liveMessages.value - removedRoomIds
        liveMembers.value = liveMembers.value - removedRoomIds
        cachedRoomMemberIds.value = cachedRoomMemberIds.value - removedRoomIds
        if (wasActiveHub) saveActiveHubNetworkId(null)
        if (_preferredHubNetworkId.value == networkId) savePreferredHubNetworkId(null)
        if (preview.previewMode.value) return

        persistState()
        if (wasActiveHub) {
            scope.launch {
                runCatching { transport.disconnect() }
                    .onFailure { _lastError.value = it.message ?: "Could not disconnect from the removed RRC hub." }
                joinMutex.withLock { joinedRoomNames.clear() }
            }
        }
    }

    override fun restoreNetwork(network: ChatNetwork) = preview.restoreNetwork(network)

    override fun restoreRoom(room: ChatRoom) = preview.restoreRoom(room)

    override fun updateNetworkNickname(networkId: String, nickname: String?) {
        val previous = preview.networks.value.firstOrNull { it.id == networkId }?.rrcNickname
        preview.updateNetworkNickname(networkId, nickname)
        if (!preview.previewMode.value) {
            persistState()
            if (previous != preview.networks.value.firstOrNull { it.id == networkId }?.rrcNickname) {
                reconnectForNicknameChange(networkId)
            }
        }
    }

    override fun removeRoom(roomId: String) {
        val room = preview.rooms.value.firstOrNull { it.id == roomId } ?: return
        preview.removeRoom(roomId)
        if (preview.previewMode.value) return

        liveMessages.value = liveMessages.value - roomId
        liveMembers.value = liveMembers.value - roomId
        cachedRoomMemberIds.value = cachedRoomMemberIds.value - roomId
        persistState()
        scope.launch {
            joinMutex.withLock {
                val joinedName = "${room.networkId}:${room.name.lowercase()}"
                if (joinedName in joinedRoomNames) {
                    runCatching { transport.leaveRoom(room.name) }
                        .onFailure { _lastError.value = it.message ?: "Could not leave ${room.name}." }
                    joinedRoomNames -= joinedName
                }
            }
        }
    }

    override fun setPreviewMode(enabled: Boolean) {
        if (preview.previewMode.value == enabled) return
        preview.setPreviewMode(enabled)
        if (enabled) {
            _activeHubNetworkId.value = null
            _lastError.value = null
            _directMessageError.value = null
            scope.launch { runCatching { transport.disconnect() } }
        }
        persistState()
    }

    override fun addContact(member: ChatMember): ChatMember? {
        if (member.isSelf) return null
        if (preview.previewMode.value) {
            return preview.addContact(member)
        }
        val address = member.address?.let(::normalizeLxmfAddress)
        val candidate = member.copy(
            id = member.id.ifBlank { address?.let { "lxmf-$it" }.orEmpty() },
            address = address ?: member.address,
        )
        if (candidate.id.isBlank()) return null
        val knownMember = knownMembers.value.firstOrNull {
            it.id == candidate.id || (address != null && normalizeLxmfAddress(it.address.orEmpty()) == address)
        }
        val resolved = if (knownMember == null) candidate else mergeMember(knownMember, candidate.copy(id = knownMember.id))
        if (resolved.isSelf) return null
        discoveredMembers.value = mergeMembers(discoveredMembers.value, listOf(resolved))
        val canonical = discoveredMembers.value.firstOrNull {
            it.id == resolved.id || (address != null && normalizeLxmfAddress(it.address.orEmpty()) == address)
        } ?: resolved
        liveContactMemberIds.value = normalizeContactIds(liveContactMemberIds.value + canonical.id, discoveredMembers.value)
        persistState()
        return canonical
    }

    override fun toggleContact(member: ChatMember) {
        if (preview.previewMode.value) {
            preview.toggleContact(member)
            return
        }
        if (member.isSelf) return
        val address = member.address?.let(::normalizeLxmfAddress)
        val knownMember = knownMembers.value.firstOrNull {
            it.id == member.id || (address != null && normalizeLxmfAddress(it.address.orEmpty()) == address)
        }
        if (knownMember?.isSelf == true) return
        val contactId = knownMember?.id ?: member.id
        val existingContactId = liveContactMemberIds.value.firstOrNull { id ->
            id == contactId || (address != null && knownMembers.value.any { known ->
                known.id == id && normalizeLxmfAddress(known.address.orEmpty()) == address
            })
        }
        if (existingContactId != null) {
            liveContactMemberIds.value = liveContactMemberIds.value - existingContactId
        } else {
            val resolved = knownMember?.let { mergeMember(it, member.copy(id = it.id)) } ?: member.copy(address = address ?: member.address)
            discoveredMembers.value = mergeMembers(discoveredMembers.value, listOf(resolved))
            val canonical = discoveredMembers.value.firstOrNull {
                it.id == resolved.id || (address != null && normalizeLxmfAddress(it.address.orEmpty()) == address)
            } ?: resolved
            liveContactMemberIds.value = normalizeContactIds(liveContactMemberIds.value + canonical.id, discoveredMembers.value)
        }
        persistState()
    }

    override fun acceptContactRequest(requestId: String) {
        if (preview.previewMode.value) {
            preview.acceptContactRequest(requestId)
            return
        }
        val message = messageByRequestId.remove(requestId)
        val request = _contactRequests.value.firstOrNull { it.id == requestId }
        if (request == null) return
        val sender = addContact(request.sender) ?: return
        _contactRequests.value = _contactRequests.value.filterNot { it.id == requestId }
        if (message != null) receiveAcceptedDirect(sender, message)
        else {
            val acceptedMessage = ChatMessage(
                id = request.id,
                author = sender.displayName,
                text = request.message,
                time = request.time,
                timestampMillis = request.timestampMillis,
            )
            val current = liveDirectMessages.value[sender.id].orEmpty()
            if (current.none { it.id == acceptedMessage.id }) {
                liveDirectMessages.value = liveDirectMessages.value + (sender.id to (current + acceptedMessage))
            }
        }
        persistState()
    }

    override fun ignoreContactRequest(requestId: String) {
        if (preview.previewMode.value) {
            preview.ignoreContactRequest(requestId)
            return
        }
        if (_contactRequests.value.none { it.id == requestId }) return
        messageByRequestId.remove(requestId)
        _contactRequests.value = _contactRequests.value.filterNot { it.id == requestId }
        persistState()
    }

    override fun blockContactRequest(requestId: String) {
        if (preview.previewMode.value) {
            preview.blockContactRequest(requestId)
            return
        }
        val request = _contactRequests.value.firstOrNull { it.id == requestId }
        if (request == null) return
        messageByRequestId.remove(requestId)
        _contactRequests.value = _contactRequests.value.filterNot { it.id == requestId }
        liveBlockedMemberIds.value = liveBlockedMemberIds.value + request.sender.id
        liveContactMemberIds.value = liveContactMemberIds.value - request.sender.id
        persistState()
    }

    override suspend fun send(roomId: String, text: String) {
        if (preview.previewMode.value) {
            preview.send(roomId, text)
            return
        }
        val outgoing = parseOutgoingChatMessage(text) ?: return
        val room = preview.rooms.value.firstOrNull { it.id == roomId }
            ?: throw IllegalArgumentException("Unknown room")
        val connectedToRoom =
            transport.state.value is ReticulumConnectionState.Connected &&
                room.networkId == _activeHubNetworkId.value
        if (!connectedToRoom) {
            // Keep the message instead of dropping it; it is flushed once the hub is reachable.
            queueSend(roomId, outgoing.body)
            return
        }
        sendRoomMessageNow(room, outgoing)
    }

    /** Queues a message for later delivery, or reports an error when the queue is full. */
    private suspend fun queueSend(roomId: String, body: String) {
        sendQueueMutex.withLock {
            if (pendingSends.size >= MAX_QUEUED_SENDS) {
                _lastError.value = "Message queue is full. Reconnect and try again."
                return@withLock
            }
            pendingSends.add(PendingSend(roomId, body))
            _lastError.value = null
        }
    }

    /** Sends a room message immediately when the hub connection is already established. */
    private suspend fun sendRoomMessageNow(room: ChatRoom, outgoing: OutgoingChatMessage) {
        if (outgoing.body.startsWith('/')) {
            val isServerCommand = isRrcServerSlashCommand(outgoing.body)
            lastServerCommandRoomId.value = room.id
            lastServerCommandText.value = outgoing.body
            lastServerCommandName.value = if (isServerCommand) {
                outgoing.body.drop(1).takeWhile { !it.isWhitespace() }.lowercase()
            } else {
                null
            }
        } else {
            lastServerCommandRoomId.value = null
            lastServerCommandName.value = null
            lastServerCommandText.value = null
        }
        _lastError.value = null
        try {
            transport.sendRoomMessage(room.name, outgoing.body, outgoing.isAction)
        } catch (error: Exception) {
            _lastError.value = error.message ?: "Could not send the RRC message."
        }
    }

    /** Flushes queued messages for rooms on the active hub, in order, stopping at the first failure. */
    private suspend fun flushQueuedSends() = sendQueueMutex.withLock {
        val networkId = _activeHubNetworkId.value ?: return@withLock
        val roomsById = preview.rooms.value.associateBy { it.id }
        val (due, remaining) = pendingSends.partition { pending ->
            roomsById[pending.roomId]?.networkId == networkId
        }
        if (due.isEmpty()) return@withLock
        // A new flush attempt means we are trying to deliver again, so clear any
        // stale send error; a failed attempt re-reports it below.
        _lastError.value = null
        var failureIndex: Int? = null
        for ((index, pending) in due.withIndex()) {
            val room = roomsById[pending.roomId]!!
            try {
                transport.sendRoomMessage(room.name, pending.body, false)
            } catch (error: Exception) {
                _lastError.value = error.message ?: "Could not send a queued RRC message."
                failureIndex = index
                break
            }
        }
        pendingSends = ((failureIndex?.let { due.subList(it, due.size) } ?: emptyList()) + remaining).toMutableList()
    }

    override suspend fun sendDirectMessage(memberId: String, text: String) {
        if (preview.previewMode.value) {
            preview.sendDirectMessage(memberId, text)
            return
        }
        val outgoing = parseOutgoingChatMessage(text) ?: return
        _directMessageError.value = null

        val member = knownMembers.value.firstOrNull { it.id == memberId }
        val address = member?.address?.let(::normalizeLxmfAddress)
        if (!transport.nodeReady.value) {
            _directMessageError.value = "Start the Reticulum node before sending an LXMF message."
            return
        }
        if (transport.localDeliveryAddress.value == null) {
            _directMessageError.value = "The LXMF delivery identity is still starting. Try again in a moment."
            return
        }
        if (address == null) {
            _directMessageError.value = "This contact has no valid LXMF delivery address. Add or update the contact with their 32-character address."
            return
        }
        val pendingMessage = appendPendingDirectMessage(memberId, outgoing.body)
        try {
            transport.sendDirectMessage(address, outgoing.body)
        } catch (error: Exception) {
            updateDirectMessageDeliveryState(memberId, pendingMessage.id, "failed")
            _directMessageError.value = error.message ?: "Could not send the LXMF message."
        }
    }

    suspend fun connectToHub(config: ReticulumConnectionConfig) {
        check(!preview.previewMode.value) { "Turn off Local preview before connecting to an RRC hub." }
        _lastError.value = null
        val network = preview.networks.value.firstOrNull { it.id == config.networkId }
            ?: throw IllegalArgumentException("Select a configured RRC network first.")
        if (!network.hubDestination.equals(config.hubDestination, ignoreCase = true)) {
            throw IllegalArgumentException("The selected RRC network destination changed. Select it again.")
        }
        lastServerCommandRoomId.value = null
        lastServerCommandName.value = null
        lastServerCommandText.value = null
        saveActiveHubNetworkId(network.id)
        transport.connect(config.copy(displayName = nicknameForNetwork(network)))
    }

    suspend fun disconnectFromHub() {
        lastServerCommandRoomId.value = null
        lastServerCommandName.value = null
        lastServerCommandText.value = null
        saveActiveHubNetworkId(null)
        transport.disconnect()
    }

    /**
     * Reconnect to the currently active RRC hub using its saved destination and nickname.
     * Intended as an actionable recovery path when the hub connection has failed or the
     * hub is disconnected, so the user is not stuck with an error and no way to retry.
     */
    suspend fun reconnectActiveHub() {
        check(!preview.previewMode.value) { "Turn off Local preview before connecting to an RRC hub." }
        val networkId = _activeHubNetworkId.value
            ?: throw IllegalStateException("No active RRC hub to reconnect to.")
        val network = preview.networks.value.firstOrNull { it.id == networkId }
            ?: throw IllegalStateException("The active RRC network is no longer configured.")
        if (network.hubDestination.isNullOrBlank()) {
            throw IllegalStateException("The active RRC network has no hub destination.")
        }
        _lastError.value = null
        lastServerCommandRoomId.value = null
        lastServerCommandName.value = null
        lastServerCommandText.value = null
        saveActiveHubNetworkId(network.id)
        transport.disconnect()
        joinMutex.withLock { joinedRoomNames.clear() }
        transport.connect(
            ReticulumConnectionConfig(network.hubDestination.orEmpty(), nicknameForNetwork(network), network.id)
        )
    }

    fun close() {
        persistenceRequests.trySend(Unit)
        persistenceRequests.close()
        runBlocking { persistenceJob?.join() }
        scope.cancel()
    }

    /** Persist interface preferences, but keep the shared-instance RPC key session-only. */
    fun saveNodeConfig(config: ReticulumNodeConfig) {
        val storedConfig = config.copy(existingInstanceRpcKeyHex = null)
        if (_nodeConfig.value == storedConfig) return
        _nodeConfig.value = storedConfig
        persistState()
    }

    fun saveUiSettings(settings: StarlingUiSettings) {
        if (_uiSettings.value == settings) return
        _uiSettings.value = settings
        persistState()
    }

    fun saveGlobalNickname(value: String) {
        val nickname = normalizeStarlingNickname(value)
        if (_globalNickname.value == nickname) return
        _globalNickname.value = nickname
        persistState()
        val activeNetwork = preview.networks.value.firstOrNull { it.id == _activeHubNetworkId.value }
        if (activeNetwork != null && activeNetwork.rrcNickname.isNullOrBlank()) {
            reconnectForNicknameChange(activeNetwork.id)
        }
    }

    private fun nicknameForNetwork(network: ChatNetwork): String =
        normalizeStarlingNickname(network.rrcNickname?.takeIf(String::isNotBlank) ?: _globalNickname.value)

    private fun reconnectForNicknameChange(networkId: String) {
        if (preview.previewMode.value || networkId != _activeHubNetworkId.value) return
        if (transport.state.value !is ReticulumConnectionState.Connected && transport.state.value != ReticulumConnectionState.Connecting) return
        scope.launch {
            nicknameReconnectMutex.withLock {
                if (preview.previewMode.value || _activeHubNetworkId.value != networkId) return@withLock
                val network = preview.networks.value.firstOrNull { it.id == networkId } ?: return@withLock
                runCatching {
                    transport.disconnect()
                    joinMutex.withLock { joinedRoomNames.clear() }
                    if (!preview.previewMode.value && _activeHubNetworkId.value == networkId) {
                        transport.connect(ReticulumConnectionConfig(network.hubDestination.orEmpty(), nicknameForNetwork(network), network.id))
                    }
                }.onFailure { _lastError.value = it.message ?: "Could not apply the RRC nickname. Reconnect to the hub." }
            }
        }
    }

    /** Identity keys live outside chat state; invalidate cached self data when the key changes. */
    fun updateManagedIdentity(identityHash: String?) {
        managedIdentityLoaded.value = true
        if (identityHash != null && _localIdentityHash.value == identityHash) return
        _localIdentityHash.value = identityHash
        localMember.value = null
        discoveredMembers.value = discoveredMembers.value.filterNot { it.isSelf }
        liveMembers.value = liveMembers.value.mapValues { (_, members) -> members.filterNot { it.isSelf } }
        persistState()
    }

    fun savePreferredHubNetworkId(networkId: String?) {
        val normalized = networkId?.takeIf { id ->
            preview.networks.value.any { it.id == id && !it.isPreview && !it.hubDestination.isNullOrBlank() }
        }
        if (_preferredHubNetworkId.value == normalized) return
        _preferredHubNetworkId.value = normalized
        persistState()
    }

    private fun saveActiveHubNetworkId(networkId: String?) {
        if (_activeHubNetworkId.value == networkId) return
        _activeHubNetworkId.value = networkId
        persistState()
    }

    private suspend fun joinConfiguredRooms() {
        val networkId = _activeHubNetworkId.value ?: return
        preview.rooms.value.filter { it.networkId == networkId }.forEach { joinRoomIfConnected(it) }
    }

    private suspend fun joinRoomIfConnected(room: ChatRoom) {
        if (preview.previewMode.value) return
        if (transport.state.value !is ReticulumConnectionState.Connected) return
        val normalized = "${room.networkId}:${room.name.lowercase()}"
        if (room.networkId != _activeHubNetworkId.value) return
        joinMutex.withLock {
            if (normalized in joinedRoomNames) return
            try {
                transport.joinRoom(room.name)
                joinedRoomNames += normalized
            } catch (error: Exception) {
                _lastError.value = error.message ?: "Could not join ${room.name}."
            }
        }
    }

    private fun matchingRoom(wireRoom: String): ChatRoom? =
        preview.rooms.value.firstOrNull {
            it.networkId == _activeHubNetworkId.value && it.name.equals(wireRoom, ignoreCase = true)
        }

    private fun roomForServerResponse(): ChatRoom? {
        val commandRoomId = lastServerCommandRoomId.value
        if (commandRoomId != null) {
            preview.rooms.value.firstOrNull { it.id == commandRoomId }?.let { return it }
        }
        val activeNetworkId = _activeHubNetworkId.value ?: return null
        return preview.rooms.value.firstOrNull { it.networkId == activeNetworkId }
    }

    private fun appendLiveMessage(roomId: String, message: ChatMessage) {
        val current = liveMessages.value[roomId].orEmpty()
        if (current.any { it.id == message.id }) return
        val updated = collapseRepeatedSystemMessage(current, message)
        if (updated != current) {
            liveMessages.value = liveMessages.value + (roomId to updated)
            persistState()
        }
    }

    private fun appendSystemMessage(
        room: ChatRoom,
        text: String,
        authorId: String? = null,
        timestampMillis: Long = 0,
        kind: ChatSystemEventKind = ChatSystemEventKind.OTHER,
    ) {
        val timeMillis = timestampMillis.takeIf { it > 0 } ?: Clock.System.now().toEpochMilliseconds()
        val time = formatChatTimestamp(timeMillis, _uiSettings.value.timestampTimeZone)
        val message = ChatMessage(
            id = "system-$timeMillis-${systemMessageSequence++}",
            author = "System",
            text = text,
            time = time,
            timestampMillis = timeMillis,
            authorId = authorId,
            isSystem = true,
            systemEvents = listOf(ChatSystemEvent(kind, text, time, timeMillis)),
        )
        appendLiveMessage(room.id, message)
    }

    private fun receive(message: RrcRoomMessage) {
        val room = matchingRoom(message.roomId)
            ?: (if (message.roomId.isBlank() && message.isNotice) roomForServerResponse() else null)
            ?: return
        if (message.isNotice) {
            val stateUpdate = parseRrcRoomStateUpdate(room.name, message.body)
            if (stateUpdate != null && preview.updateRoomState(room.id, stateUpdate)) persistState()
        }
        if (message.isNotice && message.body == RRC_PRESENCE_NOTICE_BODY && message.senderId.isNotBlank()) {
            val member = markSelf(
                ChatMember(
                    id = message.senderId,
                    displayName = message.senderName,
                    isSelf = message.isMine,
                    address = message.senderLxmfAddress,
                ),
            )
            if (member.isSelf) {
                localMember.value = member
            } else {
                discoveredMembers.value = mergeMembers(discoveredMembers.value, listOf(member))
            }
            addLiveMember(room.id, member)
            cacheRoomMembers(room.id, listOf(member))
            persistState()
            return
        }
        val isPendingHelpReply = lastServerCommandName.value == "help" && lastServerCommandRoomId.value == room.id
        val isEchoedSlashCommand = message.isMine && message.body.startsWith('/') &&
            lastServerCommandRoomId.value == room.id &&
            lastServerCommandText.value?.equals(message.body, ignoreCase = true) == true
        if (isEchoedSlashCommand) {
            if (isPendingHelpReply) {
                appendSystemMessage(room, RRC_COMMAND_HELP, authorId = "client-help", kind = ChatSystemEventKind.HELP)
            } else {
                appendSystemMessage(
                    room,
                    "Unrecognized command: ${message.body}",
                    authorId = "server",
                    timestampMillis = message.timestampMillis,
                    kind = ChatSystemEventKind.COMMAND_ERROR,
                )
            }
            lastServerCommandText.value = null
            lastServerCommandName.value = null
            lastServerCommandRoomId.value = null
            return
        }
        if (isPendingHelpReply && classifyCommandError(message.body) == ChatSystemEventKind.COMMAND_ERROR) {
            appendSystemMessage(room, RRC_COMMAND_HELP, authorId = "client-help", kind = ChatSystemEventKind.HELP)
            lastServerCommandText.value = null
            lastServerCommandName.value = null
            lastServerCommandRoomId.value = null
            return
        }
        if (message.isNotice && lastServerCommandRoomId.value == room.id) {
            lastServerCommandText.value = null
            lastServerCommandName.value = null
        }
        val timestampMillis = message.timestampMillis.takeIf { it > 0 }
            ?: Clock.System.now().toEpochMilliseconds()
        val sender = message.senderName.ifBlank { message.senderId.take(12) }
        val time = formatChatTimestamp(timestampMillis, _uiSettings.value.timestampTimeZone)
        val chatMessage = ChatMessage(
            id = message.id.ifBlank { "rrc-$timestampMillis-${message.senderId}" },
            author = sender,
            text = message.body,
            time = time,
            timestampMillis = timestampMillis,
            isAction = message.isAction,
            isMine = message.isMine,
            authorId = message.senderId,
            isSystem = message.isNotice,
            systemEvents = if (message.isNotice) {
                listOf(ChatSystemEvent(classifySystemNotice(message.body), message.body, time, timestampMillis))
            } else {
                emptyList()
            },
        )
        appendLiveMessage(room.id, chatMessage)
        if (classifyCommandError(message.body) == ChatSystemEventKind.COMMAND_ERROR) {
            lastServerCommandText.value = null
            lastServerCommandName.value = null
            lastServerCommandRoomId.value = null
        }
        if (message.senderId.isNotBlank() && !message.isNotice) {
            val member = ChatMember(message.senderId, sender, isSelf = message.isMine, address = message.senderLxmfAddress)
            if (member.isSelf) localMember.value = member
            val current = liveMembers.value[room.id].orEmpty().associateBy { it.id }
            liveMembers.value = liveMembers.value + (room.id to (current + (member.id to (current[member.id]?.copy(
                displayName = sender,
                address = message.senderLxmfAddress ?: current[member.id]?.address,
            ) ?: member))).values.toList())
            cacheRoomMembers(room.id, listOf(member))
            discoveredMembers.value = mergeMembers(discoveredMembers.value, listOf(member))
            if (member.isSelf) addLiveMember(room.id, member)
        }
        persistState()
    }

    private fun receiveDirect(message: LxmfDirectMessage) {
        val address = normalizeLxmfAddress(message.address) ?: return
        val timestampMillis = message.timestampMillis.takeIf { it > 0 }
            ?: Clock.System.now().toEpochMilliseconds()
        val known = knownMembers.value.firstOrNull { normalizeLxmfAddress(it.address.orEmpty()) == address }
        val member = known ?: ChatMember(
            id = "lxmf-$address",
            displayName = message.senderName.ifBlank { address.take(12) },
            address = address,
        )
        if (isMemberBlocked(member)) return
        if (known == null && discoveredMembers.value.none { it.id == member.id }) {
            discoveredMembers.value = mergeMembers(discoveredMembers.value, listOf(member))
        }
        if (message.isMine || isMemberContact(member)) {
            receiveAcceptedDirect(member, message)
            return
        }

        val requestId = "lxmf-request-${message.id}"
        val request = ChatContactRequest(
            id = requestId,
            sender = member,
            message = message.body,
            time = formatChatTimestamp(timestampMillis, _uiSettings.value.timestampTimeZone),
            timestampMillis = timestampMillis,
        )
        val previous = _contactRequests.value.firstOrNull { it.sender.id == member.id }
        messageByRequestId[requestId] = message.copy(timestampMillis = timestampMillis)
        _contactRequests.value = if (previous == null) {
            _contactRequests.value + request
        } else {
            messageByRequestId.remove(previous.id)
            _contactRequests.value.map { if (it.id == previous.id) request else it }
        }
        persistState()
    }

    private fun receiveAcceptedDirect(member: ChatMember, message: LxmfDirectMessage) {
        val timestampMillis = message.timestampMillis.takeIf { it > 0 }
            ?: Clock.System.now().toEpochMilliseconds()
        val chatMessage = ChatMessage(
            id = message.id,
            author = if (message.isMine) "you" else member.displayName,
            text = message.body,
            time = formatChatTimestamp(timestampMillis, _uiSettings.value.timestampTimeZone),
            isMine = message.isMine,
            deliveryState = message.deliveryState.takeIf { message.isMine && it.isNotBlank() } ?: if (message.isMine) "queued" else null,
            authorId = member.id.takeUnless { message.isMine },
            timestampMillis = timestampMillis,
        )
        val current = liveDirectMessages.value[member.id].orEmpty()
        if (current.any { it.id == chatMessage.id }) return
        val pending = if (message.isMine) {
            current.firstOrNull { it.isMine && it.deliveryState == "sending" && it.text == message.body }
        } else {
            null
        }
        if (pending != null) {
            liveDirectMessages.value = liveDirectMessages.value +
                (member.id to current.map { if (it.id == pending.id) chatMessage else it })
            persistState()
        } else {
            liveDirectMessages.value = liveDirectMessages.value + (member.id to (current + chatMessage))
            persistState()
        }
    }

    private fun appendPendingDirectMessage(memberId: String, body: String): ChatMessage {
        val timestampMillis = Clock.System.now().toEpochMilliseconds()
        val message = ChatMessage(
            id = "lxmf-pending-$timestampMillis-${pendingDirectMessageSequence++}",
            author = "you",
            text = body,
            time = formatChatTimestamp(timestampMillis, _uiSettings.value.timestampTimeZone),
            isMine = true,
            deliveryState = "sending",
            timestampMillis = timestampMillis,
        )
        liveDirectMessages.value = liveDirectMessages.value +
            (memberId to (liveDirectMessages.value[memberId].orEmpty() + message))
        persistState()
        return message
    }

    private fun updateDirectMessageDeliveryState(memberId: String, messageId: String, state: String) {
        val messages = liveDirectMessages.value[memberId].orEmpty()
        if (messages.none { it.id == messageId && it.deliveryState != state }) return
        liveDirectMessages.value = liveDirectMessages.value +
            (memberId to messages.map { if (it.id == messageId) it.copy(deliveryState = state) else it })
        persistState()
    }

    private fun markPendingDirectMessages(address: String?, state: String) {
        val normalizedAddress = address?.let(::normalizeLxmfAddress) ?: return
        val memberIds = knownMembers.value.filter {
            normalizeLxmfAddress(it.address.orEmpty()) == normalizedAddress
        }.mapTo(mutableSetOf()) { it.id }
        var changed = false
        liveDirectMessages.value = liveDirectMessages.value.mapValues { (memberId, messages) ->
            if (memberId !in memberIds) return@mapValues messages
            messages.map { message ->
                if (message.isMine && message.deliveryState == "sending") {
                    changed = true
                    message.copy(deliveryState = state)
                } else {
                    message
                }
            }
        }
        if (changed) persistState()
    }

    private fun updateDeliveryStatus(event: ReticulumEvent.DeliveryStatus) {
        liveDirectMessages.value = liveDirectMessages.value.mapValues { (_, messages) ->
            messages.map { message ->
                if (message.id == event.messageId) message.copy(deliveryState = event.state) else message
            }
        }
        persistState()
    }

    private fun persistState() {
        persistenceRequests.trySend(Unit)
    }

    private fun markSelf(member: ChatMember): ChatMember {
        val self = localMember.value
        return if (self?.id == member.id) member.copy(
            displayName = self.displayName.ifBlank { member.displayName },
            isSelf = true,
            address = member.address ?: self.address,
        ) else member
    }

    private fun addLiveMember(roomId: String, member: ChatMember) {
        val current = liveMembers.value[roomId].orEmpty().associateBy { it.id }
        val merged = current + (member.id to (current[member.id]?.let { mergeMember(it, member) } ?: member))
        liveMembers.value = liveMembers.value + (roomId to merged.values.toList())
    }

    private fun cacheRoomMembers(roomId: String, members: Collection<ChatMember>) {
        val additions = members.asSequence()
            .map { it.id.trim() }
            .filter { it.isNotEmpty() && !preview.isPreviewMember(it) }
            .toSet()
        if (additions.isEmpty()) return
        val current = cachedRoomMemberIds.value[roomId].orEmpty()
        val updated = current + additions
        if (updated == current) return
        cachedRoomMemberIds.value = cachedRoomMemberIds.value + (roomId to updated)
        persistState()
    }

    private fun persistCurrentState() {
        val state = PersistedChatState(
            previewMode = preview.previewMode.value,
            nodeConfig = _nodeConfig.value,
            preferredHubNetworkId = _preferredHubNetworkId.value,
            localIdentityHash = _localIdentityHash.value,
            globalNickname = _globalNickname.value,
            networks = preview.persistedNetworks(),
            rooms = preview.persistedRooms(),
            roomMemberIds = cachedRoomMemberIds.value,
            activeHubNetworkId = _activeHubNetworkId.value,
            members = mergeMembers(discoveredMembers.value, listOfNotNull(localMember.value))
                .filterNot { preview.isPreviewMember(it.id) },
            contacts = liveContactMemberIds.value,
            blockedMembers = liveBlockedMemberIds.value,
            contactRequests = _contactRequests.value.filterNot { it.isPreview },
            roomMessages = liveMessages.value,
            directMessages = liveDirectMessages.value,
            uiSettings = _uiSettings.value,
        )
        runCatching { stateStore.write(ChatStateCodec.encode(state)) }
            .onFailure { _lastError.value = "Could not save chat data: ${it.message ?: "storage error"}" }
    }

    private fun mergeMembers(existing: List<ChatMember>, incoming: List<ChatMember>): List<ChatMember> {
        val merged = existing.toMutableList()
        incoming.forEach { member ->
            val index = merged.indexOfFirst { old -> old.id == member.id || hasSameLxmfAddress(old, member) }
            if (index < 0) {
                merged += member
            } else {
                val old = merged[index]
                merged[index] = mergeMember(old, member.copy(id = old.id))
            }
        }
        return merged
    }

    private fun mergeMember(old: ChatMember, member: ChatMember): ChatMember = old.copy(
        displayName = member.displayName.takeIf { it.isNotBlank() && it != member.id.take(12) } ?: old.displayName.ifBlank { member.displayName },
        isSelf = old.isSelf || member.isSelf,
        address = member.address?.let(::normalizeLxmfAddress) ?: old.address ?: member.address,
    )

    private fun normalizeContactIds(contactIds: Set<String>, members: List<ChatMember>): Set<String> =
        contactIds.mapTo(mutableSetOf()) { contactId ->
            val member = members.firstOrNull { it.id == contactId }
            val address = member?.address?.let(::normalizeLxmfAddress)
            if (address == null) contactId
            else members.firstOrNull { normalizeLxmfAddress(it.address.orEmpty()) == address }?.id ?: contactId
        }

    private fun isMemberContact(member: ChatMember): Boolean {
        if (member.id in liveContactMemberIds.value) return true
        val address = member.address?.let(::normalizeLxmfAddress) ?: return false
        return liveContactMemberIds.value.any { contactId ->
            knownMembers.value.firstOrNull { it.id == contactId }
                ?.address?.let(::normalizeLxmfAddress) == address
        }
    }

    private fun isMemberBlocked(member: ChatMember): Boolean {
        if (member.id in liveBlockedMemberIds.value) return true
        val address = member.address?.let(::normalizeLxmfAddress) ?: return false
        return liveBlockedMemberIds.value.any { blockedId ->
            knownMembers.value.firstOrNull { it.id == blockedId }
                ?.address?.let(::normalizeLxmfAddress) == address
        }
    }

    private fun classifySystemNotice(text: String): ChatSystemEventKind {
        val normalized = text.lowercase()
        classifyCommandError(normalized)?.let { return it }
        return when {
            " is now known as " in normalized || ("nick" in normalized && "chang" in normalized) -> ChatSystemEventKind.NICK
            "joined #" in normalized || " has joined " in normalized || " joined the room" in normalized -> ChatSystemEventKind.JOIN
            " left #" in normalized || " has left " in normalized || " has quit " in normalized -> ChatSystemEventKind.LEAVE
            normalized.startsWith("mode for ") || normalized.startsWith("mode ") -> ChatSystemEventKind.MODE
            else -> ChatSystemEventKind.NOTICE
        }
    }

    private fun classifyCommandError(text: String): ChatSystemEventKind? {
        return if (isUnrecognizedRrcCommandReply(text)) ChatSystemEventKind.COMMAND_ERROR else null
    }

}
