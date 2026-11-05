package com.finance.tracker.backend

import java.math.BigDecimal
import kotlin.test.*
import kotlinx.serialization.json.*

class CashRelationGeometryTest {
    private val golden: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-relation-geometry.json")!!
            .bufferedReader().use { it.readText() }).jsonObject
    }
    private fun fact(id: String = "1", billSource: String = "alipay", source: String = "alipay",
        sourceIdentity: String = "", occurredAt: Any? = "2026-10-01T10:00:00+00:00",
        payload: Map<String, Any?>? = null, counterparty: String = "商户", note: String = "备注",
        recordId: String = "ALI-1") = RelationFact(id = id, amount = BigDecimal("-10.00"), currency = "CNY",
        accountId = "a", occurredAt = occurredAt, counterparty = counterparty, note = note,
        billSource = billSource, source = source, sourceIdentity = sourceIdentity, rawPayload = payload, recordId = recordId)

    @Test fun geometryAndMirrorGraphMatchPython() {
        val base = fact()
        assertEquals(golden.getValue("delta").jsonArray.map { it.jsonPrimitive.long }, listOf(
            CashRelationGeometry.timeDeltaSeconds("2026-10-01T10:00:00+08:00", "2026-10-01T02:00:05+00:00"),
            CashRelationGeometry.timeDeltaSeconds("2026-10-01T10:00:00", "2026-10-01T10:00:02Z")))
        assertEquals(golden.getValue("same_day").jsonArray.map { it.jsonPrimitive.boolean }, listOf(
            CashRelationGeometry.sameCalendarDay("2026-10-01T23:00:00+00:00", "2026-10-01T00:00:01+00:00"),
            CashRelationGeometry.sameCalendarDay("2026-10-01T23:00:00+00:00", "2026-10-02T00:00:01+00:00")))
        val dateOnly = listOf("2026-10-01", "20261001", "2026-10-01T00:00:00Z", "2026-10-01 00:00", "2026/10/01",
            "2026-10-01T10:00:00Z", "")
        assertEquals(golden.getValue("date_only").jsonArray.map { it.jsonPrimitive.boolean },
            dateOnly.map(CashRelationGeometry::isDateOnlyBusinessString))
        val days = listOf(
            CashRelationGeometry.businessDayUtc(fact(payload = mapOf("date" to "2026-10-01")))?.toString(),
            CashRelationGeometry.businessDayUtc(fact(payload = mapOf("date" to "20261002")))?.toString(),
            CashRelationGeometry.businessDayUtc(fact(occurredAt = "2026-10-01 10:00:00"))?.toString(),
            CashRelationGeometry.businessDayUtc(fact(occurredAt = "bad"))?.toString(),
            CashRelationGeometry.businessDayUtc(fact(occurredAt = ""))?.toString())
        assertEquals(golden.getValue("business_day").jsonArray.map { if (it == JsonNull) null else it.jsonPrimitive.content }, days)
        val bank = listOf(
            CashRelationGeometry.factIsBankDateOnly(fact(billSource = "icbc_debit", payload = mapOf("date" to "2026-10-01"))),
            CashRelationGeometry.factIsBankDateOnly(fact(billSource = "icbc_debit", payload = mapOf("date" to "2026-10-01T10:00:00"))),
            CashRelationGeometry.factIsBankDateOnly(fact(billSource = "icbc_debit", occurredAt = "2026-10-01T16:00:00+00:00")),
            CashRelationGeometry.factIsBankDateOnly(fact(occurredAt = "2026-10-01T16:00:00+00:00")))
        assertEquals(golden.getValue("bank_date_only").jsonArray.map { it.jsonPrimitive.boolean }, bank)
        val tails = listOf("尾号：1234", "****abcd", "储蓄卡1234", "卡号 6222 1234", "订单1234", "")
            .map { CashRelationGeometry.extractCardTails(it).sorted() }
        assertEquals(golden.getValue("tails"), sourceJson(tails))
        val cross = listOf(CashRelationGeometry.mainStyleCrossVerify("支付宝商户", "支付宝"),
            CashRelationGeometry.mainStyleCrossVerify("abc", "xyz"),
            CashRelationGeometry.mainStyleCrossVerify(mapOf("counterparty" to "商户", "note" to "很长备注"),
                mapOf("counterparty" to "其他", "note" to "备注")))
        assertEquals(golden.getValue("cross").jsonArray.map { it.jsonPrimitive.boolean }, cross)
        assertEquals(golden.getValue("cross_kind").jsonArray.map { it.jsonPrimitive.boolean }, listOf(
            CashRelationGeometry.crossKindCompatible(listOf("payment_mirror"), "transfer_pair"),
            CashRelationGeometry.crossKindCompatible(listOf("transfer_pair", "payment_mirror"), "refund_offset")))
        val mirror = golden.getValue("mirror").jsonObject
        assertEquals(mirror.getValue("scores").jsonArray.map { it.jsonPrimitive.int }, listOf(
            CashRelationGeometry.platformScore(base),
            CashRelationGeometry.platformScore(fact(billSource = "icbc_debit", source = "")),
            CashRelationGeometry.platformScore(fact(billSource = "unknown", source = ""))))
        assertEquals(mirror.getValue("canonical").jsonArray.map { if (it == JsonNull) "none" else it.jsonPrimitive.content },
            listOf(CashRelationGeometry.canonicalMirrorFact(listOf(base))?.id ?: "none",
                CashRelationGeometry.canonicalMirrorFact(listOf(base, fact(id = "2", billSource = "wechat", source = "wechat",
                    sourceIdentity = "named")))?.id ?: "none",
                CashRelationGeometry.canonicalMirrorFact(listOf(base, fact(id = "2")))?.id ?: "none"))
        val components = CashRelationGeometry.buildMirrorComponents(listOf("1", "2", "3", "4"),
            listOf("1" to "2", "2" to "3")).map { it.sorted() }.sortedBy { it.joinToString() }
        assertEquals(mirror.getValue("components"), sourceJson(components))
    }
}
