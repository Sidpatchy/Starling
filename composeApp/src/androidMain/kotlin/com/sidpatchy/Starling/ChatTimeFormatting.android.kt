package com.sidpatchy.Starling

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal actual fun formatChatTimestamp(
    timestampMillis: Long,
    timeZonePreference: TimestampTimeZonePreference,
): String = chatDateTimeFormatter("HH:mm", timeZonePreference).format(Date(timestampMillis))

internal actual fun chatDateFormatter(timeZonePreference: TimestampTimeZonePreference): (Long) -> String {
    val formatter = chatDateTimeFormatter("MMM d, yyyy", timeZonePreference)
    return { formatter.format(Date(it)) }
}

private fun chatDateTimeFormatter(pattern: String, timeZonePreference: TimestampTimeZonePreference): SimpleDateFormat {
    val timeZone = if (timeZonePreference == TimestampTimeZonePreference.UTC) {
        TimeZone.getTimeZone("UTC")
    } else {
        TimeZone.getDefault()
    }
    return SimpleDateFormat(pattern, Locale.ROOT).apply { this.timeZone = timeZone }
}
