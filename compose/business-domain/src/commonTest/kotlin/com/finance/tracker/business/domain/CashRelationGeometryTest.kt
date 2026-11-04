package com.finance.tracker.business.domain

import kotlin.test.*
import kotlinx.serialization.json.*

class CashRelationGeometryTest {
    private val golden=Json.parseToJsonElement("""{"delta":[5,2],"same_day":[true,false],"date_only":[true,true,true,true,false,false,false],"business_day":["2026-10-01","2026-10-02","2026-10-01",null,null],"bank_date_only":[true,false,false,false],"tails":[["1234"],[],["1234"],["1234","6222"],[],[]],"cross":[true,false,true],"cross_kind":[true,false],"mirror":{"scores":[2,1,0],"canonical":["1","none","none"],"components":[["1","2","3"],["4"]]}}""").jsonObject
    private fun sourceJson(value:Any?)=sourceJson(value,{null},::pythonFloatText)
    private fun fact(id: String = "1", billSource: String = "alipay", source: String = "alipay",
        sourceIdentity: String = "", occurredAt: Any? = "2026-10-01T10:00:00+00:00",
        payload: Map<String, Any?>? = null, counterparty: String = "商户", note: String = "备注",
        recordId: String = "ALI-1") = RelationFact(id = id, amount = CashAmount.parse("-10.00"), currency = "CNY",
        accountId = "a", occurredAt = occurredAt, counterparty = counterparty, note = note,
        billSource = billSource, source = source, sourceIdentity = sourceIdentity, rawPayload = payload, recordId = recordId)

