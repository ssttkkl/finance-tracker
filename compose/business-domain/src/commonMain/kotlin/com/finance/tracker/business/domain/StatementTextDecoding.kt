package com.finance.tracker.business.domain

/** The existing strict UTF-8, then GB18030 fallback. File size/format guards belong to the reader. */
fun decodeCashStatementText(content: ByteArray): String? {
    val text = try {
        content.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        decodeGb18030Statement(content) ?: return null
    }
    return if (text.startsWith('\ufeff')) text.substring(1) else text
}

/** GB18030 mapping is frozen from the previous JVM decoder, independent of browser/OS charsets. */
internal fun decodeGb18030Statement(content: ByteArray): String? {
    val text = StringBuilder(content.size)
    var index = 0
    while (index < content.size) {
        val first = content[index++].toInt() and 0xff
        if (first < 0x80) {
            text.append(first.toChar())
            continue
        }
        if (first !in 0x81..0xfe || index >= content.size) return null
        val second = content[index++].toInt() and 0xff
        val codePoint = when {
            second in 0x30..0x39 -> {
                if (content.size - index < 2) return null
                val third = content[index++].toInt() and 0xff
                val fourth = content[index++].toInt() and 0xff
                if (third !in 0x81..0xfe || fourth !in 0x30..0x39) return null
                val pointer = (((first - 0x81) * 10 + second - 0x30) * 126 + third - 0x81) * 10 + fourth - 0x30
                StatementGb18030Data.fourByte(pointer)
            }
            second in 0x40..0xfe && second != 0x7f -> StatementGb18030Data.twoByte(first, second)
            else -> return null
        }
        if (codePoint < 0) return null
        text.appendPythonCodePoint(codePoint)
    }
    return text.toString()
}

/** Java UTF_16 replacement behavior: malformed high-surrogate pairs consume both input units. */
internal fun decodeBankStatementUtf16(content: ByteArray): String {
    val littleEndian = content.size >= 2 && content[0] == 0xff.toByte() && content[1] == 0xfe.toByte()
    val hasBom = littleEndian || content.size >= 2 && content[0] == 0xfe.toByte() && content[1] == 0xff.toByte()
    var index = if (hasBom) 2 else 0
    fun unit(): Char {
        val first = content[index++].toInt() and 0xff
        val second = content[index++].toInt() and 0xff
        return (if (littleEndian) first or (second shl 8) else (first shl 8) or second).toChar()
    }
    val text = StringBuilder(content.size / 2)
    while (index < content.size) {
        if (content.size - index < 2) { text.append('\ufffd'); break }
        val first = unit()
        when {
            first.isHighSurrogate() -> {
                if (content.size - index < 2) { text.append('\ufffd'); break }
                val second = unit()
                if (second.isLowSurrogate()) text.append(first).append(second) else text.append('\ufffd')
            }
            first.isLowSurrogate() -> text.append('\ufffd')
            else -> text.append(first)
        }
    }
    return text.toString()
}
