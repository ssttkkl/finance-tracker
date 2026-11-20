@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.core

import kotlin.time.Instant

internal actual fun cashLedgerPlatformOffset(epochSeconds: String, timeZoneId: String): Int =
    com.finance.tracker.business.domain.wasmCashLedgerOffset(epochSeconds, timeZoneId)

internal actual fun cashLedgerPlatformTimeZoneExists(timeZoneId: String): Boolean = js("""(() => {
    if (!timeZoneId || /^[+-]/.test(timeZoneId)) return false;
    try { new Intl.DateTimeFormat('en', { timeZone: timeZoneId }); return true; }
    catch (error) { if (error instanceof RangeError) return false; throw error; }
})()""")

@JsModule("@js-temporal/polyfill")
private external object LedgerTemporalModule { val Temporal: JsAny }

@JsFun("(temporal, date, zone) => temporal.PlainDate.from(date).toZonedDateTime(zone).toInstant().toString()")
private external fun ledgerStartOfDay(temporal: JsAny, date: String, zone: String): String

internal actual fun cashLedgerPlatformStartOfDay(date: String, timeZoneId: String): Instant =
    Instant.parse(ledgerStartOfDay(LedgerTemporalModule.Temporal, date, timeZoneId))
