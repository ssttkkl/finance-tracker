package com.finance.tracker.backend

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

internal fun manualCashTimestamp(value:String,rawSurrogates:Set<Int> = emptySet()):Instant {
    val text=value.trim {it.code in pythonWhitespace}
    require(text.isNotEmpty()) {"date is required"}
    val start=value.indexOfFirst {it.code !in pythonWhitespace}
    return try {pythonCashTimestamp(text,allowNaive=true,separateSurrogates=rawSurrogates.map {it-start}.toSet())}
        catch(_:InvalidCashValue) {throw IllegalArgumentException("invalid transaction date: $text")}
}

internal fun pythonCashTimestamp(value: Any,allowNaive:Boolean=false,separateSurrogates:Set<Int> = emptySet()): Instant {
    fun invalid(): Nothing=throw InvalidCashValue()
    fun checked(instant: Instant): Instant {
        if(instant.atOffset(ZoneOffset.UTC).year !in 1..9999) {
            if(allowNaive) throw ArithmeticException("date value out of range")
            invalid()
        }
        return instant.truncatedTo(ChronoUnit.MICROS)
    }
    try {
        when(value) {
            is Instant ->return checked(value)
            is OffsetDateTime ->return checked(value.toInstant())
            is ZonedDateTime ->return checked(value.toInstant())
        }
        if(value !is String) invalid()
        val input=if(allowNaive) value else value.replace("Z","+00:00")
        if(input.length<(if(allowNaive) 7 else 8)) invalid()
        val separator=when {
            input[4]=='-' && input[5]=='W' -> {
                if(input.length>8 && input[8]=='-') {
                    if(input.length==9) invalid()
                    if(input.length>10 && input[10] in '0'..'9') 8 else 10
                } else 8
            }
            input[4]=='-' ->10
            input[4]=='W' -> {
                var end=7
                while(end<input.length && input[end] in '0'..'9') end++
                if(end<9) end else if(end%2==0) 7 else 8
            }
            else ->8
        }
        if(separator>input.length || separator==input.length && !allowNaive) invalid()
        val dateText=input.take(separator)
        val calendar=dateText.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
        val week=dateText.matches(Regex("[0-9]{4}(?:-W[0-9]{2}(?:-[0-9])?|W[0-9]{2}[0-9]?)"))
        val basic=dateText.matches(Regex("[0-9]{8}"))
        if(!calendar && !week && !basic) invalid()
        val date=when {
            'W' in dateText ->{
                val compact=dateText.replace("-","")
                val normalized=compact.take(4)+"-W"+compact.substring(5,7)+"-"+if(compact.length==8) compact.last() else '1'
                LocalDate.parse(normalized,DateTimeFormatter.ISO_WEEK_DATE)
            }
            calendar ->LocalDate.parse(dateText)
            else ->LocalDate.parse(dateText,DateTimeFormatter.BASIC_ISO_DATE)
        }
        if(date.year !in 1..9999) invalid()
        if(separator==input.length) return checked(date.atStartOfDay().toInstant(ZoneOffset.UTC))
        val width=Character.charCount(input.codePointAt(separator))
        if(width==2 && (separator in separateSurrogates || separator+1 in separateSurrogates)) invalid()
        val rest=input.substring(separator+width)
        val offsetIndex=rest.indexOfFirst {it=='+' || it=='-' || it=='Z'}
        if(offsetIndex<0 && !allowNaive) invalid()
        fun clock(text: String): Pair<Long,Int> {
            val fractionIndex=text.indexOfFirst {it=='.' || it==','}
            val main=if(fractionIndex<0) text else text.take(fractionIndex)
            val fraction=if(fractionIndex<0) "" else text.substring(fractionIndex+1).also {if(it.isEmpty() || it.any {c ->c !in '0'..'9'}) invalid()}
            val parts=if(':' in main) main.split(':') else {
                if(main.length !in setOf(2,4,6)) invalid();main.chunked(2)
            }
            if(parts.size !in 1..3 || parts.any {it.length!=2 || it.any {c ->c !in '0'..'9'}}) invalid()
            val hour=parts[0].toInt();val minute=parts.getOrNull(1)?.toInt() ?: 0;val second=parts.getOrNull(2)?.toInt() ?: 0
            return (hour*3600L+minute*60L+second) to fraction.take(6).padEnd(6,'0').toInt()
        }
        val clockText=if(offsetIndex<0) rest else rest.take(offsetIndex)
        val (seconds,micros)=clock(clockText)
        val main=clockText.substringBefore('.').substringBefore(',')
        val parts=if(':' in main) main.split(':') else main.chunked(2)
        if(parts[0].toInt()>23 || (parts.getOrNull(1)?.toInt() ?: 0)>59 || (parts.getOrNull(2)?.toInt() ?: 0)>59) invalid()
        val offset=if(offsetIndex<0) "Z" else rest.substring(offsetIndex)
        val (offsetSeconds,offsetMicros)=if(allowNaive && offset=="Z") 0L to 0 else clock(offset.substring(1))
        if(offsetSeconds>=86400L) invalid()
        val direction=if(offset.startsWith('-')) -1 else 1
        val instant=date.atStartOfDay().plusSeconds(seconds).plusNanos(micros*1000L).toInstant(ZoneOffset.UTC)
            .minusSeconds(direction*offsetSeconds).minusNanos(if(offsetSeconds==0L) 0L else direction*offsetMicros*1000L)
        return checked(instant)
    } catch (_: DateTimeException) {invalid()}
}
