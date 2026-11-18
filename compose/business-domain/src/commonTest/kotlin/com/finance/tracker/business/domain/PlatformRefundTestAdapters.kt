package com.finance.tracker.business.domain

/** Only normalized fixture values; backend raw parsing retains its separately tested native contract. */
internal object PlatformRefundTestAdapters {
    fun absolute(value:CashAmount)=CashAmount.parse(value.toString().removePrefix("-"))
    fun sign(value:CashAmount)=requireNotNull(LedgerDecimal.parseOrNull(value.numericText())).sign
    fun compare(a:CashAmount,b:CashAmount):Int {
        val x=requireNotNull(LedgerDecimal.parseOrNull(a.numericText())); val y=requireNotNull(LedgerDecimal.parseOrNull(b.numericText()))
        return if(x.sign!=y.sign) x.sign.compareTo(y.sign) else if(x.sign==0) 0 else compareUnsigned(x.magnitudeAt(18),y.magnitudeAt(18))*x.sign
    }
    val platform=CashPlatformRefundRules<CashAmount,kotlin.time.Instant>(
        {CashAmount.parseOrNull(it.toString())}, ::absolute,
        ::sign,
        ::compare,
        {value ->runCatching {parsePythonCashTimestamp(value.toString().replace(" ","T"),allowNaive=true)}.getOrNull()},
        {from,to ->(to-from).inWholeNanoseconds/1_000_000_000.0})
}
