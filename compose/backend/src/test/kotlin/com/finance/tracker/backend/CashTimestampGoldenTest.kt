package com.finance.tracker.backend

import java.time.OffsetDateTime
import kotlin.test.*
import kotlinx.serialization.json.*

class CashTimestampGoldenTest {
    @Test fun timestampAcceptanceAndUtcValuesMatchPythonAcrossIsoFormatsAndBoundaries() {
        val data=requireNotNull(javaClass.getResourceAsStream("/python-cash-timestamps.json")).bufferedReader().use {it.readText()}
        val failures=mutableListOf<String>()
        for(item in Json.parseToJsonElement(data).jsonArray) {
            val row=item.jsonObject;val input=row.getValue("value").jsonPrimitive.content
            val actual=try {pythonCashTimestamp(input)} catch (_: InvalidCashValue) {null}
            if("error" in row) {if(actual!=null) failures.add("accepted $input => $actual")}
            else {
                val expected=OffsetDateTime.parse(row.getValue("utc").jsonPrimitive.content).toInstant()
                if(actual!=expected) failures.add("$input expected $expected actual $actual")
            }
        }
        assertTrue(failures.isEmpty(),"${failures.size} timestamp mismatches: ${failures.take(10)}")
    }
}
