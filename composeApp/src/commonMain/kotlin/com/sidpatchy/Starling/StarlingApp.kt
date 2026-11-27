package com.sidpatchy.Starling

import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import org.jetbrains.compose.resources.painterResource
import com.sidpatchy.Starling.generated.resources.Res
import com.sidpatchy.Starling.generated.resources.starling_dark
import com.sidpatchy.Starling.generated.resources.starling_light
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

private val EMPTY_CHAT_ROOM = ChatRoom("empty", "", "", "", 0)
private val SidebarEdgeInset = 12.dp
private val SidebarActionSize = 48.dp
private val InteractiveRowMinHeight = 48.dp

/**
 * True when the app is at a fresh live-mode start: not in Local preview, no rooms or live
 * networks configured yet, identity management available, and no saved identity.
 * In that state Starling shows a welcome screen that explains the decentralized model and
 * offers explicit identity setup (create or import) or Local preview. Keys are never
 * generated implicitly; the condition is derived from live state so the screen disappears
 * as soon as the user creates an identity, adds a network, or enables preview.
 */
internal fun shouldShowWelcomeScreen(
    previewMode: Boolean,
    rooms: List<ChatRoom>,
    networks: List<ChatNetwork>,
    identityManagerAvailable: Boolean,
    identityState: ReticulumIdentityState,
): Boolean {
    return !previewMode &&
        rooms.isEmpty() &&
        networks.none { !it.isPreview } &&
        identityManagerAvailable &&
        identityState == ReticulumIdentityState.Missing
}

