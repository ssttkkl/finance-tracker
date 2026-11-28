package com.finance.tracker.backend

import com.finance.tracker.business.contracts.ReadPageChunk
import com.finance.tracker.business.contracts.ReadPagePosition
import com.finance.tracker.business.contracts.CashImportReceiptReadPage
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

class CompatibleReadPageProtocolTest {
    @Test
    fun acceptedRowsCaptureTheExactResponseBeforeAdvancingTheCursor() {
        val position = ReadPagePosition("transactions", 0)
        val original = Json.parseToJsonElement("""{"amount":1e0,"id":9007199254740993,"large":18446744073709551619,"zero":-0,"text":"原值😀\n\t","extras":[null,false]}""")
        val fields = (original as JsonObject).toMutableMap()
        val candidate = JsonObject(fields)
        val candidates = sequence {
            yield(candidate)
            fields["text"] = JsonPrimitive("advanced")
            yield(original)
        }
        val actual = boundedReadPageChunk(position, listOf(position.section), 2, candidates, 8_192)
        val legacy = ReadPageChunk(position, 2, listOf(original, original), null, null)
        assertEquals(compatibleReadPageJson.encodeToString(legacy), compatibleReadPageJson.encodeToString(actual))
        assertEquals(compatibleReadPageJson.encodeToString(legacy).encodeToByteArray().size, actual.encodedSize())
        val expected = CashImportReceiptReadPage("workspace", "receipt", "42", legacy, listOf(legacy))
        val header = expected.copy(chunk = actual.headerChunk(), moreChunks = emptyList())
        assertEquals(compatibleReadPageJson.encodeToString(expected), encodedReadPageBytes(header, listOf(actual, actual)).decodeToString())
    }

    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun invalidRawTokensCannotReachTheResponse() {
        val position = ReadPagePosition("transactions", 0)
        val value = JsonUnquotedLiteral("false,[]")
        assertFailsWith<IllegalArgumentException> {
            boundedReadPageChunk(position, listOf(position.section), 1, sequenceOf(value), 4_096)
        }
    }

    @Test
    fun fragmentOffsetsStillReferToTheOriginalUtf8Record() {
        val section = "transactions"
        val row = JsonObject(mapOf("text" to JsonPrimitive("账😀".repeat(1_000))))
        val decoded = mutableListOf<Byte>()
        var next: ReadPagePosition? = ReadPagePosition(section, 0)
        while (next != null) {
            val chunk = boundedReadPageChunk(next, listOf(section), 1, sequenceOf(row), 4_096)
            val fragment = assertNotNull(chunk.fragment)
            assertEquals(row.toString().encodeToByteArray().size, fragment.totalBytes)
            decoded += Base64.getDecoder().decode(fragment.base64).toList()
            next = chunk.next
        }
        assertEquals(row.toString(), decoded.toByteArray().decodeToString())
    }
}
