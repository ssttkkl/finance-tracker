package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CashImportWriteSetScopeTest {
    private val empty = JsonObject(emptyMap())
    private val array = JsonArray(emptyList())
    private val time = "2026-01-01T00:00:00Z"
    private fun ref(id: String) = ExistingCashWriteReferenceDto(id)
    private fun target(id: String) = CashWriteVersionedReferenceDto(ref(id), "1")
    private fun fresh(id: String) = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto(id), null)
    private fun transaction(id: String) = LedgerCashTransactionFactDto(id, "10", "posted", "import", null, "1", "atomic", "bank",
        "record:$id", empty, empty, null, empty, time, "-5", "CNY", "", "", array, "", null, "consumption", "not_applicable", time, null, "", "")
    private fun facts(): LedgerFactsDto = LedgerFactsDto(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0").copy(
        accounts = listOf(LedgerAccountFactDto("10", "cash", "cash", true, JsonArray(listOf(JsonPrimitive("CNY"))), "1")),
        cashTransactions = listOf(transaction("1"), transaction("2")),
        cashTransactionComponents = listOf("1", "2").map { LedgerCashComponentFactDto("$it$it", it, "10", "-5", "CNY", "0", "", "", empty, time) },
        cashTransactionIssues = listOf(LedgerCashIssueFactDto("41", "1", "pending", "source_update_pending", null, empty, empty, empty, "1", time, time)),
        cashEntries = listOf(LedgerCashEntryFactDto("entry", "1", "1", "1", null, "", "", "", array, array, null)),
        cashSourceBindings = listOf(LedgerSourceBindingFactDto("51", "1", "bank", "account", "scope", "provider_id", "provider", "strong", null, "1", empty, "1", time, time)),
    )
    private fun row() = CashTransactionWriteDto(target("1"), CashTransactionWriteValuesDto("10", "posted", "import", null, "atomic", "bank", "record:1",
        empty, empty, null, empty, time, "-5", "CNY", "", "", array, "edited", null, "consumption", "not_applicable"),
        listOf(CashComponentWriteDto(ref("11"), "10", "-5", "CNY", "0", "", "", empty)))
    private fun binding(owner: String) = CashSourceBindingWriteDto(target("51"), ref(owner), "bank", "account", "scope", "provider_id", "provider", "strong", null, "1", empty)
    private fun relation() = CashRelationWriteDto(fresh("edge"), "payment_mirror", target("1"), target("2"), ref("11"), ref("22"),
        target("1"), ref("11"), "expense", "expense", "confirmed", true, null, "rule", empty, "5", "rule", "", emptyList())
    private fun relationChanges(row: CashRelationWriteDto) = CashWriteSetDto(relations = listOf(row),
        relationGroups = listOf(CashRelationWriteGroupDto(listOf(row.target.reference))))
    private fun denied(changes: CashWriteSetDto, source: LedgerFactsDto = facts(), path: String) {
        val failure = assertFailsWith<CashPolicyError> { validateCashImportWriteSetScope(changes, source) }
        assertEquals("field_not_editable", failure.code)
        assertEquals(listOf(path), failure.fields)
    }

    @Test fun fileImportsCannotExecuteStandaloneMaintenanceCommands() {
        denied(CashWriteSetDto(deletedTransactions = listOf(CashTransactionDeleteDto(target("1"), "delete"))), path = "deleted_transactions")
        for (action in listOf(ApplySourceIssueResolutionDto(target("41")), KeepExistingIssueResolutionDto(target("41")),
            CreateIndependentIssueResolutionDto(target("41")), MergeIntoIssueResolutionDto(target("41"), target("2")))) {
            denied(CashWriteSetDto(issueResolutions = listOf(action)), path = "issue_resolutions")
        }
        for (inherit in listOf(false, true)) denied(CashWriteSetDto(entrySplitChoices = listOf(CashEntrySplitChoiceDto(target("entry"), inherit))), path = "entry_split_choices")
        validateCashImportWriteSetScope(CashWriteSetDto(resolvedIssues = listOf(target("41"))), facts())
    }

    @Test fun onlyWrittenParentsNeedTheLiveImportedRecordQualification() {
        for (origin in listOf("import", "legacy")) for (state in listOf("draft", "posted")) {
            val source = facts().copy(cashTransactions = facts().cashTransactions.map { it.copy(recordOrigin = origin, state = state) })
            val changes = CashWriteSetDto(transactions = listOf(row().let { it.copy(values = it.values.copy(recordOrigin = origin, state = state)) }))
            validateCashImportWriteSetScope(changes, source)
            assertEquals("edited", changes.transactions.single().values.note)
            assertEquals("", source.cashTransactions.first().note)
        }
        for (origin in listOf("manual", "manual_transfer")) {
            val source = facts().copy(cashTransactions = facts().cashTransactions.map { it.copy(recordOrigin = origin) })
            denied(CashWriteSetDto(transactions = listOf(row().let { it.copy(values = it.values.copy(recordOrigin = origin)) })), source, "transactions[0].target")
            validateCashImportWriteSetScope(relationChanges(relation()), source)
        }
        val deleted = facts().copy(cashTransactions = facts().cashTransactions.map { it.copy(deletedAt = time) })
        denied(CashWriteSetDto(transactions = listOf(row())), deleted, "transactions[0].target")
    }

    @Test fun fileRelationDecisionsCreateNewSuggestionsRatherThanRewriteStoredEdges() {
        for (state in listOf("draft", "confirmed", "ignored")) {
            val source = facts().copy(transactionRelations = listOf(LedgerCashRelationFactDto("31", "payment_mirror", "1", "2", "11", "22", "1", "11",
                "expense", "expense", state, true, "1", null, "rule", empty, "5", "rule", "user", time, "user", time, "")))
            val changes = relationChanges(relation().copy(state = state))
            validateCashImportWriteSetScope(changes, source)
            denied(changes.copy(relations = changes.relations.map { it.copy(target = target("31")) }), source, "relations[0].target")
        }
    }

    @Test fun bindingsCannotMoveBetweenLiveParentsButDeletedOwnerReuseIsPreserved() {
        validateCashImportWriteSetScope(CashWriteSetDto(sourceBindings = listOf(binding("1"))), facts())
        denied(CashWriteSetDto(sourceBindings = listOf(binding("2"))), path = "source_bindings[0].transaction")
        val deleted = facts().copy(cashTransactions = facts().cashTransactions.map { if (it.id == "1") it.copy(deletedAt = time) else it })
        validateCashImportWriteSetScope(CashWriteSetDto(sourceBindings = listOf(binding("2"))), deleted)
        val created = binding("2").copy(target = fresh("new-binding"), identityKey = "other-provider")
        validateCashImportWriteSetScope(CashWriteSetDto(sourceBindings = listOf(created)), facts())
        assertEquals("1", deleted.cashSourceBindings.single().transactionId)
        assertEquals("2", binding("2").let { (it.transaction as ExistingCashWriteReferenceDto).id })
    }

    @Test fun referencesAndOriginalFixedFinancialChecksStillPrecedeTheScopeGate() {
        val stale = assertFailsWith<CashWriteSetReferenceFailure> {
            validateCashImportWriteSetScope(CashWriteSetDto(transactions = listOf(row().copy(target = target("missing")))), facts())
        }
        assertEquals("reference_changed", stale.code)
        val changed = row().let { it.copy(values = it.values.copy(recordType = "fee")) }
        val fixed = assertFailsWith<CashPolicyError> { validateCashImportWriteSetScope(CashWriteSetDto(transactions = listOf(changed)), facts()) }
        assertEquals("fixed_field_conflict", fixed.code)
        assertContains(fixed.fields, "transactions[0].values.record_type")
    }

    @Test fun newImportRowsUseTheExistingCreationPolicyWithoutClaimingManualAuthority() {
        val created = row().let { it.copy(target = fresh("parent"), values = it.values.copy(recordId = "new"),
            components = it.components.map { part -> part.copy(reference = NewCashWriteReferenceDto("part")) }) }
        validateCashImportWriteSetScope(CashWriteSetDto(transactions = listOf(created)), facts())
        val failure = assertFailsWith<CashPolicyError> {
            validateCashImportWriteSetScope(CashWriteSetDto(transactions = listOf(created.copy(values = created.values.copy(recordOrigin = "manual")))), facts())
        }
        assertEquals("fixed_field_conflict", failure.code)
        assertEquals(listOf("transactions[0].values.record_origin"), failure.fields)
    }
}
