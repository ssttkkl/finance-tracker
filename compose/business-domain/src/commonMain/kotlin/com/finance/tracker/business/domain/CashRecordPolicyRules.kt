package com.finance.tracker.business.domain

import kotlinx.serialization.json.*

val mutableCashFields = setOf("counterparty", "counterparty_account", "counterparty_account_attrs", "note",
    "category_id", "record_type", "record_subtype", "source_payload")
val financialCashFields = setOf("amount", "currency", "occurred_at", "account_id", "account_name", "components", "cash_granularity")
val editableCashRecordFields = (mutableCashFields + financialCashFields) - "account_name"

class InvalidCashValue : RuntimeException("invalid_cash_value")
class CashPolicyError(val code: String,val fields: List<String>) : RuntimeException(code)
data class CashPolicyUpdate(val values: Map<String,Any?>,val typeChanged: Boolean,
    val protectedFields: List<String>,val normalizedValues: Map<String,Any?>)

/** Shared field decisions over values normalized by the calling platform adapter. */
class CashRecordPolicyRules(
    private val normalize: (String, Any?) -> Any?,
    private val equal: (Any?, Any?) -> Boolean,
) {
    private val mutable=mutableCashFields
    private val financial=financialCashFields
    private val identity=setOf("record_id","source_type","source_scope_key","source_account_key","record_origin","manual_transfer_id")
    private val recognized=mutable+financial+identity
    private val types=cashRecordTypes
    private val subtypes=cashRecordSubtypes
    private fun sorted(values: Collection<String>) = values.sortedWith(Comparator(::pythonStringOrder))
    private fun validPair(type: Any?, subtype: Any?) = validCashRecordPair(type, subtype)
    fun prepare(current: Map<*,*>,patch: Any?,protected: Set<String> = emptySet()): CashPolicyUpdate {
        if(patch !is Map<*,*> || patch.keys.any {it !is String}) throw CashPolicyError("invalid_field",emptyList())
        val state=if("state" in current) current["state"] else "posted"
        if(state !in setOf("draft","posted")) throw CashPolicyError("invalid_state",listOf("state"))
        val keys=patch.keys.filterIsInstance<String>()
        val unknown=sorted((keys-recognized).toSet())
        if(unknown.isNotEmpty()) throw CashPolicyError("field_not_editable",unknown)
        val normalized=linkedMapOf<String,Any?>()
        for(field in keys) try {normalized[field]=normalize(field,patch[field])}
            catch (_: InvalidCashValue) {throw CashPolicyError("invalid_field",listOf(field))}
        val fixed=identity+(if(state=="posted" && current["record_origin"] !in setOf("manual","manual_transfer")) financial+setOf("record_type","record_subtype") else emptySet())
        val conflicts=sorted((fixed intersect keys.toSet()).filter {field ->
            try {!equal(normalize(field,current[field]),normalized[field])} catch (_: InvalidCashValue) {true}
        })
        if(conflicts.isNotEmpty()) throw CashPolicyError("fixed_field_conflict",conflicts)
        val protectedFields=sorted(normalized.keys intersect protected intersect mutable)
        val effective=normalized.filterKeys {it !in fixed && it !in protected}
        val type=if("record_type" in effective) effective["record_type"] else current["record_type"]
        val subtype=if("record_subtype" in effective) effective["record_subtype"] else current["record_subtype"]
        if(state=="posted" || type!=null && subtype!=null) {
            if(!validPair(type,subtype)) throw CashPolicyError("invalid_record_type",listOf("record_type","record_subtype"))
        } else {
            if(type!=null && type !in types) throw CashPolicyError("invalid_record_type",listOf("record_type"))
            if(subtype!=null && subtype !in subtypes) throw CashPolicyError("invalid_record_type",listOf("record_subtype"))
        }
        val changed=effective.filter {(field,value) ->try {!equal(normalize(field,current[field]),value)} catch (_: InvalidCashValue) {true}}
        return CashPolicyUpdate(changed,changed.keys.any {it in setOf("record_type","record_subtype")},protectedFields,normalized)
    }
    fun sourceNote(payload:Map<*,*>?):String=cashRecordSourceNote(payload)
    private fun known(values: Map<String,Any?>) {
        fun scalar(value: Any?): JsonElement=when(value) {
            null ->JsonNull
            is CashAmount ->JsonPrimitive(value.toString())
            is String ->JsonPrimitive(value)
            is Int ->JsonPrimitive(value)
            else ->error("unexpected_normalized_cash_value")
        }
        val record=buildJsonObject {
            for(field in listOf("amount","currency","account_id","cash_granularity")) put(field,scalar(values[field]))
            @Suppress("UNCHECKED_CAST") val parts=values["components"] as List<Map<String,Any?>>
            put("components",JsonArray(parts.map {part ->buildJsonObject {
                for(field in listOf("component_id","account_id","amount","currency","ordinal")) if(field in part) put(field,scalar(part[field]))
            }}))
        }
        cashComponentError(record)?.let {throw CashPolicyError(it.code,it.fields)}
    }
    fun validateConsistency(record: Map<*,*>) {
        val values=linkedMapOf<String,Any?>()
        for(field in listOf("amount","currency","account_id","cash_granularity","components")) try {
            values[field]=normalize(field,if(field in record) record[field] else when(field) {"cash_granularity" ->"atomic";"components" ->emptyList<Any>();else ->null})
        } catch (_: InvalidCashValue) {throw CashPolicyError("invalid_field",listOf(field))}
        known(values)
    }
    fun validateForPosting(record: Map<*,*>) {
        val required=mutableSetOf("amount","currency","occurred_at","record_type","record_subtype")
        if((if("cash_granularity" in record) record["cash_granularity"] else "atomic")=="atomic") required.add("account_id")
        val missing=sorted(required.filter {record[it]==null || record[it]==""})
        if(missing.isNotEmpty()) throw CashPolicyError("incomplete_record",missing)
        if(record["cash_granularity"]=="aggregate" && record["account_id"]!=null) throw CashPolicyError("component_account_mismatch",listOf("account_id"))
        val normalized=linkedMapOf<String,Any?>()
        for(field in sorted(required+setOf("components","cash_granularity"))) try {
            normalized[field]=normalize(field,if(field=="cash_granularity" && field !in record) "atomic" else record[field])
        } catch (_: InvalidCashValue) {throw CashPolicyError("invalid_field",listOf(field))}
        if(!validPair(normalized["record_type"],normalized["record_subtype"])) throw CashPolicyError("invalid_record_type",listOf("record_type","record_subtype"))
        @Suppress("UNCHECKED_CAST") val parts=normalized.getValue("components") as List<Map<String,Any?>>
        if(parts.isEmpty() || parts.any {part ->listOf("account_id","amount","currency","ordinal").any {part[it]==null}}) throw CashPolicyError("incomplete_record",listOf("components"))
        known(normalized)
    }
}

fun cashRecordSourceNote(payload:Map<*,*>?):String {
    for(field in listOf("备注","備註","注释","描述","商品说明","商品","交易描述","附言","交易地点/附言","摘要","remark","remarks","comment","comments","description","note")) {
        val value=payload?.get(field)
        if(value is String && value.trim {it.code in pythonWhitespace}.isNotEmpty()) return value
    }
    return ""
}
