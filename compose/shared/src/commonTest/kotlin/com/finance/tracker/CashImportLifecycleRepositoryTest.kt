package com.finance.tracker

import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class CashImportLifecycleRepositoryTest {
    @Test fun restoredContextPreservesExplicitEmptySelectionAndManualNullPatch() = runTest {
        val api = api(CONTEXT)
        val context = KtorCashImportLifecycleRepository(api).context("s1")
        val source = context.sourceAccounts.single()
        assertEquals("9007199254740993", source.suggestion?.accountId)
        assertNull(assertNotNull(source.selection).accountId)
        val choices = assertNotNull(context.lastChoices)
        assertNull(choices.accountSelections.single().target.accountId)
        assertEquals(ImportRowDecision.APPLY, choices.rows.single().action)
        assertEquals(StructuredNull, choices.rows.single().patch.fields["note"])
        assertEquals(ImportPairDecision.DEFER, choices.relations.single().action)
        api.close()
    }

    @Test fun planKeepsUnknownFactsMoneyTextAndCursorSeparateFromNavigation() = runTest {
        val api = api(PLAN)
        val plan = KtorCashImportLifecycleRepository(api).plan("ip_one")
        assertNull(plan.items.single().display.occurredAt)
        assertEquals("12345678901234567890.0100", plan.items.single().display.amount)
        assertFalse(plan.items.single().statisticsEligible)
        assertEquals("next/+?&", plan.nextCursor)
        assertEquals("draft", plan.items.single().anticipatedState)
        api.close()
    }

    @Test fun previewMappingSendsCompleteChoicesAndNulls() = runTest {
        var sent: JsonObject? = null
        val api = FinanceApiClient({ "https://api.example.test" }, LifecycleTokenStore(), MockEngine {
            sent = Json.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject
            respond(PLAN, headers = headersOf(HttpHeaders.ContentType,"application/json"))
        })
        val choices = ImportChoiceSet(
            listOf(ImportAccountSelection("src1", ImportAccountTarget(null, null))),
            listOf(ImportRecordChoice("f1:r1", ImportRowDecision.APPLY, StructuredObject(mapOf("note" to StructuredNull)))),
            listOf(ImportPairChoice("sg_group", ImportPairDecision.DEFER)))
        KtorCashImportLifecycleRepository(api).preview("s1", 3, null, choices)
        val body = assertNotNull(sent)
        assertEquals(JsonNull, body["previous_plan_id"])
        assertEquals(JsonNull, body["account_selections"]!!.jsonArray.single().jsonObject["account_id"])
        assertEquals(JsonNull, body["row_choices"]!!.jsonArray.single().jsonObject["patch"]!!.jsonObject["note"])
        assertEquals("defer", body["relation_choices"]!!.jsonArray.single().jsonObject["action"]!!.jsonPrimitive.content)
        api.close()
    }

    @Test fun unknownCommitFailureKeepsRecoveryControlWithoutPrivateCause() = runTest {
        val api = FinanceApiClient({ "https://api.example.test" }, LifecycleTokenStore(), MockEngine {
            respond("""{"error":{"code":"commit_state_unknown","message":"private SQL /private/file"},"receipt_id":"receipt1","commit_state":"unknown"}""", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType,"application/json"))
        })
        val failure = assertFailsWith<DomainFailure> { KtorCashImportLifecycleRepository(api).confirm("ip_one", 4, "original") }
        assertEquals("receipt1", failure.receiptId)
        assertEquals("unknown", failure.commitState)
        assertEquals("commit_state_unknown", failure.message)
        assertNull(failure.cause)
        api.close()
    }

    @Test fun cancellationPropagatesInsteadOfBecomingRecoverableError() = runTest {
        val api = FinanceApiClient({ "https://api.example.test" }, LifecycleTokenStore(), MockEngine { throw CancellationException("cancelled") })
        assertFailsWith<CancellationException> { KtorCashImportLifecycleRepository(api).context("s1") }
        api.close()
    }

    @Test fun malformedRestoredChoicesNeverBecomeSilentEmptyDecisions() = runTest {
        val api = api(CONTEXT.replace("\"action\":\"apply\"", "\"action\":\"invalid\""))
        assertFailsWith<DomainFailure> { KtorCashImportLifecycleRepository(api).context("s1") }
        api.close()
    }

    @Test fun memberFieldsAndDifferencesRemainStructuredAndExact() = runTest {
        val api = api(DETAIL)
        val detail = KtorCashImportLifecycleRepository(api).item("ip_one", "item1")
        val fields = detail.members.single().fields.fields
        assertEquals(StructuredText("12345678901234567890.0100"), fields["amount"])
        assertEquals(StructuredNull, fields["note"])
        assertEquals(StructuredNumber("9007199254740993"), fields["source_snapshot"]?.let { (it as StructuredObject).fields["number"] })
        assertEquals(StructuredNull, detail.fieldDiffs.single().effective)
        assertEquals("protected", detail.fieldDiffs.single().resolution)
        assertEquals("sg_joint", detail.relationChoices.single().suggestionId)
        assertEquals(listOf("f1:r1", "f1:r2"), detail.relationChoices.single().members.map { it.rowRef })
        assertEquals("candidate1", detail.relationChoices.single().candidates.single().candidateId)
        assertEquals("removed", detail.relationEffects.single().effect)
        api.close()
    }

    @Test fun receiptResultsAndResolveRetainCurrentReferencesWithoutGuessing() = runTest {
        val receiptApi = api(RECEIPT)
        val receipt = KtorCashImportLifecycleRepository(receiptApi).confirm("ip_one", 4, "original")
        assertEquals("partial", receipt.status)
        assertEquals(2L, receipt.pendingItemCount)
        assertEquals(1L, receipt.summary["skipped"])
        receiptApi.close()
        val resolveApi = api("""{"status":"committed","receipt":$RECEIPT}""")
        assertEquals(receipt, KtorCashImportLifecycleRepository(resolveApi).resolve("ip_one", "original").receipt)
        resolveApi.close()
        val resultApi = api("""{"items":[{"result_id":"r1","result_kind":"row","item_ref":"f1:r1","outcome":"skipped","code":"field_conflict","message":"字段已变化","transaction_id":"t1"}],"next_after":"9007199254740993","projection_refs":[{"transaction_id":"t1","projection_id":"p2"}],"projection_version":9}""")
        val page = KtorCashImportLifecycleRepository(resultApi).results("receipt1", ImportResultKind.ROW)
        assertEquals("9007199254740993", page.nextAfter)
        assertEquals("field_conflict", page.items.single().code)
        assertEquals(ImportProjectionRef("t1", "p2"), page.projectionRefs.single())
        assertEquals(9L, page.projectionVersion)
        resultApi.close()
    }

    @Test fun uploadPublishesContextAndKeepsLocalFileFailureSeparate() = runTest {
        val api = api(CONTEXT)
        val contexts = mutableListOf<ImportContext>()
        val files = listOf(ImportUpload("bad", LifecycleFile(true)), ImportUpload("good", LifecycleFile(false)))
        val result = KtorCashImportLifecycleRepository(api).upload(files, onContext = contexts::add)
        assertEquals(listOf("s1"), contexts.map { it.sessionId })
        assertEquals("s1", result.context?.sessionId)
        assertEquals(listOf(ImportLocalFileFailure("bad", "import_file_read_failed")), result.localFailures)
        assertNull(result.interruption)
        assertFalse(files.first().toString().contains("private"))
        api.close()
    }

    @Test fun accountCommandsPreserveStableIdRevisionAndExplicitFalsePatch() = runTest {
        var sent: JsonObject? = null
        val api = FinanceApiClient({ "https://api.example.test" }, LifecycleTokenStore(), MockEngine {
            sent = Json.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject
            respond("""{"account_id":"9007199254740993","name":"账户","type":"cash","active":false,"currencies":["CNY"],"account_revision":8,"deleted":false,"receipt_id":"ar1","committed":true}""", headers = headersOf(HttpHeaders.ContentType,"application/json"))
        })
        val account = KtorCashImportLifecycleRepository(api).updateAccount("9007199254740993", 7,
            StructuredObject(mapOf("active" to StructuredBoolean(false))), "account-own-key")
        assertEquals("9007199254740993", account.accountId)
        assertEquals(8L, account.revision)
        assertFalse(account.active)
        assertEquals("ar1", account.receiptId)
        assertEquals(JsonPrimitive(false), sent!!["patch"]!!.jsonObject["active"])
        api.close()
    }

    @Test fun uploadInterruptionRetainsLatestPublishedContextAndRemainingSlots() = runTest {
        var count = 0
        val api = FinanceApiClient({ "https://api.example.test" }, LifecycleTokenStore(), MockEngine {
            count++
            if (count == 3) respond("""{"error":{"code":"import_unavailable","message":"暂时无法处理"},"receipt_id":"receipt1"}""", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType,"application/json"))
            else respond(CONTEXT.replace("\"session_revision\":3", "\"session_revision\":${count + 2}"), headers = headersOf(HttpHeaders.ContentType,"application/json"))
        })
        val revisions = mutableListOf<Long?>()
        val result = KtorCashImportLifecycleRepository(api).upload((1..4).map { ImportUpload("slot$it", LifecycleFile(false)) },
            onContext = { revisions += it.revision })
        assertEquals(listOf<Long?>(3, 4), revisions)
        assertEquals(4L, result.context?.revision)
        val interruption = assertNotNull(result.interruption)
        assertEquals("receipt1", interruption.receiptId)
        assertEquals("import_unavailable", interruption.code)
        assertEquals(503, interruption.status)
        assertEquals(listOf("slot3", "slot4"), interruption.unprocessedFileRefs)
        assertEquals(3, count)
        api.close()
    }

    private fun api(body: String) = FinanceApiClient({ "https://api.example.test" }, LifecycleTokenStore(), MockEngine {
        respond(body, headers = headersOf(HttpHeaders.ContentType,"application/json"))
    })
}
private class LifecycleFile(private val fail: Boolean) : BoundedFileSource {
    override val name = "synthetic.csv"
    override val mediaType: String? = null
    override val size: Long? = null
    override suspend fun read(): ByteArray = error("unbounded read forbidden")
    override suspend fun readBounded(maxBytes: Int): ByteArray {
        if (fail) error("private local I/O")
        return byteArrayOf(1, 2, 3)
    }
}
private class LifecycleTokenStore : TokenStore {
    override suspend fun get(): String = "synthetic-token"
    override suspend fun set(value: String) = Unit
    override suspend fun clear() = Unit
}
private const val CONTEXT = """{"session_id":"s1","session_revision":3,"expires_at":"2099-01-01T00:00:00Z","state":"open","files":[],"source_accounts":[{"source_account_ref":"src1","source_type":"wechat","display_name":"零钱","currencies":["CNY"],"row_count":1,"suggestion":{"account_id":"9007199254740993","expected_account_revision":7,"basis":"confirmed_mapping"},"selection":{"account_id":null,"expected_account_revision":null}}],"last_preview_input":{"account_selections":[{"source_account_ref":"src1","account_id":null,"expected_account_revision":null}],"row_choices":[{"row_ref":"f1:r1","action":"apply","patch":{"note":null}}],"relation_choices":[{"suggestion_id":"sg_group","action":"defer","candidate_id":null}]},"last_plan_id":null,"can_preview":true,"receipt_id":null}"""
private const val PLAN = """{"plan_id":"ip_one","session_id":"s1","session_revision":4,"expires_at":"2099-01-01T00:00:00Z","summary":{"saved_draft":1},"item_counts":{"pending":1,"confirmed":0},"can_commit":true,"items":[{"preview_item_id":"item1","confirmation_status":"pending","anticipated_state":"draft","display":{"occurred_at":null,"amount":"12345678901234567890.0100","currency":"CNY","counterparty":null,"note":null,"accounts":[]},"statistics_eligible":false,"member_refs":[{"row_ref":"f1:r1"}],"attention_reasons":[],"detail_available":true}],"file_results":[],"pages":{"items":"next/+?&","processing_details":null},"invalidated_choices":[]}"""
private const val DETAIL = """{"preview_item_id":"item1","members":[{"row_ref":"f1:r1","planned_action":"save_draft","fields":{"amount":"12345678901234567890.0100","note":null,"source_snapshot":{"number":9007199254740993}},"editable_fields":["note","record_type"],"allowed_actions":["apply","defer"]}],"field_diffs":[{"row_ref":"f1:r1","field":"note","current":"人工","incoming":"来源","effective":null,"resolution":"protected"}],"issues":[],"relation_choices":[{"suggestion_id":"sg_joint","action":"defer","candidates":[{"candidate_id":"candidate1","member_refs":[{"transaction_id":"t2"}]}],"member_refs":[{"row_ref":"f1:r1"},{"row_ref":"f1:r2"}],"allowed_actions":["accept","defer","ignore"]}],"relation_effects":[{"relation_id":"rel1","kind":"refund_offset","effect":"removed","member_refs":[{"transaction_id":"t1"}]}]}"""
private const val RECEIPT = """{"receipt_id":"receipt1","plan_id":"ip_one","operation_kind":"import","committed":true,"created_at":"2026-01-01T00:00:00Z","status":"partial","summary":{"created_posted":1,"skipped":1},"needs_attention_count":2,"pending_item_count":2,"projection_version":9,"retry_context_status":"available","retry_context_expires_at":"2099-01-01T00:00:00Z"}"""
