package com.finance.tracker.business.domain

import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.serialization.json.*

class CashLedgerCursorTest {
    private val filters = linkedMapOf<String, String?>(
        "state" to "all", "hide_zero_amount" to "True", "timezone" to "Asia/Shanghai",
        "counterparty" to "商户𐐀", "amount_min" to "-12.340000000000000001", "category_id" to null,
    )
    private val time = Instant.parse("2026-10-31T23:59:59.123456Z")
    private fun scope(workspace: String = "工作区", version: Long = 9007199254740993L,
        selected: Map<String, String?> = filters, rules: String = CASH_RULES_VERSION,
        projectionRules: String = CASH_PROJECTION_RULES_VERSION) =
        CashLedgerCursorScope(workspace, version, selected, rules, projectionRules)
    private fun payload(cursor: String) = Json.parseToJsonElement(
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(cursor).decodeToString(throwOnInvalidSequence = true),
    ).jsonObject
    private fun encode(value: JsonElement) = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        .encode(value.toString().encodeToByteArray())
    private fun rejects(code: String = "invalid_cursor", action: () -> Unit) =
        assertEquals(code, assertFailsWith<CashProjectionDomainFailure>(block = action).code)

    @Test fun exactVersionUnicodeFiltersAndMicrosecondsSurviveRoundTrip() {
        val cursor = scope().encode(time, "cash-entry:条目𐐀")
        assertEquals(CashLedgerCursorPosition(time, "cash-entry:条目𐐀"), scope().decode(cursor))
        val wire = payload(cursor)
        assertEquals(JsonPrimitive("9007199254740993"), wire["ledger_seq"])
        assertEquals(JsonPrimitive(CASH_RULES_VERSION), wire["rules_version"])
        assertEquals(JsonPrimitive(CASH_PROJECTION_RULES_VERSION), wire["projection_rules_version"])
        assertEquals(JsonObject(filters.mapValues { it.value?.let(::JsonPrimitive) ?: JsonNull }), wire["filters"])
        assertFalse(cursor.contains('='))
    }

    @Test fun ledgerOrEitherDerivedRuleVersionChangeRequiresACompleteReload() {
        val cursor = scope().encode(time, "cash-entry:a")
        for (changed in listOf(scope(version = 9007199254740994L), scope(rules = "cash-rules-v2"),
            scope(projectionRules = "cash-projection-v4"))) {
            rejects("read_snapshot_changed") { changed.decode(cursor) }
        }
        assertEquals(CashLedgerCursorPosition(time, "cash-entry:a"), scope().decode(cursor))
    }

    @Test fun workspaceAndEverySelectedFilterAreBoundWithoutDependingOnMapOrder() {
        val cursor = scope().encode(time, "cash-entry:a")
        rejects { scope(workspace = "other").decode(cursor) }
        for (name in filters.keys) rejects { scope(selected = filters + (name to "changed")).decode(cursor) }
        rejects { scope(selected = filters - "category_id").decode(cursor) }
        rejects { scope(selected = filters + ("currency" to "CNY")).decode(cursor) }
        assertEquals(scope().decode(cursor), scope(selected = filters.entries.reversed().associate { it.toPair() }).decode(cursor))
    }

    @Test fun constructorFreezesFiltersAgainstLaterCallerMutation() {
        val mutable = filters.toMutableMap()
        val captured = scope(selected = mutable)
        val before = captured.encode(time, "cash-entry:a")
        mutable["timezone"] = "UTC"
        assertEquals(before, captured.encode(time, "cash-entry:a"))
        assertEquals(scope().decode(before), captured.decode(before))
    }

    @Test fun malformedCursorsCannotBecomeStaleSnapshotErrorsOrPartialPositions() {
        for (wire in listOf("", "%%%", "a", "abc=", "[]", "null", "true", "1", "\"cursor\"")) {
            rejects { scope().decode(wire) }
        }
        for (value in listOf(JsonArray(emptyList()), JsonNull, JsonPrimitive(true), JsonPrimitive("cursor"))) {
            rejects { scope().decode(encode(value)) }
        }
        val valid = payload(scope().encode(time, "cash-entry:a"))
        for (name in valid.keys) rejects { scope().decode(encode(JsonObject(valid - name))) }
        rejects { scope().decode(encode(JsonObject(valid + ("extra" to JsonNull)))) }
        for (name in listOf("workspace", "ledger_seq", "rules_version", "projection_rules_version", "sort_at", "projection_id")) {
            for (value in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), JsonArray(emptyList()))) {
                rejects { scope().decode(encode(JsonObject(valid + (name to value)))) }
            }
        }
        for (version in listOf(JsonPrimitive("1"), JsonPrimitive(true), JsonPrimitive(2))) {
            rejects { scope().decode(encode(JsonObject(valid + ("v" to version)))) }
        }
        for (date in listOf("2026-10-31", "2026-02-30T00:00:00Z", "invalid")) {
            rejects { scope().decode(encode(JsonObject(valid + ("sort_at" to JsonPrimitive(date))))) }
        }
        rejects { scope().decode(encode(JsonObject(valid + ("projection_id" to JsonPrimitive(""))))) }
        rejects { scope().decode(encode(JsonObject(valid + ("filters" to JsonArray(emptyList()))))) }
        val invalidUtf8 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(byteArrayOf(0xc0.toByte(), 0x80.toByte()))
        rejects { scope().decode(invalidUtf8) }
    }

    @Test fun unknownDatesUseTheirRealSortTimeAndEqualTimesUsePythonCodePointOrder() {
        // Draft creation times are supplied as sortAt; they are never promoted to occurredAt.
        val created = Instant.parse("2026-10-31T00:00:00Z")
        val first = scope().decode(scope().encode(created, "cash-entry:𐀀"))
        assertFalse(first.isAfter(created, "cash-entry:𐀀"))
        assertFalse(first.isAfter(created, "cash-entry:𐐀"))
        assertTrue(first.isAfter(created, "cash-entry:\ue000"))
        assertTrue(first.isAfter(created - kotlin.time.Duration.parse("1us"), "cash-entry:𐐀"))
        assertFalse(first.isAfter(created + kotlin.time.Duration.parse("1us"), "cash-entry:a"))
    }

    @Test fun completeKeysetWalkHasNoDuplicatesOrOmissionsAcrossTies() {
        val rows = listOf("𐐀", "𐀀", "\ue000", "z", "b", "a").map { CashLedgerCursorPosition(time, "cash-entry:$it") } +
            listOf(CashLedgerCursorPosition(Instant.parse("2026-10-30T00:00:00Z"), "cash-entry:old"))
        val visited = mutableListOf<CashLedgerCursorPosition>()
        var cursor: String? = null
        do {
            val position = cursor?.let { scope().decode(it) }
            val page = rows.filter { position == null || position.isAfter(it.sortAt, it.projectionId) }.take(2)
            visited += page
            cursor = page.lastOrNull()?.let { scope().encode(it.sortAt, it.projectionId) }
        } while (page.isNotEmpty())
        assertEquals(rows, visited)
        assertEquals(rows.size, visited.distinct().size)
    }
}
