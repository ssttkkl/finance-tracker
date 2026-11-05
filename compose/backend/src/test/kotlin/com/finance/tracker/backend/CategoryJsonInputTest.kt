package com.finance.tracker.backend

import java.util.Base64
import kotlin.test.*
import kotlinx.serialization.json.*

class CategoryJsonInputTest {
    @Test fun integerParsingDoesNotCombineIndependentRawSurrogatesIntoADigit() {
        val prefix="{\"expected_revision\":\"".toByteArray();val suffix="\"}".toByteArray()
        val parsed=categoryDeleteRequest(pythonJsonText(prefix+byteArrayOf(0xed.toByte(),0xa0.toByte(),0x81.toByte(),0xed.toByte(),0xb2.toByte(),0xa1.toByte())+suffix))
        val value=parsed.value.jsonObject.getValue("expected_revision")
        assertEquals("invalid literal for int() with base 10: '\\ud801\\udca1'",assertFailsWith<CategoryFailure> {categoryRevisionMatches(value,null,1,parsed.stringRepresentation(value))}.code)
        val escaped=categoryDeleteRequest(pythonJsonText(("{\"expected_revision\":\"\\ud801\\udca1\"}").toByteArray()))
        val digit=escaped.value.jsonObject.getValue("expected_revision")
        assertTrue(categoryRevisionMatches(digit,null,1,escaped.stringRepresentation(digit)))
    }
    @Test fun jsonBytesMatchPythonValuesAndPreciseErrors() {
        val cases=Json.parseToJsonElement(javaClass.getResource("/python-category-json.json")!!.readText()).jsonArray
        for((index,raw) in cases.withIndex()) {
            val case=raw.jsonObject;val bytes=Base64.getDecoder().decode(case.getValue("bytes").jsonPrimitive.content)
            if("error" in case) assertEquals(case.getValue("error").jsonPrimitive.content,assertFailsWith<CategoryFailure>("case $index") {categoryDeleteBody(bytes)}.code,"case $index")
            else assertEquals(case.getValue("value"),categoryDeleteBody(bytes),"case $index")
        }
    }
    @Test fun integerStringLimitRetainsPythonDigitCountingAndError() {
        for(digit in listOf("9","９")) {
            assertFalse(categoryRevisionMatches(JsonPrimitive(digit.repeat(4300)),null,0))
            assertEquals("Exceeds the limit (4300 digits) for integer string conversion: value has 4301 digits; use sys.set_int_max_str_digits() to increase the limit",
                assertFailsWith<CategoryFailure> {categoryRevisionMatches(JsonPrimitive(digit.repeat(4301)),null,0)}.code)
        }
    }

    @Test fun invalidIntegerLiteralUsesAtMostTwoHundredPythonReprCharacters() {
        for((input,expected) in listOf("x".repeat(250) to ("'"+"x".repeat(199)),"😀".repeat(201) to ("'"+"😀".repeat(199)),"\n".repeat(150) to ("'"+"\\n".repeat(99)+"\\"))) {
            assertEquals("invalid literal for int() with base 10: $expected",assertFailsWith<CategoryFailure> {categoryRevisionMatches(JsonPrimitive(input),null,0)}.code)
        }
    }

}
