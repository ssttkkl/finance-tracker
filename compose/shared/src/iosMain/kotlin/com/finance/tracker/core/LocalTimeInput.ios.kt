package com.finance.tracker.core

import platform.Foundation.*

actual fun instantForLocalDateTime(value: String, timeZoneId: String): String? {
    if (!Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}").matches(value)) return null
    val zone = NSTimeZone.timeZoneWithName(timeZoneId) ?: return null
    val local = NSDateFormatter().apply {
        locale = NSLocale("en_US_POSIX")
        calendar = NSCalendar(NSCalendarIdentifierGregorian)
        timeZone = zone
        dateFormat = "yyyy-MM-dd'T'HH:mm"
        lenient = false
    }
    val parsed = local.dateFromString(value) ?: return null
    if (local.stringFromDate(parsed) != value) return null
    val base = parsed.timeIntervalSince1970
    val baseOffset = zone.secondsFromGMTForDate(parsed)
    val offsets = (-8..8).map { zone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(base + it * 21600.0)) }.distinct()
    val candidates = offsets.map { NSDate.dateWithTimeIntervalSince1970(base + baseOffset - it) }.filter { local.stringFromDate(it) == value }
    if (candidates.size != 1) return null
    return NSDateFormatter().apply {
        locale = NSLocale("en_US_POSIX")
        calendar = NSCalendar(NSCalendarIdentifierGregorian)
        timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
        dateFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'"
    }.stringFromDate(candidates.single())
}
