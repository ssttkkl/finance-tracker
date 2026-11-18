package com.finance.tracker.business.domain

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.test.Test
import kotlin.test.assertEquals

class StatementTextCharsetParityTest {
    @Test fun everyGb18030SingleTwoAndFourByteSlotMatchesOriginalJvmDecoder() {
        val decoder = Charset.forName("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.allocate(4)
        val output = CharBuffer.allocate(2)
        fun compare(bytes: ByteArray) {
            decoder.reset(); input.clear(); input.put(bytes).flip(); output.clear()
            val result = decoder.decode(input, output, true)
            val expected = if (result.isError || result.isOverflow || input.hasRemaining()) null else {
                val flush = decoder.flush(output)
                if (flush.isError || flush.isOverflow) null else { output.flip(); output.toString() }
            }
            val actual = decodeGb18030Statement(bytes)
            if (expected != actual) assertEquals(expected, actual, bytes.joinToString { "%02x".format(it) })
        }
        for (first in 0..255) compare(byteArrayOf(first.toByte()))
        for (first in 0x81..0xfe) for (second in 0..255) compare(byteArrayOf(first.toByte(), second.toByte()))
        for (pointer in 0 until 126 * 10 * 126 * 10) {
            var value = pointer
            val fourth = value % 10 + 0x30; value /= 10
            val third = value % 126 + 0x81; value /= 126
            compare(byteArrayOf((value / 10 + 0x81).toByte(), (value % 10 + 0x30).toByte(), third.toByte(), fourth.toByte()))
        }
    }
}
