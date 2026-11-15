package com.finance.tracker.business.domain

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import kotlin.test.*
import kotlinx.serialization.json.JsonArray

class LocalCashRelationPipelineParityTest {
    // Independent native adapters mirror the unchanged backend, including its original expression and clocks.
    private val core = CashRelationCoreRules<BigDecimal>({ it.signum() }, ::stableCashRelationTimestamp)
    // Generated ISO strings use the backend's Python string boundary; native Instant objects bypass it in parseDt.
    private fun instant(value: Any?): Instant = if (value is Instant) value
        else Instant.parse(value.toString()).truncatedTo(ChronoUnit.MICROS)
    private val geometry = CashRelationGeometryRules(core,
        { instant(it).atOffset(ZoneOffset.UTC).toLocalDate().toString() },
        { a, b -> kotlin.math.abs(ChronoUnit.SECONDS.between(instant(a), instant(b))) })
    private fun index(facts: List<RelationFact<BigDecimal>>, sourceGroup: Boolean = true) =
        CashFactCandidateIndex(facts, core, { it.abs().stripTrailingZeros().toPlainString() }, { value ->
            val time = instant(value)
            CashCandidateTime(time.epochSecond + time.nano / 1000000000.0) {
                time.atOffset(ZoneOffset.UTC).toLocalDate().toString()
            }
        }, { day, offset -> LocalDate.parse(day).plusDays(offset.toLong()).toString() },
            if (sourceGroup) core::sourceGroup else { _ -> "other" })
    private val mirror = CashMirrorMatchRules(core, geometry, { it.stripTrailingZeros().toPlainString() },
        { a, b -> a.compareTo(b) == 0 }, { a, b -> a.subtract(b).abs().toPlainString() },
        { instant(it).epochSecond }, geometry::mainStyleCrossVerify)
    private val transfer = CashTransferMatchRules(core, { a, b -> a.abs().compareTo(b.abs()) == 0 },
        { a, b -> a.abs().subtract(b.abs()).toPlainString() }, geometry::timeDeltaSeconds, { index(it, false) })
    private val diamond = CashRefundDiamondRules(core, { a, b -> a.abs() + BigDecimal("0.0001") >= b.abs() },
        { it.abs().toPlainString() }, geometry::timeDeltaSeconds)
    private val refund = CashRefundMatchRules(core,
        { value -> Regex("(?U)[\\W_]+").replace(value.orEmpty(), "").lowercase() }, ::instant,
        { a, b -> Duration.between(a, b).toNanos() }, { it.abs() }, { a, b -> a.subtract(b) },
        { it.toPlainString() }, { BigDecimal(it.toString()) })
    private val rules = CashRelationPipelineRules(core, geometry, mirror, transfer, diamond, refund, { index(it) },
        { it.abs() }, { a, b -> a.add(b) }, { a, b -> a.subtract(b) }, { BigDecimal(it.toString()) }, BigDecimal.ZERO)
    private fun proposals(values: List<RelationProposal>) = JsonArray(values.map { value ->
        value.toJsonValue { sourceJson(it, { null }, ::pythonFloatText) }
    })

    private data class Row(val id: String, val amount: String, val account: String, val type: String,
        val subtype: String, val source: String, val merchant: String, val time: String, val deleted: Boolean = false) {
        fun <A> fact(parse: (String) -> A) = RelationFact(id, parse(amount), "CNY", account,
            accountName = "账户", occurredAt = time, counterparty = merchant, recordType = type, recordSubtype = subtype,
            billSource = source, source = source, recordId = "record-$id", deleted = deleted,
            stableSourceKey = listOf(source, "identity-$id"), recordOrigin = "import")
    }

