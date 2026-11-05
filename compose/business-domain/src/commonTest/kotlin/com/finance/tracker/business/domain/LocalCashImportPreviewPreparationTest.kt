package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.LedgerAccountFactDto
import com.finance.tracker.business.contracts.LedgerSourceBindingFactDto
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class LocalCashImportPreviewPreparationTest {
    private fun source() = LocalCashRelationContextTest().snapshot().let { source ->
        source.copy(facts = source.facts.copy(cashTransactions = source.facts.cashTransactions.filter { it.id != "5" },
            accounts = listOf(LedgerAccountFactDto("10", "cash", "cash", true, JsonArray(emptyList()), "9007199254740993"))))
    }

    @Test fun actualSnapshotReadsComposeSourceReferencesCandidatesDependenciesAndTheFactory() {
        val source = source()
        @Suppress("UNCHECKED_CAST") val operations = decodeLocalCashValue(LocalCashImportPreviewSnapshotTest().operation) as List<Map<String, Any?>>
        val before = localCashImportDigest(listOf(operations))
        var guards = 0
        val rows = mapOf("file:r1" to SourceOccurrence("ccb_debit", "owner", "a".repeat(64), "fixture", "table", 0,
            emptyMap(), providerId = "provider"))
        val prepared = LocalCashImportPreviewPreparation(source) { guards++ }.prepare(operations, rows)
        assertEquals(2, guards)
        assertEquals(9007199254740993L, prepared.accounts.getValue("10")["revision"])
        assertEquals(setOf(1L, 2L), prepared.candidateIds)
        assertEquals(setOf(1L, 2L), prepared.context.seedIds)
        assertEquals("owner", prepared.operations.single()["source_scope_key"])
        assertEquals("record-new", prepared.operations.single()["matching_record_id"])
        assertTrue(prepared.operations.single()["stable_source_key"] is List<*>)
        val preview = buildLocalCashImportPreviewSnapshot(source, prepared.context, prepared.operations, emptyList()) {
            Instant.parse("2026-10-01T09:00:00Z")
        }
        assertEquals(3, preview.facts.size)
        assertEquals(1, preview.operations.size)
        assertEquals(before, localCashImportDigest(listOf(operations)))
    }

    @Test fun staleEntryTargetsFailBeforeAccountDecodingAndGuardErrorsRemainOriginal() {
        val source = source().let { it.copy(facts = it.facts.copy(accounts = it.facts.accounts.map { account -> account.copy(revision = "invalid") })) }
        var guards = 0
        val preparation = LocalCashImportPreviewPreparation(source) { guards++ }
        assertEquals("plan_changed", assertFailsWith<CashImportPlanError> {
            preparation.prepareOperations(emptyList(), entryChoices = listOf(mapOf("edit_ref" to "missing")))
        }.code)
        assertEquals(1, guards)
        val failure = IllegalStateException("scope closed")
        assertSame(failure, assertFailsWith<IllegalStateException> {
            LocalCashImportPreviewPreparation(source) { throw failure }.prepare(emptyList(), emptyMap())
        })
    }

    @Test fun computedOperationsKeepTheirTypesAndUseTheSameQueryAsJsonInput() {
        val json = LocalCashImportPreviewSnapshotTest().operation
        @Suppress("UNCHECKED_CAST") val operations = decodeLocalCashValue(json) as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val values = operations.single()["values"] as MutableMap<String, Any?>
        for (field in financialCashFields) if (field in values) values[field] = LocalCashRecordValues.normalize(field, values[field])
        val before = localCashImportDigest(listOf(operations))
        assertEquals(prepareLocalCashImportCandidateQuery(json), prepareLocalCashImportCandidateQuery(operations))
        val prepared = LocalCashImportPreviewPreparation(source()) {}.prepareOperations(operations)
        val result = prepared.operations.single()["values"] as Map<*, *>
        assertIs<Instant>(result["occurred_at"])
        assertIs<CashAmount>(result["amount"])
        assertEquals(before, localCashImportDigest(listOf(prepared.operations)))
        assertEquals(before, localCashImportDigest(listOf(operations)))
    }

    @Test fun platformSourceRowsReachProviderBindingRecallOutsideTheTimeWindow() {
        val base = source()
        val scope = CashSourceScope.ALIPAY_TRANSACTION_SCOPE
        val time = "2027-06-01T10:00:00Z"
        val transaction = base.facts.cashTransactions.first().copy(sourceType = "alipay", occurredAt = time,
            relationMetadata = JsonNull, sourcePayload = JsonNull)
        val binding = LedgerSourceBindingFactDto("binding", transaction.id, "alipay", "account", scope, "provider_id",
            providerIdentityKey("provider"), "provider", null, "fixture", JsonNull, "1", time, time)
        val source = base.copy(facts = base.facts.copy(cashTransactions = listOf(transaction),
            cashTransactionComponents = base.facts.cashTransactionComponents.filter { it.cashTransactionId == transaction.id },
            transactionRelations = emptyList(), cashSourceBindings = listOf(binding)))
        @Suppress("UNCHECKED_CAST") val operations = decodeLocalCashValue(LocalCashImportPreviewSnapshotTest().operation) as List<MutableMap<String, Any?>>
        @Suppress("UNCHECKED_CAST") val values = operations.single()["values"] as MutableMap<String, Any?>
        values["source_type"] = "alipay"
        val rows = mapOf("file:r1" to SourceOccurrence("alipay", scope, "a".repeat(64), "fixture", "table", 0,
            emptyMap(), providerId = "provider"))
        val preparation = LocalCashImportPreviewPreparation(source) {}
        val prepared = preparation.prepare(operations, rows)
        assertEquals(setOf(1L), prepared.candidateIds)
        assertEquals(setOf(1L), prepared.context.seedIds)
        operations.single()["source_explanation"] = mapOf("txn_id" to null)
        assertEquals(emptySet(), preparation.prepare(operations, rows).candidateIds)
    }
}
