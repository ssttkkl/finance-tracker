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

internal fun accountCurrencyRequirements(body: JsonObject): List<JsonObject> {
    fun invalid(): Nothing = throw AccountFailure("account_payload_invalid")
    if(body.keys!=setOf("items")) invalid()
    val items=body["items"] as? JsonArray ?: invalid()
    if(items.size !in 1..100) invalid()
    val ids=mutableSetOf<String>();val keys=mutableSetOf<String>()
    return items.map { value ->
        val item=value as? JsonObject ?: invalid()
        if(item.keys!=setOf("idempotency_key","account_id","expected_revision","currencies")) invalid()
        fun text(key: String)=(item[key] as? JsonPrimitive)?.takeIf {it.isString}?.content ?: invalid()
        val id=text("account_id");val key=text("idempotency_key")
        if(!id.matches(Regex("[1-9][0-9]{0,18}")) || id.toLongOrNull()==null || !ids.add(id) ||
            !key.matches(Regex("[A-Za-z0-9_:.~-]{1,255}")) || !keys.add(key)) invalid()
        val revision=accountRevision(item["expected_revision"])
        val currencies=item["currencies"] as? JsonArray ?: invalid()
        if(currencies.size !in 1..100) invalid()
        val normalized=currencies.map {
            val currency=(it as? JsonPrimitive)?.takeIf {value ->value.isString}?.content ?: invalid()
            if(!currency.matches(Regex("[A-Za-z]{3}"))) invalid()
            currency.uppercase()
        }.distinct().sorted()
        buildJsonObject {put("account_id",id);put("idempotency_key",key);put("expected_revision",revision)
            put("currencies",JsonArray(normalized.map(::JsonPrimitive)))}
    }
}

internal fun accountFailureStatus(code: String): Pair<Int,String> = when(code) {
    "invalid_filter" -> 400 to "不支持该账本视图。"
    "workspace_forbidden" -> 403 to "当前用户无权访问该账户。"
    "account_not_found" -> 404 to "未找到账户。"
    "account_changed" -> 409 to "账户已变化，请刷新后重试。"
    "account_in_use" -> 409 to "账户已被使用，不能删除。"
    "account_active" -> 409 to "请先停用账户。"
    "account_name_unavailable" -> 409 to "账户名称已被使用。"
    "idempotency_conflict" -> 409 to "这次操作与上次不同，请重试。"
    "idempotency_key_required" -> 400 to "未能保存账户，请重试。"
    else -> 400 to "账户信息有误，请检查后重试。"
}
