package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.time.Instant
import java.math.BigDecimal
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import kotlin.test.*

class CashImportOriginalOperationTest {
    private fun fixture(name: String = "base") = CashImportOriginalApiFixture(name, "FT_KOTLIN_IMPORT_ORIGINAL_OPERATION_CONTRACT_DIR")
    private fun selections(files: List<ParsedImportFile>, account: String = "101", revision: Int = 1) = CashImportFunding.scan(files).groups.map {
        mapOf("source_account_ref" to it.sourceAccountRef, "account_id" to account, "expected_account_revision" to revision)
    }
    private fun choice(patch: Map<String, Any?>, ref: String = "file_a:r1") =
        mapOf("row_ref" to ref, "action" to "apply", "patch" to patch)
    private fun prepare(f: CashImportOriginalApiFixture, files: List<ParsedImportFile>, selected: Boolean = true,
        choices: List<Any?> = emptyList(), ids: Map<String, String> = emptyMap(), wire: Boolean = true,
        account: String = "101", revision: Int = 1,
        transform: ((PreparedCashSourcePlan) -> PreparedCashSourcePlan)? = null) = f.database.read {
        val context = CashSqlContext(queryOnly(it), f.workspace, f.database.sqlite)
        CashImportOperationPlanner(context).prepare(files,
            accountSelections = if (selected) selections(files, account, revision) else emptyList(), rowChoices = choices,
            recordIds = ids, wireValues = wire,
            sourcePlan = transform?.invoke(CashImportSourcePlanner(context).prepare(files)))
    }
    private fun queryOnly(connection: Connection): Connection = Proxy.newProxyInstance(Connection::class.java.classLoader,
        arrayOf(Connection::class.java)) { _, method, args ->
        if (method.name == "prepareStatement") {
            val query = (args!![0] as String).trimStart()
            assertTrue(query.startsWith("select", ignoreCase = true), "Read-only planning must issue SELECT only")
        }
        check(method.name != "createStatement" && method.name != "prepareCall") { "Unobserved SQL execution path" }
        try { method.invoke(connection, *(args ?: emptyArray())) }
        catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
    } as Connection
    @Suppress("UNCHECKED_CAST")
    private fun values(operation: Map<String, Any?>) = operation.getValue("values") as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun issues(operation: Map<String, Any?>) = operation.getValue("issues") as List<Map<String, Any?>>
    private inline fun readOnly(f: CashImportOriginalApiFixture, block: () -> Unit) {
        val before = f.snapshot(); block(); assertEquals(before, f.snapshot())
    }
    private fun invalid(block: () -> Unit) {
        assertEquals("plan_input_invalid", assertFailsWith<CashImportPlanError>(block = block).code)
    }
    @Suppress("UNCHECKED_CAST")
    private fun objectValue(value: Any?) = value as Map<String, Any?>
    private fun stored(f: CashImportOriginalApiFixture) = f.database.read { connection ->
        connection.jooq(f.database.sqlite).select(CASH_TRANSACTIONS.COUNTERPARTY, CASH_TRANSACTIONS.RECORD_TYPE,
            CASH_TRANSACTIONS.SOURCE_PAYLOAD).from(CASH_TRANSACTIONS)
            .where(CASH_TRANSACTIONS.ID.eq(1003L).and(CASH_TRANSACTIONS.WORKSPACE_ID.eq(f.workspace))).fetchSingle()
    }

