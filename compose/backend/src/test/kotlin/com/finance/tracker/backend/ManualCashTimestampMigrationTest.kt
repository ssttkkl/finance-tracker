package com.finance.tracker.backend

import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

class ManualCashTimestampMigrationTest {
    @Test fun originalByteSurrogateBoundariesRemainVisibleToManualDateParsing() {
        fun request(separator:ByteArray):ManualCashRequest {
            val prefix="""{"amount":"1","currency":"CNY","occurred_at":" 2026-08-04""".toByteArray()
            return manualCashRequest(prefix+separator+"12:00 \"}".toByteArray())
        }
        for(bytes in listOf("😀".toByteArray(),"\\ud83d\\ude00".toByteArray())) {
            val input=request(bytes);val value=input.value.getValue("occurred_at")
            assertEquals(Instant.parse("2026-08-04T12:00:00Z"),manualCashTimestamp(value.jsonPrimitive.content,input.original.stringRawSurrogates(value)))
        }
        val input=request(byteArrayOf(0xed.toByte(),0xa0.toByte(),0xbd.toByte(),0xed.toByte(),0xb8.toByte(),0x80.toByte()))
        val value=input.value.getValue("occurred_at")
        assertFailsWith<IllegalArgumentException> {manualCashTimestamp(value.jsonPrimitive.content,input.original.stringRawSurrogates(value))}
    }
    @Test fun repositoryDatesAndNaiveClocksUseUtcWhilePostingPolicyStillRequiresAnOffset() {
        for(value in listOf("2026-08-04","20260804","2026-W32-2","2026W322")) {
            assertEquals(Instant.parse("2026-08-04T00:00:00Z"),manualCashTimestamp(value))
            assertFailsWith<InvalidCashValue> {pythonCashTimestamp(value)}
        }
        assertEquals(Instant.parse("2026-08-04T12:34:56.123456Z"),manualCashTimestamp(" 2026-08-04T12:34:56.123456789 "))
        assertEquals(Instant.parse("2026-08-04T12:00:00Z"),manualCashTimestamp("2026-08-04Z12:00"))
        assertEquals("date is required",assertFailsWith<IllegalArgumentException> {manualCashTimestamp(" \n")}.message)
        assertEquals("invalid transaction date: wrong",assertFailsWith<IllegalArgumentException> {manualCashTimestamp(" wrong ")}.message)
    }
    @Test fun completeRepositoryTimestampGoldenMatchesPython() {
        val rows=Json.parseToJsonElement(requireNotNull(javaClass.getResource("/python-manual-cash-timestamps.json")).readText()).jsonArray
        for(raw in rows) {
            val row=raw.jsonObject;val input=row.getValue("value").jsonPrimitive.content
            if("overflow" in row) assertEquals(row.getValue("overflow").jsonPrimitive.content,
                assertFailsWith<ArithmeticException>(input) {manualCashTimestamp(input)}.message,input)
            else if("error" in row) assertEquals(row.getValue("error").jsonPrimitive.content,
                assertFailsWith<IllegalArgumentException>(input) {manualCashTimestamp(input)}.message,input)
            else assertEquals(Instant.parse(row.getValue("utc").jsonPrimitive.content),manualCashTimestamp(input),input)
        }
        assertTrue(rows.size>10000)
    }
}
