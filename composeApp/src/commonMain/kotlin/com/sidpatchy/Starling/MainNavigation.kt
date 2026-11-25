package com.sidpatchy.Starling

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal enum class MainTab(val label: String, val icon: ImageVector) {
    CHAT("Chat", Icons.AutoMirrored.Filled.Chat),
    SETTINGS("Settings", Icons.Filled.Settings);

    fun showsBottomNavigation(chatDrawerOpen: Boolean): Boolean = when (this) {
        CHAT -> chatDrawerOpen
        SETTINGS -> true
    }
}

internal enum class MainPage(val parent: MainPage? = null, val tab: MainTab = parent?.tab ?: MainTab.CHAT) {
    CHAT,
    NEARBY(CHAT),
    CONTACT_REQUESTS(CHAT),
    SETTINGS(CHAT, MainTab.SETTINGS),
}

@Composable
internal fun WideNavigationLayout(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    showRail: Boolean,
    content: @Composable RowScope.() -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        if (showRail) {
            NavigationRail(
                modifier = Modifier.width(80.dp).fillMaxHeight(),
                containerColor = Paper,
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                MainTab.entries.forEach { tab ->
                    val selected = tab == selectedTab
                    NavigationRailItem(
                        selected = selected,
                        onClick = { onTabSelected(tab) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { MainTabLabel(tab, selected) },
                        alwaysShowLabel = true,
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Accent,
                            selectedTextColor = Ink,
                            indicatorColor = PaleAccent,
                            unselectedIconColor = Muted,
                            unselectedTextColor = Ink,
                        ),
                    )
                }
            }
            VerticalDivider(color = Line)
        }
        Row(Modifier.weight(1f).fillMaxHeight(), content = content)
    }
}

@Composable
internal fun CompactNavigationLayout(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    showBottomBar: Boolean,
    content: @Composable () -> Unit,
) {
    val reducedMotion = LocalReducedMotion.current
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        AnimatedVisibility(
            visible = showBottomBar,
            enter = if (reducedMotion) EnterTransition.None else expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
            exit = if (reducedMotion) ExitTransition.None else shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
        ) {
            BottomNavigationBar(selectedTab, onTabSelected)
        }
    }
}

@Composable
internal fun CompactChatNavigationLayout(
    drawerState: DrawerState,
    gesturesEnabled: Boolean,
    onTabSelected: (MainTab) -> Unit,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    CompactNavigationLayout(
        selectedTab = MainTab.CHAT,
        onTabSelected = onTabSelected,
        showBottomBar = MainTab.CHAT.showsBottomNavigation(drawerState.targetValue == DrawerValue.Open),
    ) {
        // The chat drawer and its scrim cover the content, not the app's navigation.
        ModalNavigationDrawer(
            modifier = Modifier.fillMaxSize(),
            drawerState = drawerState,
            gesturesEnabled = gesturesEnabled,
            drawerContent = drawerContent,
            content = content,
        )
    }
}

@Composable
private fun BottomNavigationBar(selectedTab: MainTab, onTabSelected: (MainTab) -> Unit) {
    Column {
        HorizontalDivider(color = Line)
        NavigationBar(containerColor = Paper, tonalElevation = 0.dp, windowInsets = WindowInsets(0, 0, 0, 0)) {
            MainTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                NavigationBarItem(
                    selected = selected,
                    onClick = { onTabSelected(tab) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { MainTabLabel(tab, selected) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Accent,
                        selectedTextColor = Ink,
                        indicatorColor = PaleAccent,
                        unselectedIconColor = Muted,
                        unselectedTextColor = Ink,
                    ),
                )
            }
        }
    }
}

@Composable
private fun MainTabLabel(tab: MainTab, selected: Boolean) {
    Text(tab.label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
}
