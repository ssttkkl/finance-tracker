package com.finance.tracker.core

import kotlin.time.Instant

internal actual fun cashLedgerPlatformOffset(epochSeconds: String, timeZoneId: String): Int = js("""(() => {
    const timestamp = Number(epochSeconds) * 1000;
    const formatter = new Intl.DateTimeFormat('en-US-u-ca-gregory-nu-latn', {
        timeZone: timeZoneId, era: 'short', year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
    });
    const parts = Object.fromEntries(formatter.formatToParts(new Date(timestamp)).map(p => [p.type, p.value]));
    const year = parts.era === 'BC' ? 1 - Number(parts.year) : Number(parts.year);
    const local = new Date(0);
    local.setUTCFullYear(year, Number(parts.month) - 1, Number(parts.day));
    local.setUTCHours(Number(parts.hour), Number(parts.minute), Number(parts.second), 0);
    const offset = (local.getTime() - timestamp) / 1000;
    if (!Number.isInteger(offset) || Math.abs(offset) > 86400) throw new Error('Invalid ledger time zone offset');
    return offset;
})()""")

internal actual fun cashLedgerPlatformTimeZoneExists(timeZoneId: String): Boolean = js("""(() => {
    if (!timeZoneId || /^[+-]/.test(timeZoneId)) return false;
    try { new Intl.DateTimeFormat('en', { timeZone: timeZoneId }); return true; }
    catch (error) { if (error instanceof RangeError) return false; throw error; }
})()""")

@JsModule("@js-temporal/polyfill")
@JsNonModule
private external object LedgerTemporalModule { val Temporal: dynamic }

internal actual fun cashLedgerPlatformStartOfDay(date: String, timeZoneId: String): Instant {
    val text = LedgerTemporalModule.Temporal.PlainDate.from(date).toZonedDateTime(timeZoneId).toInstant().toString() as String
    return Instant.parse(text)
}
