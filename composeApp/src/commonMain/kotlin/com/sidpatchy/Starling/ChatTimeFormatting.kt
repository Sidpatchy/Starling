package com.sidpatchy.Starling

internal expect fun formatChatTimestamp(
    timestampMillis: Long,
    timeZonePreference: TimestampTimeZonePreference,
): String

internal fun formatUtcChatTimestamp(timestampMillis: Long): String {
    val millisPerDay = 86_400_000L
    val millisOfDay = ((timestampMillis % millisPerDay) + millisPerDay) % millisPerDay
    val hour = (millisOfDay / 3_600_000L).toInt()
    val minute = ((millisOfDay % 3_600_000L) / 60_000L).toInt()
    return hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')
}

internal fun displayChatTime(
    fallback: String,
    timestampMillis: Long?,
    timeZonePreference: TimestampTimeZonePreference,
): String = timestampMillis?.let { formatChatTimestamp(it, timeZonePreference) } ?: fallback
