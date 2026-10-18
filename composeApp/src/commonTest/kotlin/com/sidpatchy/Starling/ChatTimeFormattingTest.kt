package com.sidpatchy.Starling

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatTimeFormattingTest {
    @Test
    fun utcTimestampsFormatAcrossDayBoundaries() {
        assertEquals("00:00", formatChatTimestamp(0L, TimestampTimeZonePreference.UTC))
        assertEquals("23:59", formatChatTimestamp(86_399_000L, TimestampTimeZonePreference.UTC))
        assertEquals("00:00", formatChatTimestamp(86_400_000L, TimestampTimeZonePreference.UTC))
        assertEquals("23:59", formatChatTimestamp(-60_000L, TimestampTimeZonePreference.UTC))
    }
}
