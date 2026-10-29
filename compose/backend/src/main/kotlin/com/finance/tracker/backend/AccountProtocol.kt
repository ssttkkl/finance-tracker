package com.finance.tracker.backend

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

internal class AccountJson(private val source: String) {
    private var index=0
    private fun invalid(): Nothing = throw AccountFailure("account_payload_invalid")
    private fun space() { while(index<source.length && source[index] in " \r\n\t") index++ }
    private fun character(expected: Char) { space();if(index>=source.length || source[index++]!=expected) invalid() }
    private fun string(): JsonPrimitive {
        space();val start=index
        character('"')
        while(index<source.length) {
            val character=source[index++]
            if(character.code<0x20) invalid()
            when(character) {
                '\\' -> { if(index>=source.length) invalid();index++ }
                '"' -> return try { Json.parseToJsonElement(source.substring(start,index)) as JsonPrimitive }
                    catch (_: SerializationException) { invalid() }
            }
        }
        invalid()
    }
    private fun value(depth: Int): JsonElement {
        if(depth>1000) invalid()
        space();if(index>=source.length) invalid()
        return when(source[index]) {
            '{' -> {
                index++;space();val result=linkedMapOf<String,JsonElement>()
                if(index<source.length && source[index]=='}') {index++;JsonObject(result)} else {
                    while(true) {
                        val key=string().content
                        if(key in result) invalid()
                        character(':');result[key]=value(depth+1);space()
                        if(index<source.length && source[index]=='}') {index++;break}
                        character(',')
                    }
                    JsonObject(result)
                }
            }
            '[' -> {
                index++;space();val result=mutableListOf<JsonElement>()
                if(index<source.length && source[index]==']') {index++;JsonArray(result)} else {
                    while(true) {
                        result.add(value(depth+1));space()
                        if(index<source.length && source[index]==']') {index++;break}
                        character(',')
                    }
                    JsonArray(result)
                }
            }
            '"' -> string()
            else -> {
                val start=index
                while(index<source.length && source[index] !in " \r\n\t,]}") index++
                val token=source.substring(start,index)
                if(token !in setOf("null","true","false") && !token.matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))) invalid()
                try { Json.parseToJsonElement(token) } catch (_: SerializationException) { invalid() }
            }
        }
    }
    fun parse(): JsonObject {
        val result=value(0);space();if(index!=source.length || result !is JsonObject) invalid()
        return result
    }
}

internal suspend fun ApplicationCall.accountBody(): JsonObject {
    val bytes=receiveChannel().readRemaining(65537).readByteArray()
    if(bytes.size>65536) throw AccountFailure("account_payload_invalid")
    val charset=when {
        bytes.size>=4 && bytes[0]==0.toByte() && bytes[1]==0.toByte() -> "UTF-32BE"
        bytes.size>=4 && bytes[2]==0.toByte() && bytes[3]==0.toByte() -> "UTF-32LE"
        bytes.size>=2 && bytes[0]==0.toByte() -> "UTF-16BE"
        bytes.size>=2 && bytes[1]==0.toByte() -> "UTF-16LE"
        bytes.size>=2 && ((bytes[0]==0xFE.toByte() && bytes[1]==0xFF.toByte()) || (bytes[0]==0xFF.toByte() && bytes[1]==0xFE.toByte())) -> "UTF-16"
        else -> "UTF-8"
    }
    val source=try { java.nio.charset.Charset.forName(charset).newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }
        catch (_: java.nio.charset.CharacterCodingException) { throw AccountFailure("account_payload_invalid") }
    return AccountJson(source).parse()
}
internal fun ApplicationCall.accountKey(): String = request.headers["Idempotency-Key"]?.takeIf {
    it.matches(Regex("[A-Za-z0-9_:.~-]{1,255}"))
} ?: throw AccountFailure("idempotency_key_required")
internal fun accountRevision(value: JsonElement?): Long = (value as? JsonPrimitive)?.takeIf {
    !it.isString && it.content.matches(Regex("-?(?:0|[1-9][0-9]*)"))
}?.content?.toLongOrNull()?.takeIf { it in 1 until Long.MAX_VALUE } ?: throw AccountFailure("account_payload_invalid")

internal val accountProtocolJson=Json {encodeDefaults=true;explicitNulls=false}
