package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CashWriteSetSourceAliasesTest {
    private val empty = JsonObject(emptyMap())
    private val array = JsonArray(emptyList())
    private val time = "2026-01-01T00:00:00Z"
    private fun old(id: String) = ExistingCashWriteReferenceDto(id)
    private fun new(id: String) = NewCashWriteReferenceDto(id)
    private fun observed(id: String, revision: String = "1") = CashWriteVersionedReferenceDto(old(id), revision)
    private fun created(id: String) = CashWriteVersionedReferenceDto(new(id), null)
    private fun parent(id: String) = LedgerCashTransactionFactDto(id, "10", "posted", "legacy", null, "1", "atomic", "bank", "row:$id",
        empty, empty, null, empty, time, "-5", "CNY", "", "", array, "", null, "consumption", "not_applicable", time, null, "", "")
    private fun part(id: String) = LedgerCashComponentFactDto("$id$id", id, "10", "-5", "CNY", "0", "", "", empty, time)
    private fun binding() = LedgerSourceBindingFactDto("41", "1", "bank", "account", "scope", "provider_id", "key", "strong", null, "v1", empty, "1", time, time)
    private fun facts() = LedgerFactsDto(listOf(LedgerAccountFactDto("10", "cash", "cash", true, JsonArray(listOf(JsonPrimitive("CNY"))), "1")),
        emptyList(), emptyList(), emptyList(), listOf(parent("1"), parent("2")), listOf(part("1"), part("2")), listOf(binding()), emptyList(),
        listOf(LedgerCashRelationFactDto("31", "payment_mirror", "1", "2", "11", "22", "1", "11", "expense", "expense", "confirmed", true,
            "1", null, "user", empty, "5", "rule", "user", time, "user", time, "")), emptyList(), emptyList(), emptyList(),
        listOf(LedgerCashEntryFactDto("71", "1", "1", "1", null, "", "", "", array, array, null)),
        listOf("1", "2").map { LedgerCashEntryMemberFactDto(it, "71", empty, array) }, emptyList(), "0")
    private fun row() = CashTransactionWriteDto(created("source"), CashTransactionWriteValuesDto("10", "posted", "import", null, "atomic", "bank", "row:1",
        empty, empty, null, empty, time, "-5", "CNY", "", "", array, "", null, "consumption", "not_applicable"),
        listOf(CashComponentWriteDto(new("part"), "10", "-5", "CNY", "0", "", "", empty)))
    private fun writeBinding() = binding().let { CashSourceBindingWriteDto(created("binding"), new("source"), it.sourceType, it.sourceScopeKind,
        it.sourceScopeKey, it.identityKind, it.identityKey, it.identityStrength, it.stableEvidenceKey, it.strategyVersion, it.coverage) }
    private fun entry() = CashEntryWriteDto(created("source"), null, "", "", "", array, emptyList(),
        listOf(CashEntryMemberWriteDto(created("source"), empty, emptyList()), CashEntryMemberWriteDto(observed("2"), empty, emptyList())))
    private fun changes() = CashWriteSetDto(transactions = listOf(row()), sourceBindings = listOf(writeBinding()), entries = listOf(entry()), entryDependencies = listOf(observed("71")))
    private fun aliases() = CashWriteSetSourceAliases(mapOf(new("source") to "1"), mapOf(new("part") to "11"), mapOf(new("binding") to "41"))
    private fun failure(changes: CashWriteSetDto, path: String? = null, code: String = "invalid_reference") {
        val error = assertFailsWith<CashWriteSetReferenceFailure> { validateCashWriteSetReferences(changes, facts(), aliases()) }
        assertEquals(code, error.code)
        if (path != null) assertEquals(path, error.fieldPath)
    }

    @Test fun actualSourceAliasesPassWithoutChangingTheOriginalDtoOrItsNullRevisions() {
        val changes = changes(); val original = changes.copy()
        validateCashWriteSetReferences(changes, facts(), aliases())
        val graph = buildCashWriteSetConfirmedGraph(changes, facts(), aliases())
        assertEquals("1", graph.parents.text(new("source")))
        assertEquals(1, graph.projections.size)
        assertEquals(setOf("1", "2"), graph.projections.single().memberIds.mapTo(mutableSetOf()) { it.toString() })
        assertEquals(listOf("71"), validateCashWriteSetEntryTopology(changes, facts(), aliases()).map { it.id })
        assertEquals(original, changes)
        assertNull(changes.transactions.single().target.expectedRevision)
        assertNull(changes.entries.single().members.first().transaction.expectedRevision)
    }

    @Test fun oldEntryDependenciesAndObservedEditTargetsRemainRequiredForAliasedSources() {
        failure(changes().copy(entryDependencies = emptyList()), "entry_dependencies")
        failure(changes().copy(entryDependencies = listOf(observed("71", "2"))), "entry_dependencies[0]", "reference_changed")
        val edit = CashEntryBeforeRelationEditDto(null, empty)
        failure(changes().copy(transactions = listOf(row().copy(entryEdit = edit))), "transactions[0].entry_edit.entry", "reference_changed")
        validateCashWriteSetReferences(changes().copy(transactions = listOf(row().copy(entryEdit = edit.copy(entry = observed("71"))))), facts(), aliases())
    }

    @Test fun aliasedSourceCannotBeDeclaredAgainAsAnExistingWriteTarget() {
        failure(changes().copy(transactions = listOf(row(), row().copy(target = observed("1"), components = emptyList()))), "transactions[1].target", "duplicate_reference")
    }

    @Test fun existingAndNewReferencesToOneMemberCannotEvadeDuplicateDetection() {
        val duplicate = entry().copy(members = entry().members + CashEntryMemberWriteDto(observed("1"), empty, emptyList()))
        failure(changes().copy(entries = listOf(duplicate)), "entries[0].members[2].transaction", "duplicate_reference")
        val issue = CashIssueWriteDto(created("issue"), observed("2"), "kind", "kind", null, empty, empty, empty, listOf(created("source"), observed("1")))
        failure(changes().copy(issues = listOf(issue)), "issues[0].candidates[1]", "duplicate_reference")
    }

    @Test fun componentOwnershipAndExistingDependencyRevisionsStillUseActualParents() {
        val relation = CashRelationWriteDto(created("edge"), "payment_mirror", created("source"), observed("2", "2"), new("part"), old("22"),
            created("source"), new("part"), "expense", "expense", "draft", true, null, "user", empty, "5", "rule", "user", emptyList())
        failure(changes().copy(relations = listOf(relation)), "relations[0].secondary_transaction", "reference_changed")
        failure(changes().copy(relations = listOf(relation.copy(secondaryTransaction = observed("2"), secondaryComponent = new("part")))), "relations[0].secondary_component")
        validateCashWriteSetReferences(changes().copy(relations = listOf(relation.copy(secondaryTransaction = observed("2"), primaryComponent = old("11")))), facts(), aliases())
    }

    @Test fun mappedBindingReplacesOnlyItsNaturalKeyAndCannotConcealAnotherClaim() {
        val changedKey = writeBinding().copy(identityKey = "other")
        val duplicate = writeBinding().copy(target = created("second-binding"))
        failure(changes().copy(sourceBindings = listOf(writeBinding(), duplicate)), "source_bindings[1]", "duplicate_reference")
        assertFailsWith<IllegalStateException> { validateCashWriteSetReferences(changes().copy(sourceBindings = listOf(changedKey)), facts(), aliases()) }
    }

    @Test fun incompleteGroupAndWrongRootAreRejectedAfterActualIdentityCoalescing() {
        for (bad in listOf(entry().copy(rootTransaction = observed("2")), entry().copy(members = entry().members.take(1)))) {
            val failure = assertFailsWith<CashPolicyError> { validateCashWriteSetEntryTopology(changes().copy(entries = listOf(bad)), facts(), aliases()) }
            assertEquals("entry_result_invalid", failure.code)
        }
    }

    @Test fun aliasMappingsAreTrustedInternalInputsWithRealDeclarationsAndOwners() {
        val invalid = listOf(CashWriteSetSourceAliases(mapOf(new("missing") to "1")),
            CashWriteSetSourceAliases(mapOf(new("source") to "999")),
            CashWriteSetSourceAliases(mapOf(new("source") to "1"), mapOf(new("part") to "22")))
        for (mapping in invalid) assertFailsWith<IllegalStateException> { validateCashWriteSetReferences(changes(), facts(), mapping) }
    }
    @Test fun identicalOpaqueTextInDifferentEntityKindsDoesNotShareAnIdentityScope() {
        val binding = writeBinding().copy(target = created("source"))
        val relation = CashRelationWriteDto(created("source"), "payment_mirror", created("source"), observed("2"), new("part"), old("22"),
            created("source"), new("part"), "expense", "expense", "draft", true, null, "user", empty, "5", "rule", "user", emptyList())
        val changes = changes().copy(sourceBindings = listOf(binding), relations = listOf(relation))
        val aliases = CashWriteSetSourceAliases(mapOf(new("source") to "1"), mapOf(new("part") to "11"), mapOf(new("source") to "41"))
        validateCashWriteSetReferences(changes, facts(), aliases)
        assertEquals(listOf("71"), validateCashWriteSetEntryTopology(changes, facts(), aliases).map { it.id })
    }

    @Test fun unchangedInvestmentFundingStillOwnsTheActualAliasedComponent() {
        val funding = LedgerInvestmentFundingFactDto("81", "11", "82", "cash_to_investment", "accepted", "fixture", empty,
            "active", "user", time, "user", time, "", LedgerFundingEventFactDto("20", "CNY", null, "5"))
        val source = facts().copy(transactionRelations = emptyList(), cashInvestmentFundingRelations = listOf(funding))
        validateCashWriteSetConfirmedGraph(changes(), source, aliases())
        val failure = assertFailsWith<CashRelationFailure> { validateCashWriteSetConfirmedGraph(changes(), facts().copy(cashInvestmentFundingRelations = listOf(funding)), aliases()) }
        assertEquals("relation_endpoint_occupied", failure.code)
    }

    @Test fun anAliasedDraftProducesOneSingletonAndRequiresItsActualOldEntry() {
        val source = facts().copy(cashTransactions = listOf(parent("1").copy(state = "draft", amount = null, currency = null, occurredAt = null)),
            cashTransactionComponents = emptyList(), transactionRelations = emptyList(), cashEntryMembers = facts().cashEntryMembers.take(1))
        val row = row().copy(values = row().values.copy(state = "draft", amount = null, currency = null, occurredAt = null), components = emptyList())
        val changes = changes().copy(transactions = listOf(row), entries = listOf(entry().copy(members = entry().members.take(1))))
        val aliases = CashWriteSetSourceAliases(mapOf(new("source") to "1"), bindings = mapOf(new("binding") to "41"))
        assertEquals(listOf("71"), validateCashWriteSetEntryTopology(changes, source, aliases).map { it.id })
        val omitted = assertFailsWith<CashWriteSetReferenceFailure> { validateCashWriteSetEntryTopology(changes.copy(entryDependencies = emptyList()), source, aliases) }
        assertEquals("entry_dependencies", omitted.fieldPath)
    }

    @Test fun candidateDuplicatesAreComparedByActualParentAndComponentTogether() {
        val relation = CashRelationWriteDto(created("edge"), "payment_mirror", observed("2"), null, old("22"), null,
            observed("2"), old("22"), "expense", null, "draft", true, null, "user", empty, "0", "rule", "user",
            listOf(CashRelationCandidateWriteDto(created("source"), new("part"), empty, "valid"),
                CashRelationCandidateWriteDto(observed("1"), old("11"), empty, "valid")))
        failure(changes().copy(relations = listOf(relation)), "relations[0].candidates[1]", "duplicate_reference")
    }

    @Test fun unmatchedNewReferenceWhoseTextIsAnExistingIdKeepsItsSeparateComputationIdentity() {
        val newRow = row().copy(target = created("1"), components = row().components.map { it.copy(reference = new("11")) })
        val changed = changes().copy(transactions = listOf(row(), newRow))
        val graph = buildCashWriteSetConfirmedGraph(changed, facts(), aliases())
        assertEquals("1", graph.parents.text(new("source")))
        assertNotEquals("1", graph.parents.text(new("1")))
        assertTrue(graph.parents.text(new("1")).length > 19)
        assertEquals(2, graph.projections.size)
    }

    @Test fun sourceOrderAndCompleteGraphMatchTheSameOriginalStoredFacts() {
        val source = facts().copy(cashSourceBindings = emptyList())
        val changes = changes().copy(sourceBindings = emptyList())
        val aliases = CashWriteSetSourceAliases(mapOf(new("source") to "1"), mapOf(new("part") to "11"))
        val baseline = buildCashWriteSetConfirmedGraph(CashWriteSetDto(), source)
        val resolved = buildCashWriteSetConfirmedGraph(changes, source, aliases)
        assertEquals(baseline.projections, resolved.projections)
        val bad = row().copy(values = row().values.copy(amount = "-6"), components = row().components.map { it.copy(amount = "-6") })
        val failure = assertFailsWith<CashRelationFailure> { validateCashWriteSetConfirmedGraph(changes.copy(transactions = listOf(bad)), source, aliases) }
        assertEquals("relation_amount_invalid", failure.code)
    }

    @Test fun sourceIdentityMapsSnapshotTheirInputAndNeverInventAnObservedRevision() {
        val parents = mutableMapOf(new("source") to "1")
        val parts = mutableMapOf(new("part") to "11")
        val bindings = mutableMapOf(new("binding") to "41")
        val aliases = CashWriteSetSourceAliases(parents, parts, bindings)
        parents.clear(); parts.clear(); bindings.clear()
        validateCashWriteSetReferences(changes(), facts(), aliases)
        assertEquals("1", buildCashWriteSetConfirmedGraph(changes(), facts(), aliases).parents.text(new("source")))
        val row = row().copy(target = row().target.copy(expectedRevision = "1"))
        failure(changes().copy(transactions = listOf(row)), "transactions[0].target")
    }

    private fun importFacts() = facts().copy(cashTransactions = facts().cashTransactions.map { it.copy(sourceType = "ccb_debit") },
        cashSourceBindings = listOf(binding().copy(sourceType = "ccb_debit", identityKind = "file_occurrence", identityStrength = "validated", stableEvidenceKey = "evidence")))
    private fun importChanges() = changes().copy(transactions = listOf(row().copy(values = row().values.copy(sourceType = "ccb_debit"))),
        sourceBindings = listOf(writeBinding().copy(sourceType = "ccb_debit", identityKind = "file_occurrence", identityStrength = "validated", stableEvidenceKey = "evidence")))

    @Test fun fileSourcePreparationUsesFrozenAccountEligibilityWithoutRewritingNewDefaults() {
        val source = importFacts().copy(accounts = facts().accounts.map { it.copy(active = false) })
        val request = importChanges()
        val prepared = prepareCashImportWriteSetSourceValues(request, source, aliases())
        assertEquals(request.copy(transactions = request.transactions.map { it.copy(values = it.values.copy(occurredAt = "2026-01-01T00:00:00+00:00")) }), prepared)
        assertEquals(time, request.transactions.single().values.occurredAt)
        assertNull(prepared.transactions.single().target.expectedRevision)
        val fresh = assertFailsWith<CashPolicyError> { prepareCashImportWriteSetSourceValues(request.copy(sourceBindings = emptyList()), source) }
        assertEquals("account_unavailable", fresh.code)
    }

    @Test fun bindingPreparationGroupsBothReferenceFormsByActualOwnerAndKeepsOtherOwnersSeparate() {
        val source = importFacts(); val request = importChanges()
        val extra = request.sourceBindings.single().copy(target = created("extra"), identityKey = "extra")
        val changes = request.copy(sourceBindings = request.sourceBindings + extra)
        val prepared = prepareCashImportWriteSetBindings(changes, source, aliases())
        assertEquals(changes.sourceBindings.map { it.target }, prepared.sourceBindings.map { it.target })
        assertEquals(2, prepared.sourceBindings.size)
        val otherOwner = source.cashSourceBindings.single().copy(id = "42", transactionId = "2", identityKey = "other")
        val conflict = assertFailsWith<CashPolicyError> { prepareCashImportWriteSetBindings(changes, source.copy(cashSourceBindings = source.cashSourceBindings + otherOwner), aliases()) }
        assertEquals("active_source_binding", conflict.code)
    }

    private fun pendingIssue() = CashIssueWriteDto(created("issue"), created("source"), "import:pending", "source_update_pending", null,
        JsonNull, buildJsonObject { put("note", "later") }, empty, emptyList())

    @Test fun metadataUsesTheActualOwnerForNewExistingAndResolvedIssuesWithoutRewritingTheRequest() {
        val issue = pendingIssue()
        val request = importChanges().copy(issues = listOf(issue.copy(transaction = observed("1"))))
        val prepared = prepareCashImportWriteSetSourceMetadata(request, importFacts(), aliases())
        assertEquals(request.issues, prepared.issues)
        assertEquals(request.transactions, prepared.transactions)
        assertEquals(empty, request.sourceBindings.single().coverage)
        val stored = LedgerCashIssueFactDto("20", "1", issue.issueKey, issue.code, null, issue.pendingSourcePayload,
            issue.pendingFields, empty, "3", time, time)
        val source = importFacts().copy(cashTransactionIssues = listOf(stored))
        val update = importChanges().copy(issues = listOf(issue.copy(target = observed("20", "3"))))
        assertEquals(update.issues, prepareCashImportWriteSetSourceMetadata(update, source, aliases()).issues)
        assertNull(update.issues.single().transaction.expectedRevision)
        val retired = importChanges().copy(resolvedIssues = listOf(observed("20", "3")))
        assertEquals(retired.resolvedIssues, prepareCashImportWriteSetSourceMetadata(retired, source, aliases()).resolvedIssues)
        val other = source.copy(cashTransactionIssues = listOf(stored.copy(transactionId = "2")))
        assertEquals("field_not_editable", assertFailsWith<CashPolicyError> {
            prepareCashImportWriteSetSourceMetadata(retired, other, aliases())
        }.code)
    }

    @Test fun aliasedMetadataCannotHideActualSelfCandidatesOrFabricateAnOldObservation() {
        val issue = pendingIssue().copy(code = "source_identity_ambiguous", candidates = listOf(observed("1")))
        val request = importChanges().copy(issues = listOf(issue))
        assertEquals("source_candidate_changed", assertFailsWith<CashPolicyError> {
            prepareCashImportWriteSetSourceMetadata(request, importFacts(), aliases())
        }.code)
        assertEquals("reference_changed", assertFailsWith<CashPolicyError> {
            prepareCashImportWriteSetSourceMetadata(request.copy(issues = listOf(issue.copy(candidates = listOf(observed("2", "999"))))), importFacts(), aliases())
        }.code)
        val old = LedgerCashIssueFactDto("20", "1", "import:old", "posting_incomplete", null, JsonNull, JsonNull, empty, "1", time, time)
        val source = importFacts().copy(cashTransactionIssues = listOf(old))
        assertEquals(importChanges().transactions, prepareCashImportWriteSetSourceMetadata(importChanges(), source, aliases()).transactions)
    }

    @Test fun preMergeEditsCoalesceByActualOldEntryOwnerAndStillRejectConflictingValues() {
        val first = importChanges().transactions.single().copy(entryEdit = CashEntryBeforeRelationEditDto(observed("71"), buildJsonObject { put("note", "C") }))
        val second = first.copy(target = observed("2"), values = first.values.copy(recordOrigin = "legacy", recordId = "row:2"),
            components = first.components.map { it.copy(reference = old("22")) },
            entryEdit = CashEntryBeforeRelationEditDto(observed("71"), buildJsonObject { put("counterparty", "party") }))
        val request = importChanges().copy(transactions = listOf(first, second), entries = emptyList())
        val result = prepareCashImportWriteSetEntryEdits(request, importFacts(), aliases())
        assertEquals(buildJsonObject { put("note", "C"); put("counterparty", "party") }, result.transactions.first().entryEdit!!.patch)
        assertNull(result.transactions.last().entryEdit)
        assertEquals(first.target, result.transactions.first().target)
        assertEquals(second.target, result.transactions.last().target)
        assertEquals(observed("71"), result.transactions.first().entryEdit!!.entry)
        assertFailsWith<CashImportPlanError> {
            prepareCashImportWriteSetEntryEdits(request.copy(transactions = listOf(first,
                second.copy(entryEdit = second.entryEdit!!.copy(patch = buildJsonObject { put("note", "conflict") })))), importFacts(), aliases())
        }
    }

    @Test fun frozenSourceEligibilityDoesNotMakeAnInactiveAccountEligibleForFinalMappingPublication() {
        val mapping = CashStatementMappingWriteDto(created("mapping"), "ccb_debit", "funding_group_v1", "group", "10")
        val request = importChanges().copy(statementAccountMappings = listOf(mapping))
        assertEquals(request, prepareCashImportWriteSetMappings(request, importFacts(), aliases()))
        val source = importFacts().copy(accounts = importFacts().accounts.map { it.copy(active = false) })
        prepareCashImportWriteSetSourceValues(request, source, aliases())
        assertEquals("funding_mapping_invalid", assertFailsWith<CashPolicyError> {
            prepareCashImportWriteSetMappings(request, source, aliases())
        }.code)
    }

    private fun aliasEdge(id: String, secondary: String, state: String = "draft") = CashRelationWriteDto(created(id), "payment_mirror",
        created("source"), observed(secondary), new("part"), old("$secondary$secondary"), created("source"), new("part"),
        "expense", "expense", state, true, null, "user", empty, "5", "rule", "user", emptyList())
    private fun extraSource() = importFacts().copy(cashTransactions = importFacts().cashTransactions + listOf("3", "4").map { parent(it).copy(sourceType = "ccb_debit") },
        cashTransactionComponents = importFacts().cashTransactionComponents + listOf("3", "4").map(::part))

    @Test fun relationBatchesJoinBothParentReferenceFormsButKeepRelationKeysInTheirOwnEntityScope() {
        val first = aliasEdge("source", "3")
        val second = aliasEdge("other-edge", "4").copy(primaryTransaction = observed("1"), primaryComponent = old("11"),
            anchorTransaction = observed("1"), anchorComponent = old("11"))
        val groups = listOf(first, second).map { CashRelationWriteGroupDto(listOf(it.target.reference)) }
        val request = importChanges().copy(entries = emptyList(), relations = listOf(first, second), relationGroups = groups)
        assertEquals(listOf(groups), prepareCashImportWriteSetRelationBatches(request, extraSource(), aliases()))
        assertEquals(listOf(groups), prepareCashImportWriteSetCommonChoices(request, extraSource(), aliases()))
        assertEquals(new("source"), first.target.reference)
    }

    @Test fun acceptedChoicesMustCoverManualFieldsOfTheActualAliasedOwner() {
        val source = extraSource().copy(transactionRelations = emptyList(),
            cashEntries = importFacts().cashEntries.map { it.copy(manualFields = JsonArray(listOf(JsonPrimitive("note")))) },
            cashEntryMembers = importFacts().cashEntryMembers.take(1))
        val edge = aliasEdge("edge", "3", "confirmed")
        val group = CashRelationWriteGroupDto(listOf(edge.target.reference))
        val request = importChanges().copy(entries = emptyList(), relations = listOf(edge), relationGroups = listOf(group))
        assertEquals("entry_result_invalid", assertFailsWith<CashPolicyError> {
            prepareCashImportWriteSetCommonChoices(request, source, aliases())
        }.code)
        val chosen = group.copy(commonFields = buildJsonObject { put("note", "B") })
        assertEquals(listOf(listOf(chosen)), prepareCashImportWriteSetCommonChoices(request.copy(relationGroups = listOf(chosen)), source, aliases()))
    }

}
