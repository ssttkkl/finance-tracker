package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CashWriteSetFixedRecordsTest {
    private val empty = JsonObject(emptyMap())
    private val array = JsonArray(emptyList())
    private val time = "2026-01-01T00:00:00Z"
    private val target = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("1"), "1")
    private fun facts(origin: String = "import", state: String = "posted"): LedgerFactsDto {
        val row = LedgerCashTransactionFactDto("1", "10", state, origin, null, "1", "atomic", "wechat", "record", empty, empty,
            null, empty, time, "-5.00", "CNY", "", "", array, "", null, "consumption", "not_applicable", time, null, "", "")
        return LedgerFactsDto(listOf("10", "20").map { LedgerAccountFactDto(it, it, "cash", true, JsonArray(listOf(JsonPrimitive("CNY"), JsonPrimitive("USD"))), "1") },
            emptyList(), emptyList(), emptyList(), listOf(row), listOf(LedgerCashComponentFactDto("11", "1", "10", "-5.00", "CNY", "0", "", "", empty, time)),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0")
    }
    private fun row(origin: String = "import", state: String = "posted") = CashTransactionWriteDto(target,
        CashTransactionWriteValuesDto("10", state, origin, null, "atomic", "wechat", "record", empty, empty, null, empty,
            time, "-5.00", "CNY", "", "", array, "", null, "consumption", "not_applicable"),
        listOf(CashComponentWriteDto(ExistingCashWriteReferenceDto("11"), "10", "-5.00", "CNY", "0", "", "", empty)))
    private fun validate(row: CashTransactionWriteDto, facts: LedgerFactsDto = facts()) =
        validateCashWriteSetFixedRecords(CashWriteSetDto(transactions = listOf(row)), facts)
    private fun denied(row: CashTransactionWriteDto, facts: LedgerFactsDto = facts(), field: String) {
        val failure = assertFailsWith<CashPolicyError> { validate(row, facts) }
        assertEquals("fixed_field_conflict", failure.code)
        assertContains(failure.fields, "transactions[0].values.$field")
    }

    @Test fun semanticallyIdenticalFinancialValuesAndPublicTextUpdatesDoNotConflict() {
        val input = row().copy(values = row().values.copy(amount = "-5e0", occurredAt = "2026-01-01T08:00:00+08:00", note = "new note"),
            components = row().components.map { it.copy(amount = "-5.0") })
        validate(input)
        assertEquals("-5e0", input.values.amount)
    }

    @Test fun postedImportAndLegacyFinancialFieldsAndTypesRemainFixed() {
        for (origin in listOf("import", "legacy")) {
            val base = row(origin)
            val source = facts(origin)
            denied(base.copy(values = base.values.copy(amount = "-6"), components = base.components.map { it.copy(amount = "-6") }), source, "amount")
            denied(base.copy(values = base.values.copy(state = "draft", amount = "-6"), components = base.components.map { it.copy(amount = "-6") }), source, "amount")
            denied(base.copy(values = base.values.copy(occurredAt = "2026-01-02T00:00:00Z")), source, "occurred_at")
            denied(base.copy(values = base.values.copy(currency = "USD"), components = base.components.map { it.copy(currency = "USD") }), source, "currency")
            denied(base.copy(values = base.values.copy(accountId = "20"), components = base.components.map { it.copy(accountId = "20") }), source, "account_id")
            denied(base.copy(values = base.values.copy(cashGranularity = "aggregate", accountId = null)), source, "cash_granularity")
            denied(base.copy(values = base.values.copy(recordType = "fee")), source, "record_type")
        }
    }

    @Test fun callerCannotSpoofCreationMethodOrAnotherImmutableSourceIdentity() {
        val base = row()
        denied(base.copy(values = base.values.copy(recordOrigin = "manual", amount = "-6"), components = base.components.map { it.copy(amount = "-6") }), field = "record_origin")
        for ((field, value) in listOf("source_type" to base.values.copy(sourceType = "bank"),
            "record_id" to base.values.copy(recordId = "other"), "manual_transfer_id" to base.values.copy(manualTransferId = "group"))) {
            denied(base.copy(values = value), field = field)
            denied(base.copy(values = value.copy(state = "draft")), facts(state = "draft"), field)
        }
    }

    @Test fun postedComponentsCannotBeRecreatedUsingANewReferenceWithTheSameText() {
        val base = row()
        val failure = assertFailsWith<CashPolicyError> {
            validate(base.copy(components = base.components.map { it.copy(reference = NewCashWriteReferenceDto("11")) }))
        }
        assertEquals("fixed_field_conflict", failure.code)
        assertContains(failure.fields, "transactions[0].components")
    }

    @Test fun editableDraftAndManualFinancialFieldsStillUseTheOriginalRecordPolicy() {
        for ((origin, state) in listOf("import" to "draft", "manual" to "posted")) {
            val base = row(origin, state)
            validate(base.copy(values = base.values.copy(amount = "-6", recordType = "fee"), components = base.components.map { it.copy(amount = "-6") }), facts(origin, state))
        }
        val base = row(state = "draft")
        validate(base.copy(components = base.components.map { it.copy(reference = NewCashWriteReferenceDto("replacement")) }), facts(state = "draft"))
    }

    @Test fun importCreationCannotClaimManualAuthorityOrATransferGroup() {
        val base = row().copy(target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("new"), null),
            values = row().values.copy(recordId = "new"), components = row().components.map { it.copy(reference = NewCashWriteReferenceDto("part")) })
        validate(base)
        for (origin in listOf("manual", "legacy", "manual_transfer")) {
            denied(base.copy(values = base.values.copy(recordOrigin = origin)), field = "record_origin")
        }
        denied(base.copy(values = base.values.copy(manualTransferId = "manual-group")), field = "manual_transfer_id")
    }

    @Test fun ordinalDefinesStoredComponentOrderWithoutChangingEitherInputList() {
        val old = facts()
        val stored = old.copy(cashTransactions = old.cashTransactions.map { it.copy(accountId = null, cashGranularity = "aggregate") },
            cashTransactionComponents = listOf(old.cashTransactionComponents.single().copy(amount = "-2", ordinal = "7"),
                old.cashTransactionComponents.single().copy(id = "12", amount = "-3", ordinal = "0")))
        val base = row()
        val input = base.copy(values = base.values.copy(accountId = null, cashGranularity = "aggregate"), components = listOf(
            base.components.single().copy(amount = "-2", ordinal = "7"), base.components.single().copy(reference = ExistingCashWriteReferenceDto("12"), amount = "-3", ordinal = "0")))
        validate(input, stored)
        validate(input.copy(components = input.components.reversed()), stored)
        assertEquals(listOf("7", "0"), input.components.map { it.ordinal })
        assertEquals(listOf("7", "0"), stored.cashTransactionComponents.map { it.ordinal })
        val wrong = input.copy(components = input.components.map { it.copy(ordinal = if (it.ordinal == "7") "0" else "7") })
        val failure = assertFailsWith<CashPolicyError> { validate(wrong, stored) }
        assertEquals("fixed_field_conflict", failure.code)
        assertContains(failure.fields, "transactions[0].components")
    }
}
