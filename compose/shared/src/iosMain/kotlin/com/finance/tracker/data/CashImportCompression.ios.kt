@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.finance.tracker.data

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSDataCompressionAlgorithmZlib
import platform.Foundation.NSError
import platform.Foundation.compressedDataUsingAlgorithm
import platform.Foundation.dataWithBytes
import kotlinx.cinterop.ObjCObjectVar
import platform.posix.memcpy

internal actual fun encodeCashImportRequest(bytes: ByteArray): CashImportRequestEncoding {
    if (bytes.isEmpty()) return CashImportRequestEncoding.Identity
    val source = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
    val zlib = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val compressed = source.compressedDataUsingAlgorithm(NSDataCompressionAlgorithmZlib, error.ptr) ?: return CashImportRequestEncoding.Identity
        val length = compressed.length.toInt()
        if (length <= 6) return CashImportRequestEncoding.Identity
        ByteArray(length).also { result ->
            result.usePinned { memcpy(it.addressOf(0), compressed.bytes, length.toULong()) }
        }
    }
    // Apple's COMPRESSION_ZLIB (NSDataCompressionAlgorithmZlib) emits raw DEFLATE, not an RFC1950
    // zlib wrapper. gzip shares that payload and adds its own header, CRC32 and size trailer.
    val rawLength = zlib.size
    if (rawLength == 0) return CashImportRequestEncoding.Identity
    val output = ByteArray(10 + rawLength + 8)
    output[0] = 0x1f; output[1] = 0x8b.toByte(); output[2] = 0x08; output[3] = 0
    output[4] = 0; output[5] = 0; output[6] = 0; output[7] = 0
    output[8] = 0; output[9] = 0xff.toByte()
    zlib.copyInto(output, 10, 0, zlib.size)
    val crc = crc32(bytes)
    val size = bytes.size
    for (index in 0 until 4) {
        output[10 + rawLength + index] = ((crc ushr (8 * index)) and 0xff).toByte()
        output[10 + rawLength + 4 + index] = ((size ushr (8 * index)) and 0xff).toByte()
    }
    return CashImportRequestEncoding.Native(output)
}

private val crcTable: IntArray by lazy {
    IntArray(256) { value ->
        var current = value
        repeat(8) { current = if (current and 1 != 0) (current ushr 1) xor 0xedb88320.toInt() else current ushr 1 }
        current
    }
}

private fun crc32(bytes: ByteArray): Int {
    var crc = -1
    for (byte in bytes) crc = (crc ushr 8) xor crcTable[(crc xor byte.toInt()) and 0xff]
    return crc.inv()
}
