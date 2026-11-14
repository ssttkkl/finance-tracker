package com.finance.tracker.backend

import io.ktor.utils.io.ByteReadChannel
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class CashWriteSetJsonInputTest {
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
