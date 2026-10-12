package dev.starling

import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.painterResource
import dev.starling.generated.resources.Res
import dev.starling.generated.resources.starling_dark
import dev.starling.generated.resources.starling_light

private data class StarlingPalette(
    val ink: Color,
    val canvas: Color,
    val paper: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
    val paleAccent: Color,
)

private enum class ThemePreference(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

private val LightPalette = StarlingPalette(
    ink = Color(0xFF383A42), canvas = Color(0xFFFAFAFA), paper = Color.White,
    muted = Color(0xFFA0A1A7), line = Color(0xFFE5E5E6), accent = Color(0xFF7056C5),
    paleAccent = Color(0xFFEFEBFA),
)
private val DarkPalette = StarlingPalette(
    ink = Color(0xFFABB2BF), canvas = Color(0xFF282C34), paper = Color(0xFF21252B),
    muted = Color(0xFF7F848E), line = Color(0xFF3E4451), accent = Color(0xFFB4A0F5),
    paleAccent = Color(0xFF393248),
)
private val LocalStarlingPalette = staticCompositionLocalOf { LightPalette }

private val Ink: Color @Composable get() = LocalStarlingPalette.current.ink
private val Canvas: Color @Composable get() = LocalStarlingPalette.current.canvas
private val Paper: Color @Composable get() = LocalStarlingPalette.current.paper
private val Muted: Color @Composable get() = LocalStarlingPalette.current.muted
private val Line: Color @Composable get() = LocalStarlingPalette.current.line
private val Accent: Color @Composable get() = LocalStarlingPalette.current.accent
private val PaleAccent: Color @Composable get() = LocalStarlingPalette.current.paleAccent
private val EMPTY_CHAT_ROOM = ChatRoom("empty", "", "", "", 0)

@Composable
fun StarlingApp(
    repository: ChatRepository = remember { LocalPreviewChatRepository() },
    nodeController: ReticulumNodeController = remember {
        UnsupportedReticulumNodeController("The embedded RNS runtime is not available on this platform yet.")
    },
    liveChatRepository: ReticulumChatRepository? = null,
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
    val visibleConnectionState = if (previewMode) ReticulumConnectionState.Disconnected else reticulumConnectionState
    val visibleConnectionError = if (previewMode) null else reticulumConnectionError
    val visibleHubNetworkId = if (previewMode) null else activeHubNetworkId
    val visibleDeliveryAddress = if (previewMode) null else localDeliveryAddress
    val scope = rememberCoroutineScope()
    LaunchedEffect(previewMode, nodeController) {
        if (previewMode) {
            nodeController.disconnect()
        }
    }
    var showMembersPanel by rememberSaveable { mutableStateOf(true) }
    var showMembersDrawer by rememberSaveable { mutableStateOf(false) }
    var showDesktopNavigation by rememberSaveable { mutableStateOf(true) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
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
    LaunchedEffect(nodeController, liveChatRepository) {
        if (repository.previewMode.value || !nodeController.isSupported) return@LaunchedEffect
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
                    ReticulumConnectionConfig(network.hubDestination.orEmpty(), "Starling", network.id),
                )
            }
        }
    }
    val selectedDirectMessage = knownMembers.firstOrNull { it.id == selectedDirectMessageId }
    val directMessages by remember(selectedDirectMessageId) {
        selectedDirectMessageId?.let(repository::directMessages) ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    val useDarkPalette = when (themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    val palette = when (themePreference) {
        ThemePreference.LIGHT -> LightPalette
        ThemePreference.DARK -> DarkPalette
        ThemePreference.SYSTEM -> if (useDarkPalette) DarkPalette else LightPalette
    }
    val materialColors = if (useDarkPalette) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.canvas,
            background = palette.canvas,
            onBackground = palette.ink,
            surface = palette.paper,
            onSurface = palette.ink,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            background = palette.canvas,
            onBackground = palette.ink,
            surface = palette.paper,
            onSurface = palette.ink,
        )
    }

    MaterialTheme(colorScheme = materialColors) {
        CompositionLocalProvider(LocalStarlingPalette provides palette) {
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
                            selectedRoomId = selectedRoom.id,
                            selectedDirectMessageId = selectedDirectMessageId,
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
                            onSettingsClick = { showSettings = true; showIdentityPage = false },
                            onIdentityClick = {
                                showIdentityPage = true
                                showSettings = false
                                showContactRequests = false
                                selectedDirectMessageId = null
                            },
                        )
                        if (showSettings) {
                            SettingsPage(
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
                                onBack = { showSettings = false },
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
                                identityHash = localIdentityHash,
                                localDeliveryAddress = visibleDeliveryAddress,
                                globalNickname = globalNickname,
                                networks = networks,
                                onGlobalNicknameChange = { liveChatRepository?.saveGlobalNickname(it) },
                                onNetworkNicknameChange = { id, nickname -> repository.updateNetworkNickname(id, nickname) },
                                showNavigationButton = !showDesktopNavigation,
                                onOpenNavigation = { showDesktopNavigation = true },
                                onManageNetwork = { showIdentityPage = false; showSettings = true },
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
                                EmptyNetworkContent(onAddNetwork = { showAddNetwork = true }, onSettings = { showSettings = true })
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
                                    selectedRoomId = selectedRoom.id,
                                    selectedDirectMessageId = selectedDirectMessageId,
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
                                        showIdentityPage = false
                                        showMembersDrawer = false
                                        scope.launch { drawerState.close() }
                                    },
                                    onIdentityClick = {
                                        showIdentityPage = true
                                        showSettings = false
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
                                onBack = { showSettings = false },
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
                                identityHash = localIdentityHash,
                                localDeliveryAddress = visibleDeliveryAddress,
                                globalNickname = globalNickname,
                                networks = networks,
                                onGlobalNicknameChange = { liveChatRepository?.saveGlobalNickname(it) },
                                onNetworkNicknameChange = { id, nickname -> repository.updateNetworkNickname(id, nickname) },
                                compact = true,
                                onOpenNavigation = { scope.launch { drawerState.open() } },
                                onManageNetwork = { showIdentityPage = false; showSettings = true },
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
                                onSettings = { showSettings = true },
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
    onToggleMembers: () -> Unit,
    showNavigationButton: Boolean = false,
    onOpenNavigation: () -> Unit = {},
    onMemberClick: (ChatMember) -> Unit,
    onSend: (String) -> Unit,
) {
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

        val listState = rememberLazyListState()
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
    Column(Modifier.fillMaxSize().background(Canvas)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(68.dp).background(Paper)
                .padding(horizontal = if (compact) 8.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (compact || showNavigationButton) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpenNavigation),
                    contentAlignment = Alignment.Center,
                ) { Text("☰", color = Accent, fontSize = 20.sp) }
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
            val listState = rememberLazyListState()
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
                    )
                }
            }
        }
        MessageComposer(onSend = onSend)
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
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpenNavigation),
                    contentAlignment = Alignment.Center,
                ) { Text("☰", color = Accent, fontSize = 20.sp) }
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
private fun NavigationToggle(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("☰", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Medium)
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
private fun IdentityPage(
    previewMode: Boolean,
    nodeState: ReticulumNodeState,
    rrcState: ReticulumConnectionState,
    identityHash: String?,
    localDeliveryAddress: String?,
    globalNickname: String,
    networks: List<ChatNetwork>,
    onGlobalNicknameChange: (String) -> Unit,
    onNetworkNicknameChange: (String, String?) -> Unit,
    compact: Boolean = false,
    showNavigationButton: Boolean = false,
    onOpenNavigation: () -> Unit = {},
    onManageNetwork: () -> Unit,
) {
    var globalNicknameDraft by rememberSaveable { mutableStateOf(globalNickname) }
    LaunchedEffect(globalNickname) { globalNicknameDraft = globalNickname }

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
            Text("Identity", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .preferredFrameRate(120f).padding(horizontal = 24.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("DEVICE IDENTITY", color = Muted, fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Paper)
                    .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text("Reticulum identity", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (previewMode) {
                    Text(
                        "Live identity details are hidden while isolated preview is active.",
                        color = Muted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                } else {
                    Text("IDENTITY HASH", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                    if (identityHash.isNullOrBlank()) {
                        Text("Start the Reticulum node to load this device’s identity.", color = Muted, fontSize = 12.sp)
                    } else {
                        SelectionContainer {
                            Text(identityHash, color = Accent, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        }
                        Text(
                            "This public hash identifies your device. Its private key remains stored locally and is never displayed.",
                            color = Muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("LXMF DELIVERY ADDRESS", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                    if (localDeliveryAddress.isNullOrBlank()) {
                        Text("Available after the node and LXMF router start.", color = Muted, fontSize = 12.sp)
                    } else {
                        SelectionContainer {
                            Text("lxmf://$localDeliveryAddress", color = Accent, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            Text("NICKNAMES", color = Muted, fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Paper)
                    .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Global nickname", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Used for your LXMF identity in direct messages and as the default nickname for RRC hubs.",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                OutlinedTextField(
                    value = globalNicknameDraft,
                    onValueChange = { globalNicknameDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Global nickname") },
                    singleLine = true,
                )
                Button(
                    enabled = globalNicknameDraft != globalNickname,
                    onClick = { onGlobalNicknameChange(normalizeStarlingNickname(globalNicknameDraft)) },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) {
                    Text("Save global nickname")
                }
                if (previewMode) {
                    Text(
                        "This preference is saved locally and applied when Local preview is turned off. Live hub nicknames are hidden in preview.",
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                } else {
                    val configuredHubs = networks.filterNot { it.isPreview }
                    Text("RRC hub nicknames", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Set an override for a specific hub. Leave it blank to use the global nickname. Changes apply the next time you connect to that hub.",
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    if (configuredHubs.isEmpty()) {
                        Text("Add an RRC hub in Network settings to configure a hub-specific nickname.", color = Muted, fontSize = 11.sp)
                    } else {
                        configuredHubs.forEach { network ->
                            HubNicknameEditor(
                                network = network,
                                globalNickname = globalNickname,
                                onSave = { onNetworkNicknameChange(network.id, it) },
                            )
                        }
                    }
                }
            }

            Text("CONNECTION STATUS", color = Muted, fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Paper)
                    .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text("Reticulum node", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(if (previewMode) "Stopped in isolated preview" else reticulumNodeSummary(nodeState), color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                Text("RRC hub", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(if (previewMode) "Live hub status is hidden in preview" else rrcConnectionSummary(rrcState), color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            }

            Text(
                "Node interfaces, hub selection, and local identity settings are managed in Network settings. The identity key is not exported or replaced from this screen.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Button(onClick = onManageNetwork, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text("Network settings")
            }
        }
    }
}

private fun reticulumNodeSummary(state: ReticulumNodeState): String = when (state) {
    ReticulumNodeState.Stopped -> "Stopped."
    is ReticulumNodeState.Starting -> "Starting the ${state.mode.name.lowercase().replace('_', ' ')} node…"
    is ReticulumNodeState.PreparingRuntime -> state.detail
    is ReticulumNodeState.Failed -> state.message
    is ReticulumNodeState.Unavailable -> state.reason
    is ReticulumNodeState.Running -> when {
        state.sharedInstanceConnected -> "Attached to the existing local RNS shared instance."
        else -> {
            val online = state.onlineTcpPeerIds.size + state.onlineRNodePorts.size
            val total = state.tcpPeers.size + state.rnodeInterfaces.size
            if (total == 0) "Running without a configured interface."
            else "$online of $total configured interfaces online."
        }
    }
}

private fun rrcConnectionSummary(state: ReticulumConnectionState): String = when (state) {
    ReticulumConnectionState.Disconnected -> "Disconnected from an RRC hub."
    ReticulumConnectionState.Starting -> "Preparing the RRC session…"
    ReticulumConnectionState.Connecting -> "Connecting to the selected RRC hub…"
    is ReticulumConnectionState.Connected -> "Connected${state.hubName?.takeIf(String::isNotBlank)?.let { " to $it" }.orEmpty()}."
    is ReticulumConnectionState.Failed -> state.message
}

@Composable
private fun HubNicknameEditor(
    network: ChatNetwork,
    globalNickname: String,
    onSave: (String?) -> Unit,
) {
    var nicknameDraft by rememberSaveable(network.id) { mutableStateOf(network.rrcNickname.orEmpty()) }
    LaunchedEffect(network.rrcNickname) { nicknameDraft = network.rrcNickname.orEmpty() }
    val normalizedNickname = nicknameDraft.trim().takeIf(String::isNotEmpty)?.let(::normalizeStarlingNickname)

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Canvas).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(network.name, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (normalizedNickname == null) "Using global nickname · $globalNickname" else "Hub nickname · $normalizedNickname",
            color = Muted,
            fontSize = 10.sp,
        )
        OutlinedTextField(
            value = nicknameDraft,
            onValueChange = { nicknameDraft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Hub-specific nickname") },
            placeholder = { Text("$globalNickname (global)") },
            singleLine = true,
        )
        Button(
            enabled = normalizedNickname != network.rrcNickname,
            onClick = { onSave(normalizedNickname) },
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
        ) {
            Text(if (normalizedNickname == null) "Use global nickname" else "Save hub nickname")
        }
    }
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
    selectedRoomId: String,
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
                Image(
                    painter = painterResource(
                        if (LocalStarlingPalette.current == DarkPalette) Res.drawable.starling_dark else Res.drawable.starling_light,
                    ),
                    contentDescription = "Starling",
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            Spacer(Modifier.width(11.dp))
            Column {
                Text("Starling", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("RETICULUM CHAT", fontSize = 9.sp, letterSpacing = 1.5.sp, color = Muted)
            }
            if (canHideNavigation) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onHideNavigation) {
                    Text("‹", color = Accent, fontSize = 25.sp)
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .preferredFrameRate(120f),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, top = 20.dp, bottom = 6.dp),
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
                IconButton(onClick = onAddNetwork) {
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
                            Text("+ Add a channel", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, top = 20.dp, bottom = 6.dp),
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
                IconButton(onClick = onAddContact) {
                    Icon(Icons.Filled.Add, contentDescription = "Add contact", tint = Accent)
                }
            }
            contacts.forEach { member ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
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
                modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 8.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(8.dp)).clickable(onClick = onRequestsClick)
                    .padding(vertical = 7.dp),
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
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
            IconButton(onClick = onSettingsClick) {
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
private fun RuntimeModeSelection(
    selected: ReticulumRuntimeMode,
    enabled: Boolean,
    onSelect: (ReticulumRuntimeMode) -> Unit,
) {
    listOf(ReticulumRuntimeMode.EMBEDDED, ReticulumRuntimeMode.EXISTING_SHARED_INSTANCE).forEach { mode ->
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .clickable(enabled = enabled) { onSelect(mode) }
                .padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected == mode, onClick = { onSelect(mode) }, enabled = enabled)
            Column {
                Text(
                    if (mode == ReticulumRuntimeMode.EMBEDDED) "Embedded Starling node" else "Existing local RNS instance",
                    color = Ink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (mode == ReticulumRuntimeMode.EXISTING_SHARED_INSTANCE) {
                    Text("Advanced", color = Muted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun NetworkHubPicker(
    networks: List<ChatNetwork>,
    selectedNetwork: ChatNetwork?,
    enabled: Boolean,
    onSelected: (ChatNetwork) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Canvas)
                .border(1.dp, Line, RoundedCornerShape(8.dp))
                .clickable(enabled = enabled && networks.isNotEmpty()) { expanded = true }
                .padding(horizontal = 12.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(selectedNetwork?.name ?: "No RRC network configured", modifier = Modifier.weight(1f), color = Ink, fontSize = 13.sp)
            Text("▾", color = if (enabled && networks.isNotEmpty()) Accent else Muted)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            networks.forEach { network ->
                DropdownMenuItem(
                    text = { Text(network.name) },
                    onClick = { onSelected(network); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun SettingsPage(
    themePreference: ThemePreference,
    onThemeChange: (ThemePreference) -> Unit,
    bubbleStyle: Boolean,
    onBubbleStyleChange: (Boolean) -> Unit,
    denseMessages: Boolean,
    onDenseMessagesChange: (Boolean) -> Unit,
    previewMode: Boolean,
    onPreviewModeChange: (Boolean) -> Unit,
    networks: List<ChatNetwork>,
    nodeConfig: ReticulumNodeConfig?,
    onNodeConfigChange: (ReticulumNodeConfig) -> Unit,
    globalNickname: String,
    preferredHubNetworkId: String?,
    onPreferredHubNetworkChange: (String?) -> Unit,
    nodeState: ReticulumNodeState,
    nodeSupported: Boolean,
    supportsRNodeInterfaces: Boolean,
    rrcState: ReticulumConnectionState,
    rrcError: String?,
    localDeliveryAddress: String?,
    showNavigationButton: Boolean = false,
    onOpenNavigation: () -> Unit = {},
    onStartNode: (ReticulumNodeConfig) -> Unit,
    onDisconnectNode: () -> Unit,
    onConnectHub: (ReticulumConnectionConfig) -> Unit,
    onDisconnectHub: () -> Unit,
    onBack: () -> Unit,
) {
    var runtimeModeName by rememberSaveable {
        mutableStateOf(nodeConfig?.mode?.name ?: ReticulumRuntimeMode.EMBEDDED.name)
    }
    var tcpPeers by rememberSaveable(
        stateSaver = listSaver(
            save = { peers: List<ReticulumTcpPeer> ->
                peers.flatMap { peer ->
                    listOf(peer.host, peer.port.toString(), peer.ifacNetworkName.orEmpty(), peer.ifacPassphrase.orEmpty())
                }
            },
            restore = { saved: List<String> ->
                saved.chunked(4).mapNotNull { fields ->
                    if (fields.size < 4) null else ReticulumTcpPeer(
                        host = fields[0],
                        port = fields[1].toIntOrNull() ?: 0,
                        ifacNetworkName = fields[2].ifBlank { null },
                        ifacPassphrase = fields[3].ifBlank { null },
                    )
                }
            },
        ),
    ) { mutableStateOf(nodeConfig?.tcpPeers ?: listOf(ReticulumTcpPeer("", 4242))) }
    var rnodeInterfaces by rememberSaveable(
        stateSaver = listSaver(
            save = { radios: List<ReticulumRNodeInterface> ->
                radios.flatMap { radio ->
                    listOf(
                        radio.port,
                        radio.frequencyHz.toString(),
                        radio.bandwidthHz.toString(),
                        radio.txPowerDbm.toString(),
                        radio.spreadingFactor.toString(),
                        radio.codingRate.toString(),
                    )
                }
            },
            restore = { saved: List<String> ->
                saved.chunked(6).mapNotNull { fields ->
                    if (fields.size != 6) null else ReticulumRNodeInterface(
                        port = fields[0],
                        frequencyHz = fields[1].toLongOrNull() ?: 0L,
                        bandwidthHz = fields[2].toIntOrNull() ?: 0,
                        txPowerDbm = fields[3].toIntOrNull() ?: -1,
                        spreadingFactor = fields[4].toIntOrNull() ?: 0,
                        codingRate = fields[5].toIntOrNull() ?: 0,
                    )
                }
            },
        ),
    ) { mutableStateOf(nodeConfig?.rnodeInterfaces.orEmpty()) }
    var existingRpcKey by remember { mutableStateOf("") }
    var selectedHubNetworkId by rememberSaveable { mutableStateOf(preferredHubNetworkId.orEmpty()) }
    var connectionInputError by rememberSaveable { mutableStateOf<String?>(null) }
    val configuredNetworks = networks.filter { !it.isPreview && !it.hubDestination.isNullOrBlank() }
    val selectedHubNetwork = configuredNetworks.firstOrNull { it.id == selectedHubNetworkId } ?: configuredNetworks.firstOrNull()
    val selectedRrcNickname = selectedHubNetwork?.rrcNickname?.takeIf(String::isNotBlank) ?: globalNickname
    LaunchedEffect(preferredHubNetworkId, configuredNetworks) {
        val selectedId = preferredHubNetworkId?.takeIf { id -> configuredNetworks.any { it.id == id } }
            ?: configuredNetworks.firstOrNull()?.id.orEmpty()
        if (selectedHubNetworkId != selectedId) selectedHubNetworkId = selectedId
        if (preferredHubNetworkId == null && selectedId.isNotBlank()) onPreferredHubNetworkChange(selectedId)
    }
    val runtimeMode = runCatching { ReticulumRuntimeMode.valueOf(runtimeModeName) }
        .getOrDefault(ReticulumRuntimeMode.EMBEDDED)
    val isRunning = nodeState is ReticulumNodeState.Running
    val isStarting = nodeState is ReticulumNodeState.Starting || nodeState is ReticulumNodeState.PreparingRuntime
    LaunchedEffect(nodeState) {
        when (val state = nodeState) {
            is ReticulumNodeState.Running -> {
                runtimeModeName = state.mode.name
                if (tcpPeers.all { it.host.isBlank() } && state.tcpPeers.isNotEmpty()) {
                    tcpPeers = state.tcpPeers
                }
                if (rnodeInterfaces.isEmpty() && state.rnodeInterfaces.isNotEmpty()) {
                    rnodeInterfaces = state.rnodeInterfaces
                }
            }
            is ReticulumNodeState.Starting -> runtimeModeName = state.mode.name
            is ReticulumNodeState.PreparingRuntime -> Unit
            else -> Unit
        }
    }
    LaunchedEffect(runtimeModeName, tcpPeers, rnodeInterfaces) {
        onNodeConfigChange(
            ReticulumNodeConfig(
                mode = runCatching { ReticulumRuntimeMode.valueOf(runtimeModeName) }
                    .getOrDefault(ReticulumRuntimeMode.EMBEDDED),
                tcpPeers = tcpPeers,
                rnodeInterfaces = rnodeInterfaces,
            ),
        )
    }

    Column(Modifier.fillMaxSize().background(Canvas)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(68.dp).background(Paper)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", color = Accent, fontSize = 26.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text("Settings", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (showNavigationButton) {
                Spacer(Modifier.weight(1f))
                NavigationToggle { onOpenNavigation() }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .preferredFrameRate(120f)
                .padding(horizontal = 24.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("APPEARANCE", color = Muted, fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Paper)
                    .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(18.dp),
            ) {
                Text("Color theme", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                ThemePreference.values().forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { onThemeChange(option) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = themePreference == option, onClick = { onThemeChange(option) })
                        Text(option.label, color = Ink, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Bubble messages", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Use a chat-bubble layout instead of the IRC-style timeline.", color = Muted, fontSize = 11.sp)
                    }
                    Switch(checked = bubbleStyle, onCheckedChange = onBubbleStyleChange)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Dense messages", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Reduce vertical spacing in room and direct-message history.", color = Muted, fontSize = 11.sp)
                    }
                    Switch(checked = denseMessages, onCheckedChange = onDenseMessagesChange)
                }
            }
            Text("NETWORK", color = Muted, fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Paper)
                    .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Reticulum node", modifier = Modifier.weight(1f), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (previewMode) "ISOLATED PREVIEW" else when (nodeState) {
                                is ReticulumNodeState.Running -> if (nodeState.interfaceOnline) "CONNECTED" else "RETRYING"
                                is ReticulumNodeState.Starting -> "STARTING"
                                is ReticulumNodeState.PreparingRuntime -> "PREPARING"
                                is ReticulumNodeState.Failed -> "ERROR"
                                is ReticulumNodeState.Unavailable -> "UNAVAILABLE"
                                ReticulumNodeState.Stopped -> "DISCONNECTED"
                            },
                        color = if (!previewMode && nodeState is ReticulumNodeState.Failed) Color(0xFFC44545) else Accent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                    )
                }
                Text(
                    if (previewMode) {
                        "Preview mode is isolated. It shows sample data only and stops live Reticulum connections."
                    } else {
                        "Starting RNS manages the network node; RRC room traffic is a separate connection below."
                    },
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Local preview", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Isolated sample data; live identity and connections are hidden.", color = Muted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = previewMode,
                        onCheckedChange = {
                            connectionInputError = null
                            onPreviewModeChange(it)
                        },
                    )
                }

                if (previewMode) {
                    Text(
                        "Live interfaces, shared-instance keys, and RRC/LXMF addresses are hidden while preview is on.",
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                } else {
                    Text(
                        if (runtimeMode == ReticulumRuntimeMode.EMBEDDED) {
                            "Interface settings are saved and the node starts automatically in live mode on next launch."
                        } else {
                            "The shared-instance RPC key is session-only; re-enter it after restarting the app."
                        },
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                RuntimeModeSelection(
                    selected = runtimeMode,
                    enabled = !isRunning && !isStarting && nodeSupported,
                    onSelect = { runtimeModeName = it.name; connectionInputError = null },
                )

                if (runtimeMode == ReticulumRuntimeMode.EMBEDDED) {
                    Text(
                        if (supportsRNodeInterfaces) {
                            "Configure TCP peers and/or an RNode serial radio. Starling stays a client; Reticulum transport routing remains disabled."
                        } else {
                            "Manage outbound TCP client interfaces. Starling stays a client; Reticulum transport routing remains disabled."
                        },
                        color = Muted,
                        fontSize = 11.sp,
                    )
                    tcpPeers.forEachIndexed { index, peer ->
                        Column(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Canvas)
                                .border(1.dp, Line, RoundedCornerShape(10.dp)).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("TCP connection ${index + 1}", modifier = Modifier.weight(1f), color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                if ((tcpPeers.size > 1 || rnodeInterfaces.isNotEmpty()) && !isRunning && !isStarting) {
                                    TextButton(onClick = { tcpPeers = tcpPeers.filterIndexed { i, _ -> i != index }; connectionInputError = null }) {
                                        Text("Remove", color = Color(0xFFC44545))
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = peer.host,
                                onValueChange = { value -> tcpPeers = tcpPeers.mapIndexed { i, old -> if (i == index) old.copy(host = value) else old }; connectionInputError = null },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Peer host or IP") },
                                singleLine = true,
                                enabled = !isRunning && !isStarting,
                            )
                            OutlinedTextField(
                                value = if (peer.port > 0) peer.port.toString() else "",
                                onValueChange = { value ->
                                    if (value.all(Char::isDigit)) {
                                        tcpPeers = tcpPeers.mapIndexed { i, old -> if (i == index) old.copy(port = value.toIntOrNull() ?: 0) else old }
                                        connectionInputError = null
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("TCP port") },
                                singleLine = true,
                                enabled = !isRunning && !isStarting,
                            )
                            OutlinedTextField(
                                value = peer.ifacNetworkName.orEmpty(),
                                onValueChange = { value -> tcpPeers = tcpPeers.mapIndexed { i, old -> if (i == index) old.copy(ifacNetworkName = value.takeIf(String::isNotBlank)) else old }; connectionInputError = null },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("IFAC network name (optional)") },
                                singleLine = true,
                                enabled = !isRunning && !isStarting,
                            )
                            OutlinedTextField(
                                value = peer.ifacPassphrase.orEmpty(),
                                onValueChange = { value -> tcpPeers = tcpPeers.mapIndexed { i, old -> if (i == index) old.copy(ifacPassphrase = value.takeIf(String::isNotBlank)) else old }; connectionInputError = null },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("IFAC passphrase (optional)") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                enabled = !isRunning && !isStarting,
                            )
                        }
                    }
                    Text("IFAC values are per connection and must match that peer. Leave both blank to disable IFAC.", color = Muted, fontSize = 10.sp)
                    if (!isRunning && !isStarting) {
                        TextButton(onClick = { tcpPeers = tcpPeers + ReticulumTcpPeer("", 4242) }) {
                            Text("+ Add TCP connection", color = Accent)
                        }
                    }
                    if (supportsRNodeInterfaces) {
                        rnodeInterfaces.forEachIndexed { index, radio ->
                            Column(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Canvas)
                                    .border(1.dp, Line, RoundedCornerShape(10.dp)).padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("RNode radio ${index + 1}", modifier = Modifier.weight(1f), color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    if (!isRunning && !isStarting) {
                                        TextButton(onClick = {
                                            rnodeInterfaces = rnodeInterfaces.filterIndexed { radioIndex, _ -> radioIndex != index }
                                            connectionInputError = null
                                        }) {
                                            Text("Remove", color = Color(0xFFC44545))
                                        }
                                    }
                                }
                                OutlinedTextField(
                                    value = radio.port,
                                    onValueChange = { value ->
                                        rnodeInterfaces = rnodeInterfaces.mapIndexed { radioIndex, old ->
                                            if (radioIndex == index) old.copy(port = value) else old
                                        }
                                        connectionInputError = null
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Serial port (e.g. /dev/ttyUSB0)") },
                                    singleLine = true,
                                    enabled = !isRunning && !isStarting,
                                )
                                OutlinedTextField(
                                    value = radio.frequencyHz.takeIf { it > 0 }?.toString().orEmpty(),
                                    onValueChange = { value ->
                                        if (value.all(Char::isDigit)) {
                                            rnodeInterfaces = rnodeInterfaces.mapIndexed { radioIndex, old ->
                                                if (radioIndex == index) old.copy(frequencyHz = value.toLongOrNull() ?: 0L) else old
                                            }
                                            connectionInputError = null
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Radio frequency (Hz)") },
                                    singleLine = true,
                                    enabled = !isRunning && !isStarting,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = radio.bandwidthHz.toString(),
                                        onValueChange = { value ->
                                            if (value.all(Char::isDigit)) {
                                                rnodeInterfaces = rnodeInterfaces.mapIndexed { radioIndex, old ->
                                                    if (radioIndex == index) old.copy(bandwidthHz = value.toIntOrNull() ?: 0) else old
                                                }
                                                connectionInputError = null
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        label = { Text("Bandwidth (Hz)") },
                                        singleLine = true,
                                        enabled = !isRunning && !isStarting,
                                    )
                                    OutlinedTextField(
                                        value = radio.txPowerDbm.toString(),
                                        onValueChange = { value ->
                                            if (value.all(Char::isDigit)) {
                                                rnodeInterfaces = rnodeInterfaces.mapIndexed { radioIndex, old ->
                                                    if (radioIndex == index) old.copy(txPowerDbm = value.toIntOrNull() ?: 0) else old
                                                }
                                                connectionInputError = null
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        label = { Text("TX power (dBm)") },
                                        singleLine = true,
                                        enabled = !isRunning && !isStarting,
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = radio.spreadingFactor.toString(),
                                        onValueChange = { value ->
                                            if (value.all(Char::isDigit)) {
                                                rnodeInterfaces = rnodeInterfaces.mapIndexed { radioIndex, old ->
                                                    if (radioIndex == index) old.copy(spreadingFactor = value.toIntOrNull() ?: 0) else old
                                                }
                                                connectionInputError = null
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        label = { Text("Spreading factor") },
                                        singleLine = true,
                                        enabled = !isRunning && !isStarting,
                                    )
                                    OutlinedTextField(
                                        value = radio.codingRate.toString(),
                                        onValueChange = { value ->
                                            if (value.all(Char::isDigit)) {
                                                rnodeInterfaces = rnodeInterfaces.mapIndexed { radioIndex, old ->
                                                    if (radioIndex == index) old.copy(codingRate = value.toIntOrNull() ?: 0) else old
                                                }
                                                connectionInputError = null
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        label = { Text("Coding rate (5–8)") },
                                        singleLine = true,
                                        enabled = !isRunning && !isStarting,
                                    )
                                }
                                radio.validationError()?.let { validationError ->
                                    Text(validationError, color = Color(0xFFC44545), fontSize = 10.sp)
                                }
                            }
                        }
                        Text(
                            "Set frequency, bandwidth, power, and spreading factor to match your RNode network and local radio rules. These settings control the radio itself.",
                            color = Muted,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                        )
                        if (!isRunning && !isStarting) {
                            TextButton(onClick = {
                                if (tcpPeers.size == 1 && tcpPeers.single().host.isBlank()) {
                                    tcpPeers = emptyList()
                                }
                                rnodeInterfaces = rnodeInterfaces + ReticulumRNodeInterface(
                                    port = "",
                                    frequencyHz = 0L,
                                    bandwidthHz = 125_000,
                                    txPowerDbm = 0,
                                    spreadingFactor = 7,
                                    codingRate = 5,
                                )
                                connectionInputError = null
                            }) {
                                Text("+ Add RNode serial interface", color = Accent)
                            }
                        }
                    }
                } else {
                    Text(
                        "Advanced: attach to an RNS shared instance already running on this device. Its RPC key must match; default ports are 37428/37429.",
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    OutlinedTextField(
                        value = existingRpcKey,
                        onValueChange = { value -> existingRpcKey = value.filter { it.digitToIntOrNull(16) != null }; connectionInputError = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Shared instance RPC key (hex)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !isRunning && !isStarting,
                    )
                }

                val stateMessage = when (val state = nodeState) {
                    is ReticulumNodeState.Running -> when {
                        state.sharedInstanceConnected -> "Using the existing local Reticulum shared instance."
                        state.rnodeInterfaces.isNotEmpty() && state.tcpPeers.isNotEmpty() ->
                            "${state.onlineTcpPeerIds.size} of ${state.tcpPeers.size} TCP connections online; " +
                                "${state.onlineRNodePorts.size} of ${state.rnodeInterfaces.size} RNode radios online."
                        state.rnodeInterfaces.isNotEmpty() -> "${state.onlineRNodePorts.size} of ${state.rnodeInterfaces.size} RNode radios online. " +
                            state.rnodeInterfaces.joinToString { radio -> "${radio.port} ${if (radio.id in state.onlineRNodePorts) "(online)" else "(retrying)"}" }
                        state.tcpPeers.isNotEmpty() -> "${state.onlineTcpPeerIds.size} of ${state.tcpPeers.size} TCP connections online. " +
                            state.tcpPeers.joinToString { peer -> "${peer.host}:${peer.port} ${if (peer.id in state.onlineTcpPeerIds) "(online)" else "(retrying)"}" }
                        else -> "RNS node is running without a configured interface."
                    }
                    is ReticulumNodeState.Starting -> "Starting the Reticulum node…"
                    is ReticulumNodeState.PreparingRuntime -> state.detail
                    is ReticulumNodeState.Failed -> state.message
                    is ReticulumNodeState.Unavailable -> state.reason
                    ReticulumNodeState.Stopped -> if (nodeSupported) "No Reticulum node is running. The app remains in local preview." else "Embedded RNS is not available on this platform yet."
                }
                Text(stateMessage, color = if (nodeState is ReticulumNodeState.Failed) Color(0xFFC44545) else Muted, fontSize = 11.sp)
                connectionInputError?.let { Text(it, color = Color(0xFFC44545), fontSize = 11.sp) }

                Button(
                    enabled = nodeSupported && !isStarting && (isRunning || if (runtimeMode == ReticulumRuntimeMode.EMBEDDED) {
                        (tcpPeers.isNotEmpty() || rnodeInterfaces.isNotEmpty()) &&
                            tcpPeers.all { it.validationError() == null } &&
                            rnodeInterfaces.all { it.validationError() == null }
                    } else existingRpcKey.length >= 32 && existingRpcKey.length % 2 == 0),
                    onClick = {
                        if (isRunning) {
                            onDisconnectNode()
                        } else {
                            val config = when (runtimeMode) {
                                ReticulumRuntimeMode.EMBEDDED -> ReticulumNodeConfig(
                                    mode = runtimeMode,
                                    tcpPeers = tcpPeers.map { peer -> peer.copy(
                                        host = peer.host.trim(),
                                        ifacNetworkName = peer.ifacNetworkName?.trim()?.ifBlank { null },
                                        ifacPassphrase = peer.ifacPassphrase?.takeIf { it.isNotBlank() },
                                    ) },
                                    rnodeInterfaces = rnodeInterfaces.map { radio -> radio.copy(port = radio.port.trim()) },
                                )
                                ReticulumRuntimeMode.EXISTING_SHARED_INSTANCE -> ReticulumNodeConfig(
                                    mode = runtimeMode,
                                    existingInstanceRpcKeyHex = existingRpcKey,
                                )
                            }
                            val error = when {
                                config.validationError() != null -> config.validationError()
                                runtimeMode == ReticulumRuntimeMode.EXISTING_SHARED_INSTANCE && existingRpcKey.length < 32 ->
                                    "The RPC key must be at least 32 hexadecimal characters."
                                else -> null
                            }
                            connectionInputError = error
                            if (error == null) onStartNode(config)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) {
                    Text(if (isRunning) "Disconnect node" else if (isStarting) "Starting…" else "Start node")
                }
                }
            }
            if (previewMode) {
                Text("MESSAGING", color = Muted, fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Preview messages are simulated locally. RRC hub details and the LXMF delivery address are hidden.",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            } else {
            Text("MESSAGING", color = Muted, fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Paper)
                    .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Room chat", modifier = Modifier.weight(1f), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        when (rrcState) {
                            ReticulumConnectionState.Disconnected -> "DISCONNECTED"
                            ReticulumConnectionState.Starting -> "STARTING"
                            ReticulumConnectionState.Connecting -> "CONNECTING"
                            is ReticulumConnectionState.Connected -> "CONNECTED"
                            is ReticulumConnectionState.Failed -> "ERROR"
                        },
                        color = if (rrcState is ReticulumConnectionState.Failed) Color(0xFFC44545) else Accent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                    )
                }
                Text("Choose one of your configured RRC networks. Starling joins its channel names; RRC does not provide room enumeration or history.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                NetworkHubPicker(
                    networks = configuredNetworks,
                    selectedNetwork = selectedHubNetwork,
                    enabled = rrcState !is ReticulumConnectionState.Connected && rrcState !is ReticulumConnectionState.Connecting,
                    onSelected = {
                        selectedHubNetworkId = it.id
                        onPreferredHubNetworkChange(it.id)
                    },
                )
                if (selectedHubNetwork != null) {
                    Text("Destination · ${selectedHubNetwork.hubDestination}", color = Muted, fontSize = 10.sp)
                } else {
                    Text("Add a network with an RRC hub destination using + in the Networks list.", color = Muted, fontSize = 11.sp)
                }
                Text("Your nickname on this hub: $selectedRrcNickname", color = Muted, fontSize = 11.sp)
                val rrcStatusText = when (val state = rrcState) {
                    ReticulumConnectionState.Disconnected -> "Room messages stay in local preview until an RRC hub welcomes this client."
                    ReticulumConnectionState.Starting -> "Preparing the RRC session…"
                    ReticulumConnectionState.Connecting -> "Establishing a Reticulum Link and waiting for WELCOME…"
                    is ReticulumConnectionState.Connected -> "Connected${state.hubName?.takeIf { it.isNotBlank() }?.let { " to $it" }.orEmpty()}. Room messages now use RRC."
                    is ReticulumConnectionState.Failed -> state.message
                }
                Text(rrcStatusText, color = if (rrcState is ReticulumConnectionState.Failed) Color(0xFFC44545) else Muted, fontSize = 11.sp)
                rrcError?.let { Text(it, color = Color(0xFFC44545), fontSize = 11.sp) }
                val rrcBusy = rrcState is ReticulumConnectionState.Starting || rrcState is ReticulumConnectionState.Connecting
                val rrcActive = rrcState is ReticulumConnectionState.Connected
                Button(
                    enabled = if (rrcActive || rrcBusy) true else nodeState is ReticulumNodeState.Running && selectedHubNetwork != null,
                    onClick = {
                        if (rrcActive || rrcBusy) onDisconnectHub()
                        else selectedHubNetwork?.hubDestination?.let { destination ->
                            onConnectHub(ReticulumConnectionConfig(destination, selectedRrcNickname, selectedHubNetwork.id))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) {
                    Text(if (rrcActive) "Disconnect from hub" else if (rrcBusy) "Cancel connection" else "Connect to hub")
                }
            }
            SettingsCard(
                title = "Direct messages",
                subtitle = "LXMF",
                description = if (localDeliveryAddress == null) {
                    "Start the Reticulum node to receive a shareable LXMF address and use direct delivery. Unknown senders appear in Requests."
                } else {
                    "Your LXMF delivery destination is active. Messages from contacts open in their conversation; unknown senders go to Requests."
                },
            )
            if (localDeliveryAddress != null) {
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Paper)
                        .border(1.dp, Line, RoundedCornerShape(12.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("YOUR LXMF ADDRESS · SELECT TO COPY", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                    SelectionContainer {
                        Text("lxmf://$localDeliveryAddress", color = Accent, fontSize = 13.sp)
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, subtitle: String, description: String, status: String? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Paper)
            .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            status?.let { Text(it, color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp) }
        }
        Text(subtitle, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(description, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
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
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(9.dp))
            .padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
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
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (expanded) "⌄" else "›", color = Muted, fontSize = 15.sp)
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
        IconButton(onClick = onAddChannel, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "Add channel to ${network.name}", tint = Muted)
        }
    }
}

@Composable
private fun RoomRow(room: ChatRoom, selected: Boolean, onClick: () -> Unit, onManage: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
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
        IconButton(onClick = onManage, modifier = Modifier.size(30.dp)) {
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
            Box(
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenNavigation),
                contentAlignment = Alignment.Center,
            ) {
                Text("☰", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }
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
                Text(if (membersVisible) "›" else "‹", color = Muted, fontSize = 15.sp)
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
) {
    val authorName = member?.displayName ?: message.author
    val palette = LocalStarlingPalette.current
    val authorColor = ircNickTextColor(authorName, darkTheme = palette == DarkPalette)
    if (bubbleStyle) {
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
private fun deliveryMarker(state: String?) {
    if (state == null) return
    val (label, color) = when (state) {
        "queued", "sending" -> "◷" to Muted
        "sent", "delivered" -> "✓" to Accent
        "failed", "rejected" -> "!" to Color(0xFFC44545)
        else -> return
    }
    Text(label, modifier = Modifier.padding(start = 4.dp), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
private fun MessageComposer(onSend: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    fun send() {
        val text = draft.trim()
        if (text.isNotEmpty()) {
            onSend(text)
            draft = ""
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().background(Canvas).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Paper)
                .border(1.dp, Line, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(color = Ink, fontSize = 14.sp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                decorationBox = { innerTextField ->
                    Box {
                        if (draft.isEmpty()) Text("Write a message…", color = Muted, fontSize = 14.sp)
                        innerTextField()
                    }
                },
            )
            Text("↵", color = Muted, fontSize = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        Button(
            onClick = { send() },
            enabled = draft.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, disabledContainerColor = Line),
        ) {
            Text("Send", modifier = Modifier.padding(horizontal = 5.dp), fontWeight = FontWeight.SemiBold)
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
