package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CashWriteSetConfirmedGraphTest {
    private val empty = JsonObject(emptyMap())
    private val array = JsonArray(emptyList())
    private val time = "2026-01-01T00:00:00Z"
    private fun existing(id: String) = ExistingCashWriteReferenceDto(id)
    private fun target(id: String) = CashWriteVersionedReferenceDto(existing(id), "1")
    private fun created(id: String) = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto(id), null)
    private fun transaction(id: String, amount: String = "-5", account: String = "10", type: String = "consumption") =
        LedgerCashTransactionFactDto(id, account, "posted", "legacy", null, "1", "atomic", "bank", "record:$id", empty, empty,
            null, empty, time, amount, "CNY", "", "", array, "", null, type, defaultCashRecordSubtype(type), time, null, "", "")
    private fun part(id: String, parent: String, amount: String = "-5", account: String = "10") =
        LedgerCashComponentFactDto(id, parent, account, amount, "CNY", "0", "", "", empty, time)
    private fun relation(id: String = "31", primary: String = "1", secondary: String = "2", firstPart: String = "11", secondPart: String = "22",
        kind: String = "payment_mirror", amount: String = "5", firstRole: String = "expense", secondRole: String = "expense") =
        LedgerCashRelationFactDto(id, kind, primary, secondary, firstPart, secondPart, primary, firstPart, firstRole, secondRole,
            "confirmed", true, "1", null, "user", empty, amount, "rule", "user", time, "user", time, "")
    private fun facts() = LedgerFactsDto(
        listOf("10", "20").map { LedgerAccountFactDto(it, it, "cash", true, JsonArray(listOf(JsonPrimitive("CNY"))), "1") },
        emptyList(), emptyList(), emptyList(), listOf(transaction("1"), transaction("2")),
        listOf(part("11", "1"), part("22", "2")), emptyList(), emptyList(), listOf(relation()), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0")
    private fun write(id: String, value: LedgerCashTransactionFactDto = transaction(id)) = CashTransactionWriteDto(target(id),
        CashTransactionWriteValuesDto(value.accountId, value.state, value.recordOrigin, value.manualTransferId, value.cashGranularity,
            value.sourceType, value.recordId, value.sourcePayload, value.relationMetadata, value.sourceFingerprint, value.manualOverrides,
            value.occurredAt, value.amount, value.currency, value.counterparty, value.counterpartyAccount, value.counterpartyAccountAttrs,
            value.note, value.categoryId, value.recordType, value.recordSubtype), listOf(CashComponentWriteDto(existing(if (id == "1") "11" else "22"),
            value.accountId, value.amount, value.currency, "0", "", "", empty)))
    private fun write(edge: LedgerCashRelationFactDto = relation()) = CashRelationWriteDto(target(edge.id), edge.kind,
        target(edge.primaryTransactionId), edge.secondaryTransactionId?.let(::target), edge.primaryComponentId?.let(::existing),
        edge.secondaryComponentId?.let(::existing), target(edge.anchorTransactionId), edge.anchorComponentId?.let(::existing),
        edge.primaryRole, edge.secondaryRole, edge.state, edge.active, edge.decisionGroupId, edge.decisionSource, edge.evidence,
        edge.appliedAmount, edge.ruleId, edge.decisionReason, emptyList())
    private fun invalid(changes: CashWriteSetDto, source: LedgerFactsDto = facts(), code: String? = null) {
        val failure = assertFailsWith<CashRelationFailure> { validateCashWriteSetConfirmedGraph(changes, source) }
        if (code != null) assertEquals(code, failure.code)
    }

    @Test fun finalGraphRetainsUnchangedConfirmedRelationsAndNeverMutatesFacts() {
        val source = facts()
        validateCashWriteSetConfirmedGraph(CashWriteSetDto(), source)
        validateCashWriteSetConfirmedGraph(CashWriteSetDto(relations = listOf(write())), source)
        assertEquals(listOf(relation()), source.transactionRelations)
        invalid(CashWriteSetDto(transactions = listOf(write("1", transaction("1", "-6")))), code = "relation_amount_invalid")
        invalid(CashWriteSetDto(deletedTransactions = listOf(CashTransactionDeleteDto(target("1"), "delete"))), code = "relation_endpoint_incomplete")
        invalid(CashWriteSetDto(transactions = listOf(write("1", transaction("1").copy(state = "draft")))), code = "relation_endpoint_incomplete")
    }

    @Test fun replacingComponentsRequiresTheOldConfirmedEdgeToBeExplicitlyReplacedOrDisabled() {
        val row = write("1")
        val replacement = row.copy(components = row.components.map { it.copy(reference = NewCashWriteReferenceDto("replacement")) })
        invalid(CashWriteSetDto(transactions = listOf(replacement)), code = "relation_endpoint_incomplete")
        val disabled = write().copy(active = false)
        validateCashWriteSetConfirmedGraph(CashWriteSetDto(transactions = listOf(replacement), relations = listOf(disabled.copy(
            primaryComponent = NewCashWriteReferenceDto("replacement"), anchorComponent = NewCashWriteReferenceDto("replacement")))), facts())
        val changed = write().copy(primaryComponent = NewCashWriteReferenceDto("replacement"), anchorComponent = NewCashWriteReferenceDto("replacement"))
        validateCashWriteSetConfirmedGraph(CashWriteSetDto(transactions = listOf(replacement), relations = listOf(changed)), facts())
    }

    @Test fun newOpaqueReferencesRemainDistinctFromExistingIdsAndNeverNarrowToLong() {
        val input = facts().copy(transactionRelations = emptyList())
        val row = write("1").copy(target = created("1"), values = write("1").values.copy(recordId = "record:new"),
            components = listOf(write("1").components.single().copy(reference = NewCashWriteReferenceDto("11"))))
        val edge = write().copy(target = created("31"), primaryTransaction = created("1"), anchorTransaction = created("1"),
            primaryComponent = NewCashWriteReferenceDto("11"), anchorComponent = NewCashWriteReferenceDto("11"))
        validateCashWriteSetConfirmedGraph(CashWriteSetDto(transactions = listOf(row), relations = listOf(edge)), input)
        val large = "9223372036854775807"
        val largeFacts = input.copy(cashTransactions = input.cashTransactions.map { if (it.id == "1") it.copy(id = large) else it },
            cashTransactionComponents = input.cashTransactionComponents.map { if (it.id == "11") it.copy(cashTransactionId = large) else it })
        validateCashWriteSetConfirmedGraph(CashWriteSetDto(relations = listOf(write().copy(target = created("edge"),
            primaryTransaction = target(large), anchorTransaction = target(large)))), largeFacts)
    }

    @Test fun cumulativeRefundsIncludeExistingEdgesAndRejectOnlyTheNewOverspend() {
        val expense = transaction("1", "-100")
        val refund = transaction("2", "75", "10", "refund")
        val incoming = transaction("3", "50", "10", "refund")
        val old = relation(kind = "refund_offset", amount = "75", firstRole = "expense", secondRole = "refund")
        val source = facts().copy(cashTransactions = listOf(expense, refund, incoming),
            cashTransactionComponents = listOf(part("11", "1", "-100"), part("22", "2", "75"), part("33", "3", "50")),
            transactionRelations = listOf(old))
        validateCashWriteSetConfirmedGraph(CashWriteSetDto(), source)
        val extra = write(relation("32", "1", "3", "11", "33", "refund_offset", "50", "expense", "refund"))
            .copy(target = created("new-refund"))
        invalid(CashWriteSetDto(relations = listOf(extra)), source, "relation_refund_exceeds_remaining")
        val smaller = source.copy(cashTransactions = source.cashTransactions.map { if (it.id == "3") it.copy(amount = "25") else it },
            cashTransactionComponents = source.cashTransactionComponents.map { if (it.id == "33") it.copy(amount = "25") else it })
        validateCashWriteSetConfirmedGraph(CashWriteSetDto(relations = listOf(extra.copy(appliedAmount = "25"))), smaller)
    }

    @Test fun financialAmountsAloneCannotAuthorizeWrongRolesDuplicateEdgesOrFundingOccupancy() {
        invalid(CashWriteSetDto(relations = listOf(write().copy(primaryRole = "refund"))), code = "relation_role_incompatible")
        invalid(CashWriteSetDto(relations = listOf(write().copy(target = created("duplicate")))), code = "relation_data_invalid")
        invalid(CashWriteSetDto(relations = listOf(write().copy(appliedAmount = "-1"))), code = "relation_amount_invalid")
        val event = LedgerFundingEventFactDto("20", "CNY", null, "5")
        val funding = LedgerInvestmentFundingFactDto("41", "11", "42", "cash_to_investment", "accepted", "fixture", empty,
            "active", "user", time, "user", time, "", event)
        invalid(CashWriteSetDto(), facts().copy(cashInvestmentFundingRelations = listOf(funding)), "relation_endpoint_occupied")
        val source = facts().copy(transactionRelations = emptyList(), cashInvestmentFundingRelations = listOf(funding))
        validateCashWriteSetConfirmedGraph(CashWriteSetDto(), source)
        val row = write("1")
        invalid(CashWriteSetDto(transactions = listOf(row.copy(components = row.components.map {
            it.copy(reference = NewCashWriteReferenceDto("replacement"))
        }))), source, "relation_endpoint_incomplete")
    }

    @Test fun confirmedTransferOccupancyIncludesUnchangedStoredEdgesAndExplicitRelease() {
        val old = relation(kind = "transfer_pair", amount = "0", firstRole = "outgoing", secondRole = "incoming")
        val source = facts().copy(cashTransactions = listOf(transaction("1", "-5", "10", "transfer_out"),
            transaction("2", "5", "20", "transfer_in"), transaction("3", "5", "20", "transfer_in")),
            cashTransactionComponents = listOf(part("11", "1"), part("22", "2", "5", "20"), part("33", "3", "5", "20")),
            transactionRelations = listOf(old))
        validateCashWriteSetConfirmedGraph(CashWriteSetDto(), source)
        val extra = write(relation("32", "1", "3", "11", "33", "transfer_pair", "0", "outgoing", "incoming"))
            .copy(target = created("replacement"))
        invalid(CashWriteSetDto(relations = listOf(extra)), source, "relation_endpoint_occupied")
        validateCashWriteSetConfirmedGraph(CashWriteSetDto(relations = listOf(write(old).copy(active = false), extra)), source)
    }

    @Test fun storedZeroDefaultAndInactiveEdgesKeepTheirExistingReadSemantics() {
        validateCashWriteSetConfirmedGraph(CashWriteSetDto(), facts().copy(transactionRelations = listOf(relation(amount = "0"))))
        val source = facts().copy(transactionRelations = listOf(relation().copy(active = false)))
        validateCashWriteSetConfirmedGraph(CashWriteSetDto(transactions = listOf(write("1", transaction("1", "-6")))), source)
        validateCashWriteSetConfirmedGraph(CashWriteSetDto(), facts().copy(transactionRelations = listOf(relation().copy(state = "ignored"))))
    }

    @Test fun anExplicitZeroInThisWriteSetIsNeverReplacedWithADefaultAllocation() {
        val mirror = write().copy(appliedAmount = "0")
        invalid(CashWriteSetDto(relations = listOf(mirror)), code = "relation_amount_invalid")
        invalid(CashWriteSetDto(relations = listOf(mirror.copy(target = created("mirror")))),
            facts().copy(transactionRelations = emptyList()), "relation_amount_invalid")
        val old = relation(kind = "refund_offset", amount = "5", firstRole = "expense", secondRole = "refund")
        val source = facts().copy(cashTransactions = listOf(transaction("1"), transaction("2", "5", type = "refund")),
            cashTransactionComponents = listOf(part("11", "1"), part("22", "2", "5")), transactionRelations = listOf(old))
        invalid(CashWriteSetDto(relations = listOf(write(old).copy(appliedAmount = "0"))), source, "relation_amount_invalid")
        val zero = source.copy(cashTransactions = source.cashTransactions.map { it.copy(amount = "0") },
            cashTransactionComponents = source.cashTransactionComponents.map { it.copy(amount = "0") })
        validateCashWriteSetConfirmedGraph(CashWriteSetDto(relations = listOf(write(old).copy(appliedAmount = "0"))), zero)
    }

    @Test fun importedGraphMembersUseTheFinalSourceBindingsWithoutInventingSourceIdentities() {
        val row = write("1").copy(target = created("row"), values = write("1").values.copy(recordOrigin = "import",
            recordId = "import-v1:new", sourceType = "wechat"), components = listOf(write("1").components.single().copy(reference = NewCashWriteReferenceDto("part"))))
        val edge = write().copy(target = created("edge"), primaryTransaction = created("row"), anchorTransaction = created("row"),
            primaryComponent = NewCashWriteReferenceDto("part"), anchorComponent = NewCashWriteReferenceDto("part"))
        val source = facts().copy(transactionRelations = emptyList())
        val changes = CashWriteSetDto(transactions = listOf(row), relations = listOf(edge))
        invalid(changes, source, "relation_graph_invalid")
        val binding = CashSourceBindingWriteDto(created("binding"), NewCashWriteReferenceDto("row"), "wechat", "account", "scope",
            "provider_id", "transaction-id", "strong", null, "1", empty)
        validateCashWriteSetConfirmedGraph(changes.copy(sourceBindings = listOf(binding)), source)
        assertEquals(emptyList(), source.cashSourceBindings)
    }
}