    @Test fun userSourcePayloadPatchRejectsAllThreeOriginalParametersWithoutPartialEdit() {
        for ((name, explicit) in listOf("bound" to null, "protected_old" to null, "bound" to "")) {
            val f = fixture(name); readOnly(f) {
                val patch = linkedMapOf<String, Any?>("source_payload" to mapOf("备注" to "伪造来源原文"))
                if (explicit != null) patch["note"] = explicit
                invalid { prepare(f, f.files("default"), selected = false, choices = listOf(choice(patch))) }
                assertNotEquals(sourceJson(patch["source_payload"]), stored(f)[CASH_TRANSACTIONS.SOURCE_PAYLOAD])
            }
        }
    }
    @Test fun identicalPostedSourcePayloadStillIsNotEditable() {
        val f = fixture("bound"); readOnly(f) {
            val files = f.files("default")
            invalid { prepare(f, files, selected = false, choices = listOf(choice(mapOf("source_payload" to files.single().document!!.rows.single().fields!!["source_payload"])))) }
        }
    }
    @Test fun sameBatchUsesNewestSnapshotForOneExistingUpdate() {
        val f = fixture("source_status"); readOnly(f) {
            val prepared = prepare(f, f.files("status_batch")); val operation = prepared.operations.single()
            assertEquals("update_posted", operation["planned_action"])
            assertEquals("交易成功", objectValue(values(operation)["source_payload"])["状态"])
            assertEquals(listOf("file_a:r1" to "update_posted", "file_b:r1" to "duplicate_in_batch"), prepared.rows.map { it["row_ref"] to it["planned_action"] })
        }
    }
    @Test fun crossBatchUpdateIgnoresOldAndNewFileModificationOrder() {
        val f = fixture("source_status_time"); readOnly(f) {
            val operation = prepare(f, f.files("status_older"), selected = false).operations.single()
            assertEquals("update_posted", operation["planned_action"])
            assertEquals("交易成功", objectValue(values(operation)["source_payload"])["状态"])
        }
    }
    @Test fun sourceUpdateKeepsManualNoteAndNeedsNoActiveMapping() {
        val f = fixture("protected_inactive"); readOnly(f) {
            val files = f.files("changed"); val operation = prepare(f, files, selected = false).operations.single()
            assertEquals("update_posted", operation["planned_action"]); assertEquals("1003", operation["transaction_id"])
            val value = values(operation)
            assertEquals("当前商户", value["counterparty"]); assertEquals("新来源备注", value["note"]); assertEquals("101", value["account_id"])
            assertEquals(files.single().document!!.rows.single().fields!!["source_payload"], value["source_payload"])
            assertTrue((operation["field_diffs"] as List<*>).map(::objectValue).any { it["field"] == "note" && it["resolution"] == "apply" })
        }
    }
    @Test fun fixedAmountConflictSkipsOnlyItsRowWithoutPartialMutableUpdate() {
        val f = fixture("bound"); readOnly(f) {
            val prepared = prepare(f, f.files("fixed_mixed"))
            assertEquals("skip", prepared.rows[0]["planned_action"]); assertEquals("fixed_field_conflict", prepared.rows[0]["code"])
            assertEquals("create_posted", prepared.rows[1]["planned_action"]); assertEquals(1, prepared.operations.size)
            assertEquals("合成商户", stored(f)[CASH_TRANSACTIONS.COUNTERPARTY])
        }
    }
    @Test fun explicitSameValueNoteIsManualDecisionOnDuplicateSource() {
        val f = fixture("bound"); readOnly(f) {
            val operation = prepare(f, f.files("default"), selected = false, choices = listOf(choice(mapOf("note" to "原始备注")))).operations.single()
            assertEquals("update_posted", operation["planned_action"])
            assertEquals(mapOf("note" to "原始备注"), objectValue(operation["entry_edit"])["patch"])
        }
    }
    @Test fun explicitNoteClearsProtectionAndFixedManualPatchRejects() {
        val f = fixture("protected"); readOnly(f) {
            val files = f.files("default")
            val operation = prepare(f, files, selected = false, choices = listOf(choice(mapOf("note" to "")))).operations.single()
            assertEquals(mapOf("note" to ""), objectValue(operation["entry_edit"])["patch"])
            assertEquals("原始备注", values(operation)["note"])
            invalid { prepare(f, files, selected = false, choices = listOf(choice(mapOf("amount" to "-100")))) }
        }
    }
    @Test fun duplicateKeepsBindingWorkWithoutClaimingUpdate() {
        val f = fixture("bound"); readOnly(f) {
            val prepared = prepare(f, f.files("alias"), selected = false); val operation = prepared.operations.single()
            assertEquals("duplicate", prepared.rows.single()["planned_action"]); assertEquals("duplicate", operation["planned_action"])
            assertEquals(true, objectValue((operation["source_updates"] as List<*>).first())["bind"])
        }
    }
    @Test fun deferKnownSourceUpdateKeepsPostedRecordAndPendingFields() {
        val f = fixture("bound"); readOnly(f) {
            val operation = prepare(f, f.files("defer"), selected = false,
                choices = listOf(mapOf("row_ref" to "file_b:r1", "action" to "defer"))).operations.single()
            assertEquals("1003", operation["transaction_id"]); assertEquals("posted", values(operation)["state"])
            assertEquals("合成商户", values(operation)["counterparty"])
            assertEquals("稍后处理的名称", objectValue(operation["pending_fields"])["counterparty"])
            assertTrue(issues(operation).isNotEmpty()); assertEquals("update_posted", operation["planned_action"])
        }
    }
    @Test fun differentSelectedAccountConflictsWithPostedRecord() {
        val f = fixture("bound"); readOnly(f) {
            val row = prepare(f, f.files("different"), account = "102").rows.single()
            assertEquals("skip", row["planned_action"]); assertEquals("fixed_field_conflict", row["code"])
        }
    }
    @Test fun keepExistingRequiresActuallyMatchedSavedRecord() {
        val choices = listOf(mapOf("row_ref" to "file_a:r1", "action" to "keep_existing"))
        val fresh = fixture(); readOnly(fresh) { invalid { prepare(fresh, fresh.files("default"), selected = false, choices = choices) } }
        val bound = fixture("bound"); readOnly(bound) {
            val prepared = prepare(bound, bound.files("default"), selected = false, choices = choices)
            assertEquals("duplicate", prepared.rows.single()["planned_action"])
            assertEquals("1003", prepared.operations.single()["transaction_id"])
        }
    }
    @Test fun sameFileAmbiguousDraftDoesNotFinalizeOnCompleteReupload() {
        val f = fixture("ambiguous"); readOnly(f) {
            val operation = prepare(f, f.files("default")).operations.single()
            assertEquals("draft", values(operation)["state"]); assertNotEquals("finalize_draft", operation["planned_action"])
            assertTrue(issues(operation).any { it["code"] == "source_identity_ambiguous" })
        }
    }
    @Test fun newDraftDoesNotSubstituteCurrencyOrGuessAccount() {
        val f = fixture("currency"); readOnly(f) {
            val operation = prepare(f, f.files("default"), revision = 2).operations.single()
            assertEquals("draft", values(operation)["state"]); assertEquals("CNY", values(operation)["currency"])
            assertTrue(issues(operation).any { it["code"] == "account_currency_missing" })
        }
    }
    @Test fun postedTypeEditRejectsWithoutChangingCurrentRecord() {
        val f = fixture("bound"); readOnly(f) {
            invalid { prepare(f, f.files("default"), selected = false,
                choices = listOf(choice(mapOf("record_type" to "transfer_out", "record_subtype" to "ordinary_transfer")))) }
            assertEquals("consumption", stored(f)[CASH_TRANSACTIONS.RECORD_TYPE])
        }
    }
    @Test fun unexpectedFirstRowPreparationErrorStopsBeforeNextOperation() {
        val f = fixture(); readOnly(f) {
            val reached = mutableListOf<String>()
            val failure = assertFailsWith<IllegalStateException> {
                prepare(f, f.files("two"), transform = { original ->
                    val rows = linkedMapOf<String, SourceOccurrence>()
                    for ((ref, occurrence) in original.rows.rows) {
                        val fields = occurrence.fields
                        rows[ref] = occurrence.copy(fields = object : Map<String, Any?> by fields {
                            override val entries: Set<Map.Entry<String, Any?>> get() {
                                if (Thread.currentThread().stackTrace.any { it.className == CashImportOperationPlanner::class.java.name && it.methodName == "operation" }) {
                                    reached += ref
                                    if (ref == "file_a:r1") error("SQL private source body")
                                }
                                return fields.entries
                            }
                        })
                    }
                    original.copy(rows = SourceBatchRows(rows, original.rows.documentEvidence, original.rows.documentCoverages, original.rows.fileMetadata))
                })
            }
            assertEquals("SQL private source body", failure.message); assertEquals(listOf("file_a:r1"), reached)
        }
    }
    @Test fun fixedConflictExplainsActualFieldInSafeRowResult() {
        val f = fixture("bound"); readOnly(f) {
            val issue = (prepare(f, f.files("bad_amount"), selected = false).rows.single()["issues"] as List<*>).single().let(::objectValue)
            assertEquals("amount", issue["field_path"]); assertTrue((issue["message"] as String).contains("金额"))
        }
    }
    @Test fun explicitFinancialCompletionRevalidatesAllOriginalAccountParameters() {
        for ((name, account, success) in listOf(Triple("base", "101", true), Triple("inactive", "101", false), Triple("base", "104", false))) {
            val f = fixture(name); readOnly(f) {
                val patch = mapOf("account_id" to account, "components" to listOf(mapOf("ordinal" to 0,
                    "account_id" to account, "amount" to "-12.5", "currency" to "CNY")))
                if (!success) invalid { prepare(f, f.files("default"), selected = false, choices = listOf(choice(patch))) }
                else {
                    val operation = prepare(f, f.files("default"), selected = false, choices = listOf(choice(patch))).operations.single()
                    assertEquals("create_posted", operation["planned_action"]); assertEquals(emptyList(), issues(operation))
                    assertEquals(listOf(mapOf("account_id" to "101", "exists" to true, "active" to true, "type" to "cash",
                        "currency_support" to mapOf("CNY" to true))), operation["expected_accounts"])
                }
            }
        }
    }
    @Test fun correctedMissingAmountAndTimePostWithoutStaleParserIssues() {
        val f = fixture(); readOnly(f) {
            val patch = mapOf("amount" to "-12.5", "occurred_at" to "2026-07-05T00:00:00Z",
                "components" to listOf(mapOf("ordinal" to 0, "account_id" to "101", "amount" to "-12.5", "currency" to "CNY")))
            val operation = prepare(f, f.files("missing_finance"), choices = listOf(choice(patch))).operations.single()
            assertEquals("create_posted", operation["planned_action"]); assertEquals(emptyList(), issues(operation))
        }
    }
    @Test fun existingCompleteDraftUsesCurrentAccountEligibility() {
        val f = fixture("draft_inactive"); readOnly(f) {
            val operation = prepare(f, f.files("default"), selected = false).operations.single()
            assertEquals("draft", values(operation)["state"])
            assertTrue(issues(operation).any { it["code"] == "account_unavailable" })
        }
    }
    @Test fun existingDraftFundingRefreshPreservesComponentIdentity() {
        val f = fixture("draft"); readOnly(f) {
            val operation = prepare(f, f.files("default")).operations.single()
            assertEquals("finalize_draft", operation["planned_action"])
            assertEquals("1003", objectValue((values(operation)["components"] as List<*>).first())["component_id"])
        }
    }
    @Test fun explicitDraftComponentIdsMustBeOwnedAndUniqueForBothOriginalParameters() {
        val f = fixture("draft"); readOnly(f) {
            val foreign = listOf(mapOf("component_id" to "1001", "ordinal" to 0, "account_id" to "101", "amount" to "-12.5", "currency" to "CNY"))
            val duplicate = listOf(mapOf("component_id" to "1003", "ordinal" to 0, "account_id" to "101", "amount" to "-6.25", "currency" to "CNY"),
                mapOf("component_id" to "1003", "ordinal" to 1, "account_id" to "101", "amount" to "-6.25", "currency" to "CNY"))
            for (components in listOf(foreign, duplicate)) invalid {
                prepare(f, f.files("default"), selected = false, choices = listOf(choice(mapOf("components" to components))))
            }
        }
    }

