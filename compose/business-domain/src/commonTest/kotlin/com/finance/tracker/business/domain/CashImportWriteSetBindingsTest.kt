package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CashImportWriteSetBindingsTest {
    private val empty = JsonObject(emptyMap())
    private val time = "2026-01-01T00:00:00Z"
    private fun existing(id: String) = ExistingCashWriteReferenceDto(id)
    private fun parent(id: String, deleted: Boolean = false, origin: String = "import") = LedgerCashTransactionFactDto(
        id, null, "draft", origin, null, "1", "atomic", "ccb_debit", "record:$id", empty, empty, null, empty,
        null, null, null, "", "", JsonArray(emptyList()), "", null, null, null, time, if (deleted) time else null, "", "")
    private fun facts(bindings: List<LedgerSourceBindingFactDto> = emptyList()) = LedgerFactsDto(emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0")
        .copy(cashTransactions = listOf(parent("1"), parent("2")), cashSourceBindings = bindings)
    private fun binding(name: String = "b", owner: String = "1", scope: String = "owner", key: String = "occurrence") = CashSourceBindingWriteDto(
        CashWriteVersionedReferenceDto(NewCashWriteReferenceDto(name), null), existing(owner), "ccb_debit", "account", scope,
        "file_occurrence", key, "validated", null, "p", empty)
    private fun stored(row: CashSourceBindingWriteDto = binding(), id: String = "20") = LedgerSourceBindingFactDto(id,
        (row.transaction as ExistingCashWriteReferenceDto).id, row.sourceType, row.sourceScopeKind, row.sourceScopeKey, row.identityKind,
        row.identityKey, row.identityStrength, row.stableEvidenceKey, row.strategyVersion, row.coverage, "1", time, time)
    private fun input(vararg rows: CashSourceBindingWriteDto) = CashWriteSetDto(sourceBindings = rows.toList())
    private fun prepare(value: CashWriteSetDto, source: LedgerFactsDto = facts()) = prepareCashImportWriteSetBindings(value, source)
    private fun denied(value: CashWriteSetDto, source: LedgerFactsDto = facts(), code: String? = null) {
        val failure = assertFailsWith<CashPolicyError> { prepare(value, source) }
        if (code != null) assertEquals(code, failure.code)
    }
    private fun coverage(fingerprint: Char = 'b') = buildJsonObject {
        put("document_key", "a".repeat(64)); put("page_total", 2)
        put("page_fingerprints", buildJsonObject { put("1", fingerprint.toString().repeat(64)) })
    }

    @Test fun canonicalCoverageAndDerivedStrengthPreserveTheFinalNaturalIdentity() {
        val row = binding(); val result = prepare(input(row)).sourceBindings.single()
        assertEquals(row.copy(coverage = result.coverage), result)
        assertEquals(Json.parseToJsonElement("""{"start":null,"end":null,"complete":false,"evidence":"","filters":{},"strategy_version":"source-coverage-v1","start_inclusive":true,"end_inclusive":false,"time_precision":"second","document_key":null,"page_total":null,"page_fingerprints":{}}"""), result.coverage)
        val provider = row.copy(identityKind = "provider_id", identityStrength = "provider")
        assertEquals("provider", prepare(input(provider)).sourceBindings.single().identityStrength)
        assertEquals(JsonPrimitive(2), prepare(input(row.copy(coverage = coverage()))).sourceBindings.single().coverage.jsonObject["page_total"])
        assertEquals(CashWriteSetDto(), prepare(CashWriteSetDto()))
    }

    @Test fun immutableNaturalKeysAndRealRevisionsProtectExistingBindings() {
        val old = stored(); val source = facts(listOf(old))
        val row = binding().copy(target = CashWriteVersionedReferenceDto(existing(old.id), "1"))
        denied(input(row.copy(identityKey = "another")), source, "source_identity_conflict")
        denied(input(row.copy(target = row.target.copy(expectedRevision = "2"))), source, "reference_changed")
        denied(input(binding()), source, "duplicate_reference")
        denied(input(row.copy(transaction = existing("2"))), source, "field_not_editable")
        assertEquals(existing("2"), prepare(input(row.copy(transaction = existing("2"))), source.copy(
            cashTransactions = listOf(parent("1", deleted = true), parent("2")))).sourceBindings.single().transaction)
    }

    @Test fun sourceAccountProviderAndStableEvidenceConflictsCannotBeHiddenByAChangedRow() {
        val first = binding().copy(stableEvidenceKey = "evidence")
        val old = stored(first); val source = facts(listOf(old))
        denied(input(binding("second", scope = "another", key = "another")), source, "source_identity_conflict")
        val edited = first.copy(target = CashWriteVersionedReferenceDto(existing("20"), "1"))
        denied(input(edited.copy(stableEvidenceKey = null)), source, "source_identity_conflict")
        denied(input(edited.copy(stableEvidenceKey = "changed")), source, "source_identity_conflict")
        denied(input(binding("second", owner = "2", key = "another").copy(stableEvidenceKey = "evidence")), source, "active_source_binding")
        val provider = binding().copy(identityKind = "provider_id", identityStrength = "provider", identityKey = "provider1")
        denied(input(provider, provider.copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("other"), null), identityKey = "provider2")), code = "source_identity_conflict")
    }

    @Test fun targetsMustBeLiveImportedRecordsWithTheSameSource() {
        denied(input(binding().copy(sourceType = "alipay", sourceScopeKey = CashSourceScope.ALIPAY_TRANSACTION_SCOPE)), code = "source_target_incompatible")
        denied(input(binding()), facts().copy(cashTransactions = listOf(parent("1", deleted = true))), "source_target_unavailable")
        denied(input(binding()), facts().copy(cashTransactions = listOf(parent("1", origin = "manual"))), "source_target_incompatible")
    }

    @Test fun invalidKeyKindsStrengthAndCoverageAreRejectedAtTheInputBoundary() {
        for (row in listOf(binding().copy(sourceScopeKey = ""), binding().copy(identityKey = "x".repeat(65)),
            binding().copy(identityStrength = "manual"), binding().copy(sourceScopeKind = "file_local", identityKind = "provider_id", identityStrength = "file_local"),
            binding().copy(stableEvidenceKey = ""), binding().copy(strategyVersion = ""))) denied(input(row), code = "invalid_source_binding")
        for (value in listOf<JsonElement>(JsonNull, JsonArray(emptyList()), buildJsonObject { put("unexpected", true) },
            buildJsonObject { put("complete", "true") }, buildJsonObject { put("start", 42) }, buildJsonObject { put("filters", JsonArray(emptyList())) }))
            denied(input(binding().copy(coverage = value)), code = "invalid_source_coverage")
    }

    @Test fun documentPageAndDeclarationConflictsUseExistingWorkspaceEvidence() {
        val oldRow = binding().copy(coverage = coverage(), sourceScopeKind = "file_local", sourceScopeKey = "old-file", identityStrength = "file_local")
        val source = facts(listOf(stored(oldRow)))
        val next = oldRow.copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("next"), null), transaction = existing("2"), identityKey = "next")
        denied(input(next.copy(coverage = coverage('c'))), source, "source_document_conflict")
        denied(input(next.copy(coverage = JsonObject(coverage() + ("start_inclusive" to JsonPrimitive(false))))), source, "source_document_conflict")
        assertEquals(1, prepare(input(next), source).sourceBindings.size)
        val documentScope = localSourceStringDigest(listOf("source-document-v1", "ccb_debit", "p", "a".repeat(64)))
        val original = oldRow.copy(sourceScopeKey = documentScope)
        denied(input(original.copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("next"), null), identityKey = "other-position")),
            facts(listOf(stored(original))), "source_identity_conflict")
    }
    @Test fun updatingTheOnlyBindingCannotReplaceTrustedDocumentEvidence() {
        val original = binding().copy(coverage = coverage())
        val old = stored(original)
        val update = original.copy(target = CashWriteVersionedReferenceDto(existing(old.id), old.revision))
        denied(input(update.copy(coverage = coverage('c'))), facts(listOf(old)), "source_document_conflict")
        denied(input(update.copy(coverage = JsonObject(coverage() + ("start_inclusive" to JsonPrimitive(false))))), facts(listOf(old)), "source_document_conflict")
        assertEquals(1, prepare(input(update), facts(listOf(old))).sourceBindings.size)
    }

    @Test fun platformAccountScopesUseTheirExistingChannelConstants() {
        for ((channel, scope) in listOf("alipay" to CashSourceScope.ALIPAY_TRANSACTION_SCOPE, "wechat" to CashSourceScope.WECHAT_DEFAULT_SCOPE)) {
            val source = facts().copy(cashTransactions = listOf(parent("1").copy(sourceType = channel)))
            val row = binding().copy(sourceType = channel, sourceScopeKey = scope, identityKind = "provider_id", identityStrength = "provider")
            assertEquals(scope, prepare(input(row), source).sourceBindings.single().sourceScopeKey)
            denied(input(row.copy(sourceScopeKey = "another-account")), source, "invalid_source_binding")
        }
    }

    @Test fun liveDocumentAliasesKeepTheirExistingNamespaceAndPagesWithoutFreezingOtherMetadata() {
        val document = localSourceStringDigest(listOf("source-document-v1", "ccb_debit", "p", "a".repeat(64)))
        val page = localSourceStringDigest(listOf("source-document-page-v1", document, "1"))
        for (scope in listOf(document, page)) {
            val original = binding().copy(sourceScopeKind = "file_local", sourceScopeKey = scope, identityStrength = "file_local", coverage = coverage())
            val old = stored(original)
            val update = original.copy(target = CashWriteVersionedReferenceDto(existing(old.id), old.revision))
            for (row in listOf(update.copy(coverage = empty), update.copy(strategyVersion = "another-parser"),
                update.copy(coverage = JsonObject(coverage() + ("document_key" to JsonPrimitive("d".repeat(64))))),
                update.copy(coverage = JsonObject(coverage() + ("page_fingerprints" to buildJsonObject { put("2", "b".repeat(64)) })))))
                denied(input(row), facts(listOf(old)), "source_document_conflict")
            val deleted = facts(listOf(old)).copy(cashTransactions = listOf(parent("1", deleted = true), parent("2")))
            assertEquals(existing("2"), prepare(input(update.copy(transaction = existing("2"), coverage = empty)), deleted).sourceBindings.single().transaction)
        }
        val ordinary = binding().copy(coverage = coverage())
        val old = stored(ordinary)
        val update = ordinary.copy(target = CashWriteVersionedReferenceDto(existing(old.id), old.revision), coverage = empty)
        assertEquals(JsonNull, prepare(input(update), facts(listOf(old))).sourceBindings.single().coverage.jsonObject["document_key"])
    }

}
