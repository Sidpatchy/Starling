package com.sidpatchy.Starling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ChatTimeFormattingTest {
    @Test
    fun utcTimestampsFormatAcrossDayBoundaries() {
        assertEquals("00:00", formatChatTimestamp(0L, TimestampTimeZonePreference.UTC))
        assertEquals("23:59", formatChatTimestamp(86_399_000L, TimestampTimeZonePreference.UTC))
        assertEquals("00:00", formatChatTimestamp(86_400_000L, TimestampTimeZonePreference.UTC))
        assertEquals("23:59", formatChatTimestamp(-60_000L, TimestampTimeZonePreference.UTC))
    }

    @Test
    fun dividerUsesUtcCalendarDayAcrossNewYear() {
        val formatDate = chatDateFormatter(TimestampTimeZonePreference.UTC)
        assertEquals("Dec 31, 2025", formatDate(epochMillis("2025-12-31T23:59:59Z")))
        assertEquals("Jan 1, 2026", formatDate(epochMillis("2026-01-01T00:00:00Z")))
    }

    @Test
    fun labelsAppearOnlyAtKnownDayChanges() {
        val formatDate = chatDateFormatter(TimestampTimeZonePreference.UTC)
        val messages = listOf(
            message("a", null),
            message("b", "2025-12-31T23:58:00Z"),
            message("c", null),
            message("d", "2025-12-31T23:59:00Z"),
            message("e", "2026-01-01T00:00:00Z"),
            message("f", "2026-01-01T00:01:00Z"),
        )
        assertEquals(
            listOf(null, "Dec 31, 2025", null, null, "Today", null),
            chatMessageDayLabels(messages, today = "Jan 1, 2026", formatDate),
        )
        assertEquals(
            listOf(null, "Dec 31, 2025", null, null, "Jan 1, 2026", null),
            chatMessageDayLabels(messages, today = "Jan 2, 2026", formatDate),
        )
    }

    private fun message(id: String, timestamp: String?) =
        ChatMessage(id, "moss", "Hello", time = "12:00", timestampMillis = timestamp?.let(::epochMillis))

    private fun epochMillis(iso: String): Long = Instant.parse(iso).toEpochMilliseconds()
}
