package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CashWriteSetFinancialValuesTest {
    private val empty = JsonObject(emptyMap())
    private val attributes = JsonArray(emptyList())
    private val target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("row"), null)
    private fun values() = CashTransactionWriteValuesDto("account", "posted", "import", null, "atomic", "wechat", "record",
        empty, empty, null, empty, "2026-01-01T08:00:00+08:00", "-5.00", "CNY", "", "", attributes, "", null, "consumption", "not_applicable")
    private fun part(amount: String? = "-5.00", account: String? = "account", currency: String? = "CNY", ordinal: String = "0") =
        CashComponentWriteDto(NewCashWriteReferenceDto("part-$ordinal"), account, amount, currency, ordinal, "", "", empty)
    private fun row(values: CashTransactionWriteValuesDto = values(), parts: List<CashComponentWriteDto> = listOf(part())) =
        CashTransactionWriteDto(target, values, parts)
    private fun normalize(row: CashTransactionWriteDto) = normalizeCashWriteSetFinancialValues(CashWriteSetDto(transactions = listOf(row))).transactions.single()
    private fun failure(row: CashTransactionWriteDto, code: String, field: String? = null) {
        val error = assertFailsWith<CashPolicyError> { normalize(row) }
        assertEquals(code, error.code)
        if (field != null) assertContains(error.fields, field)
    }

    @Test fun exactAmountsAndTimesAreReturnedForPersistenceWithoutChangingOtherFacts() {
        val payload = Json.parseToJsonElement("{\"large\":9007199254740993,\"float\":1.00e+2}")
        val input = row(values().copy(amount = " -１２_３.４５００ ", sourcePayload = payload,
            occurredAt = "2026-01-01T08:00:00.123456789+08:00", note = " note "), listOf(part("-1.234500E2")))
        val output = normalize(input)
        assertEquals("-123.4500", output.values.amount)
        assertEquals("-123.4500", output.components.single().amount)
        assertEquals("2026-01-01T00:00:00.123456+00:00", output.values.occurredAt)
        assertEquals(input.copy(values = input.values.copy(amount = output.values.amount, occurredAt = output.values.occurredAt),
            components = input.components.map { it.copy(amount = "-123.4500") }), output)
        assertSame(payload, output.values.sourcePayload)
        assertEquals(" -１２_３.４５００ ", input.values.amount)
        assertEquals(output, normalize(output))
    }

    @Test fun columnBoundariesAndZeroRepresentationsDoNotUseBinaryFloatingPoint() {
        val maximum = "99999999999999999999.999999999999999999"
        assertEquals(maximum, normalize(row(values().copy(amount = maximum), listOf(part(maximum)))).values.amount)
        for ((text, expected) in listOf("-0.000" to "-0.000", "1.2300" to "1.2300", "0E2147483647" to "0",
            "-0E999999999999999999" to "-0", "1e-18" to "0.000000000000000001")) {
            val output = normalize(row(values().copy(amount = text), listOf(part(text))))
            assertEquals(expected, output.values.amount, text)
            assertEquals(expected, output.components.single().amount, text)
        }
        for (bad in listOf("NaN", "Infinity", "1e20", "1e-19", "1e2147483647", "1e-2147483648", "not money")) {
            failure(row(values().copy(amount = bad)), "invalid_field", "transactions[0].values.amount")
            failure(row(parts = listOf(part(bad))), "invalid_field", "transactions[0].components[0].amount")
        }
    }

    @Test fun cashRangeUsesConvertedUtcAndIncludesTheFinalMicrosecond() {
        for ((input, expected) in listOf(
            "1899-12-31T23:00:00-01:00" to "1900-01-01T00:00:00+00:00",
            "2101-01-01T00:59:59.999999+01:00" to "2100-12-31T23:59:59.999999+00:00",
        )) assertEquals(expected, normalize(row(values().copy(occurredAt = input))).values.occurredAt)
        for (bad in listOf("1900-01-01T00:00:00+00:01", "2100-12-31T23:59:59-00:01", "2026-02-29T00:00:00Z",
            "2026-01-01T00:00:00", "2101-01-01T00:00:00Z")) {
            failure(row(values().copy(occurredAt = bad)), "invalid_field", "transactions[0].values.occurred_at")
            failure(row(values().copy(state = "draft", occurredAt = bad)), "invalid_field", "transactions[0].values.occurred_at")
        }
    }

    @Test fun incompleteDraftsRemainDraftsButKnownContradictionsAreRejected() {
        val draft = values().copy(state = "draft", accountId = null, amount = null, currency = null, occurredAt = null,
            recordType = null, recordSubtype = null)
        assertEquals(row(draft, emptyList()), normalize(row(draft, emptyList())))
        failure(row(draft.copy(currency = "cny"), emptyList()), "invalid_field", "transactions[0].values.currency")
        failure(row(draft.copy(amount = "-5", currency = "CNY"), listOf(part("5"))), "component_direction_mismatch")
        failure(row(draft.copy(recordType = "unknown"), emptyList()), "invalid_record_type")
        failure(row(draft.copy(state = "other"), emptyList()), "invalid_state")
    }

    @Test fun postedFactsRequireCompleteAndConsistentComponentsAndTypes() {
        failure(row(values().copy(occurredAt = null)), "incomplete_record", "transactions[0].values.occurred_at")
        failure(row(values().copy(accountId = null)), "incomplete_record", "transactions[0].values.account_id")
        failure(row(values().copy(amount = null)), "incomplete_record", "transactions[0].values.amount")
        failure(row(values().copy(currency = null)), "incomplete_record", "transactions[0].values.currency")
        failure(row(values().copy(recordType = null)), "incomplete_record", "transactions[0].values.record_type")
        failure(row(values().copy(recordSubtype = null)), "incomplete_record", "transactions[0].values.record_subtype")
        failure(row(parts = emptyList()), "incomplete_record", "transactions[0].components")
        failure(row(parts = listOf(part(amount = null))), "incomplete_record")
        failure(row(values().copy(recordSubtype = "other")), "invalid_record_type")
        failure(row(parts = listOf(part("-6"))), "component_total_mismatch")
        failure(row(parts = listOf(part("5"))), "component_direction_mismatch")
        failure(row(parts = listOf(part(currency = "USD"))), "component_currency_mismatch")
        failure(row(parts = listOf(part(account = "other"))), "component_account_mismatch")
        failure(row(values().copy(cashGranularity = "aggregate")), "component_account_mismatch")
        failure(row(values().copy(cashGranularity = "other")), "invalid_field")
        for (ordinal in listOf("-1", "00", "+0", "2147483648")) failure(row(parts = listOf(part(ordinal = ordinal))), "invalid_field")
    }

    @Test fun aggregateComponentsHaveExactTotalsWithoutChangingTheirDeclaredOrder() {
        val input = row(values().copy(accountId = null, cashGranularity = "aggregate", amount = "-3"),
            listOf(part("-1.00", "a", ordinal = "7"), part("-2.0", "b", ordinal = "0")))
        val normalized = normalize(input)
        assertEquals(listOf("7", "0"), normalized.components.map { it.ordinal })
        assertEquals(listOf("-1.00", "-2.0"), normalized.components.map { it.amount })
        failure(input.copy(components = input.components.map { it.copy(ordinal = "0") }), "duplicate_component_ordinal")
        failure(input.copy(components = input.components.map { it.copy(amount = "-1") }), "component_total_mismatch")
    }

    @Test fun relationAmountNormalizationDoesNotPretendToValidateTheRelationshipGraph() {
        val relation = CashRelationWriteDto(target, "not-a-validated-kind", target, null, null, null, target, null, null, null,
            "not-a-validated-state", true, null, "user", empty, "-1.2300E1", "rule", "", emptyList())
        val changes = CashWriteSetDto(relations = listOf(relation))
        assertEquals(changes.copy(relations = listOf(relation.copy(appliedAmount = "-12.300"))), normalizeCashWriteSetFinancialValues(changes))
        for (bad in listOf("NaN", "1e20", "1e-19")) {
            val error = assertFailsWith<CashPolicyError> { normalizeCashWriteSetFinancialValues(changes.copy(relations = listOf(relation.copy(appliedAmount = bad)))) }
            assertEquals("invalid_field", error.code)
            assertEquals(listOf("relations[0].applied_amount"), error.fields)
        }
    }

    @Test fun failureNeverReturnsAPartialWriteSetOrMutatesTheOriginal() {
        val first = row()
        val invalid = row(values().copy(amount = "invalid"))
        val changes = CashWriteSetDto(transactions = listOf(first, invalid))
        val error = assertFailsWith<CashPolicyError> { normalizeCashWriteSetFinancialValues(changes) }
        assertEquals(listOf("transactions[1].values.amount"), error.fields)
        assertEquals("2026-01-01T08:00:00+08:00", changes.transactions.first().values.occurredAt)
        assertEquals(listOf(first, invalid), changes.transactions)
    }
}
