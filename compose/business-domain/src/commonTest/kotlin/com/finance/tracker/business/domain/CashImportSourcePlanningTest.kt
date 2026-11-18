package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashSourceCoverage
import kotlin.test.*

class CashImportSourcePlanningTest {
    private fun page(number: String) = CashSourceCoverage("2026-08-01T00:00Z", "2026-09-01T00:00Z", false, "",
        emptyMap(), "pages-v1", documentKey = "d".repeat(64), pageTotal = 2, pageFingerprints = mapOf(number to "e".repeat(64)))
    private fun files() = listOf(ParsedImportFile("file", "a".repeat(64), "parsed", ParsedCashDocument("icbc_credit", "pdf", "v1",
        listOf(ParsedCashRow(1, mapOf("amount" to "-1", "currency" to "CNY"), documentPosition = "p1:t1:r1")),
        "123456789012", null, page("1"), 1, 1)))
    @Test fun sourceProofsAreLiftedFromTheSameReadInputsWithoutTrustingLegacyFlags() {
        val input = files()
        val regular = page("1").copy(documentKey = null, pageTotal = null, pageFingerprints = emptyMap(), complete = true, evidence = "regular")
        val old = page("1").copy(complete = true, evidence = "old-unproven")
        val candidate = SourceDocumentEvidence("icbc_credit", "v1", page("1"))
        val saved = SourceDocumentEvidence("icbc_credit", "v1", page("2"))
        val records = listOf(SourceRecord("1", "icbc_credit", "123456789012", mapOf("state" to "posted"),
            coverages = listOf(regular, old), documentEvidence = listOf(candidate)),
            SourceRecord("2", "icbc_credit", "123456789012", mapOf("state" to "posted"), coverages = listOf(regular, old)))
        val calls = mutableListOf<String>()
        val expectedPlan = SourceBatchPlan(listOf(SourceBatchResolution("file:r1", "duplicate", "1")))
        val rules = CashImportSourcePlanningRules({ rows ->
            calls += "records"
            assertEquals(1, rows.size)
            assertFalse(rows.single().coverage.complete)
            records
        }, { files, candidates ->
            calls += "proofs"
            assertSame(input, files)
            assertEquals(listOf(candidate), candidates)
            listOf(saved)
        }, { rows, lifted, evidence ->
            calls += "resolve"
            assertTrue(rows.rows.values.single().coverage.complete)
            assertEquals(listOf(regular, page("1").copy(complete = true, evidence = "icbc-pdf-export-pages-count-v1")), lifted[0].coverages)
            assertEquals(listOf(regular, old.copy(complete = false, evidence = "")), lifted[1].coverages)
            assertEquals(listOf(candidate, saved, candidate), evidence)
            expectedPlan
        })
        val prepared = rules.prepare(input)
        assertSame(expectedPlan, prepared.plan)
        assertEquals(listOf("records", "proofs", "resolve"), calls)
        assertEquals(listOf(candidate, saved, candidate), prepared.evidence)
        assertEquals(old, records[1].coverages.last())
        assertFalse(input.single().document!!.coverage.complete)
    }
    @Test fun noSavedProofDoesNotInventCompletenessAndReadFailureStopsThePipeline() {
        val files = files()
        val regular = page("1").copy(documentKey = null, pageTotal = null, pageFingerprints = emptyMap())
        val record = SourceRecord("1", "icbc_credit", null, emptyMap(), coverages = listOf(regular))
        val prepared = CashImportSourcePlanningRules({ listOf(record) }, { _, _ -> emptyList() }, { rows, records, _ ->
            assertFalse(rows.rows.values.single().coverage.complete)
            assertEquals(listOf(record), records)
            SourceBatchPlan(emptyList())
        }).prepare(files)
        assertEquals(1, prepared.evidence.size)
        val failed = IllegalStateException("read failed")
        var laterCalled = false
        val rules = CashImportSourcePlanningRules({ throw failed }, { _, _ -> laterCalled = true; emptyList() }, { _, _, _ ->
            laterCalled = true; SourceBatchPlan(emptyList())
        })
        assertSame(failed, assertFailsWith<IllegalStateException> { rules.prepare(files) })
        assertFalse(laterCalled)
    }
}
