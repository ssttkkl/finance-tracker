package com.finance.tracker.backend

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import com.finance.tracker.business.domain.buildLocalCashImportBusinessPlan
import com.finance.tracker.business.domain.freezeLocalCashImportPreviewPlan
import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LocalCashImportBusinessPlanParityTest(private val backend: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun backends() = listOf(arrayOf("sqlite"), arrayOf("postgresql"))
    }
    private val now = Instant.parse("2026-10-01T00:00:00Z")
    private fun payload(record: String, refund: Boolean) = mapOf("交易订单号" to record, "商品说明" to "原始商品",
        "交易对方" to "商户", "收/付款方式" to "支付宝余额", "收/支" to if (refund) "收入" else "支出",
        "交易状态" to if (refund) "退款成功" else "交易成功")
    private fun files(refundOnly: Boolean = false): List<ParsedImportFile> {
        fun row(index: Int, refund: Boolean): ParsedCashRow {
            val record = if (refund) "ORDER_1" else "ORDER"
            return ParsedCashRow(index, mapOf("amount" to if (refund) "3.00" else "-10.00", "currency" to "CNY",
                "occurred_at" to "2026-08-${if (refund) "16" else "15"}T02:00:00Z", "record_type" to if (refund) "refund" else "consumption",
                "record_subtype" to "not_applicable", "counterparty" to "商户", "note" to "edited-note", "source_payload" to payload(record, refund)),
                paymentMethod = "支付宝余额", providerId = record, sourceExplanation = mapOf("txn_id" to record, "payment_method" to "支付宝余额",
                    "platform_status" to if (refund) "退款成功" else "交易成功", "_alipay_direction" to if (refund) "收入" else "支出"))
        }
        val rows = if (refundOnly) listOf(row(1, true)) else listOf(row(1, false), row(2, true))
        return listOf(ParsedImportFile("f", (if (refundOnly) "r" else "f").repeat(64), "parsed", ParsedCashDocument("alipay", "csv", "p", rows,
            "owner", "支付宝", CashSourceCoverage(null, null, false, "", emptyMap(), "v"), rows.size, rows.size)))
    }
    private fun input(files: List<ParsedImportFile>, account: Long) = buildJsonObject {
        put("account_selections", sourceJson(CashImportFunding.scan(files).groups.map {
            mapOf("source_account_ref" to it.sourceAccountRef, "account_id" to account.toString(), "expected_account_revision" to 1L)
        }))
    }
    @Suppress("UNCHECKED_CAST")
    private fun nativeInput(input: JsonObject) = cashSourceValue(input) as Map<String, Any?>

    private fun compare(context: CashSqlContext, source: LedgerSnapshotDto, files: List<ParsedImportFile>, input: JsonObject,
        previousNative: Map<String, Any?>? = null, previousLocal: Map<String, Any?>? = null): Pair<Map<String, Any?>, Map<String, Any?>> {
        val expected = CashImportBusinessPlanBuilder(context).build(files, nativeInput(input), previousNative)
        val actual = buildLocalCashImportBusinessPlan(files, source, input, previousLocal, requireActive = context::requireActive) {
            kotlin.time.Instant.parse(now.toString())
        }
        assertEquals(sourceJson(expected), freezeLocalCashImportPreviewPlan(actual))
        return expected to actual
    }

    @Test fun completeNewFilePlanRelationChoiceEntryChoiceAndDraftMatchWithoutWrites() = withCashWriteSetTestWorkspace(backend) { db, workspace, account ->
        val files = files(); val input = input(files, account)
        val before = detailDatabaseState(db)
        db.read { connection ->
            val context = CashSqlContext(connection, workspace, db.sqlite, now = { now })
            val source = LedgerSnapshotDto(workspace, "0", CASH_RULES_VERSION, LedgerSnapshotRepository(connection, workspace, db.sqlite).facts())
            val (native, local) = compare(context, source, files, input)
            val group = (native["relation_groups"] as List<*>).single() as Map<*, *>
            assertEquals("refund_offset", ((group["edges"] as List<*>).single() as Map<*, *>)["kind"])
            val choice = mapOf("suggestion_id" to group["suggestion_id"], "action" to "accept", "common_fields" to mapOf("note" to "checked-refund"))
            val relationInput = JsonObject(input + ("relation_choices" to sourceJson(listOf(choice))))
            val (selectedNative, selectedLocal) = compare(context, source, files, relationInput, native, local)
            val edit = (selectedNative["entry_targets"] as Map<*, *>).keys.first() as String
            compare(context, source, files, JsonObject(relationInput + ("entry_choices" to sourceJson(listOf(
                mapOf("edit_ref" to edit, "patch" to mapOf("note" to "checked-entry")))))), selectedNative, selectedLocal)
            compare(context, source, files, JsonObject(input + ("row_choices" to sourceJson(listOf(mapOf("row_ref" to "f:r2", "action" to "defer"))))))
            assertEquals(sourceJson(native), freezeLocalCashImportPreviewPlan(local))
        }
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun storedCandidatesEntryDependenciesAndAliasesMatchWithoutWrites() = withCashWriteSetTestWorkspace(backend) { db, workspace, account ->
        val expense = db.write { connection ->
            val context = CashSqlContext(connection, workspace, db.sqlite, now = { now })
            val (id, _) = context.insertAtomicCash(account, Instant.parse("2026-08-15T02:00:00Z"), CashAmount.parse("-10.00"), "CNY",
                "consumption", "not_applicable", "import", sourceType = "alipay", counterparty = "商户", note = "stored-note")
            val tx = com.finance.tracker.backend.persistence.generated.tables.references.CASH_TRANSACTIONS
            context.sql.update(tx).set(tx.RECORD_ID, "stored-record").set(tx.SOURCE_PAYLOAD, sourceJson(payload("ORDER", false)))
                .set(tx.RELATION_METADATA, sourceJson(mapOf("txn_id" to "ORDER", "payment_method" to "支付宝余额"))).where(tx.ID.eq(id)).execute()
            val occurrence = com.finance.tracker.business.domain.CashSourceRows.sourceBatchRows(files()).rows.values.first()
            for (key in occurrence.bindingSpecs.filter { it.scopeKind == "account" })
                deletionInsert(connection, db, "cash_source_bindings", mapOf("workspace_id" to workspace, "transaction_id" to id,
                    "source_type" to key.sourceType, "source_scope_kind" to key.scopeKind, "source_scope_key" to key.scopeKey,
                    "identity_kind" to key.identityKind, "identity_key" to key.identityKey, "identity_strength" to key.identityStrength,
                    "strategy_version" to "p", "coverage" to JsonObject(emptyMap()), "revision" to 1L, "created_at" to now, "updated_at" to now))
            deletionEntry(connection, db, workspace, "entry-existing", id, null)
            deletionInsert(connection, db, "cash_entry_members", mapOf("workspace_id" to workspace, "transaction_id" to id,
                "entry_id" to "entry-existing", "standalone_values" to sourceJson(mapOf("category_id" to null, "note" to "stored-note",
                    "counterparty" to "商户", "counterparty_account" to "", "counterparty_account_attrs" to emptyList<String>())),
                "standalone_manual_fields" to JsonArray(emptyList())))
            for ((type, value) in listOf("card_tail" to "1234", "account_identifier" to "622200001234"))
                deletionInsert(connection, db, "account_aliases", mapOf("workspace_id" to workspace, "alias_type" to type, "alias_value" to value,
                    "account_id" to account, "created_at" to now, "updated_at" to now))
            id
        }
        val before = detailDatabaseState(db)
        db.read { connection ->
            val context = CashSqlContext(connection, workspace, db.sqlite, now = { now })
            val source = LedgerSnapshotDto(workspace, "0", CASH_RULES_VERSION, LedgerSnapshotRepository(connection, workspace, db.sqlite).facts())
            val files = files(refundOnly = true)
            val (native, _) = compare(context, source, files, input(files, account))
            val group = (native["relation_groups"] as List<*>).single() as Map<*, *>
            val edge = (group["edges"] as List<*>).single() as Map<*, *>
            assertEquals(expense.toString(), edge["primary_transaction_id"])
            assertEquals("refund_offset", edge["kind"])
            val dependencies = native["dependencies"] as Map<*, *>
            assertEquals(listOf(expense), dependencies["candidate_ids"])
            val targets = (native["entry_targets"] as Map<*, *>).values.map { it as Map<*, *> }
            val target = targets.single { expense.toString() in (it["parent_ids"] as List<*>) }
            val entries = (target["entries"] as List<*>).map { it as List<*> }
            assertEquals(listOf(expense, "entry-existing", 1L), entries.single().take(3))
            assertEquals(2, source.facts.accountAliases.size)
        }
        assertEquals(before, detailDatabaseState(db))
    }
}
