package com.finance.tracker.backend

import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashSqlValuesMigrationTest {
    private fun database():Database {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    @Test fun sqlTimestampBindingUsesPythonMicrosecondsOnBothBackends() {
        val db=database()
        for(text in listOf("2026-08-12T00:00:00.123456999Z","2026-12-31T23:59:59.999999999Z","0001-01-01T00:00:00.000000999Z","9999-12-30T23:59:59.999999999Z")) {
            val value=Instant.parse(text)
            db.read {c ->c.prepareStatement("SELECT ?").use {it.instant(1,value,db.sqlite);it.executeQuery().use {r ->assertTrue(r.next());assertEquals(value.truncatedTo(ChronoUnit.MICROS),storedCashInstant(r.getString(1),db.sqlite),text)}}}
        }
    }
    @Test fun storedDecimalRepresentationKeepsSqliteScaleAndNormalizesOnlyPostgresPadding() {
        assertEquals("-0.00",storedCashDecimal("-0.00",true).text)
        assertEquals("-10.0000",storedCashDecimal("-10.0000",true).text)
        assertEquals("0",storedCashDecimal("-0.000000000000000000",false).text)
        assertEquals("-1E+1",storedCashDecimal("-10.000000000000000000",false).text)
        assertEquals("1.234567890123456789",storedCashDecimal("1.234567890123456789",false).text)
    }
    @Test fun canonicalSourceValuesMatchPythonAndRejectFloatingOrOverPreciseInputs() {
        val text=canonicalCashJson(mapOf("金额" to storedCashDecimal("-0.00",true),"time" to Instant.parse("2026-08-12T01:02:03.000004Z"),"data" to Json.parseToJsonElement("""{"z":"商家","a":[true,null,123]}""")))
        assertEquals("""{"data":{"a":[true,null,123],"z":"商家"},"time":"2026-08-12T01:02:03.000004+00:00","金额":"-0.00"}""",text)
        for(value in listOf<Any>(1.0,Json.parseToJsonElement("1.0"),storedCashDecimal("1.0000000000000000000",true),storedCashDecimal("NaN",true))) {
            assertFailsWith<CashCanonicalFailure> {canonicalCashJson(value)}
        }
    }
    @Test fun completeCanonicalTextAndUtf8DigestMatchPythonAcrossSyntheticValues() {
        val rows=Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/python-cash-canonical.json")).bufferedReader().use {it.readText()}).jsonArray
        for((index,raw) in rows.withIndex()) {
            val row=raw.jsonObject;val input=row.getValue("input").jsonObject;val rawValue=input.getValue("value")
            val value:Any?=when(input.getValue("kind").jsonPrimitive.content) {
                "decimal" ->storedCashDecimal(rawValue.jsonPrimitive.content,true)
                "timestamp" ->pythonCashTimestamp(rawValue.jsonPrimitive.content)
                "float" ->when(val text=rawValue.jsonPrimitive.content) {"nan" ->Double.NaN;"inf" ->Double.POSITIVE_INFINITY;"-inf" ->Double.NEGATIVE_INFINITY;else ->text.toDouble()}
                else ->rawValue
            }
            val actual=try {val text=canonicalCashJson(value);buildJsonObject {put("text",text);put("sha256",tokenDigest(text))}}
                catch(f:CashCanonicalFailure) {buildJsonObject {put("error",f.code)}}
            assertEquals(JsonObject(row.filterKeys {it!="input"}),actual,"canonical case $index input=$input")
        }
    }
}