@Composable
fun StarlingApp(
    repository: ChatRepository = remember { LocalPreviewChatRepository() },
    nodeController: ReticulumNodeController = remember {
        UnsupportedReticulumNodeController("The embedded RNS runtime is not available on this platform yet.")
    },
    liveChatRepository: ReticulumChatRepository? = null,
    identityManager: ReticulumIdentityManager? = null,
    forceGreeterOnLaunch: Boolean = false,
    foregroundEvents: Flow<Unit> = emptyFlow(),
) {
    var selectedRoomId by rememberSaveable { mutableStateOf("general") }
    val networks by repository.networks.collectAsState()
    val rooms by repository.rooms.collectAsState()
    val previewMode by repository.previewMode.collectAsState()
    // Keep drafts when a conversation leaves composition, but never share them between preview and live.
    val draftStateHolder = key(previewMode) { rememberSaveableStateHolder() }
    val selectedRoom = rooms.firstOrNull { it.id == selectedRoomId } ?: rooms.firstOrNull() ?: EMPTY_CHAT_ROOM
    val messages by repository.messages(selectedRoom.id).collectAsState(initial = emptyList())
    val members by repository.members(selectedRoom.id).collectAsState(initial = emptyList())
    val offlineMembers by repository.offlineMembers(selectedRoom.id).collectAsState(initial = emptyList())
    val contactMemberIds by repository.contactMemberIds.collectAsState()
    val knownMembers by repository.knownMembers.collectAsState()
    val contactRequests by repository.contactRequests.collectAsState()
    val blockedMemberIds by repository.blockedMemberIds.collectAsState()
    val nearbyPeers by repository.nearbyPeers.collectAsState()
    // Expiry and "last heard" labels move with the clock, not only with new announces.
    val nearbyNowMillis by produceState(Clock.System.now().toEpochMilliseconds(), nearbyPeers) {
        value = Clock.System.now().toEpochMilliseconds()
        while (true) {
            delay(30_000)
            value = Clock.System.now().toEpochMilliseconds()
        }
    }
    val nearbyListing = remember(nearbyPeers, nearbyNowMillis, knownMembers, contactMemberIds) {
        buildNearbyListing(nearbyPeers, nearbyNowMillis, knownMembers, contactMemberIds)
    }
    val reticulumNodeState by nodeController.state.collectAsState()
    val reticulumConnectionState by remember(liveChatRepository) {
        liveChatRepository?.connectionState ?: flowOf(ReticulumConnectionState.Disconnected)
    }.collectAsState(initial = ReticulumConnectionState.Disconnected)
    val activeHubNetworkId by remember(liveChatRepository) {
        liveChatRepository?.activeHubNetworkId ?: flowOf(null)
    }.collectAsState(initial = null)
    val rrcError by remember(liveChatRepository) {
        liveChatRepository?.rrcError ?: flowOf(null)
    }.collectAsState(initial = null)
    val directMessageError by remember(liveChatRepository) {
        liveChatRepository?.directMessageError ?: flowOf(null)
    }.collectAsState(initial = liveChatRepository?.directMessageError?.value)
    val localDeliveryAddress by remember(liveChatRepository) {
        liveChatRepository?.localDeliveryAddress ?: flowOf(null)
    }.collectAsState(initial = null)
    val persistedNodeConfig by remember(liveChatRepository) {
        liveChatRepository?.nodeConfig ?: flowOf<ReticulumNodeConfig?>(null)
    }.collectAsState(initial = liveChatRepository?.nodeConfig?.value)
    val resolvedBleRNode by nodeController.resolvedBleRNode.collectAsState()
    var rnodeUpgradeNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(resolvedBleRNode, liveChatRepository) {
        // A Bluetooth RNode saved by name is kept by device id once the node has connected to it.
        val resolved = resolvedBleRNode ?: return@LaunchedEffect
        val chat = liveChatRepository ?: return@LaunchedEffect
        val config = chat.nodeConfig.value ?: return@LaunchedEffect
        val (radios, note) = upgradeRNodeBleInterfaces(config.rnodeInterfaces, resolved) ?: return@LaunchedEffect
        chat.saveNodeConfig(config.copy(rnodeInterfaces = radios))
        if (note != null) rnodeUpgradeNote = note
    }
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
    val showWelcome = shouldShowWelcomeScreen(
        previewMode = previewMode,
        rooms = rooms,
        networks = networks,
        identityManagerAvailable = identityManager != null,
        identityState = identityState,
    )
    val identityError by remember(identityManager) { identityManager?.error ?: flowOf(null) }.collectAsState(initial = null)
    val managedIdentity = (identityState as? ReticulumIdentityState.Available)?.identity
    var identityNotice by remember { mutableStateOf<String?>(null) }
    val visibleConnectionState = if (previewMode) ReticulumConnectionState.Disconnected else reticulumConnectionState
    val visibleRrcError = if (previewMode) null else rrcError
    val visibleHubNetworkId = if (previewMode) null else activeHubNetworkId
    val visibleDeliveryAddress = if (previewMode) null else localDeliveryAddress
    val scope = rememberCoroutineScope()
    val dismissKeyboard = rememberDismissKeyboardAction()
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val createIdentity: () -> Unit = {
        identityNotice = null
        scope.launch {
            if (identityManager?.create() == true) {
                repository.setPreviewMode(false)
                identityNotice = "Identity created. Export a backup before using it."
            }
        }
    }
    val importIdentity: () -> Unit = {
        identityNotice = null
        scope.launch {
            if (identityManager?.importIdentity() == true) {
                repository.setPreviewMode(false)
                identityNotice = "Identity imported."
            }
        }
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
    var reconnecting by remember { mutableStateOf(false) }
    val retryActiveHub: () -> Unit = {
        if (!reconnecting && liveChatRepository != null) {
            reconnecting = true
            scope.launch {
                runCatching { liveChatRepository.reconnectActiveHub() }.onFailure {
                    reconnecting = false
                }
            }
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
    var forceGreeterVisible by remember(forceGreeterOnLaunch) { mutableStateOf(forceGreeterOnLaunch) }
    var page by rememberSaveable { mutableStateOf(MainPage.CHAT) }
    var settingsSection by rememberSaveable { mutableStateOf(SettingsSection.APPEARANCE) }
    var showSettingsIndex by rememberSaveable { mutableStateOf(true) }
    val inSettings = page.tab == MainTab.SETTINGS
    val showContactRequests = page == MainPage.CONTACT_REQUESTS
    val showNearby = page == MainPage.NEARBY
    // The details dialog keeps the row it was opened from, so it can outlive the entry.
    var nearbyDetail by remember { mutableStateOf<NearbyRow?>(null) }
    var nearbyNameRequest by remember { mutableStateOf<NearbyPeer?>(null) }
    var nearbyAddedAddress by remember { mutableStateOf<String?>(null) }
    var showAddContact by rememberSaveable { mutableStateOf(false) }
    var showAddNetwork by rememberSaveable { mutableStateOf(false) }
    var addChannelToNetworkId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDirectMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var localThemePreference by rememberSaveable { mutableStateOf(ThemePreference.SYSTEM) }
    var localPalettePreference by rememberSaveable { mutableStateOf(PalettePreference.IRIS) }
    var localBubbleStyle by rememberSaveable { mutableStateOf(false) }
    var localDenseMessages by rememberSaveable { mutableStateOf(false) }
    var showNetworkDiagnostics by remember { mutableStateOf(false) }
    val repositoryUiSettings by remember(liveChatRepository) {
        liveChatRepository?.uiSettings ?: flowOf(StarlingUiSettings())
    }.collectAsState(initial = liveChatRepository?.uiSettings?.value ?: StarlingUiSettings())
    val uiSettings = if (liveChatRepository == null) {
        StarlingUiSettings(
            themePreference = localThemePreference,
            palettePreference = localPalettePreference,
            bubbleStyle = localBubbleStyle,
            denseMessages = localDenseMessages,
        )
    } else {
        repositoryUiSettings
    }
    val updateUiSettings: (StarlingUiSettings) -> Unit = { settings ->
        if (liveChatRepository == null) {
            localThemePreference = settings.themePreference
            localPalettePreference = settings.palettePreference
            localBubbleStyle = settings.bubbleStyle
            localDenseMessages = settings.denseMessages
        } else {
            liveChatRepository.saveUiSettings(settings)
        }
    }
    var selectedMember by remember { mutableStateOf<ChatMember?>(null) }
    val openMemberProfile: (ChatMember) -> Unit = { member ->
        dismissKeyboard()
        selectedMember = member
    }
    var roomToManage by remember { mutableStateOf<ChatRoom?>(null) }
    var networkToInspect by remember { mutableStateOf<ChatNetwork?>(null) }
    var autoConnectAttempted by remember(liveChatRepository) { mutableStateOf(false) }
    fun openChat() {
        dismissKeyboard()
        showMembersDrawer = false
        page = MainPage.CHAT
    }
    fun openSettings(section: SettingsSection? = null) {
        dismissKeyboard()
        forceGreeterVisible = false
        showMembersDrawer = false
        if (section != null) {
            settingsSection = section
            showSettingsIndex = false
        } else if (page.tab != MainTab.SETTINGS) {
            showSettingsIndex = true
        }
        page = MainPage.SETTINGS
    }
    fun openIdentitySettings() = openSettings(SettingsSection.IDENTITY)
    val selectMainTab: (MainTab) -> Unit = { tab ->
        when (tab) {
            MainTab.CHAT -> openChat()
            MainTab.SETTINGS -> openSettings()
        }
    }
    val hasChatBackDestination = showAddContact || showAddNetwork || addChannelToNetworkId != null ||
        roomToManage != null || networkToInspect != null || selectedMember != null || showMembersDrawer ||
        nearbyNameRequest != null || nearbyDetail != null || showNearby ||
        showContactRequests || selectedDirectMessageId != null
    PlatformBackHandler(enabled = !inSettings && hasChatBackDestination) {
        if (keyboardVisible) {
            dismissKeyboard()
        } else {
            when {
                selectedMember != null -> selectedMember = null
                roomToManage != null -> roomToManage = null
                addChannelToNetworkId != null -> addChannelToNetworkId = null
                showAddNetwork -> showAddNetwork = false
                showAddContact -> showAddContact = false
                networkToInspect != null -> networkToInspect = null
                showMembersDrawer -> showMembersDrawer = false
                nearbyNameRequest != null -> nearbyNameRequest = null
                nearbyDetail != null -> nearbyDetail = null
                showContactRequests || showNearby -> page = page.parent ?: MainPage.CHAT
                selectedDirectMessageId != null -> selectedDirectMessageId = null
            }
        }
    }
    val changePreviewMode: (Boolean) -> Unit = { enabled ->
        if (enabled) {
            selectedDirectMessageId = null
            selectedMember = null
            if (page.tab == MainTab.CHAT) openChat()
            networkToInspect = null
        }
        repository.setPreviewMode(enabled)
    }
    val onGreeterSetUpIdentity: () -> Unit = {
        forceGreeterVisible = false
        openIdentitySettings()
        selectedDirectMessageId = null
    }
    val onGreeterContinueInPreview: () -> Unit = {
        forceGreeterVisible = false
        changePreviewMode(true)
    }
    LaunchedEffect(previewMode) {
        if (previewMode) {
            selectedDirectMessageId = null
            selectedMember = null
            networkToInspect = null
        }
        // Sample and live people never share a dialog.
        nearbyDetail = null
        nearbyNameRequest = null
        nearbyAddedAddress = null
    }
    val nodeRunning = !previewMode && reticulumNodeState is ReticulumNodeState.Running
    val nearbyBadgeCount = if (nodeRunning) nearbyListing.newBluetoothCount else 0
    val openNearby: () -> Unit = {
        page = MainPage.NEARBY
    }
    val announceNow: () -> Unit = { scope.launch { liveChatRepository?.announce() } }
    val addNearbyContact: (NearbyRow) -> Unit = { row ->
        if (row.peer.name.isBlank()) {
            nearbyNameRequest = row.peer
        } else if (repository.addContactFromAnnounce(row.peer) != null && nearbyDetail?.peer?.address == row.peer.address) {
            nearbyAddedAddress = row.peer.address
        }
    }
    val nearbyPage: @Composable (Boolean, Boolean, () -> Unit) -> Unit = { compact, showNavigationButton, onOpenNavigation ->
        NearbyPeoplePage(
            listing = nearbyListing,
            nowMillis = nearbyNowMillis,
            previewMode = previewMode,
            nodeRunning = nodeRunning,
            supportsBlePeering = nodeController.supportsBlePeering,
            blePeeringEnabled = (reticulumNodeState as? ReticulumNodeState.Running)?.blePeeringEnabled == true,
            onAnnounce = announceNow,
            onAdd = addNearbyContact,
            onDetails = { row ->
                dismissKeyboard()
                nearbyAddedAddress = null
                nearbyDetail = row
            },
            onOpenSettings = { openSettings(SettingsSection.NETWORK) },
            compact = compact,
            showNavigationButton = showNavigationButton,
            onOpenNavigation = onOpenNavigation,
        )
    }
    LaunchedEffect(nearbyDetail, blockedMemberIds, knownMembers) {
        val open = nearbyDetail ?: return@LaunchedEffect
        if (isNearbyPeerBlocked(open.peer, knownMembers, blockedMemberIds)) nearbyDetail = null
    }
    LaunchedEffect(nodeController, liveChatRepository, identityManager) {
        identityManager?.refresh()
        if (repository.previewMode.value || !nodeController.isSupported) return@LaunchedEffect
        if (identityManager != null && identityManager.state.value !is ReticulumIdentityState.Available) return@LaunchedEffect
        nodeController.refreshState()
        val savedConfig = liveChatRepository?.nodeConfig?.value ?: return@LaunchedEffect
        if (savedConfig.mode != ReticulumRuntimeMode.EMBEDDED ||
            savedConfig.validationError(nodeController.supportsBleRNodes) != null
        ) {
            return@LaunchedEffect
        }

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
    LaunchedEffect(foregroundEvents, liveChatRepository, nodeController, identityManager) {
        foregroundEvents.collect {
            val chat = liveChatRepository ?: return@collect
            if (repository.previewMode.value) return@collect

            nodeController.refreshState()
            val savedConfig = chat.nodeConfig.value?.takeIf { it.validationError(nodeController.supportsBleRNodes) == null }
            val canStartNode = identityManager == null || identityManager.state.value is ReticulumIdentityState.Available
            when (val state = nodeController.state.value) {
                is ReticulumNodeState.Running -> {
                    // A newly saved connection does not become active until the next node start.
                    // It must not be mistaken for a broken socket and force a restart on resume.
                    if (savedConfig != null && canStartNode && state.needsTcpRecovery()) {
                        // Give RNS a moment to restore sockets after iOS resumes before restarting its interfaces.
                        delay(1_000)
                        nodeController.refreshState()
                        val refreshed = nodeController.state.value as? ReticulumNodeState.Running
                        if (refreshed?.needsTcpRecovery() == true) {
                            nodeController.disconnect()
                            nodeController.start(savedConfig)
                        }
                    }
                }
                ReticulumNodeState.Stopped -> {
                    if (canStartNode) savedConfig?.let { nodeController.start(it) }
                }
                is ReticulumNodeState.Failed -> {
                    if (canStartNode && savedConfig != null) {
                        nodeController.disconnect()
                        nodeController.start(savedConfig)
                    }
                }
                else -> Unit
            }

            val recoveredNodeState = when (nodeController.state.value) {
                is ReticulumNodeState.Starting, is ReticulumNodeState.PreparingRuntime -> withTimeoutOrNull(30_000) {
                    nodeController.state.first {
                        it is ReticulumNodeState.Running || it is ReticulumNodeState.Stopped ||
                            it is ReticulumNodeState.Failed || it is ReticulumNodeState.Unavailable
                    }
                }
                else -> nodeController.state.value
            }
            if (chat.activeHubNetworkId.value != null && recoveredNodeState is ReticulumNodeState.Running) {
                reconnecting = true
                runCatching { chat.reconnectActiveHub() }.onFailure { reconnecting = false }
            }
        }
    }
    // Settle the in-progress reconnect flag once the hub reaches a conclusive state so the
    // retry button can be offered again after a successful or failed attempt.
    LaunchedEffect(visibleConnectionState) {
        if (visibleConnectionState is ReticulumConnectionState.Connected ||
            visibleConnectionState is ReticulumConnectionState.Failed
        ) {
            reconnecting = false
        }
    }
    val selectedDirectMessage = knownMembers.firstOrNull { it.id == selectedDirectMessageId }
    val sidebarSelection = sidebarSelectionForVisiblePage(
        roomId = selectedRoom.id.takeIf { rooms.isNotEmpty() },
        directMessageId = selectedDirectMessage?.id,
        page = page,
    )
    val defaultSidebarSection = defaultChatSidebarSection(sidebarSelection, page)
    var sidebarSection by rememberSaveable(defaultSidebarSection, previewMode) { mutableStateOf(defaultSidebarSection) }
    var collapsedNetworkIds by rememberSaveable(
        previewMode,
        stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() }),
    ) { mutableStateOf(emptySet()) }
    val sidebarCurrentRoom = selectedRoom.takeIf { rooms.isNotEmpty() }
    val onToggleSidebarNetwork: (String) -> Unit = { id ->
        collapsedNetworkIds = toggleSidebarNetwork(collapsedNetworkIds, id)
    }
    val directMessages by remember(selectedDirectMessageId) {
        selectedDirectMessageId?.let(repository::directMessages) ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val identityContent: @Composable (Modifier, Dp) -> Unit = { modifier, horizontalPadding ->
        IdentitySettingsContent(
            modifier = modifier,
            horizontalPadding = horizontalPadding,
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
            canAnnounce = nodeRunning && liveChatRepository != null,
            onAnnounce = announceNow,
            globalNickname = globalNickname,
            networks = networks,
            onGlobalNicknameChange = { liveChatRepository?.saveGlobalNickname(it) },
            onNetworkNicknameChange = { id, nickname -> repository.updateNetworkNickname(id, nickname) },
            onDismissKeyboard = dismissKeyboard,
            onManageNetwork = { openSettings(SettingsSection.NETWORK) },
        )
    }

    val settingsContent: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val isWideLayout = maxWidth >= SettingsTwoPaneMinWidth
            val backTarget = settingsBackTarget(isWideLayout, showSettingsIndex)
            val backFromSettings: () -> Unit = {
                when (backTarget) {
                    SettingsBackTarget.SETTINGS_INDEX -> {
                        dismissKeyboard()
                        showSettingsIndex = true
                    }
                    SettingsBackTarget.EXIT_SETTINGS -> openChat()
                }
            }
            PlatformBackHandler(enabled = true) {
                if (keyboardVisible) dismissKeyboard() else backFromSettings()
            }
            SettingsPage(
                isWideLayout = isWideLayout,
                selectedSection = settingsSection,
                onSectionSelected = { section ->
                    settingsSection = section
                    showSettingsIndex = false
                },
                showSectionIndex = showSettingsIndex,
                themePreference = uiSettings.themePreference,
                onThemeChange = { updateUiSettings(uiSettings.copy(themePreference = it)) },
                palettePreference = uiSettings.palettePreference,
                onPaletteChange = { updateUiSettings(uiSettings.copy(palettePreference = it)) },
                bubbleStyle = uiSettings.bubbleStyle,
                onBubbleStyleChange = { updateUiSettings(uiSettings.copy(bubbleStyle = it)) },
                denseMessages = uiSettings.denseMessages,
                onDenseMessagesChange = { updateUiSettings(uiSettings.copy(denseMessages = it)) },
                reduceMotion = uiSettings.reduceMotion,
                onReduceMotionChange = { updateUiSettings(uiSettings.copy(reduceMotion = it)) },
                timestampTimeZone = uiSettings.timestampTimeZone,
                onTimestampTimeZoneChange = { updateUiSettings(uiSettings.copy(timestampTimeZone = it)) },
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
                identityContent = identityContent,
                supportsReticulumProtocol = nodeController.supportsReticulumProtocol,
                supportsRNodeInterfaces = nodeController.supportsRNodeInterfaces,
                supportsBleRNodes = nodeController.supportsBleRNodes,
                rnodeBlePicker = nodeController.rnodeBlePicker,
                resolvedBleRNode = resolvedBleRNode,
                rnodeUpgradeNote = rnodeUpgradeNote,
                supportsBlePeering = nodeController.supportsBlePeering,
                rrcState = visibleConnectionState,
                rrcError = visibleRrcError,
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
                onOpenNetworkDiagnostics = {
                    dismissKeyboard()
                    showNetworkDiagnostics = true
                },
                onBack = backFromSettings,
                onDismissKeyboard = dismissKeyboard,
            )
        }
    }

    StarlingTheme(
        preference = uiSettings.themePreference,
        palettePreference = uiSettings.palettePreference,
        timestampTimeZonePreference = uiSettings.timestampTimeZone,
        reducedMotion = uiSettings.reduceMotion,
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Canvas) {
            val safeInsets = WindowInsets.safeDrawing
            // On iOS the bottom bar owns the bottom inset so its surface reaches the home indicator.
            val rootInsets = if (LocalStarlingUiStyle.current == StarlingUiStyle.IOS) {
                safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            } else {
                safeInsets
            }
            BoxWithConstraints(
                Modifier.fillMaxSize().windowInsetsPadding(rootInsets).imePadding().dismissKeyboardOnUpwardScroll(),
            ) {
                if (maxWidth >= 1000.dp) {
                    WideNavigationLayout(
                        selectedTab = page.tab,
                        onTabSelected = selectMainTab,
                        showRail = !forceGreeterVisible,
                    ) {
                        if (inSettings) {
                            settingsContent()
                            return@WideNavigationLayout
                        }
                        if (showDesktopNavigation && !forceGreeterVisible) RoomSidebar(
                            networks = networks,
                            rooms = rooms,
                            previewMode = previewMode,
                            nodeState = reticulumNodeState,
                            rrcState = visibleConnectionState,
                            activeHubNetworkId = visibleHubNetworkId,
                            contacts = knownMembers.filter { it.id in contactMemberIds },
                            requestsCount = contactRequests.size,
                            section = sidebarSection,
                            onSectionSelected = { sidebarSection = it },
                            currentRoom = sidebarCurrentRoom,
                            collapsedNetworkIds = collapsedNetworkIds,
                            onToggleNetwork = onToggleSidebarNetwork,
                            selectedRoomId = sidebarSelection.roomId,
                            selectedDirectMessageId = sidebarSelection.directMessageId,
                            onRoomSelected = {
                                openChat()
                                selectedRoomId = it
                                selectedDirectMessageId = null
                            },
                            onContactSelected = { openChat(); selectedDirectMessageId = it.id },
                            onRequestsClick = { page = MainPage.CONTACT_REQUESTS },
                            nearbyCount = nearbyBadgeCount,
                            onNearbyClick = openNearby,
                            onAddContact = { showAddContact = true },
                            onAddNetwork = { showAddNetwork = true },
                            onAddChannel = { addChannelToNetworkId = it },
                            onManageRoom = { roomToManage = it },
                            onNetworkDetails = { networkToInspect = it },
                            canHideNavigation = true,
                            onHideNavigation = { showDesktopNavigation = false },
                            onDismissKeyboard = dismissKeyboard,
                            onIdentityClick = ::openIdentitySettings,
                        )
                        if (forceGreeterVisible) {
                            WelcomeScreen(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                onSetUpIdentity = onGreeterSetUpIdentity,
                                onContinueInPreview = onGreeterContinueInPreview,
                                onSettings = { openSettings() },
                            )
                        } else if (showContactRequests) {
                            ContactRequestsPage(
                                requests = contactRequests,
                                onAccept = { request -> repository.acceptContactRequest(request.id) },
                                onIgnore = { request -> repository.ignoreContactRequest(request.id) },
                                onBlock = { request -> repository.blockContactRequest(request.id) },
                                showNavigationButton = !showDesktopNavigation,
                                onOpenNavigation = { dismissKeyboard(); showDesktopNavigation = true },
                            )
                        } else if (showNearby) {
                            nearbyPage(false, !showDesktopNavigation) { dismissKeyboard(); showDesktopNavigation = true }
                        } else if (selectedDirectMessage != null) {
                            DirectMessageContent(
                                member = selectedDirectMessage,
                                draftStateHolder = draftStateHolder,
                                messages = directMessages,
                                bubbleStyle = uiSettings.bubbleStyle,
                                denseMessages = uiSettings.denseMessages,
                                previewMode = previewMode,
                                isLive = visibleDeliveryAddress != null,
                                error = directMessageError,
                                onMemberClick = { openMemberProfile(selectedDirectMessage) },
                                showNavigationButton = !showDesktopNavigation,
                                onOpenNavigation = { dismissKeyboard(); showDesktopNavigation = true },
                                onSend = { text -> scope.launch { repository.sendDirectMessage(selectedDirectMessage.id, text) } },
                            )
                        } else if (rooms.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                if (showWelcome) {
                                    WelcomeScreen(
                                        onSetUpIdentity = onGreeterSetUpIdentity,
                                        onContinueInPreview = onGreeterContinueInPreview,
                                        onSettings = { openSettings() },
                                    )
                                } else {
                                    EmptyNetworkContent(
                                        onAddNetwork = { dismissKeyboard(); showAddNetwork = true },
                                        onSettings = { openSettings() },
                                        showIdentitySetup = !previewMode && identityManager != null && identityState == ReticulumIdentityState.Missing,
                                        onSetupIdentity = ::openIdentitySettings,
                                    )
                                }
                                if (!showDesktopNavigation) {
                                    NavigationToggle(Modifier.align(Alignment.TopStart)) {
                                        dismissKeyboard()
                                        showDesktopNavigation = true
                                    }
                                }
                            }
                        } else {
                            ChatContent(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                room = selectedRoom,
                                draftStateHolder = draftStateHolder,
                                networks = networks,
                                messages = messages,
                                members = members,
                                contactMemberIds = contactMemberIds,
                                bubbleStyle = uiSettings.bubbleStyle,
                                denseMessages = uiSettings.denseMessages,
                                compact = false,
                                membersVisible = showMembersPanel,
                                previewMode = previewMode,
                                isLive = visibleConnectionState is ReticulumConnectionState.Connected && visibleHubNetworkId == selectedRoom.networkId,
                                error = visibleRrcError,
                                onToggleMembers = { dismissKeyboard(); showMembersPanel = !showMembersPanel },
                                showNavigationButton = !showDesktopNavigation,
                                onOpenNavigation = { dismissKeyboard(); showDesktopNavigation = true },
                                onMemberClick = openMemberProfile,
                                onSend = { text -> scope.launch { repository.send(selectedRoom.id, text) } },
                            )
                        }
                        if (!forceGreeterVisible && rooms.isNotEmpty() && showMembersPanel && page == MainPage.CHAT && selectedDirectMessage == null) {
                            MembersPane(
                                members = members,
                                offlineMembers = offlineMembers,
                                contactMemberIds = contactMemberIds,
                                knownMembers = knownMembers,
                                onMemberClick = openMemberProfile,
                            )
                        }
                    }
                } else if (inSettings) {
                    CompactNavigationLayout(
                        selectedTab = page.tab,
                        onTabSelected = selectMainTab,
                        showBottomBar = page.tab.showsBottomNavigation(chatDrawerOpen = false),
                        content = settingsContent,
                    )
                } else {
                    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                    LaunchedEffect(drawerState.targetValue) {
                        if (drawerState.targetValue == DrawerValue.Open) dismissKeyboard()
                    }
                    val reducedMotion = LocalReducedMotion.current
                    val drawerEnter: EnterTransition = if (reducedMotion) EnterTransition.None else fadeIn()
                    val drawerExit: ExitTransition = if (reducedMotion) ExitTransition.None else (slideOutHorizontally(targetOffsetX = { it }) + fadeOut())
                    CompactChatNavigationLayout(
                        drawerState = drawerState,
                        gesturesEnabled = !forceGreeterVisible,
                        onTabSelected = { tab ->
                            selectMainTab(tab)
                            if (tab == MainTab.CHAT) scope.launch { drawerState.close() }
                        },
                        drawerContent = {
                            ModalDrawerSheet(drawerState = drawerState, drawerContainerColor = Paper) {
                                RoomSidebar(
                                    networks = networks,
                                    rooms = rooms,
                                    previewMode = previewMode,
                                    nodeState = reticulumNodeState,
                                    rrcState = visibleConnectionState,
                                    activeHubNetworkId = visibleHubNetworkId,
                                    contacts = knownMembers.filter { it.id in contactMemberIds },
                                    requestsCount = contactRequests.size,
                                    section = sidebarSection,
                                    onSectionSelected = { sidebarSection = it },
                                    currentRoom = sidebarCurrentRoom,
                                    collapsedNetworkIds = collapsedNetworkIds,
                                    onToggleNetwork = onToggleSidebarNetwork,
                                    selectedRoomId = sidebarSelection.roomId,
                                    selectedDirectMessageId = sidebarSelection.directMessageId,
                                    onRoomSelected = { roomId ->
                                        openChat()
                                        selectedRoomId = roomId
                                        selectedDirectMessageId = null
                                        scope.launch { drawerState.close() }
                                    },
                                    onContactSelected = { member ->
                                        openChat()
                                        selectedDirectMessageId = member.id
                                        scope.launch { drawerState.close() }
                                    },
                                    onRequestsClick = {
                                        page = MainPage.CONTACT_REQUESTS
                                        scope.launch { drawerState.close() }
                                    },
                                    nearbyCount = nearbyBadgeCount,
                                    onNearbyClick = {
                                        openNearby()
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
                                    onIdentityClick = ::openIdentitySettings,
                                    onDismissKeyboard = dismissKeyboard,
                                )
                            }
                        },
                    ) {
                        if (forceGreeterVisible) {
                            WelcomeScreen(
                                modifier = Modifier.fillMaxSize(),
                                onSetUpIdentity = onGreeterSetUpIdentity,
                                onContinueInPreview = onGreeterContinueInPreview,
                                onSettings = { openSettings() },
                            )
                        } else if (showContactRequests) {
                            ContactRequestsPage(
                                requests = contactRequests,
                                onAccept = { request -> repository.acceptContactRequest(request.id) },
                                onIgnore = { request -> repository.ignoreContactRequest(request.id) },
                                onBlock = { request -> repository.blockContactRequest(request.id) },
                                compact = true,
                                onOpenNavigation = { dismissKeyboard(); scope.launch { drawerState.open() } },
                            )
                        } else if (showNearby) {
                            nearbyPage(true, true) { dismissKeyboard(); scope.launch { drawerState.open() } }
                        } else if (selectedDirectMessage != null) {
                            DirectMessageContent(
                                member = selectedDirectMessage,
                                draftStateHolder = draftStateHolder,
                                messages = directMessages,
                                bubbleStyle = uiSettings.bubbleStyle,
                                denseMessages = uiSettings.denseMessages,
                                previewMode = previewMode,
                                isLive = visibleDeliveryAddress != null,
                                error = directMessageError,
                                compact = true,
                                onOpenNavigation = { dismissKeyboard(); scope.launch { drawerState.open() } },
                                onMemberClick = { openMemberProfile(selectedDirectMessage) },
                                onSend = { text -> scope.launch { repository.sendDirectMessage(selectedDirectMessage.id, text) } },
                            )
                        } else if (rooms.isEmpty()) {
                            if (showWelcome) {
                                WelcomeScreen(
                                    modifier = Modifier.fillMaxSize(),
                                    onSetUpIdentity = onGreeterSetUpIdentity,
                                    onContinueInPreview = onGreeterContinueInPreview,
                                    onSettings = { openSettings() },
                                )
                            } else {
                                Box(Modifier.fillMaxSize()) {
                                    EmptyNetworkContent(
                                        onAddNetwork = { showAddNetwork = true },
                                        onSettings = { openSettings() },
                                        showIdentitySetup = !previewMode && identityManager != null && identityState == ReticulumIdentityState.Missing,
                                        onSetupIdentity = ::openIdentitySettings,
                                    )
                                    NavigationToggle(Modifier.align(Alignment.TopStart)) {
                                        scope.launch { drawerState.open() }
                                    }
                                }
                            }
                        } else Box(Modifier.fillMaxSize()) {
                            ChatContent(
                                room = selectedRoom,
                                draftStateHolder = draftStateHolder,
                                networks = networks,
                                messages = messages,
                                members = members,
                                contactMemberIds = contactMemberIds,
                                bubbleStyle = uiSettings.bubbleStyle,
                                denseMessages = uiSettings.denseMessages,
                                compact = true,
                                membersVisible = false,
                                previewMode = previewMode,
                                isLive = visibleConnectionState is ReticulumConnectionState.Connected && visibleHubNetworkId == selectedRoom.networkId,
                                error = visibleRrcError,
                                onToggleMembers = { dismissKeyboard(); showMembersDrawer = true },
                                onOpenNavigation = { dismissKeyboard(); scope.launch { drawerState.open() } },
                                onMemberClick = openMemberProfile,
                                onSend = { text -> scope.launch { repository.send(selectedRoom.id, text) } },
                            )
                            AnimatedVisibility(
                                visible = showMembersDrawer,
                                modifier = Modifier.fillMaxSize(),
                                enter = drawerEnter,
                                exit = drawerExit,
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
                                            enter = if (reducedMotion) EnterTransition.None else slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                                            exit = if (reducedMotion) ExitTransition.None else slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                                        ) {
                                            MembersPane(
                                                members = members,
                                                offlineMembers = offlineMembers,
                                                contactMemberIds = contactMemberIds,
                                                knownMembers = knownMembers,
                                                drawer = true,
                                                onMemberClick = {
                                                    openMemberProfile(it)
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

            if (inSettings) {
                if (showNetworkDiagnostics) NetworkDiagnosticsDialog(
                    network = null,
                    nodeState = reticulumNodeState,
                    rrcState = visibleConnectionState,
                    activeHubNetworkId = visibleHubNetworkId,
                    localDeliveryAddress = visibleDeliveryAddress,
                    previewMode = previewMode,
                    onDismiss = { showNetworkDiagnostics = false },
                    onRefresh = { scope.launch { nodeController.refreshState() } },
                    onReconnect = retryActiveHub,
                    isRetrying = reconnecting,
                )
                return@Surface
            }

            networkToInspect?.let { network ->
                NetworkStatusDialog(
                    network = network,
                    previewMode = previewMode,
                    nodeState = reticulumNodeState,
                    rrcState = visibleConnectionState,
                    activeHubNetworkId = visibleHubNetworkId,
                    onDismiss = { networkToInspect = null },
                    onRetry = retryActiveHub,
                    isRetrying = reconnecting,
                    onRemove = {
                        repository.removeNetwork(network.id)
                        networkToInspect = null
                    },
                )
            }

            if (showAddContact) {
                AddContactDialog(
                    previewMode = previewMode,
                    onAdd = { displayName, address ->
                        val cleanName = displayName.trim()
                        val added = if (previewMode) {
                            val slug = cleanName.lowercase().filter { it.isLetterOrDigit() || it == '-' }
                                .ifBlank { "contact" }
                            repository.addContact(ChatMember("preview-local-$slug", cleanName))
                        } else {
                            val normalizedAddress = normalizeLxmfAddress(address) ?: return@AddContactDialog
                            val known = knownMembers.firstOrNull {
                                normalizeLxmfAddress(it.address.orEmpty()) == normalizedAddress
                            }
                            val contact = known?.copy(displayName = cleanName, address = normalizedAddress)
                                ?: ChatMember("lxmf-$normalizedAddress", cleanName, address = normalizedAddress)
                            repository.addContact(contact)
                        }
                        // Adding a contact never opens or creates a conversation.
                        if (added != null) showAddContact = false
                    },
                    onDismiss = { showAddContact = false },
                )
            }

            nearbyDetail?.let { opened ->
                val liveRow = nearbyListing.row(opened.peer.address)
                val contact = nearbyContactFor(opened.peer, knownMembers, contactMemberIds)
                NearbyPeerDialog(
                    row = liveRow ?: opened.copy(
                        contact = contact,
                        displayName = contact?.displayName?.takeIf { it.isNotBlank() } ?: opened.peer.name,
                    ),
                    expired = liveRow == null,
                    nowMillis = nearbyNowMillis,
                    justAdded = nearbyAddedAddress == opened.peer.address,
                    onAdd = { addNearbyContact(liveRow ?: opened) },
                    onMessage = { member ->
                        // Selecting a contact shows their timeline; nothing is created until a message is sent.
                        openChat()
                        selectedDirectMessageId = member.id
                        nearbyDetail = null
                    },
                    onDismiss = { nearbyDetail = null },
                )
            }

            nearbyNameRequest?.let { peer ->
                AddContactDialog(
                    previewMode = previewMode,
                    fixedAddress = peer.address,
                    onAdd = { displayName, _ ->
                        if (repository.addContactFromAnnounce(peer, displayName) != null) {
                            if (nearbyDetail?.peer?.address == peer.address) nearbyAddedAddress = peer.address
                            nearbyNameRequest = null
                        }
                    },
                    onDismiss = { nearbyNameRequest = null },
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
                            collapsedNetworkIds = collapsedNetworkIds - networkId
                            openChat()
                            selectedRoomId = room.id
                            selectedDirectMessageId = null
                            addChannelToNetworkId = null
                        },
                        onDismiss = { addChannelToNetworkId = null },
                    )
                }
            }

            roomToManage?.let { selectedRoomToManage ->
                val room = rooms.firstOrNull { it.id == selectedRoomToManage.id } ?: selectedRoomToManage
                val roomMembers by remember(room.id) { repository.members(room.id) }
                    .collectAsState(initial = emptyList())
                ManageChannelDialog(
                    room = room,
                    networkName = networks.firstOrNull { it.id == room.networkId }?.name.orEmpty(),
                    previewMode = previewMode,
                    isLive = !previewMode && visibleConnectionState is ReticulumConnectionState.Connected &&
                        visibleHubNetworkId == room.networkId,
                    members = roomMembers,
                    onRunCommand = { command -> scope.launch { repository.send(room.id, command) } },
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
                    isContact = isChatMemberContact(member, knownMembers, contactMemberIds),
                    onToggleContact = { repository.toggleContact(member) },
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
    draftStateHolder: SaveableStateHolder,
    networks: List<ChatNetwork>,
    messages: List<ChatMessage>,
    members: List<ChatMember>,
    contactMemberIds: Set<String>,
    bubbleStyle: Boolean,
    denseMessages: Boolean,
    compact: Boolean,
    membersVisible: Boolean,
    previewMode: Boolean,
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
    val reducedMotion = LocalReducedMotion.current
    val dayLabels = rememberChatDayLabels(messages)
    val bubblePositions = rememberBubblePositions(messages, dayLabels, bubbleStyle)
    val ircAuthorWidth = rememberIrcAuthorWidth(messages, members, bubbleStyle) {
        roomMessageAuthor(it, members)
    }
    LaunchedEffect(systemExpansionScrollRequest) {
        if (systemExpansionScrollRequest > 0) {
            withFrameNanos { }
            scrollToLatestMessage(listState, messages, reducedMotion)
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
        conversationNotice(ConversationKind.CHANNEL, previewMode, isLive, error)?.let {
            ChatNoticeBanner(it)
        }

        LaunchedEffect(room.id, messages.size) {
            if (messages.isNotEmpty()) scrollToLatestMessage(listState, messages, reducedMotion)
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().preferredFrameRate(120f),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = if (compact) 12.dp else 20.dp,
                vertical = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(
                if (denseMessages) 0.dp else if (bubbleStyle) 3.dp else 2.dp,
            ),
        ) {
            itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                val author = roomMessageAuthor(message, members)
                TranscriptRow(dayLabels[index]) {
                    MessageRow(
                        message = message,
                        member = author,
                        bubbleStyle = bubbleStyle,
                        bubblePosition = bubblePositions.getOrElse(index) { BubblePosition.SINGLE },
                        ircAuthorWidth = ircAuthorWidth,
                        denseMessages = denseMessages,
                        onMemberClick = { author?.let(onMemberClick) },
                        onSystemExpansion = {
                            if (!listState.canScrollForward) systemExpansionScrollRequest++
                        },
                    )
                }
            }
        }

        draftStateHolder.SaveableStateProvider("room:${room.id}") {
            MessageComposer(onSend = onSend)
        }
    }
}

@Composable
private fun DirectMessageContent(
    member: ChatMember,
    draftStateHolder: SaveableStateHolder,
    messages: List<ChatMessage>,
    bubbleStyle: Boolean,
    denseMessages: Boolean,
    previewMode: Boolean,
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
    val reducedMotion = LocalReducedMotion.current
    val dayLabels = rememberChatDayLabels(messages)
    val bubblePositions = rememberBubblePositions(messages, dayLabels, bubbleStyle)
    val ircAuthorWidth = rememberIrcAuthorWidth(messages, member, bubbleStyle) {
        directMessageAuthor(it, member)
    }
    LaunchedEffect(systemExpansionScrollRequest) {
        if (systemExpansionScrollRequest > 0 && messages.isNotEmpty()) {
            withFrameNanos { }
            scrollToLatestMessage(listState, messages, reducedMotion)
        }
    }
    Column(Modifier.fillMaxSize().background(Canvas)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(68.dp).background(Chrome)
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
                val isDark = LocalStarlingPalette.current.isDark
                Text(member.displayName, color = ircNickTextColor(member.displayName, isDark), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Direct message", color = Muted, fontSize = 11.sp)
            }
            TextButton(onClick = onMemberClick) { Text("Profile", color = Accent) }
        }
        conversationNotice(ConversationKind.DIRECT_MESSAGE, previewMode, isLive, error)?.let {
            ChatNoticeBanner(it)
        }

        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MemberAvatar(member, size = 52.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("No messages yet", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(5.dp))
                    Text("Say hi to ${member.displayName}.", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            }
        } else {
            LaunchedEffect(messages.size) {
                scrollToLatestMessage(listState, messages, reducedMotion)
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
                itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                    TranscriptRow(dayLabels[index]) {
                        MessageRow(
                            message = message,
                            member = directMessageAuthor(message, member),
                            bubbleStyle = bubbleStyle,
                            bubblePosition = bubblePositions.getOrElse(index) { BubblePosition.SINGLE },
                            ircAuthorWidth = ircAuthorWidth,
                            denseMessages = denseMessages,
                            onMemberClick = onMemberClick,
                            onSystemExpansion = {
                                if (!listState.canScrollForward) systemExpansionScrollRequest++
                            },
                        )
                    }
                }
            }
        }
        draftStateHolder.SaveableStateProvider("dm:${member.id}") {
            MessageComposer(onSend = onSend, supportsSlashCommands = false)
        }
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
    val timeZonePreference = LocalTimestampTimeZonePreference.current
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
                                val isDark = LocalStarlingPalette.current.isDark
                                Text(request.sender.displayName, color = ircNickTextColor(request.sender.displayName, isDark), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Unknown sender · ${displayChatTime(request.time, request.timestampMillis, timeZonePreference)}",
                                    color = Muted,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(request.message, color = Ink, fontSize = 13.sp, lineHeight = 19.sp)
                        request.sender.address?.takeUnless { request.isPreview }?.let {
                            Spacer(Modifier.height(7.dp))
                            Text("LXMF address: $it", color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { onAccept(request) }) { Text("Accept", color = Accent) }
                            TextButton(onClick = { onIgnore(request) }) { Text("Ignore", color = Muted) }
                            TextButton(onClick = { onBlock(request) }) { Text("Block", color = ErrorText) }
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
    /** Set when naming someone heard nearby: the address is shown but cannot be edited. */
    fixedAddress: String? = null,
) {
    var displayName by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf(fixedAddress.orEmpty()) }
    val normalizedAddress = fixedAddress ?: normalizeLxmfAddress(address)
    val addressError = fixedAddress == null && !previewMode && address.isNotBlank() && normalizedAddress == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add contact", color = Ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (fixedAddress != null) "This person hasn’t shared a name. Choose one to remember them by."
                    else if (previewMode) "Add a demo-only contact. No LXMF address is needed and nothing is sent."
                    else "Add someone by their LXMF delivery address. They’ll appear under Direct Messages.",
                    color = Muted,
                    fontSize = 12.sp,
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                if (fixedAddress != null) {
                    OutlinedTextField(
                        value = fixedAddress,
                        onValueChange = {},
                        label = { Text("LXMF address") },
                        readOnly = true,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    )
                } else if (!previewMode) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("LXMF address") },
                        isError = addressError,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        supportingText = {
                            Text(
                                if (addressError) "Enter a 32-character hexadecimal address or lxmf:// address."
                                else "Paste a 32-character delivery hash, optionally prefixed with lxmf://.",
                                color = if (addressError) ErrorText else Muted,
                                fontSize = 10.sp,
                            )
                        },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = displayName.isNotBlank() && (fixedAddress != null || previewMode || normalizedAddress != null),
                onClick = { onAdd(displayName.trim(), normalizedAddress.orEmpty()) },
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
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Icon(Icons.Filled.Menu, contentDescription = "Open navigation", tint = Accent)
    }
}

@Composable
private fun EmptyNetworkContent(
    onAddNetwork: () -> Unit,
    onSettings: () -> Unit,
    showIdentitySetup: Boolean,
    onSetupIdentity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().background(Canvas).padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StarlingMark(40.dp)
        Spacer(Modifier.height(12.dp))
        Text("No channels to show", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (showIdentitySetup) {
                "Create an identity or import a backup before connecting. Then add an RRC network and channel."
            } else {
                "Add an RRC network and a channel, or turn on Local preview in Settings → Appearance to explore the sample chat."
            },
            color = Muted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showIdentitySetup) {
                Button(onClick = onSetupIdentity, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                    Text("Set up identity")
                }
            }
            Button(onClick = onAddNetwork, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text("Add network")
            }
            TextButton(onClick = onSettings) { Text("Settings", color = Accent) }
        }
    }
}

@Composable
private fun WelcomeScreen(
    modifier: Modifier = Modifier,
    onSetUpIdentity: () -> Unit,
    onContinueInPreview: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Canvas)
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(66.dp).clip(RoundedCornerShape(16.dp)).background(PaleAccent),
            contentAlignment = Alignment.Center,
        ) {
            StarlingMark(46.dp)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Welcome to Starling",
            color = Ink,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Starling is a decentralized Reticulum chat client — there is no central server or directory. You hold your own identity and choose the hubs and people you talk to.",
            color = Muted,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Before going live, create an identity for this device or import an existing Reticulum backup. Otherwise, continue in Local preview to explore the sample chat with demo data only.",
            color = Muted,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(28.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(
                onClick = onSetUpIdentity,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .widthIn(min = 220.dp, max = 340.dp),
            ) {
                Text("Set up identity", textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = onContinueInPreview,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Continue in Local preview", color = Accent, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = onSettings,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Settings", color = Muted, textAlign = TextAlign.Center)
            }
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
    previewMode: Boolean,
    isLive: Boolean,
    members: List<ChatMember>,
    onRunCommand: (String) -> Unit,
    onRemoveFromApp: () -> Unit,
    onDismiss: () -> Unit,
) {
    var topicDraft by remember(room.id, room.topic) { mutableStateOf(room.topic) }
    var newKey by remember(room.id) { mutableStateOf("") }
    var feedback by remember(room.id) { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<Pair<String, String>?>(null) }
    var roleMember by remember { mutableStateOf<ChatMember?>(null) }
    val modes = room.modes
    val canSendRoomCommands = isLive && room.name.none { it.isWhitespace() } &&
        countChatMessageCharacters("/unregister ${room.name}") <= MAX_CHAT_MESSAGE_CHARACTERS
    val topicCommand = "/topic ${room.name} ${topicDraft.trim()}"
    val keyCommand = "/mode ${room.name} +k ${newKey.trim()}"

    fun canSendCommand(command: String): Boolean = canSendRoomCommands &&
        countChatMessageCharacters(command) <= MAX_CHAT_MESSAGE_CHARACTERS

    fun requestConfirmation(description: String, command: String) {
        if (canSendCommand(command)) {
            confirmation = description to command
        } else {
            feedback = "This request cannot be sent: the channel name must not contain spaces and commands are limited to $MAX_CHAT_MESSAGE_CHARACTERS characters."
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage #${room.name}", color = Ink) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("$networkName · #${room.name}", color = Muted, fontSize = 12.sp)
                if (previewMode) {
                    Text("Channel management is available for a connected live hub.", color = Muted, fontSize = 12.sp)
                } else {
                    Text("Only channel operators can change settings. This RRC hub does not tell Starling your role; it will accept or reject each change.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                    Text("Topic", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = topicDraft,
                        onValueChange = { topicDraft = it },
                        enabled = canSendRoomCommands,
                        label = { Text("Channel topic") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = canSendCommand(topicCommand) && topicDraft.trim().isNotEmpty() && topicDraft.trim() != room.topic,
                            onClick = {
                                requestConfirmation("Set channel topic", topicCommand)
                            },
                        ) { Text("Save topic", color = if (canSendCommand(topicCommand)) Accent else Muted) }
                        TextButton(
                            enabled = canSendCommand("/topic ${room.name}"),
                            onClick = { onRunCommand("/topic ${room.name}"); feedback = "Requested the current topic from the hub." },
                        ) { Text("Refresh", color = if (canSendRoomCommands) Accent else Muted) }
                    }
                    if (modes == null) {
                        Text(
                            "This hub has not provided a room-mode snapshot. Mode controls appear on hubs that send RRCD room-info notices.",
                            color = Muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    } else {
                        Text("Channel permissions", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        ChannelModeSwitch("Moderated", "Only operators and voiced members can speak.", modes.moderated, canSendRoomCommands) {
                            requestConfirmation("${if (it) "Enable" else "Disable"} moderated mode", "/mode ${room.name} ${if (it) "+m" else "-m"}")
                        }
                        ChannelModeSwitch("Invite-only", "Members need an invitation to join.", modes.inviteOnly, canSendRoomCommands) {
                            requestConfirmation("${if (it) "Enable" else "Disable"} invite-only mode", "/mode ${room.name} ${if (it) "+i" else "-i"}")
                        }
                        ChannelModeSwitch("Topic restricted", "Only operators can change the topic.", modes.topicOpsOnly, canSendRoomCommands) {
                            requestConfirmation("${if (it) "Restrict" else "Allow"} topic changes to operators", "/mode ${room.name} ${if (it) "+t" else "-t"}")
                        }
                        ChannelModeSwitch("No outside messages", "Only members of this room can send messages here.", modes.noOutsideMessages, canSendRoomCommands) {
                            requestConfirmation("${if (it) "Block" else "Allow"} messages from outside the room", "/mode ${room.name} ${if (it) "+n" else "-n"}")
                        }
                        ChannelModeSwitch("Private", "Hide this room from public room listings.", modes.privateRoom, canSendRoomCommands) {
                            requestConfirmation("Make this room ${if (it) "private" else "public"}", "/mode ${room.name} ${if (it) "+p" else "-p"}")
                        }
                        ChannelModeSwitch("Registered", "Keep this room registered on the hub.", modes.registered, canSendRoomCommands) {
                            if (it) {
                                requestConfirmation("Register this room", "/register ${room.name}")
                            } else {
                                requestConfirmation("Unregister this room", "/unregister ${room.name}")
                            }
                        }
                        Text("Room key", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (modes.hasKey) "A key is required to join. The current key is never shown." else "No key is currently set.",
                            color = Muted,
                            fontSize = 11.sp,
                        )
                        if (!modes.hasKey) {
                            OutlinedTextField(
                                value = newKey,
                                onValueChange = { newKey = it },
                                enabled = canSendRoomCommands,
                                label = { Text("New room key") },
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextButton(
                                enabled = canSendCommand(keyCommand) && newKey.isNotBlank(),
                                onClick = {
                                    requestConfirmation("Set room key", keyCommand)
                                },
                            ) { Text("Set key", color = if (canSendCommand(keyCommand)) Accent else Muted) }
                        } else {
                            TextButton(
                                enabled = canSendCommand("/mode ${room.name} -k"),
                                onClick = { requestConfirmation("Remove room key", "/mode ${room.name} -k") },
                            ) { Text("Remove key", color = if (canSendRoomCommands) ErrorText else Muted) }
                        }
                    }

                    Text("Operator and voice roles", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Role changes are hub-enforced. The current RRC extension does not provide a role-list query.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                    if (members.isEmpty()) {
                        Text("No current member list is available.", color = Muted, fontSize = 11.sp)
                    } else {
                        members.sortedBy { it.displayName.lowercase() }.forEach { member ->
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    member.displayName + if (member.isSelf) " (you)" else "",
                                    modifier = Modifier.weight(1f),
                                    color = Ink,
                                    fontSize = 12.sp,
                                )
                                TextButton(enabled = canSendRoomCommands, onClick = { roleMember = member }) {
                                    Text("Manage", color = if (canSendRoomCommands) Accent else Muted)
                                }
                            }
                        }
                    }
                    feedback?.let { Text(it, color = Muted, fontSize = 11.sp) }
                    if (!isLive) {
                        Text("Connect to ${networkName} to manage its live topic, permissions, and roles.", color = Muted, fontSize = 11.sp)
                    } else if (!canSendRoomCommands) {
                        Text("This channel name cannot be targeted safely by the hub's slash-command format, or the command would exceed the message limit.", color = Muted, fontSize = 11.sp)
                    }
                }
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
                Text("Remove from app", color = ErrorText)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done", color = Accent) } },
    )

    roleMember?.let { member ->
        AlertDialog(
            onDismissRequest = { roleMember = null },
            title = { Text("Roles for ${member.displayName}", color = Ink) },
            text = {
                Text("Choose an action. The hub will apply it only if your identity has operator permission.", color = Muted, fontSize = 12.sp)
            },
            confirmButton = {
                Column {
                    TextButton(enabled = canSendCommand("/op ${room.name} ${member.id}"), onClick = {
                        requestConfirmation("Grant operator to ${member.displayName}", "/op ${room.name} ${member.id}")
                        roleMember = null
                    }) { Text("Grant operator", color = Accent) }
                    TextButton(enabled = canSendCommand("/deop ${room.name} ${member.id}"), onClick = {
                        requestConfirmation("Remove operator from ${member.displayName}", "/deop ${room.name} ${member.id}")
                        roleMember = null
                    }) { Text("Remove operator", color = ErrorText) }
                    TextButton(enabled = canSendCommand("/voice ${room.name} ${member.id}"), onClick = {
                        requestConfirmation("Grant voice to ${member.displayName}", "/voice ${room.name} ${member.id}")
                        roleMember = null
                    }) { Text("Grant voice", color = Accent) }
                    TextButton(enabled = canSendCommand("/devoice ${room.name} ${member.id}"), onClick = {
                        requestConfirmation("Remove voice from ${member.displayName}", "/devoice ${room.name} ${member.id}")
                        roleMember = null
                    }) { Text("Remove voice", color = ErrorText) }
                }
            },
            dismissButton = { TextButton(onClick = { roleMember = null }) { Text("Cancel", color = Muted) } },
        )
    }
    confirmation?.let { (description, command) ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(description, color = Ink) },
            text = {
                Text("Send this request to the RRC hub? It will only succeed if this identity is authorized.", color = Muted, fontSize = 12.sp)
            },
            confirmButton = {
                TextButton(enabled = canSendCommand(command), onClick = {
                    onRunCommand(command)
                    feedback = "Request sent. The hub will apply it if authorized; its response appears in the channel."
                    confirmation = null
                }) { Text("Send request", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel", color = Muted) } },
        )
    }
}

@Composable
private fun ChannelModeSwitch(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onRequestChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(description, color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onRequestChange)
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
    section: ChatSidebarSection,
    onSectionSelected: (ChatSidebarSection) -> Unit,
    currentRoom: ChatRoom?,
    collapsedNetworkIds: Set<String>,
    onToggleNetwork: (String) -> Unit,
    selectedRoomId: String?,
    selectedDirectMessageId: String?,
    onRoomSelected: (String) -> Unit,
    onContactSelected: (ChatMember) -> Unit,
    onRequestsClick: () -> Unit,
    nearbyCount: Int,
    onNearbyClick: () -> Unit,
    onAddContact: () -> Unit,
    onAddNetwork: () -> Unit,
    onAddChannel: (String) -> Unit,
    onManageRoom: (ChatRoom) -> Unit,
    onNetworkDetails: (ChatNetwork) -> Unit,
    canHideNavigation: Boolean = false,
    onHideNavigation: () -> Unit = {},
    onDismissKeyboard: () -> Unit = {},
    onIdentityClick: () -> Unit,
) {
    val roomsByNetwork = remember(rooms) { rooms.groupBy { it.networkId } }
    val actionNetwork = channelActionNetwork(networks, currentRoom, activeHubNetworkId)
    val channelScroll = rememberScrollState()
    val directMessageScroll = rememberScrollState()
    val (identityDetail, identityColor) = when {
        previewMode -> "Live identity hidden in preview" to Muted
        rrcState is ReticulumConnectionState.Connected ->
            "Connected to ${rrcState.hubName?.takeIf(String::isNotBlank) ?: "RRC hub"}" to Color(0xFF48A868)
        rrcState is ReticulumConnectionState.Failed -> "Hub connection failed" to ErrorIndicator
        rrcState is ReticulumConnectionState.Connecting || rrcState == ReticulumConnectionState.Starting ->
            "Connecting to hub" to Color(0xFFE1A448)
        nodeState is ReticulumNodeState.Running -> {
            val totalInterfaces = nodeState.configuredInterfaceCount
            val onlineInterfaces = nodeState.onlineInterfaceCount
            when {
                nodeState.sharedInstanceConnected -> "Shared RNS instance connected" to Color(0xFF48A868)
                totalInterfaces == 0 -> "Reticulum running without interfaces" to Muted
                onlineInterfaces > 0 -> "$onlineInterfaces of $totalInterfaces interfaces online" to Color(0xFF48A868)
                else -> "Waiting for an interface" to Color(0xFFE1A448)
            }
        }
        nodeState is ReticulumNodeState.Starting || nodeState is ReticulumNodeState.PreparingRuntime ->
            "Starting Reticulum" to Color(0xFFE1A448)
        nodeState is ReticulumNodeState.Failed -> "Reticulum node failed" to ErrorIndicator
        nodeState is ReticulumNodeState.Unavailable -> "Reticulum unavailable" to Muted
        else -> "Reticulum stopped" to Muted
    }

    Column(
        modifier = Modifier
            .width(250.dp)
            .fillMaxHeight()
            .background(Chrome),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .padding(start = 16.dp, end = SidebarEdgeInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(PaleAccent),
                contentAlignment = Alignment.Center,
            ) {
                StarlingMark(21.dp)
            }
            Spacer(Modifier.width(9.dp))
            Text(
                "Starling",
                modifier = Modifier.weight(1f),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (section == ChatSidebarSection.CHANNELS) {
                ChannelActionsMenu(
                    network = actionNetwork,
                    room = currentRoom?.takeIf { it.networkId == actionNetwork?.id },
                    onAddNetwork = { onDismissKeyboard(); onAddNetwork() },
                    onNetworkDetails = { onDismissKeyboard(); onNetworkDetails(it) },
                    onManageRoom = { onDismissKeyboard(); onManageRoom(it) },
                )
            } else {
                IconButton(onClick = { onDismissKeyboard(); onAddContact() }, modifier = Modifier.size(SidebarActionSize)) {
                    Icon(Icons.Filled.Add, contentDescription = "Add contact", tint = Ink)
                }
            }
            if (canHideNavigation) {
                IconButton(onClick = { onDismissKeyboard(); onHideNavigation() }, modifier = Modifier.size(SidebarActionSize)) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "Hide navigation", tint = Accent)
                }
            }
        }

        ChatSidebarSectionPicker(section, requestsCount) { onDismissKeyboard(); onSectionSelected(it) }

        if (section == ChatSidebarSection.CHANNELS) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(channelScroll)
                    .preferredFrameRate(120f),
            ) {
                if (networks.isEmpty()) {
                    SidebarAddRow("Add a network", Modifier.padding(horizontal = SidebarEdgeInset)) {
                        onDismissKeyboard()
                        onAddNetwork()
                    }
                }
                networks.forEach { network ->
                    val expanded = network.id !in collapsedNetworkIds
                    NetworkRow(
                        network = network,
                        expanded = expanded,
                        connectionColor = networkConnectionColor(network, previewMode, activeHubNetworkId, rrcState, nodeState)
                            .takeIf { !previewMode && network.id == activeHubNetworkId },
                        connectionDescription = networkConnectionDescription(network, previewMode, activeHubNetworkId, rrcState, nodeState),
                        onAddChannel = { onDismissKeyboard(); onAddChannel(network.id) },
                        onDetails = { onDismissKeyboard(); onNetworkDetails(network) },
                        onToggle = {
                            onDismissKeyboard()
                            onToggleNetwork(network.id)
                        },
                    )
                    if (expanded) {
                        val networkRooms = roomsByNetwork[network.id].orEmpty()
                        networkRooms.forEach { room ->
                            RoomRow(
                                room = room,
                                selected = room.id == selectedRoomId,
                                onClick = { onDismissKeyboard(); onRoomSelected(room.id) },
                                onManage = { onDismissKeyboard(); onManageRoom(room) },
                            )
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(directMessageScroll)
                    .preferredFrameRate(120f),
            ) {
                SidebarInboxRow("Message requests", requestsCount, messageRequestsBadgeDescription(requestsCount)) {
                    onDismissKeyboard()
                    onRequestsClick()
                }
                SidebarInboxRow("Nearby people", nearbyCount, nearbyBadgeDescription(nearbyCount)) {
                    onDismissKeyboard()
                    onNearbyClick()
                }
                HorizontalDivider(Modifier.padding(horizontal = SidebarEdgeInset, vertical = 6.dp), color = Line)
                contacts.forEach { member ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = SidebarEdgeInset, vertical = 2.dp)
                            .heightIn(min = InteractiveRowMinHeight).clip(RoundedCornerShape(9.dp))
                            .background(if (member.id == selectedDirectMessageId) PaleAccent else Color.Transparent)
                            .clickable(role = Role.Button) { onDismissKeyboard(); onContactSelected(member) }
                            .semantics { selected = member.id == selectedDirectMessageId }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MemberAvatar(member, size = 25.dp)
                        Spacer(Modifier.width(9.dp))
                        Text(member.displayName, color = Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (contacts.isEmpty()) {
                    Text(
                        "No direct messages yet.",
                        modifier = Modifier.padding(start = 22.dp, end = 16.dp, top = 8.dp),
                        color = Muted,
                        fontSize = 12.sp,
                    )
                    SidebarAddRow("Add a contact", Modifier.padding(horizontal = SidebarEdgeInset)) {
                        onDismissKeyboard()
                        onAddContact()
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = SidebarEdgeInset, top = 12.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .heightIn(min = InteractiveRowMinHeight).clickable(role = Role.Button) { onDismissKeyboard(); onIdentityClick() }
                    .padding(horizontal = 8.dp, vertical = 9.dp),
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
        }
    }
}

@Composable
private fun ChatSidebarSectionPicker(
    section: ChatSidebarSection,
    requestsCount: Int,
    onSelect: (ChatSidebarSection) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = SidebarEdgeInset, end = SidebarEdgeInset, bottom = 8.dp)
            .clip(RoundedCornerShape(9.dp)).background(Canvas).padding(2.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ChatSidebarSection.entries.forEach { option ->
            val isSelected = option == section
            Row(
                modifier = Modifier.weight(1f).heightIn(min = InteractiveRowMinHeight)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (isSelected) PaleAccent else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.Tab) { onSelect(option) }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    option.label,
                    color = if (isSelected) Accent else Ink,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (option == ChatSidebarSection.DIRECT_MESSAGES && requestsCount > 0) {
                    Spacer(Modifier.width(4.dp))
                    SidebarCountBadge(requestsCount, messageRequestsBadgeDescription(requestsCount))
                }
            }
        }
    }
}

private fun messageRequestsBadgeDescription(count: Int): String =
    if (count == 1) "1 pending message request" else "$count pending message requests"

@Composable
private fun SidebarCountBadge(count: Int, description: String) {
    Text(
        if (count > 99) "99+" else count.toString(),
        modifier = Modifier.semantics { contentDescription = description }
            .clip(CircleShape).background(PaleAccent).padding(horizontal = 6.dp, vertical = 2.dp),
        color = Accent,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}

@Composable
private fun SidebarInboxRow(label: String, count: Int, badgeDescription: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SidebarEdgeInset)
            .heightIn(min = InteractiveRowMinHeight).clip(RoundedCornerShape(9.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), color = Ink, fontSize = 13.sp)
        if (count > 0) SidebarCountBadge(count, badgeDescription)
    }
}

@Composable
private fun SidebarAddRow(label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = InteractiveRowMinHeight).clip(RoundedCornerShape(9.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Accent, fontSize = 13.sp)
    }
}

@Composable
private fun ChannelActionsMenu(
    network: ChatNetwork?,
    room: ChatRoom?,
    onAddNetwork: () -> Unit,
    onNetworkDetails: (ChatNetwork) -> Unit,
    onManageRoom: (ChatRoom) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(SidebarActionSize)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Channel actions", tint = Ink)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = Paper) {
            if (network != null) {
                Text(
                    network.name,
                    modifier = Modifier.widthIn(max = 220.dp).padding(horizontal = 12.dp, vertical = 5.dp)
                        .semantics { heading() },
                    color = Muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DropdownMenuItem(
                    text = { Text("Network details", color = Ink) },
                    onClick = { expanded = false; onNetworkDetails(network) },
                )
                if (room != null) {
                    DropdownMenuItem(
                        text = { Text("Manage current channel", color = Ink) },
                        onClick = { expanded = false; onManageRoom(room) },
                    )
                }
                HorizontalDivider(color = Line)
            }
            DropdownMenuItem(
                text = { Text("Add network", color = Ink) },
                onClick = { expanded = false; onAddNetwork() },
            )
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
    onRetry: (() -> Unit)? = null,
    isRetrying: Boolean = false,
    onRemove: (() -> Unit)? = null,
) {
    var confirmRemoval by remember(network.id) { mutableStateOf(false) }
    val isTargetNetwork = activeHubNetworkId == network.id
    val showRetry = onRetry != null &&
        isTargetNetwork &&
        (rrcState is ReticulumConnectionState.Failed || rrcState == ReticulumConnectionState.Disconnected)
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
                val online = state.onlineInterfaceCount
                val total = state.configuredInterfaceCount
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
        title = {
            Text(
                if (confirmRemoval) "Remove ${network.name}?" else "${network.name} status",
                color = Ink,
            )
        },
        text = {
            if (confirmRemoval) {
                Text(
                    "This removes the hub and its saved channel list from Starling. It does not delete or change the hub for anyone else.",
                    color = Muted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Hub connection", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(hubStatus, color = Ink, fontSize = 13.sp, lineHeight = 18.sp)
                    if (!previewMode) {
                        network.hubDestination?.let { destination ->
                            Text("RRC destination", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            SelectionContainer {
                                Text(destination, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                        Text("Reticulum node", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text(nodeStatus, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                        Text("Long-press a hub on touch, or right-click it on desktop, to view this status.", color = Muted, fontSize = 10.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (confirmRemoval) {
                TextButton(onClick = { onRemove?.invoke() }) {
                    Text("Remove hub", color = ErrorText)
                }
            } else if (showRetry) {
                val retryLabel = when {
                    rrcState is ReticulumConnectionState.Failed -> "Retry connection"
                    else -> "Connect"
                }
                TextButton(
                    enabled = !isRetrying,
                    onClick = onRetry,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(if (isRetrying) Muted else Accent),
                ) {
                    Text(
                        if (isRetrying) "Reconnecting…" else retryLabel,
                        color = if (isRetrying) Muted else Accent,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Close", color = Accent) }
            }
        },
        dismissButton = {
            if (confirmRemoval) {
                TextButton(onClick = { confirmRemoval = false }) { Text("Cancel", color = Muted) }
            } else if (onRemove != null && !previewMode && !network.isPreview) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { confirmRemoval = true }) { Text("Remove hub", color = ErrorText) }
                    if (showRetry) TextButton(onClick = onDismiss) { Text("Close", color = Muted) }
                }
            } else if (showRetry) {
                TextButton(onClick = onDismiss) { Text("Close", color = Muted) }
            }
        },
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

private fun networkConnectionDescription(
    network: ChatNetwork,
    previewMode: Boolean,
    activeHubNetworkId: String?,
    rrcState: ReticulumConnectionState,
    nodeState: ReticulumNodeState,
): String = when {
    previewMode || network.isPreview -> "Local preview"
    activeHubNetworkId != network.id -> "Not active"
    rrcState is ReticulumConnectionState.Connected -> "Connected"
    rrcState == ReticulumConnectionState.Starting || rrcState == ReticulumConnectionState.Connecting -> "Connecting"
    rrcState is ReticulumConnectionState.Failed || nodeState is ReticulumNodeState.Failed -> "Connection failed"
    nodeState is ReticulumNodeState.Starting || nodeState is ReticulumNodeState.PreparingRuntime -> "Starting Reticulum"
    else -> "Disconnected"
}

@Composable
private fun NetworkRow(
    network: ChatNetwork,
    expanded: Boolean,
    connectionColor: Color?,
    connectionDescription: String,
    onAddChannel: () -> Unit,
    onDetails: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SidebarEdgeInset, end = SidebarEdgeInset, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f)
                .heightIn(min = InteractiveRowMinHeight)
                .clip(RoundedCornerShape(9.dp))
                .onSidebarSecondaryClick(onDetails)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "Collapse network" else "Expand network",
                    onLongClickLabel = "Network details",
                    onClick = onToggle,
                    onLongClick = onDetails,
                )
                .semantics(mergeDescendants = true) {
                    heading()
                    stateDescription = "${if (expanded) "Expanded" else "Collapsed"}, $connectionDescription"
                }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                network.name,
                modifier = Modifier.weight(1f),
                color = Muted,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (connectionColor != null) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(7.dp).clip(CircleShape).background(connectionColor))
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = Muted,
                modifier = Modifier.size(16.dp),
            )
        }
        IconButton(onClick = onAddChannel, modifier = Modifier.size(SidebarActionSize)) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Add channel to ${network.name}",
                tint = Accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun StarlingMark(size: Dp) {
    Icon(
        painter = painterResource(if (LocalStarlingPalette.current.isDark) Res.drawable.starling_dark else Res.drawable.starling_light),
        contentDescription = null,
        tint = Color.Unspecified,
        modifier = Modifier.size(size),
    )
}

@Composable
private fun RoomRow(room: ChatRoom, selected: Boolean, onClick: () -> Unit, onManage: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SidebarEdgeInset, vertical = 1.dp)
            .heightIn(min = InteractiveRowMinHeight)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) PaleAccent else Color.Transparent)
            .onSidebarSecondaryClick(onManage)
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClickLabel = "Manage channel",
                onLongClick = onManage,
            )
            .semantics { this.selected = selected }
            .padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "#${room.name}",
            color = if (selected) Accent else Ink,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun Modifier.onSidebarSecondaryClick(onClick: () -> Unit): Modifier = pointerInput(onClick) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                onClick()
            }
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
        modifier = Modifier.fillMaxWidth().height(68.dp).background(Chrome)
            .padding(horizontal = if (compact) 10.dp else 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (compact || showNavigationButton) {
            NavigationToggle(onClick = onOpenNavigation)
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                "#${room.name}", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val networkName = networks.firstOrNull { it.id == room.networkId }?.name.orEmpty()
            val subtitle = if (compact) room.topic.ifBlank { networkName }
                else listOf(networkName, room.topic).filter { it.isNotBlank() }.joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(
            modifier = Modifier.heightIn(min = InteractiveRowMinHeight)
                .clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onToggleMembers)
                .semantics {
                    stateDescription = if (membersVisible) "Member list shown" else "Member list hidden"
                }
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
    offlineMembers: List<ChatMember>,
    contactMemberIds: Set<String>,
    knownMembers: List<ChatMember>,
    drawer: Boolean = false,
    onMemberClick: (ChatMember) -> Unit,
) {
    val contactsHere = members.filter { !it.isSelf && isChatMemberContact(it, knownMembers, contactMemberIds) }
    val otherMembers = members.filterNot { it in contactsHere }.sortedByDescending { it.isSelf }
    val onlineIds = members.mapTo(mutableSetOf()) { it.id }
    val offline = offlineMembers.filterNot { it.isSelf || it.id in onlineIds }.distinctBy { it.id }
    val panelModifier = if (drawer) {
        Modifier.width(282.dp).fillMaxHeight().background(Paper).padding(18.dp)
    } else {
        Modifier.width(242.dp).fillMaxHeight().background(Paper).padding(16.dp)
    }

    Column(panelModifier.verticalScroll(rememberScrollState())) {
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
                isContact = isChatMemberContact(member, knownMembers, contactMemberIds),
                onClick = { if (!member.isSelf) onMemberClick(member) },
            )
        }
        if (offline.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            MemberSectionLabel("OFFLINE · ${offline.size}")
            offline.forEach { member ->
                MemberRow(
                    member = member,
                    isContact = isChatMemberContact(member, knownMembers, contactMemberIds),
                    isOffline = true,
                    onClick = { onMemberClick(member) },
                )
            }
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
private fun MemberRow(member: ChatMember, isContact: Boolean, isOffline: Boolean = false, onClick: () -> Unit) {
    val isDark = LocalStarlingPalette.current.isDark
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = InteractiveRowMinHeight).clip(RoundedCornerShape(9.dp))
            .clickable(enabled = !member.isSelf, role = Role.Button, onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemberAvatar(member)
        Spacer(Modifier.width(10.dp))
        Text(
            member.displayName,
            modifier = Modifier.weight(1f),
            color = if (isOffline) Muted else ircNickTextColor(member.displayName, isDark),
            fontSize = 12.sp,
            fontWeight = if (member.isSelf) FontWeight.Bold else FontWeight.Medium,
        )
        if (member.isSelf) Text("YOU", color = Accent, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
        if (isContact) Text("DM", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun MemberAvatar(member: ChatMember, size: androidx.compose.ui.unit.Dp = 30.dp) {
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
                    val isDark = LocalStarlingPalette.current.isDark
                    Text(member.displayName, color = ircNickTextColor(member.displayName, isDark), fontWeight = FontWeight.SemiBold)
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
internal fun PreviewNotice(message: String) = ChatNoticeBanner(ConversationNotice("Preview", message))

@Composable
private fun ChatNoticeBanner(notice: ConversationNotice) {
    val palette = LocalStarlingPalette.current
    val bannerBackground = if (notice.isError) palette.errorBackground else palette.noticeBackground
    val bannerAccent = if (notice.isError) palette.errorText else palette.noticeLabel
    val bannerText = if (notice.isError) palette.errorText else palette.noticeText
    Row(
        modifier = Modifier.fillMaxWidth().background(bannerBackground)
            .semantics(mergeDescendants = true) {
                liveRegion = if (notice.isError) LiveRegionMode.Assertive else LiveRegionMode.Polite
            }
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(notice.label.uppercase(), color = bannerAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.width(10.dp))
        Text(notice.message, modifier = Modifier.weight(1f), color = bannerText, fontSize = 11.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun rememberChatDayLabels(messages: List<ChatMessage>): List<String?> {
    val timeZonePreference = LocalTimestampTimeZonePreference.current
    val formatDate = remember(timeZonePreference) { chatDateFormatter(timeZonePreference) }
    val today by produceState(formatDate(Clock.System.now().toEpochMilliseconds()), formatDate) {
        while (true) {
            value = formatDate(Clock.System.now().toEpochMilliseconds())
            delay(30_000)
        }
    }
    return remember(messages, today, formatDate) { chatMessageDayLabels(messages, today, formatDate) }
}

@Composable
private fun rememberBubblePositions(
    messages: List<ChatMessage>,
    dayLabels: List<String?>,
    bubbleStyle: Boolean,
): List<BubblePosition> = remember(messages, dayLabels, bubbleStyle) {
    if (bubbleStyle) chatBubblePositions(messages, dayLabels) else emptyList()
}

@Composable
private fun TranscriptRow(dayLabel: String?, content: @Composable () -> Unit) {
    Column {
        if (dayLabel != null) DayDivider(dayLabel)
        content()
    }
}

@Composable
private fun DayDivider(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f).height(1.dp).background(Line))
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp).semantics { heading() },
            color = Muted,
            fontSize = 11.sp,
        )
        Spacer(Modifier.weight(1f).height(1.dp).background(Line))
    }
}

private val IrcMessageMaxWidth = 960.dp
private val SystemEventBubbleMaxWidth = 720.dp
private val MessageAuthorMaxWidth = 144.dp
private val IrcAuthorFontSize = 13.sp

private fun roomMessageAuthor(message: ChatMessage, members: List<ChatMember>): ChatMember? =
    members.firstOrNull { it.id == (message.authorId ?: message.author) }
        ?: members.firstOrNull { it.displayName.equals(message.author, ignoreCase = true) }

private fun directMessageAuthor(message: ChatMessage, member: ChatMember): ChatMember? =
    member.takeIf { !message.isMine && (message.authorId == it.id || message.author == it.id) }

private fun ircAuthorLabel(name: String, isAction: Boolean): String = if (isAction) name else "$name:"

private fun messageAuthorWeight(fontStyle: FontStyle): FontWeight =
    if (fontStyle == FontStyle.Italic) FontWeight.Normal else FontWeight.SemiBold

@Composable
private fun rememberIrcAuthorWidth(
    messages: List<ChatMessage>,
    memberKey: Any,
    bubbleStyle: Boolean,
    findMember: (ChatMessage) -> ChatMember?,
): Dp {
    if (bubbleStyle) return 0.dp
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val textStyle = LocalTextStyle.current
    return remember(messages, memberKey, measurer, density, textStyle) {
        with(density) {
            val maxWidth = MessageAuthorMaxWidth.roundToPx()
            val measuredLabels = mutableSetOf<Pair<String, Boolean>>()
            var widest = 0
            for (message in messages) {
                if (message.isSystem) continue
                val label = ircAuthorLabel(findMember(message)?.displayName ?: message.author, message.isAction)
                if (!measuredLabels.add(label to message.isAction)) continue
                val style = if (message.isAction) FontStyle.Italic else FontStyle.Normal
                val width = measurer.measure(
                    label,
                    style = textStyle.copy(
                        fontSize = IrcAuthorFontSize,
                        lineHeight = IrcAuthorFontSize * 1.25f,
                        fontStyle = style,
                        fontWeight = messageAuthorWeight(style),
                    ),
                    maxLines = 1,
                    softWrap = false,
                ).size.width
                widest = maxOf(widest, width)
                if (widest >= maxWidth) break
            }
            widest.coerceAtMost(maxWidth).toDp()
        }
    }
}

@Composable
private fun MessageRow(
    message: ChatMessage,
    member: ChatMember?,
    bubbleStyle: Boolean,
    bubblePosition: BubblePosition,
    ircAuthorWidth: Dp,
    denseMessages: Boolean,
    onMemberClick: () -> Unit,
    onSystemExpansion: () -> Unit = {},
) {
    if (message.isSystem) {
        SystemMessageRow(message, denseMessages, bubbleStyle, onSystemExpansion)
        return
    }
    val authorName = member?.displayName ?: message.author
    val palette = LocalStarlingPalette.current
    val timeZonePreference = LocalTimestampTimeZonePreference.current
    val displayTime = displayChatTime(message.time, message.timestampMillis, timeZonePreference)
    val authorColor = ircNickTextColor(authorName, darkTheme = palette.isDark)
    val openAuthorProfile = if (member != null && !member.isSelf) onMemberClick else null
    val messageStyle = if (message.isAction) FontStyle.Italic else FontStyle.Normal
    val formattedMessage = remember(message.text, palette) { markdown(message.text, palette) }
    val deliveryStatus = chatDeliveryStatus(message.deliveryState)
    val iconBaselineInset = with(LocalDensity.current) { 4.sp.roundToPx() }
    val body: @Composable () -> Unit = {
        Text(
            text = formattedMessage,
            color = Ink,
            fontSize = if (denseMessages) 13.sp else 14.sp,
            lineHeight = if (denseMessages) 16.sp else 19.sp,
            fontStyle = messageStyle,
        )
    }
    if (bubbleStyle) {
        val horizontalPadding = if (denseMessages) 10.dp else 12.dp
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = when {
                !bubblePosition.startsGroup -> if (denseMessages) 1.dp else 0.dp
                denseMessages -> 3.dp
                else -> 5.dp
            }),
            horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start,
        ) {
            if (!message.isMine && bubblePosition.startsGroup) {
                MessageAuthor(
                    authorName = authorName,
                    label = authorName,
                    modifier = Modifier.padding(start = horizontalPadding, bottom = 2.dp),
                    color = authorColor,
                    fontSize = 11.sp,
                    fontStyle = FontStyle.Normal,
                    onProfileClick = openAuthorProfile,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Keep the outgoing bubble anchored to the same edge as its status changes.
                if (message.isMine || deliveryStatus != null) {
                    DeliveryStatusSlot(
                        status = deliveryStatus,
                        displayTime = displayTime,
                        modifier = Modifier.alignBy { it.measuredHeight / 2 + iconBaselineInset },
                    )
                }
                val top = if (bubblePosition.startsGroup) 15.dp else 5.dp
                val bottom = if (bubblePosition.endsGroup) 15.dp else 5.dp
                val shape = if (message.isMine) {
                    RoundedCornerShape(topStart = 15.dp, topEnd = top, bottomEnd = bottom, bottomStart = 15.dp)
                } else {
                    RoundedCornerShape(topStart = top, topEnd = 15.dp, bottomEnd = 15.dp, bottomStart = bottom)
                }
                SelectionContainer(
                    modifier = Modifier.alignBy(FirstBaseline).widthIn(max = 560.dp).clip(shape)
                        .background(if (message.isMine) PaleAccent else Paper)
                        .padding(horizontal = horizontalPadding, vertical = if (denseMessages) 4.dp else 8.dp),
                    content = body,
                )
            }
            if (bubblePosition.endsGroup && displayTime.isNotBlank()) {
                Text(
                    displayTime,
                    modifier = Modifier.padding(start = horizontalPadding, end = horizontalPadding, top = 2.dp),
                    color = Muted,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                )
            }
        }
    } else {
        val timeColumnWidth = with(LocalDensity.current) { 36.sp.toDp() }.coerceAtLeast(36.dp)
        Row(
            modifier = Modifier.widthIn(max = IrcMessageMaxWidth).fillMaxWidth()
                .padding(vertical = if (denseMessages) 0.dp else 1.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                displayTime,
                modifier = Modifier.width(timeColumnWidth).alignBy(FirstBaseline),
                color = Muted,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier.alignBy(FirstBaseline).layout { measurable, constraints ->
                    // Reserve enough space for the widest rendered nickname, without
                    // letting a long name crowd out the message on narrow screens.
                    val width = minOf(ircAuthorWidth.roundToPx(), constraints.maxWidth / 4)
                    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                    layout(width, placeable.height) { placeable.placeRelative(0, 0) }
                },
                contentAlignment = Alignment.TopEnd,
            ) {
                MessageAuthor(
                    authorName = authorName,
                    label = ircAuthorLabel(authorName, message.isAction),
                    modifier = Modifier,
                    color = authorColor,
                    fontSize = IrcAuthorFontSize,
                    fontStyle = messageStyle,
                    onProfileClick = openAuthorProfile,
                )
            }
            SelectionContainer(
                modifier = Modifier.weight(1f).alignBy(FirstBaseline),
                content = body,
            )
            // The trailing column is reserved even on rows without a delivery status.
            DeliveryStatusSlot(
                status = deliveryStatus,
                displayTime = displayTime,
                modifier = Modifier.alignBy { it.measuredHeight / 2 + iconBaselineInset },
            )
        }
    }
}

@Composable
private fun MessageAuthor(
    authorName: String,
    label: String,
    modifier: Modifier,
    color: Color,
    fontSize: TextUnit,
    fontStyle: FontStyle,
    onProfileClick: (() -> Unit)?,
) {
    // Keep the layout compact; Compose expands clickable touch targets without growing the line.
    val profileLink = if (onProfileClick == null) Modifier else Modifier
        .clickable(role = Role.Button, onClick = onProfileClick)
        .semantics { contentDescription = "View profile for $authorName" }
    Text(
        label,
        modifier = modifier.widthIn(max = MessageAuthorMaxWidth).then(profileLink),
        color = color,
        fontSize = fontSize,
        lineHeight = fontSize * 1.25f,
        fontWeight = messageAuthorWeight(fontStyle),
        fontStyle = fontStyle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SystemMessageRow(
    message: ChatMessage,
    denseMessages: Boolean,
    bubbleStyle: Boolean,
    onSystemExpansion: () -> Unit,
) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val timeZonePreference = LocalTimestampTimeZonePreference.current
    val displayTime = displayChatTime(message.time, message.timestampMillis, timeZonePreference)
    val isErrorMessage = message.systemEvents.any {
        it.kind == ChatSystemEventKind.ERROR || it.kind == ChatSystemEventKind.COMMAND_ERROR
    }
    val isStandaloneSystemMessage = isErrorMessage || message.systemEvents.any { it.kind == ChatSystemEventKind.HELP }
    val canExpand = message.systemEvents.size > 1 && !isStandaloneSystemMessage
    if (canExpand) {
        SystemEventGroup(message, displayTime, denseMessages, bubbleStyle, expanded) {
            onSystemExpansion()
            expanded = !expanded
        }
        return
    }
    val summaryMaxLines = if (isStandaloneSystemMessage) Int.MAX_VALUE else 2
    val textColor = if (isErrorMessage) ErrorText else Muted
    val verticalPadding = if (denseMessages) 1.dp else 4.dp

    if (bubbleStyle) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = verticalPadding),
            horizontalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = SystemEventBubbleMaxWidth).clip(RoundedCornerShape(10.dp))
                    .background(if (isErrorMessage) Color(0x22C44545) else PaleAccent.copy(alpha = 0.55f))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SelectionContainer(Modifier.fillMaxWidth()) {
                    Text(
                        message.text,
                        color = textColor,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        textAlign = TextAlign.Center,
                        maxLines = summaryMaxLines,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(displayTime, color = textColor, fontSize = 9.sp)
            }
        }
    } else {
        Column(Modifier.widthIn(max = IrcMessageMaxWidth).fillMaxWidth().padding(vertical = verticalPadding)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(displayTime, modifier = Modifier.width(40.dp).padding(top = 1.dp), color = Muted, fontSize = 10.sp)
                SelectionContainer(
                    modifier = Modifier.weight(1f).padding(top = 1.dp),
                ) {
                    Text(
                        message.text,
                        color = textColor,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontStyle = FontStyle.Italic,
                        maxLines = summaryMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemEventGroup(
    message: ChatMessage,
    displayTime: String,
    denseMessages: Boolean,
    bubbleStyle: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val pill = RoundedCornerShape(10.dp)
    val muted = Muted
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = if (denseMessages) 1.dp else 3.dp),
        horizontalArrangement = if (bubbleStyle) Arrangement.Center else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.widthIn(max = if (bubbleStyle) SystemEventBubbleMaxWidth else IrcMessageMaxWidth).then(
                if (bubbleStyle) Modifier.clip(pill).background(PaleAccent.copy(alpha = 0.55f))
                else Modifier.fillMaxWidth(),
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(pill)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "Collapse system events" else "Expand system events",
                        onClick = onToggle,
                    )
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    .padding(
                        horizontal = if (bubbleStyle) 10.dp else 0.dp,
                        vertical = if (denseMessages) 2.dp else 4.dp,
                    ),
                horizontalArrangement = if (bubbleStyle) Arrangement.Center else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!bubbleStyle) {
                    Text(displayTime, modifier = Modifier.width(40.dp), color = muted, fontSize = 10.sp, maxLines = 1)
                }
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        message.text,
                        color = muted,
                        fontSize = if (bubbleStyle) 11.sp else 12.sp,
                        lineHeight = if (bubbleStyle) 15.sp else 16.sp,
                        fontStyle = if (bubbleStyle) FontStyle.Normal else FontStyle.Italic,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (bubbleStyle) {
                        val count = "${message.systemEvents.size} events"
                        Text(
                            if (displayTime.isBlank()) count else "$count · last at $displayTime",
                            color = muted,
                            fontSize = 9.sp,
                            lineHeight = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(5.dp))
                ComposeCanvas(Modifier.size(width = 16.dp, height = 12.dp)) {
                    val outerY = size.height * if (expanded) 0.7f else 0.3f
                    val centerY = size.height - outerY
                    drawPath(
                        path = Path().apply {
                            moveTo(size.width * 0.12f, outerY)
                            lineTo(size.width / 2f, centerY)
                            lineTo(size.width * 0.88f, outerY)
                        },
                        color = muted,
                        style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
            if (expanded) {
                SelectionContainer(
                    modifier = if (bubbleStyle) Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp)
                    else Modifier.padding(start = 40.dp, top = 4.dp),
                ) {
                    SystemEventDetails(message.systemEvents)
                }
            }
        }
    }
}

@Composable
private fun SystemEventDetails(events: List<ChatSystemEvent>, modifier: Modifier = Modifier) {
    val timeZonePreference = LocalTimestampTimeZonePreference.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        events.forEach { event ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    displayChatTime(event.time, event.timestampMillis, timeZonePreference),
                    modifier = Modifier.width(40.dp),
                    color = Muted,
                    fontSize = 9.sp,
                )
                Text(event.text, modifier = Modifier.weight(1f), color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
            }
        }
    }
}

@Composable
private fun DeliveryStatusSlot(
    status: ChatDeliveryStatus?,
    displayTime: String,
    modifier: Modifier,
) {
    val slot = modifier.size(width = 24.dp, height = 16.dp)
    if (status == null) {
        Spacer(slot)
        return
    }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val (icon, color) = when (status) {
        ChatDeliveryStatus.QUEUED, ChatDeliveryStatus.SENDING -> Icons.Filled.Schedule to Muted
        ChatDeliveryStatus.SENT, ChatDeliveryStatus.DELIVERED -> Icons.Filled.Check to Accent
        ChatDeliveryStatus.FAILED, ChatDeliveryStatus.REJECTED -> Icons.Filled.ErrorOutline to ErrorIndicator
    }
    Box(
        modifier = slot.clickable(
            role = Role.Button,
            onClickLabel = "Show delivery details",
            onClick = { showDetails = true },
        ).semantics { contentDescription = status.label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(13.dp), tint = color)
    }
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text(status.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(status.detail)
                    if (displayTime.isNotBlank()) Text("Message time: $displayTime", color = Muted)
                }
            },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text("Close") } },
        )
    }
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
    var inputFocused by remember { mutableStateOf(false) }
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
                        modifier = Modifier.fillMaxWidth().heightIn(min = InteractiveRowMinHeight)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (index == selectedSlashSuggestionIndex) PaleAccent else Color.Transparent)
                            .clickable(role = Role.Button) { draft = completeSlashCommand(draft, command) }
                            .semantics(mergeDescendants = true) {
                                selected = index == selectedSlashSuggestionIndex
                            }
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

        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Paper)
                        .border(1.dp, if (inputFocused) Accent else Line, RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = limitChatMessageLength(it) },
                        modifier = Modifier.weight(1f).onFocusChanged { inputFocused = it.isFocused }.semantics {
                            contentDescription = "Message"
                        }.onPreviewKeyEvent { event ->
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
                        textStyle = TextStyle(color = Ink, fontSize = 14.sp, fontFamily = LocalTextStyle.current.fontFamily),
                        cursorBrush = SolidColor(Accent),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send,
                        ),
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
                        IconButton(onClick = { focusManager.clearFocus() }, modifier = Modifier.size(48.dp)) {
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
            Text(
                "$characterCount / $MAX_CHAT_MESSAGE_CHARACTERS characters",
                modifier = Modifier.align(Alignment.End).padding(top = 4.dp, end = 4.dp),
                color = if (characterCount >= MAX_CHAT_MESSAGE_CHARACTERS) ErrorText else Muted,
                fontSize = 10.sp,
            )
        }
    }
}

internal fun ircNickTextColor(nickname: String, darkTheme: Boolean): Color =
    ircNickTextColorForHue(ircNickHue(nickname), darkTheme)

// Keep low-contrast hues (dark blue and light yellow) readable on tinted surfaces too.
internal fun ircNickTextColorForHue(hue: Float, darkTheme: Boolean): Color =
    hslColor(hue, saturation = 0.70f, lightness = if (darkTheme) 0.79f else 0.23f)

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

/** Scrolls the chat list to its latest message. Animates unless the system prefers reduced motion. */
private suspend fun scrollToLatestMessage(
    state: LazyListState,
    messages: List<ChatMessage>,
    reducedMotion: Boolean,
) {
    if (messages.isEmpty()) return
    if (reducedMotion) {
        state.scrollToItem(messages.lastIndex)
    } else {
        state.animateScrollToItem(messages.lastIndex)
    }
}
