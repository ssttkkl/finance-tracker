package com.finance.tracker.business.domain

/** Computation-only finite decimal; persisted facts still use CashAmount's column limits. */
internal class LedgerArithmeticValue private constructor(private val representation:DecimalRepresentation):Comparable<LedgerArithmeticValue> {
    private val digits get()=representation.coefficient
    private val scale get()=(-representation.exponent).toInt()
    val signum:Int get()=if(digits=="0") 0 else if(representation.negative) -1 else 1

    fun abs()=if(representation.negative) create(digits,scale,false) else this
    fun negate()=if(signum==0) this else create(digits,scale,!representation.negative)

    override fun compareTo(other:LedgerArithmeticValue):Int {
        val signs=signum.compareTo(other.signum)
        if(signs!=0) return signs
        if(signum==0) return 0
        val magnitude=(digits.length.toLong()-scale).compareTo(other.digits.length.toLong()-other.scale)
        if(magnitude!=0) return magnitude*signum
        for(index in 0 until maxOf(digits.length,other.digits.length)) {
            val order=(digits.getOrNull(index) ?: '0').compareTo(other.digits.getOrNull(index) ?: '0')
            if(order!=0) return order.compareTo(0)*signum
        }
        return 0
    }

    fun add(other:LedgerArithmeticValue,precision:Int?=null):LedgerArithmeticValue {
        val targetScale=maxOf(scale,other.scale)
        fun aligned(value:LedgerArithmeticValue):String {
            if(value.signum==0) return "0"
            val padding=targetScale.toLong()-value.scale
            if(padding>Int.MAX_VALUE) throw ArithmeticException("decimal scale overflow")
            return value.digits+"0".repeat(padding.toInt())
        }
        val a=aligned(this);val b=aligned(other)
        val order=compareUnsigned(a,b)
        val sameSign=representation.negative==other.representation.negative
        val magnitude=if(sameSign) addUnsigned(a,b) else if(order>=0) subtractUnsigned(a,b) else subtractUnsigned(b,a)
        val negative=if(sameSign || order>=0) representation.negative else other.representation.negative
        val result=create(magnitude,targetScale,negative)
        return if(precision==null) result else result.round(precision)
    }
    fun subtract(other:LedgerArithmeticValue,precision:Int?=null)=add(other.negate(),precision)

    fun round(precision:Int):LedgerArithmeticValue {
        require(precision>0)
        val dropped=digits.length-precision
        if(dropped<=0) return this
        var kept=digits.take(precision)
        val tail=digits.substring(precision)
        if(tail[0]>'5' || tail[0]=='5' && (tail.drop(1).any {it!='0'} || kept.last().digitToInt()%2!=0))
            kept=addUnsigned(kept,"1")
        val carry=kept.length>precision
        if(carry) kept=kept.dropLast(1)
        val roundedScale=checkedScale(scale.toLong()-dropped-(if(carry) 1 else 0))
        return create(kept,roundedScale,representation.negative)
    }

    fun toPlainString():String {
        val sign=if(representation.negative) "-" else ""
        if(scale<=0) return if(signum==0) "0" else sign+digits+"0".repeat(checkedPadding(-scale.toLong()))
        val point=digits.length-scale
        return sign+if(point>0) digits.take(point)+"."+digits.drop(point)
            else "0."+"0".repeat(checkedPadding(-point.toLong()))+digits
    }
    override fun toString():String {
        val adjusted=digits.length.toLong()-1-scale
        if(scale>=0 && adjusted>=-6) return toPlainString()
        val sign=if(representation.negative) "-" else ""
        val mantissa=digits.first()+if(digits.length>1) "."+digits.drop(1) else ""
        return sign+mantissa+"E"+(if(adjusted>=0) "+" else "")+adjusted
    }
    private fun signature():String {
        if(signum==0) return "0"
        val normalized=digits.trimEnd('0')
        return "$signum:$normalized:${representation.exponent+digits.length-normalized.length}"
    }
    override fun equals(other:Any?)=other is LedgerArithmeticValue && compareTo(other)==0
    override fun hashCode()=signature().hashCode()

    companion object {
        val ZERO=LedgerArithmeticValue(DecimalRepresentation(false,"0",0))
        private val canonical=Regex("^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?$")
        fun fromCanonical(text:String):LedgerArithmeticValue {
            require(canonical.matches(text)) {"invalid_canonical_decimal"}
            val parts=requireNotNull(LedgerDecimal.parseRepresentationOrNull(text)) {"invalid_canonical_decimal"}
            return fromParts(parts)
        }
        fun fromCashAmount(amount:CashAmount):LedgerArithmeticValue {
            val parts=amount.representation
            // Preserve the native arithmeticValue boundary for an unrepresentably large zero exponent.
            if(parts.coefficient=="0" && parts.exponent>Int.MAX_VALUE) return ZERO
            return fromParts(parts)
        }
        private fun fromParts(parts:DecimalRepresentation):LedgerArithmeticValue=
            create(parts.coefficient,checkedScale(-parts.exponent),parts.negative)
        private fun create(digits:String,scale:Int,negative:Boolean):LedgerArithmeticValue {
            val coefficient=digits.trimStart('0').ifEmpty {"0"}
            return LedgerArithmeticValue(DecimalRepresentation(negative && coefficient!="0",coefficient,-scale.toLong()))
        }
        private fun checkedScale(value:Long):Int {
            if(value<Int.MIN_VALUE || value>Int.MAX_VALUE) throw ArithmeticException("decimal scale overflow")
            return value.toInt()
        }
        private fun checkedPadding(value:Long):Int {
            if(value>Int.MAX_VALUE) throw ArithmeticException("decimal scale overflow")
            return value.toInt()
        }
    }
}
