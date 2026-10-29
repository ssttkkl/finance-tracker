@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.finance.tracker.backend

import java.math.BigInteger
import kotlinx.serialization.json.*

private fun invalidReceiptSummary(): Nothing=throw IllegalArgumentException("mutation_summary_invalid")
private fun receiptRaw(value: Any?): Any? = if(value is JsonPrimitive) when {
    value==JsonNull ->null
    value.isString ->value.content
    value.booleanOrNull!=null ->value.boolean
    else ->value.content.toBigIntegerOrNull() ?: value.content.toDoubleOrNull() ?: value
} else value
private fun receiptMap(value: Any?)=value as? Map<*,*>
private fun receiptList(value: Any?)=value as? List<*>

internal fun receiptReference(value: Any?,maximum: Int,code: String="mutation_reference_invalid",nullable: Boolean=false): String? {
    val raw=receiptRaw(value)
    if(nullable && raw==null) return null
    val text=if(raw is String) raw else strictCashInteger(raw)?.takeIf {it.signum()>0}?.toString()
    return text?.takeIf {it.matches(Regex("[A-Za-z0-9_:.~-]{1,$maximum}"))} ?: throw IllegalArgumentException(code)
}
internal fun receiptOperation(value: Any?): String=(receiptRaw(value) as? String)?.takeIf {it.matches(Regex("[a-z][a-z0-9_]{0,31}"))} ?: throw IllegalArgumentException("mutation_operation_invalid")
internal fun receiptDigest(value: Any?,nullable: Boolean=false): String? {
    val raw=receiptRaw(value)
    if(nullable && raw==null) return null
    return (raw as? String)?.takeIf {it.matches(Regex("[0-9a-f]{64}"))} ?: throw IllegalArgumentException("mutation_digest_invalid")
}
private fun receiptCount(value: Any?,positive: Boolean=false,bounded: Boolean=false): JsonPrimitive {
    val number=strictCashInteger(receiptRaw(value)) ?: invalidReceiptSummary()
    if(number.signum()<(if(positive) 1 else 0) || bounded && number>BigInteger.valueOf(Long.MAX_VALUE)) invalidReceiptSummary()
    return JsonUnquotedLiteral(number.toString())
}
private fun summaryReference(value: Any?,nullable: Boolean=false)=receiptReference(value,128,"mutation_summary_invalid",nullable)?.let(::JsonPrimitive) ?: JsonNull
private val accountResultNames=setOf("account_id","name","type","active","currencies","account_revision","deleted")
private val commitReasonCodes=setOf("record_changed","account_changed","entry_changed","row_save_failed","source_evidence_save_failed","relation_context_changed","relation_save_failed")
private fun accountResult(value: Any?): JsonObject {
    val account=receiptMap(value) ?: invalidReceiptSummary()
    if(account.keys!=accountResultNames) invalidReceiptSummary()
    val name=receiptRaw(account["name"]) as? String ?: invalidReceiptSummary()
    if(name.codePointCount(0,name.length) !in 1..255) invalidReceiptSummary()
    val type=receiptRaw(account["type"]) as? String ?: invalidReceiptSummary()
    if(type !in setOf("cash","loan","lend","security","crypto")) invalidReceiptSummary()
    val active=receiptRaw(account["active"]) as? Boolean ?: invalidReceiptSummary()
    val deleted=receiptRaw(account["deleted"]) as? Boolean ?: invalidReceiptSummary()
    val revision=receiptCount(account["account_revision"],true,true)
    val currencies=receiptList(account["currencies"])?.map {receiptRaw(it) as? String ?: invalidReceiptSummary()} ?: invalidReceiptSummary()
    if(currencies.size>100 || currencies.any {!it.matches(Regex("[A-Z]{3}"))} || currencies.toSet().size!=currencies.size) invalidReceiptSummary()
    return buildJsonObject {
        put("account_id",summaryReference(account["account_id"]));put("name",name);put("type",type);put("active",active)
        put("currencies",JsonArray(currencies.map(::JsonPrimitive)));put("account_revision",revision);put("deleted",deleted)
    }
}
private fun commitOutcome(value: Any?): JsonObject {
    val item=receiptMap(value) ?: invalidReceiptSummary()
    if(item.keys!=setOf("has_new_failures","has_business_writes","reason_codes")) invalidReceiptSummary()
    val failures=receiptRaw(item["has_new_failures"]) as? Boolean ?: invalidReceiptSummary()
    val writes=receiptRaw(item["has_business_writes"]) as? Boolean ?: invalidReceiptSummary()
    val codes=receiptList(item["reason_codes"])?.map {receiptRaw(it) as? String ?: invalidReceiptSummary()} ?: invalidReceiptSummary()
    if(codes.size>commitReasonCodes.size || codes.any {it !in commitReasonCodes} || codes.toSet().size!=codes.size || codes.isNotEmpty()!=failures) invalidReceiptSummary()
    return buildJsonObject {put("has_new_failures",failures);put("has_business_writes",writes);put("reason_codes",JsonArray(codes.map(::JsonPrimitive)))}
}
internal fun normalizeReceiptSummary(value: Any?): JsonObject {
    val map=receiptMap(value) ?: invalidReceiptSummary()
    val counters=setOf("needs_attention_count","pending_item_count","projection_version","account_revision")
    val references=setOf("account_id","retained_transaction_id")
    val allowed=counters+references+setOf("affected_transaction_ids","status","summary","committed","projection_refs","account","legacy_by_account","commit_outcome")
    if(map.keys.any {it !in allowed}) invalidReceiptSummary()
    val normalized=buildJsonObject {for((rawKey,item) in map) {
        val key=rawKey as String
        put(key,when(key) {
            in counters ->receiptCount(item)
            in references ->summaryReference(item,true)
            "affected_transaction_ids" ->JsonArray((receiptList(item) ?: invalidReceiptSummary()).map {summaryReference(it)})
            "projection_refs" ->JsonArray((receiptList(item) ?: invalidReceiptSummary()).map {raw ->
                val ref=receiptMap(raw) ?: invalidReceiptSummary()
                if(ref.keys!=setOf("transaction_id","projection_id")) invalidReceiptSummary()
                buildJsonObject {for(field in listOf("transaction_id","projection_id")) put(field,summaryReference(ref[field]))}
            })
            "commit_outcome" ->commitOutcome(item)
            "account" ->accountResult(item)
            "status" ->JsonPrimitive((receiptRaw(item) as? String)?.takeIf {it in setOf("completed","partial","no_changes","failed")} ?: invalidReceiptSummary())
            "committed" ->JsonPrimitive((receiptRaw(item) as? Boolean)?.takeIf {it} ?: invalidReceiptSummary())
            "summary","legacy_by_account" -> {
                val counts=receiptMap(item) ?: invalidReceiptSummary()
                buildJsonObject {for((rawName,count) in counts) {
                    val name=rawName as? String ?: invalidReceiptSummary()
                    if(key=="summary" && !name.matches(Regex("[a-z][a-z0-9_]{0,63}"))) invalidReceiptSummary()
                    if(key=="legacy_by_account") try {strictUtf8(name)} catch (_: java.nio.charset.CharacterCodingException) {invalidReceiptSummary()}
                    put(name,receiptCount(count))
                }}
            }
            else ->invalidReceiptSummary()
        })
    }}
    normalized["account"]?.jsonObject?.let {
        if(normalized["account_id"]!=it["account_id"] || normalized["account_revision"]!=it["account_revision"]) invalidReceiptSummary()
    }
    if(receiptAsciiJson(normalized).length>1024*1024) invalidReceiptSummary()
    return normalized
}

