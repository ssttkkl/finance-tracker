package com.finance.tracker.business.domain

class CashAmount private constructor(private val decimal: LedgerDecimal): Comparable<CashAmount> {
    val representation: DecimalRepresentation get()=decimal.representation
    private fun signature(): String {
        if(decimal.sign==0) return "0"
        val coefficient=representation.coefficient.trimEnd('0')
        val exponent=representation.exponent+(representation.coefficient.length-coefficient.length)
        return "${decimal.sign}:$coefficient:$exponent"
    }
    fun numericText(): String {
        if(decimal.sign==0) return "0"
        val coefficient=representation.coefficient.trimEnd('0')
        return (if(decimal.sign<0) "-" else "")+coefficient+"E"+(representation.exponent+representation.coefficient.length-coefficient.length)
    }
    override fun compareTo(other: CashAmount): Int {
        val signOrder = decimal.sign.compareTo(other.decimal.sign)
        if (signOrder != 0) return signOrder
        if (decimal.sign == 0) return 0
        val scale = maxOf(decimal.scale, other.decimal.scale)
        return compareUnsigned(decimal.magnitudeAt(scale), other.decimal.magnitudeAt(scale)).compareTo(0) * decimal.sign
    }
    override fun equals(other: Any?)=other is CashAmount && signature()==other.signature()
    override fun hashCode()=signature().hashCode()
    override fun toString()=(if(representation.negative) "-" else "")+representation.coefficient+"E"+representation.exponent
    companion object {
        fun parse(text: String)=parseOrNull(text) ?: throw IllegalArgumentException("invalid_cash_amount")
        fun parseOrNull(text: String,onInvalid:(LedgerDecimal.InputError)->Unit = {})=LedgerDecimal.parseOrNull(text,onInvalid)?.let(::CashAmount)
    }
}
fun exactCashAmount(text:String,field:String="value"):CashAmount=CashAmount.parseOrNull(text) {reason ->
    throw IllegalArgumentException(when(reason) {
        LedgerDecimal.InputError.DECIMAL_COMPATIBLE ->"$field must be decimal-compatible"
        LedgerDecimal.InputError.FINITE ->"$field must be finite"
        LedgerDecimal.InputError.SCALE ->"$field exceeds NUMERIC(38,18): must have at most 18 decimal places"
        LedgerDecimal.InputError.RANGE ->"$field exceeds NUMERIC(38,18)"
    })
} ?: error("decimal parser did not report rejection")
