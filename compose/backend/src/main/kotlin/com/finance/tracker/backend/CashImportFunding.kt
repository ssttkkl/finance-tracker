package com.finance.tracker.backend

import java.math.BigDecimal
import com.finance.tracker.business.domain.normalizeCashNfc
import java.math.MathContext
import java.math.RoundingMode
import kotlinx.serialization.json.*

internal typealias CashFundingInputError = com.finance.tracker.business.domain.CashFundingInputError
internal typealias CashFundingPartsTooLarge = com.finance.tracker.business.domain.CashFundingPartsTooLarge
internal typealias CashFundingPart = com.finance.tracker.business.domain.CashFundingPart
internal typealias CashFundingRow = com.finance.tracker.business.domain.CashFundingRow
internal typealias CashFundingScan = com.finance.tracker.business.domain.CashFundingScan
internal typealias CashFundingResolution = com.finance.tracker.business.domain.CashFundingResolution
internal typealias CashFundingGroup = com.finance.tracker.business.domain.CashFundingGroup

internal fun CashFundingRow.json(): JsonObject = json(::sourceJson)
internal fun CashFundingScan.json(): JsonObject = json(::sourceJson)
internal fun CashFundingResolution.json(): JsonObject = json(::sourceJson)

internal object CashImportFunding : CashImportFundingEngine()

internal open class CashImportFundingEngine(private val validatePosting: (Map<*, *>) -> Unit = CashRecordPolicy::validateForPosting) :
    com.finance.tracker.business.domain.CashFundingScanRules(
        { exactCashAmount(it).arithmeticValue().toPlainString() },
        ::normalizeCashNfc,
        CashSourceAccountMapping::parseAlipayPaymentComponents,
        CashSourceAccountMapping::normalizeAlipayComponent,
        CashSourceAccountMapping::normalizeWechatPaymentIdentity,
        { values -> cashSourceDigest(*values.map(::JsonPrimitive).toTypedArray()) },
        { fields -> CashRecordPolicy.copyValue(fields) as Map<String, Any?> },
    ) {
    private val math = MathContext(80, RoundingMode.HALF_EVEN)
    private fun amount(value: String) = exactCashAmount(value).arithmeticValue()
    protected open fun total(values: List<BigDecimal>) = values.fold(BigDecimal.ZERO) { sum, value -> sum.add(value, math) }
    private val resolution by lazy {
        com.finance.tracker.business.domain.CashFundingResolutionRules(
            object : com.finance.tracker.business.domain.CashFundingAmountValues<BigDecimal> {
                override fun parse(text: String) = amount(text)
                override fun absolute(value: BigDecimal) = value.abs()
                override fun negate(value: BigDecimal) = value.negate()
                override fun total(values: List<BigDecimal>) = this@CashImportFundingEngine.total(values)
                override fun compare(left: BigDecimal, right: BigDecimal) = left.compareTo(right)
                override fun sign(value: BigDecimal) = value.signum()
                override fun plain(value: BigDecimal) = value.toPlainString()
            },
            { val cache = PostingValidationCache(validatePosting); { record -> cache(record) } },
            { fields -> CashRecordPolicy.copyValue(fields) as Map<String, Any?> },
            { revision -> when (revision) {
                is Byte, is Short, is Int, is Long, is java.math.BigInteger -> revision.toString().toBigInteger().signum() > 0
                else -> false
            } },
        )
    }
    fun resolve(scan: CashFundingScan, selections: Any?, accounts: Any?): List<CashFundingResolution> =
        resolution.resolve(scan, selections, accounts)
}
