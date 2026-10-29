package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class ManualCashProtocolMigrationTest {
    @Test fun directApplicationBoundaryCopiesCallerOwnedContainers() {
        val attributes=mutableListOf<JsonElement>(JsonPrimitive("tail"))
        val component=mutableMapOf<String,JsonElement>("amount" to JsonPrimitive("10"))
        val components=mutableListOf<JsonElement>(JsonObject(component))
        val fields=mutableMapOf<String,JsonElement>(
            "amount" to JsonPrimitive("10"),
            "counterparty_account_attrs" to JsonArray(attributes),
            "components" to JsonArray(components),
        )
        val input=JsonObject(fields)
        val accepted=manualCashApplicationInput(input)
        val before=accepted.toString()
        fields["state"]=JsonPrimitive("draft")
        fields["manual_overrides"]=JsonObject(emptyMap())
        component["metadata_json"]=JsonPrimitive("private")
        components.add(JsonObject(mapOf("id" to JsonPrimitive(42))))
        attributes.clear()
        assertEquals(before,accepted.toString())
        assertNotSame(input,accepted)
        assertNotSame(input["components"],accepted["components"])
        assertNotSame(input["counterparty_account_attrs"],accepted["counterparty_account_attrs"])
        assertNotSame(components[0],accepted.getValue("components").jsonArray[0])
    }
    private val minimal="""{"amount":"-10","currency":"CNY","occurred_at":"2026-08-04T12:00:00+00:00"}"""
    private fun decode(text:String)=manualCashRequest(text.toByteArray()).value
    @Test fun strictRequestPreservesOmittedAndExplicitNullPublicFields() {
        val omitted=decode(minimal);assertFalse("note" in omitted);assertFalse("category_id" in omitted)
        val explicit=decode(minimal.dropLast(1)+",\"note\":null,\"category_id\":null}");assertEquals(JsonNull,explicit["note"]);assertEquals(JsonNull,explicit["category_id"])
        assertEquals(JsonPrimitive(0),decode(minimal.replace("\"-10\"","-0"))["amount"])
        assertEquals(JsonPrimitive(""),decode(minimal.dropLast(1)+",\"note\":\"\"}")["note"])
    }
    @Test fun strictFieldTypesRejectBooleansFloatsContainersAndUnknownServerFields() {
        val base=Json.parseToJsonElement(minimal).jsonObject
        val bad=listOf("amount" to JsonPrimitive(true),"amount" to JsonPrimitive(1.0),"currency" to JsonNull,"occurred_at" to JsonPrimitive(1),"note" to JsonArray(emptyList()),"account_name" to JsonNull,"counterparty_account_attrs" to JsonArray(listOf(JsonPrimitive(1))))
        for((key,value) in bad) assertFailsWith<ManualCashInputFailure> {decode(JsonObject(base+(key to value)).toString())}
        for(field in listOf("relation_metadata","manual_overrides","state","source_type","record_id","source_payload","workspace_id","row_revision","deleted_at","id","account_type")) {
            val body=JsonObject(base+(field to buildJsonObject {put("private","forged")}))
            assertFailsWith<ManualCashInputFailure> {decode(body.toString())};assertFailsWith<ManualCashInputFailure> {manualCashApplicationInput(body)}
        }
    }
    @Test fun componentsKeepStrictPublicFieldsAndSixtyFourLimit() {
        val base=Json.parseToJsonElement(minimal).jsonObject
        val item=Json.parseToJsonElement("""{"account_id":101,"amount":10,"currency":null,"label":"现金"}""")
        assertEquals(JsonArray(listOf(item)),decode(JsonObject(base+("components" to JsonArray(listOf(item)))).toString())["components"])
        for(size in listOf(0,64)) decode(JsonObject(base+("components" to JsonArray(List(size) {item}))).toString())
        assertFailsWith<ManualCashInputFailure> {decode(JsonObject(base+("components" to JsonArray(List(65) {item}))).toString())}
        for(field in listOf("metadata_json","source_key","component_id","id","ordinal","account_type")) {
            val bad=JsonObject(item.jsonObject+(field to JsonPrimitive("private")));val body=JsonObject(base+("components" to JsonArray(listOf(bad))))
            assertFailsWith<ManualCashInputFailure> {decode(body.toString())};assertFailsWith<ManualCashInputFailure> {manualCashApplicationInput(body)}
        }
        for(value in listOf(JsonPrimitive(false),JsonPrimitive(1.5),JsonArray(emptyList()))) {
            val bad=JsonObject(item.jsonObject+("account_id" to value));assertFailsWith<ManualCashInputFailure> {decode(JsonObject(base+("components" to JsonArray(listOf(bad)))).toString())}
        }
    }
    @Test fun encodingDetectionMatchesPythonBytesWithoutLeakingMalformedInputs() {
        for(charset in listOf("UTF-8","UTF-16","UTF-16LE","UTF-16BE","UTF-32LE","UTF-32BE")) assertEquals(decode(minimal),manualCashRequest(minimal.toByteArray(charset(charset))).value)
        assertFailsWith<ManualCashInputFailure> {manualCashRequest(byteArrayOf(123,34,120,34,58,34,0xff.toByte(),34,125))}
        assertFailsWith<ManualCashInputFailure> {decode("[".repeat(2000)+"0"+"]".repeat(2000))}
        assertFailsWith<ManualCashInputFailure> {manualCashRequest(ByteArray(65537) {32})}
    }
    @Test fun duplicateKeysNonFiniteNumbersAndPythonIntegerLimitAreProtocolFailures() {
        for(text in listOf("{}","[]",minimal.dropLast(1)+",\"amount\":\"-20\"}",minimal.replace("\"-10\"","1e999"),minimal.replace("\"-10\"","NaN"),minimal.replace("\"-10\"","9".repeat(4301)))) assertFailsWith<ManualCashInputFailure> {decode(text)}
        assertEquals(4300,decode(minimal.replace("\"-10\"","9".repeat(4300))).getValue("amount").jsonPrimitive.content.length)
        val component="""{"amount":"1","account_id":1,"account_id":2}"""
        assertFailsWith<ManualCashInputFailure> {decode(minimal.dropLast(1)+",\"components\":[$component]}")}
    }
    @Test fun completeStrictAndApplicationBoundariesMatchEveryPythonGolden() {
        val resource=requireNotNull(javaClass.getResource("/python-manual-cash-protocol.json"))
        val cases=Json.parseToJsonElement(resource.readText()).jsonArray
        fun stringRepresentations(value:JsonElement,request:ManualCashRequest,path:String=""):Map<String,JsonElement> =when(value) {
            is JsonObject ->value.entries.flatMap {(key,item) ->stringRepresentations(item,request,"$path/$key").entries}.associate {it.key to it.value}
            is JsonArray ->value.flatMapIndexed {index,item ->stringRepresentations(item,request,"$path/$index").entries}.associate {it.key to it.value}
            is JsonPrimitive ->if(value.isString) mapOf(path to JsonPrimitive(requireNotNull(request.original.stringRepresentation(value)))) else emptyMap()
        }
        for(raw in cases) {
            val case=raw.jsonObject;val label=case.getValue("label").jsonPrimitive.content;val bytes=java.util.Base64.getDecoder().decode(case.getValue("bytes").jsonPrimitive.content)
            if(case.getValue("accepted").jsonPrimitive.boolean) {
                val request=manualCashRequest(bytes)
                assertEquals(case.getValue("payload"),request.value,label)
                assertEquals(case.getValue("string_reprs"),JsonObject(stringRepresentations(request.value,request)),"$label original Unicode representation")
            } else assertFailsWith<ManualCashInputFailure>(label) {manualCashRequest(bytes)}
            case["application_accepted"]?.let {accepted ->
                val body=categoryDeleteRequest(pythonJsonText(bytes)).value
                if(accepted.jsonPrimitive.boolean) assertEquals(body,manualCashApplicationInput(body),label)
                else assertFailsWith<ManualCashInputFailure>(label) {manualCashApplicationInput(body)}
            }
        }
        assertEquals(308,cases.size)
    }
}
