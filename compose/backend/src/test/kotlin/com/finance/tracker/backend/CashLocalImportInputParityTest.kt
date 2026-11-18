package com.finance.tracker.backend

import com.finance.tracker.business.domain.encodeLocalCashValue
import com.finance.tracker.business.domain.prepareLocalCashImportInput
import kotlinx.serialization.json.*
import kotlin.test.*

class CashLocalImportInputParityTest {
    @Test fun completeJsonChoicesAndFailuresMatchNativeValueAdapters() {
        val empty = JsonArray(emptyList())
        fun compare(accounts: JsonElement = empty, rows: JsonElement = empty, relations: JsonElement = empty,
            entries: JsonElement? = null) {
            val native = runCatching { sourceJson(CashImportPlanViews.previewInput(cashSourceValue(accounts),
                cashSourceValue(rows), cashSourceValue(relations), cashSourceValue(entries))) }
            val local = runCatching { encodeLocalCashValue(prepareLocalCashImportInput(accounts, rows, relations, entries)) }
            assertEquals(native.getOrNull(), local.getOrNull(), "$accounts/$rows/$relations/$entries")
            assertEquals(native.exceptionOrNull()?.javaClass, local.exceptionOrNull()?.javaClass)
            assertEquals(native.exceptionOrNull()?.message, local.exceptionOrNull()?.message)
        }
        fun json(text: String) = Json.parseToJsonElement(text)
        val tokens = listOf("null", "true", "0", "-1", "1", "1.0", "1e0", "9007199254740993", "9223372036854775808",
            "123456789012345678901234567890", "\"1\"", "[]", "{}")
        for (token in tokens) {
            compare(accounts = json("""[{"source_account_ref":"ref","account_id":"1","expected_account_revision":$token}]"""))
            compare(accounts = json("""[{"source_account_ref":"ref","account_id":null,"expected_account_revision":$token}]"""))
            compare(rows = json("""[{"row_ref":"f:r1","action":"apply","patch":$token}]"""))
            compare(relations = json("""[{"suggestion_id":"sg","action":"accept","inherit_common_fields":$token}]"""))
        }
        for (action in listOf("accept", "defer", "ignore", "unknown")) for (field in listOf("note", "counterparty", "counterparty_account_attrs", "amount")) {
            for (token in tokens + listOf("\"x\"", "[\"x\"]")) {
                compare(relations = json("""[{"suggestion_id":"sg","action":"$action","common_fields":{"$field":$token}}]"""))
            }
        }
        compare(rows = json("""[{"row_ref":"f:r1","action":"apply","patch":{"source_payload":{"n":1.0,"i":9007199254740993}}}]"""))
        compare(rows = json("""[{"row_ref":"f:r1","action":"apply"},{"row_ref":"f:r1","action":"keep_existing"}]"""))
        compare(entries = json("""[{"edit_ref":"e1","patch":{"note":1}}]""")) // Structural entry validation preserves its deferred field-validation boundary.
    }
}
