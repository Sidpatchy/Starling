package dev.starling

import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private val EMPTY_CHAT_ROOM = ChatRoom("empty", "", "", "", 0)
private val SidebarEdgeInset = 12.dp
private val SidebarActionSize = 30.dp

@Composable
fun StarlingApp(
    repository: ChatRepository = remember { LocalPreviewChatRepository() },
    nodeController: ReticulumNodeController = remember {
        UnsupportedReticulumNodeController("The embedded RNS runtime is not available on this platform yet.")
    },
    liveChatRepository: ReticulumChatRepository? = null,
    identityManager: ReticulumIdentityManager? = null,
) {
    var selectedRoomId by rememberSaveable { mutableStateOf("general") }
    val networks by repository.networks.collectAsState()
    val rooms by repository.rooms.collectAsState()
    val previewMode by repository.previewMode.collectAsState()
    val selectedRoom = rooms.firstOrNull { it.id == selectedRoomId } ?: rooms.firstOrNull() ?: EMPTY_CHAT_ROOM
    val messages by repository.messages(selectedRoom.id).collectAsState(initial = emptyList())
    val members by repository.members(selectedRoom.id).collectAsState(initial = emptyList())
    val contactMemberIds by repository.contactMemberIds.collectAsState()
    val knownMembers by repository.knownMembers.collectAsState()
    val contactRequests by repository.contactRequests.collectAsState()
    val reticulumNodeState by nodeController.state.collectAsState()
    val reticulumConnectionState by remember(liveChatRepository) {
        liveChatRepository?.connectionState ?: flowOf(ReticulumConnectionState.Disconnected)
    }.collectAsState(initial = ReticulumConnectionState.Disconnected)
    val activeHubNetworkId by remember(liveChatRepository) {
        liveChatRepository?.activeHubNetworkId ?: flowOf(null)
    }.collectAsState(initial = null)
    val reticulumConnectionError by remember(liveChatRepository) {
        liveChatRepository?.lastError ?: flowOf(null)
    }.collectAsState(initial = null)
    val localDeliveryAddress by remember(liveChatRepository) {
        liveChatRepository?.localDeliveryAddress ?: flowOf(null)
    }.collectAsState(initial = null)
    val persistedNodeConfig by remember(liveChatRepository) {
        liveChatRepository?.nodeConfig ?: flowOf<ReticulumNodeConfig?>(null)
    }.collectAsState(initial = liveChatRepository?.nodeConfig?.value)
    val preferredHubNetworkId by remember(liveChatRepository) {
        liveChatRepository?.preferredHubNetworkId ?: flowOf(null)
    }.collectAsState(initial = liveChatRepository?.preferredHubNetworkId?.value)
    val globalNickname by remember(liveChatRepository) {
        liveChatRepository?.globalNickname ?: flowOf("Starling")
    }.collectAsState(initial = liveChatRepository?.globalNickname?.value ?: "Starling")
    val localIdentityHash by remember(liveChatRepository) {
        liveChatRepository?.localIdentityHash ?: flowOf(null)
    }.collectAsState(initial = liveChatRepository?.localIdentityHash?.value)
    val identityState by remember(identityManager) {
        identityManager?.state ?: flowOf<ReticulumIdentityState>(ReticulumIdentityState.Missing)
    }.collectAsState(initial = identityManager?.state?.value ?: ReticulumIdentityState.Missing)
    val identityBusy by remember(identityManager) { identityManager?.busy ?: flowOf(false) }.collectAsState(initial = false)
    val identityError by remember(identityManager) { identityManager?.error ?: flowOf(null) }.collectAsState(initial = null)
    val managedIdentity = (identityState as? ReticulumIdentityState.Available)?.identity
    var identityNotice by remember { mutableStateOf<String?>(null) }
    val visibleConnectionState = if (previewMode) ReticulumConnectionState.Disconnected else reticulumConnectionState
    val visibleConnectionError = if (previewMode) null else reticulumConnectionError
    val visibleHubNetworkId = if (previewMode) null else activeHubNetworkId
    val visibleDeliveryAddress = if (previewMode) null else localDeliveryAddress
    val scope = rememberCoroutineScope()
    val createIdentity: () -> Unit = {
        identityNotice = null
        scope.launch { if (identityManager?.create() == true) identityNotice = "Identity created. Export a backup before using it." }
    }
    val importIdentity: () -> Unit = {
        identityNotice = null
        scope.launch { if (identityManager?.importIdentity() == true) identityNotice = "Identity imported." }
    }
    val exportIdentity: () -> Unit = {
        identityNotice = null
        scope.launch { if (identityManager?.exportIdentity() == true) identityNotice = "Private identity backup exported. Keep it secure." }
    }
    val deleteIdentity: () -> Unit = {
        identityNotice = null
        scope.launch { if (identityManager?.delete() == true) identityNotice = "Identity deleted from this app. Backups remain valid." }
    }
    val disconnectIdentityNode: () -> Unit = {
        scope.launch {
            liveChatRepository?.disconnectFromHub()
            nodeController.disconnect()
            nodeController.refreshState()
        }
    }
    LaunchedEffect(previewMode, nodeController) {
        if (previewMode) {
            nodeController.disconnect()
        }
    }
    var showMembersPanel by rememberSaveable { mutableStateOf(true) }
    var showMembersDrawer by rememberSaveable { mutableStateOf(false) }
    var showDesktopNavigation by rememberSaveable { mutableStateOf(true) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var settingsSection by rememberSaveable { mutableStateOf(SettingsSection.APPEARANCE) }
    var showSettingsIndex by rememberSaveable { mutableStateOf(true) }
    var returnToSettingsAfterIdentity by rememberSaveable { mutableStateOf(false) }
    var showIdentityPage by rememberSaveable { mutableStateOf(false) }
    var showContactRequests by rememberSaveable { mutableStateOf(false) }
    var showAddContact by rememberSaveable { mutableStateOf(false) }
    var showAddNetwork by rememberSaveable { mutableStateOf(false) }
    var addChannelToNetworkId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDirectMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var themePreference by rememberSaveable { mutableStateOf(ThemePreference.SYSTEM) }
    var bubbleStyle by rememberSaveable { mutableStateOf(false) }
    var denseMessages by rememberSaveable { mutableStateOf(false) }
    var selectedMember by remember { mutableStateOf<ChatMember?>(null) }
    var roomToManage by remember { mutableStateOf<ChatRoom?>(null) }
    var networkToInspect by remember { mutableStateOf<ChatNetwork?>(null) }
    var autoConnectAttempted by remember(liveChatRepository) { mutableStateOf(false) }
    val hasBackDestination = showAddContact || showAddNetwork || addChannelToNetworkId != null ||
        roomToManage != null || networkToInspect != null || selectedMember != null || showMembersDrawer ||
        showContactRequests || showIdentityPage || showSettings || selectedDirectMessageId != null
    PlatformBackHandler(enabled = hasBackDestination) {
        when {
            selectedMember != null -> selectedMember = null
            roomToManage != null -> roomToManage = null
            addChannelToNetworkId != null -> addChannelToNetworkId = null
            showAddNetwork -> showAddNetwork = false
            showAddContact -> showAddContact = false
            networkToInspect != null -> networkToInspect = null
            showMembersDrawer -> showMembersDrawer = false
            showContactRequests -> showContactRequests = false
            showIdentityPage -> {
                showIdentityPage = false
                if (returnToSettingsAfterIdentity) {
                    showSettings = true
                }
                returnToSettingsAfterIdentity = false
            }
            showSettings && !showSettingsIndex -> showSettingsIndex = true
            showSettings -> showSettings = false
            selectedDirectMessageId != null -> selectedDirectMessageId = null
        }
    }
    val changePreviewMode: (Boolean) -> Unit = { enabled ->
        if (enabled) {
            selectedDirectMessageId = null
            selectedMember = null
            showContactRequests = false
            showIdentityPage = false
            networkToInspect = null
        }
        repository.setPreviewMode(enabled)
    }
    LaunchedEffect(previewMode) {
        if (previewMode) {
            selectedDirectMessageId = null
            selectedMember = null
            showIdentityPage = false
            networkToInspect = null
        }
    }
    LaunchedEffect(nodeController, liveChatRepository, identityManager) {
        identityManager?.refresh()
        if (repository.previewMode.value || !nodeController.isSupported) return@LaunchedEffect
        if (identityManager != null && identityManager.state.value !is ReticulumIdentityState.Available) return@LaunchedEffect
        nodeController.refreshState()
        val savedConfig = liveChatRepository?.nodeConfig?.value ?: return@LaunchedEffect
        if (savedConfig.mode != ReticulumRuntimeMode.EMBEDDED || savedConfig.validationError() != null) return@LaunchedEffect

        when (nodeController.state.value) {
            ReticulumNodeState.Stopped -> nodeController.start(savedConfig)
            is ReticulumNodeState.Starting, is ReticulumNodeState.PreparingRuntime -> {
                val resolvedState = withTimeoutOrNull(30_000) {
                    nodeController.state.first {
                        it is ReticulumNodeState.Running || it is ReticulumNodeState.Stopped ||
                            it is ReticulumNodeState.Failed || it is ReticulumNodeState.Unavailable
                    }
                }
                if (resolvedState is ReticulumNodeState.Stopped && !repository.previewMode.value) {
                    nodeController.start(savedConfig)
                }
            }
            else -> Unit
        }
    }
    LaunchedEffect(previewMode, networks, preferredHubNetworkId, liveChatRepository) {
        if (previewMode || preferredHubNetworkId != null) return@LaunchedEffect
        networks.firstOrNull { !it.isPreview && !it.hubDestination.isNullOrBlank() }?.let { network ->
            liveChatRepository?.savePreferredHubNetworkId(network.id)
        }
    }
    LaunchedEffect(previewMode, reticulumNodeState, networks, preferredHubNetworkId, liveChatRepository) {
        if (previewMode || reticulumNodeState is ReticulumNodeState.Stopped ||
            reticulumNodeState is ReticulumNodeState.Failed || reticulumNodeState is ReticulumNodeState.Unavailable
        ) {
            autoConnectAttempted = false
            return@LaunchedEffect
        }
        if (reticulumNodeState !is ReticulumNodeState.Running || autoConnectAttempted) return@LaunchedEffect

        val chat = liveChatRepository ?: return@LaunchedEffect
        val network = networks.firstOrNull { it.id == preferredHubNetworkId && !it.hubDestination.isNullOrBlank() }
            ?: networks.firstOrNull { !it.isPreview && !it.hubDestination.isNullOrBlank() }
            ?: return@LaunchedEffect
        delay(300)
        if (repository.previewMode.value || nodeController.state.value !is ReticulumNodeState.Running) return@LaunchedEffect
        autoConnectAttempted = true

        val connection = chat.connectionState.value
        val activeNetworkId = chat.activeHubNetworkId.value
        val isAlreadyOnTarget = activeNetworkId == network.id && (
            connection is ReticulumConnectionState.Connected ||
                connection == ReticulumConnectionState.Starting ||
                connection == ReticulumConnectionState.Connecting
            )
        if (!isAlreadyOnTarget) {
            if (connection != ReticulumConnectionState.Disconnected) chat.disconnectFromHub()
            runCatching {
                chat.connectToHub(
                    ReticulumConnectionConfig(network.hubDestination.orEmpty(), network.rrcNickname ?: globalNickname, network.id),
                )
            }
        }
    }
    val selectedDirectMessage = knownMembers.firstOrNull { it.id == selectedDirectMessageId }
    val sidebarSelection = sidebarSelectionForVisiblePage(
        roomId = selectedRoom.id.takeIf { rooms.isNotEmpty() },
        directMessageId = selectedDirectMessage?.id,
        showSettings = showSettings,
        showIdentityPage = showIdentityPage,
        showContactRequests = showContactRequests,
    )
    val directMessages by remember(selectedDirectMessageId) {
        selectedDirectMessageId?.let(repository::directMessages) ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    StarlingTheme(themePreference) {
        Surface(modifier = Modifier.fillMaxSize(), color = Canvas) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                val isWide = maxWidth >= 1000.dp
                if (isWide) {
                    Row(Modifier.fillMaxSize()) {
                        if (showDesktopNavigation) RoomSidebar(
                            networks = networks,
                            rooms = rooms,
                            previewMode = previewMode,
                            nodeState = reticulumNodeState,
                            rrcState = visibleConnectionState,
                            activeHubNetworkId = visibleHubNetworkId,
                            contacts = knownMembers.filter { it.id in contactMemberIds },
                            requestsCount = contactRequests.size,
                            selectedRoomId = sidebarSelection.roomId,
                            selectedDirectMessageId = sidebarSelection.directMessageId,
                            onRoomSelected = {
                                selectedRoomId = it
                                selectedDirectMessageId = null
                                showContactRequests = false
                                showSettings = false
                                showIdentityPage = false
                            },
                            onContactSelected = { selectedDirectMessageId = it.id; showContactRequests = false; showSettings = false; showIdentityPage = false },
                            onRequestsClick = { selectedDirectMessageId = null; showContactRequests = true; showSettings = false; showIdentityPage = false },
                            onAddContact = { showAddContact = true },
                            onAddNetwork = { showAddNetwork = true },
                            onAddChannel = { addChannelToNetworkId = it },
                            onManageRoom = { roomToManage = it },
                            onNetworkDetails = { networkToInspect = it },
                            canHideNavigation = true,
                            onHideNavigation = { showDesktopNavigation = false },
                            onSettingsClick = {
                                showSettings = true
                                showSettingsIndex = true
                                showIdentityPage = false
                                returnToSettingsAfterIdentity = false
                            },
                            onIdentityClick = {
                                showIdentityPage = true
                                showSettings = false
                                returnToSettingsAfterIdentity = false
                                showContactRequests = false
                                selectedDirectMessageId = null
                            },
                        )
                        if (showSettings) {
                            SettingsPage(
                                selectedSection = settingsSection,
                                onSectionSelected = { section ->
                                    settingsSection = section
                                    showSettingsIndex = false
                                },
                                showSectionIndex = showSettingsIndex,
                                onShowSectionIndex = { showSettingsIndex = true },
                                themePreference = themePreference,
                                onThemeChange = { themePreference = it },
                                bubbleStyle = bubbleStyle,
                                onBubbleStyleChange = { bubbleStyle = it },
                                denseMessages = denseMessages,
                                onDenseMessagesChange = { denseMessages = it },
                                previewMode = previewMode,
                                onPreviewModeChange = changePreviewMode,
                                networks = networks,
                                nodeConfig = persistedNodeConfig,
                                onNodeConfigChange = { config -> liveChatRepository?.saveNodeConfig(config) },
                                globalNickname = globalNickname,
                                preferredHubNetworkId = preferredHubNetworkId,
                                onPreferredHubNetworkChange = { id -> liveChatRepository?.savePreferredHubNetworkId(id) },
                                nodeState = reticulumNodeState,
                                nodeSupported = nodeController.isSupported,
                                identityAvailable = managedIdentity != null,
                                identityBusy = identityBusy,
                                onManageIdentity = {
                                    returnToSettingsAfterIdentity = true
                                    showSettings = false
                                    showIdentityPage = true
                                },
                                supportsReticulumProtocol = nodeController.supportsReticulumProtocol,
                                supportsRNodeInterfaces = nodeController.supportsRNodeInterfaces,
                                rrcState = visibleConnectionState,
                                rrcError = visibleConnectionError,
                                localDeliveryAddress = visibleDeliveryAddress,
                                showNavigationButton = !showDesktopNavigation,
                                onOpenNavigation = { showDesktopNavigation = true },
                                onStartNode = { config -> scope.launch { nodeController.start(config) } },
                                onDisconnectNode = {
                                    scope.launch {
                                        liveChatRepository?.disconnectFromHub()
                                        nodeController.disconnect()
                                    }
                                },
                                onConnectHub = { config -> scope.launch { liveChatRepository?.connectToHub(config) } },
                                onDisconnectHub = { scope.launch { liveChatRepository?.disconnectFromHub() } },
                                onBack = { showSettings = false; showSettingsIndex = true },
                            )
                        } else if (showContactRequests) {
                            ContactRequestsPage(
                                requests = contactRequests,
                                onAccept = { request -> repository.acceptContactRequest(request.id) },
                                onIgnore = { request -> repository.ignoreContactRequest(request.id) },
                                onBlock = { request -> repository.blockContactRequest(request.id) },
                                showNavigationButton = !showDesktopNavigation,
                                onOpenNavigation = { showDesktopNavigation = true },
                            )
                        } else if (showIdentityPage) {
                            IdentityPage(
                                previewMode = previewMode,
                                nodeState = reticulumNodeState,
                                rrcState = visibleConnectionState,
                                identityHash = if (identityManager != null) managedIdentity?.hash else localIdentityHash,
                                localDeliveryAddress = managedIdentity?.deliveryAddress ?: visibleDeliveryAddress,
                                identityState = identityState,
                                identityBusy = identityBusy,
                                identityError = identityError,
                                identityNotice = identityNotice,
                                identitySupported = identityManager != null,
                                onCreateIdentity = createIdentity,
                                onImportIdentity = importIdentity,
                                onExportIdentity = exportIdentity,
                                onDeleteIdentity = deleteIdentity,
                                onDisconnectNode = disconnectIdentityNode,
                                globalNickname = globalNickname,
                                networks = networks,
                                onGlobalNicknameChange = { liveChatRepository?.saveGlobalNickname(it) },
                                onNetworkNicknameChange = { id, nickname -> repository.updateNetworkNickname(id, nickname) },
                                showNavigationButton = !showDesktopNavigation,
                                onOpenNavigation = { showDesktopNavigation = true },
                                showBackButton = returnToSettingsAfterIdentity,
                                onBack = {
                                    showIdentityPage = false
                                    if (returnToSettingsAfterIdentity) {
                                        showSettings = true
                                    }
                                    returnToSettingsAfterIdentity = false
                                },
                                onManageNetwork = {
                                    showIdentityPage = false
                                    settingsSection = SettingsSection.NETWORK
                                    showSettingsIndex = false
                                    returnToSettingsAfterIdentity = false
                                    showSettings = true
                                },
                            )
                        } else if (selectedDirectMessage != null) {
                            DirectMessageContent(
                                member = selectedDirectMessage,
                                messages = directMessages,
                                bubbleStyle = bubbleStyle,
                                denseMessages = denseMessages,
                                isLive = visibleDeliveryAddress != null,
                                error = visibleConnectionError,
                                onMemberClick = { selectedMember = selectedDirectMessage },
                                showNavigationButton = !showDesktopNavigation,
                                onOpenNavigation = { showDesktopNavigation = true },
                                onSend = { text -> scope.launch { repository.sendDirectMessage(selectedDirectMessage.id, text) } },
                            )
                        } else if (rooms.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                EmptyNetworkContent(
                                    onAddNetwork = { showAddNetwork = true },
                                    onSettings = { showSettings = true; showSettingsIndex = true },
                                )
                                if (!showDesktopNavigation) {
                                    NavigationToggle(Modifier.align(Alignment.TopStart)) { showDesktopNavigation = true }
                                }
                            }
                        } else {
                            ChatContent(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                room = selectedRoom,
                                networks = networks,
                                messages = messages,
                                members = members,
                                contactMemberIds = contactMemberIds,
                                bubbleStyle = bubbleStyle,
                                denseMessages = denseMessages,
                                compact = false,
                                membersVisible = showMembersPanel,
                                isLive = visibleConnectionState is ReticulumConnectionState.Connected && visibleHubNetworkId == selectedRoom.networkId,
                                error = visibleConnectionError,
                                onToggleMembers = { showMembersPanel = !showMembersPanel },
                                showNavigationButton = !showDesktopNavigation,
                                onOpenNavigation = { showDesktopNavigation = true },
                                onMemberClick = { selectedMember = it },
                                onSend = { text -> scope.launch { repository.send(selectedRoom.id, text) } },
                            )
                        }
                        if (rooms.isNotEmpty() && showMembersPanel && !showSettings && !showContactRequests && !showIdentityPage && selectedDirectMessage == null) {
                            MembersPane(
                                members = members,
                                contactMemberIds = contactMemberIds,
                                onMemberClick = { selectedMember = it },
                            )
                        }
                    }
                } else {
                    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            ModalDrawerSheet(drawerContainerColor = Paper) {
                                RoomSidebar(
                                    networks = networks,
                                    rooms = rooms,
                                    previewMode = previewMode,
                                    nodeState = reticulumNodeState,
                                    rrcState = visibleConnectionState,
                                    activeHubNetworkId = visibleHubNetworkId,
                                    contacts = knownMembers.filter { it.id in contactMemberIds },
                                    requestsCount = contactRequests.size,
                                    selectedRoomId = sidebarSelection.roomId,
                                    selectedDirectMessageId = sidebarSelection.directMessageId,
                                    onRoomSelected = { roomId ->
                                        selectedRoomId = roomId
                                        selectedDirectMessageId = null
                                        showContactRequests = false
                                        showSettings = false
                                        showIdentityPage = false
                                        scope.launch { drawerState.close() }
                                    },
                                    onContactSelected = { member ->
                                        selectedDirectMessageId = member.id
                                        showContactRequests = false
                                        showSettings = false
                                        showIdentityPage = false
                                        scope.launch { drawerState.close() }
                                    },
                                    onRequestsClick = {
                                        selectedDirectMessageId = null
                                        showContactRequests = true
                                        showSettings = false
                                        showIdentityPage = false
                                        scope.launch { drawerState.close() }
                                    },
                                    onAddContact = { showAddContact = true },
                                    onAddNetwork = { showAddNetwork = true },
                                    onAddChannel = {
                                        addChannelToNetworkId = it
                                        scope.launch { drawerState.close() }
                                    },
                                    onManageRoom = {
                                        roomToManage = it
                                        scope.launch { drawerState.close() }
                                    },
                                    onNetworkDetails = {
                                        networkToInspect = it
                                        scope.launch { drawerState.close() }
                                    },
                                    onSettingsClick = {
                                        showSettings = true
                                        showSettingsIndex = true
                                        showIdentityPage = false
                                        returnToSettingsAfterIdentity = false
                                        showMembersDrawer = false
                                        scope.launch { drawerState.close() }
                                    },
                                    onIdentityClick = {
                                        showIdentityPage = true
                                        showSettings = false
                                        returnToSettingsAfterIdentity = false
                                        showContactRequests = false
                                        selectedDirectMessageId = null
                                        scope.launch { drawerState.close() }
                                    },
                                )
                            }
                        },
                    ) {
                        if (showSettings) {
                            SettingsPage(
                                selectedSection = settingsSection,
                                onSectionSelected = { section ->
                                    settingsSection = section
                                    showSettingsIndex = false
                                },
                                showSectionIndex = showSettingsIndex,
                                onShowSectionIndex = { showSettingsIndex = true },
                                themePreference = themePreference,
                                onThemeChange = { themePreference = it },
                                bubbleStyle = bubbleStyle,
                                onBubbleStyleChange = { bubbleStyle = it },
                                denseMessages = denseMessages,
                                onDenseMessagesChange = { denseMessages = it },
                                previewMode = previewMode,
                                onPreviewModeChange = changePreviewMode,
                                networks = networks,
                                nodeConfig = persistedNodeConfig,
                                onNodeConfigChange = { config -> liveChatRepository?.saveNodeConfig(config) },
                                globalNickname = globalNickname,
                                preferredHubNetworkId = preferredHubNetworkId,
                                onPreferredHubNetworkChange = { id -> liveChatRepository?.savePreferredHubNetworkId(id) },
                                nodeState = reticulumNodeState,
                                nodeSupported = nodeController.isSupported,
                                identityAvailable = managedIdentity != null,
                                identityBusy = identityBusy,
                                onManageIdentity = {
                                    returnToSettingsAfterIdentity = true
                                    showSettings = false
                                    showIdentityPage = true
                                    scope.launch { drawerState.close() }
                                },
                                supportsReticulumProtocol = nodeController.supportsReticulumProtocol,
                                supportsRNodeInterfaces = nodeController.supportsRNodeInterfaces,
                                rrcState = visibleConnectionState,
                                rrcError = visibleConnectionError,
                                localDeliveryAddress = visibleDeliveryAddress,
                                onStartNode = { config -> scope.launch { nodeController.start(config) } },
                                onDisconnectNode = {
                                    scope.launch {
                                        liveChatRepository?.disconnectFromHub()
                                        nodeController.disconnect()
                                    }
                                },
                                onConnectHub = { config -> scope.launch { liveChatRepository?.connectToHub(config) } },
                                onDisconnectHub = { scope.launch { liveChatRepository?.disconnectFromHub() } },
                                onBack = { showSettings = false; showSettingsIndex = true },
                            )
                        } else if (showContactRequests) {
                            ContactRequestsPage(
                                requests = contactRequests,
                                onAccept = { request -> repository.acceptContactRequest(request.id) },
                                onIgnore = { request -> repository.ignoreContactRequest(request.id) },
                                onBlock = { request -> repository.blockContactRequest(request.id) },
                                compact = true,
                                onOpenNavigation = { scope.launch { drawerState.open() } },
                            )
                        } else if (showIdentityPage) {
                            IdentityPage(
                                previewMode = previewMode,
                                nodeState = reticulumNodeState,
                                rrcState = visibleConnectionState,
                                identityHash = if (identityManager != null) managedIdentity?.hash else localIdentityHash,
                                localDeliveryAddress = managedIdentity?.deliveryAddress ?: visibleDeliveryAddress,
                                identityState = identityState,
                                identityBusy = identityBusy,
                                identityError = identityError,
                                identityNotice = identityNotice,
                                identitySupported = identityManager != null,
                                onCreateIdentity = createIdentity,
                                onImportIdentity = importIdentity,
                                onExportIdentity = exportIdentity,
                                onDeleteIdentity = deleteIdentity,
                                onDisconnectNode = disconnectIdentityNode,
                                globalNickname = globalNickname,
                                networks = networks,
                                onGlobalNicknameChange = { liveChatRepository?.saveGlobalNickname(it) },
                                onNetworkNicknameChange = { id, nickname -> repository.updateNetworkNickname(id, nickname) },
                                compact = true,
                                onOpenNavigation = { scope.launch { drawerState.open() } },
                                showBackButton = returnToSettingsAfterIdentity,
                                onBack = {
                                    showIdentityPage = false
                                    if (returnToSettingsAfterIdentity) {
                                        showSettings = true
                                    }
                                    returnToSettingsAfterIdentity = false
                                },
                                onManageNetwork = {
                                    showIdentityPage = false
                                    settingsSection = SettingsSection.NETWORK
                                    showSettingsIndex = false
                                    returnToSettingsAfterIdentity = false
                                    showSettings = true
                                },
                            )
                        } else if (selectedDirectMessage != null) {
                            DirectMessageContent(
                                member = selectedDirectMessage,
                                messages = directMessages,
                                bubbleStyle = bubbleStyle,
                                denseMessages = denseMessages,
                                isLive = visibleDeliveryAddress != null,
                                error = visibleConnectionError,
                                compact = true,
                                onOpenNavigation = { scope.launch { drawerState.open() } },
                                onMemberClick = { selectedMember = selectedDirectMessage },
                                onSend = { text -> scope.launch { repository.sendDirectMessage(selectedDirectMessage.id, text) } },
                            )
                        } else if (rooms.isEmpty()) {
                            EmptyNetworkContent(
                                onAddNetwork = { showAddNetwork = true },
                                onSettings = { showSettings = true; showSettingsIndex = true },
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else Box(Modifier.fillMaxSize()) {
                            ChatContent(
                                room = selectedRoom,
                                networks = networks,
                                messages = messages,
                                members = members,
                                contactMemberIds = contactMemberIds,
                                bubbleStyle = bubbleStyle,
                                denseMessages = denseMessages,
                                compact = true,
                                membersVisible = false,
                                isLive = visibleConnectionState is ReticulumConnectionState.Connected && visibleHubNetworkId == selectedRoom.networkId,
                                error = visibleConnectionError,
                                onToggleMembers = { showMembersDrawer = true },
                                onOpenNavigation = { scope.launch { drawerState.open() } },
                                onMemberClick = { selectedMember = it },
                                onSend = { text -> scope.launch { repository.send(selectedRoom.id, text) } },
                            )
                            AnimatedVisibility(
                                visible = showMembersDrawer,
                                modifier = Modifier.fillMaxSize(),
                                enter = fadeIn(),
                                exit = fadeOut(),
                            ) {
                                Box(Modifier.fillMaxSize()) {
                                    Box(
                                        Modifier.fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.36f))
                                            .clickable { showMembersDrawer = false },
                                    )
                                    Row(Modifier.fillMaxSize()) {
                                        Spacer(Modifier.weight(1f))
                                        AnimatedVisibility(
                                            visible = showMembersDrawer,
                                            modifier = Modifier.widthIn(max = 320.dp).fillMaxHeight(),
                                            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                                            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                                        ) {
                                            MembersPane(
                                                members = members,
                                                contactMemberIds = contactMemberIds,
                                                drawer = true,
                                                onMemberClick = {
                                                    selectedMember = it
                                                    showMembersDrawer = false
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            networkToInspect?.let { network ->
                NetworkStatusDialog(
                    network = network,
                    previewMode = previewMode,
                    nodeState = reticulumNodeState,
                    rrcState = visibleConnectionState,
                    activeHubNetworkId = visibleHubNetworkId,
                    onDismiss = { networkToInspect = null },
                )
            }

            if (showAddContact) {
                AddContactDialog(
                    previewMode = previewMode,
                    onAdd = { displayName, address ->
                        if (previewMode) {
                            val slug = displayName.trim().lowercase().filter { it.isLetterOrDigit() || it == '-' }
                                .ifBlank { "contact" }
                            repository.addContact(ChatMember("preview-local-$slug", displayName.trim()))
                        } else {
                            val normalizedAddress = address.trim()
                            val idAddress = normalizedAddress.removePrefix("lxmf://").removePrefix("LXMF://").lowercase()
                            repository.addContact(ChatMember("lxmf-$idAddress", displayName.trim(), address = normalizedAddress))
                        }
                        showAddContact = false
                    },
                    onDismiss = { showAddContact = false },
                )
            }

            if (showAddNetwork) {
                AddNetworkDialog(
                    previewMode = previewMode,
                    onAdd = { name, destination ->
                        runCatching { repository.addNetwork(name, destination) }
                            .onSuccess { showAddNetwork = false }
                    },
                    onDismiss = { showAddNetwork = false },
                )
            }

            addChannelToNetworkId?.let { networkId ->
                val network = networks.firstOrNull { it.id == networkId }
                if (network != null) {
                    AddChannelDialog(
                        networkName = network.name,
                        previewMode = previewMode,
                        onAdd = { name, topic ->
                            val room = repository.addRoom(networkId, name, topic)
                            selectedRoomId = room.id
                            selectedDirectMessageId = null
                            showContactRequests = false
                            showSettings = false
                            addChannelToNetworkId = null
                        },
                        onDismiss = { addChannelToNetworkId = null },
                    )
                }
            }

            roomToManage?.let { room ->
                ManageChannelDialog(
                    room = room,
                    networkName = networks.firstOrNull { it.id == room.networkId }?.name.orEmpty(),
                    onRemoveFromApp = {
                        if (selectedRoomId == room.id) {
                            selectedRoomId = rooms.firstOrNull { it.id != room.id }?.id.orEmpty()
                        }
                        repository.removeRoom(room.id)
                        roomToManage = null
                    },
                    onDismiss = { roomToManage = null },
                )
            }

            selectedMember?.let { member ->
                MemberProfileDialog(
                    member = if (previewMode) member.copy(address = null) else member,
                    sharedRooms = repository.sharedRooms(member.id),
                    isContact = member.id in contactMemberIds,
                    onToggleContact = { repository.toggleContact(member.id) },
                    onDismiss = { selectedMember = null },
                )
            }
        }
    }
}

@Composable
private fun ChatContent(
    modifier: Modifier = Modifier,
    room: ChatRoom,
    networks: List<ChatNetwork>,
    messages: List<ChatMessage>,
    members: List<ChatMember>,
    contactMemberIds: Set<String>,
    bubbleStyle: Boolean,
    denseMessages: Boolean,
    compact: Boolean,
    membersVisible: Boolean,
    isLive: Boolean = false,
    error: String? = null,
    onToggleMembers: () -> Unit,
    showNavigationButton: Boolean = false,
    onOpenNavigation: () -> Unit = {},
    onMemberClick: (ChatMember) -> Unit,
    onSend: (String) -> Unit,
) {
    var systemExpansionScrollRequest by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    LaunchedEffect(systemExpansionScrollRequest) {
        if (systemExpansionScrollRequest > 0) {
            withFrameNanos { }
            listState.animateScrollToItem(messages.size)
        }
    }
    Column(modifier.fillMaxSize()) {
        ChatHeader(
            room = room,
            networks = networks,
            compact = compact,
            showNavigationButton = showNavigationButton,
            onOpenNavigation = onOpenNavigation,
            memberCount = members.size,
            membersVisible = membersVisible,
            onToggleMembers = onToggleMembers,
        )
        if (isLive) LiveNotice("Connected to an RRC hub. Messages are live and are not stored by RRC.") else PreviewNotice()
        error?.let {
            Text(
                it,
                modifier = Modifier.fillMaxWidth().background(Color(0x22C44545)).padding(horizontal = 18.dp, vertical = 8.dp),
                color = Color(0xFFC44545),
                fontSize = 11.sp,
            )
        }

        LaunchedEffect(room.id, messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size)
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().preferredFrameRate(120f),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = if (compact) 12.dp else 20.dp,
                vertical = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(if (denseMessages) 0.dp else 3.dp),
        ) {
            item { DayDivider() }
            items(messages, key = { it.id }) { message ->
                val author = members.firstOrNull { it.id == (message.authorId ?: message.author) }
                    ?: members.firstOrNull { it.displayName.equals(message.author, ignoreCase = true) }
                MessageRow(
                    message = message,
                    member = author,
                    bubbleStyle = bubbleStyle,
                    denseMessages = denseMessages,
                    onMemberClick = { author?.let(onMemberClick) },
                    onSystemExpansion = {
                        if (!listState.canScrollForward) systemExpansionScrollRequest++
                    },
                )
            }
        }

        MessageComposer(onSend = onSend)
    }
}

@Composable
private fun DirectMessageContent(
    member: ChatMember,
    messages: List<ChatMessage>,
    bubbleStyle: Boolean,
    denseMessages: Boolean,
    isLive: Boolean = false,
    error: String? = null,
    compact: Boolean = false,
    showNavigationButton: Boolean = false,
    onOpenNavigation: () -> Unit = {},
    onMemberClick: () -> Unit,
    onSend: (String) -> Unit,
) {
    var systemExpansionScrollRequest by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    LaunchedEffect(systemExpansionScrollRequest) {
        if (systemExpansionScrollRequest > 0 && messages.isNotEmpty()) {
            withFrameNanos { }
            listState.animateScrollToItem(messages.size)
        }
    }
    Column(Modifier.fillMaxSize().background(Canvas)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(68.dp).background(Paper)
                .padding(horizontal = if (compact) 8.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (compact || showNavigationButton) {
                NavigationToggle(onClick = onOpenNavigation)
                Spacer(Modifier.width(6.dp))
            }
            MemberAvatar(member, size = 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                val palette = LocalStarlingPalette.current
                Text(member.displayName, color = ircNickTextColor(member.displayName, palette == DarkPalette), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Direct message", color = Muted, fontSize = 11.sp)
            }
            TextButton(onClick = onMemberClick) { Text("Profile", color = Accent) }
        }
        if (isLive) LiveNotice("Connected to LXMF. Delivery may wait for the recipient’s Reticulum path.")
        else PreviewNotice("Direct messages are local preview only — start Reticulum to use LXMF.")
        if (error != null) {
            Text(error, modifier = Modifier.fillMaxWidth().background(Color(0x22C44545)).padding(horizontal = 18.dp, vertical = 8.dp), color = Color(0xFFC44545), fontSize = 11.sp)
        }

        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MemberAvatar(member, size = 52.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("No messages yet", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(5.dp))
                    Text("Send a message to start this conversation.", color = Muted, fontSize = 12.sp)
                }
            }
        } else {
            LaunchedEffect(messages.size) {
                listState.animateScrollToItem(messages.size)
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().preferredFrameRate(120f),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = if (compact) 12.dp else 20.dp,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(
                    if (denseMessages) 0.dp else if (bubbleStyle) 4.dp else 2.dp,
                ),
            ) {
                item { DayDivider() }
                items(messages, key = { it.id }) { message ->
                    MessageRow(
                        message = message,
                        member = if (!message.isMine && (message.authorId == member.id || message.author == member.id)) member else null,
                        bubbleStyle = bubbleStyle,
                        denseMessages = denseMessages,
                        onMemberClick = onMemberClick,
                        onSystemExpansion = {
                            if (!listState.canScrollForward) systemExpansionScrollRequest++
                        },
                    )
                }
            }
        }
        MessageComposer(onSend = onSend, supportsSlashCommands = false)
    }
}

@Composable
private fun ContactRequestsPage(
    requests: List<ChatContactRequest>,
    onAccept: (ChatContactRequest) -> Unit,
    onIgnore: (ChatContactRequest) -> Unit,
    onBlock: (ChatContactRequest) -> Unit,
    compact: Boolean = false,
    showNavigationButton: Boolean = false,
    onOpenNavigation: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(Canvas)) {
        Row(
            Modifier.fillMaxWidth().height(68.dp).background(Paper)
                .padding(horizontal = if (compact) 8.dp else 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (compact || showNavigationButton) {
                NavigationToggle(onClick = onOpenNavigation)
                Spacer(Modifier.width(6.dp))
            }
            Text("Message requests", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            if (requests.isNotEmpty()) Text(requests.size.toString(), color = Accent, fontSize = 12.sp)
        }
        if (requests.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                Text("You’re all caught up. New messages from unknown senders will appear here.", color = Muted, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).preferredFrameRate(120f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(requests, key = { it.id }) { request ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Paper)
                            .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(18.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MemberAvatar(request.sender, size = 38.dp)
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                val palette = LocalStarlingPalette.current
                                Text(request.sender.displayName, color = ircNickTextColor(request.sender.displayName, palette == DarkPalette), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Unknown sender · ${request.time}", color = Muted, fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(request.message, color = Ink, fontSize = 13.sp, lineHeight = 19.sp)
                        request.sender.address?.takeUnless { request.isPreview }?.let {
                            Spacer(Modifier.height(7.dp))
                            Text("LXMF address: $it", color = Muted, fontSize = 10.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { onAccept(request) }) { Text("Accept", color = Accent) }
                            TextButton(onClick = { onIgnore(request) }) { Text("Ignore", color = Muted) }
                            TextButton(onClick = { onBlock(request) }) { Text("Block", color = Color(0xFFC44545)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddContactDialog(
    previewMode: Boolean,
    onAdd: (displayName: String, address: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var displayName by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add contact", color = Ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (previewMode) "Add a demo-only contact. No LXMF address is needed and nothing is sent."
                    else "Add someone by their shareable LXMF address. Adding them saves the contact but doesn’t start a conversation.",
                    color = Muted,
                    fontSize = 12.sp,
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                if (!previewMode) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("LXMF address") },
                        singleLine = true,
                    )
                    Text("QR scanning isn’t available yet. Paste the 32-character delivery hash or an lxmf:// address.", color = Muted, fontSize = 10.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = displayName.isNotBlank() && (previewMode || address.isNotBlank()),
                onClick = { onAdd(displayName, address) },
            ) { Text("Add contact", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } },
    )
}

@Composable
private fun AddNetworkDialog(previewMode: Boolean, onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var destination by rememberSaveable { mutableStateOf("") }
    val validDestination = destination.length == 32 && destination.all { it.lowercaseChar() in "0123456789abcdef" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add network", color = Ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (previewMode) "Create a local-only demo network. Nothing is connected or sent."
                    else "Connect this network to an RRC hub destination. Channels you add will be joined by name.",
                    color = Muted,
                    fontSize = 12.sp,
                )
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Network name") }, singleLine = true)
                if (!previewMode) {
                    OutlinedTextField(
                        value = destination,
                        onValueChange = { destination = it.filter { character -> character.lowercaseChar() in "0123456789abcdef" }.take(32) },
                        label = { Text("RRC hub destination hash") },
                        supportingText = { Text("32 hexadecimal characters", color = Muted) },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && (previewMode || validDestination),
                onClick = { onAdd(name.trim(), if (previewMode) "" else destination.lowercase()) },
            ) { Text("Add network", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } },
    )
}

@Composable
internal fun NavigationToggle(modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier.size(42.dp)) {
        Icon(Icons.Filled.Menu, contentDescription = "Open navigation", tint = Accent)
    }
}

@Composable
private fun EmptyNetworkContent(
    onAddNetwork: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().background(Canvas).padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No channels to show", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add an RRC network and a channel, or turn on Local preview in Settings to explore the sample chat.",
            color = Muted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onAddNetwork, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text("Add network")
            }
            TextButton(onClick = onSettings) { Text("Settings", color = Accent) }
        }
    }
}

@Composable
private fun AddChannelDialog(
    networkName: String,
    previewMode: Boolean,
    onAdd: (name: String, topic: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var topic by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add channel", color = Ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (previewMode) "This adds a local-only channel to $networkName. Nothing is sent."
                    else "This adds a channel for $networkName. Starling joins this name on its RRC hub.",
                    color = Muted,
                    fontSize = 12.sp,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.lowercase() },
                    label = { Text("Channel name") },
                    supportingText = { Text("Channel names are lowercase.", color = Muted) },
                    singleLine = true,
                )
                OutlinedTextField(value = topic, onValueChange = { topic = it }, label = { Text("Topic (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onAdd(normalizeChannelName(name), topic) },
            ) { Text("Add channel", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } },
    )
}

@Composable
private fun ManageChannelDialog(
    room: ChatRoom,
    networkName: String,
    onRemoveFromApp: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage #${room.name}", color = Ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${networkName} · #${room.name}", color = Muted, fontSize = 12.sp)
                Text(
                    "Channel permissions and operator modes aren’t exposed by the current RRC integration yet.",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                Text(
                    "Removing the channel removes it from this app and leaves it on the hub if currently joined. It does not delete the channel for anyone else.",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onRemoveFromApp) {
                Text("Remove from app", color = Color(0xFFC44545))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done", color = Accent) } },
    )
}

@Composable
private fun RoomSidebar(
    networks: List<ChatNetwork>,
    rooms: List<ChatRoom>,
    previewMode: Boolean,
    nodeState: ReticulumNodeState,
    rrcState: ReticulumConnectionState,
    activeHubNetworkId: String?,
    contacts: List<ChatMember>,
    requestsCount: Int,
    selectedRoomId: String?,
    selectedDirectMessageId: String?,
    onRoomSelected: (String) -> Unit,
    onContactSelected: (ChatMember) -> Unit,
    onRequestsClick: () -> Unit,
    onAddContact: () -> Unit,
    onAddNetwork: () -> Unit,
    onAddChannel: (String) -> Unit,
    onManageRoom: (ChatRoom) -> Unit,
    onNetworkDetails: (ChatNetwork) -> Unit,
    canHideNavigation: Boolean = false,
    onHideNavigation: () -> Unit = {},
    onSettingsClick: () -> Unit,
    onIdentityClick: () -> Unit,
) {
    var expandedNetworkIds by remember {
        mutableStateOf<Set<String>>(networks.map { it.id }.toSet())
    }
    LaunchedEffect(networks) {
        expandedNetworkIds = expandedNetworkIds + networks.map { it.id }
    }
    val (identityDetail, identityColor) = when {
        previewMode -> "Live identity hidden in preview" to Muted
        rrcState is ReticulumConnectionState.Connected ->
            "Connected to ${rrcState.hubName?.takeIf(String::isNotBlank) ?: "RRC hub"}" to Color(0xFF48A868)
        rrcState is ReticulumConnectionState.Failed -> "Hub connection failed" to Color(0xFFC44545)
        rrcState is ReticulumConnectionState.Connecting || rrcState == ReticulumConnectionState.Starting ->
            "Connecting to hub" to Color(0xFFE1A448)
        nodeState is ReticulumNodeState.Running -> {
            val totalInterfaces = nodeState.tcpPeers.size + nodeState.rnodeInterfaces.size
            val onlineInterfaces = nodeState.onlineTcpPeerIds.size + nodeState.onlineRNodePorts.size
            when {
                nodeState.sharedInstanceConnected -> "Shared RNS instance connected" to Color(0xFF48A868)
                totalInterfaces == 0 -> "Reticulum running without interfaces" to Muted
                onlineInterfaces > 0 -> "$onlineInterfaces of $totalInterfaces interfaces online" to Color(0xFF48A868)
                else -> "Waiting for an interface" to Color(0xFFE1A448)
            }
        }
        nodeState is ReticulumNodeState.Starting || nodeState is ReticulumNodeState.PreparingRuntime ->
            "Starting Reticulum" to Color(0xFFE1A448)
        nodeState is ReticulumNodeState.Failed -> "Reticulum node failed" to Color(0xFFC44545)
        nodeState is ReticulumNodeState.Unavailable -> "Reticulum unavailable" to Muted
        else -> "Reticulum stopped" to Muted
    }

    Column(
        modifier = Modifier
            .width(250.dp)
            .fillMaxHeight()
            .background(Paper)
            .border(width = 1.dp, color = Line),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(PaleAccent),
                contentAlignment = Alignment.Center,
            ) {
                Text("S", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(11.dp))
            Column {
                Text("Starling", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("RETICULUM CHAT", fontSize = 9.sp, letterSpacing = 1.5.sp, color = Muted)
            }
            if (canHideNavigation) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onHideNavigation) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "Hide navigation", tint = Accent)
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .preferredFrameRate(120f),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 22.dp, end = SidebarEdgeInset, top = 20.dp, bottom = 6.dp)
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "NETWORKS",
                    modifier = Modifier.weight(1f),
                    color = Muted,
                    fontSize = 10.sp,
                    letterSpacing = 1.3.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onAddNetwork, modifier = Modifier.size(SidebarActionSize)) {
                    Icon(Icons.Filled.Add, contentDescription = "Add network", tint = Accent)
                }
            }
            networks.forEach { network ->
                NetworkRow(
                    network = network,
                    expanded = network.id in expandedNetworkIds,
                    connectionColor = networkConnectionColor(network, previewMode, activeHubNetworkId, rrcState, nodeState),
                    onAddChannel = { onAddChannel(network.id) },
                    onDetails = { onNetworkDetails(network) },
                    onClick = {
                        expandedNetworkIds = if (network.id in expandedNetworkIds) {
                            expandedNetworkIds - network.id
                        } else {
                            expandedNetworkIds + network.id
                        }
                    },
                )
                if (network.id in expandedNetworkIds) {
                    val networkRooms = rooms.filter { it.networkId == network.id }
                    networkRooms.forEach { room ->
                        RoomRow(
                            room = room,
                            selected = room.id == selectedRoomId,
                            onClick = { onRoomSelected(room.id) },
                            onManage = { onManageRoom(room) },
                        )
                    }
                    if (networkRooms.isEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 42.dp, end = 12.dp)
                                .clip(RoundedCornerShape(8.dp)).clickable { onAddChannel(network.id) }
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Add a channel", tint = Muted, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("Add a channel", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 22.dp, end = SidebarEdgeInset, top = 20.dp, bottom = 6.dp)
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "DIRECT MESSAGES",
                    modifier = Modifier.weight(1f),
                    color = Muted,
                    fontSize = 10.sp,
                    letterSpacing = 1.3.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onAddContact, modifier = Modifier.size(SidebarActionSize)) {
                    Icon(Icons.Filled.Add, contentDescription = "Add contact", tint = Accent)
                }
            }
            contacts.forEach { member ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = SidebarEdgeInset, vertical = 2.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (member.id == selectedDirectMessageId) PaleAccent else Color.Transparent)
                        .clickable { onContactSelected(member) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MemberAvatar(member, size = 25.dp)
                    Spacer(Modifier.width(9.dp))
                    Text(member.displayName, color = Ink, fontSize = 13.sp)
                }
            }
            if (contacts.isEmpty()) {
                Text(
                    "Add someone by LXMF address or QR.",
                    modifier = Modifier.padding(start = 22.dp, end = 16.dp, bottom = 8.dp),
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = SidebarEdgeInset, end = SidebarEdgeInset, top = 8.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(9.dp)).clickable(onClick = onRequestsClick)
                    .padding(start = 10.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Message requests", modifier = Modifier.weight(1f), color = Ink, fontSize = 12.sp)
                if (requestsCount > 0) {
                    Text(
                        requestsCount.toString(),
                        modifier = Modifier.clip(CircleShape).background(PaleAccent).padding(horizontal = 7.dp, vertical = 3.dp),
                        color = Accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = SidebarEdgeInset, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onIdentityClick).padding(horizontal = 8.dp, vertical = 9.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(identityColor))
                    Spacer(Modifier.width(8.dp))
                    Text("Identity", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Text(
                    identityDetail,
                    color = Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
            }
            IconButton(onClick = onSettingsClick, modifier = Modifier.size(SidebarActionSize)) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = Muted,
                )
            }
        }
    }
}

@Composable
private fun NetworkStatusDialog(
    network: ChatNetwork,
    previewMode: Boolean,
    nodeState: ReticulumNodeState,
    rrcState: ReticulumConnectionState,
    activeHubNetworkId: String?,
    onDismiss: () -> Unit,
) {
    val isTargetNetwork = activeHubNetworkId == network.id
    val hubStatus = when {
        previewMode || network.isPreview -> "Sample network. Live hub status is hidden in preview."
        !isTargetNetwork -> "Not connected. This hub is not the active connection."
        rrcState is ReticulumConnectionState.Connected ->
            "Connected${rrcState.hubName?.takeIf(String::isNotBlank)?.let { " to $it" }.orEmpty()}."
        rrcState is ReticulumConnectionState.Starting || rrcState == ReticulumConnectionState.Connecting -> "Connecting to this hub…"
        rrcState is ReticulumConnectionState.Failed -> rrcState.message
        else -> "Disconnected from this hub."
    }
    val nodeStatus = when (val state = nodeState) {
        is ReticulumNodeState.Running -> when {
            state.sharedInstanceConnected -> "Attached to an existing shared instance."
            else -> {
                val online = state.onlineTcpPeerIds.size + state.onlineRNodePorts.size
                val total = state.tcpPeers.size + state.rnodeInterfaces.size
                if (total == 0) "Reticulum is running without configured interfaces."
                else "$online of $total configured interfaces online."
            }
        }
        is ReticulumNodeState.Starting -> "Reticulum is starting…"
        is ReticulumNodeState.PreparingRuntime -> state.detail
        is ReticulumNodeState.Failed -> state.message
        is ReticulumNodeState.Unavailable -> state.reason
        ReticulumNodeState.Stopped -> "Reticulum is stopped."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${network.name} status", color = Ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Hub connection", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(hubStatus, color = Ink, fontSize = 13.sp, lineHeight = 18.sp)
                if (!previewMode) {
                    network.hubDestination?.let { destination ->
                        Text("RRC destination", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        SelectionContainer {
                            Text(destination, color = Muted, fontSize = 11.sp)
                        }
                    }
                    Text("Reticulum node", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(nodeStatus, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                    Text("Long-press a hub on touch, or right-click it on desktop, to view this status.", color = Muted, fontSize = 10.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Accent) } },
    )
}

@Composable
private fun networkConnectionColor(
    network: ChatNetwork,
    previewMode: Boolean,
    activeHubNetworkId: String?,
    rrcState: ReticulumConnectionState,
    nodeState: ReticulumNodeState,
): Color {
    if (previewMode || network.isPreview || activeHubNetworkId != network.id) return Muted
    return when {
        rrcState is ReticulumConnectionState.Connected -> Color(0xFF48A868)
        rrcState == ReticulumConnectionState.Starting || rrcState == ReticulumConnectionState.Connecting -> Color(0xFFE1A448)
        rrcState is ReticulumConnectionState.Failed || nodeState is ReticulumNodeState.Failed -> Color(0xFFC44545)
        nodeState is ReticulumNodeState.Starting || nodeState is ReticulumNodeState.PreparingRuntime -> Color(0xFFE1A448)
        else -> Muted
    }
}

@Composable
private fun NetworkRow(
    network: ChatNetwork,
    expanded: Boolean,
    connectionColor: Color,
    onAddChannel: () -> Unit,
    onDetails: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SidebarEdgeInset, vertical = 2.dp)
            .clip(RoundedCornerShape(9.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                .pointerInput(onDetails) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                event.changes.forEach { it.consume() }
                                onDetails()
                            }
                        }
                    }
                }
                .combinedClickable(onClick = onClick, onLongClick = onDetails)
                .padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse network" else "Expand network",
                tint = Muted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(7.dp))
            Box(Modifier.size(7.dp).clip(CircleShape).background(connectionColor))
            Spacer(Modifier.width(8.dp))
            Text(
                network.name,
                color = Ink,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        IconButton(onClick = onAddChannel, modifier = Modifier.size(SidebarActionSize)) {
            Icon(Icons.Filled.Add, contentDescription = "Add channel to ${network.name}", tint = Muted)
        }
    }
}

@Composable
private fun RoomRow(room: ChatRoom, selected: Boolean, onClick: () -> Unit, onManage: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SidebarEdgeInset, vertical = 2.dp)
            .clip(RoundedCornerShape(9.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                .background(if (selected) PaleAccent else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(start = 31.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("#", color = if (selected) Accent else Muted, fontSize = 17.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                room.name,
                modifier = Modifier.weight(1f),
                color = if (selected) Accent else Ink,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp,
            )
            if (selected) Text(room.members.toString(), color = Accent, fontSize = 11.sp)
        }
        IconButton(onClick = onManage, modifier = Modifier.size(SidebarActionSize)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Manage #${room.name}", tint = Muted)
        }
    }
}

@Composable
private fun ChatHeader(
    room: ChatRoom,
    networks: List<ChatNetwork>,
    compact: Boolean,
    showNavigationButton: Boolean,
    memberCount: Int,
    membersVisible: Boolean,
    onToggleMembers: () -> Unit,
    onOpenNavigation: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(68.dp).background(Paper)
            .padding(horizontal = if (compact) 10.dp else 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (compact || showNavigationButton) {
            NavigationToggle(onClick = onOpenNavigation)
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f)) {
            if (compact) {
                Text("#${room.name}", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(
                    "${networks.firstOrNull { it.id == room.networkId }?.name.orEmpty()} · ${room.topic}",
                    color = Muted,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        networks.firstOrNull { it.id == room.networkId }?.name.orEmpty(),
                        color = Muted,
                        fontSize = 11.sp,
                    )
                    Text("  /  #${room.name}", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                Text(room.topic, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 20.dp))
            }
        }
        Row(
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onToggleMembers)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PersonGlyph(color = if (membersVisible || compact) Accent else Muted)
            Spacer(Modifier.width(7.dp))
            Text("$memberCount here", color = if (membersVisible || compact) Ink else Muted, fontSize = 12.sp)
            if (!compact) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (membersVisible) Icons.Filled.ChevronRight else Icons.Filled.ChevronLeft,
                    contentDescription = if (membersVisible) "Hide member list" else "Show member list",
                    tint = Muted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun MembersPane(
    members: List<ChatMember>,
    contactMemberIds: Set<String>,
    drawer: Boolean = false,
    onMemberClick: (ChatMember) -> Unit,
) {
    val contactsHere = members.filter { !it.isSelf && it.id in contactMemberIds }
    val otherMembers = members.filterNot { it in contactsHere }.sortedByDescending { it.isSelf }
    val panelModifier = if (drawer) {
        Modifier.width(282.dp).fillMaxHeight().background(Paper).padding(18.dp)
    } else {
        Modifier.width(242.dp).fillMaxHeight().background(Paper)
            .border(1.dp, Line).padding(16.dp)
    }

    Column(panelModifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PersonGlyph(Accent)
            Spacer(Modifier.width(9.dp))
            Text("HERE", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Spacer(Modifier.width(7.dp))
            Text(members.size.toString(), color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(18.dp))

        if (contactsHere.isNotEmpty()) {
            MemberSectionLabel("CONTACTS HERE")
            contactsHere.forEach { member ->
                MemberRow(
                    member = member,
                    isContact = true,
                    onClick = { onMemberClick(member) },
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        MemberSectionLabel("IN ROOM")
        otherMembers.forEach { member ->
            MemberRow(
                member = member,
                isContact = member.id in contactMemberIds,
                onClick = { if (!member.isSelf) onMemberClick(member) },
            )
        }
        if (drawer) {
            Spacer(Modifier.height(12.dp))
            Text("Tap a person to see their profile and shared rooms.", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MemberSectionLabel(label: String) {
    Text(
        label,
        modifier = Modifier.padding(start = 4.dp, bottom = 7.dp),
        color = Muted,
        fontSize = 9.sp,
        letterSpacing = 1.1.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun MemberRow(member: ChatMember, isContact: Boolean, onClick: () -> Unit) {
    val palette = LocalStarlingPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
            .clickable(enabled = !member.isSelf, onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemberAvatar(member)
        Spacer(Modifier.width(10.dp))
        Text(
            member.displayName,
            modifier = Modifier.weight(1f),
            color = ircNickTextColor(member.displayName, palette == DarkPalette),
            fontSize = 12.sp,
            fontWeight = if (member.isSelf) FontWeight.Bold else FontWeight.Medium,
        )
        if (member.isSelf) Text("YOU", color = Accent, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
        if (isContact) Text("DM", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MemberAvatar(member: ChatMember, size: androidx.compose.ui.unit.Dp = 30.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(nickAvatarColor(member.displayName)),
        contentAlignment = Alignment.Center,
    ) {
        Text(member.displayName.take(1).uppercase(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MemberProfileDialog(
    member: ChatMember,
    sharedRooms: List<ChatRoom>,
    isContact: Boolean,
    onToggleContact: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MemberAvatar(member, size = 38.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    val palette = LocalStarlingPalette.current
                    Text(member.displayName, color = ircNickTextColor(member.displayName, palette == DarkPalette), fontWeight = FontWeight.SemiBold)
                    Text("Reticulum member", color = Muted, fontSize = 11.sp)
                }
            }
        },
        text = {
            Column {
                Text("SHARED ROOMS", color = Muted, fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(7.dp))
                if (sharedRooms.isEmpty()) {
                    Text("No shared rooms", color = Muted, fontSize = 13.sp)
                } else {
                    Text(sharedRooms.joinToString("   ") { "#${it.name}" }, color = Ink, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            if (!member.isSelf) {
                TextButton(onClick = onToggleContact) {
                    Text(if (isContact) "Remove contact" else "Add contact", color = Accent)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Muted) }
        },
    )
}

@Composable
private fun PersonGlyph(color: Color) {
    ComposeCanvas(Modifier.size(17.dp)) {
        drawCircle(
            color = color,
            radius = size.width * 0.19f,
            center = Offset(size.width / 2f, size.height * 0.27f),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.16f, size.height * 0.53f),
            size = Size(size.width * 0.68f, size.height * 0.4f),
            cornerRadius = CornerRadius(size.width * 0.25f),
        )
    }
}

@Composable
private fun PreviewNotice(message: String = "Messages stay on this device for now — RRC isn’t connected yet.") {
    val isLightTheme = LocalStarlingPalette.current == LightPalette
    val bannerBackground = if (isLightTheme) Color(0xFFFFF7E8) else Color(0xFF383226)
    val bannerAccent = if (isLightTheme) Color(0xFF986520) else Color(0xFFE1B86A)
    val bannerText = if (isLightTheme) Color(0xFF826B4B) else Color(0xFFC6B895)
    Row(
        modifier = Modifier.fillMaxWidth().background(bannerBackground).padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("PREVIEW", color = bannerAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.width(10.dp))
        Text(message, modifier = Modifier.weight(1f), color = bannerText, fontSize = 11.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun LiveNotice(message: String) {
    val isLightTheme = LocalStarlingPalette.current == LightPalette
    val bannerBackground = if (isLightTheme) Color(0xFFEAF7EF) else Color(0xFF26372D)
    val bannerAccent = if (isLightTheme) Color(0xFF2D7D4A) else Color(0xFF81C995)
    val bannerText = if (isLightTheme) Color(0xFF426B50) else Color(0xFFB1D6BA)
    Row(
        modifier = Modifier.fillMaxWidth().background(bannerBackground).padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("LIVE", color = bannerAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.width(10.dp))
        Text(message, modifier = Modifier.weight(1f), color = bannerText, fontSize = 11.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun DayDivider() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f).height(1.dp).background(Line))
        Text("TODAY", modifier = Modifier.padding(horizontal = 14.dp), color = Muted, fontSize = 9.sp, letterSpacing = 1.2.sp)
        Spacer(Modifier.weight(1f).height(1.dp).background(Line))
    }
}

@Composable
private fun MessageRow(
    message: ChatMessage,
    member: ChatMember?,
    bubbleStyle: Boolean,
    denseMessages: Boolean,
    onMemberClick: () -> Unit,
    onSystemExpansion: () -> Unit = {},
) {
    val authorName = member?.displayName ?: message.author
    val palette = LocalStarlingPalette.current
    val authorColor = ircNickTextColor(authorName, darkTheme = palette == DarkPalette)
    if (message.isSystem) {
        SystemMessageRow(message, denseMessages, bubbleStyle, onSystemExpansion)
    } else if (bubbleStyle) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = if (denseMessages) 0.dp else 1.dp),
            horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(15.dp))
                    .background(if (message.isMine) PaleAccent else Paper)
                    .padding(horizontal = 10.dp, vertical = if (denseMessages) 4.dp else 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        authorName,
                        modifier = Modifier.clickable(enabled = member != null && !member.isSelf, onClick = onMemberClick),
                        color = authorColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(message.time, color = Muted, fontSize = 10.sp)
                    deliveryMarker(message.deliveryState)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = markdown(message.text, palette),
                    color = Ink,
                    fontSize = 13.sp,
                    lineHeight = if (denseMessages) 16.sp else 17.sp,
                    fontStyle = if (message.isAction) FontStyle.Italic else FontStyle.Normal,
                )
            }
        }
    } else {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = if (denseMessages) 0.dp else 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(message.time, modifier = Modifier.width(40.dp).padding(top = 1.dp), color = Muted, fontSize = 10.sp)
        deliveryMarker(message.deliveryState)
        if (message.isAction) {
            Text(
                "$authorName ",
                modifier = Modifier
                    .clickable(enabled = member != null && !member.isSelf, onClick = onMemberClick)
                    .alignBy(FirstBaseline),
                color = authorColor,
                fontSize = 13.sp,
                fontStyle = FontStyle.Italic,
            )
            Text(
                text = markdown(message.text, palette),
                modifier = Modifier.weight(1f).alignBy(FirstBaseline),
                color = Ink,
                fontSize = 13.sp,
                lineHeight = if (denseMessages) 16.sp else 17.sp,
                fontStyle = FontStyle.Italic,
            )
        } else {
            Text(
                "$authorName:",
                modifier = Modifier
                    .clickable(enabled = member != null && !member.isSelf, onClick = onMemberClick)
                    .alignBy(FirstBaseline),
                color = authorColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
                Spacer(Modifier.width(5.dp))
            Text(
                text = markdown(message.text, palette),
                modifier = Modifier.weight(1f).alignBy(FirstBaseline),
                color = Ink,
                fontSize = 13.sp,
                lineHeight = if (denseMessages) 16.sp else 18.sp,
            )
        }
    }
    }
}

@Composable
private fun SystemMessageRow(
    message: ChatMessage,
    denseMessages: Boolean,
    bubbleStyle: Boolean,
    onSystemExpansion: () -> Unit,
) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val isErrorMessage = message.systemEvents.any {
        it.kind == ChatSystemEventKind.ERROR || it.kind == ChatSystemEventKind.COMMAND_ERROR
    }
    val isStandaloneSystemMessage = isErrorMessage || message.systemEvents.any { it.kind == ChatSystemEventKind.HELP }
    val canExpand = message.systemEvents.size > 1 && !isStandaloneSystemMessage
    val summaryMaxLines = if (expanded || isStandaloneSystemMessage) Int.MAX_VALUE else 2
    val textColor = if (isErrorMessage) Color(0xFFC44545) else Muted
    val verticalPadding = if (denseMessages) 1.dp else 4.dp

    if (bubbleStyle) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = verticalPadding),
            horizontalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 720.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (isErrorMessage) Color(0x22C44545) else PaleAccent.copy(alpha = 0.55f))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message.text,
                        modifier = Modifier.weight(1f),
                        color = textColor,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        textAlign = TextAlign.Center,
                        maxLines = summaryMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (canExpand) SystemEventExpandButton(expanded) {
                        onSystemExpansion()
                        expanded = !expanded
                    }
                }
                if (expanded && canExpand) {
                    SystemEventDetails(message.systemEvents)
                } else {
                    Text(
                        if (canExpand) "${message.systemEvents.size} events · last at ${message.time}" else message.time,
                        color = textColor,
                        fontSize = 9.sp,
                    )
                }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(vertical = verticalPadding)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(message.time, modifier = Modifier.width(40.dp).padding(top = 1.dp), color = Muted, fontSize = 10.sp)
                Text(
                    message.text,
                    modifier = Modifier.weight(1f).padding(top = 1.dp),
                    color = textColor,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontStyle = FontStyle.Italic,
                    maxLines = summaryMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                if (canExpand) SystemEventExpandButton(expanded) {
                    onSystemExpansion()
                    expanded = !expanded
                }
            }
            if (expanded && canExpand) {
                SystemEventDetails(message.systemEvents, modifier = Modifier.padding(start = 40.dp, top = 4.dp))
            }
        }
    }
}

@Composable
private fun SystemEventExpandButton(expanded: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Collapse system events" else "Expand system events",
            tint = Muted,
        )
    }
}

@Composable
private fun SystemEventDetails(events: List<ChatSystemEvent>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        events.forEach { event ->
            Row(verticalAlignment = Alignment.Top) {
                Text(event.time, modifier = Modifier.width(40.dp), color = Muted, fontSize = 9.sp)
                Text(event.text, modifier = Modifier.weight(1f), color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
            }
        }
    }
}

@Composable
private fun deliveryMarker(state: String?) {
    if (state == null) return
    val (icon, description, color) = when (state) {
        "queued" -> Triple(Icons.Filled.Schedule, "Queued", Muted)
        "sending" -> Triple(Icons.Filled.Schedule, "Sending", Muted)
        "sent" -> Triple(Icons.Filled.Check, "Sent", Accent)
        "delivered" -> Triple(Icons.Filled.Check, "Delivered", Accent)
        "failed", "rejected" -> Triple(Icons.Filled.ErrorOutline, "Delivery failed", Color(0xFFC44545))
        else -> return
    }
    Icon(
        imageVector = icon,
        contentDescription = description,
        modifier = Modifier.padding(start = 4.dp).size(12.dp),
        tint = color,
    )
}

private fun markdown(source: String, palette: StarlingPalette): AnnotatedString {
    val tokenPattern = Regex("""(\*\*.+?\*\*|__.+?__|~~.+?~~|`[^`]+`|<u>.+?</u>|\+\+.+?\+\+|\*[^*\n]+\*|_[^_\n]+_)""")
    return buildAnnotatedString {
        var cursor = 0
        tokenPattern.findAll(source).forEach { match ->
            append(source.substring(cursor, match.range.first))
            val token = match.value
            val (content, style) = when {
                token.startsWith("**") -> token.removeSurrounding("**") to SpanStyle(fontWeight = FontWeight.Bold)
                token.startsWith("__") -> token.removeSurrounding("__") to SpanStyle(fontWeight = FontWeight.Bold)
                token.startsWith("~~") -> token.removeSurrounding("~~") to SpanStyle(textDecoration = TextDecoration.LineThrough)
                token.startsWith("`") -> token.removeSurrounding("`") to SpanStyle(color = palette.accent, background = palette.paleAccent)
                token.startsWith("<u>") -> token.removeSurrounding("<u>", "</u>") to SpanStyle(textDecoration = TextDecoration.Underline)
                token.startsWith("++") -> token.removeSurrounding("++") to SpanStyle(textDecoration = TextDecoration.Underline)
                token.startsWith("*") -> token.removeSurrounding("*") to SpanStyle(fontStyle = FontStyle.Italic)
                else -> token.removeSurrounding("_") to SpanStyle(fontStyle = FontStyle.Italic)
            }
            withStyle(style) { append(content) }
            cursor = match.range.last + 1
        }
        append(source.substring(cursor))
    }
}

@Composable
private fun MessageComposer(
    onSend: (String) -> Unit,
    supportsSlashCommands: Boolean = true,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var selectedSlashSuggestionIndex by rememberSaveable { mutableStateOf(0) }
    val slashSuggestions = remember(draft, supportsSlashCommands) {
        if (supportsSlashCommands) slashCommandSuggestions(draft) else emptyList()
    }
    val slashSuggestionListState = rememberLazyListState()
    LaunchedEffect(selectedSlashSuggestionIndex, slashSuggestions.size) {
        if (slashSuggestions.isNotEmpty() &&
            slashSuggestionListState.layoutInfo.visibleItemsInfo.none { it.index == selectedSlashSuggestionIndex }
        ) {
            slashSuggestionListState.animateScrollToItem(selectedSlashSuggestionIndex.coerceIn(slashSuggestions.indices))
        }
    }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val focusManager = LocalFocusManager.current
    LaunchedEffect(draft) {
        val limitedDraft = limitChatMessageLength(draft)
        if (limitedDraft != draft) draft = limitedDraft
        selectedSlashSuggestionIndex = 0
    }
    val characterCount = countChatMessageCharacters(draft)
    fun send() {
        val text = draft.trim()
        if (parseOutgoingChatMessage(text) != null) {
            onSend(text)
            draft = ""
        }
    }
    fun completeSelectedSlashCommand(): Boolean {
        val command = slashSuggestions.getOrNull(selectedSlashSuggestionIndex) ?: return false
        val completedDraft = completeSlashCommand(draft, command)
        if (completedDraft == draft) return false
        draft = completedDraft
        return true
    }

    Column(
        modifier = Modifier.fillMaxWidth().background(Canvas).padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        if (slashSuggestions.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 190.dp).clip(RoundedCornerShape(12.dp))
                    .background(Paper).border(1.dp, Line, RoundedCornerShape(12.dp))
                    .padding(6.dp),
                state = slashSuggestionListState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                itemsIndexed(slashSuggestions, key = { _, command -> command.name }) { index, command ->
                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (index == selectedSlashSuggestionIndex) PaleAccent else Color.Transparent)
                            .clickable { draft = completeSlashCommand(draft, command) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            command.usage,
                            color = if (index == selectedSlashSuggestionIndex) Accent else Ink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(command.description, color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Paper)
                    .border(1.dp, Line, RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = limitChatMessageLength(it) },
                    modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || slashSuggestions.isEmpty()) {
                            false
                        } else {
                            when (event.key) {
                                Key.Tab -> {
                                    slashSuggestions.getOrNull(selectedSlashSuggestionIndex)?.let { command ->
                                        draft = completeSlashCommand(draft, command)
                                        true
                                    } ?: false
                                }
                                Key.DirectionDown -> {
                                    selectedSlashSuggestionIndex = (selectedSlashSuggestionIndex + 1) % slashSuggestions.size
                                    true
                                }
                                Key.DirectionUp -> {
                                    selectedSlashSuggestionIndex =
                                        (selectedSlashSuggestionIndex - 1 + slashSuggestions.size) % slashSuggestions.size
                                    true
                                }
                                else -> false
                            }
                        }
                    },
                    textStyle = TextStyle(color = Ink, fontSize = 14.sp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (!completeSelectedSlashCommand()) send()
                    }),
                    decorationBox = { innerTextField ->
                        Box {
                            if (draft.isEmpty()) Text("Write a message…", color = Muted, fontSize = 14.sp)
                            innerTextField()
                        }
                    },
                )
                if (keyboardVisible) {
                    IconButton(onClick = { focusManager.clearFocus() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.KeyboardHide, contentDescription = "Hide keyboard", tint = Muted)
                    }
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardReturn,
                        contentDescription = "Press Enter to send",
                        modifier = Modifier.padding(start = 8.dp).size(16.dp),
                        tint = Muted,
                    )
                }
            }
            Text(
                "$characterCount / $MAX_CHAT_MESSAGE_CHARACTERS characters",
                modifier = Modifier.align(Alignment.End).padding(top = 4.dp, end = 4.dp),
                color = if (characterCount >= MAX_CHAT_MESSAGE_CHARACTERS) Color(0xFFC44545) else Muted,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.width(10.dp))
        Button(
            onClick = { send() },
            enabled = parseOutgoingChatMessage(draft) != null,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, disabledContainerColor = Line),
        ) {
            Text("Send", modifier = Modifier.padding(horizontal = 5.dp), fontWeight = FontWeight.SemiBold)
        }
        }
    }
}

private fun ircNickTextColor(nickname: String, darkTheme: Boolean): Color =
    hslColor(ircNickHue(nickname), saturation = 0.70f, lightness = if (darkTheme) 0.72f else 0.25f)

private fun nickAvatarColor(nickname: String): Color =
    hslColor(ircNickHue(nickname), saturation = 0.72f, lightness = 0.27f)

/** WeeChat-style deterministic DJB2 nick hashing, case-folded like IRC nicks. */
private fun ircNickHue(nickname: String): Float {
    var hash = 5381u
    nickname.lowercase().encodeToByteArray().forEach { byte ->
        hash = hash * 33u + (byte.toInt() and 0xff).toUInt()
    }
    return (hash.toLong() * 360.0 / 4_294_967_296.0).toFloat()
}

private fun hslColor(hue: Float, saturation: Float, lightness: Float): Color {
    val chroma = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
    val hueSection = hue / 60f
    val secondary = chroma * (1f - kotlin.math.abs(hueSection % 2f - 1f))
    val (red, green, blue) = when {
        hueSection < 1f -> Triple(chroma, secondary, 0f)
        hueSection < 2f -> Triple(secondary, chroma, 0f)
        hueSection < 3f -> Triple(0f, chroma, secondary)
        hueSection < 4f -> Triple(0f, secondary, chroma)
        hueSection < 5f -> Triple(secondary, 0f, chroma)
        else -> Triple(chroma, 0f, secondary)
    }
    val offset = lightness - chroma / 2f
    return Color(red + offset, green + offset, blue + offset)
}
