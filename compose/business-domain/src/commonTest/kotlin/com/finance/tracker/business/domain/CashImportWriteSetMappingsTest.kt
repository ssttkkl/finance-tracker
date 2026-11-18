package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CashImportWriteSetMappingsTest {
    private fun facts() = LedgerFactsDto(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0").copy(
        accounts = listOf(LedgerAccountFactDto("10", "Cash", "cash", true, JsonArray(listOf(JsonPrimitive("CNY"))), "1")))
    private fun mapping() = CashStatementMappingWriteDto(CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("mapping"), null),
        "alipay", "funding_group_v1", "opaque-group-key", "10")
    private fun changes(mapping: CashStatementMappingWriteDto = mapping()) = CashWriteSetDto(statementAccountMappings = listOf(mapping))
    private fun prepare(input: CashWriteSetDto = changes(), source: LedgerFactsDto = facts()) = prepareCashImportWriteSetMappings(input, source)
    private fun denied(input: CashWriteSetDto, source: LedgerFactsDto = facts()) {
        assertFailsWith<CashPolicyError> { prepare(input, source) }
    }

    @Test fun finalMappingNeedsNoRowsGroupsOrSourceAllocationReplay() {
        val input = changes()
        assertEquals(input, prepare(input))
        assertEquals(CashWriteSetDto(), prepare(CashWriteSetDto()))
        for (source in listOf("alipay", "icbc_credit", "icbc_debit", "icbc_asia", "ccb_debit"))
            assertEquals(source, prepare(changes(mapping().copy(sourceType = source))).statementAccountMappings.single().sourceType)
    }

    @Test fun publishedAccountsMustExistBeActiveAndSupportCash() {
        denied(changes(mapping().copy(accountId = null)))
        denied(changes(mapping().copy(accountId = "other-workspace-account")))
        val original = facts()
        for (account in listOf(original.accounts.single().copy(active = false), original.accounts.single().copy(type = "investment")))
            denied(changes(), original.copy(accounts = listOf(account)))
        for (type in listOf("cash", "loan", "lend"))
            assertEquals(changes(), prepare(changes(), original.copy(accounts = listOf(original.accounts.single().copy(type = type)))))
    }

    @Test fun channelsKindAndNonemptyNaturalKeyAreCheckedWithoutReconstructingTheGroup() {
        for (source in listOf("wechat", "unknown", "")) denied(changes(mapping().copy(sourceType = source)))
        denied(changes(mapping().copy(identityKind = "file_account")))
        denied(changes(mapping().copy(sourceAccountKey = "")))
        denied(changes(mapping().copy(sourceAccountKey = "g".repeat(256))))
        assertEquals("g".repeat(255), prepare(changes(mapping().copy(sourceAccountKey = "g".repeat(255))))
            .statementAccountMappings.single().sourceAccountKey)
    }

    @Test fun existingNaturalIdentityAndExpectedRevisionCannotBeRewritten() {
        val old = LedgerStatementMappingFactDto("20", "alipay", "funding_group_v1", "opaque-group-key", "10", "actor", "3", "time", "time")
        val source = facts().copy(statementAccountMappings = listOf(old))
        val update = mapping().copy(target = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("20"), "3"))
        assertEquals(changes(update), prepare(changes(update), source))
        denied(changes(update.copy(sourceAccountKey = "another-key")), source)
        denied(changes(update.copy(sourceType = "ccb_debit")), source)
        denied(changes(update.copy(target = update.target.copy(expectedRevision = "2"))), source)
        assertEquals(changes(), prepare(changes(), source))
    }

    @Test fun exactNewMappingKeepsItsNewReferenceWithoutFabricatingAnObservation() {
        val old = LedgerStatementMappingFactDto("9007199254740993", "alipay", "funding_group_v1", "opaque-group-key", "10",
            "original-actor", "9223372036854775807", "original-created", "original-updated")
        val source = facts().copy(statementAccountMappings = listOf(old))
        val input = changes()
        assertSame(input, prepare(input, source))
        assertIs<NewCashWriteReferenceDto>(input.statementAccountMappings.single().target.reference)
        assertNull(input.statementAccountMappings.single().target.expectedRevision)
        denied(changes(mapping().copy(target = mapping().target.copy(expectedRevision = "1"))), source)
        denied(changes(), source.copy(accounts = source.accounts.map { it.copy(active = false) }))
    }

    @Test fun exactNaturalIdentityStillRejectsTwoDeclarationsAndNewExistingAliases() {
        val old = LedgerStatementMappingFactDto("20", "alipay", "funding_group_v1", "opaque-group-key", "10", "actor", "3", "time", "time")
        val source = facts().copy(statementAccountMappings = listOf(old))
        val first = mapping()
        val second = first.copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("second"), null))
        val existing = first.copy(target = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("20"), "3"))
        for (rows in listOf(listOf(first, second), listOf(first, existing), listOf(existing, first)))
            denied(CashWriteSetDto(statementAccountMappings = rows), source)
        denied(changes(existing.copy(target = existing.target.copy(expectedRevision = "2"))), source)
    }

    @Test fun sameNaturalKeyWithDifferentAccountRequiresAnObservedExistingTarget() {
        val original = facts()
        val source = original.copy(accounts = original.accounts + original.accounts.single().copy(id = "11"),
            statementAccountMappings = listOf(LedgerStatementMappingFactDto("20", "alipay", "funding_group_v1", "opaque-group-key", "10", "actor", "3", "time", "time")))
        val changed = mapping().copy(accountId = "11")
        denied(changes(changed), source)
        val observed = changed.copy(target = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("20"), "3"))
        assertEquals(changes(observed), prepare(changes(observed), source))
        denied(changes(), source.copy(statementAccountMappings = source.statementAccountMappings.map { it.copy(accountId = null) }))
    }

    @Test fun duplicateNaturalKeysAndUnknownReferencesRejectBeforePersistence() {
        val first = mapping()
        val second = first.copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("second"), null))
        denied(CashWriteSetDto(statementAccountMappings = listOf(first, second)))
        denied(changes(first.copy(target = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("missing"), "1"))))
    }
}
