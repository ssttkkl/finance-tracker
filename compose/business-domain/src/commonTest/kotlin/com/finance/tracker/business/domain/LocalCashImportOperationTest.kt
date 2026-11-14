package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashImportOperationTest {
    private fun snapshot() = LedgerSnapshotDto("w", "9007199254740993", CASH_RULES_VERSION,
        LedgerFactsDto(listOf(LedgerAccountFactDto("10", "现金", "cash", true, JsonArray(listOf(JsonPrimitive("CNY"))), "9007199254740993")),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0"))
    private fun files(): List<ParsedImportFile> {
        val fields = mapOf<String, Any?>("amount" to "-12.50", "currency" to "CNY", "occurred_at" to "2026-08-15T02:00:00Z",
            "record_type" to "consumption", "record_subtype" to "not_applicable", "counterparty" to "商户", "note" to "",
            "source_payload" to mapOf("订单号" to "0001", "付款方式" to "支付宝余额"))
        return listOf(ParsedImportFile("f", "f".repeat(64), "parsed", ParsedCashDocument("alipay", "csv", "p",
            listOf(ParsedCashRow(1, fields, paymentMethod = "支付宝余额", providerId = "0001")), "owner", "支付宝",
            CashSourceCoverage(null, null, false, "", emptyMap(), "v"), 1, 1)))
    }
    @Test fun completeLocalCompositionPostsWithoutAPlatformRuleAdapter() {
        val files = files(); val snapshot = snapshot()
        val group = scanLocalCashFunding(files).groups.single().sourceAccountRef
        val selections = Json.parseToJsonElement("""[{"source_account_ref":"$group","account_id":"10","expected_account_revision":9007199254740993}]""")
        val result = prepareLocalCashImportOperations(files, snapshot, selections)
        assertEquals("create_posted", result.rows.single()["planned_action"])
        val operation = encodeLocalCashValue(result.operations.single()).jsonObject
        val values = operation.getValue("values").jsonObject
        assertEquals("-12.50", values.getValue("amount").jsonPrimitive.content)
        assertEquals("posted", values.getValue("state").jsonPrimitive.content)
        assertEquals(JsonPrimitive(0), values.getValue("components").jsonArray.single().jsonObject.getValue("ordinal"))
        assertTrue(values.getValue("record_id").jsonPrimitive.content.startsWith("provider-v1:"))
        assertEquals("9007199254740993", snapshot.ledgerSeq)
        assertEquals("-12.50", files.single().document!!.rows.single().fields!!["amount"])
        val deferred = prepareLocalCashImportOperations(files, snapshot, selections,
            Json.parseToJsonElement("""[{"row_ref":"f:r1","action":"defer"}]"""))
        assertEquals("create_draft", deferred.rows.single()["planned_action"])
        assertEquals("posting_incomplete", (deferred.operations.single().getValue("issues") as List<*>).single().let { (it as Map<*, *>)["code"] })
    }
    private fun selections(files: List<ParsedImportFile>) = encodeLocalCashValue(scanLocalCashFunding(files).groups.map {
        mapOf("source_account_ref" to it.sourceAccountRef, "account_id" to "10", "expected_account_revision" to JsonPrimitive(9007199254740993L))
    })
    private fun saved(files: List<ParsedImportFile>): LedgerSnapshotDto {
        val empty = JsonObject(emptyMap()); val time = "2026-08-15T02:00:00Z"
        val row = files.single().document!!.rows.single()
        val occurrence = CashSourceRows.sourceBatchRows(files).rows.values.single()
        val bindings = occurrence.bindingSpecs.mapIndexed { index, key -> LedgerSourceBindingFactDto("${index + 20}", "1", key.sourceType,
            key.scopeKind, key.scopeKey, key.identityKind, key.identityKey,
            if (key.scopeKind == "file_local") "file_local" else "provider", null, "p", empty, "1", time, time) }
        return snapshot().copy(facts = snapshot().facts.copy(
            cashTransactions = listOf(LedgerCashTransactionFactDto("1", "10", "posted", "import", null, "1", "atomic", "alipay", "record",
                encodeLocalCashValue(row.fields!!.getValue("source_payload")), empty, null, empty, time, "-12.50", "CNY", "商户", "", JsonArray(emptyList()),
                "", null, "consumption", "not_applicable", time, null, "", "")),
            cashTransactionComponents = listOf(LedgerCashComponentFactDto("11", "1", "10", "-12.50", "CNY", "0", "", "", empty, time)),
            cashSourceBindings = bindings))
    }
    private fun operation(files: List<ParsedImportFile>, source: LedgerSnapshotDto = snapshot(), action: String = "apply",
        patch: Map<String, Any?> = emptyMap()) = prepareLocalCashImportOperations(files, source, selections(files),
        encodeLocalCashValue(listOf(mapOf("row_ref" to "f:r1", "action" to action, "patch" to patch)))).operations.single()
    private fun used(operation: Map<String, Any?>) = (operation["used_account_selections"] as? List<*>).orEmpty()

    @Test fun sharedClientPublicationKeepsApplyDeferAndKeepBehavior() {
        val files = files(); val source = saved(files)
        for (action in listOf("apply", "defer", "keep_existing")) {
            val operation = operation(files, source, action)
            assertEquals("-12.50", (operation.getValue("values") as Map<*, *>)["amount"])
            assertEquals(if (action == "apply") 1 else 0, used(operation).size)
        }
        val deferredDraft = operation(files, action = "defer")
        assertEquals("create_draft", deferredDraft["planned_action"])
        assertEquals(1, used(deferredDraft).size)
    }

    @Test fun sharedClientPublicationKeepsOverridesCurrencyFilteringAndMergedGroups() {
        val files = files()
        for (patch in listOf(mapOf("account_id" to "10"), mapOf("cash_granularity" to "atomic")))
            assertTrue(used(operation(files, patch = patch)).isEmpty())
        val first = files.single(); val doc = first.document!!; val row = doc.rows.single()
        val unsupported = listOf(first.copy(document = doc.copy(rows = listOf(row.copy(fields = row.fields!! + ("currency" to "USD"))))))
        assertTrue(used(operation(unsupported)).isEmpty())
        val merged = listOf(first.copy(document = doc.copy(rows = listOf(row.copy(paymentMethod = "支付宝余额&余额宝")))))
        val operation = operation(merged)
        assertEquals("create_posted", operation["planned_action"])
        assertEquals(scanLocalCashFunding(merged).groups.map { it.sourceAccountRef }, used(operation).map { (it as Map<*, *>)["source_account_ref"] })
        assertEquals(1, ((operation.getValue("values") as Map<*, *>)["components"] as List<*>).size)
    }

}
