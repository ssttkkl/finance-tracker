package com.finance.tracker.backend

import kotlinx.serialization.json.*

internal class CashStatementParseInternalFailure(cause: Throwable) : RuntimeException("statement_parser_failed", cause)
internal class CashStatementWorkerFailure(val trace: JsonObject) : RuntimeException("isolated_statement_parser_failed")

internal fun statementParseFailure(failure: Throwable): Throwable = when (failure) {
    is kotlinx.coroutines.CancellationException, is CashStatementParseInternalFailure, is StatementPdfPasswordFailure,
    is CashImportProtocolError, is AccessFailure, is StorageFailure -> failure
    is StatementDocumentFailure -> if (failure.code in PDF_ERROR_CODES ||
        failure.code in setOf("statement_parser_busy", "statement_parser_timeout")) failure
        else CashStatementParseInternalFailure(failure)
    else -> CashStatementParseInternalFailure(failure)
}

/** Only code locations and exception types cross the worker/log boundary, never exception messages. */
internal fun statementFailureTrace(failure: Throwable): JsonObject {
    val causes = mutableListOf<JsonElement>()
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = failure
    while (current != null && causes.size < 8 && seen.add(current)) {
        val active = current
        if (active is CashStatementWorkerFailure) return active.trace
        val frames = active.stackTrace
        causes += buildJsonObject {
            put("type", active.javaClass.name)
            put("frames", JsonArray(frames.take(64).map { JsonPrimitive("${it.className}:${it.fileName}:${it.lineNumber}:${it.methodName}") }))
            put("omitted_frames", (frames.size - 64).coerceAtLeast(0))
        }
        current = active.cause
    }
    return buildJsonObject {
        put("causes", JsonArray(causes)); put("causes_truncated", current != null)
    }
}

internal fun readStatementWorkerFailure(value: JsonElement): CashStatementParseInternalFailure {
    fun malformed(): Nothing = throw IllegalArgumentException("invalid worker failure protocol")
    val trace = value as? JsonObject ?: malformed()
    if (trace.keys != setOf("causes", "causes_truncated")) malformed()
    val causes = trace["causes"] as? JsonArray ?: malformed()
    val truncated = trace["causes_truncated"] as? JsonPrimitive ?: malformed()
    if (causes.size !in 1..8 || truncated.isString || truncated.booleanOrNull == null) malformed()
    for (cause in causes) {
        val item = cause as? JsonObject ?: malformed()
        if (item.keys != setOf("type", "frames", "omitted_frames")) malformed()
        val type = item["type"] as? JsonPrimitive ?: malformed()
        if (!type.isString || !type.content.matches(Regex("[A-Za-z0-9_.$]{1,256}"))) malformed()
        val frames = item["frames"] as? JsonArray ?: malformed()
        if (frames.size > 64 || frames.any { it !is JsonPrimitive || !it.isString || it.content.length > 2048 }) malformed()
        val omitted = item["omitted_frames"] as? JsonPrimitive ?: malformed()
        if (omitted.isString || omitted.intOrNull?.let { it >= 0 } != true) malformed()
    }
    return CashStatementParseInternalFailure(CashStatementWorkerFailure(trace))
}

internal fun statementWorkerResponse(action: () -> JsonObject): JsonObject = try {
    action()
} catch (failure: StatementPdfPasswordFailure) {
    buildJsonObject { put("error", if (failure.required) "password_required" else "password_invalid") }
} catch (failure: StatementDocumentFailure) {
    buildJsonObject { put("error", if (failure.code in PDF_ERROR_CODES) failure.code else "statement_unreadable") }
} catch (_: java.io.IOException) {
    buildJsonObject { put("error", "statement_unreadable") }
} catch (failure: Throwable) {
    buildJsonObject { put("internal_failure", statementFailureTrace(failure)) }
}
