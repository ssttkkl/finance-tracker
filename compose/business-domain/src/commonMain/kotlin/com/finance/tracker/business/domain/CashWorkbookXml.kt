package com.finance.tracker.business.domain

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlException
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.xmlStreaming

internal fun workbookUnreadable(): Nothing = throw StatementDocumentFailure("statement_unreadable")

/** XML byte decoding is declaration-driven; CSV's encoding fallback does not apply. */
internal fun decodeCashWorkbookXml(content: ByteArray): String {
    fun byte(i: Int) = content.getOrNull(i)?.toInt()?.and(255)
    if (listOf(byte(0), byte(1), byte(2), byte(3)) in listOf(
            listOf(255, 254, 0, 0), listOf(0, 0, 254, 255), listOf(60, 0, 0, 0), listOf(0, 0, 0, 60))) workbookUnreadable()
    val bom = when {
        byte(0) == 255 && byte(1) == 254 -> "UTF-16LE"
        byte(0) == 254 && byte(1) == 255 -> "UTF-16BE"
        byte(0) == 239 && byte(1) == 187 && byte(2) == 191 -> "UTF-8"
        else -> null
    }
    val signature = when {
        byte(0) == 60 && byte(1) == 0 && byte(2) == 63 && byte(3) == 0 -> "UTF-16LE"
        byte(0) == 0 && byte(1) == 60 && byte(2) == 0 && byte(3) == 63 -> "UTF-16BE"
        else -> null
    }
    val offset = when (bom) { "UTF-8" -> 3; null -> 0; else -> 2 }
    val inferred = bom ?: signature
    fun utf16(little: Boolean): String {
        if ((content.size - offset) % 2 != 0) workbookUnreadable()
        val text = StringBuilder((content.size - offset) / 2)
        var high = false
        var index = offset
        while (index < content.size) {
            val first = content[index++].toInt() and 255
            val second = content[index++].toInt() and 255
            val char = (if (little) first or (second shl 8) else (first shl 8) or second).toChar()
            if (high && !char.isLowSurrogate() || !high && char.isLowSurrogate()) workbookUnreadable()
            high = char.isHighSurrogate()
            text.append(char)
        }
        if (high) workbookUnreadable()
        return text.toString()
    }
    val prefix = if (inferred?.startsWith("UTF-16") == true) utf16(inferred == "UTF-16LE")
        else buildString { for (i in offset until minOf(content.size, offset + 1024)) append((content[i].toInt() and 255).toChar()) }
    val declaration = if (prefix.startsWith("<?xml")) prefix.substringBefore("?>") else ""
    val declared = Regex("\\bencoding\\s*=\\s*(['\"])([^'\"]+)\\1").find(declaration)?.groupValues?.get(2)?.uppercase()
    val encoding = declared ?: inferred ?: "UTF-8"
    if (inferred != null && encoding != inferred && !(encoding == "UTF-16" && inferred.startsWith("UTF-16"))) workbookUnreadable()
    if (encoding == "UTF-16" && inferred == null) workbookUnreadable()
    val bytes = if (offset == 0) content else content.copyOfRange(offset, content.size)
    return when (encoding) {
        "UTF-8", "UTF8" -> try { bytes.decodeToString(throwOnInvalidSequence = true) }
            catch (failure: CharacterCodingException) { throw StatementDocumentFailure("statement_unreadable").also { it.addSuppressed(failure) } }
        "UTF-16", "UTF-16LE", "UTF-16BE" -> if (inferred != null) prefix else utf16(encoding == "UTF-16LE")
        "GB18030" -> decodeGb18030Statement(bytes) ?: workbookUnreadable()
        "ISO-8859-1", "ISO_8859-1", "LATIN1" -> buildString(bytes.size) { bytes.forEach { append((it.toInt() and 255).toChar()) } }
        "WINDOWS-1252", "CP1252" -> buildString(bytes.size) {
            val middle = "€\u0000‚ƒ„…†‡ˆ‰Š‹Œ\u0000Ž\u0000\u0000‘’“”•–—˜™š›œ\u0000žŸ"
            for (value in bytes) {
                val code = value.toInt() and 255
                val char = if (code in 128..159) middle[code - 128] else code.toChar()
                if (char == '\u0000') workbookUnreadable()
                append(char)
            }
        }
        "US-ASCII", "ASCII" -> buildString(bytes.size) { for (value in bytes) {
            if (value < 0) workbookUnreadable()
            append(value.toInt().toChar())
        } }
        else -> workbookUnreadable()
    }
}

