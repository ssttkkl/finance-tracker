package com.finance.tracker

import com.finance.tracker.app.*
import com.finance.tracker.core.*
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*
import com.finance.tracker.data.mapping.toStructuredObject

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals

class FinanceModelMapperTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesSharedCashRecordResponsesWithNumericIdsAndExactRelationAmounts() {
        val payload = """
            {
              "record": {
                "id": 123,
                "component_id": "456",
                "occurred_at": "2026-10-19T10:30:00Z",
                "amount": "-12.345678",
                "currency": "CNY",
                "category_id": "leaf",
                "category": {"id":"leaf","name":"早餐","path":[{"id":"food","name":"餐饮"},{"id":"leaf","name":"早餐"}]},
                "record_type": "expense",
                "components": [{"id":456,"cash_transaction_id":123,"amount":"-12.345678","currency":"CNY","ordinal":0}]
              },
              "relations": [{
                "id":"relation-1","kind":"refund_offset","label":"退款冲销","status":"accepted",
                "primary_record":{"id":124,"component_id":"457","occurred_at":"2026-10-19T10:31:00Z","amount":"12.345678","currency":"CNY","record_type":"income"},
                "secondary_record":null,
                "primary_component_id":456,"secondary_component_id":457,"applied_amount":"12.345678"
              }]
            }
        """.trimIndent()

        val dto = json.decodeFromString<CashRecordDetailDto>(payload)
        val detail = dto.toDomain()

        assertEquals("123", detail.record.id)
        assertEquals("456", detail.record.components?.single()?.id)
        assertEquals("123", detail.record.components?.single()?.cashTransactionId)
        assertEquals(listOf("餐饮", "早餐"), detail.record.category?.path?.map { it.name })
        assertEquals("456", detail.relations.single().primaryComponentId)
        assertEquals("457", detail.relations.single().secondaryComponentId)
        assertEquals("12.345678", detail.relations.single().appliedAmount)
        assertEquals("457", detail.relations.single().primaryRecord?.componentId)
    }

    @Test
    fun decodesEvidenceStringRecordIdsAndNumericComponentIds() {
        val payload = """
            {
              "projection_version": 7,
              "projection": {
                "projection_id": "cash:1001",
                "occurred_at": "2026-10-19T10:30:00Z",
                "amount": "-12.345678",
                "currency": "CNY",
                "economic_type": "expense",
                "record_id": "1001"
              },
              "root_record": {
                "id": "1001",
                "occurred_at": "2026-10-19T10:30:00Z",
                "amount": "-12.345678",
                "currency": "CNY",
                "record_type": "expense",
                "components": [{"id":456,"cash_transaction_id":123,"amount":"-12.345678","currency":"CNY","ordinal":0}]
              },
              "members": [{"id":"1001","occurred_at":"2026-10-19T10:30:00Z","amount":"-12.345678","currency":"CNY","record_type":"expense","components":[{"id":456,"cash_transaction_id":123,"amount":"-12.345678","currency":"CNY","ordinal":0}],"roles":["root"]}],
              "accepted_relations": [],
              "inactive_relation_hints": [],
              "refund_timeline": []
            }
        """.trimIndent()

        val evidence = json.decodeFromString<EvidenceDto>(payload)
        val domain = evidence.toDomain()

        assertEquals("1001", evidence.rootRecord.id)
        assertEquals("1001", domain.rootRecord.id)
        assertEquals("456", domain.rootRecord.components?.single()?.id)
        assertEquals("123", domain.rootRecord.components?.single()?.cashTransactionId)
        assertEquals("1001", domain.members.single().id)
    }

    @Test
    fun decodesIndexedPageMonthPositionsWithoutChangingTheirOrdinals() {
        val payload = """
            {
              "projection_version": 7,
              "items": [],
              "page_size": 20,
              "filters": {},
              "filter_options": {},
              "monthly_summaries": [],
              "start_index": 0,
              "total_count": 5,
              "items_before": 0,
              "items_after": 5,
              "month_positions": [
                {"month":"2026-07","start_index":0,"end_index":3},
                {"month":"2026-06","start_index":3,"end_index":5}
              ]
            }
        """.trimIndent()

        val page = json.decodeFromString<CashPageDto>(payload).toDomain()

        assertEquals(
            listOf(CashMonthPosition("2026-07", 0, 3), CashMonthPosition("2026-06", 3, 5)),
            page.monthPositions,
        )
        assertEquals("2026-07", cashLedgerMonthForOrdinal(page.monthPositions, 2))
        assertEquals("2026-06", cashLedgerMonthForOrdinal(page.monthPositions, 3))
        assertEquals(null, cashLedgerMonthForOrdinal(page.monthPositions, 5))
    }

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
