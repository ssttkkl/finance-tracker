@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.finance.tracker

import com.finance.tracker.data.CashImportRequestEncoding
import com.finance.tracker.data.encodeCashImportRequest
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDataCompressionAlgorithmZlib
import platform.Foundation.dataWithBytes
import platform.Foundation.decompressedDataUsingAlgorithm
import platform.posix.memcpy
import kotlin.test.*

class CashImportCompressionIOSTest {
    @Test fun rawDeflateIsWrappedInAValidGzipFrame() {
        val original = ByteArray(200_000) { (it * 31 % 251).toByte() } + "账单原文".encodeToByteArray()
        val encoding = encodeCashImportRequest(original)
        val frame = assertIs<CashImportRequestEncoding.Native>(encoding).gzip
        assertEquals(0x1f, frame[0].toInt() and 0xff)
        assertEquals(0x8b, frame[1].toInt() and 0xff)
        assertEquals(0x08, frame[2].toInt() and 0xff)
        val raw = frame.copyOfRange(10, frame.size - 8)
        val compressed = raw.usePinned { NSData.dataWithBytes(it.addressOf(0), raw.size.toULong()) }
        val decoded = assertNotNull(compressed.decompressedDataUsingAlgorithm(NSDataCompressionAlgorithmZlib, null))
        assertContentEquals(original, decoded.bytesCopy())
        val crc = crc32(original)
        val trailer = frame.copyOfRange(frame.size - 8, frame.size)
        for (index in 0 until 4) {
            assertEquals(((crc ushr (8 * index)) and 0xff).toByte(), trailer[index])
            assertEquals(((original.size ushr (8 * index)) and 0xff).toByte(), trailer[4 + index])
        }
    }

    private fun NSData.bytesCopy(): ByteArray {
        val result = ByteArray(length.toInt())
        if (result.isNotEmpty()) result.usePinned { memcpy(it.addressOf(0), bytes, length) }
        return result
    }

    private val crcTable = IntArray(256) { value ->
        var current = value
        repeat(8) { current = if (current and 1 != 0) (current ushr 1) xor 0xedb88320.toInt() else current ushr 1 }
        current
    }

    private fun crc32(bytes: ByteArray): Int {
        var crc = -1
        for (byte in bytes) crc = (crc ushr 8) xor crcTable[(crc xor byte.toInt()) and 0xff]
        return crc.inv()
    }
}