    @Test fun fullOutputAndContextMatchOriginalNativeAdapters() {
        val random = Random(426193)
        val merchants = listOf("商户", "退款", "AΣ𐀀A", "aς𐀀a", "İAΣ𐐀A", "i\u0307aσ𐐨a", "ΟΣ_!", "ος", "消费")
        val amounts = listOf("10.00", "10.0000", "1E+1", "3.000", "0.000000000000000001",
            "99999999999999999999.999999999999999999")
        repeat(240) { sample ->
            val rows = (0 until 12).map { i ->
                val type = listOf("consumption", "refund", "transfer_out", "transfer_in", "fx_out", "fx_in")[i % 6]
                val positive = type in setOf("refund", "transfer_in", "fx_in")
                val value = amounts[random.nextInt(amounts.size)]
                val seconds = random.nextInt(0, 300000)
                Row("f$i", (if (positive) "" else "-") + value, if (i % 6 < 2) "a" else "a${i % 2}", type,
                    if (i % 6 >= 4) "currency_exchange" else if (i % 6 >= 2) "ordinary_transfer" else "not_applicable",
                    listOf("alipay", "wechat", "icbc_debit", "ccb_debit")[random.nextInt(4)],
                    merchants[random.nextInt(merchants.size)],
                    Instant.parse("2026-10-01T00:00:00Z").plusSeconds(seconds.toLong()).plusNanos(random.nextInt(10).toLong()).toString(),
                    deleted = sample % 9 == 0 && i == 11)
            }
            compare(rows, sample % 2 == 0)
        }
    }

    @Test fun unicodeEvidenceAndMirrorEventChainsMatchNativeWithSeedAndOccupancy() {
        val rows = listOf(
            Row("pe", "-10.0000", "a", "consumption", "not_applicable", "alipay", "AΣ𐀀A", "2026-10-01T00:00:00Z"),
            Row("be", "-1E+1", "a", "consumption", "not_applicable", "icbc_debit", "AΣ𐀀A", "2026-10-01T00:00:05Z"),
            Row("pr", "3.00", "a", "refund", "not_applicable", "wechat", "aς𐀀a", "2026-10-02T00:00:00Z"),
            Row("br", "3.000", "a", "refund", "not_applicable", "icbc_debit", "aς𐀀a", "2026-10-02T00:00:05Z"))
        compare(rows, false)
        compare(rows, true)
        compare(rows, false, occupied = true)
        compare(rows, true, occupied = true)
    }

    private fun compare(rows: List<Row>, strict: Boolean, occupied: Boolean = false) {
        val native = rows.map { it.fact(::BigDecimal) }
        val local = rows.map { it.fact(LocalCashRelationArithmetic::amount) }
        val nativeContext = MatchContext<BigDecimal>("w")
        val localContext = MatchContext<LedgerArithmeticValue>("w")
        if (occupied) {
            val mirror = RelationEdge("pe", "be", "payment_mirror")
            val refund = RelationEdge("pe", "pr", "refund_offset")
            nativeContext.acceptedMirrors += mirror; localContext.acceptedMirrors += mirror
            nativeContext.acceptedPlatformRefunds += refund; localContext.acceptedPlatformRefunds += refund
            nativeContext.usedFactIds += "pr"; localContext.usedFactIds += "pr"
            nativeContext.remainingByExpense = mapOf("pe" to BigDecimal("7.0000"), "be" to BigDecimal("10.00"))
            localContext.remainingByExpense = mapOf("pe" to LocalCashRelationArithmetic.amount("7.0000"),
                "be" to LocalCashRelationArithmetic.amount("10.00"))
        }
        val seeds = if (strict) rows.filterIndexed { i, _ -> i % 2 == 1 }.map { it.id } else null
        val expected = rules.run(native, nativeContext, seeds, index(native), requireUniqueMirrors = strict)
        val actual = LocalCashRelationPipeline.rules.run(local, localContext, seeds, LocalCashRelationPipeline.index(local),
            requireUniqueMirrors = strict)
        assertEquals(proposals(expected), proposals(actual), rows.toString())
        assertEquals(nativeContext.usedFactIds, localContext.usedFactIds)
        assertEquals(nativeContext.acceptedMirrors, localContext.acceptedMirrors)
        assertEquals(nativeContext.acceptedPlatformRefunds, localContext.acceptedPlatformRefunds)
        assertEquals(nativeContext.acceptedTransfers, localContext.acceptedTransfers)
        assertEquals(nativeContext.remainingByExpense?.mapValues { it.value.toPlainString() },
            localContext.remainingByExpense?.mapValues { it.value.toPlainString() })
    }
}
