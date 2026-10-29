package com.finance.tracker.backend

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*

internal class ManualCashInputFailure:RuntimeException("invalid_record")
internal suspend fun ApplicationCall.manualCashBody():ManualCashRequest=manualCashRequest(receiveChannel().readRemaining(65537).readByteArray())
internal data class ManualCashRequest(val value:JsonObject,val original:DecodedPythonJson)
private val manualCashFields=setOf("account_name","amount","currency","occurred_at","counterparty","counterparty_account","counterparty_account_attrs","note","category_id","category","record_type","record_subtype","components")
private val manualCashComponentFields=setOf("account_name","account_id","amount","currency","label")
private fun invalidManualCash():Nothing=throw ManualCashInputFailure()

internal fun manualCashApplicationInput(value:JsonElement):JsonObject {
    val body=value as? JsonObject ?: invalidManualCash()
    if((body.keys-manualCashFields).isNotEmpty()) invalidManualCash()
    val components=body["components"]
    if(components!=null && components!=JsonNull) {
        if(components !is JsonArray || components.size>64) invalidManualCash()
        for(item in components) if(item !is JsonObject || (item.keys-manualCashComponentFields).isNotEmpty()) invalidManualCash()
    }
    return copyCashJsonContainers(body).jsonObject
}
private fun strictManualString(body:JsonObject,key:String,required:Boolean=false,nullable:Boolean=false) {
    val value=body[key]
    if(value==null) {if(required) invalidManualCash();return}
    if(value==JsonNull && nullable) return
    if(value !is JsonPrimitive || !value.isString) invalidManualCash()
}
private fun strictManualIntegerOrString(body:JsonObject,key:String,required:Boolean=false,nullable:Boolean=false) {
    val value=body[key]
    if(value==null) {if(required) invalidManualCash();return}
    if(value==JsonNull && nullable) return
    if(value !is JsonPrimitive || !value.isString && !value.content.matches(Regex("-?(?:0|[1-9][0-9]*)"))) invalidManualCash()
}
internal fun manualCashRequest(bytes:ByteArray):ManualCashRequest {
    if(bytes.size>65536) invalidManualCash()
    val decoded=try {strictCashJsonRequest(pythonJsonText(bytes))}
        catch(_:CategoryFailure) {invalidManualCash()}
        catch(_:NumberFormatException) {invalidManualCash()}
    val body=manualCashApplicationInput(decoded.value)
    strictManualIntegerOrString(body,"amount",required=true)
    strictManualString(body,"currency",required=true);strictManualString(body,"occurred_at",required=true)
    for(key in listOf("account_name","counterparty","counterparty_account","record_type","record_subtype")) strictManualString(body,key)
    for(key in listOf("note","category_id","category")) strictManualString(body,key,nullable=true)
    body["counterparty_account_attrs"]?.let {value ->
        if(value !is JsonArray || value.any {it !is JsonPrimitive || !it.isString}) invalidManualCash()
    }
    val components=body["components"]
    if(components is JsonArray) for(raw in components) {
        val item=raw.jsonObject;strictManualIntegerOrString(item,"amount",required=true)
        strictManualIntegerOrString(item,"account_id",nullable=true)
        strictManualString(item,"account_name");strictManualString(item,"label");strictManualString(item,"currency",nullable=true)
    }
    return ManualCashRequest(body,decoded)
}
