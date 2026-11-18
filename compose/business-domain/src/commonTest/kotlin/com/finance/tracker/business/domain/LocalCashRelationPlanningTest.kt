package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class LocalCashRelationPlanningTest {
    private fun fact(id: String, amount: String, record: String = id, type: String = "consumption") =
        RelationFact(id, LocalCashRelationArithmetic.amount(amount), "CNY", "a", occurredAt = "2026-10-01T10:00:00Z",
            counterparty = "商户", recordType = type, billSource = "alipay", source = "alipay", recordId = record)

    @Test fun phaseAHardKeyConsumesRemainingBeforeMerchantDiscoveryAndLeavesInputsUnchanged() {
        val expense = fact("e", "-10.0000", "ORDER")
        val first = fact("r1", "3.00", "ORDER_1", "refund")
        val second = fact("r2", "8.00", "OTHER", "refund")
        val facts = listOf(expense, first, second)
        val remaining = mapOf("e" to LocalCashRelationArithmetic.amount("10.0000"))
        val plan = LocalCashRelationPlanning.rules.planRelationProposals(facts, remainingByExpense = remaining)
        val accepted = plan.proposals.filter { it.status == "accepted" }
        assertEquals(listOf("e" to "r1"), accepted.map { it.primaryFactId to it.secondaryFactId })
        assertEquals("A", accepted.single().evidence.extras["phase"])
        assertEquals("3.00", accepted.single().evidence.extras["refund_amount"])
        assertEquals("10.0000", remaining.getValue("e").toPlainString())
        assertEquals(plan, LocalCashRelationPlanning.rules.planRelationProposals(facts.reversed(), remainingByExpense = remaining))
    }

    @Test fun sameParentGraphRejectsAnOtherwiseAcceptedHardKeyRefund() {
        val expense = fact("e", "-10.00", "ORDER").copy(parentId = "wide-parent", componentOrdinal = 0)
        val refund = fact("r", "3.00", "ORDER_1", "refund").copy(parentId = "wide-parent", componentOrdinal = 1)
        val facts = listOf(expense, refund)
        assertEquals("accepted", LocalCashPlatformRefund.hardKeys.match(
            facts.map(LocalCashRelationArithmetic.serialization::factDetailRow), facts.associateBy { it.id }).single().status)
        val result = LocalCashRelationPlanning.rules.planRelationProposals(facts)
        assertTrue(result.proposals.none { it.status == "accepted" })
    }

    @Test fun rowMappingKeepsFallbackPresenceAndScalarBoundaries() {
        val raw = mapOf<String, Any?>("id" to "row", "amount" to JsonPrimitive(1), "account_name" to "fallback",
            "account_id" to "", "source_type" to "", "bill_source" to "wechat", "record_type" to "transfer_out",
            "counterparty_account_attrs" to listOf("tail", null), "component_ordinal" to "+００３",
            "raw_payload" to emptyMap<String, Any?>(), "source_payload" to mapOf("ignored" to "payload"),
            "deleted" to SourceJsonInteger(0), "deleted_at" to JsonPrimitive(false))
        val result = LocalCashRelationPlanning.factFromRow(raw)
        assertEquals("1", result.amount.toPlainString())
        assertEquals("", result.accountId)
        assertEquals("fallback", result.accountName)
        assertEquals("wechat", result.source)
        assertEquals("ordinary_transfer", result.recordSubtype)
        assertEquals(listOf("tail", "null"), result.counterpartyAccountAttrs)
        assertEquals(3, result.componentOrdinal)
        assertTrue(result.rawPayload!!.isEmpty())
        assertFalse(result.deleted)
        assertTrue(LocalCashRelationPlanning.factFromRow(raw + ("deleted" to JsonPrimitive("0"))).deleted)
        assertNull(LocalCashRelationPlanning.factFromRow(raw + ("component_ordinal" to "2147483648")).componentOrdinal)
        assertFailsWith<NoSuchElementException> { LocalCashRelationPlanning.factFromRow(raw - "amount") }
        assertFailsWith<NumberFormatException> { LocalCashRelationPlanning.factFromRow(raw + ("amount" to "program-error")) }
    }

    @Test fun digestEncodingKeepsComputedTypesAndSourceNumericKindsDistinct() {
        val raw = Json.parseToJsonElement("""{"large":9007199254740993,"float":1.0,"text":"1.0","flag":false}""")
        val value = mapOf("payload" to decodeLocalCashValue(raw), "amount" to LocalCashRelationArithmetic.amount("1E-7"),
            "time" to Instant.parse("2026-10-01T10:00:00.123456789Z"), "count" to SourceJsonInteger(1))
        assertEquals(Json.parseToJsonElement("""{"payload":{"large":9007199254740993,"float":1.0,"text":"1.0","flag":false},"amount":"1E-7","time":"2026-10-01T10:00:00.123456789Z","count":1}"""),
            LocalCashRelationPlanning.json(value))
        val original = fact("e", "-10.00").copy(rawPayload = mapOf("value" to JsonPrimitive("1.0")))
        val numeric = original.copy(rawPayload = mapOf("value" to Json.parseToJsonElement("1.0")))
        assertNotEquals(LocalCashRelationPlanning.fingerprints.relationContextDigest(listOf(original), emptyList(), emptyList()),
            LocalCashRelationPlanning.fingerprints.relationContextDigest(listOf(numeric), emptyList(), emptyList()))
    }

    @Test fun dependencyDigestKeepsTheOriginalSourceBinary64FloatBoundary() {
        fun factWith(raw: String) = fact("e", "-10.00").copy(rawPayload = mapOf("value" to Json.parseToJsonElement(raw)))
        fun digest(raw: String) = LocalCashRelationPlanning.fingerprints.relationContextDigest(
            listOf(factWith(raw)), emptyList(), emptyList())
        assertEquals(digest("1.000000000000000001"), digest("1.000000000000000002"))
        assertEquals("{\"value\":1.0}", LocalCashRelationPlanning.json(factWith("1.000000000000000001").rawPayload).toString())
        assertEquals("{\"value\":Infinity}", LocalCashRelationPlanning.json(factWith("1e400").rawPayload).toString())
        assertNotEquals(digest("1"), digest("1.0"))
        assertNotEquals(digest("9007199254740993"), digest("9007199254740992"))
    }

    @Test fun localSourceFalsyScalarsReachTheOriginalEnrichmentAndPhaseAHardKeyFallback() {
        for (zero in listOf(null, false, "", JsonPrimitive(0), JsonPrimitive(false), JsonPrimitive(""), SourceJsonInteger(0))) {
            val row = mapOf("id" to "e", "record_id" to "ORDER", "offset_group" to zero,
                "source_payload" to mapOf("txn_id" to zero), "relation_metadata" to mapOf("offset_group" to "metadata"))
            val result = LocalCashRelationPlanning.rules.enrichPlatformRefundRows(listOf(row)).single()
            assertEquals("ORDER", result["txn_id"], zero.toString())
            assertEquals("metadata", result["offset_group"], zero.toString())
        }
        val expense = fact("e", "-10.00", "ORDER").copy(rawPayload = mapOf("txn_id" to JsonPrimitive(0)))
        val refund = fact("r", "3.00", "ORDER_1", "refund")
        val result = LocalCashRelationPlanning.rules.planRelationProposals(listOf(expense, refund)).proposals.single()
        assertEquals("A", result.evidence.extras["phase"])
        assertEquals("e" to "r", result.primaryFactId to result.secondaryFactId)
    }

    @Test fun dependencyJsonRetainsTheOriginalLegacySurrogateStringBoundary() {
        val high = charArrayOf(0xd800.toChar()).concatToString()
        val low = charArrayOf(0xdfff.toChar()).concatToString()
        assertEquals(0xd800, high.single().code)
        assertEquals(0xdfff, low.single().code)
        val raw = mapOf("payload" to high, "items" to listOf(low, "a${high}b"))
        assertEquals(sourceJson(raw, { null }, ::pythonFloatText), LocalCashRelationPlanning.json(raw))
        assertEquals(sourceJson(mapOf("payload" to high), { null }, ::pythonFloatText),
            LocalCashRelationPlanning.json(mapOf("payload" to JsonPrimitive(high))))
        val legacy = CashRelationPlanFingerprints(LocalCashRelationArithmetic.serialization,
            { value: LedgerArithmeticValue -> value.toPlainString() },
            { value -> sourceJson(value, { (it as? LedgerArithmeticValue)?.toString() }, ::pythonFloatText).toString() })
        val input = fact("e", "-10.00").copy(rawPayload = raw)
        assertEquals(legacy.relationContextDigest(listOf(input), emptyList(), emptyList()),
            LocalCashRelationPlanning.fingerprints.relationContextDigest(listOf(input), emptyList(), emptyList()))
    }
}
