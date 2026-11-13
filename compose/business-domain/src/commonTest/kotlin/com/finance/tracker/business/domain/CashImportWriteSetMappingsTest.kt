package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CashImportWriteSetMappingsTest {
    private val empty = JsonObject(emptyMap())
    private val time = "2026-01-01T00:00:00Z"
    private fun existing(id: String) = ExistingCashWriteReferenceDto(id)
    private fun target(id: String, revision: String = "1") = CashWriteVersionedReferenceDto(existing(id), revision)
    private fun created(id: String) = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto(id), null)
    private fun group(key: String = "wallet", source: String = "alipay"): CashMappingGroupDto {
        val fields = listOf("funding-group-v1", source, "payment_method", "owner", key)
        return CashMappingGroupDto(localSourceStringDigest(fields), source, "payment_method", "owner", key)
    }
    private fun facts(state: String = "posted"): LedgerFactsDto = LedgerFactsDto(emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0").copy(
        accounts = listOf(LedgerAccountFactDto("10", "Cash", "cash", true, JsonArray(listOf(JsonPrimitive("CNY"))), "1")),
        cashTransactions = listOf(LedgerCashTransactionFactDto("1", "10", state, "import", null, "1", "atomic", "alipay", "record", empty, empty,
            null, empty, time, "-5.00", "CNY", "", "", JsonArray(emptyList()), "", null, "consumption", "not_applicable", time, null, "", "")),
        cashTransactionComponents = listOf(LedgerCashComponentFactDto("11", "1", "10", "-5.00", "CNY", "0", "", "", empty, time)),
        cashSourceBindings = listOf(LedgerSourceBindingFactDto("20", "1", "alipay", "file_local", "scope", "file_occurrence", "occurrence", "file_local", null, "1", empty, "1", time, time)),
    )
    private fun changes(source: LedgerFactsDto = facts(), decision: String = "apply", publish: Boolean = true): CashWriteSetDto {
        val r = source.cashTransactions.single(); val p = source.cashTransactionComponents.single(); val g = group(source = r.sourceType!!)
        val row = CashTransactionWriteDto(target(r.id), CashTransactionWriteValuesDto(r.accountId, r.state, r.recordOrigin, r.manualTransferId,
            r.cashGranularity, r.sourceType, r.recordId, r.sourcePayload, r.relationMetadata, r.sourceFingerprint, r.manualOverrides,
            r.occurredAt, r.amount, r.currency, r.counterparty, r.counterpartyAccount, r.counterpartyAccountAttrs, r.note, r.categoryId, r.recordType, r.recordSubtype),
            listOf(CashComponentWriteDto(existing(p.id), p.accountId, p.amount, p.currency, p.ordinal, p.label, p.sourceKey, p.metadataJson)))
        return CashWriteSetDto(transactions = listOf(row), accountMappingGroups = listOf(g),
            accountMappingRows = listOf(CashMappingRowDto(row.target, target("20"), decision,
                listOf(CashMappingSelectionDto(g.sourceAccountRef, "10")), listOf(CashMappingComponentDto("0", "10", "-5", "CNY")))),
            statementAccountMappings = if (publish) listOf(CashStatementMappingWriteDto(created("mapping"), "alipay", "funding_group_v1", g.sourceAccountRef, "10")) else emptyList())
    }
    private fun prepare(changes: CashWriteSetDto, source: LedgerFactsDto = facts()) = prepareCashImportWriteSetMappings(changes, source)
    private fun denied(changes: CashWriteSetDto, source: LedgerFactsDto = facts()) {
        assertFailsWith<CashPolicyError> { prepare(changes, source) }
    }

    @Test fun samePostedValuesHaveDifferentApplyDeferAndKeepPublication() {
        val original = changes()
        assertEquals(original.statementAccountMappings, prepare(original).statementAccountMappings)
        for (decision in listOf("defer", "keep_existing")) {
            val proof = original.accountMappingRows.single().copy(decision = decision)
            denied(original.copy(accountMappingRows = listOf(proof)))
            assertTrue(prepare(original.copy(accountMappingRows = listOf(proof), statementAccountMappings = emptyList())).statementAccountMappings.isEmpty())
        }
        assertEquals(original, changes())
    }

    @Test fun draftDeferCanPublishButFinancialOverridePresenceCannot() {
        val source = facts("draft"); val input = changes(source, "defer")
        assertEquals(input.statementAccountMappings, prepare(input, source).statementAccountMappings)
        for (field in listOf("account_id", "components", "cash_granularity")) {
            val edited = input.copy(transactions = input.transactions.map { it.copy(editedFields = listOf(field)) })
            denied(edited, source)
            assertTrue(prepare(edited.copy(statementAccountMappings = emptyList()), source).statementAccountMappings.isEmpty())
        }
        assertEquals(input.statementAccountMappings, prepare(input.copy(transactions = input.transactions.map { it.copy(editedFields = listOf("currency")) }), source).statementAccountMappings)
    }

    @Test fun postedFundingComparisonUsesNormalizedFinancialValuesAndOrdinal() {
        val input = changes(); val proof = input.accountMappingRows.single()
        assertEquals(input.statementAccountMappings, prepare(input.copy(accountMappingRows = listOf(proof.copy(
            fundingComponents = listOf(CashMappingComponentDto("0", "10", "-5.000", "CNY")))))).statementAccountMappings)
        for (part in listOf(CashMappingComponentDto("0", "10", "-4", "CNY"), CashMappingComponentDto("1", "10", "-5", "CNY"))) {
            val changed = input.copy(accountMappingRows = listOf(proof.copy(fundingComponents = listOf(part))))
            denied(changed)
            assertTrue(prepare(changed.copy(statementAccountMappings = emptyList())).statementAccountMappings.isEmpty())
        }
        val source = facts("draft")
        val draft = changes(source).copy(accountMappingRows = listOf(proof.copy(fundingComponents = emptyList())))
        assertEquals(draft.statementAccountMappings, prepare(draft, source).statementAccountMappings)
    }

    @Test fun multipleGroupsMergedToOneAccountRequireEveryEligibleMapping() {
        val input = changes(); val other = group("card")
        val second = input.statementAccountMappings.single().copy(target = created("second"), sourceAccountKey = other.sourceAccountRef)
        val complete = input.copy(accountMappingGroups = input.accountMappingGroups + other,
            accountMappingRows = input.accountMappingRows.map { it.copy(selections = it.selections + CashMappingSelectionDto(other.sourceAccountRef, "10")) },
            statementAccountMappings = input.statementAccountMappings + second)
        assertEquals(2, prepare(complete).statementAccountMappings.size)
        denied(complete.copy(statementAccountMappings = input.statementAccountMappings))
        denied(input.copy(statementAccountMappings = input.statementAccountMappings + second))
    }

    @Test fun finalCurrenciesFilterSelectionsInsteadOfCreatingUnsupportedDefaults() {
        val source = facts("draft").let { it.copy(cashTransactions = it.cashTransactions.map { r -> r.copy(currency = "USD") },
            cashTransactionComponents = it.cashTransactionComponents.map { p -> p.copy(currency = "USD") }) }
        val input = changes(source)
        denied(input, source)
        assertTrue(prepare(input.copy(statementAccountMappings = emptyList()), source).statementAccountMappings.isEmpty())
        val supported = source.copy(accounts = source.accounts.map { it.copy(currencies = JsonArray(listOf(JsonPrimitive("CNY"), JsonPrimitive("USD")))) })
        assertEquals(input.statementAccountMappings, prepare(input, supported).statementAccountMappings)
    }

    @Test fun wechatNeverPublishesAndSelectionAccountsMustBeRealAndAvailable() {
        val source = facts().let { it.copy(cashTransactions = it.cashTransactions.map { r -> r.copy(sourceType = "wechat") },
            cashSourceBindings = it.cashSourceBindings.map { b -> b.copy(sourceType = "wechat") }) }
        val input = changes(source).let { it.copy(statementAccountMappings = it.statementAccountMappings.map { m -> m.copy(sourceType = "wechat") }) }
        denied(input, source)
        assertTrue(prepare(input.copy(statementAccountMappings = emptyList()), source).statementAccountMappings.isEmpty())
        for (account in listOf(facts().accounts.single().copy(active = false), facts().accounts.single().copy(type = "security")))
            denied(changes(), facts().copy(accounts = listOf(account)))
    }

    @Test fun groupIdentityAndMappingNaturalKeyCannotBeInventedOrReassigned() {
        val input = changes(); val mapping = input.statementAccountMappings.single(); val group = input.accountMappingGroups.single()
        denied(input.copy(accountMappingGroups = listOf(group.copy(originKey = "another owner"))))
        denied(input.copy(accountMappingGroups = listOf(group, group)))
        denied(input.copy(statementAccountMappings = listOf(mapping.copy(identityKind = "legacy"))))
        denied(input.copy(statementAccountMappings = listOf(mapping.copy(accountId = null))))
        val old = LedgerStatementMappingFactDto("30", "alipay", "legacy", "legacy-key", "10", "old", "1", time, time)
        denied(input.copy(statementAccountMappings = listOf(mapping.copy(target = target("30")))), facts().copy(statementAccountMappings = listOf(old)))
        val valid = old.copy(identityKind = "funding_group_v1", sourceAccountKey = group.sourceAccountRef)
        assertEquals(target("30"), prepare(input.copy(statementAccountMappings = listOf(mapping.copy(target = target("30")))),
            facts().copy(statementAccountMappings = listOf(valid))).statementAccountMappings.single().target)
    }

    @Test fun proofsRequireCompleteUniqueOperationCoverageAndMatchingBindings() {
        val input = changes(); val proof = input.accountMappingRows.single()
        denied(input.copy(accountMappingRows = emptyList()))
        denied(input.copy(accountMappingRows = listOf(proof, proof)))
        denied(input.copy(accountMappingRows = listOf(proof.copy(decision = "unknown"))))
        denied(input.copy(accountMappingRows = listOf(proof.copy(transaction = target("1", "2")))))
        denied(input.copy(accountMappingRows = listOf(proof.copy(sourceBinding = target("20", "2")))))
        for (binding in listOf(facts().cashSourceBindings.single().copy(transactionId = "2"),
            facts().cashSourceBindings.single().copy(identityKind = "provider_id"), facts().cashSourceBindings.single().copy(sourceType = "wechat")))
            denied(input, facts().copy(cashSourceBindings = listOf(binding)))
    }

    @Test fun unresolvedDuplicateAndUnknownSelectionsCannotPublishOrDisappear() {
        val input = changes(); val proof = input.accountMappingRows.single()
        denied(input.copy(accountMappingRows = listOf(proof.copy(selections = emptyList()))))
        denied(input.copy(accountMappingRows = listOf(proof.copy(selections = proof.selections + proof.selections))))
        denied(input.copy(accountMappingRows = listOf(proof.copy(selections = listOf(proof.selections.single().copy(groupRef = "unknown"))))))
        denied(input.copy(accountMappingRows = listOf(proof.copy(selections = listOf(proof.selections.single().copy(accountId = "999"))))))
    }

    @Test fun componentTransportOrderDoesNotChangePublicationWhenOrdinalsStayFixed() {
        val base = changes(); val row = base.transactions.single(); val part = row.components.single()
        val source = facts().copy(cashTransactionComponents = listOf(
            facts().cashTransactionComponents.single().copy(amount = "-2"),
            facts().cashTransactionComponents.single().copy(id = "12", ordinal = "1", amount = "-3")))
        val components = listOf(part.copy(amount = "-2"), part.copy(reference = existing("12"), ordinal = "1", amount = "-3"))
        val proof = base.accountMappingRows.single().copy(fundingComponents = components.map {
            CashMappingComponentDto(it.ordinal, it.accountId, it.amount, it.currency) })
        for (transactionParts in listOf(components, components.reversed())) for (funding in listOf(proof.fundingComponents, proof.fundingComponents.reversed())) {
            val input = base.copy(transactions = listOf(row.copy(components = transactionParts)), accountMappingRows = listOf(proof.copy(fundingComponents = funding)))
            assertEquals(input.statementAccountMappings, prepare(input, source).statementAccountMappings)
        }
    }

    @Test fun suppressedRowsCannotHideAConflictingGlobalGroupSelection() {
        val base = changes(); val row = base.transactions.single(); val proof = base.accountMappingRows.single()
        val source = facts().let { it.copy(accounts = it.accounts + it.accounts.single().copy(id = "20"),
            cashTransactions = it.cashTransactions + it.cashTransactions.single().copy(id = "2"),
            cashTransactionComponents = it.cashTransactionComponents + it.cashTransactionComponents.single().copy(id = "21", cashTransactionId = "2"),
            cashSourceBindings = it.cashSourceBindings + it.cashSourceBindings.single().copy(id = "22", transactionId = "2", identityKey = "second")) }
        val second = row.copy(target = target("2"), components = row.components.map { it.copy(reference = existing("21")) })
        for (decision in listOf("defer", "keep_existing")) {
            val observed = proof.copy(transaction = second.target, sourceBinding = target("22"), decision = decision)
            val input = base.copy(transactions = base.transactions + second, accountMappingRows = base.accountMappingRows + observed)
            assertEquals(base.statementAccountMappings, prepare(input, source).statementAccountMappings)
            denied(input.copy(accountMappingRows = base.accountMappingRows + observed.copy(
                selections = listOf(proof.selections.single().copy(accountId = "20")))), source)
        }
    }

    @Test fun newSourceAndBindingReferencesStayDistinctFromExistingDependencies() {
        val base = changes(); val original = base.transactions.single()
        val row = original.copy(target = created("new-row"), components = original.components.map { it.copy(reference = NewCashWriteReferenceDto("new-part")) })
        val binding = CashSourceBindingWriteDto(created("new-binding"), row.target.reference, "alipay", "file_local", "new-scope", "file_occurrence",
            "new-occurrence", "file_local", null, "1", empty)
        val input = base.copy(transactions = listOf(row), sourceBindings = listOf(binding), accountMappingRows = base.accountMappingRows.map {
            it.copy(transaction = row.target, sourceBinding = binding.target, fundingComponents = emptyList()) })
        assertEquals(input, prepare(input))
        denied(input.copy(accountMappingRows = input.accountMappingRows.map { it.copy(sourceBinding = created("missing-binding")) }))
    }

    @Test fun everyFinalCurrencyForASelectedAccountMustBeSupported() {
        val source = facts("draft"); val base = changes(source); val row = base.transactions.single(); val part = row.components.single()
        val input = base.copy(transactions = listOf(row.copy(values = row.values.copy(amount = null, currency = null, accountId = null, cashGranularity = "aggregate"),
            components = listOf(part.copy(amount = "-2"), part.copy(reference = NewCashWriteReferenceDto("usd"), ordinal = "1", amount = "-3", currency = "USD")))))
        denied(input, source)
        assertTrue(prepare(input.copy(statementAccountMappings = emptyList()), source).statementAccountMappings.isEmpty())
        for (type in listOf("cash", "loan", "lend")) {
            val supported = source.copy(accounts = source.accounts.map { it.copy(type = type, currencies = JsonArray(listOf(JsonPrimitive("CNY"), JsonPrimitive("USD")))) })
            assertEquals(input.statementAccountMappings, prepare(input, supported).statementAccountMappings)
        }
    }
}
