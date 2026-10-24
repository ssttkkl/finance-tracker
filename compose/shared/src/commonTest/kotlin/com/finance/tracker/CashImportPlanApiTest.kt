package com.finance.tracker

import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class CashImportPlanApiTest {
    @Test fun receiptCommitOutcomeDistinguishesNewErrorsAndPreservesLegacyAbsence() {
        val legacy = apiJson.decodeFromString<CashMutationReceiptDto>(RECEIPT).toDomain()
        assertNull(legacy.commitOutcome)
        val body = apiJson.parseToJsonElement(RECEIPT).jsonObject.toMutableMap()
        body["commit_outcome"] = buildJsonObject {
            put("has_new_failures", true); put("has_business_writes", false)
            put("reason_codes", JsonArray(listOf(JsonPrimitive("row_save_failed"))))
        }
        val current = apiJson.decodeFromJsonElement<CashMutationReceiptDto>(JsonObject(body)).toDomain()
        val outcome = assertNotNull(current.commitOutcome)
        assertTrue(outcome.hasNewFailures)
        assertFalse(outcome.hasBusinessWrites)
        assertEquals(listOf("row_save_failed"), outcome.reasonCodes)
    }

    @Test fun optionalCommonDecisionsAreOmittedWhileExplicitClearsAndFalseArePreserved() = runTest {
        val calls = mutableListOf<JsonObject>()
        val api = client(PLAN) { calls += json(it) }
        api.previewCashImport("s1", CashImportPreviewInput(3, null, emptyList(), emptyList(), listOf(CashImportRelationInput("sg", CashImportRelationAction.DEFER))))
        val omitted = calls.single()["relation_choices"]!!.jsonArray.single().jsonObject
        assertFalse("common_fields" in omitted)
        assertFalse("inherit_common_fields" in omitted)
        assertEquals(JsonNull, calls.single()["previous_plan_id"])
        api.previewCashImport("s1", CashImportPreviewInput(3, null, emptyList(), emptyList(), listOf(CashImportRelationInput("sg", CashImportRelationAction.ACCEPT,
            commonFields = buildJsonObject { put("category_id", JsonNull); put("note", "") }, inheritCommonFields = false))))
        val explicit = calls.last()["relation_choices"]!!.jsonArray.single().jsonObject
        assertEquals(JsonNull, explicit["common_fields"]!!.jsonObject["category_id"])
        assertEquals("", explicit["common_fields"]!!.jsonObject["note"]!!.jsonPrimitive.content)
        assertEquals(false, explicit["inherit_common_fields"]!!.jsonPrimitive.boolean)
        api.close()
    }

    @Test fun firstPreviewSendsRequiredNullAndAllReplacementArrays() = runTest {
        var call: HttpRequestData? = null
        val client = client(PLAN) { call = it }
        val plan = client.previewCashImport("s1", CashImportPreviewInput(3, null, emptyList(), emptyList(), emptyList()))
        assertEquals("/api/v1/cash-import/sessions/s1/plans", call!!.url.encodedPath)
        val body = json(call!!)
        assertEquals(setOf("expected_session_revision", "previous_plan_id", "account_selections", "row_choices", "relation_choices"), body.keys)
        assertEquals(JsonNull, body["previous_plan_id"])
        for (key in listOf("account_selections", "row_choices", "relation_choices")) assertEquals(JsonArray(emptyList()), body[key])
        assertEquals(4L, plan.sessionRevision)
        assertTrue(plan.canCommit)
        assertEquals("0.00", plan.items.single().display.amount)
        client.close()
    }

    @Test fun repreviewPreservesExplicitClearsAndWholeGroupChoices() = runTest {
        var body: JsonObject? = null
        val client = client(PLAN) { body = json(it) }
        client.previewCashImport("s1", CashImportPreviewInput(4, "ip_old", listOf(
            CashImportAccountInput("source-a", "9007199254740993", 7), CashImportAccountInput("source-b", null, null)),
            listOf(CashImportRowInput("f1:r1", CashImportRowAction.APPLY, buildJsonObject { put("note", JsonNull); put("category_id", JsonNull); put("amount", "12345678901234567890.0100") })),
            listOf(CashImportRelationInput("whole-group", CashImportRelationAction.DEFER, null))))
        val value = assertNotNull(body)
        assertEquals("ip_old", value["previous_plan_id"]!!.jsonPrimitive.content)
        val accounts = value["account_selections"]!!.jsonArray
        assertEquals("9007199254740993", accounts[0].jsonObject["account_id"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, accounts[1].jsonObject["account_id"])
        val row = value["row_choices"]!!.jsonArray.single().jsonObject
        assertEquals("apply", row["action"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, row["patch"]!!.jsonObject["note"])
        assertEquals("12345678901234567890.0100", row["patch"]!!.jsonObject["amount"]!!.jsonPrimitive.content)
        val relation = value["relation_choices"]!!.jsonArray.single().jsonObject
        assertEquals("defer", relation["action"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, relation["candidate_id"])
        client.close()
    }

    @Test fun itemAndProcessingPaginationEscapeCursorsAndUseLocalPaths() = runTest {
        val calls = mutableListOf<HttpRequestData>()
        val client = client("""{"items":[],"next_cursor":"next/+?&"}""") { calls += it }
        assertEquals("next/+?&", client.readCashImportItems("ip_one", "cursor/+?&", 17, "pending").nextCursor)
        assertEquals("cursor/+?&", calls.single().url.parameters["cursor"])
        assertEquals("pending", calls.single().url.parameters["confirmation_status"])
        client.readCashImportProcessing("ip_one", "next/+?&", 9)
        assertTrue(calls.last().url.encodedPath.endsWith("/processing-details"))
        assertNull(calls.last().url.parameters["confirmation_status"])
        assertEquals("9", calls.last().url.parameters["limit"])
        client.close()
    }

    @Test fun detailRetainsUnknownFactsDiffsAndCompleteRelationChoices() = runTest {
        var path = ""
        val client = client(DETAIL) { path = it.url.encodedPath }
        val detail = client.readCashImportItem("ip_one", "item:1")
        assertTrue(path.endsWith("/items/item%3A1"))
        assertNull(detail.members.single().transactionId)
        assertEquals(JsonNull, detail.members.single().fields["amount"])
        assertEquals(listOf("note", "record_type"), detail.members.single().editableFields)
        assertEquals(JsonNull, detail.fieldDiffs.single().effective)
        assertEquals(2, detail.relationChoices.single().memberRefs.size)
        assertEquals("open_home_item", detail.issues.single().afterImportAction)
        client.close()
    }

    @Test fun commitUsesOnlyFrozenRevisionAndOriginalKey() = runTest {
        val calls = mutableListOf<HttpRequestData>()
        val client = client(RECEIPT) { calls += it }
        val receipt = client.commitCashImport("ip_one", 4, "confirm-original")
        assertEquals("confirm-original", calls.single().headers["Idempotency-Key"])
        assertEquals(buildJsonObject { put("expected_session_revision", 4) }, json(calls.single()))
        assertEquals("partial", receipt.status)
        assertTrue(receipt.committed)
        assertEquals(2L, receipt.pendingItemCount)
        assertEquals(1L, receipt.summary["skipped"])
        client.close()
    }

    @Test fun recoveryKeepsAllFourResolveOutcomesDistinct() = runTest {
        for (status in listOf("pending", "retry_original", "not_committed", "committed")) {
            var body: JsonObject? = null
            val client = client("""{"status":"$status","receipt":${if (status == "committed") RECEIPT else "null"}}""") { body = json(it) }
            val result = client.resolveCashMutation("ip_one", "original-key")
            assertEquals(status, result.status)
            assertEquals("original-key", body!!["idempotency_key"]!!.jsonPrimitive.content)
            assertEquals(status == "committed", result.receipt != null)
            client.close()
        }
    }

    @Test fun receiptQueryPreservesLegacyKeyAndStringResultCursor() = runTest {
        var call: HttpRequestData? = null
        val client = client(RECEIPT) { call = it }
        client.readCashMutationReceiptByKey(" 旧键/空 格?& ")
        assertEquals(" 旧键/空 格?& ", call!!.url.parameters["key"])
        client.readCashMutationReceipt("receipt1")
        assertEquals("/api/v1/cash-mutations/receipts/receipt1", call!!.url.encodedPath)
        client.close()
        val pages = client(RESULT_PAGE) { call = it }
        val result = pages.readCashMutationResults("receipt1", CashMutationResultKind.RELATION, "9007199254740993", 20)
        assertEquals("9007199254740993", call!!.url.parameters["after"])
        assertTrue(call!!.url.encodedPath.endsWith("/relations"))
        assertEquals("9007199254740994", result.nextAfter)
        assertEquals("t1", result.projectionRefs.single().transactionId)
        assertEquals("pairing_failed", result.items.single().code)
        pages.close()
    }

    @Test fun retryRequestKeepsSubsetAndOwnKey() = runTest {
        var call: HttpRequestData? = null
        val client = client(SESSION) { call = it }
        assertEquals("s_retry", client.retryCashImport("receipt1", "retry-own-key", listOf("f1:r3")).sessionId)
        assertEquals("retry-own-key", call!!.headers["Idempotency-Key"])
        assertEquals(JsonArray(listOf(JsonPrimitive("f1:r3"))), json(call!!)["row_refs"])
        client.retryCashImport("receipt1", "retry-all-key")
        assertEquals(JsonNull, json(call!!)["row_refs"])
        client.close()
    }

    @Test fun safeErrorsRetainOnlyKnownCommitState() = runTest {
        for (state in listOf("unknown", "not_committed", "invalid", "123", "null")) {
            val primitive = if (state in listOf("123", "null")) state else "\"$state\""
            val client = FinanceApiClient({ "https://api.example.test" }, PlanTokenStore(), MockEngine {
                respond("""{"error":{"code":"commit_state_unknown","message":"private SQL /private/path"},"commit_state":$primitive,"receipt_id":"receipt1"}""", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType,"application/json"))
            })
            val failure = assertFailsWith<ApiFailure> { client.commitCashImport("ip_one", 4, "original") }
            assertEquals(state.takeIf { it in listOf("unknown", "not_committed") }, failure.commitState)
            assertEquals("receipt1", failure.receiptId)
            assertEquals("commit_state_unknown", failure.message)
            client.close()
        }
    }

    @Test fun accountListPreservesOpaqueIdAndCurrentReadProvidesRevision() = runTest {
        val calls = mutableListOf<HttpRequestData>()
        val list = client("""{"items":[{"id":9007199254740993,"name":"停用账户","type":"cash","active":false,"currencies":["CNY"]}]}""") { calls += it }
        val account = list.listCashImportAccounts().single()
        assertEquals("9007199254740993", account.id)
        assertFalse(account.active)
        list.close()
        val client = client(ACCOUNT) { calls += it }
        val current = client.readImportAccount(account.id)
        assertEquals(7L, current.accountRevision)
        assertEquals(false, current.capabilities["can_delete"])
        assertEquals("/api/v1/accounts/by-id/9007199254740993", calls.last().url.encodedPath)
        client.close()
    }

    @Test fun independentAccountCommandsHaveCasAndSeparateKeys() = runTest {
        val calls = mutableListOf<HttpRequestData>()
        val client = client(ACCOUNT) { calls += it }
        client.createImportAccount("账户", "cash", listOf("CNY"), "create-own")
        client.updateImportAccount("account1", 7, buildJsonObject { put("active", false) }, "update-own")
        client.deleteImportAccount("account1", 8, "delete-own")
        assertEquals(listOf(HttpMethod.Post, HttpMethod.Patch, HttpMethod.Delete), calls.map { it.method })
        assertEquals(listOf("create-own", "update-own", "delete-own"), calls.map { it.headers["Idempotency-Key"] })
        assertEquals(7L, json(calls[1])["expected_revision"]!!.jsonPrimitive.long)
        assertEquals(8L, json(calls[2])["expected_revision"]!!.jsonPrimitive.long)
        assertEquals(setOf("expected_revision"), json(calls[2]).keys)
        client.close()
    }

    @Test fun invalidLocalShapeNeverSendsRequest() = runTest {
        var calls = 0
        val client = client(PLAN) { calls++ }
        assertFailsWith<ApiFailure> { client.commitCashImport("ip_one", 0, "valid") }
        assertFailsWith<ApiFailure> { client.commitCashImport("ip_one", 4, "bad key") }
        assertFailsWith<ApiFailure> { client.retryCashImport("receipt1", "valid", emptyList()) }
        assertFailsWith<ApiFailure> { client.readCashImportItems("ip_one", limit = 201) }
        assertFailsWith<ApiFailure> { client.readCashImportItems("ip_one", confirmationStatus = "invalid") }
        assertFailsWith<ApiFailure> { client.updateImportAccount("a", 7, buildJsonObject { put("type", "loan") }, "valid") }
        assertEquals(0, calls)
        client.close()
    }

    @Test fun legacyReceiptDoesNotInventStatusOrCurrentProjectionFacts() = runTest {
        val client = client("""{"receipt_id":"old1","plan_id":null,"operation_kind":"legacy_import","committed":true,"created_at":"2026-01-01T00:00:00Z","summary":{"new_rows":2},"legacy_by_account":{"旧账户":2}}""") {}
        val receipt = client.readCashMutationReceipt("old1")
        assertNull(receipt.status)
        assertNull(receipt.pendingItemCount)
        assertNull(receipt.retryContextStatus)
        assertNull(receipt.projectionVersion)
        assertEquals(2L, receipt.legacyByAccount["旧账户"])
        client.close()
    }

    @Test fun networkFailureNeverRetriesOrChangesTheOriginalCommit() = runTest {
        var calls = 0
        val client = FinanceApiClient({ "https://api.example.test" }, PlanTokenStore(), MockEngine { call ->
            calls++
            assertEquals("original-key", call.headers["Idempotency-Key"])
            assertEquals(4L, json(call)["expected_session_revision"]!!.jsonPrimitive.long)
            throw IllegalStateException("synthetic network loss")
        })
        assertFailsWith<IllegalStateException> { client.commitCashImport("ip_one", 4, "original-key") }
        assertEquals(1, calls)
        client.close()
    }

    @Test fun planReadAndMalformedAccountIdUseStrictPublicShapes() = runTest {
        var call: HttpRequestData? = null
        val client = client(PLAN) { call = it }
        assertEquals("ip_one", client.readCashImportPlan("ip_one").planId)
        assertEquals("/api/v1/cash-import/plans/ip_one", call!!.url.encodedPath)
        client.close()
        for (id in listOf("null", "true", "1.5", "1e2", "[]")) {
            val malformed = client("""{"items":[{"id":$id,"name":"账户","type":"cash","active":true}]}""") {}
            assertFailsWith<ApiFailure> { malformed.listCashImportAccounts() }
            malformed.close()
        }
    }

    @Test fun oldReceiptKeyCountsUnicodeCharactersAndNeverReplacesInvalidUnicode() = runTest {
        val calls = mutableListOf<HttpRequestData>()
        val client = client(RECEIPT) { calls += it }
        for (count in listOf(128, 255)) {
            val key = "😀".repeat(count)
            client.readCashMutationReceiptByKey(key)
            assertEquals(key, calls.last().url.parameters["key"])
        }
        assertEquals(2, calls.size)
        for (key in listOf("😀".repeat(256), "bad\uD800", "\uDC00bad", "\uD800x\uDC00")) {
            assertFailsWith<ApiFailure> { client.readCashMutationReceiptByKey(key) }
        }
        assertEquals(2, calls.size)
        client.close()
    }

    @Test fun accountCommandResponsesAndReceiptWorkWithoutReadOnlyCapabilities() = runTest {
        val account = JsonObject(apiJson.parseToJsonElement(ACCOUNT).jsonObject - "capabilities")
        val client = FinanceApiClient({ "https://api.example.test" }, PlanTokenStore(), MockEngine { call ->
            val body = JsonObject(account + mapOf("receipt_id" to JsonPrimitive("account-result"), "committed" to JsonPrimitive(true),
                "deleted" to JsonPrimitive(call.method == HttpMethod.Delete)))
            respond(body.toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val created = client.createImportAccount("账户", "cash", listOf("CNY"), "create-own")
        assertEquals("account-result", created.receiptId)
        assertEquals(true, created.committed)
        assertTrue(created.capabilities.isEmpty())
        assertTrue(client.deleteImportAccount(created.accountId, created.accountRevision, "delete-own").deleted)
        client.close()
        val receiptJson = buildJsonObject {
            put("receipt_id", "account-result"); put("plan_id", JsonNull); put("operation_kind", "account_create")
            put("committed", true); put("created_at", "2026-01-01T00:00:00Z")
            put("account_id", created.accountId); put("account_revision", 7); put("account", account)
        }
        val receipts = client(receiptJson.toString()) {}
        val receipt = receipts.readCashMutationReceipt("account-result")
        assertEquals(created.accountId, receipt.account?.accountId)
        assertEquals(7L, receipt.accountRevision)
        assertNull(receipt.planId)
        assertNull(receipt.status)
        receipts.close()
    }

    private fun client(response: String, inspect: suspend (HttpRequestData) -> Unit) = FinanceApiClient({ "https://api.example.test" }, PlanTokenStore(), MockEngine {
        inspect(it)
        respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    })
    private suspend fun json(call: HttpRequestData) = apiJson.parseToJsonElement(call.body.toByteArray().decodeToString()).jsonObject
}
private class PlanTokenStore : TokenStore {
    override suspend fun get(): String = "synthetic-token"
    override suspend fun set(value: String) = Unit
    override suspend fun clear() = Unit
}
private const val PLAN = """{"plan_id":"ip_one","session_id":"s1","session_revision":4,"expires_at":"2099-01-01T00:00:00Z","summary":{"created_posted":1},"item_counts":{"all":1,"confirmed":1,"pending":0},"can_commit":true,"items":[{"preview_item_id":"item:1","confirmation_status":"confirmed","anticipated_state":"posted","display":{"occurred_at":"2026-01-01T01:00:00Z","amount":"0.00","currency":"CNY","counterparty":null,"note":null,"accounts":["账户"]},"statistics_eligible":true,"member_refs":[{"row_ref":"f1:r1","transaction_id":null}],"attention_reasons":[],"detail_available":true}],"file_results":[],"pages":{"items":null,"processing_details":null},"invalidated_choices":[]}"""
private const val DETAIL = """{"preview_item_id":"item:1","members":[{"row_ref":"f1:r1","transaction_id":null,"planned_action":"save_draft","fields":{"amount":null,"note":"来源备注"},"editable_fields":["note","record_type"],"allowed_actions":["apply","defer"],"row_revision":null}],"field_diffs":[{"row_ref":"f1:r1","transaction_id":null,"field":"note","current":"人工备注","incoming":"来源备注","effective":null,"resolution":"protected"}],"issues":[{"code":"account_required","message":"请补充账户","field_path":"account_id","blocking_scope":"row","next_action":"edit","after_import_action":"open_home_item"}],"relation_choices":[{"suggestion_id":"sg_group","kind":"refund_offset","subtype":null,"action":"defer","candidate_id":null,"candidates":[],"member_refs":[{"row_ref":"f1:r1"},{"transaction_id":"t2"}],"allowed_actions":["accept","defer","ignore"],"code":null}],"relation_effects":[]}"""
private const val RECEIPT = """{"receipt_id":"receipt1","plan_id":"ip_one","operation_kind":"import","committed":true,"created_at":"2026-01-01T00:00:00Z","status":"partial","summary":{"created_posted":1,"skipped":1},"needs_attention_count":2,"pending_item_count":2,"projection_version":7,"retry_context_status":"available","retry_context_expires_at":"2099-01-01T00:00:00Z"}"""
private const val RESULT_PAGE = """{"items":[{"result_id":"9007199254740993","result_kind":"relation","item_ref":"sg_group","outcome":"failed","code":"pairing_failed","message":"配对未完成","transaction_id":"t1"}],"next_after":"9007199254740994","projection_refs":[{"transaction_id":"t1","projection_id":"p1"}],"projection_version":7}"""
private const val ACCOUNT = """{"account_id":"9007199254740993","name":"账户","type":"cash","active":true,"currencies":["CNY"],"account_revision":7,"deleted":false,"capabilities":{"can_delete":false}}"""
private const val SESSION = """{"session_id":"s_retry","session_revision":1,"expires_at":"2099-01-01T00:00:00Z","state":"open","files":[],"source_accounts":[],"last_preview_input":null,"last_plan_id":null,"receipt_id":null,"can_preview":true}"""
