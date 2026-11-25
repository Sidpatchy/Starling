package com.sidpatchy.Starling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainNavigationTest {
    @Test
    fun chatSidebarPagesKeepChatSelectedAndReturnToTheConversation() {
        listOf(MainPage.NEARBY, MainPage.CONTACT_REQUESTS).forEach { page ->
            assertEquals(MainTab.CHAT, page.tab)
            assertEquals(MainPage.CHAT, page.parent)
        }
    }

    @Test
    fun chatShowsBottomNavigationOnlyWithTheDrawerOpen() {
        assertFalse(MainTab.CHAT.showsBottomNavigation(chatDrawerOpen = false))
        assertTrue(MainTab.CHAT.showsBottomNavigation(chatDrawerOpen = true))
    }

    @Test
    fun settingsKeepsBottomNavigationWithoutTheChatDrawer() {
        assertTrue(MainTab.SETTINGS.showsBottomNavigation(chatDrawerOpen = false))
        assertEquals(MainPage.CHAT, MainPage.SETTINGS.parent)
    }
}
