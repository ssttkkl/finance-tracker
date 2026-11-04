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
    val left = first.pythonCodePoints()
    val right = second.pythonCodePoints()
    val shared = minOf(left.size, right.size)
    for (index in 0 until shared) {
        val comparison = left[index].compareTo(right[index])
        if (comparison != 0) return comparison
    }
    return left.size.compareTo(right.size)
}

/** 与后端 `tokenDigest` 等价的十六进制 SHA-256。 */
fun sha256Hex(value: String): String = sha256Hex(value.encodeToByteArray())

/** 与后端 `tokenDigest` 等价的十六进制 SHA-256。 */
fun sha256Hex(bytes: ByteArray): String {
    val digest = sha256(bytes)
    val digits = "0123456789abcdef"
    val hex = CharArray(digest.size * 2)
    for (index in digest.indices) {
        val value = digest[index].toInt() and 0xFF
        hex[index * 2] = digits[value ushr 4]
        hex[index * 2 + 1] = digits[value and 0x0F]
    }
    return hex.concatToString()
}

private const val MASK32 = 0xFFFFFFFFL

private val sha256InitialState = longArrayOf(
    0x6a09e667L, 0xbb67ae85L, 0x3c6ef372L, 0xa54ff53aL,
    0x510e527fL, 0x9b05688cL, 0x1f83d9abL, 0x5be0cd19L,
)

private val sha256RoundConstants = longArrayOf(
    0x428a2f98L, 0x71374491L, 0xb5c0fbcfL, 0xe9b5dba5L, 0x3956c25bL, 0x59f111f1L, 0x923f82a4L, 0xab1c5ed5L,
    0xd807aa98L, 0x12835b01L, 0x243185beL, 0x550c7dc3L, 0x72be5d74L, 0x80deb1feL, 0x9bdc06a7L, 0xc19bf174L,
    0xe49b69c1L, 0xefbe4786L, 0x0fc19dc6L, 0x240ca1ccL, 0x2de92c6fL, 0x4a7484aaL, 0x5cb0a9dcL, 0x76f988daL,
    0x983e5152L, 0xa831c66dL, 0xb00327c8L, 0xbf597fc7L, 0xc6e00bf3L, 0xd5a79147L, 0x06ca6351L, 0x14292967L,
    0x27b70a85L, 0x2e1b2138L, 0x4d2c6dfcL, 0x53380d13L, 0x650a7354L, 0x766a0abbL, 0x81c2c92eL, 0x92722c85L,
    0xa2bfe8a1L, 0xa81a664bL, 0xc24b8b70L, 0xc76c51a3L, 0xd192e819L, 0xd6990624L, 0xf40e3585L, 0x106aa070L,
    0x19a4c116L, 0x1e376c08L, 0x2748774cL, 0x34b0bcb5L, 0x391c0cb3L, 0x4ed8aa4aL, 0x5b9cca4fL, 0x682e6ff3L,
    0x748f82eeL, 0x78a5636fL, 0x84c87814L, 0x8cc70208L, 0x90befffaL, 0xa4506cebL, 0xbef9a3f7L, 0xc67178f2L,
)

private fun rotateRight(value: Long, bits: Int): Long =
    ((value ushr bits) or (value shl (32 - bits))) and MASK32

/** 多平台 SHA-256（FIPS 180-4）。 */
fun sha256(bytes: ByteArray): ByteArray {
    val bitLength = bytes.size.toLong() * 8
    val paddedLength = ((bytes.size + 8) / 64 + 1) * 64
    val message = ByteArray(paddedLength)
    bytes.copyInto(message)
    message[bytes.size] = 0x80.toByte()
    for (index in 0 until 8) {
        message[paddedLength - 1 - index] = ((bitLength ushr (8 * index)) and 0xFF).toByte()
    }

    val state = sha256InitialState.copyOf()
    val schedule = LongArray(64)
    for (chunk in 0 until paddedLength / 64) {
        val offset = chunk * 64
        for (index in 0 until 16) {
            var word = 0L
            for (byteIndex in 0 until 4) {
                word = (word shl 8) or (message[offset + index * 4 + byteIndex].toLong() and 0xFF)
            }
            schedule[index] = word
        }
        for (index in 16 until 64) {
            val previous15 = schedule[index - 15]
            val previous2 = schedule[index - 2]
            val small0 = rotateRight(previous15, 7) xor rotateRight(previous15, 18) xor (previous15 ushr 3)
            val small1 = rotateRight(previous2, 17) xor rotateRight(previous2, 19) xor (previous2 ushr 10)
            schedule[index] = (schedule[index - 16] + small0 + schedule[index - 7] + small1) and MASK32
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
            val big1 = rotateRight(e, 6) xor rotateRight(e, 11) xor rotateRight(e, 25)
            val choose = (e and f) xor (e.inv() and g)
            val temp1 = (h + big1 + choose + sha256RoundConstants[index] + schedule[index]) and MASK32
            val big0 = rotateRight(a, 2) xor rotateRight(a, 13) xor rotateRight(a, 22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val temp2 = (big0 + majority) and MASK32
            h = g
            g = f
            f = e
            e = (d + temp1) and MASK32
            d = c
            c = b
            b = a
            a = (temp1 + temp2) and MASK32
        }
        state[0] = (state[0] + a) and MASK32
        state[1] = (state[1] + b) and MASK32
        state[2] = (state[2] + c) and MASK32
        state[3] = (state[3] + d) and MASK32
        state[4] = (state[4] + e) and MASK32
        state[5] = (state[5] + f) and MASK32
        state[6] = (state[6] + g) and MASK32
        state[7] = (state[7] + h) and MASK32
    }

    val result = ByteArray(32)
    for (index in 0 until 8) {
        val word = state[index]
        for (byteIndex in 0 until 4) {
            result[index * 4 + byteIndex] = ((word ushr (24 - 8 * byteIndex)) and 0xFF).toByte()
        }
    }
    return result
}