/** One generic parser on all platforms; never expand a DTD or resolve external entities. */
internal fun visitCashWorkbookXml(content: ByteArray, visit: (XmlReader, EventType) -> Unit) {
    val text = decodeCashWorkbookXml(content)
    if (text.isEmpty()) workbookUnreadable()
    val xml11 = Regex("^<\\?xml[ \\t\\r\\n]+version[ \\t\\r\\n]*=[ \\t\\r\\n]*(['\"])1\\.1\\1").containsMatchIn(text)
    validateWorkbookXmlCharacters(text, xml11, literal = true)
    val reader = try { xmlStreaming.newGenericReader(normalizeWorkbookXmlReferences(text, xml11), expandEntities = true) }
        catch (failure: XmlException) { throw StatementDocumentFailure("statement_unreadable").also { it.addSuppressed(failure) } }
    try {
        var depth = 0
        var roots = 0
        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.DOCDECL) workbookUnreadable()
            if (event == EventType.START_ELEMENT) {
                if (depth == 0 && ++roots > 1 || ++depth > 128) workbookUnreadable()
            }
            // DOCDECL is rejected before any body reference is read. Expansion here resolves
            // predefined/numeric references and rejects unknown names, including in attributes.
            if (event in setOf(EventType.TEXT, EventType.CDSECT, EventType.ENTITY_REF, EventType.IGNORABLE_WHITESPACE))
                validateWorkbookXmlCharacters(reader.text, xml11, literal = false)
            if (event == EventType.START_ELEMENT) for (attribute in 0 until reader.attributeCount)
                validateWorkbookXmlCharacters(reader.getAttributeValue(attribute), xml11, literal = false)
            visit(reader, event)
            if (event == EventType.END_ELEMENT) depth--
        }
        if (roots != 1 || depth != 0) workbookUnreadable()
    } catch (failure: XmlException) {
        throw StatementDocumentFailure("statement_unreadable").also { it.addSuppressed(failure) }
    } finally { reader.close() }
}

/** Check references before the parser can narrow an oversized value; protected XML text is literal. */
private fun normalizeWorkbookXmlReferences(text: String, xml11: Boolean): String = buildString(text.length) {
    var index = 0
    var inMarkup = false
    var quote: Char? = null
    while (index < text.length) {
        val char = text[index]
        if (quote != null && char == '<') workbookUnreadable()
        val terminator = if (inMarkup) null else when {
            text.startsWith("<![CDATA[", index) -> "]]>"
            text.startsWith("<!--", index) -> "-->"
            text.startsWith("<?", index) -> "?>"
            else -> null
        }
        if (terminator != null) {
            val end = text.indexOf(terminator, index + 2)
            val next = if (end < 0) text.length else end + terminator.length
            append(text.substring(index, next)); index = next
        } else if (text.startsWith("&#", index)) {
            index += 2
            val radix = if (text.getOrNull(index) == 'x') { index++; 16 } else 10
            val start = index
            var point = 0
            while (index < text.length && text[index] != ';') {
                val char = text[index++]
                val digit = when (char) {
                    in '0'..'9' -> char - '0'
                    in 'a'..'f' -> char - 'a' + 10
                    in 'A'..'F' -> char - 'A' + 10
                    else -> workbookUnreadable()
                }
                if (digit >= radix || point > (0x10ffff - digit) / radix) workbookUnreadable()
                point = point * radix + digit
            }
            if (index == start || text.getOrNull(index) != ';') workbookUnreadable()
            val value = buildString { appendPythonCodePoint(point) }
            validateWorkbookXmlCharacters(value, xml11, literal = false)
            // Canonical decimal avoids the generic parser's Int overflow on long leading zeroes.
            append("&#").append(point).append(';'); index++
        } else {
            when {
                quote != null -> if (char == quote) quote = null
                char == '<' -> { if (inMarkup) workbookUnreadable(); inMarkup = true }
                char == '>' -> inMarkup = false
                inMarkup && (char == '\'' || char == '"') -> quote = char
            }
            append(char); index++
        }
    }
}

/** Validate XML scalars before SpreadsheetML's separate UTF-16 _xHHHH_ string decoding. */
private fun validateWorkbookXmlCharacters(text: String, xml11: Boolean, literal: Boolean) {
    var index = 0
    while (index < text.length) {
        val first = text[index++]
        val point = when {
            first.isHighSurrogate() -> {
                val low = text.getOrNull(index++) ?: workbookUnreadable()
                if (!low.isLowSurrogate()) workbookUnreadable()
                0x10000 + ((first.code - 0xd800) shl 10) + low.code - 0xdc00
            }
            first.isLowSurrogate() -> workbookUnreadable()
            else -> first.code
        }
        if (point == 0 || point == 0xfffe || point == 0xffff) workbookUnreadable()
        if (!xml11 && point < 32 && point !in setOf(9, 10, 13)) workbookUnreadable()
        if (xml11 && literal && (point in 1..8 || point in 11..12 || point in 14..31 || point in 127..132 || point in 134..159)) workbookUnreadable()
    }
}

internal fun XmlReader.cashAttribute(name: String, namespace: String = ""): String? = (0 until attributeCount)
    .firstOrNull { getAttributeLocalName(it) == name && getAttributeNamespace(it) == namespace }?.let(::getAttributeValue)

internal fun validateCashWorkbookXml(content: ByteArray) {
    val guard = CashWorksheetXmlGuard()
    var root = true
    var worksheet = false
    visitCashWorkbookXml(content) { reader, event -> when (event) {
        EventType.START_ELEMENT -> {
            if (root) { worksheet = reader.localName == "worksheet"; root = false }
            if (worksheet) guard.start(reader.localName) { reader.cashAttribute(it) }
        }
        EventType.END_ELEMENT -> if (worksheet) guard.end()
        else -> Unit
    } }
}

internal fun validateCashWorksheetXml(content: ByteArray) {
    val guard = CashWorksheetXmlGuard()
    visitCashWorkbookXml(content) { reader, event -> when (event) {
        EventType.START_ELEMENT -> guard.start(reader.localName) { reader.cashAttribute(it) }
        EventType.END_ELEMENT -> guard.end()
        else -> Unit
    } }
}
