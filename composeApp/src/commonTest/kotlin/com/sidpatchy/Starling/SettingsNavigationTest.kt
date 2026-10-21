package com.sidpatchy.Starling

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsNavigationTest {
    @Test
    fun compactSettingsSectionBackReturnsToTheSectionIndex() {
        assertEquals(
            SettingsBackTarget.SETTINGS_INDEX,
            settingsBackTarget(isWideLayout = false, showSectionIndex = false),
        )
    }

    @Test
    fun compactSettingsIndexBackExitsSettings() {
        assertEquals(
            SettingsBackTarget.EXIT_SETTINGS,
            settingsBackTarget(isWideLayout = false, showSectionIndex = true),
        )
    }

    @Test
    fun wideSettingsBackExitsSettingsInsteadOfNavigatingToAnInvisibleIndex() {
        assertEquals(
            SettingsBackTarget.EXIT_SETTINGS,
            settingsBackTarget(isWideLayout = true, showSectionIndex = false),
        )
    }
}
