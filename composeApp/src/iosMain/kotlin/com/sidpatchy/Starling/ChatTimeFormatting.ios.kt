package com.sidpatchy.Starling

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone

internal actual fun formatChatTimestamp(
    timestampMillis: Long,
    timeZonePreference: TimestampTimeZonePreference,
): String {
    if (timeZonePreference == TimestampTimeZonePreference.UTC) return formatUtcChatTimestamp(timestampMillis)
    return chatDateTimeFormatter("HH:mm", timeZonePreference).stringFromDate(dateFromEpochMillis(timestampMillis))
}

internal actual fun chatDateFormatter(timeZonePreference: TimestampTimeZonePreference): (Long) -> String {
    val formatter = chatDateTimeFormatter("MMM d, yyyy", timeZonePreference)
    return { formatter.stringFromDate(dateFromEpochMillis(it)) }
}

private fun chatDateTimeFormatter(pattern: String, timeZonePreference: TimestampTimeZonePreference): NSDateFormatter =
    NSDateFormatter().apply {
        locale = NSLocale("en_US_POSIX")
        if (timeZonePreference == TimestampTimeZonePreference.UTC) {
            timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
        }
        dateFormat = pattern
    }

private fun dateFromEpochMillis(timestampMillis: Long): NSDate =
    NSDate(timestampMillis.toDouble() / 1_000.0 - 978_307_200.0)
