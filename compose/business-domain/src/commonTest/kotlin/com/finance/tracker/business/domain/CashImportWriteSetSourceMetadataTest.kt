package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CashImportWriteSetSourceMetadataTest {
    private val empty = JsonObject(emptyMap())
    private val time = "2026-01-01T00:00:00Z"
    private fun ref(id: String) = ExistingCashWriteReferenceDto(id)
    private fun target(id: String) = CashWriteVersionedReferenceDto(ref(id), "1")
    private fun parent(id: String) = LedgerCashTransactionFactDto(id, "10", "draft", "import", null, "1", "atomic", "ccb_debit", "record:$id",
        empty, empty, null, empty, time, "-5.00", "CNY", "", "", JsonArray(emptyList()), "", null, "consumption", "not_applicable", time, null, "", "")
    private fun facts(issues: List<LedgerCashIssueFactDto> = emptyList()) = LedgerFactsDto(
        listOf(LedgerAccountFactDto("10", "Cash", "cash", true, JsonArray(listOf(JsonPrimitive("CNY"))), "1")),
        emptyList(), emptyList(), emptyList(), listOf(parent("1"), parent("2")),
        listOf("1", "2").map { LedgerCashComponentFactDto("1$it", it, "10", "-5.00", "CNY", "0", "", "", empty, time) },
        emptyList(), emptyList(), emptyList(), emptyList(), issues, emptyList(), emptyList(), emptyList(), emptyList(), "0")
    private fun row(id: String = "1"): CashTransactionWriteDto {
        val r = parent(id)
        return CashTransactionWriteDto(target(id), CashTransactionWriteValuesDto(r.accountId, r.state, r.recordOrigin, r.manualTransferId,
            r.cashGranularity, r.sourceType, r.recordId, r.sourcePayload, r.relationMetadata, r.sourceFingerprint, r.manualOverrides, r.occurredAt,
            r.amount, r.currency, r.counterparty, r.counterpartyAccount, r.counterpartyAccountAttrs, r.note, r.categoryId, r.recordType, r.recordSubtype),
            listOf(CashComponentWriteDto(ref("1$id"), "10", "-5.00", "CNY", "0", "", "", empty)))
    }
    private fun issue(code: String = "posting_incomplete") = CashIssueWriteDto(CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("issue"), null),
        target("1"), "import:issue", code, null, JsonNull, JsonNull, empty, emptyList())
    private fun stored(code: String = "posting_incomplete", key: String = "import:issue") = LedgerCashIssueFactDto("20", "1", key, code, null, JsonNull, JsonNull, empty, "1", time, time)
    private fun coverage() = buildJsonObject { put("document_key", "a".repeat(64)); put("page_total", 2)
        put("page_fingerprints", buildJsonObject { put("1", "b".repeat(64)) }); put("complete", true); put("evidence", "original") }
    private fun page() = LedgerSourceEmptyPageFactDto("ccb_debit", "p", "a".repeat(64), 1, coverage())
    private fun input(vararg issues: CashIssueWriteDto) = CashWriteSetDto(transactions = listOf(row()), issues = issues.toList())
    private fun prepare(value: CashWriteSetDto, source: LedgerFactsDto = facts()) = prepareCashImportWriteSetSourceMetadata(value, source)
    private fun denied(value: CashWriteSetDto, source: LedgerFactsDto = facts(), code: String? = null) {
        val error = assertFailsWith<CashPolicyError> { prepare(value, source) }; if (code != null) assertEquals(code, error.code)
    }

    @Test fun emptyPagesHaveCanonicalSinglePageProofsAndExactIntegers() {
        val result = prepare(CashWriteSetDto(sourceEmptyPages = listOf(page()))).sourceEmptyPages.single()
        assertEquals(JsonPrimitive(2), result.coverage.jsonObject["page_total"])
        assertEquals(JsonPrimitive(false), result.coverage.jsonObject["complete"]); assertEquals(JsonPrimitive(""), result.coverage.jsonObject["evidence"])
        for (p in listOf(page().copy(pageNumber = 2), page().copy(documentKey = "c".repeat(64)), page().copy(parserVersion = ""),
            page().copy(pageNumber = 2001), page().copy(sourceType = "unknown"), page().copy(coverage = JsonNull)))
            denied(CashWriteSetDto(sourceEmptyPages = listOf(p)), code = "invalid_source_coverage")
    }

    @Test fun sourceIssuesKeepTheirNaturalOwnerKeyAndClientRevisions() {
        val old = stored(); val source = facts(listOf(old)); val edit = issue().copy(target = target("20"))
        assertEquals(edit, prepare(input(edit), source).issues.single())
        denied(input(edit.copy(issueKey = "changed")), source, "source_identity_conflict")
        denied(input(edit.copy(transaction = target("2"))), source, "source_identity_conflict")
        denied(input(edit.copy(target = edit.target.copy(expectedRevision = "2"))), source, "reference_changed")
        denied(input(issue()), source, "duplicate_reference")
        denied(CashWriteSetDto(issues = listOf(issue())), code = "field_not_editable")
    }

    @Test fun issueFieldsAndPendingPatchesReuseTheExistingStructureRules() {
        for (i in listOf(issue().copy(code = "BAD"), issue().copy(issueKey = ""), issue().copy(fieldPath = "bad path"),
            issue().copy(origin = buildJsonObject { put("unknown", "x") }), issue().copy(pendingFields = JsonArray(emptyList())),
            issue("source_update_pending"))) denied(input(i), code = "invalid_source_issue")
        val valid = issue("source_update_pending").copy(pendingFields = buildJsonObject { put("note", "later source") })
        assertEquals(valid, prepare(input(valid)).issues.single())
        assertEquals("new_reason", prepare(input(issue("new_reason"))).issues.single().code)
        val raw = issue().copy(pendingSourcePayload = buildJsonObject { put("", ""); put("large", JsonPrimitive(9007199254740993L)) })
        assertEquals(raw.pendingSourcePayload, prepare(input(raw)).issues.single().pendingSourcePayload)
    }

    @Test fun candidatesMustBeLiveSameSourceImportedNonSelfAndUseTheirOldDependencies() {
        val i = issue("source_identity_ambiguous").copy(candidates = listOf(target("2")))
        assertEquals(i, prepare(input(i)).issues.single())
        denied(input(i.copy(candidates = listOf(target("1")))), code = "source_candidate_changed")
        denied(input(i.copy(candidates = listOf(target("2").copy(expectedRevision = "2")))), code = "reference_changed")
        denied(input(issue().copy(candidates = listOf(target("2")))), code = "invalid_source_issue")
        for (candidate in listOf(parent("2").copy(deletedAt = time), parent("2").copy(sourceType = "alipay"), parent("2").copy(recordOrigin = "manual")))
            denied(input(i), facts().copy(cashTransactions = listOf(parent("1"), candidate)), "source_candidate_changed")
    }

    @Test fun existingAmbiguityWithEmptyCandidatesRetainsTheWholeOldIssue() {
        val old = stored("source_identity_ambiguous"); val source = facts(listOf(old))
        val request = issue(old.code).copy(target = target(old.id), pendingFields = buildJsonObject { put("note", "must not replace old") })
        val actual = prepare(input(request), source).issues.single()
        assertEquals(old.pendingFields, actual.pendingFields); assertEquals(old.origin, actual.origin); assertTrue(actual.candidates.isEmpty())
        denied(input(request.copy(code = "posting_incomplete")), source, "field_not_editable")
        denied(input(request.copy(issueKey = "relation:forged")), source, "source_identity_conflict")
    }

    @Test fun ordinarySourceIssuesRetireButAmbiguityAndRelationProblemsRemain() {
        for (old in listOf(stored("source_identity_ambiguous"), stored(key = "relation:original")))
            denied(CashWriteSetDto(transactions = listOf(row()), resolvedIssues = listOf(target(old.id))), facts(listOf(old)), "field_not_editable")
        val old = stored("source_update_pending")
        assertEquals(listOf(target(old.id)), prepare(CashWriteSetDto(transactions = listOf(row()), resolvedIssues = listOf(target(old.id))), facts(listOf(old))).resolvedIssues)
        denied(CashWriteSetDto(resolvedIssues = listOf(target(old.id))), facts(listOf(old)), "field_not_editable")
    }

    @Test fun finalizationCannotLeaveSourceIssuesWhilePreviouslyPostedPendingUpdatesRemainLegal() {
        val posted = row().let { it.copy(values = it.values.copy(state = "posted")) }
        denied(input(issue()).copy(transactions = listOf(posted)), code = "posting_incomplete")
        val old = stored("source_identity_ambiguous")
        denied(CashWriteSetDto(transactions = listOf(posted)), facts(listOf(old)), "posting_incomplete")
        val pending = issue("source_update_pending").copy(pendingFields = buildJsonObject { put("note", "new source") })
        assertEquals(pending, prepare(input(pending).copy(transactions = listOf(posted)), facts().copy(
            cashTransactions = listOf(parent("1").copy(state = "posted"), parent("2")))).issues.single())
    }

    @Test fun relationProblemWritesRemainForTheRelationStageWithoutRequiringSourceWrites() {
        val relation = issue().copy(issueKey = "relation:new")
        assertEquals(relation, prepare(CashWriteSetDto(issues = listOf(relation))).issues.single())
    }
    @Test fun ordinaryAliasesAndEmptyPagesUseTheSameDocumentConsistencyRules() {
        val binding = CashSourceBindingWriteDto(CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("binding"), null), ref("1"),
            "ccb_debit", "file_local", "ordinary-file", "file_occurrence", "position", "file_local", null, "p", coverage())
        val conflict = page().copy(coverage = JsonObject(coverage() + ("page_fingerprints" to buildJsonObject { put("1", "c".repeat(64)) })))
        denied(input().copy(sourceBindings = listOf(binding), sourceEmptyPages = listOf(conflict)), code = "source_document_conflict")
        val old = LedgerSourceBindingFactDto("30", "1", "ccb_debit", "account", "owner", "provider_id", "provider", "provider", null, "p", coverage(), "1", time, time)
        denied(CashWriteSetDto(sourceEmptyPages = listOf(conflict)), facts().copy(cashSourceBindings = listOf(old)), "source_document_conflict")
    }

}
