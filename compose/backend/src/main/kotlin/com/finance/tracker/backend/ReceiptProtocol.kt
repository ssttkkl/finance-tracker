package com.finance.tracker.backend

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class ReceiptFailure(val code: String): RuntimeException(code)
class ReceiptUnavailable(val failure: Exception): RuntimeException("receipt_unavailable")
internal data class ReceiptPage(val after: Long,val limit: Int)
private fun badReceiptQuery(): Nothing=throw ReceiptFailure("import_payload_invalid")
private fun decodeReceiptQuery(value: String): String {
    val bytes=ByteArrayOutputStream();var index=0
    while(index<value.length) {
        val char=value[index]
        when {
            char=='%' -> {
                if(index+2>=value.length) badReceiptQuery()
                val high=value[index+1].digitToIntOrNull(16) ?: badReceiptQuery();val low=value[index+2].digitToIntOrNull(16) ?: badReceiptQuery()
                bytes.write(high*16+low);index+=3
            }
            char=='+' -> {bytes.write(32);index++}
            char.code<=127 -> {bytes.write(char.code);index++}
            else ->badReceiptQuery()
        }
    }
    return try {Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()} catch (_: java.nio.charset.CharacterCodingException) {badReceiptQuery()}
}
internal fun validReceiptLookupKey(value: String): Boolean {
    if(value.codePointCount(0,value.length)>255 || value.trim {it.code in pythonWhitespace}.isEmpty()) return false
    return try {strictUtf8(value);true} catch (_: java.nio.charset.CharacterCodingException) {false}
}
internal fun receiptKeyQuery(query: String): String {
    if(query.length>4096 || query.any {it.code>127}) badReceiptQuery()
    val pairs=query.split('&')
    if(pairs.size!=1 || '=' !in pairs.single()) badReceiptQuery()
    val key=decodeReceiptQuery(pairs.single().substringBefore('='));val value=decodeReceiptQuery(pairs.single().substringAfter('='))
    if(key!="key" || !validReceiptLookupKey(value)) badReceiptQuery()
    return value
}
internal fun receiptPageQuery(query: String): ReceiptPage {
    val fields=linkedMapOf<String,String>()
    if(query.isNotEmpty()) for(pair in query.split('&').filter {it.isNotEmpty()}) {
        val key=decodeReceiptQuery(pair.substringBefore('='));val value=decodeReceiptQuery(pair.substringAfter('=',""))
        if(key !in setOf("limit","after") || fields.putIfAbsent(key,value)!=null) badReceiptQuery()
    }
    fun number(key: String,default: String): Long {
        val value=fields[key] ?: default
        if(!value.matches(Regex("0|[1-9][0-9]{0,18}"))) badReceiptQuery()
        return value.toLongOrNull() ?: badReceiptQuery()
    }
    val limit=number("limit","50")
    if(limit !in 1..200) badReceiptQuery()
    return ReceiptPage(number("after","0"),limit.toInt())
}
