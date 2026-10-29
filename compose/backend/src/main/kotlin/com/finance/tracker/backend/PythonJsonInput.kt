package com.finance.tracker.backend

import java.math.BigInteger
import kotlinx.serialization.json.*

internal fun copyCashJsonContainers(value:JsonElement):JsonElement {
    val copied=java.util.IdentityHashMap<JsonElement,JsonElement>()
    fun copy(item:JsonElement):JsonElement {
        copied[item]?.let {return it}
        return when(item) {
            is JsonObject -> {
                val fields=linkedMapOf<String,JsonElement>()
                JsonObject(fields).also {result ->
                    copied[item]=result
                    for((key,child) in item) fields[key]=copy(child)
                }
            }
            is JsonArray -> {
                val children=mutableListOf<JsonElement>()
                JsonArray(children).also {result ->
                    copied[item]=result
                    for(child in item) children.add(copy(child))
                }
            }
            else ->item
        }
    }
    return copy(value)
}

/** Python JSON input compatibility for the legacy category-delete error contract. */
internal data class DecodedPythonJson(val value: JsonElement,private val stringRepresentations: java.util.IdentityHashMap<JsonElement,String>,
    private val rawStringSurrogates:java.util.IdentityHashMap<JsonElement,Set<Int>> = java.util.IdentityHashMap()) {
    fun stringRepresentation(value:JsonElement?):String?=value?.let {stringRepresentations[it]}
    fun stringRawSurrogates(value:JsonElement):Set<Int> = rawStringSurrogates[value].orEmpty()
    fun integerRepresentations(body:JsonObject):Map<String,String> = listOf("expected_revision","expected_category_revision","expected_usage_count").mapNotNull {field ->
        body[field]?.let {value ->stringRepresentations[value]?.let {field to it}}
    }.toMap()
}
internal fun categoryDeleteBody(text: String): JsonElement = PythonJsonInput(text).decode().value
internal fun categoryDeleteRequest(source:PythonJsonText):DecodedPythonJson=PythonJsonInput(source.text,source.rawSurrogates).decode()
internal fun strictCashJsonRequest(source:PythonJsonText):DecodedPythonJson=PythonJsonInput(source.text,source.rawSurrogates,
    rejectDuplicateKeys=true,rejectNonFiniteNumbers=true,maxDepth=128).decode()