    @Test fun geometryAndMirrorGraphMatchPython() {
        val base = fact()
        assertEquals(golden.getValue("delta").jsonArray.map { it.jsonPrimitive.long }, listOf(
            cashRelationGeometry.timeDeltaSeconds("2026-10-01T10:00:00+08:00", "2026-10-01T02:00:05+00:00"),
            cashRelationGeometry.timeDeltaSeconds("2026-10-01T10:00:00", "2026-10-01T10:00:02Z")))
        assertEquals(golden.getValue("same_day").jsonArray.map { it.jsonPrimitive.boolean }, listOf(
            cashRelationGeometry.sameCalendarDay("2026-10-01T23:00:00+00:00", "2026-10-01T00:00:01+00:00"),
            cashRelationGeometry.sameCalendarDay("2026-10-01T23:00:00+00:00", "2026-10-02T00:00:01+00:00")))
        val dateOnly = listOf("2026-10-01", "20261001", "2026-10-01T00:00:00Z", "2026-10-01 00:00", "2026/10/01",
            "2026-10-01T10:00:00Z", "")
        assertEquals(golden.getValue("date_only").jsonArray.map { it.jsonPrimitive.boolean },
            dateOnly.map(cashRelationGeometry::isDateOnlyBusinessString))
        val days = listOf(
            cashRelationGeometry.businessDayUtc(fact(payload = mapOf("date" to "2026-10-01")))?.toString(),
            cashRelationGeometry.businessDayUtc(fact(payload = mapOf("date" to "20261002")))?.toString(),
            cashRelationGeometry.businessDayUtc(fact(occurredAt = "2026-10-01 10:00:00"))?.toString(),
            cashRelationGeometry.businessDayUtc(fact(occurredAt = "bad"))?.toString(),
            cashRelationGeometry.businessDayUtc(fact(occurredAt = ""))?.toString())
        assertEquals(golden.getValue("business_day").jsonArray.map { if (it == JsonNull) null else it.jsonPrimitive.content }, days)
        val bank = listOf(
            cashRelationGeometry.factIsBankDateOnly(fact(billSource = "icbc_debit", payload = mapOf("date" to "2026-10-01"))),
            cashRelationGeometry.factIsBankDateOnly(fact(billSource = "icbc_debit", payload = mapOf("date" to "2026-10-01T10:00:00"))),
            cashRelationGeometry.factIsBankDateOnly(fact(billSource = "icbc_debit", occurredAt = "2026-10-01T16:00:00+00:00")),
            cashRelationGeometry.factIsBankDateOnly(fact(occurredAt = "2026-10-01T16:00:00+00:00")))
        assertEquals(golden.getValue("bank_date_only").jsonArray.map { it.jsonPrimitive.boolean }, bank)
        val tails = listOf("尾号：1234", "****abcd", "储蓄卡1234", "卡号 6222 1234", "订单1234", "")
            .map { cashRelationGeometry.extractCardTails(it).sorted() }
        assertEquals(golden.getValue("tails"), sourceJson(tails))
        val cross = listOf(cashRelationGeometry.mainStyleCrossVerify("支付宝商户", "支付宝"),
            cashRelationGeometry.mainStyleCrossVerify("abc", "xyz"),
            cashRelationGeometry.mainStyleCrossVerify(mapOf("counterparty" to "商户", "note" to "很长备注"),
                mapOf("counterparty" to "其他", "note" to "备注")))
        assertEquals(golden.getValue("cross").jsonArray.map { it.jsonPrimitive.boolean }, cross)
        assertEquals(golden.getValue("cross_kind").jsonArray.map { it.jsonPrimitive.boolean }, listOf(
            cashRelationGeometry.crossKindCompatible(listOf("payment_mirror"), "transfer_pair"),
            cashRelationGeometry.crossKindCompatible(listOf("transfer_pair", "payment_mirror"), "refund_offset")))
        val mirror = golden.getValue("mirror").jsonObject
        assertEquals(mirror.getValue("scores").jsonArray.map { it.jsonPrimitive.int }, listOf(
            cashRelationGeometry.platformScore(base),
            cashRelationGeometry.platformScore(fact(billSource = "icbc_debit", source = "")),
            cashRelationGeometry.platformScore(fact(billSource = "unknown", source = ""))))
        assertEquals(mirror.getValue("canonical").jsonArray.map { if (it == JsonNull) "none" else it.jsonPrimitive.content },
            listOf(cashRelationGeometry.canonicalMirrorFact(listOf(base))?.id ?: "none",
                cashRelationGeometry.canonicalMirrorFact(listOf(base, fact(id = "2", billSource = "wechat", source = "wechat",
                    sourceIdentity = "named")))?.id ?: "none",
                cashRelationGeometry.canonicalMirrorFact(listOf(base, fact(id = "2")))?.id ?: "none"))
        val components = cashRelationGeometry.buildMirrorComponents(listOf("1", "2", "3", "4"),
            listOf("1" to "2", "2" to "3")).map { it.sorted() }.sortedBy { it.joinToString() }
        assertEquals(mirror.getValue("components"), sourceJson(components))
    }

    @Test fun businessDatesKeepRawPriorityYearZeroAndOffsetTruncation() {
        assertEquals("0000-02-29",cashRelationGeometry.businessDayUtc(fact(payload=mapOf("date" to "00000229"))))
        assertNull(cashRelationGeometry.businessDayUtc(fact(payload=mapOf("date" to "2026-02-29"))))
        assertNull(cashRelationGeometry.businessDayUtc(fact(payload=mapOf("date" to "2026-1-001"))))
        assertNull(cashRelationGeometry.businessDayUtc(fact(payload=mapOf("date" to "bad","occurred_at" to "2026-10-01"))))
        assertEquals("2026-10-01",cashRelationGeometry.businessDayUtc(fact(occurredAt="2026-10-01T23:00:00.123456-02:00")))
        assertEquals("2026-10-02",cashRelationGeometry.businessDayUtc(fact(occurredAt="2026-10-01T23:00:00-02:00")))
        assertTrue(cashRelationGeometry.factIsBankDateOnly(fact(payload=mapOf("date" to "2026-02-29"))))
        assertEquals(setOf("1234"),cashRelationGeometry.extractCardTails("ending\t: 1234"))
        assertTrue(cashRelationGeometry.extractCardTails("ending\u30001234").isEmpty())
        assertEquals(setOf("1234"),cashRelationGeometry.extractCardTails("Endİng1234"))
        assertEquals(setOf("1234"),cashRelationGeometry.extractCardTails("endıng1234"))
    }
}
