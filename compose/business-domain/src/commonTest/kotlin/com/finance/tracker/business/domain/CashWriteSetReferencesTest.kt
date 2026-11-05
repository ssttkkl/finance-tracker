package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CashWriteSetReferencesTest {
    private val empty = JsonObject(emptyMap())
    private val time = "2026-01-01T00:00:00Z"
    private val revision = "9007199254740993"
    private fun existing(id: String) = ExistingCashWriteReferenceDto(id)
    private fun fresh(id: String) = NewCashWriteReferenceDto(id)
    private fun target(id: String, version: String? = revision) = CashWriteVersionedReferenceDto(existing(id), version)
    private fun created(id: String) = CashWriteVersionedReferenceDto(fresh(id), null)
    private fun transaction(id: String) = LedgerCashTransactionFactDto(id, "10", "posted", "import", null, revision,
        "atomic", "wechat", "record-$id", empty, empty, null, empty, time, "-5", "CNY", "", "", JsonArray(emptyList()), "", null,
        "consumption", "not_applicable", time, null, "", "")
    private fun facts(): LedgerFactsDto {
        val base = LedgerFactsDto(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0")
        return base.copy(accounts = listOf(LedgerAccountFactDto("10", "cash", "cash", true, JsonArray(listOf(JsonPrimitive("CNY"))), "1")),
            cashTransactions = listOf(transaction("1"), transaction("2")),
            cashTransactionComponents = listOf("1", "2").map { LedgerCashComponentFactDto(it + it, it, "10", "-5", "CNY", "0", "", "", empty, time) },
            transactionRelations = listOf(LedgerCashRelationFactDto("3", "payment_mirror", "1", "2", "11", "22", "1", "11", "expense", "expense",
                "confirmed", true, revision, null, "user", empty, "0", "rule", "", time, "", time, "")),
            cashTransactionIssues = listOf(LedgerCashIssueFactDto("4", "1", "issue", "field_conflict", "note", empty, empty, empty, revision, time, time)),
            cashEntries = listOf(LedgerCashEntryFactDto("entry-old", "1", "1", revision, null, "", "", "", JsonArray(emptyList()), JsonArray(emptyList()), null)),
            cashSourceBindings = listOf(LedgerSourceBindingFactDto("5", "1", "wechat", "account", "scope", "provider", "key", "strong", null, "1", empty, revision, time, time)),
            statementAccountMappings = listOf(LedgerStatementMappingFactDto("6", "wechat", "provider", "account-key", "10", "user", revision, time, time)))
    }
    private fun values() = CashTransactionWriteValuesDto("10", "posted", "import", null, "atomic", "wechat", "new-record", empty, empty, null, empty,
        time, "-5", "CNY", "", "", JsonArray(emptyList()), "", null, "consumption", "not_applicable")
    private fun part(reference: CashWriteReferenceDto = fresh("part"), ordinal: String = "0") =
        CashComponentWriteDto(reference, "10", "-5", "CNY", ordinal, "", "", empty)
    private fun relation(first: CashWriteVersionedReferenceDto = target("1"), firstPart: CashWriteReferenceDto? = existing("11")) =
        CashRelationWriteDto(created("relation"), "payment_mirror", first, target("2"), firstPart, existing("22"), first, firstPart, "expense", "expense",
            "confirmed", true, null, "user", empty, "0", "rule", "", listOf(CashRelationCandidateWriteDto(target("2"), existing("22"), empty, "valid")))
    private fun issue() = CashIssueWriteDto(created("issue"), target("1"), "issue-new", "field_conflict", "note", empty, empty, empty, listOf(target("2")))
    private fun entry() = CashEntryWriteDto(created("entry"), target("1"), target("1"), null, "", "", "", JsonArray(emptyList()), emptyList(), null,
        listOf(CashEntryMemberWriteDto(target("1"), empty, emptyList())))
    private fun binding() = CashSourceBindingWriteDto(created("binding"), existing("1"), "wechat", "account", "scope", "provider", "new-key", "strong", null, "1", empty)
    private fun mapping() = CashStatementMappingWriteDto(created("mapping"), "wechat", "provider", "new-account-key", "10")
    private fun failure(changes: CashWriteSetDto, code: String) {
        assertEquals(code, assertFailsWith<CashWriteSetReferenceFailure> { validateCashWriteSetReferences(changes, facts()) }.code)
    }

    @Test fun existingNewAndOpaqueEntryReferencesUseTheirOwnEntityScope() {
        val row = CashTransactionWriteDto(created("row"), values(), listOf(part()))
        val changes = CashWriteSetDto(transactions = listOf(row), relations = listOf(relation(created("row"), fresh("part"))),
            issues = listOf(issue()), entries = listOf(entry()), sourceBindings = listOf(binding()), statementAccountMappings = listOf(mapping()))
        validateCashWriteSetReferences(changes, facts())
        validateCashWriteSetReferences(CashWriteSetDto(deletedEntries = listOf(target("entry-old"))), facts())
        assertEquals(row, changes.transactions.single())
    }

    @Test fun everyVersionedTargetUsesThePersistedRevisionRatherThanTheClientClaim() {
        val changes = listOf(
            CashWriteSetDto(transactions = listOf(CashTransactionWriteDto(target("1", "7"), values(), listOf(part(existing("11")))))),
            CashWriteSetDto(deletedTransactions = listOf(CashTransactionDeleteDto(target("1", "7"), "merge_draft"))),
            CashWriteSetDto(relations = listOf(relation().copy(target = target("3", "7")))),
            CashWriteSetDto(issues = listOf(issue().copy(target = target("4", "7")))),
            CashWriteSetDto(resolvedIssues = listOf(target("4", "7"))),
            CashWriteSetDto(entries = listOf(entry().copy(target = target("entry-old", "7")))),
            CashWriteSetDto(deletedEntries = listOf(target("entry-old", "7"))),
            CashWriteSetDto(sourceBindings = listOf(binding().copy(target = target("5", "7")))),
            CashWriteSetDto(statementAccountMappings = listOf(mapping().copy(target = target("6", "7")))),
        )
        for (change in changes) failure(change, "reference_changed")
        failure(CashWriteSetDto(relations = listOf(relation(target("1", "7")))), "reference_changed")
        failure(CashWriteSetDto(relations = listOf(relation().copy(candidates = listOf(CashRelationCandidateWriteDto(target("2", "7"), existing("22"), empty, "valid"))))), "reference_changed")
    }

    @Test fun absentAndWrongKindReferencesNeverResolveFromOtherEntities() {
        failure(CashWriteSetDto(deletedEntries = listOf(target("1"))), "reference_changed")
        failure(CashWriteSetDto(sourceBindings = listOf(binding().copy(transaction = existing("foreign-workspace")))), "reference_changed")
        failure(CashWriteSetDto(relations = listOf(relation(created("missing"), fresh("part")))), "invalid_reference")
        failure(CashWriteSetDto(statementAccountMappings = listOf(mapping().copy(accountId = "other-account"))), "reference_changed")
        failure(CashWriteSetDto(entries = listOf(entry().copy(categoryId = "other-category"))), "reference_changed")
    }

    @Test fun declarationsCannotOverwriteEachOtherOrReuseAnotherParentsComponent() {
        val row = CashTransactionWriteDto(created("row"), values(), listOf(part()))
        failure(CashWriteSetDto(transactions = listOf(row, row)), "duplicate_reference")
        failure(CashWriteSetDto(transactions = listOf(row.copy(components = listOf(part(), part())))), "duplicate_reference")
        failure(CashWriteSetDto(transactions = listOf(row.copy(components = listOf(part(existing("11")))))), "invalid_reference")
        failure(CashWriteSetDto(transactions = listOf(row.copy(target = target("1"), components = listOf(part(existing("22")))))), "invalid_reference")
        failure(CashWriteSetDto(relations = listOf(relation(firstPart = existing("22")))), "invalid_reference")
        failure(CashWriteSetDto(transactions = listOf(row.copy(target = target("1"), components = listOf(part()))), relations = listOf(relation())), "invalid_reference")
        failure(CashWriteSetDto(entries = listOf(entry(), entry().copy(target = created("entry-two")))), "duplicate_reference")
    }

    @Test fun revisionAndOrdinalAliasesAreRejectedBeforeTheyCanHideConflicts() {
        for (bad in listOf(null, "0", "01", "+1", "9223372036854775808")) {
            failure(CashWriteSetDto(deletedEntries = listOf(target("entry-old", bad))), "invalid_reference")
        }
        val row = CashTransactionWriteDto(created("row"), values(), listOf(part()))
        failure(CashWriteSetDto(transactions = listOf(row.copy(target = row.target.copy(expectedRevision = "1")))), "invalid_reference")
        for (bad in listOf("-1", "00", "+0", "2147483648", "9223372036854775807", "9223372036854775808")) {
            failure(CashWriteSetDto(transactions = listOf(row.copy(components = listOf(part(ordinal = bad))))), "invalid_reference")
        }
        failure(CashWriteSetDto(transactions = listOf(row.copy(components = listOf(part(), part(fresh("other")))))), "duplicate_reference")
        failure(CashWriteSetDto(deletedEntries = listOf(created("entry"))), "invalid_reference")
        validateCashWriteSetReferences(CashWriteSetDto(transactions = listOf(row)), facts())
    }

    @Test fun everyDependencyAndComponentPositionIsCheckedAgainstItsActualParent() {
        val bad = target("2", "7")
        for (change in listOf(
            CashWriteSetDto(relations = listOf(relation().copy(secondaryTransaction = bad))),
            CashWriteSetDto(relations = listOf(relation().copy(anchorTransaction = bad))),
            CashWriteSetDto(issues = listOf(issue().copy(transaction = bad))),
            CashWriteSetDto(issues = listOf(issue().copy(candidates = listOf(bad)))),
            CashWriteSetDto(entries = listOf(entry().copy(rootTransaction = bad))),
            CashWriteSetDto(entries = listOf(entry().copy(identityTransaction = bad))),
            CashWriteSetDto(entries = listOf(entry().copy(members = listOf(CashEntryMemberWriteDto(bad, empty, emptyList()))))),
        )) failure(change, "reference_changed")
        for (edge in listOf(
            relation().copy(secondaryComponent = existing("11")),
            relation().copy(anchorComponent = existing("22")),
            relation().copy(secondaryTransaction = null),
            relation().copy(candidates = listOf(CashRelationCandidateWriteDto(target("2"), existing("11"), empty, "valid"))),
            relation().copy(primaryComponent = fresh("not-declared")),
            relation().copy(primaryComponent = existing("foreign-component")),
        )) failure(CashWriteSetDto(relations = listOf(edge)), if (edge.primaryComponent == existing("foreign-component")) "reference_changed" else "invalid_reference")
        failure(CashWriteSetDto(transactions = listOf(CashTransactionWriteDto(created("row"), values(), listOf(part()))),
            relations = listOf(relation(created("row").copy(expectedRevision = "1"), fresh("part")))), "invalid_reference")
    }

    @Test fun duplicateMutationAndNaturalKeysNeverBecomeLastWriterWins() {
        failure(CashWriteSetDto(transactions = listOf(CashTransactionWriteDto(target("1"), values(), listOf(part(existing("11"))))),
            deletedTransactions = listOf(CashTransactionDeleteDto(target("1"), "merge_draft"))), "duplicate_reference")
        failure(CashWriteSetDto(issues = listOf(issue(), issue().copy(target = created("issue-two")))), "duplicate_reference")
        failure(CashWriteSetDto(issues = listOf(issue().copy(candidates = listOf(target("2"), target("2"))))), "duplicate_reference")
        failure(CashWriteSetDto(relations = listOf(relation().copy(candidates = relation().candidates + relation().candidates))), "duplicate_reference")
        failure(CashWriteSetDto(sourceBindings = listOf(binding(), binding().copy(target = created("binding-two")))), "duplicate_reference")
        failure(CashWriteSetDto(statementAccountMappings = listOf(mapping(), mapping().copy(target = created("mapping-two")))), "duplicate_reference")
        val page = LedgerSourceEmptyPageFactDto("ccb", "1", "document", 2, empty)
        failure(CashWriteSetDto(sourceEmptyPages = listOf(page, page)), "duplicate_reference")
        val row = CashTransactionWriteDto(created("row"), values(), listOf(part()))
        failure(CashWriteSetDto(transactions = listOf(row, row.copy(target = created("row-two")))), "duplicate_reference")
    }

    @Test fun fullLongValuesRemainExactAndBrokenTrustedFactsAreNotBusinessRejections() {
        val original = facts()
        val largeId = "9007199254740993"
        val large = original.copy(cashTransactions = listOf(transaction(largeId).copy(rowRevision = "9223372036854775807")))
        validateCashWriteSetReferences(CashWriteSetDto(transactions = listOf(
            CashTransactionWriteDto(target(largeId, "9223372036854775807"), values(), listOf(part(ordinal = "2147483647")))),
            sourceBindings = listOf(binding().copy(transaction = existing(largeId)))), large)
        assertFailsWith<CashWriteSetReferenceFailure> {
            validateCashWriteSetReferences(CashWriteSetDto(sourceBindings = listOf(binding().copy(transaction = existing("9007199254740992")))), large)
        }
        assertFailsWith<IllegalStateException> {
            validateCashWriteSetReferences(CashWriteSetDto(), original.copy(cashTransactions = original.cashTransactions + original.cashTransactions.first()))
        }
        assertFailsWith<IllegalStateException> {
            validateCashWriteSetReferences(CashWriteSetDto(), original.copy(cashTransactions = listOf(transaction("1").copy(rowRevision = "0"))))
        }
        assertEquals(revision, original.cashTransactions.first().rowRevision)
    }

    @Test fun unchangedPersistedNaturalKeysAndEntryMembersRemainOccupied() {
        failure(CashWriteSetDto(sourceBindings = listOf(binding().copy(identityKey = "key"))), "duplicate_reference")
        failure(CashWriteSetDto(statementAccountMappings = listOf(mapping().copy(sourceAccountKey = "account-key"))), "duplicate_reference")
        failure(CashWriteSetDto(issues = listOf(issue().copy(issueKey = "issue"))), "duplicate_reference")
        val old = facts().copy(cashEntryMembers = listOf(LedgerCashEntryMemberFactDto("1", "entry-old", empty, JsonArray(emptyList()))))
        assertEquals("duplicate_reference", assertFailsWith<CashWriteSetReferenceFailure> {
            validateCashWriteSetReferences(CashWriteSetDto(entries = listOf(entry())), old)
        }.code)
    }

    @Test fun explicitReplacementsAndRemovalsReleaseTheirPreviousNaturalKeys() {
        validateCashWriteSetReferences(CashWriteSetDto(sourceBindings = listOf(binding().copy(target = target("5"), identityKey = "key"))), facts())
        validateCashWriteSetReferences(CashWriteSetDto(statementAccountMappings = listOf(mapping().copy(target = target("6"), sourceAccountKey = "account-key"))), facts())
        validateCashWriteSetReferences(CashWriteSetDto(issues = listOf(issue().copy(target = target("4"), issueKey = "issue"))), facts())
        validateCashWriteSetReferences(CashWriteSetDto(issues = listOf(issue().copy(issueKey = "issue")), resolvedIssues = listOf(target("4"))), facts())
        val old = facts().copy(cashEntryMembers = listOf(LedgerCashEntryMemberFactDto("1", "entry-old", empty, JsonArray(emptyList()))))
        validateCashWriteSetReferences(CashWriteSetDto(entries = listOf(entry().copy(target = target("entry-old")))), old)
        validateCashWriteSetReferences(CashWriteSetDto(entries = listOf(entry()), deletedEntries = listOf(target("entry-old"))), old)
        val removeMember = entry().copy(target = target("entry-old"), members = emptyList())
        validateCashWriteSetReferences(CashWriteSetDto(entries = listOf(entry(), removeMember)), old)
    }
}
