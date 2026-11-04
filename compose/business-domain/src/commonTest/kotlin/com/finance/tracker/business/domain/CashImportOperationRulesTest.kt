package com.finance.tracker.business.domain

import kotlinx.serialization.json.JsonArray
import kotlin.test.*

class CashImportOperationRulesTest {
    private fun rules(calls: MutableList<String>, sourceFailure: Throwable? = null): CashImportOperationRules = CashImportOperationRules(
        reads = object : CashImportOperationRead {
            override fun source(files: List<ParsedImportFile>): PreparedCashSourcePlan {
                calls += "source"
                sourceFailure?.let { throw it }
                return PreparedCashSourcePlan(SourceBatchRows(linkedMapOf(), emptyList(), emptyList(), emptyMap()),
                    emptyList(), SourceBatchPlan(emptyList()), emptyList())
            }
            override fun accounts(selections: Map<String, Map<String, Any?>>, records: List<SourceRecord>,
                choices: Map<String, Map<String, Any?>>): Map<String, Map<String, Any?>> {
                calls += "accounts"; return emptyMap()
            }
            override fun entries(transactionIds: Collection<String>): Pair<Map<String, CashImportEntryVersion>, Map<String, List<Any?>>> {
                calls += "entries"; return emptyMap<String, CashImportEntryVersion>() to emptyMap()
            }
            override fun targets(records: Map<String, SourceRecord>): Pair<Map<String, CashImportOperationTarget>, Map<String, List<Map<String, Any?>>>> {
                calls += "targets"; return emptyMap<String, CashImportOperationTarget>() to emptyMap()
            }
            override fun categories(choices: Map<String, Map<String, Any?>>): Set<String> {
                calls += "categories"; return emptySet()
            }
        },
        recordValues = LocalCashRecordValues,
        previewInput = { accounts, rows -> prepareLocalCashImportInput(encodeLocalCashValue(accounts), encodeLocalCashValue(rows), JsonArray(emptyList())) },
        scanFunding = { calls += "scan"; scanLocalCashFunding(it) },
        resolveFunding = { scan, selections, accounts -> calls += "funding"; resolveLocalCashFunding(scan, encodeLocalCashValue(selections), encodeLocalCashValue(accounts)) },
        postingValidator = { LocalCashRecordValues::validateForPosting },
        wireValue = { it }, digestValues = ::localCashSourceDigest, allocateRecordId = ::allocateLocalCashSourceRecordId,
        issueValue = { _, _, _ -> error("no issues for an empty input") }, integerValue = ::SourceJsonInteger,
    )

    @Test fun sharedPlannerKeepsReadOrderAndDoesNotWrite() {
        val calls = mutableListOf<String>()
        val prepared = rules(calls).prepare(emptyList())
        assertEquals(listOf("scan", "source", "accounts", "funding", "entries", "targets", "categories"), calls)
        assertTrue(prepared.rows.isEmpty())
        assertTrue(prepared.operations.isEmpty())
        assertTrue(prepared.candidateSets.isEmpty())
    }

    @Test fun invalidInputAndReadFailureStopBeforeLaterDependencies() {
        val calls = mutableListOf<String>()
        assertFailsWith<CashImportPlanError> {
            rules(calls).prepare(emptyList(), rowChoices = listOf(mapOf("row_ref" to "r", "action" to "bad")))
        }
        assertTrue(calls.isEmpty())
        val failure = IllegalStateException("snapshot unavailable")
        assertSame(failure, assertFailsWith<IllegalStateException> { rules(calls, failure).prepare(emptyList()) })
        assertEquals(listOf("scan", "source"), calls)
    }
}
