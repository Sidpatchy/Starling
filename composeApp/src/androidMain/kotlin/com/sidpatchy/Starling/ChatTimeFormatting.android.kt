package com.sidpatchy.Starling

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal actual fun formatChatTimestamp(
    timestampMillis: Long,
    timeZonePreference: TimestampTimeZonePreference,
): String {
    val timeZone = if (timeZonePreference == TimestampTimeZonePreference.UTC) {
        TimeZone.getTimeZone("UTC")
    } else {
        TimeZone.getDefault()
    }
    return SimpleDateFormat("HH:mm", Locale.ROOT).apply { this.timeZone = timeZone }
        .format(Date(timestampMillis))
}
