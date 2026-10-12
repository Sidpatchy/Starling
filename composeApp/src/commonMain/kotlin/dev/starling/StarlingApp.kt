package dev.starling

import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf

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
    val scope = rememberCoroutineScope()
    var showMembersPanel by rememberSaveable { mutableStateOf(true) }
    var showMembersDrawer by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showNetworkStatus by remember { mutableStateOf(false) }
    var showContactRequests by rememberSaveable { mutableStateOf(false) }
    var showAddContact by rememberSaveable { mutableStateOf(false) }
    var showAddNetwork by rememberSaveable { mutableStateOf(false) }
    var addChannelToNetworkId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDirectMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var themePreference by rememberSaveable { mutableStateOf(ThemePreference.SYSTEM) }
    var bubbleStyle by rememberSaveable { mutableStateOf(false) }
    var selectedMember by remember { mutableStateOf<ChatMember?>(null) }
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
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val isWide = maxWidth >= 1000.dp
                if (isWide) {
                    Row(Modifier.fillMaxSize()) {
                        RoomSidebar(
                            networks = networks,
                            rooms = rooms,
                            previewMode = previewMode,
                            contacts = knownMembers.filter { it.id in contactMemberIds },
                            requestsCount = contactRequests.size,
                            selectedRoomId = selectedRoom.id,
                            selectedDirectMessageId = selectedDirectMessageId,
                            onRoomSelected = {
                                selectedRoomId = it
                                selectedDirectMessageId = null
                                showContactRequests = false
                                showSettings = false
                            },
                            onContactSelected = { selectedDirectMessageId = it.id; showContactRequests = false; showSettings = false },
                            onRequestsClick = { selectedDirectMessageId = null; showContactRequests = true; showSettings = false },
                            onAddContact = { showAddContact = true },
                            onAddNetwork = { showAddNetwork = true },
                            onAddChannel = { addChannelToNetworkId = it },
                            onSettingsClick = { showSettings = true },
                            onStatusClick = { showNetworkStatus = true },
                        )
                        if (showSettings) {
                            SettingsPage(
                                themePreference = themePreference,
                                onThemeChange = { themePreference = it },
                                bubbleStyle = bubbleStyle,
                                onBubbleStyleChange = { bubbleStyle = it },
                                previewMode = previewMode,
                                onPreviewModeChange = repository::setPreviewMode,
                                networks = networks,
                                nodeState = reticulumNodeState,
                                nodeSupported = nodeController.isSupported,
                                supportsRNodeInterfaces = nodeController.supportsRNodeInterfaces,
                                rrcState = reticulumConnectionState,
                                rrcError = reticulumConnectionError,
                                localDeliveryAddress = localDeliveryAddress,
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
                            )
                        } else if (selectedDirectMessage != null) {
                            DirectMessageContent(
                                member = selectedDirectMessage,
                                messages = directMessages,
                                bubbleStyle = bubbleStyle,
                                isLive = localDeliveryAddress != null,
                                error = reticulumConnectionError,
                                onMemberClick = { selectedMember = selectedDirectMessage },
                                onSend = { text -> scope.launch { repository.sendDirectMessage(selectedDirectMessage.id, text) } },
                            )
                        } else if (rooms.isEmpty()) {
                            EmptyNetworkContent(onAddNetwork = { showAddNetwork = true }, onSettings = { showSettings = true })
                        } else {
                            ChatContent(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                room = selectedRoom,
                                networks = networks,
                                messages = messages,
                                members = members,
                                contactMemberIds = contactMemberIds,
                                bubbleStyle = bubbleStyle,
                                compact = false,
                                membersVisible = showMembersPanel,
                                isLive = reticulumConnectionState is ReticulumConnectionState.Connected && activeHubNetworkId == selectedRoom.networkId,
                                onToggleMembers = { showMembersPanel = !showMembersPanel },
                                onMemberClick = { selectedMember = it },
                                onSend = { text -> scope.launch { repository.send(selectedRoom.id, text) } },
                            )
                        }
                        if (rooms.isNotEmpty() && showMembersPanel && !showSettings && !showContactRequests && selectedDirectMessage == null) {
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
                                    contacts = knownMembers.filter { it.id in contactMemberIds },
                                    requestsCount = contactRequests.size,
                                    selectedRoomId = selectedRoom.id,
                                    selectedDirectMessageId = selectedDirectMessageId,
                                    onRoomSelected = { roomId ->
                                        selectedRoomId = roomId
                                        selectedDirectMessageId = null
                                        showContactRequests = false
                                        showSettings = false
                                        scope.launch { drawerState.close() }
                                    },
                                    onContactSelected = { member ->
                                        selectedDirectMessageId = member.id
                                        showContactRequests = false
                                        showSettings = false
                                        scope.launch { drawerState.close() }
                                    },
                                    onRequestsClick = {
                                        selectedDirectMessageId = null
                                        showContactRequests = true
                                        showSettings = false
                                        scope.launch { drawerState.close() }
                                    },
                                    onAddContact = { showAddContact = true },
                                    onAddNetwork = { showAddNetwork = true },
                                    onAddChannel = {
                                        addChannelToNetworkId = it
                                        scope.launch { drawerState.close() }
                                    },
                                    onSettingsClick = {
                                        showSettings = true
                                        showMembersDrawer = false
                                        scope.launch { drawerState.close() }
                                    },
                                    onStatusClick = {
                                        showNetworkStatus = true
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
                                previewMode = previewMode,
                                onPreviewModeChange = repository::setPreviewMode,
                                networks = networks,
                                nodeState = reticulumNodeState,
                                nodeSupported = nodeController.isSupported,
                                supportsRNodeInterfaces = nodeController.supportsRNodeInterfaces,
                                rrcState = reticulumConnectionState,
                                rrcError = reticulumConnectionError,
                                localDeliveryAddress = localDeliveryAddress,
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
                        } else if (selectedDirectMessage != null) {
                            DirectMessageContent(
                                member = selectedDirectMessage,
                                messages = directMessages,
                                bubbleStyle = bubbleStyle,
                                isLive = localDeliveryAddress != null,
                                error = reticulumConnectionError,
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
                                compact = true,
                                membersVisible = false,
                                isLive = reticulumConnectionState is ReticulumConnectionState.Connected && activeHubNetworkId == selectedRoom.networkId,
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
                            if (!showMembersDrawer) {
                                RightEdgeSwipeHandle(
                                    modifier = Modifier.align(Alignment.CenterEnd),
                                    onSwipe = { showMembersDrawer = true },
                                )
                            }
                        }
                    }
                }
            }

            if (showNetworkStatus) {
                AlertDialog(
                    onDismissRequest = { showNetworkStatus = false },
                    title = { Text("Network status", color = Ink) },
                    text = {
                        Column {
                    Text(if (previewMode) "Local preview is on" else "Local preview is off", color = Ink, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                            Text(
                                when {
                                    reticulumConnectionState is ReticulumConnectionState.Connected -> "Connected to an RRC hub."
                                    reticulumNodeState is ReticulumNodeState.Running -> "Reticulum node is running; no RRC hub is connected."
                                    else -> "Not connected to a Reticulum hub. Add a network to configure an RRC destination."
                                },
                                color = Muted,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showNetworkStatus = false }) { Text("Close", color = Accent) }
                    },
                )
            }

            if (showAddContact) {
                AddContactDialog(
                    onAdd = { displayName, address ->
                        val normalizedAddress = address.trim()
                        val idAddress = normalizedAddress.removePrefix("lxmf://").removePrefix("LXMF://").lowercase()
                        val id = "lxmf-$idAddress"
                        repository.addContact(ChatMember(id, displayName.trim(), address = normalizedAddress))
                        showAddContact = false
                    },
                    onDismiss = { showAddContact = false },
                )
            }

            if (showAddNetwork) {
                AddNetworkDialog(
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

            selectedMember?.let { member ->
                MemberProfileDialog(
                    member = member,
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
private fun RightEdgeSwipeHandle(modifier: Modifier = Modifier, onSwipe: () -> Unit) {
    val latestOnSwipe by rememberUpdatedState(onSwipe)
    Box(
        modifier = modifier
            .width(28.dp)
            .fillMaxHeight()
            .pointerInput(Unit) {
                var distance = 0f
                var opened = false
                detectHorizontalDragGestures(
                    onDragStart = {
                        distance = 0f
                        opened = false
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        distance += dragAmount
                        if (!opened && distance < -40.dp.toPx()) {
                            latestOnSwipe()
                            opened = true
                            change.consume()
                        }
                    },
                    onDragEnd = { distance = 0f },
                    onDragCancel = { distance = 0f },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.width(4.dp).height(48.dp).clip(RoundedCornerShape(4.dp))
                .background(Muted.copy(alpha = 0.35f)),
        )
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
    compact: Boolean,
    membersVisible: Boolean,
    isLive: Boolean = false,
    onToggleMembers: () -> Unit,
    onOpenNavigation: () -> Unit = {},
    onMemberClick: (ChatMember) -> Unit,
    onSend: (String) -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        ChatHeader(
            room = room,
            networks = networks,
            compact = compact,
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
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = if (compact) 12.dp else 20.dp,
                vertical = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            item { DayDivider() }
            items(messages, key = { it.id }) { message ->
                val author = members.firstOrNull { it.id == (message.authorId ?: message.author) }
                    ?: members.firstOrNull { it.displayName.equals(message.author, ignoreCase = true) }
                MessageRow(
                    message = message,
                    member = author,
                    bubbleStyle = bubbleStyle,
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
    isLive: Boolean = false,
    error: String? = null,
    compact: Boolean = false,
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
            if (compact) {
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
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = if (compact) 12.dp else 20.dp,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(if (bubbleStyle) 4.dp else 2.dp),
            ) {
                item { DayDivider() }
                items(messages, key = { it.id }) { message ->
                    MessageRow(
                        message = message,
                        member = if (!message.isMine && (message.authorId == member.id || message.author == member.id)) member else null,
                        bubbleStyle = bubbleStyle,
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
    onOpenNavigation: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(Canvas)) {
        Row(
            Modifier.fillMaxWidth().height(68.dp).background(Paper)
                .padding(horizontal = if (compact) 8.dp else 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (compact) {
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
                modifier = Modifier.fillMaxWidth().weight(1f),
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
                        request.sender.address?.let {
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
                Text("Add someone by their shareable LXMF address. Adding them saves the contact but doesn’t start a conversation.", color = Muted, fontSize = 12.sp)
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("LXMF address") },
                    singleLine = true,
                )
                Text("QR scanning isn’t available yet. Paste the 32-character delivery hash or an lxmf:// address.", color = Muted, fontSize = 10.sp)
            }
        },
        confirmButton = {
            TextButton(
                enabled = displayName.isNotBlank() && address.isNotBlank(),
                onClick = { onAdd(displayName, address) },
            ) { Text("Add contact", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } },
    )
}

@Composable
private fun AddNetworkDialog(onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var destination by rememberSaveable { mutableStateOf("") }
    val validDestination = destination.length == 32 && destination.all { it.lowercaseChar() in "0123456789abcdef" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add network", color = Ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Connect this network to an RRC hub destination. Channels you add will be joined by name.", color = Muted, fontSize = 12.sp)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Network name") }, singleLine = true)
                OutlinedTextField(
                    value = destination,
                    onValueChange = { destination = it.filter { character -> character.lowercaseChar() in "0123456789abcdef" }.take(32) },
                    label = { Text("RRC hub destination hash") },
                    supportingText = { Text("32 hexadecimal characters", color = Muted) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && validDestination, onClick = { onAdd(name.trim(), destination.lowercase()) }) { Text("Add network", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } },
    )
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
                Text("This adds a channel for $networkName. For a live network, Starling joins this name on its RRC hub.", color = Muted, fontSize = 12.sp)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Channel name") }, singleLine = true)
                OutlinedTextField(value = topic, onValueChange = { topic = it }, label = { Text("Topic (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onAdd(name.trim().removePrefix("#"), topic) },
            ) { Text("Add channel", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } },
    )
}

@Composable
private fun RoomSidebar(
    networks: List<ChatNetwork>,
    rooms: List<ChatRoom>,
    previewMode: Boolean,
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
    onSettingsClick: () -> Unit,
    onStatusClick: () -> Unit,
) {
    var expandedNetworkIds by remember {
        mutableStateOf<Set<String>>(networks.map { it.id }.toSet())
    }
    LaunchedEffect(networks) {
        expandedNetworkIds = expandedNetworkIds + networks.map { it.id }
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
                Text("✳", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(11.dp))
            Column {
                Text("Starling", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("RETICULUM CHAT", fontSize = 9.sp, letterSpacing = 1.5.sp, color = Muted)
            }
        }

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
                onAddChannel = { onAddChannel(network.id) },
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

        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onStatusClick).padding(horizontal = 8.dp, vertical = 9.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFE1A448)))
                    Spacer(Modifier.width(8.dp))
                    Text(if (previewMode) "Preview enabled" else "Preview hidden", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Text(
                    if (previewMode) "Sample data stays local" else "Sample data hidden",
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
    previewMode: Boolean,
    onPreviewModeChange: (Boolean) -> Unit,
    networks: List<ChatNetwork>,
    nodeState: ReticulumNodeState,
    nodeSupported: Boolean,
    supportsRNodeInterfaces: Boolean,
    rrcState: ReticulumConnectionState,
    rrcError: String?,
    localDeliveryAddress: String?,
    onStartNode: (ReticulumNodeConfig) -> Unit,
    onDisconnectNode: () -> Unit,
    onConnectHub: (ReticulumConnectionConfig) -> Unit,
    onDisconnectHub: () -> Unit,
    onBack: () -> Unit,
) {
    var runtimeModeName by rememberSaveable { mutableStateOf(ReticulumRuntimeMode.EMBEDDED.name) }
    var tcpPeers by rememberSaveable(
        stateSaver = listSaver(
            save = { peers: List<ReticulumTcpPeer> ->
                peers.flatMap { peer -> listOf(peer.host, peer.port.toString(), peer.ifacNetworkName.orEmpty()) }
            },
            restore = { saved: List<String> ->
                saved.chunked(3).mapNotNull { fields ->
                    if (fields.size < 3) null else ReticulumTcpPeer(
                        host = fields[0],
                        port = fields[1].toIntOrNull() ?: 0,
                        ifacNetworkName = fields[2].ifBlank { null },
                    )
                }.ifEmpty { listOf(ReticulumTcpPeer("", 4242)) }
            },
        ),
    ) { mutableStateOf(listOf(ReticulumTcpPeer("", 4242))) }
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
    ) { mutableStateOf(emptyList<ReticulumRNodeInterface>()) }
    var existingRpcKey by remember { mutableStateOf("") }
    var selectedHubNetworkId by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("Starling") }
    var connectionInputError by rememberSaveable { mutableStateOf<String?>(null) }
    val configuredNetworks = networks.filter { !it.isPreview && !it.hubDestination.isNullOrBlank() }
    val selectedHubNetwork = configuredNetworks.firstOrNull { it.id == selectedHubNetworkId } ?: configuredNetworks.firstOrNull()
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
        }

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
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
                        when (nodeState) {
                            is ReticulumNodeState.Running -> if (nodeState.interfaceOnline) "CONNECTED" else "RETRYING"
                            is ReticulumNodeState.Starting -> "STARTING"
                            is ReticulumNodeState.PreparingRuntime -> "PREPARING"
                            is ReticulumNodeState.Failed -> "ERROR"
                            is ReticulumNodeState.Unavailable -> "UNAVAILABLE"
                            ReticulumNodeState.Stopped -> "PREVIEW"
                        },
                        color = if (nodeState is ReticulumNodeState.Failed) Color(0xFFC44545) else Accent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                    )
                }
                Text(
                    "Local preview stays available without setup. Starting RNS manages the network node; RRC room traffic is a separate connection below.",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Local preview", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Show or hide sample networks, channels, and messages for this session.", color = Muted, fontSize = 11.sp)
                    }
                    Switch(checked = previewMode, onCheckedChange = onPreviewModeChange)
                }

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
                    onSelected = { selectedHubNetworkId = it.id },
                )
                if (selectedHubNetwork != null) {
                    Text("Destination · ${selectedHubNetwork.hubDestination}", color = Muted, fontSize = 10.sp)
                } else {
                    Text("Add a network with an RRC hub destination using + in the Networks list.", color = Muted, fontSize = 11.sp)
                }
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it.take(32) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Display name") },
                    singleLine = true,
                    enabled = rrcState !is ReticulumConnectionState.Connected && rrcState !is ReticulumConnectionState.Connecting,
                )
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
                    enabled = if (rrcActive || rrcBusy) true else nodeState is ReticulumNodeState.Running && selectedHubNetwork != null && displayName.isNotBlank(),
                    onClick = {
                        if (rrcActive || rrcBusy) onDisconnectHub()
                        else selectedHubNetwork?.hubDestination?.let { destination ->
                            onConnectHub(ReticulumConnectionConfig(destination, displayName.trim(), selectedHubNetwork.id))
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
private fun NetworkRow(network: ChatNetwork, expanded: Boolean, onAddChannel: () -> Unit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(9.dp))
            .padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (expanded) "⌄" else "›", color = Muted, fontSize = 15.sp)
            Spacer(Modifier.width(7.dp))
            Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFE1A448)))
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
private fun RoomRow(room: ChatRoom, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) PaleAccent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 31.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
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
}

@Composable
private fun ChatHeader(
    room: ChatRoom,
    networks: List<ChatNetwork>,
    compact: Boolean,
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
        if (compact) {
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
    onMemberClick: () -> Unit,
) {
    val authorName = member?.displayName ?: message.author
    val palette = LocalStarlingPalette.current
    val authorColor = ircNickTextColor(authorName, darkTheme = palette == DarkPalette)
    if (bubbleStyle) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
            horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(15.dp))
                    .background(if (message.isMine) PaleAccent else Paper)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
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
                    lineHeight = 17.sp,
                    fontStyle = if (message.isAction) FontStyle.Italic else FontStyle.Normal,
                )
            }
        }
    } else {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
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
                lineHeight = 17.sp,
                fontStyle = FontStyle.Italic,
            )
        } else {
            Text(
                "$authorName:",
                modifier = Modifier
                    .clickable(enabled = member != null && !member.isSelf, onClick = onMemberClick)
                    .alignBy(FirstBaseline),
                color = authorColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
                Spacer(Modifier.width(5.dp))
            Text(
                text = markdown(message.text, palette),
                modifier = Modifier.weight(1f).alignBy(FirstBaseline),
                color = Ink,
                fontSize = 13.sp,
                lineHeight = 18.sp,
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
