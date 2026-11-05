package com.finance.tracker.backend

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.zip.ZipInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/** Reads workbook metadata and a bounded header prefix, without constructing business rows. */
internal object CashWorkbookHeaderProbe {
    private data class Cell(val shared: Int?, val text: String)
    private fun invalid(): Nothing = throw StatementDocumentFailure("statement_unreadable")

    fun sources(content: ByteArray, matching: (Collection<String>) -> Set<String>): Set<String> {
        var active = 0
        val sheets = mutableListOf<String>()
        xml(content, "xl/workbook.xml") { cursor ->
            while (cursor.next()) if (cursor.reader.eventType == XMLStreamConstants.START_ELEMENT) {
                when (cursor.reader.localName) {
                    "workbookView" -> cursor.attribute("activeTab")?.let { active = it.toIntOrNull() ?: invalid() }
                    "sheet" -> {
                        if (sheets.size >= 1000) throw StatementDocumentFailure("statement_rows_too_large")
                        sheets += cursor.attribute("id") ?: invalid()
                    }
                }
            }
        } ?: invalid()
        val selected = sheets.getOrNull(active) ?: invalid()
        val target = xml(content, "xl/_rels/workbook.xml.rels") { cursor ->
            var path: String? = null
            while (cursor.next()) if (cursor.reader.eventType == XMLStreamConstants.START_ELEMENT &&
                cursor.reader.localName == "Relationship" && cursor.attribute("Id") == selected) {
                if (cursor.attribute("TargetMode") == "External") invalid()
                val raw = cursor.attribute("Target") ?: invalid()
                val uri = try { URI("xl/workbook.xml").resolve(raw).normalize() }
                    catch (_: IllegalArgumentException) { invalid() }
                if (uri.isAbsolute || uri.rawQuery != null || uri.rawFragment != null) invalid()
                path = uri.path.removePrefix("/").takeIf { it.startsWith("xl/") && "/../" !in it } ?: invalid()
                break
            }
            path
        } ?: invalid()
        val rows = xml(content, target) { cursor ->
            val result = mutableListOf<List<Cell>>()
            var row = mutableListOf<Cell>()
            var type: String? = null
            var value = StringBuilder()
            var capture = false
            var phonetic = false
            while (cursor.next()) {
                when (cursor.reader.eventType) {
                    XMLStreamConstants.START_ELEMENT -> when (cursor.reader.localName) {
                        "row" -> row = mutableListOf()
                        "c" -> { type = cursor.attribute("t"); value = StringBuilder() }
                        "rPh" -> phonetic = true
                        "v", "t" -> capture = !phonetic
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> if (capture) cursor.append(value)
                    XMLStreamConstants.END_ELEMENT -> when (cursor.reader.localName) {
                        "v", "t" -> capture = false
                        "rPh" -> phonetic = false
                        "c" -> {
                            if (row.size >= 128) throw StatementDocumentFailure("statement_rows_too_large")
                            row += Cell(if (type == "s") value.toString().toIntOrNull() ?: invalid() else null, value.toString())
                        }
                        "row" -> {
                            result += row
                            if (result.size >= 50) break
                        }
                    }
                }
            }
            result
        } ?: invalid()
        fun completedSources(shared: Map<Int, String>): Set<String> {
            val result = mutableSetOf<String>()
            for (row in rows) {
                if (row.any { it.shared != null && it.shared !in shared }) continue
                result += matching(row.map { it.shared?.let(shared::getValue) ?: it.text })
            }
            return result
        }
        val needed = rows.flatten().mapNotNull { it.shared }.toSet()
        if (needed.isEmpty()) return completedSources(emptyMap())
        return xml(content, "xl/sharedStrings.xml") { cursor ->
            val strings = mutableMapOf<Int, String>()
            var index = -1
            var value = StringBuilder()
            var capture = false
            var phonetic = false
            while (cursor.next()) {
                when (cursor.reader.eventType) {
                    XMLStreamConstants.START_ELEMENT -> when (cursor.reader.localName) {
                        "si" -> { index++; value = StringBuilder() }
                        "rPh" -> phonetic = true
                        "t" -> capture = !phonetic && index in needed
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> if (capture) cursor.append(value)
                    XMLStreamConstants.END_ELEMENT -> when (cursor.reader.localName) {
                        "t" -> capture = false
                        "rPh" -> phonetic = false
                        "si" -> {
                            if (index in needed) strings[index] = value.toString()
                            if (strings.size == needed.size) break
                        }
                    }
                }
            }
            completedSources(strings)
        } ?: completedSources(emptyMap())
    }

    private class Cursor(val reader: XMLStreamReader) {
        private var depth = 0
        fun attribute(name: String): String? = (0 until reader.attributeCount).firstOrNull {
            reader.getAttributeLocalName(it) == name
        }?.let(reader::getAttributeValue)
        fun next(): Boolean {
            if (!reader.hasNext()) return false
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> { depth++; if (depth > 128) invalid() }
                XMLStreamConstants.END_ELEMENT -> depth--
                XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE -> invalid()
            }
            return true
        }
        fun append(value: StringBuilder) {
            val remaining = 512 - value.length
            if (remaining > 0) value.append(reader.textCharacters, reader.textStart, minOf(reader.textLength, remaining))
        }
    }

    private fun <T> xml(content: ByteArray, name: String, read: (Cursor) -> T): T? {
        try {
            ZipInputStream(ByteArrayInputStream(content)).use { zip ->
                var total = 0L
                var count = 0
                val names = mutableSetOf<String>()
                val input = object : InputStream() {
                    override fun read(): Int = zip.read().also { if (it >= 0) account(1) }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        if (length == 0) return 0
                        val bounded = minOf(length.toLong(), 100L * 1024 * 1024 - total + 1).toInt()
                        return zip.read(bytes, offset, bounded).also { if (it > 0) account(it) }
                    }
                    private fun account(size: Int) {
                        total += size
                        if (total > 100L * 1024 * 1024) throw StatementDocumentFailure("statement_rows_too_large")
                    }
                    override fun close() = Unit
                }
                val buffer = ByteArray(8192)
                while (true) {
                    val entry = zip.nextEntry ?: return null
                    count++
                    if (count > 1000 || !names.add(entry.name)) throw StatementDocumentFailure("statement_rows_too_large")
                    if (entry.isDirectory) {
                        if (input.read() != -1) invalid()
                    } else if (entry.name == name) {
                        val factory = XMLInputFactory.newFactory().apply {
                            setProperty(XMLInputFactory.SUPPORT_DTD, false)
                            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
                            xmlResolver = javax.xml.stream.XMLResolver { _, _, _, _ -> throw XMLStreamException("external_entities_disabled") }
                        }
                        val reader = factory.createXMLStreamReader(input)
                        try { return read(Cursor(reader)) } finally { reader.close() }
                    }
                    while (input.read(buffer) != -1) { /* Bound decompression while seeking the requested metadata. */ }
                    zip.closeEntry()
                }
            }
        } catch (_: IOException) { invalid() }
        catch (failure: XMLStreamException) {
            var cause: Throwable? = failure
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            while (cause != null && seen.add(cause)) {
                if (cause is StatementDocumentFailure) throw cause
                cause = cause.cause
            }
            invalid()
        }
    }
}
