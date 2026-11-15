package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashImportConclusionTest {
    private fun complete(alternatives: Boolean = true, multiple: Boolean = false): Triple<LedgerSnapshotDto, JsonObject, CashWriteSetDto> {
        val (source, plan, changes) = LocalCashImportRelationWritesTest().calculate(alternatives = alternatives, multipleRows = multiple)
        return Triple(source, plan, buildLocalCashImportEntryWrites(source, plan, changes))
    }
    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))
    private fun JsonObject.firstChanged(key: String, change: (JsonObject) -> JsonObject) = with(key,
        JsonArray(getValue(key).jsonArray.mapIndexed { index, value -> if (index == 0) change(value.jsonObject) else value }))

    @Test fun skippedRowsAndFileFailuresRemainConclusionsEvenWhenTheWriteSetIsIdentical() {
        val (source, plan, changes) = complete()
        val expected = localCashImportConclusion(source, plan, changes)
        val variants = listOf(
            plan.with("row_decisions", JsonArray(plan.getValue("row_decisions").jsonArray + Json.parseToJsonElement(
                """{"row_ref":"another:r2","operation_ref":null,"planned_action":"skip","code":"row_unreadable","issues":[]}"""))),
            plan.firstChanged("row_decisions") { it.with("planned_action", JsonPrimitive("duplicate")) },
            plan.firstChanged("file_results") { it.with("status", JsonPrimitive("failed")) },
            plan.firstChanged("file_results") { it.with("recognized_row_count", JsonPrimitive(999)) },
            plan.firstChanged("file_results") { it.with("diagnostic", Json.parseToJsonElement("""{"locations":[{"row_number":42,"id":"raw","expected_revision":9007199254740993}]}""")) },
            plan.with("can_commit", JsonPrimitive(false)),
        )
        for ((index, value) in variants.withIndex()) assertNotEquals(expected, localCashImportConclusion(source, value, changes), "variant $index")
    }

    @Test fun jointCandidateCombinationsCannotBeReplacedByTheirFlattenedParticipants() {
        val (source, plan, changes) = complete(multiple = true)
        val group = plan.getValue("relation_groups").jsonArray.single().jsonObject
        val candidates = group.getValue("candidates").jsonArray
        assertEquals(4, candidates.size)
        val reduced = plan.with("relation_groups", JsonArray(listOf(group.with("candidates", JsonArray(candidates.drop(1))))))
        val sourceWrites = changes.copy(relations = emptyList(), relationGroups = emptyList(), entries = emptyList(), entryDependencies = emptyList())
        val rebuilt = buildLocalCashImportEntryWrites(source, reduced, buildLocalCashImportRelationWrites(source, reduced, sourceWrites))
        assertEquals(localCashImportWriteConclusion(source, changes), localCashImportWriteConclusion(source, rebuilt))
        assertNotEquals(localCashImportConclusion(source, plan, changes), localCashImportConclusion(source, reduced, rebuilt))
    }

    @Test fun readOnlyParticipantFinancialAndManualValuesArePartOfTheConclusion() {
        val (source, plan, changes) = complete()
        assertTrue(changes.transactions.all { it.target.reference is NewCashWriteReferenceDto })
        val expected = localCashImportConclusion(source, plan, changes)
        val old = source.facts.cashTransactions.first()
        val variants = listOf(old.copy(amount = "-6.00"), old.copy(occurredAt = "2026-10-01T00:00:00Z"),
            old.copy(manualOverrides = buildJsonObject { put("note", true) }), old.copy(accountId = "other"))
        for (row in variants) assertNotEquals(expected, localCashImportConclusion(source.copy(facts = source.facts.copy(
            cashTransactions = listOf(row) + source.facts.cashTransactions.drop(1))), plan, changes))
        val changedPart = source.copy(facts = source.facts.copy(cashTransactionComponents = source.facts.cashTransactionComponents.map {
            it.copy(metadataJson = buildJsonObject { put("raw", "changed") })
        }))
        assertNotEquals(expected, localCashImportConclusion(changedPart, plan, changes))
    }

    @Test fun projectedResultsFailuresAndInvalidatedChoicesAreCompared() {
        val (source, plan, changes) = complete()
        val expected = localCashImportConclusion(source, plan, changes)
        val variants = listOf(
            plan.firstChanged("items") { it.with("statistics_eligible", JsonPrimitive(!it.getValue("statistics_eligible").jsonPrimitive.boolean)) },
            plan.firstChanged("items") { it.with("display", it.getValue("display").jsonObject.with("amount", JsonPrimitive("123.45"))) },
            plan.with("relation_failures", Json.parseToJsonElement("""[{"code":"relation_amount_exceeded","parent_ids":["1"]}]""")),
            plan.with("invalidated_choices", Json.parseToJsonElement("""[{"suggestion_id":"old","action":"accept","member_refs":[{"row_ref":"refund:r1"}]}]""")),
            plan.with("invalidated_relation_choices", Json.parseToJsonElement("""{"old":{"choice":{"suggestion_id":"old","action":"accept"},"member_refs":[{"row_ref":"refund:r1"}]}}""")),
        )
        for ((index, value) in variants.withIndex()) assertNotEquals(expected, localCashImportConclusion(source, value, changes), "variant $index")
    }

    @Test fun planAndWriteReferencesCanBeRenamedTogetherWithoutChangingTheConclusion() {
        val (source, plan, changes) = complete()
        assertEquals("79c73a2c64889a1616dff61b08f28c74a303028bd3698b47d4a88ce84ddbe4f8", localCashImportConclusion(source, plan, changes))
        assertEquals("d9e4444048ffa6d52aaebdea39ac939be97a7d635afc3e832d8038501403aa64", localCashImportWriteConclusion(source, changes))
        val replacements = linkedMapOf<String, String>()
        fun add(raw: JsonElement?) { if (raw != null && raw != JsonNull) replacements[raw.jsonPrimitive.content] = "renamed:${raw.jsonPrimitive.content}" }
        for (operation in plan.getValue("operations").jsonArray.map { it.jsonObject }) {
            add(operation["operation_ref"]); add(operation["preview_transaction_id"])
            for (part in operation.getValue("preview_components").jsonArray) add(part.jsonObject["component_id"])
        }
        for (group in plan.getValue("relation_groups").jsonArray.map { it.jsonObject }) {
            add(group["suggestion_id"]); add(group["source_suggestion_id"])
            for (option in listOf(group) + group.getValue("candidates").jsonArray.map { it.jsonObject }) {
                add(option["candidate_id"])
                for (edge in option.getValue("edges").jsonArray) {
                    val id = edge.jsonObject.getValue("id").jsonPrimitive.content
                    replacements[id] = "renamed:$id"; replacements["relation:$id"] = "relation:renamed:$id"
                }
            }
        }
        for (item in plan.getValue("items").jsonArray) add(item.jsonObject["preview_item_id"])
        for (key in plan.getValue("entry_targets").jsonObject.keys + plan.getValue("source_candidate_sets").jsonObject.keys) replacements[key] = "renamed:$key"
        fun rename(value: JsonElement, opaque: Boolean = false): JsonElement {
            if (opaque) return value
            return when (value) {
                is JsonArray -> JsonArray(value.map { rename(it) })
                is JsonObject -> JsonObject(value.entries.associate { (key, item) ->
                    (replacements[key] ?: key) to if (key in setOf("expected_revision", "row_revision")) JsonPrimitive("42")
                    else rename(item, key in setOf("source_payload", "relation_metadata", "metadata_json", "diagnostic", "standalone_values"))
                })
                is JsonPrimitive -> replacements[value.content]?.let(::JsonPrimitive) ?: value
            }
        }
        val otherPlan = rename(plan).jsonObject.with("dependencies", buildJsonObject { put("token", "unrelated observation") })
        val otherChanges = Json.decodeFromJsonElement<CashWriteSetDto>(rename(Json.encodeToJsonElement(changes)))
        val observed = source.copy(ledgerSeq = "42", facts = source.facts.copy(
            cashTransactions = source.facts.cashTransactions.map { it.copy(rowRevision = "42") },
            cashEntries = source.facts.cashEntries.map { it.copy(revision = "42") }))
        assertNotEquals(plan, otherPlan); assertNotEquals(changes, otherChanges)
        assertEquals(localCashImportConclusion(source, plan, changes), localCashImportConclusion(observed, otherPlan, otherChanges))
    }

    @Test fun unrelatedFactsAndContainerOrderAreIgnoredAndInputsStayUntouched() {
        val (source, plan, changes) = complete()
        val before = plan.toString(); val sourceBefore = source.copy(); val writesBefore = changes.copy()
        val unrelated = source.copy(facts = source.facts.copy(accounts = source.facts.accounts + source.facts.accounts.first().copy(id = "unused"),
            cashTransactions = source.facts.cashTransactions + source.facts.cashTransactions.first().copy(id = "unused", recordId = "unused")))
        val reordered = plan.with("items", JsonArray(plan.getValue("items").jsonArray.reversed()))
            .firstChanged("relation_groups") { it.with("candidates", JsonArray(it.getValue("candidates").jsonArray.reversed())) }
        assertEquals(localCashImportConclusion(source, plan, changes), localCashImportConclusion(unrelated, reordered, changes))
        assertEquals(before, plan.toString()); assertEquals(sourceBefore, source); assertEquals(writesBefore, changes)
    }

    @Test fun readOnlyRelationsIssuesCandidatesAndFundingRemainBusinessInputs() {
        val (source, plan, changes) = complete()
        val original = LocalCashRelationContextTest().snapshot()
        val issue = original.facts.cashTransactionIssues.single()
        val relation = original.facts.transactionRelations.first()
        val funding = LedgerInvestmentFundingFactDto("80", "11", "9007199254740993", "cash_to_investment", "accepted", "fixture",
            JsonNull, "active", "", "2026-10-01T00:00:00Z", "", null, "", null)
        val issueCandidate = LedgerIssueCandidateFactDto("400", issue.id, "1", "17")
        val relationCandidate = LedgerRelationCandidateFactDto("500", relation.id, "2", "22", "17", JsonNull, "valid")
        val facts = source.facts.copy(transactionRelations = listOf(relation), cashTransactionIssues = listOf(issue),
            cashIssueCandidates = listOf(issueCandidate), cashRelationCandidates = listOf(relationCandidate), cashInvestmentFundingRelations = listOf(funding))
        val withFacts = source.copy(facts = facts)
        val expected = localCashImportConclusion(withFacts, plan, changes)
        val variants = listOf(
            facts.copy(transactionRelations = listOf(relation.copy(primaryRole = "changed"))),
            facts.copy(cashIssueCandidates = listOf(issueCandidate.copy(candidateTransactionId = "2"))),
            facts.copy(cashRelationCandidates = listOf(relationCandidate.copy(validity = "stale"))),
            facts.copy(cashInvestmentFundingRelations = listOf(funding.copy(status = "rejected"))),
        )
        for ((index, other) in variants.withIndex()) assertNotEquals(expected, localCashImportConclusion(source.copy(facts = other), plan, changes), "participant $index")
        val retired = plan.with("type_effects", buildJsonObject {
            put("removed_relation_ids", JsonArray(listOf(JsonPrimitive(relation.id))))
            put("removed_funding_ids", JsonArray(listOf(JsonPrimitive(funding.id))))
        })
        assertNotEquals(expected, localCashImportConclusion(withFacts, retired, changes))
        val renamed = withFacts.copy(facts = facts.copy(transactionRelations = listOf(relation.copy(id = "1000", rowRevision = "42")),
            cashTransactionIssues = listOf(issue.copy(id = "3000", revision = "42")),
            cashIssueCandidates = listOf(issueCandidate.copy(id = "4000", issueId = "3000", expectedRowRevision = "42")),
            cashRelationCandidates = listOf(relationCandidate.copy(id = "5000", relationId = "1000", expectedRowRevision = "42")),
            cashInvestmentFundingRelations = listOf(funding.copy(id = "8000"))))
        assertEquals(expected, localCashImportConclusion(renamed, plan, changes))
    }

    @Test fun observationOnlyDependencyParentsAndManualSetOrderDoNotChangeTheConclusion() {
        val (source, plan, changes) = complete()
        val flags = JsonArray(listOf(JsonPrimitive("note"), JsonPrimitive("counterparty")))
        val first = source.copy(facts = source.facts.copy(cashEntries = source.facts.cashEntries.map { it.copy(manualFields = flags) }))
        val second = first.copy(facts = first.facts.copy(cashEntries = first.facts.cashEntries.map { it.copy(manualFields = JsonArray(flags.reversed())) }))
        val observation = plan.firstChanged("relation_groups") { it.with("dependency_parent_ids", JsonArray(listOf(JsonPrimitive("unused")))) }
        assertEquals(localCashImportConclusion(first, plan, changes), localCashImportConclusion(second, observation, changes))
    }

    @Test fun realInvalidationResultsIgnoreOldSuggestionAndCandidateAllocation() {
        val (source, plan, changes) = complete()
        val group = plan.getValue("relation_groups").jsonArray.single().jsonObject
        fun invalidated(oldId: String, candidateId: String): JsonObject {
            val candidate = group.getValue("candidates").jsonArray.first().jsonObject.with("candidate_id", JsonPrimitive(candidateId))
            val previous = plan.with("relation_groups", JsonArray(listOf(group.with("suggestion_id", JsonPrimitive(oldId))
                .with("source_suggestion_id", JsonPrimitive("source:$oldId")).with("candidates", JsonArray(listOf(candidate))))))
            val choice = buildJsonArray { add(buildJsonObject {
                put("suggestion_id", oldId); put("candidate_id", candidateId); put("action", "accept")
                put("common_fields", buildJsonObject { put("note", "previous choice") })
            }) }
            val result = applyLocalCashImportRelationChoices(plan.getValue("relation_groups").jsonArray, choice, previous)
            assertEquals(1, result.getValue("invalidated").jsonArray.size)
            return plan.with("relation_groups", result.getValue("groups")).with("invalidated_choices", result.getValue("invalidated"))
                .with("invalidated_relation_choices", result.getValue("sources")).with("relation_choice_generations", result.getValue("generations"))
        }
        val first = invalidated("old-one", "candidate-one"); val second = invalidated("old-two", "candidate-two")
        assertEquals(localCashImportConclusion(source, first, changes), localCashImportConclusion(source, second, changes))
        assertNotEquals(localCashImportConclusion(source, plan, changes), localCashImportConclusion(source, first, changes))
        val changed = first.with("invalidated_relation_choices", JsonObject(first.getValue("invalidated_relation_choices").jsonObject.mapValues { (_, raw) ->
            raw.jsonObject.with("choice", raw.jsonObject.getValue("choice").jsonObject.with("action", JsonPrimitive("ignore")))
        }))
        assertNotEquals(localCashImportConclusion(source, first, changes), localCashImportConclusion(source, changed, changes))
    }

    @Test fun acceptedCommitGroupAllocationIsNotABusinessConclusion() {
        val (source, plan, writes) = LocalCashImportRelationWritesTest().calculate(action = "accept", multipleRows = true)
        val changes = buildLocalCashImportEntryWrites(source, plan, writes)
        assertTrue(plan.getValue("relation_groups").jsonArray.single().jsonObject["commit_group_id"] != null)
        val reallocated = plan.firstChanged("relation_groups") { it.with("commit_group_id", JsonPrimitive("recomputed-group")) }
        assertEquals(localCashImportConclusion(source, plan, changes), localCashImportConclusion(source, reallocated, changes))
    }

    @Test fun newlyReferencedParticipantsIncludeTheirCompleteOldEntryAndIssueCandidates() {
        val (source, plan, changes) = complete()
        val original = LocalCashRelationContextTest().snapshot()
        val firstIssue = original.facts.cashTransactionIssues.single()
        val secondIssue = firstIssue.copy(id = "301", transactionId = "4", issueKey = "other")
        val oldEntry = source.facts.cashEntries.single().copy(id = "other-entry", rootTransactionId = "3", identityTransactionId = "3")
        val edge = original.facts.transactionRelations.first().copy(id = "102", primaryTransactionId = "3", secondaryTransactionId = "4",
            primaryComponentId = "33", secondaryComponentId = "44", anchorTransactionId = "3", anchorComponentId = "33")
        val firstCandidate = LedgerIssueCandidateFactDto("400", firstIssue.id, "3", "17")
        val secondCandidate = LedgerIssueCandidateFactDto("401", secondIssue.id, "1", "17")
        val facts = source.facts.copy(
            cashTransactions = source.facts.cashTransactions + listOf("3", "4").map { source.facts.cashTransactions.first().copy(id = it, recordId = "record-$it") },
            cashTransactionComponents = source.facts.cashTransactionComponents + original.facts.cashTransactionComponents.filter { it.cashTransactionId in setOf("3", "4") },
            cashEntries = source.facts.cashEntries + oldEntry,
            cashEntryMembers = source.facts.cashEntryMembers + listOf("3", "4").map { source.facts.cashEntryMembers.single().copy(transactionId = it, entryId = oldEntry.id) },
            cashTransactionIssues = listOf(firstIssue, secondIssue), cashIssueCandidates = listOf(firstCandidate, secondCandidate),
            transactionRelations = listOf(edge))
        val changed = facts.copy(cashIssueCandidates = listOf(firstCandidate, secondCandidate.copy(candidateTransactionId = "2")))
        assertNotEquals(localCashImportConclusion(source.copy(facts = facts), plan, changes),
            localCashImportConclusion(source.copy(facts = changed), plan, changes))
    }

    @Test fun onlyExplicitFinancialFieldsNormalizeAndOpaqueFactsKeepExactJson() {
        val (source, plan, changes) = complete()
        val equivalent = source.copy(facts = source.facts.copy(cashTransactions = source.facts.cashTransactions.map {
            it.copy(amount = "-5.0000", occurredAt = "2026-10-02T18:00:00+08:00")
        }, cashTransactionComponents = source.facts.cashTransactionComponents.map { it.copy(amount = "-0.5E1", ordinal = "+000") }))
        val sameDisplay = plan.firstChanged("items") { item ->
            val display = item.getValue("display").jsonObject
            item.with("display", display.with("amount", JsonPrimitive("2.5000")).with("occurred_at", JsonPrimitive("2026-10-03T17:00:00+08:00")))
        }
        assertEquals(localCashImportConclusion(source, plan, changes), localCashImportConclusion(equivalent, sameDisplay, changes))
        fun withPayload(text: String) = source.copy(facts = source.facts.copy(cashTransactions = source.facts.cashTransactions.map {
            it.copy(sourcePayload = Json.parseToJsonElement(text))
        }))
        val first = withPayload("""{"id":"raw","expected_revision":9007199254740993,"amount":1.000000000000000001}""")
        val reordered = withPayload("""{"amount":1.000000000000000001,"expected_revision":9007199254740993,"id":"raw"}""")
        val other = withPayload("""{"id":"raw","expected_revision":9007199254740994,"amount":1.000000000000000001}""")
        assertEquals(localCashImportConclusion(first, plan, changes), localCashImportConclusion(reordered, plan, changes))
        assertNotEquals(localCashImportConclusion(first, plan, changes), localCashImportConclusion(other, plan, changes))
    }

    @Test fun retainedHistoricalCandidatesDistinguishDeletedAndLiveOwnersWithTheSameSourceKey() {
        val (source, plan, changes) = complete()
        val old = source.facts.cashTransactions.first().copy(id = "3", recordId = "reused", deletedAt = "2026-10-02T12:00:00Z")
        val replacement = old.copy(id = "4", deletedAt = null)
        val originalIssue = LocalCashRelationContextTest().snapshot().facts.cashTransactionIssues.single()
        val firstIssue = originalIssue.copy(id = "300", transactionId = "1")
        val otherIssue = originalIssue.copy(id = "301", transactionId = "2")
        val selected = LedgerIssueCandidateFactDto("400", firstIssue.id, old.id, "17")
        val others = listOf(LedgerIssueCandidateFactDto("401", otherIssue.id, old.id, "17"),
            LedgerIssueCandidateFactDto("402", otherIssue.id, replacement.id, "17"))
        val facts = source.facts.copy(cashTransactions = source.facts.cashTransactions + listOf(old, replacement),
            cashTransactionComponents = source.facts.cashTransactionComponents + listOf(old, replacement).map { row ->
                source.facts.cashTransactionComponents.first().copy(id = row.id.repeat(2), cashTransactionId = row.id)
            }, cashTransactionIssues = listOf(firstIssue, otherIssue), cashIssueCandidates = listOf(selected) + others)
        val changed = facts.copy(cashIssueCandidates = listOf(selected.copy(candidateTransactionId = replacement.id)) + others)
        assertNotEquals(localCashImportConclusion(source.copy(facts = facts), plan, changes),
            localCashImportConclusion(source.copy(facts = changed), plan, changes))
    }
}
