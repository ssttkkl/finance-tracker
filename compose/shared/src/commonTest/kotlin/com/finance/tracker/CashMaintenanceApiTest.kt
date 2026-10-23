package com.finance.tracker

import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class CashMaintenanceApiTest {
    @Test fun currentRecordKeepsNullPermissionsAndStableIdentity() = runTest {
        var call: HttpRequestData? = null
        val api = client(RECORD) { call = it }
        val record = api.readCurrentCashRecord("9007199254740993")
        assertEquals("/api/v1/cash-records/9007199254740993", call!!.url.encodedPath)
        assertEquals("Bearer isolated-token", call!!.headers[HttpHeaders.Authorization])
        assertEquals(JsonNull, record.fields["amount"])
        assertEquals("entry1", record.entry!!.entryId)
        assertEquals(8L, record.entry!!.revision)
        assertEquals(listOf("note", "category_id"), record.entry!!.editableFields)
        api.close()
    }
    @Test fun previewKeepsExplicitClearsAndOmitsUnusedContext() = runTest {
        var sent: JsonObject? = null
        val api = client(PLAN) { sent = body(it) }
        api.previewCashMaintenance(CashMaintenanceInput("edit_entry", "11", 2,
            patch = buildJsonObject { put("note", ""); put("category_id", JsonNull) }, entryId = "entry1", expectedEntryRevision = 8))
        val value = assertNotNull(sent)
        for (key in listOf("edit_context_id", "previous_plan_id", "expected_plan_revision")) assertFalse(key in value)
        assertEquals(JsonNull, value["patch"]!!.jsonObject["category_id"])
        assertEquals("", value["patch"]!!.jsonObject["note"]!!.jsonPrimitive.content)
        assertFalse("amount" in value["patch"]!!.jsonObject)
        api.close()
    }
    @Test fun wholeRelationGroupKeepsExplicitFalseAndContextTrio() = runTest {
        var sent: JsonObject? = null
        val api = client(PLAN) { sent = body(it) }
        api.previewCashMaintenance(CashMaintenanceInput("maintain_relations", "11", 2,
            relationChoices = listOf(CashMaintenanceRelationInput("group", "remove", inheritCommonFields = false)),
            editContextId = "context", previousPlanId = "mp_old", expectedPlanRevision = 3))
        val value = assertNotNull(sent)
        assertFalse("issue_resolutions" in value)
        assertEquals("context", value["edit_context_id"]!!.jsonPrimitive.content)
        assertEquals(3L, value["expected_plan_revision"]!!.jsonPrimitive.long)
        assertFalse(value["relation_choices"]!!.jsonArray.single().jsonObject["inherit_common_fields"]!!.jsonPrimitive.boolean)
        api.close()
    }
    @Test fun issueAndTransferPatchesKeepOpaqueIdsAndExactMoney() = runTest {
        val sent = mutableListOf<JsonObject>()
        val api = client(PLAN) { sent += body(it) }
        api.previewCashMaintenance(CashMaintenanceInput("resolve_issue", "11", 2,
            issueResolutions = listOf(CashIssueResolutionInput("21", 4, "merge_into", "9007199254740994", 6))))
        assertEquals("9007199254740994", sent.single()["issue_resolutions"]!!.jsonArray.single().jsonObject["target_transaction_id"]!!.jsonPrimitive.content)
        api.previewCashMaintenance(CashMaintenanceInput("edit_entry", "11", 2, entryId = "entry1", expectedEntryRevision = 8,
            memberPatches = listOf(CashMemberPatchInput("11", buildJsonObject { put("amount", "12345678901234567890.0100") }), CashMemberPatchInput("12", buildJsonObject { put("amount", "23.0001") }))))
        assertEquals("12345678901234567890.0100", sent.last()["member_patches"]!!.jsonArray.first().jsonObject["patch"]!!.jsonObject["amount"]!!.jsonPrimitive.content)
        api.close()
    }
    @Test fun invalidDependenciesFailBeforeNetwork() = runTest {
        var calls = 0
        val api = client(PLAN) { calls++ }
        for (input in listOf(CashMaintenanceInput("edit_entry", "11", 2), CashMaintenanceInput("maintain_relations", "11", 2, previousPlanId = "mp_old"), CashMaintenanceInput("resolve_issue", "11", 2), CashMaintenanceInput("edit_record", "11", 0))) {
            assertFailsWith<ApiFailure> { api.previewCashMaintenance(input) }
        }
        assertEquals(0, calls)
        api.close()
    }
    @Test fun draftSaveReturnsReceiptEvenWhenCurrentReadIsUnavailable() = runTest {
        var call: HttpRequestData? = null
        val api = client("""{"receipt":$RECEIPT,"current_record_status":"unavailable","current_record":null}""") { call = it }
        val result = api.saveCashDraft("11", 3, buildJsonObject { put("amount", "0.00"); put("category_id", JsonNull) }, "draft-original")
        assertEquals(HttpMethod.Patch, call!!.method)
        assertEquals("/api/v1/cash-records/11/draft", call!!.url.encodedPath)
        assertEquals("draft-original", call!!.headers["Idempotency-Key"])
        assertEquals(3L, body(call!!)["expected_revision"]!!.jsonPrimitive.long)
        assertEquals("unavailable", result.currentRecordStatus)
        assertTrue(result.receipt.committed)
        api.close()
    }
    @Test fun commitAndPaginationUseOriginalRevisionKeyAndEscapedCursor() = runTest {
        var call: HttpRequestData? = null
        val api = client(RECEIPT) { call = it }
        api.commitCashMaintenance("mp_one", 9, "original-key")
        assertEquals("/api/v1/cash-mutations/plans/mp_one/commit", call!!.url.encodedPath)
        assertEquals(buildJsonObject { put("expected_plan_revision", 9) }, body(call!!))
        assertEquals("original-key", call!!.headers["Idempotency-Key"])
        api.close()
        val pages = client("""{"items":[],"next_cursor":null}""") { call = it }
        pages.readCashMaintenanceItems("mp_one", "cursor/+?&", 7, "pending")
        assertEquals("cursor/+?&", call!!.url.parameters["cursor"])
        assertEquals("pending", call!!.url.parameters["confirmation_status"])
        pages.close()
    }
    @Test fun detailKeepsPublicEntryConflictsAndExplicitCommonClear() {
        val detail = apiJson.decodeFromString<CashPlanItemDetailDto>("""{"preview_item_id":"pi_1","entry":{"fields":{"note":"共同备注","category_id":null},"editable_fields":["note","category_id"]},"members":[],"field_diffs":[],"issues":[],"relation_choices":[{"suggestion_id":"sg","action":"accept","candidates":[],"member_refs":[],"allowed_actions":["accept"],"common_field_conflicts":{"note":["甲","乙"]},"common_fields":{"note":""},"inherit_common_fields":false}],"relation_effects":[]}""")
        assertEquals("共同备注", detail.entry!!.fields["note"]!!.jsonPrimitive.content)
        val choice = detail.relationChoices.single()
        assertEquals(2, choice.commonFieldConflicts["note"]!!.size)
        assertEquals("", choice.commonFields!!["note"]!!.jsonPrimitive.content)
        assertEquals(false, choice.inheritCommonFields)
    }
    private fun client(response: String, inspect: suspend (HttpRequestData) -> Unit) = FinanceApiClient({ "https://api.example.test" }, MaintenanceTokenStore(), MockEngine {
        inspect(it); respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    })
    private suspend fun body(call: HttpRequestData) = apiJson.parseToJsonElement(call.body.toByteArray().decodeToString()).jsonObject
}
private class MaintenanceTokenStore : TokenStore {
    override suspend fun get() = "isolated-token"
    override suspend fun set(value: String) = Unit
    override suspend fun clear() = Unit
}
private const val RECORD = """{"record_origin":"import","transaction_id":"9007199254740993","state":"draft","row_revision":3,"confirmation_status":"pending","fields":{"amount":null,"note":"账单备注"},"editable_fields":["amount"],"entry":{"entry_id":"entry1","revision":8,"fields":{"note":"共同备注","category_id":null},"manual_fields":["note"],"editable_fields":["note","category_id"],"supports_transfer_edit":false},"issues":[],"relations":[],"projection_ref":null}"""
private const val PLAN = """{"plan_id":"mp_one","edit_context_id":"context","plan_revision":1,"expires_at":"2099-01-01T00:00:00Z","summary":{},"item_counts":{"all":0,"pending":0},"can_commit":true,"items":[],"pages":{"items":null},"invalidated_choices":[]}"""
private const val RECEIPT = """{"receipt_id":"receipt1","plan_id":"mp_one","operation_kind":"save_draft","committed":true,"created_at":"2026-10-24T00:00:00Z","status":"completed","summary":{"saved_draft":1},"retained_transaction_id":"11"}"""
