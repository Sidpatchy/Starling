package com.sidpatchy.Starling

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WelcomeScreenTest {

    @Test
    fun welcomeScreenIsOfferedForFreshLiveModeStartWithoutIdentity() {
        assertTrue(
            shouldShowWelcomeScreen(
                previewMode = false,
                rooms = emptyList(),
                networks = emptyList(),
                identityManagerAvailable = true,
                identityState = ReticulumIdentityState.Missing,
            ),
        )
    }

    @Test
    fun welcomeScreenIsHiddenInLocalPreview() {
        assertFalse(
            shouldShowWelcomeScreen(
                previewMode = true,
                rooms = emptyList(),
                networks = emptyList(),
                identityManagerAvailable = true,
                identityState = ReticulumIdentityState.Missing,
            ),
        )
    }

    @Test
    fun welcomeScreenIsHiddenOnceRoomsExist() {
        assertFalse(
            shouldShowWelcomeScreen(
                previewMode = false,
                rooms = listOf(ChatRoom("general", "net", "general", "", 0)),
                networks = emptyList(),
                identityManagerAvailable = true,
                identityState = ReticulumIdentityState.Missing,
            ),
        )
    }

    @Test
    fun welcomeScreenIsHiddenOnceALiveNetworkExists() {
        assertFalse(
            shouldShowWelcomeScreen(
                previewMode = false,
                rooms = emptyList(),
                networks = listOf(ChatNetwork("net", "Net", "0123456789abcdef0123456789abcdef")),
                identityManagerAvailable = true,
                identityState = ReticulumIdentityState.Missing,
            ),
        )
    }

    @Test
    fun welcomeScreenIsHiddenOnceAnIdentityExists() {
        assertFalse(
            shouldShowWelcomeScreen(
                previewMode = false,
                rooms = emptyList(),
                networks = emptyList(),
                identityManagerAvailable = true,
                identityState = ReticulumIdentityState.Available(
                    ReticulumIdentityInfo(
                        hash = "0123456789abcdef0123456789abcdef",
                        deliveryAddress = "fedcba9876543210fedcba9876543210",
                    ),
                ),
            ),
        )
    }

    @Test
    fun welcomeScreenIsHiddenWhenIdentityManagementIsUnsupported() {
        assertFalse(
            shouldShowWelcomeScreen(
                previewMode = false,
                rooms = emptyList(),
                networks = emptyList(),
                identityManagerAvailable = false,
                identityState = ReticulumIdentityState.Missing,
            ),
        )
    }
}