    @Test fun newRecordHasFieldsWithoutFabricatedUpdateDifferences() {
        val f = fixture(); readOnly(f) {
            val operation = prepare(f, f.files("default")).operations.single()
            assertEquals("create_posted", operation["planned_action"])
            assertEquals("-12.5", values(operation)["amount"]); assertNull(operation["current"])
            assertEquals(emptyList<Any?>(), operation["field_diffs"])
        }
    }
    @Test fun missingAlipayFundingUsesWalletAndCanPost() {
        val f = fixture(); val files = f.files("wallet"); readOnly(f) {
            assertEquals("支付宝余额", CashImportFunding.scan(files).groups.single().displayName)
            val operation = prepare(f, files).operations.single()
            assertEquals("create_posted", operation["planned_action"]); assertEquals("101", values(operation)["account_id"])
            assertEquals(listOf(mapOf("ordinal" to 0, "account_id" to "101", "amount" to "-12.5", "currency" to "CNY")), values(operation)["components"])
            assertFalse(issues(operation).any { it["code"] == "funding_unrecognized" })
        }
    }
    @Test fun completeAndUnmappedNewRowsHaveReadOnlyPostedOrDraftDecisions() {
        val f = fixture(); readOnly(f) {
            val files = f.files("default"); val posted = prepare(f, files); val draft = prepare(f, files, selected = false)
            assertEquals(listOf("create_posted"), posted.rows.map { it["planned_action"] })
            assertEquals(listOf("create_draft"), draft.rows.map { it["planned_action"] })
            assertEquals("-12.5", values(posted.operations.single())["amount"])
            assertEquals("101", values(posted.operations.single())["account_id"])
            assertNull(values(draft.operations.single())["account_id"])
            assertTrue(issues(draft.operations.single()).any { it["code"] == "account_missing" })
        }
    }
    @Test fun repeatedProviderAcrossFilesHasOneOperationAndAllResults() {
        val f = fixture(); readOnly(f) {
            val prepared = prepare(f, f.files("repeated")); assertEquals(1, prepared.operations.size); assertEquals(2, prepared.rows.size)
            assertEquals(listOf("create_posted", "duplicate_in_batch"), prepared.rows.map { it["planned_action"] }.sortedBy { it.toString() })
            assertEquals(1, prepared.rows.map { it["operation_ref"] }.toSet().size)
            assertEquals(2, (prepared.operations.single()["source_updates"] as List<*>).size)
        }
    }
    @Test fun badPatchRejectsPlanButUnreadableSourceDoesNotBlockGoodRows() {
        val f = fixture(); readOnly(f) {
            val files = f.files("with_bad")
            invalid { prepare(f, files, choices = listOf(choice(mapOf("record_type" to "invalid")))) }
            val prepared = prepare(f, files)
            assertEquals(listOf("create_posted", "create_posted", "skip"), prepared.rows.map { it["planned_action"] })
            assertEquals("row_unreadable", prepared.rows[2]["code"])
        }
    }
    @Test fun multipleChoicesForOneSourceOperationRejectBothOriginalParameters() {
        val f = fixture(); readOnly(f) {
            for (note in listOf("一个决定", "另一个决定")) invalid {
                prepare(f, f.files("repeated"), choices = listOf(choice(mapOf("note" to "一个决定")), choice(mapOf("note" to note), "file_b:r1")))
            }
        }
    }
    @Test fun malformedOrForeignCategoryRejectsWholePlan() {
        val f = fixture(); readOnly(f) {
            for (category in listOf(mapOf("id" to "wrong"), "foreign-category")) invalid {
                prepare(f, f.files("two"), choices = listOf(choice(mapOf("category_id" to category))))
            }
        }
    }
    @Test fun manualTypeIncompatibleWithSourceSubtypeRejectsPlan() {
        val f = fixture(); readOnly(f) {
            invalid { prepare(f, f.files("default"), choices = listOf(choice(mapOf("record_type" to "transfer_out")))) }
        }
    }
    @Test fun unknownPatchFieldRejectsWithoutLeakingInput() {
        val f = fixture(); readOnly(f) {
            val error = assertFailsWith<CashImportPlanError> {
                prepare(f, f.files("two"), choices = listOf(choice(mapOf("bad field SQL" to true))))
            }
            assertEquals("plan_input_invalid", error.code); assertEquals("plan_input_invalid", error.message)
        }
    }
    @Test fun unidentifiedEquivalentRepetitionsKeepTwoDistinctOccurrences() {
        val f = fixture(); readOnly(f) {
            val prepared = prepare(f, f.files("unidentified")); assertEquals(2, prepared.operations.size)
            assertEquals(listOf("create_posted", "create_posted", "duplicate_in_batch", "duplicate_in_batch"), prepared.rows.map { it["planned_action"] })
        }
    }
    @Test fun accountRenameDoesNotInvalidateCurrentMapping() {
        val f = fixture("rename"); readOnly(f) {
            f.database.read { connection ->
                val account = connection.jooq(f.database.sqlite).select(ACCOUNTS.NAME, ACCOUNTS.REVISION)
                    .from(ACCOUNTS).where(ACCOUNTS.ID.eq(101L).and(ACCOUNTS.WORKSPACE_ID.eq(f.workspace))).fetchSingle()
                assertEquals("最新账户名", account[ACCOUNTS.NAME]); assertEquals(2L, account[ACCOUNTS.REVISION])
            }
            val operation = prepare(f, f.files("default")).operations.single()
            assertEquals("posted", values(operation)["state"]); assertEquals(emptyList(), issues(operation))
        }
    }
    @Test fun newRecordCannotClaimExistingComponentIdentifier() {
        val f = fixture(); readOnly(f) {
            invalid { prepare(f, f.files("default"), choices = listOf(choice(mapOf("components" to listOf(mapOf(
                "component_id" to "1001", "ordinal" to 0, "account_id" to "101", "amount" to "-12.5", "currency" to "CNY")))))) }
        }
    }
    @Test fun explicitFinancialContradictionsRejectAllSixOriginalCombinations() {
        val f = fixture(); readOnly(f) {
            val part = { account: String, amount: String -> mapOf("ordinal" to 0, "account_id" to account, "amount" to amount, "currency" to "CNY") }
            val combinations = listOf(listOf(part("101", "-6"), part("102", "-6.5")), listOf(part("101", "-1")), listOf(part("101", "12.5")))
            for (variant in listOf("default", "missing_time")) for (parts in combinations) invalid {
                prepare(f, f.files(variant), selected = false, choices = listOf(choice(mapOf("cash_granularity" to "aggregate", "account_id" to null, "components" to parts))))
            }
        }
    }
    @Test fun typedPreparationHasSameWireResultAndLeavesInputUnchanged() {
        val f = fixture(); readOnly(f) {
            val files = f.files("default"); val before = sourceJson(files.map { it.document!!.rows.map { row -> row.fields } })
            val ids = CashSourceRows.sourceBatchRows(files).rows.values.associate { it.occurrenceKey to "synthetic-record" }
            val wire = prepare(f, files, ids = ids); val typed = prepare(f, files, ids = ids, wire = false)
            assertIs<CashAmount>(values(typed.operations.single())["amount"])
            assertEquals(exactCashAmount("-12.5"), values(typed.operations.single())["amount"])
            assertEquals(sourceJson(wire.operations), sourceJson(toWire(typed.operations)))
            assertEquals(before, sourceJson(files.map { it.document!!.rows.map { row -> row.fields } }))
            assertEquals(files, f.files("default"))
        }
    }
    private fun toWire(value: Any?): Any? = when (value) {
        is CashAmount -> BigDecimal(value.toString()).toPlainString()
        is Instant -> ParsedImportCodec.timeText(value)
        is Map<*, *> -> value.mapValues { toWire(it.value) }
        is List<*> -> value.map(::toWire)
        else -> value
    }
}
