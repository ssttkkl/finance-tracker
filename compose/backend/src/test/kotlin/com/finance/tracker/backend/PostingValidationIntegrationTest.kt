package com.finance.tracker.backend

import kotlin.test.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class PostingValidationIntegrationTest {
    private fun fixture() = CashImportOriginalApiFixture("base", "FT_KOTLIN_IMPORT_ORIGINAL_OPERATION_CONTRACT_DIR")
    private fun files(f: CashImportOriginalApiFixture, count: Int = 20): List<ParsedImportFile> {
        val file = f.files("default").single(); val document = file.document!!; val original = document.rows.single()
        val rows = List(count) { index -> original.copy(position = index + 1, providerId = "cache-$index",
            fields = original.fields!! + mapOf("note" to "note-$index", "source_payload" to mapOf("交易号" to "cache-$index", "备注" to "note-$index"))) }
        return listOf(file.copy(document = document.copy(rows = rows, declaredCount = count, observedCount = count)))
    }
    private fun selections(files: List<ParsedImportFile>) = CashImportFunding.scan(files).groups.map {
        mapOf("source_account_ref" to it.sourceAccountRef, "account_id" to "101", "expected_account_revision" to 1)
    }
    @Test fun operationPrepareOwnsSuccessCachePerCallAndDoesNotWrite() {
        val f = fixture(); val input = files(f); val before = f.snapshot(); var calls = 0
        f.database.read { connection ->
            val planner = CashImportOperationPlanner(CashSqlContext(connection, f.workspace, f.database.sqlite)) {
                calls++; CashRecordPolicy.validateForPosting(it)
            }
            repeat(2) { attempt ->
                val result = planner.prepare(input, accountSelections = selections(input))
                assertEquals(20, result.operations.size)
                assertTrue(result.operations.all { it["planned_action"] == "create_posted" })
                assertEquals(attempt + 1, calls)
            }
        }
        assertEquals(before, f.snapshot())
    }
    @Test fun fundingResolveOwnsSuccessCachePerCallAndStillChecksAccounts() {
        val f = fixture(); val scan = CashImportFunding.scan(files(f)); var calls = 0
        val engine = CashImportFundingEngine { calls++; CashRecordPolicy.validateForPosting(it) }
        val selected = scan.groups.associate { it.sourceAccountRef to mapOf("account_id" to "101", "expected_account_revision" to 1) }
        val account = mapOf("id" to "101", "revision" to 1, "active" to true, "type" to "cash", "currencies" to listOf("CNY"))
        repeat(2) { attempt ->
            val result = engine.resolve(scan, selected, mapOf("101" to account))
            assertEquals(20, result.size); assertTrue(result.all { it.ready }); assertEquals(attempt + 1, calls)
        }
        val result = engine.resolve(scan, selected, mapOf("101" to (account + ("active" to false))))
        assertEquals(20, result.size); assertTrue(result.all { !it.ready && "account_unavailable" in it.issueCodes })
        assertEquals(22, calls)
    }
    @Test fun bothActualCallersStopOnUnknownValidatorFailure() {
        val f = fixture(); val input = files(f); val before = f.snapshot()
        val failure = IllegalStateException("synthetic-validator-failure"); var calls = 0
        val engine = CashImportFundingEngine { calls++; throw failure }
        val scan = CashImportFunding.scan(input)
        val selected = scan.groups.associate { it.sourceAccountRef to mapOf("account_id" to "101", "expected_account_revision" to 1) }
        val accounts = mapOf("101" to mapOf("id" to "101", "revision" to 1, "active" to true, "type" to "cash", "currencies" to listOf("CNY")))
        assertSame(failure, assertFailsWith<IllegalStateException> { engine.resolve(scan, selected, accounts) })
        assertEquals(1, calls)
        f.database.read { connection ->
            val planner = CashImportOperationPlanner(CashSqlContext(connection, f.workspace, f.database.sqlite)) { calls++; throw failure }
            assertSame(failure, assertFailsWith<IllegalStateException> { planner.prepare(input, accountSelections = selections(input)) })
        }
        assertEquals(2, calls); assertEquals(before, f.snapshot())
    }
}
