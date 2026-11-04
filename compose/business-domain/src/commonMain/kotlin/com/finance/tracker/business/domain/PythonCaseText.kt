package com.finance.tracker.business.domain

/** Frozen Python Unicode 14 mappings without exposing mutable backing collections. */
fun pythonCaseFoldMapping(code:Int):String?=pythonCaseFold[code]
fun pythonUpperCaseMapping(code:Int):String?=pythonUpperCase[code]

private fun StringBuilder.appendCasePoint(point:Int) {
    if(point<=0xffff) append(point.toChar())
    else {append(((point-0x10000 ushr 10)+0xd800).toChar());append(((point-0x10000 and 0x3ff)+0xdc00).toChar())}
}
fun pythonCashCaseFold(value:String):String=buildString {
    for(point in value.pythonCodePoints()) {
        val mapping=pythonCaseFold[point]
        if(mapping!=null) append(mapping) else appendCasePoint(point)
    }
}
fun pythonCashUpper(value:String):String=buildString {
    for(point in value.pythonCodePoints()) {
        val mapping=pythonUpperCase[point]
        if(mapping!=null) append(mapping) else appendCasePoint(point)
    }
}
fun pythonCashLower(value:String):String {
    fun member(code:Int,ranges:IntArray):Boolean {
        var low=0;var high=ranges.size/2-1
        while(low<=high) {val middle=(low+high)/2;when {code<ranges[middle*2] ->high=middle-1;code>ranges[middle*2+1] ->low=middle+1;else ->return true}}
        return false
    }
    val points=value.pythonCodePoints()
    return buildString {for((index,point) in points.withIndex()) {
        if(point==0x03a3) {
            var before=index-1;while(before>=0 && member(points[before],pythonCaseIgnorableRanges)) before--
            var after=index+1;while(after<points.size && member(points[after],pythonCaseIgnorableRanges)) after++
            append(if(before>=0 && member(points[before],pythonCasedRanges) && (after==points.size || !member(points[after],pythonCasedRanges))) 'ς' else 'σ')
        } else {
            val mapping=pythonLowerCase[point]
            if(mapping!=null) append(mapping) else appendCasePoint(point)
        }
    }}
}
