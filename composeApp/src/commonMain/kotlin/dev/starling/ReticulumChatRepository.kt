package dev.starling

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

/**
 * Keeps the no-setup preview repository for local configuration, routes room
 * traffic through RRC and DMs through LXMF, and persists app-level chat state.
 */
class ReticulumChatRepository(
    private val preview: LocalPreviewChatRepository,
    private val transport: ReticulumTransport,
    private val stateStore: ChatStateStore,
) : ChatRepository by preview {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val joinMutex = Mutex()
    private val nicknameReconnectMutex = Mutex()
    private val persistenceRequests = Channel<Unit>(Channel.CONFLATED)
    private var persistenceJob: Job? = null
    private val joinedRoomNames = mutableSetOf<String>()
    private val restoredState = runCatching { stateStore.read()?.let(ChatStateCodec::decode) }.getOrNull()
    private val restoredPreviewMode = (restoredState?.previewMode ?: true).also(preview::setPreviewMode)
    private val _nodeConfig = MutableStateFlow(restoredState?.nodeConfig)
    val nodeConfig: StateFlow<ReticulumNodeConfig?> = _nodeConfig.asStateFlow()
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
    private val discoveredMembers = MutableStateFlow(
        restoredState?.members.orEmpty().filterNot { preview.isPreviewMember(it.id) },
    )
    private val localMember = MutableStateFlow(
        restoredState?.members?.firstOrNull { it.isSelf && !preview.isPreviewMember(it.id) },
    )
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
        restoredState?.contacts.orEmpty().filterNot(preview::isPreviewMember).toSet(),
    )
    private val liveBlockedMemberIds = MutableStateFlow(
        restoredState?.blockedMembers.orEmpty().filterNot(preview::isPreviewMember).toSet(),
    )
    private val _contactRequests = MutableStateFlow(
        restoredState?.contactRequests.orEmpty().filterNot { it.isPreview || preview.isPreviewMember(it.sender.id) },
    )
    private val messageByRequestId = mutableMapOf<String, LxmfDirectMessage>()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = combine(
        _lastError,
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
                } else if (state is ReticulumConnectionState.Disconnected || state is ReticulumConnectionState.Failed) {
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
                        .onFailure { _lastError.value = it.message ?: "Could not update the LXMF identity nickname." }
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
                    is ReticulumEvent.RoomMessageReceived -> receive(event.message)
                    is ReticulumEvent.DirectMessage -> receiveDirect(event.message)
                    is ReticulumEvent.LocalMemberChanged -> {
                        if (managedIdentityLoaded.value && event.member.id != _localIdentityHash.value) return@collect
                        val self = event.member.copy(isSelf = true, address = localMember.value?.address)
                        val changed = localMember.value != self
                        localMember.value = self
                        if (changed) discoveredMembers.value = mergeMembers(discoveredMembers.value, listOf(self))
                        joinMutex.withLock {
                            val networkId = _activeHubNetworkId.value
                            if (networkId != null) {
                                preview.rooms.value.filter {
                                    it.networkId == networkId && "${it.networkId}:${it.name.lowercase()}" in joinedRoomNames
                                }.forEach { addLiveMember(it.id, self) }
                            }
                        }
                        if (changed) persistState()
                    }
                    is ReticulumEvent.RoomJoined -> joinMutex.withLock {
                        _activeHubNetworkId.value?.let { networkId ->
                            joinedRoomNames += "$networkId:${event.roomId.lowercase()}"
                            val room = preview.rooms.value.firstOrNull {
                                it.networkId == networkId && it.name.equals(event.roomId, ignoreCase = true)
                            }
                            if (room != null) localMember.value?.let { addLiveMember(room.id, it) }
                        }
                    }
                    is ReticulumEvent.RoomLeft -> joinMutex.withLock {
                        _activeHubNetworkId.value?.let { joinedRoomNames -= "$it:${event.roomId.lowercase()}" }
                    }
                    is ReticulumEvent.MembersChanged -> {
                        val chatRoomId = matchingRoom(event.roomId)?.id ?: return@collect
                        val current = liveMembers.value[chatRoomId].orEmpty().associateBy { it.id }
                        val members = event.members.map(::markSelf)
                        val merged = current.toMutableMap().apply { putAll(members.associateBy { it.id }) }
                        localMember.value?.let { if (it.id !in merged) merged[it.id] = it }
                        liveMembers.value = liveMembers.value + (chatRoomId to merged.values.toList())
                        discoveredMembers.value = mergeMembers(discoveredMembers.value, members)
                        persistState()
                    }
                    is ReticulumEvent.MemberJoined -> {
                        val chatRoomId = matchingRoom(event.roomId)?.id ?: return@collect
                        val current = liveMembers.value[chatRoomId].orEmpty().associateBy { it.id }
                        val member = markSelf(event.member)
                        liveMembers.value = liveMembers.value + (chatRoomId to (current + (member.id to member)).values.toList())
                        discoveredMembers.value = mergeMembers(discoveredMembers.value, listOf(member))
                        persistState()
                    }
                    is ReticulumEvent.MemberLeft -> {
                        val chatRoomId = matchingRoom(event.roomId)?.id ?: return@collect
                        liveMembers.value = liveMembers.value + (chatRoomId to liveMembers.value[chatRoomId].orEmpty().filterNot { it.id == event.memberId })
                    }
                    is ReticulumEvent.Error -> _lastError.value = event.message
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
            scope.launch { runCatching { transport.disconnect() } }
        }
        persistState()
    }

    override fun addContact(member: ChatMember) {
        if (member.isSelf || member.id.isBlank()) return
        if (preview.previewMode.value) {
            preview.addContact(member)
            return
        }
        discoveredMembers.value = mergeMembers(discoveredMembers.value, listOf(member))
        liveContactMemberIds.value = liveContactMemberIds.value + member.id
        persistState()
    }

    override fun toggleContact(memberId: String) {
        if (preview.previewMode.value) {
            preview.toggleContact(memberId)
            return
        }
        if (memberId in liveContactMemberIds.value) {
            liveContactMemberIds.value = liveContactMemberIds.value - memberId
        } else {
            if (knownMembers.value.firstOrNull { it.id == memberId }?.isSelf == true) return
            liveContactMemberIds.value = liveContactMemberIds.value + memberId
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
        _contactRequests.value = _contactRequests.value.filterNot { it.id == requestId }
        liveContactMemberIds.value = liveContactMemberIds.value + request.sender.id
        if (message != null) receiveAcceptedDirect(request.sender, message)
        else {
            discoveredMembers.value = mergeMembers(discoveredMembers.value, listOf(request.sender))
            val acceptedMessage = ChatMessage(
                id = request.id,
                author = request.sender.displayName,
                text = request.message,
                time = request.time,
            )
            val current = liveDirectMessages.value[request.sender.id].orEmpty()
            if (current.none { it.id == acceptedMessage.id }) {
                liveDirectMessages.value = liveDirectMessages.value + (request.sender.id to (current + acceptedMessage))
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
        val connection = transport.state.value
        if (connection !is ReticulumConnectionState.Connected) {
            _lastError.value = "Connect to this network's RRC hub before sending a message."
            return
        }
        val room = preview.rooms.value.firstOrNull { it.id == roomId }
            ?: throw IllegalArgumentException("Unknown room")
        if (room.networkId != _activeHubNetworkId.value) {
            _lastError.value = "Connect to this room's RRC hub before sending a message."
            return
        }
        val outgoing = parseOutgoingChatMessage(text) ?: return
        _lastError.value = null
        try {
            transport.sendRoomMessage(room.name, outgoing.body, outgoing.isAction)
        } catch (error: Exception) {
            _lastError.value = error.message ?: "Could not send the RRC message."
        }
    }

    override suspend fun sendDirectMessage(memberId: String, text: String) {
        if (preview.previewMode.value) {
            preview.sendDirectMessage(memberId, text)
            return
        }
        val outgoing = parseOutgoingChatMessage(text) ?: return

        val member = knownMembers.value.firstOrNull { it.id == memberId }
        val address = member?.address?.let(::normalizeLxmfAddress)
        if (!transport.nodeReady.value) {
            _lastError.value = "Start the Reticulum node before sending an LXMF message."
            return
        }
        if (transport.localDeliveryAddress.value == null) {
            _lastError.value = "The LXMF delivery identity is still starting. Try again in a moment."
            return
        }
        if (address == null) {
            _lastError.value = "This contact has no valid LXMF delivery address. Add or update the contact with their 32-character address."
            return
        }
        _lastError.value = null
        try {
            transport.sendDirectMessage(address, outgoing.body)
        } catch (error: Exception) {
            _lastError.value = error.message ?: "Could not send the LXMF message."
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
        saveActiveHubNetworkId(network.id)
        transport.connect(config.copy(displayName = nicknameForNetwork(network)))
    }

    suspend fun disconnectFromHub() {
        saveActiveHubNetworkId(null)
        transport.disconnect()
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

    private fun receive(message: RrcRoomMessage) {
        val room = matchingRoom(message.roomId) ?: return
        val sender = message.senderName.ifBlank { message.senderId.take(12) }
        val time = formatTimeUtc(message.timestampMillis)
        val chatMessage = ChatMessage(
            id = message.id.ifBlank { "rrc-${message.timestampMillis}-${message.senderId}" },
            author = sender,
            text = message.body,
            time = time,
            isAction = message.isAction,
            isMine = message.isMine,
            authorId = message.senderId,
        )
        val current = liveMessages.value[room.id].orEmpty()
        if (current.none { it.id == chatMessage.id }) {
            liveMessages.value = liveMessages.value + (room.id to (current + chatMessage))
        }
        if (message.senderId.isNotBlank()) {
            val member = ChatMember(message.senderId, sender, isSelf = message.isMine, address = message.senderLxmfAddress)
            if (member.isSelf) localMember.value = member
            val current = liveMembers.value[room.id].orEmpty().associateBy { it.id }
            liveMembers.value = liveMembers.value + (room.id to (current + (member.id to (current[member.id]?.copy(
                displayName = sender,
                address = message.senderLxmfAddress ?: current[member.id]?.address,
            ) ?: member))).values.toList())
            discoveredMembers.value = mergeMembers(discoveredMembers.value, listOf(member))
            if (member.isSelf) addLiveMember(room.id, member)
        }
        persistState()
    }

    private fun receiveDirect(message: LxmfDirectMessage) {
        val address = normalizeLxmfAddress(message.address) ?: return
        val known = knownMembers.value.firstOrNull { normalizeLxmfAddress(it.address.orEmpty()) == address }
        val member = known ?: ChatMember(
            id = "lxmf-$address",
            displayName = message.senderName.ifBlank { address.take(12) },
            address = address,
        )
        if (member.id in liveBlockedMemberIds.value) return
        if (known == null && discoveredMembers.value.none { it.id == member.id }) {
            discoveredMembers.value = mergeMembers(discoveredMembers.value, listOf(member))
        }
        if (message.isMine || member.id in contactMemberIds.value) {
            receiveAcceptedDirect(member, message)
            return
        }

        val requestId = "lxmf-request-${message.id}"
        val request = ChatContactRequest(
            id = requestId,
            sender = member,
            message = message.body,
            time = formatTimeUtc(message.timestampMillis),
        )
        val previous = _contactRequests.value.firstOrNull { it.sender.id == member.id }
        messageByRequestId[requestId] = message
        _contactRequests.value = if (previous == null) {
            _contactRequests.value + request
        } else {
            messageByRequestId.remove(previous.id)
            _contactRequests.value.map { if (it.id == previous.id) request else it }
        }
        persistState()
    }

    private fun receiveAcceptedDirect(member: ChatMember, message: LxmfDirectMessage) {
        val chatMessage = ChatMessage(
            id = message.id,
            author = if (message.isMine) "you" else member.displayName,
            text = message.body,
            time = formatTimeUtc(message.timestampMillis),
            isMine = message.isMine,
            deliveryState = message.deliveryState.takeIf { message.isMine && it.isNotBlank() } ?: if (message.isMine) "queued" else null,
            authorId = member.id.takeUnless { message.isMine },
        )
        val current = liveDirectMessages.value[member.id].orEmpty()
        if (current.none { it.id == chatMessage.id }) {
            liveDirectMessages.value = liveDirectMessages.value + (member.id to (current + chatMessage))
            persistState()
        }
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

    private fun persistCurrentState() {
        val state = PersistedChatState(
            previewMode = preview.previewMode.value,
            nodeConfig = _nodeConfig.value,
            preferredHubNetworkId = _preferredHubNetworkId.value,
            localIdentityHash = _localIdentityHash.value,
            globalNickname = _globalNickname.value,
            networks = preview.persistedNetworks(),
            rooms = preview.persistedRooms(),
            activeHubNetworkId = _activeHubNetworkId.value,
            members = mergeMembers(discoveredMembers.value, listOfNotNull(localMember.value))
                .filterNot { preview.isPreviewMember(it.id) },
            contacts = liveContactMemberIds.value,
            blockedMembers = liveBlockedMemberIds.value,
            contactRequests = _contactRequests.value.filterNot { it.isPreview },
            roomMessages = liveMessages.value,
            directMessages = liveDirectMessages.value,
        )
        runCatching { stateStore.write(ChatStateCodec.encode(state)) }
            .onFailure { _lastError.value = "Could not save chat data: ${it.message ?: "storage error"}" }
    }

    private fun normalizeLxmfAddress(value: String): String? {
        val address = value.trim().lowercase().removePrefix("lxmf://")
        return address.takeIf { it.length == 32 && it.all { character -> character.digitToIntOrNull(16) != null } }
    }

    private fun mergeMembers(existing: List<ChatMember>, incoming: List<ChatMember>): List<ChatMember> {
        val merged = existing.associateByTo(linkedMapOf()) { it.id }
        incoming.forEach { member ->
            val old = merged[member.id]
            merged[member.id] = if (old == null) member else mergeMember(old, member)
        }
        return merged.values.toList()
    }

    private fun mergeMember(old: ChatMember, member: ChatMember): ChatMember = old.copy(
        displayName = member.displayName.ifBlank { old.displayName },
        isSelf = old.isSelf || member.isSelf,
        address = member.address ?: old.address,
    )

    private fun formatTimeUtc(timestampMillis: Long): String {
        val minuteOfDay = ((timestampMillis / 60_000L) % (24 * 60)).toInt().coerceAtLeast(0)
        return minuteOfDay.div(60).toString().padStart(2, '0') + ":" +
            minuteOfDay.rem(60).toString().padStart(2, '0')
    }
}
