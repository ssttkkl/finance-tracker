package com.finance.tracker

import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class CashImportLifecycleRepositoryTest {
    @Test fun restoredContextPreservesExplicitEmptySelectionAndManualNullPatch() {
        val context = apiJson.decodeFromString<CashImportSessionDto>(CONTEXT).toDomain()
        val source = context.sourceAccounts.single()
        assertEquals("9007199254740993", source.suggestion?.accountId)
        assertNull(assertNotNull(source.selection).accountId)
        val choices = assertNotNull(context.lastChoices)
        assertNull(choices.accountSelections.single().target.accountId)
        assertEquals(ImportRowDecision.APPLY, choices.rows.single().action)
        assertEquals(StructuredNull, choices.rows.single().patch.fields["note"])
        assertEquals(ImportPairDecision.DEFER, choices.relations.single().action)
        assertEquals("ie_whole", choices.entries.single().editRef)
        assertEquals(StructuredNull, choices.entries.single().patch.fields["category_id"])
    }

    @Test fun planKeepsUnknownFactsMoneyTextAndCursorSeparateFromNavigation() {
        val plan = apiJson.decodeFromString<CashImportPlanDto>(PLAN).toDomain()
        assertNull(plan.items.single().display.occurredAt)
        assertEquals("12345678901234567890.0100", plan.items.single().display.amount)
        assertFalse(plan.items.single().statisticsEligible)
        assertEquals("next/+?&", plan.nextCursor)
        assertEquals("draft", plan.items.single().anticipatedState)
    }

    @Test fun previewMappingEncodesCompleteChoicesAndNulls() {
        val choices = ImportChoiceSet(
            listOf(ImportAccountSelection("src1", ImportAccountTarget(null, null))),
            listOf(ImportRecordChoice("f1:r1", ImportRowDecision.APPLY, StructuredObject(mapOf("note" to StructuredNull)))),
            listOf(ImportPairChoice("sg_group", ImportPairDecision.DEFER)),
            listOf(ImportEntryChoice("ie_whole", StructuredObject(mapOf("category_id" to StructuredNull, "note" to StructuredText(""))))))
        val body = Json { encodeDefaults = true }.encodeToJsonElement(choices.toDto(3, null)).jsonObject
        val entry = body["entry_choices"]!!.jsonArray.single().jsonObject
        assertEquals("ie_whole", entry["edit_ref"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, entry["patch"]!!.jsonObject["category_id"])
        assertEquals("", entry["patch"]!!.jsonObject["note"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["previous_plan_id"])
        assertEquals(JsonNull, body["account_selections"]!!.jsonArray.single().jsonObject["account_id"])
        assertEquals(JsonNull, body["row_choices"]!!.jsonArray.single().jsonObject["patch"]!!.jsonObject["note"])
        assertEquals("defer", body["relation_choices"]!!.jsonArray.single().jsonObject["action"]!!.jsonPrimitive.content)
    }

    @Test fun unknownReceiptFailureKeepsRecoveryControlWithoutPrivateCause() = runTest {
        val api = FinanceApiClient({ "https://api.example.test" }, LifecycleTokenStore(), MockEngine {
            respond("""{"error":{"code":"commit_state_unknown","message":"private SQL /private/file"},"receipt_id":"receipt1","commit_state":"unknown"}""", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType,"application/json"))
        })
        val failure = assertFailsWith<ApiFailure> { api.readCashMutationReceipt("receipt1") }.toDomainFailure()
        assertEquals("receipt1", failure.receiptId)
        assertEquals("unknown", failure.commitState)
        assertEquals("commit_state_unknown", failure.message)
        assertIs<ApiFailure>(failure.cause)
        api.close()
    }


    @Test fun malformedRestoredChoicesNeverBecomeSilentEmptyDecisions() {
        assertFailsWith<kotlinx.serialization.SerializationException> {
            apiJson.decodeFromString<CashImportSessionDto>(CONTEXT.replace("\"action\":\"apply\"", "\"action\":\"invalid\"")).toDomain()
        }
    }

    @Test fun memberFieldsAndDifferencesRemainStructuredAndExact() {
        val detail = apiJson.decodeFromString<CashPlanItemDetailDto>(DETAIL).toDomain()
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
    }

    @Test fun receiptResultsAndResolveRetainCurrentReferencesWithoutGuessing() {
        val receipt = apiJson.decodeFromString<CashMutationReceiptDto>(RECEIPT).toDomain()
        assertEquals("partial", receipt.status)
        assertEquals(2L, receipt.pendingItemCount)
        assertEquals(1L, receipt.summary["skipped"])
        assertEquals(receipt, CashMutationResolveDto("committed", apiJson.decodeFromString<CashMutationReceiptDto>(RECEIPT)).toDomain().receipt)
        val result = """{"items":[{"result_id":"r1","result_kind":"row","item_ref":"f1:r1","outcome":"skipped","code":"field_conflict","message":"字段已变化","transaction_id":"t1"}],"next_after":"9007199254740993","projection_refs":[{"transaction_id":"t1","projection_id":"p2"}],"projection_version":9}"""
        val page = apiJson.decodeFromString<CashMutationResultPageDto>(result).toDomain()
        assertEquals("9007199254740993", page.nextAfter)
        assertEquals("field_conflict", page.items.single().code)
        assertEquals(ImportProjectionRef("t1", "p2"), page.projectionRefs.single())
        assertEquals(9L, page.projectionVersion)
    }


    @Test fun accountCommandsPreserveStableIdRevisionAndExplicitFalsePatch() = runTest {
        var sent: JsonObject? = null
        val api = FinanceApiClient({ "https://api.example.test" }, LifecycleTokenStore(), MockEngine {
            sent = Json.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject
            respond("""{"account_id":"9007199254740993","name":"账户","type":"cash","active":false,"currencies":["CNY"],"account_revision":8,"deleted":false,"receipt_id":"ar1","committed":true}""", headers = headersOf(HttpHeaders.ContentType,"application/json"))
        })
        val account = api.updateImportAccount("9007199254740993", 7,
            buildJsonObject { put("active", false) }, "account-own-key").toDomain()
        assertEquals("9007199254740993", account.accountId)
        assertEquals(8L, account.revision)
        assertFalse(account.active)
        assertEquals("ar1", account.receiptId)
        assertEquals(JsonPrimitive(false), sent!!["patch"]!!.jsonObject["active"])
        api.close()
    }


    @Test fun uploadInputsKeepPrivateFileDetailsOutOfTheirStringRepresentation() {
        assertFalse(ImportUpload("bad", LifecycleFile(true)).toString().contains("private"))
    }

}
private class LifecycleFile(private val fail: Boolean) : BoundedFileSource {
    override val name = "synthetic.csv"
    override val mediaType: String? = null
    override val size: Long? = null
    override suspend fun read(): ByteArray = error("unbounded read forbidden")
    override suspend fun readBounded(maxBytes: Int): ByteArray {
        if (fail) throw io.ktor.utils.io.errors.IOException("private local I/O")
        return byteArrayOf(1, 2, 3)
    }
}
private class LifecycleTokenStore : TokenStore {
    override suspend fun get(): String = "synthetic-token"
    override suspend fun set(value: String) = Unit
    override suspend fun clear() = Unit
}
private const val CONTEXT = """{"session_id":"s1","session_revision":3,"expires_at":"2099-01-01T00:00:00Z","state":"open","files":[],"source_accounts":[{"source_account_ref":"src1","source_type":"wechat","display_name":"零钱","currencies":["CNY"],"row_count":1,"suggestion":{"account_id":"9007199254740993","expected_account_revision":7,"basis":"confirmed_mapping"},"selection":{"account_id":null,"expected_account_revision":null}}],"last_preview_input":{"account_selections":[{"source_account_ref":"src1","account_id":null,"expected_account_revision":null}],"row_choices":[{"row_ref":"f1:r1","action":"apply","patch":{"note":null}}],"relation_choices":[{"suggestion_id":"sg_group","action":"defer","candidate_id":null}],"entry_choices":[{"edit_ref":"ie_whole","patch":{"category_id":null}}]},"last_plan_id":null,"can_preview":true,"receipt_id":null}"""
private const val PLAN = """{"plan_id":"ip_one","session_id":"s1","session_revision":4,"expires_at":"2099-01-01T00:00:00Z","summary":{"saved_draft":1},"item_counts":{"pending":1,"confirmed":0},"can_commit":true,"items":[{"preview_item_id":"item1","confirmation_status":"pending","anticipated_state":"draft","display":{"occurred_at":null,"amount":"12345678901234567890.0100","currency":"CNY","counterparty":null,"note":null,"accounts":[]},"statistics_eligible":false,"member_refs":[{"row_ref":"f1:r1"}],"attention_reasons":[],"detail_available":true}],"file_results":[],"pages":{"items":"next/+?&","processing_details":null},"invalidated_choices":[]}"""
private const val DETAIL = """{"preview_item_id":"item1","members":[{"row_ref":"f1:r1","planned_action":"save_draft","fields":{"amount":"12345678901234567890.0100","note":null,"source_snapshot":{"number":9007199254740993}},"editable_fields":["note","record_type"],"allowed_actions":["apply","defer"]}],"field_diffs":[{"row_ref":"f1:r1","field":"note","current":"人工","incoming":"来源","effective":null,"resolution":"protected"}],"issues":[],"relation_choices":[{"suggestion_id":"sg_joint","action":"defer","candidates":[{"candidate_id":"candidate1","member_refs":[{"transaction_id":"t2"}]}],"member_refs":[{"row_ref":"f1:r1"},{"row_ref":"f1:r2"}],"allowed_actions":["accept","defer","ignore"]}],"relation_effects":[{"relation_id":"rel1","kind":"refund_offset","effect":"removed","member_refs":[{"transaction_id":"t1"}]}]}"""
private const val RECEIPT = """{"receipt_id":"receipt1","plan_id":"ip_one","operation_kind":"import","committed":true,"created_at":"2026-01-01T00:00:00Z","status":"partial","summary":{"created_posted":1,"skipped":1},"needs_attention_count":2,"pending_item_count":2,"projection_version":9,"retry_context_status":"available","retry_context_expires_at":"2099-01-01T00:00:00Z"}"""