private class PythonJsonInput(private val text:String,private val rawSurrogates:Set<Int> = emptySet(),
    private val rejectDuplicateKeys:Boolean=false,private val rejectNonFiniteNumbers:Boolean=false,private val maxDepth:Int?=null) {
    private val representations=java.util.IdentityHashMap<JsonElement,String>()
    private val rawSurrogatePositions=java.util.IdentityHashMap<JsonElement,Set<Int>>()
    private var lastStringSurrogates:Set<Int> = emptySet()
    private fun points(start:Int,end:Int):Int=text.codePointCount(start,end)+(start until end-1).count {
        text[it].isHighSurrogate() && text[it+1].isLowSurrogate() && (it in rawSurrogates || it+1 in rawSurrogates)
    }
    private var cursor=0
    private fun failure(message:String,at:Int=cursor):Nothing {
        val prefix=text.substring(0,at)
        val line=prefix.count {it=='\n'}+1
        val start=prefix.lastIndexOf('\n')+1
        val position=points(0,at)
        val column=points(start,at)+1
        throw CategoryFailure("$message: line $line column $column (char $position)")
    }
    private fun whitespace() {while(cursor<text.length && text[cursor] in " \t\r\n") cursor++}
    fun decode():DecodedPythonJson {
        whitespace();val result=value(0);whitespace()
        if(cursor!=text.length) failure("Extra data")
        return DecodedPythonJson(result,representations,rawSurrogatePositions)
    }
    private fun value(depth:Int):JsonElement {
        if(maxDepth!=null && depth>maxDepth) failure("Maximum JSON depth exceeded")
        if(cursor==text.length) failure("Expecting value")
        return when(text[cursor]) {
            '"' -> {val text=string();JsonPrimitive(text).also {
                representations[it]=pythonStringRepr(text,lastStringSurrogates)
                if(lastStringSurrogates.isNotEmpty()) rawSurrogatePositions[it]=lastStringSurrogates.toSet()
            }}
            '{' ->obj(depth)
            '[' ->array(depth)
            else -> {
                for((literal,result) in listOf("null" to JsonNull,"true" to JsonPrimitive(true),"false" to JsonPrimitive(false),
                    "NaN" to JsonPrimitive(Double.NaN),"Infinity" to JsonPrimitive(Double.POSITIVE_INFINITY),"-Infinity" to JsonPrimitive(Double.NEGATIVE_INFINITY))) {
                    if(text.startsWith(literal,cursor)) {
                        if(rejectNonFiniteNumbers && literal in setOf("NaN","Infinity","-Infinity")) failure("Non-finite number")
                        cursor+=literal.length;return result
                    }
                }
                val number=Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").find(text,cursor)?.takeIf {it.range.first==cursor}?.value
                    ?: failure("Expecting value")
                cursor+=number.length
                if(number.any {it in ".eE"}) {
                    val parsed=number.toDouble()
                    if(rejectNonFiniteNumbers && !parsed.isFinite()) failure("Non-finite number")
                    JsonPrimitive(parsed)
                } else {
                    val digits=number.length-if(number.startsWith('-')) 1 else 0
                    if(digits>4300) throw CategoryFailure("Exceeds the limit (4300 digits) for integer string conversion: value has $digits digits; use sys.set_int_max_str_digits() to increase the limit")
                    JsonPrimitive(BigInteger(number))
                }
            }
        }
    }
    private fun obj(depth:Int):JsonObject {
        cursor++;whitespace();val result=linkedMapOf<String,JsonElement>()
        if(cursor<text.length && text[cursor]=='}') {cursor++;return JsonObject(result)}
        while(true) {
            if(cursor==text.length || text[cursor]!='"') failure("Expecting property name enclosed in double quotes")
            val key=string();whitespace()
            if(rejectDuplicateKeys && key in result) failure("Duplicate object key")
            if(cursor==text.length || text[cursor]!=':') failure("Expecting ':' delimiter")
            cursor++;whitespace();result[key]=value(depth+1);whitespace()
            if(cursor<text.length && text[cursor]=='}') {cursor++;return JsonObject(result)}
            if(cursor==text.length || text[cursor]!=',') failure("Expecting ',' delimiter")
            cursor++;whitespace()
        }
    }
    private fun array(depth:Int):JsonArray {
        cursor++;whitespace();val result=mutableListOf<JsonElement>()
        if(cursor<text.length && text[cursor]==']') {cursor++;return JsonArray(result)}
        while(true) {
            result+=value(depth+1);whitespace()
            if(cursor<text.length && text[cursor]==']') {cursor++;return JsonArray(result)}
            if(cursor==text.length || text[cursor]!=',') failure("Expecting ',' delimiter")
            cursor++;whitespace()
        }
    }
    private fun string():String {
        val start=cursor++
        val separate=mutableSetOf<Int>()
        return buildString {
            while(cursor<text.length) {
                val character=text[cursor++]
                when {
                    character=='"' -> {lastStringSurrogates=separate;return@buildString}
                    character.code<32 ->failure("Invalid control character at",cursor-1)
                    character=='\\' -> {
                        if(cursor==text.length) failure("Unterminated string starting at",start)
                        when(val escape=text[cursor++]) {
                            '"','\\','/' ->append(escape)
                            'b' ->append('\b')
                            'f' ->append('\u000c')
                            'n' ->append('\n')
                            'r' ->append('\r')
                            't' ->append('\t')
                            'u' -> {
                                val at=cursor-1
                                val hex=text.substring(cursor,minOf(cursor+4,text.length))
                                if(hex.length!=4 || hex.any {it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F'}) failure("Invalid \\uXXXX escape",at)
                                append(hex.toInt(16).toChar());cursor+=4
                            }
                            else ->failure("Invalid \\escape",cursor-2)
                        }
                    }
                    else -> {if(cursor-1 in rawSurrogates) separate+=length;append(character)}
                }
            }
            failure("Unterminated string starting at",start)
        }
    }
}
