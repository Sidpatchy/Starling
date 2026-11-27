package com.sidpatchy.Starling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SidebarSelectionTest {
    @Test
    fun viewingAChannelHighlightsOnlyThatChannel() {
        assertEquals(SidebarSelection(roomId = "general"), sidebarSelectionForVisiblePage("general", null))
    }

    @Test
    fun viewingADirectMessageDoesNotHighlightTheRememberedChannel() {
        assertEquals(SidebarSelection(directMessageId = "moss"), sidebarSelectionForVisiblePage("general", "moss"))
    }

    @Test
    fun nearbyPeopleHidesBothChatHighlightsAndRestoresThemAfterwards() {
        for (directMessageId in listOf(null, "moss")) {
            assertEquals(SidebarSelection(), sidebarSelectionForVisiblePage("general", directMessageId, MainPage.NEARBY))
        }
        assertEquals(SidebarSelection(roomId = "general"), sidebarSelectionForVisiblePage("general", null))
        assertEquals(SidebarSelection(directMessageId = "moss"), sidebarSelectionForVisiblePage("general", "moss"))
        assertEquals(SidebarSelection(), sidebarSelectionForVisiblePage(null, null, MainPage.NEARBY))
    }

    @Test
    fun settingsAndRequestsHideBothChatHighlights() {
        for (directMessageId in listOf(null, "moss")) {
            assertEquals(SidebarSelection(), sidebarSelectionForVisiblePage("general", directMessageId, MainPage.SETTINGS))
            assertEquals(SidebarSelection(), sidebarSelectionForVisiblePage("general", directMessageId, MainPage.CONTACT_REQUESTS))
        }
    }

    @Test
    fun returningToChatRestoresTheRememberedChannelHighlight() {
        val rememberedRoom = "field-notes"
        assertEquals(SidebarSelection(), sidebarSelectionForVisiblePage(rememberedRoom, null, MainPage.SETTINGS))
        assertEquals(SidebarSelection(roomId = rememberedRoom), sidebarSelectionForVisiblePage(rememberedRoom, null))
    }

    @Test
    fun returningFromSettingsToADirectMessageRestoresOnlyTheDirectMessageHighlight() {
        assertEquals(SidebarSelection(), sidebarSelectionForVisiblePage("general", "moss", MainPage.SETTINGS))
        assertEquals(SidebarSelection(directMessageId = "moss"), sidebarSelectionForVisiblePage("general", "moss"))
    }

    @Test
    fun anEmptyChatDoesNotHighlightAChannel() {
        assertEquals(SidebarSelection(), sidebarSelectionForVisiblePage(null, null))
    }

    @Test
    fun pickerDefaultsToTheSectionForTheOpenConversationOrInboxPage() {
        assertEquals(ChatSidebarSection.CHANNELS, defaultChatSidebarSection(SidebarSelection(roomId = "general"), MainPage.CHAT))
        assertEquals(ChatSidebarSection.DIRECT_MESSAGES, defaultChatSidebarSection(SidebarSelection(directMessageId = "moss"), MainPage.CHAT))
        assertEquals(ChatSidebarSection.DIRECT_MESSAGES, defaultChatSidebarSection(SidebarSelection(), MainPage.NEARBY))
        assertEquals(ChatSidebarSection.DIRECT_MESSAGES, defaultChatSidebarSection(SidebarSelection(), MainPage.CONTACT_REQUESTS))
    }

    @Test
    fun togglingOneNetworkLeavesOtherCollapsedNetworksAlone() {
        val collapsed = setOf("field")
        assertEquals(setOf("field", "mesh"), toggleSidebarNetwork(collapsed, "mesh"))
        assertEquals(collapsed, toggleSidebarNetwork(setOf("field", "mesh"), "mesh"))
    }

    @Test
    fun channelActionsPreferTheCurrentChannelNetworkOverTheConnectedHub() {
        val configured = ChatNetwork("mesh", "Mesh")
        val connected = ChatNetwork("field", "Field")
        val room = ChatRoom("general", configured.id, "general", "", 0)
        val networks = listOf(connected, configured)
        assertEquals(configured, channelActionNetwork(networks, room, connected.id))
        assertEquals(connected, channelActionNetwork(networks, null, connected.id))
        assertEquals(connected, channelActionNetwork(networks, room.copy(networkId = "removed"), null))
        assertNull(channelActionNetwork(emptyList(), room, null))
    }
}
