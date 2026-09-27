package com.finance.tracker

import com.finance.tracker.app.*
import com.finance.tracker.core.*
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*
import com.finance.tracker.data.mapping.toStructuredObject

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class FinanceModelMapperTest {
    @Test
    fun roundTripsCashProjectionDtosWithoutChangingExactAmounts() {
        val dto = CashProjectionDto(
            projectionId = "projection-1",
            occurredAt = "2026-09-26T10:30:00Z",
            account = AccountDto(id = 7, name = "Cash", type = "cash"),
            counterparty = "Market",
            category = CashCategoryDto(id = "food", name = "Food"),
            amount = "0.10000000000000001",
            currency = "USD",
            economicType = "expense",
            recordId = "record-1",
        )

        val domain = dto.toDomain()

        assertEquals(dto.amount, domain.amount)
        assertEquals("0.10000000000000001", domain.amount)
        assertEquals(dto, domain.toDto())
    }

    @Test
    fun preservesNestedJsonNumbersAsExactDecimalText() {
        val wireValue = Json.parseToJsonElement("""{"amount":0.10000000000000001,"enabled":true,"label":"cash"}""") as JsonObject

        assertEquals(wireValue, wireValue.toStructuredObject().toJsonObject())
    }

    @Test
    fun mapsCashRecordWriteToTheExistingApiJsonContract() {
        val write = CashRecordWrite(
            occurredAt = "2026-09-26T10:30:00",
            amount = "0.10000000000000001",
            currency = "USD",
            accountName = "Cash",
            recordType = "consumption",
            categoryId = null,
            projectionVersion = 7,
            confirmRelationImpact = true,
        )

        assertEquals(
            Json.parseToJsonElement(
                """{"occurred_at":"2026-09-26T10:30:00","amount":"0.10000000000000001","currency":"USD","counterparty":"","counterparty_account":"","note":"","account_name":"Cash","record_type":"consumption","record_subtype":"not_applicable","category_id":null,"projection_version":7,"confirm_relation_impact":true}""",
            ),
            write.toStructuredObject().toJsonObject(),
        )
    }
}