internal fun normalizeReceiptResults(value: Any?): List<JsonObject> {
    val list=receiptList(value) ?: throw IllegalArgumentException("mutation_results_invalid")
    fun invalid(): Nothing=throw IllegalArgumentException("mutation_result_invalid")
    val allowed=setOf("result_kind","item_ref","outcome","code","operation_ref","transaction_id","relation_id","account_id")
    val seen=mutableSetOf<Pair<String,String>>()
    return list.map {raw ->
        val item=receiptMap(raw) ?: invalid()
        if(item.keys.any {it !in allowed} || !item.keys.containsAll(listOf("result_kind","item_ref","outcome"))) invalid()
        val kind=receiptRaw(item["result_kind"]) as? String ?: invalid()
        if(kind !in setOf("row","relation","file","account")) invalid()
        val ref=receiptReference(item["item_ref"],255,"mutation_result_invalid")!!
        if(!seen.add(kind to ref)) invalid()
        buildJsonObject {
            put("result_kind",kind);put("item_ref",ref)
            for((field,maximum) in listOf("outcome" to 32,"code" to 64)) {
                val entry=receiptRaw(item[field])
                if(field=="code" && entry==null) {if(field in item) put(field,JsonNull);continue}
                put(field,JsonPrimitive((entry as? String)?.takeIf {it.matches(Regex("[a-z][a-z0-9_]{0,${maximum-1}}"))} ?: invalid()))
            }
            for(field in listOf("transaction_id","relation_id","account_id","operation_ref"))
                put(field,receiptReference(item[field],128,"mutation_result_invalid",true)?.let(::JsonPrimitive) ?: JsonNull)
        }
    }
}

internal fun receiptAsciiJson(value: JsonElement): String {
    fun quoted(text: String): String=buildString {
        append('"')
        for(char in text) when(char) {
            '"' ->append("\\\"")
            '\\' ->append("\\\\")
            '\b' ->append("\\b")
            '\u000C' ->append("\\f")
            '\n' ->append("\\n")
            '\r' ->append("\\r")
            '\t' ->append("\\t")
            else ->if(char.code<32 || char.code>=127) {append("\\u");append(char.code.toString(16).padStart(4,'0'))} else append(char)
        }
        append('"')
    }
    return when(value) {
        is JsonObject ->value.entries.joinToString(", ","{","}") {quoted(it.key)+": "+receiptAsciiJson(it.value)}
        is JsonArray ->value.joinToString(", ","[","]",transform=::receiptAsciiJson)
        else ->if(value.jsonPrimitive.isString) quoted(value.jsonPrimitive.content) else value.jsonPrimitive.content.also {
            if(it.trimStart('-').length>4300) invalidReceiptSummary()
        }
    }
}
