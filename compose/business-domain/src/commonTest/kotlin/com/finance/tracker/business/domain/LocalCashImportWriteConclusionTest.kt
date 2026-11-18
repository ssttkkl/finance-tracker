package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashImportWriteConclusionTest {
    private fun complete(alternatives: Boolean = false): Pair<LedgerSnapshotDto, CashWriteSetDto> {
        val (source, plan, changes) = LocalCashImportRelationWritesTest().calculate(
            action = if (alternatives) null else "accept", alternatives = alternatives, multipleRows = !alternatives)
        return source to buildLocalCashImportEntryWrites(source, plan, changes)
    }

    private fun rename(value: JsonElement): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map(::rename))
        is JsonObject -> when {
            value.keys == setOf("type", "ref") && value["type"] == JsonPrimitive("new") ->
                JsonObject(value + ("ref" to JsonPrimitive("renamed:" + value.getValue("ref").jsonPrimitive.content)))
            value.keys == setOf("reference", "expected_revision") -> JsonObject(value.mapValues { (key, item) ->
                if (key == "expected_revision" && item != JsonNull) JsonPrimitive("17") else rename(item)
            })
            else -> JsonObject(value.mapValues { rename(it.value) })
        }
        else -> value
    }

    @Test fun realCompleteBusinessEffectsIgnoreTemporaryReferencesAndObservationRevisions() {
        val (source, changes) = complete()
        val original = Json.encodeToJsonElement(changes)
        val renamed = Json.decodeFromJsonElement<CashWriteSetDto>(rename(original))
        assertNotEquals(changes, renamed)
        assertEquals(localCashImportWriteConclusion(source, changes), localCashImportWriteConclusion(source.copy(ledgerSeq = "18"), renamed))
        assertEquals(original, Json.encodeToJsonElement(changes))
        assertTrue(localCashImportWriteConclusion(source, changes).matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun sourceCommonChoiceAndFinalMemberEffectsAllRemainBusinessChanges() {
        val (source, changes) = complete()
        val first = changes.transactions.first()
        val entry = changes.entries.single()
        val relation = changes.relations.first()
        val variants = listOf(
            changes.copy(transactions = listOf(first.copy(values = first.values.copy(amount = "7.01"))) + changes.transactions.drop(1)),
            changes.copy(transactions = listOf(first.copy(values = first.values.copy(note = "source changed"))) + changes.transactions.drop(1)),
            changes.copy(entries = listOf(entry.copy(note = "final changed"))),
            changes.copy(entries = listOf(entry.copy(manualFields = entry.manualFields + "counterparty"))),
            changes.copy(entries = listOf(entry.copy(members = entry.members.drop(1)))),
            changes.copy(entries = listOf(entry.copy(members = entry.members.mapIndexed { index, member ->
                if (index == 0) member.copy(standaloneValues = JsonObject(member.standaloneValues.jsonObject + ("note" to JsonPrimitive("independent changed")))) else member
            }))),
            changes.copy(relations = listOf(relation.copy(primaryRole = "different")) + changes.relations.drop(1)),
            changes.copy(relations = listOf(relation.copy(appliedAmount = "4.99")) + changes.relations.drop(1)),
            changes.copy(relationGroups = changes.relationGroups.mapIndexed { index, group ->
                if (index == 0) group.copy(commonFields = buildJsonObject { put("note", "B changed") }) else group
            }),
        )
        val expected = localCashImportWriteConclusion(source, changes)
        for ((index, changed) in variants.withIndex()) assertNotEquals(expected, localCashImportWriteConclusion(source, changed), "variant $index")
        assertNotEquals(expected, localCashImportWriteConclusion(source.copy(workspaceId = "other"), changes))
    }

    @Test fun everySourceBindingMappingAndEntryIntentIsRetained() {
        val (source, changes) = complete()
        val row = changes.transactions.first(); val part = row.components.single()
        fun row(value: CashTransactionWriteDto) = changes.copy(transactions = listOf(value) + changes.transactions.drop(1))
        val binding = changes.sourceBindings.first(); val mapping = changes.statementAccountMappings.single()
        val variants = listOf(
            row(row.copy(values = row.values.copy(accountId = "other"))),
            row(row.copy(values = row.values.copy(state = "draft"))),
            row(row.copy(values = row.values.copy(occurredAt = "2026-10-03T09:00:01Z"))),
            row(row.copy(values = row.values.copy(currency = "USD"))),
            row(row.copy(values = row.values.copy(categoryId = "category"))),
            row(row.copy(values = row.values.copy(recordId = "other-source"))),
            row(row.copy(values = row.values.copy(manualOverrides = buildJsonObject { put("note", true) }))),
            row(row.copy(values = row.values.copy(relationMetadata = buildJsonObject { put("state", "different") }))),
            row(row.copy(components = listOf(part.copy(accountId = "other")))),
            row(row.copy(components = listOf(part.copy(amount = "2.49")))),
            row(row.copy(components = listOf(part.copy(currency = "USD")))),
            row(row.copy(components = listOf(part.copy(metadataJson = buildJsonObject { put("id", "business") })))),
            row(row.copy(editedFields = listOf("record_type"))),
            row(row.copy(entryEdit = CashEntryBeforeRelationEditDto(null, buildJsonObject { put("note", "") }))),
            changes.copy(sourceBindings = listOf(binding.copy(transaction = ExistingCashWriteReferenceDto("1"))) + changes.sourceBindings.drop(1)),
            changes.copy(sourceBindings = listOf(binding.copy(stableEvidenceKey = "different")) + changes.sourceBindings.drop(1)),
            changes.copy(sourceBindings = listOf(binding.copy(coverage = buildJsonObject { put("page_total", 2) })) + changes.sourceBindings.drop(1)),
            changes.copy(statementAccountMappings = listOf(mapping.copy(accountId = "other"))),
            changes.copy(statementAccountMappings = emptyList()),
            changes.copy(entries = changes.entries.map { it.copy(members = it.members.map { member -> member.copy(standaloneManualFields = listOf("counterparty")) }) }),
            changes.copy(sourceEmptyPages = listOf(LedgerSourceEmptyPageFactDto("alipay", "p", "doc", 1, JsonObject(emptyMap())))),
        )
        val fingerprint = localCashImportWriteConclusion(source, changes)
        for ((index, variant) in variants.withIndex()) assertNotEquals(fingerprint, localCashImportWriteConclusion(source, variant), "source variant $index")
    }

    @Test fun candidateAndPendingIssueBusinessValuesRemainVisibleToTheComparison() {
        val (source, base) = complete(alternatives = true)
        val empty = JsonObject(emptyMap()); val owner = base.transactions.single().target
        val issue = CashIssueWriteDto(CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("issue-temp"), null), owner,
            "import:pending", "source_update_pending", "note", empty, buildJsonObject { put("note", "incoming") }, empty,
            listOf(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("1"), "1")))
        val changes = base.copy(issues = listOf(issue))
        val relation = changes.relations.single(); val candidate = relation.candidates.first()
        val variants = listOf(
            changes.copy(issues = listOf(issue.copy(pendingFields = buildJsonObject { put("note", "different") }))),
            changes.copy(issues = listOf(issue.copy(issueKey = "import:other"))),
            changes.copy(issues = listOf(issue.copy(fieldPath = "amount"))),
            changes.copy(issues = listOf(issue.copy(candidates = emptyList()))),
            changes.copy(relations = listOf(relation.copy(candidates = relation.candidates.drop(1)))),
            changes.copy(relations = listOf(relation.copy(candidates = listOf(candidate.copy(validity = "incomplete")) + relation.candidates.drop(1)))),
            changes.copy(relations = listOf(relation.copy(candidates = listOf(candidate.copy(evidence = buildJsonObject { put("reason", "changed") })) + relation.candidates.drop(1)))),
        )
        val expected = localCashImportWriteConclusion(source, changes)
        for ((index, variant) in variants.withIndex()) assertNotEquals(expected, localCashImportWriteConclusion(source, variant), "pending variant $index")
        val renamed = Json.decodeFromJsonElement<CashWriteSetDto>(rename(Json.encodeToJsonElement(changes)))
        assertEquals(expected, localCashImportWriteConclusion(source, renamed))
    }

    @Test fun onlyExplicitFinancialFieldsNormalizeWhileOpaqueJsonKeepsExactTokensAndNames() {
        val (source, base) = complete()
        val row = base.transactions.first()
        fun withPayload(text: String) = base.copy(transactions = listOf(row.copy(values = row.values.copy(sourcePayload = Json.parseToJsonElement(text)))) + base.transactions.drop(1))
        val original = withPayload("""{"id":"business","expected_revision":9007199254740993,"amount":"2.500","value":1.000000000000000001}""")
        val reordered = withPayload("""{"value":1.000000000000000001,"amount":"2.500","expected_revision":9007199254740993,"id":"business"}""")
        val expected = localCashImportWriteConclusion(source, original)
        assertEquals(expected, localCashImportWriteConclusion(source, reordered))
        for (replacement in listOf("1.000000000000000002", "1.0", "1", "\"1.000000000000000001\""))
            assertNotEquals(expected, localCashImportWriteConclusion(source, withPayload("""{"id":"business","expected_revision":9007199254740993,"amount":"2.500","value":$replacement}""")))
        assertNotEquals(expected, localCashImportWriteConclusion(source, withPayload("""{"id":"business","expected_revision":9007199254740994,"amount":"2.500","value":1.000000000000000001}""")))
        val equivalent = base.copy(transactions = base.transactions.map { it.copy(values = it.values.copy(amount = "0.2500E1", occurredAt = "2026-10-03T17:00:00+08:00"),
            components = it.components.map { part -> part.copy(amount = "2.5000", ordinal = "+000") }) },
            relations = base.relations.map { it.copy(appliedAmount = "2.5000") })
        assertEquals(localCashImportWriteConclusion(source, base), localCashImportWriteConclusion(source, equivalent))
    }

    @Test fun containerOrderAndUnrelatedFactsDoNotChangeTheBusinessEffect() {
        val (source, changes) = complete()
        val expected = localCashImportWriteConclusion(source, changes)
        val reordered = changes.copy(transactions = changes.transactions.reversed(), relations = changes.relations.reversed(),
            entries = changes.entries.reversed().map { it.copy(members = it.members.reversed(), manualFields = it.manualFields.reversed()) },
            sourceBindings = changes.sourceBindings.reversed(), statementAccountMappings = changes.statementAccountMappings.reversed(),
            entryDependencies = emptyList())
        assertEquals(expected, localCashImportWriteConclusion(source, reordered))
        val unrelated = source.copy(ledgerSeq = "9223372036854775807", facts = source.facts.copy(
            accounts = source.facts.accounts + source.facts.accounts.first().copy(id = "other", name = "Unrelated")))
        assertEquals(expected, localCashImportWriteConclusion(unrelated, changes))
    }

    @Test fun unsupportedMaintenanceAndIdentityAliasesCannotBecomeAnAutomaticRetryProof() {
        val (source, changes) = complete()
        for (sourceType in listOf(null, "")) assertFailsWith<IllegalArgumentException> {
            localCashImportWriteConclusion(source, changes.copy(transactions = changes.transactions.map {
                it.copy(values = it.values.copy(sourceType = sourceType))
            }))
        }
        assertFailsWith<IllegalArgumentException> { localCashImportWriteConclusion(source, changes.copy(transactions =
            changes.transactions.map { it.copy(values = it.values.copy(recordId = "")) })) }
        assertFailsWith<IllegalArgumentException> { localCashImportWriteConclusion(source, changes.copy(
            deletedTransactions = listOf(CashTransactionDeleteDto(changes.transactions.first().target, "remove")))) }
        assertFailsWith<IllegalArgumentException> { localCashImportWriteConclusion(source, changes.copy(
            issueResolutions = listOf(KeepExistingIssueResolutionDto(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("issue"), "1"))))) }
        assertFailsWith<IllegalArgumentException> { localCashImportWriteConclusion(source, changes.copy(
            transactions = changes.transactions + changes.transactions.first().copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("alias"), null)))) }
    }

    @Test fun arbitrarySourceTextKeepsDifferentIsolatedUtf16ValuesDistinct() {
        val (source, changes) = complete()
        val row = changes.transactions.first()
        fun hash(char: Char): String = localCashImportWriteConclusion(source, changes.copy(transactions =
            listOf(row.copy(values = row.values.copy(sourcePayload = JsonPrimitive(charArrayOf(char).concatToString())))) + changes.transactions.drop(1)))
        assertNotEquals(hash(0xd800.toChar()), hash(0xd801.toChar()))
        assertNotEquals(hash(0xd800.toChar()), hash('?'))
    }

    @Test fun existingCandidatesWithoutUniqueSourceKeysKeepDistinctStoredIdentities() {
        val (source, changes) = complete(alternatives = true)
        val relation = changes.relations.single()
        assertEquals(2, relation.candidates.size)
        for (sourceType in listOf(null, "")) {
            val withoutUniqueKey = source.copy(facts = source.facts.copy(cashTransactions = source.facts.cashTransactions.map {
                it.copy(sourceType = sourceType, recordId = "same")
            }))
            val candidates = relation.candidates.map { candidate -> changes.copy(relations = listOf(relation.copy(candidates = listOf(candidate)))) }
            assertNotEquals(localCashImportWriteConclusion(withoutUniqueKey, candidates[0]),
                localCashImportWriteConclusion(withoutUniqueKey, candidates[1]), "sourceType=$sourceType")
        }
    }
}
