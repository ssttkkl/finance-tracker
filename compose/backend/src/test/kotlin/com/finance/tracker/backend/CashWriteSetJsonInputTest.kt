package com.finance.tracker.backend

import io.ktor.utils.io.ByteReadChannel
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class CashWriteSetJsonInputTest {
    @Test
    fun canonicalDigestKeepsExactEscapesAndNumbersWithoutDependingOnObjectOrder() {
        val canonical = """{"a":[1e+02,{"界":"值\n"}],"z":9007199254740993}"""
        val reordered = """{"z":9007199254740993,"a":[1e+02,{"\u754c":"值\n"}]}"""
        val left = CashImportProtocol.writeSetInput(canonical.encodeToByteArray())
        val right = CashImportProtocol.writeSetInput(reordered.encodeToByteArray())
        assertEquals(left, right)
        val historical = cashWriteSetRequestBody(left)
        val current = cashWriteSetRequestBody(right, canonicalTree = false)
        assertEquals(historical.digest, current.digest)
        assertEquals(com.finance.tracker.business.domain.sha256Hex(canonical), current.digest)
        assertEquals(historical.payload.toString(), canonicalQueuedCashWriteSet(current.payload))
        assertFailsWith<CashImportProtocolError> {
            CashImportProtocol.writeSetInput("""{"a":1,"\u0061":2}""".encodeToByteArray())
        }
    }

    @Test fun reusedUtf8BufferKeepsCanonicalBytesAndExactLimitsAcrossChunks() {
        val tail = "界😀".repeat(12_000)
        val uncopied = JsonPrimitive(tail).toString()
        val unicode = CashWriteSetCanonicalDigest(uncopied.encodeToByteArray().size)
        unicode.quoted(tail)
        assertEquals(com.finance.tracker.business.domain.sha256Hex(uncopied), unicode.finish())
        val raw = "界😀\t\n\u0001\\\"$tail"
        val escaped = JsonPrimitive(raw).toString()
        val canonical = """{"a":[-0,1E+003,9007199254740993,$escaped],"z":false}"""
        val reordered = """{"z":false,"a":[-0,1E+003,9007199254740993,$escaped]}"""
        val input = CashImportProtocol.writeSetInput(reordered.encodeToByteArray())
        val expected = com.finance.tracker.business.domain.sha256Hex(canonical)
        assertEquals(expected, cashWriteSetRequestBody(input).digest)
        assertEquals(expected, cashWriteSetRequestBody(input, canonicalTree = false).digest)
        assertEquals(canonical, canonicalQueuedCashWriteSet(input))

        val limit = canonical.encodeToByteArray().size
        fun digest(cap: Int): String {
            val writer = CashWriteSetCanonicalDigest(cap)
            writer.append("""{"a":[-0,1E+003,9007199254740993,""")
            writer.quoted(raw)
            writer.append("""],"z":false}""")
            return writer.finish()
        }
        assertEquals(expected, digest(limit))
        assertEquals("write_set_payload_invalid", assertFailsWith<CashWriteSetSubmissionFailure> { digest(limit - 1) }.code)
        for (bad in listOf("\ud800", "\udc00", "x\ud800x")) {
            val failure = assertFailsWith<CashWriteSetSubmissionFailure> {
                cashWriteSetRequestBody(buildJsonObject { put("bad", bad) }, canonicalTree = false)
            }
            assertEquals("write_set_payload_invalid", failure.code)
        }
    }

    @Test fun standardJsonNumbersRemainExactTokensWithoutHostNumericConversion() {
        val numbers = listOf("1.2300", "-0", "0.0", "1E+003", "9007199254740993", "1e9999999999", "9".repeat(4301))
        for (number in numbers) {
            val parsed = CashImportProtocol.writeSetInput("{\"x\":$number}".encodeToByteArray()).getValue("x").jsonPrimitive
            assertFalse(parsed.isString); assertEquals(number, parsed.content)
        }
        assertEquals("界😀", CashImportProtocol.writeSetInput("{\"x\":\"界😀\"}".encodeToByteArray()).getValue("x").jsonPrimitive.content)
        assertEquals("1.23", CashImportProtocol.objectInput("{\"x\":1.2300}".encodeToByteArray()).getValue("x").jsonPrimitive.content)
        assertFailsWith<CashImportProtocolError> { CashImportProtocol.objectInput(("{\"x\":" + "9".repeat(4301) + "}").encodeToByteArray()) }
    }
    @Test fun onlyStrictUtf8StandardJsonObjectsWithUniqueKeysAreAccepted() {
        for (text in listOf("[]", "null", "{}{}", "{\"x\":1,\"\\u0078\":2}", "{\"a\":{\"x\":1,\"x\":2}}", "{\"x\":NaN}",
            "{\"x\":Infinity}", "{\"x\":-Infinity}", "{\"x\":+1}", "{\"x\":.1}", "{\"x\":01}", "{\"x\":1.}", "{\"x\":1e}", "{\"x\":true,}", "\uFEFF{}")) {
            assertFailsWith<CashImportProtocolError>(text) { CashImportProtocol.writeSetInput(text.encodeToByteArray()) }
        }
        assertFailsWith<CashImportProtocolError> { CashImportProtocol.writeSetInput(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x7b, 0, 0x7d, 0)) }
    }
    @Test fun bodyReadAndStructureDepthAreBoundedBeforeContractDecoding() = runBlocking {
        fun text(depth: Int) = "{\"x\":" + "[".repeat(depth) + "0" + "]".repeat(depth) + "}"
        CashImportProtocol.writeSetInput(text(127).encodeToByteArray())
        assertFailsWith<CashImportProtocolError> { CashImportProtocol.writeSetInput(text(128).encodeToByteArray()) }
        val valid = "{\"x\":\"界\"}".encodeToByteArray()
        assertEquals("界", CashImportProtocol.jsonInput(ByteReadChannel(valid), valid.size, CashImportProtocol::writeSetInput).getValue("x").jsonPrimitive.content)
        var validated = false
        assertFailsWith<CashImportProtocolError> {
            CashImportProtocol.jsonInput(ByteReadChannel(valid), valid.size - 1) { validated = true; CashImportProtocol.writeSetInput(it) }
        }
        assertFalse(validated)
    }
}
