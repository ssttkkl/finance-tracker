package com.finance.tracker.business.domain

/**
 * Python 字符串语义的多平台原语。
 *
 * 后端既有实现依赖 JVM 专有类型（`java.security.MessageDigest`、`java.math.BigDecimal`、
 * `String.codePointCount`），无法直接移入共享 KMP 模块。这里提供等价的多平台实现，
 * 供共享规则与后端共同复用。
 */

/** Python `str.isspace()` 为真的码点集合，与后端 `PythonCharacterData.pythonWhitespace` 一致。 */
val pythonWhitespace: IntArray = intArrayOf(
    0x9, 0xa, 0xb, 0xc, 0xd, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0x85, 0xa0, 0x1680,
    0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009,
    0x200a, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000,
)

/** Python 语义的码点组成；代理对按一个码点计。 */
fun String.pythonCodePoints(): IntArray {
    val points = ArrayList<Int>(length)
    var index = 0
    while (index < length) {
        val high = this[index]
        if (high.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
            points.add(0x10000 + ((high.code - 0xD800) shl 10) + (this[index + 1].code - 0xDC00))
            index += 2
        } else {
            points.add(high.code)
            index++
        }
    }
    return points.toIntArray()
}

/** Python 语义的码点计数：代理对按一个码点计。 */
fun String.pythonCodePointCount(): Int = pythonCodePoints().size

/** Python 语义的字符串比较：按 Unicode 码点逐个比较。 */
fun pythonStringOrder(first: String, second: String): Int {
    var leftIndex = 0
    var rightIndex = 0
    while (leftIndex < first.length && rightIndex < second.length) {
        val left = first.pythonCodePointAt(leftIndex)
        val right = second.pythonCodePointAt(rightIndex)
        if (left != right) return left.compareTo(right)
        val width = if (left >= 0x10000) 2 else 1
        leftIndex += width
        rightIndex += width
    }
    return (first.length - leftIndex).compareTo(second.length - rightIndex)
}

private fun String.pythonCodePointAt(index: Int): Int {
    val high = this[index]
    if (!high.isHighSurrogate() || index + 1 == length) return high.code
    val low = this[index + 1]
    if (!low.isLowSurrogate()) return high.code
    return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
}

/** 与后端 `tokenDigest` 等价的十六进制 SHA-256。 */
fun sha256Hex(value: String): String = sha256Hex(value.encodeToByteArray())

/** 与后端 `tokenDigest` 等价的十六进制 SHA-256。 */
fun sha256Hex(bytes: ByteArray): String = sha256HexDigest(sha256(bytes))

private fun sha256HexDigest(digest: ByteArray): String {
    val digits = "0123456789abcdef"
    val hex = CharArray(digest.size * 2)
    for (index in digest.indices) {
        val value = digest[index].toInt() and 0xFF
        hex[index * 2] = digits[value ushr 4]
        hex[index * 2 + 1] = digits[value and 0x0F]
    }
    return hex.concatToString()
}

private val sha256InitialState = intArrayOf(
    0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
    0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
)

private val sha256RoundConstants = intArrayOf(
    0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
    0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
    0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
    0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
)

/** 多平台 SHA-256（FIPS 180-4）。 */
fun sha256(bytes: ByteArray): ByteArray {
    val digest = Sha256Blocks()
    val complete = bytes.size - bytes.size % 64
    for (offset in 0 until complete step 64) digest.compress(bytes, offset)
    return digest.finish(bytes, complete, bytes.size - complete, bytes.size.toLong())
}

internal fun sha256HexAscii(write: ((Int) -> Unit) -> Unit): String {
    val digest = Sha256Blocks()
    val block = ByteArray(64)
    var position = 0
    var complete = 0L
    write { character ->
        block[position++] = character.toByte()
        if (position == block.size) {
            digest.compress(block, 0)
            complete += block.size
            position = 0
        }
    }
    return sha256HexDigest(digest.finish(block, 0, position, complete + position))
}

private class Sha256Blocks {
    private val state = sha256InitialState.copyOf()
    private val schedule = IntArray(64)

    fun compress(message: ByteArray, offset: Int) {
        for (index in 0 until 16) {
            var word = 0
            for (byteIndex in 0 until 4) {
                word = (word shl 8) or (message[offset + index * 4 + byteIndex].toInt() and 0xFF)
            }
            schedule[index] = word
        }
        for (index in 16 until 64) {
            val previous15 = schedule[index - 15]
            val previous2 = schedule[index - 2]
            val small0 = previous15.rotateRight(7) xor previous15.rotateRight(18) xor (previous15 ushr 3)
            val small1 = previous2.rotateRight(17) xor previous2.rotateRight(19) xor (previous2 ushr 10)
            schedule[index] = schedule[index - 16] + small0 + schedule[index - 7] + small1
        }

        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        for (index in 0 until 64) {
            val big1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val choose = (e and f) xor (e.inv() and g)
            val temp1 = h + big1 + choose + sha256RoundConstants[index] + schedule[index]
            val big0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val temp2 = big0 + majority
            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }
        state[0] = state[0] + a
        state[1] = state[1] + b
        state[2] = state[2] + c
        state[3] = state[3] + d
        state[4] = state[4] + e
        state[5] = state[5] + f
        state[6] = state[6] + g
        state[7] = state[7] + h
    }

    fun finish(bytes: ByteArray, offset: Int, count: Int, messageBytes: Long): ByteArray {
        val padding = ByteArray(if (count < 56) 64 else 128)
        bytes.copyInto(padding, startIndex = offset, endIndex = offset + count)
        padding[count] = 0x80.toByte()
        val bitLength = messageBytes * 8
        for (index in 0 until 8) {
            padding[padding.lastIndex - index] = (bitLength ushr (8 * index)).toByte()
        }
        for (start in padding.indices step 64) compress(padding, start)
        val result = ByteArray(32)
        for (index in 0 until 8) {
            val word = state[index]
            for (byteIndex in 0 until 4) {
                result[index * 4 + byteIndex] = (word ushr (24 - 8 * byteIndex)).toByte()
            }
        }
        return result
    }
}
