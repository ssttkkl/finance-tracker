package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.ACCOUNTS
import java.time.Instant
import java.math.BigDecimal
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import kotlin.test.*

class CashImportOriginalOperationTest {
    private fun fixture(name: String = "base") = CashImportOriginalApiFixture(name, "FT_KOTLIN_IMPORT_ORIGINAL_OPERATION_CONTRACT_DIR")
    private fun selections(files: List<ParsedImportFile>) = CashImportFunding.scan(files).groups.map {
        mapOf("source_account_ref" to it.sourceAccountRef, "account_id" to "101", "expected_account_revision" to 1)
    }
    private fun choice(patch: Map<String, Any?>, ref: String = "file_a:r1") =
        mapOf("row_ref" to ref, "action" to "apply", "patch" to patch)
    private fun prepare(f: CashImportOriginalApiFixture, files: List<ParsedImportFile>, selected: Boolean = true,
        choices: List<Any?> = emptyList(), ids: Map<String, String> = emptyMap(), wire: Boolean = true) = f.database.read {
        CashImportOperationPlanner(CashSqlContext(queryOnly(it), f.workspace, f.database.sqlite)).prepare(files,
            accountSelections = if (selected) selections(files) else emptyList(), rowChoices = choices,
            recordIds = ids, wireValues = wire)
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
