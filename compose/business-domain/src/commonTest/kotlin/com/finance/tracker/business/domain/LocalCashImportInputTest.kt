package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashImportInputTest {
    private val empty = JsonArray(emptyList())
    private fun json(text: String) = Json.parseToJsonElement(text)
    private fun prepare(accounts: JsonElement? = empty, rows: JsonElement? = empty, relations: JsonElement? = empty,
        entries: JsonElement? = null) = prepareLocalCashImportInput(accounts, rows, relations, entries)

    @Test fun completeChoicesAreCopiedAndPreserveIntegerRevisions() {
        val accounts = json("""[{"source_account_ref":"ref","account_id":"9101","expected_account_revision":9007199254740993}]""")
        val rows = json("""[{"row_ref":"f:r1","action":"apply","patch":{"note":"x"}}]""")
        val relations = json("""[{"suggestion_id":"sg","action":"accept","candidate_id":"c1","common_fields":{"note":"x"},"inherit_common_fields":true}]""")
        val entries = json("""[{"edit_ref":"e1","patch":{"note":"y"}}]""")
        val result = prepare(accounts, rows, relations, entries)
        assertEquals(buildJsonObject {
            put("account_selections", accounts); put("row_choices", rows); put("relation_choices", relations); put("entry_choices", entries)
        }, encodeLocalCashValue(result))
        @Suppress("UNCHECKED_CAST") val returnedRows = result.getValue("row_choices") as List<MutableMap<String, Any?>>
        @Suppress("UNCHECKED_CAST") val patch = returnedRows.single().getValue("patch") as MutableMap<String, Any?>
        patch["note"] = "edited"
        assertEquals("x", rows.jsonArray.single().jsonObject.getValue("patch").jsonObject.getValue("note").jsonPrimitive.content)
    }

    @Test fun invalidChoicesRetainPlanInputErrors() {
        val cases = listOf(
            Triple(empty, json("""[{"row_ref":"f:r1","action":"nope"}]"""), empty),
            Triple(empty, json("""[{"row_ref":"f:r1","action":"apply"},{"row_ref":"f:r1","action":"apply"}]"""), empty),
            Triple(empty, empty, json("""[{"suggestion_id":"sg","action":"accept","common_fields":{"amount":"1"}}]""")),
            Triple(empty, empty, json("""[{"suggestion_id":"sg","action":"defer","common_fields":{"note":"x"}}]""")),
            Triple(empty, empty, json("""[{"suggestion_id":"sg","action":"accept","inherit_common_fields":1}]""")),
        )
        for ((a, r, p) in cases) assertEquals("plan_input_invalid", assertFailsWith<CashImportPlanError> { prepare(a, r, p) }.code)
        assertEquals("plan_input_invalid", assertFailsWith<CashImportPlanError> {
            prepare(entries = json("""[{"edit_ref":"e1","patch":{"amount":"1"}}]"""))
        }.code)
        assertEquals("plan_input_invalid", assertFailsWith<CashImportPlanError> { prepare(accounts = JsonNull) }.code)
    }

    @Test fun revisionTokensAndReferenceBoundariesAreStrict() {
        for (token in listOf("0", "-1", "1.0", "1e0", "true", "\"1\"", "null")) {
            val accounts = json("""[{"source_account_ref":"ref","account_id":"1","expected_account_revision":$token}]""")
            assertFailsWith<CashImportPlanError>(token) { prepare(accounts) }
        }
        for (token in listOf("1", "9223372036854775808", "123456789012345678901234567890")) {
            prepare(json("""[{"source_account_ref":"ref","account_id":"1","expected_account_revision":$token}]"""))
        }
        prepare(json("""[{"source_account_ref":"ref","account_id":null,"expected_account_revision":null}]"""))
        assertFailsWith<CashImportPlanError> {
            prepare(json("""[{"source_account_ref":"ref","account_id":null,"expected_account_revision":1}]"""))
        }
        prepare(rows = json("""[{"row_ref":"${"r".repeat(255)}","action":"apply"}]"""))
        for (ref in listOf("r".repeat(256), "空白", "a b", "")) assertFailsWith<CashImportPlanError> {
            prepare(rows = json("""[{"row_ref":"$ref","action":"apply"}]"""))
        }
    }
}
