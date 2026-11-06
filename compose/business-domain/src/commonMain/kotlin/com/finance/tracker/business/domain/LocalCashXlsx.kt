package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashStatementDocument
import nl.adaptivity.xmlutil.EventType

private const val SHEET_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
private const val OFFICE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/"
private val WORKBOOK_ERROR_TEXT = setOf("(no error)", "#NULL!", "#DIV/0!", "#VALUE!", "#REF!", "#NAME?", "#NUM!", "#N/A",
    "~CIRCULAR~REF~", "~FUNCTION~NOT~IMPLEMENTED~")

/** Read a platform OOXML statement without uploading its bytes or using a JVM workbook library. */
fun readLocalCashXlsxDocument(content: ByteArray, source: String? = null): CashStatementDocument {
    if (source != null && !CashPlatformStatementTextReader.supportsSource(source)) throw StatementDocumentFailure("statement_source_unsupported")
    if (content.size > CashPlatformStatementTextReader.MAX_INPUT_BYTES) throw StatementDocumentFailure("statement_input_invalid")
    val parts = mutableMapOf<String, ByteArray>()
    scanCashWorkbookParts(content, MAX_WORKBOOK_INFLATED_BYTES, retain = { true }) { name, bytes ->
        validateCashWorkbookXml(bytes)
        parts[name] = bytes
    }
    if (source == null) workbookUnreadable()
    return LocalCashXlsx(parts).document(source)
}

fun parseLocalCashXlsxStatement(content: ByteArray, source: String? = null): ParsedCashDocument =
    normalizeLocalCashStatement(readLocalCashXlsxDocument(content, source?.replace('-', '_')))

private data class WorkbookLink(val id: String, val type: String, val target: String, val external: Boolean)
private data class WorkbookStyle(val formatId: Int, val format: String?)

private class LocalCashXlsx(private val parts: Map<String, ByteArray>) {
    private fun part(name: String) = parts[name] ?: workbookUnreadable()
    private fun relationships(name: String): List<WorkbookLink> {
        val result = mutableListOf<WorkbookLink>()
        visitCashWorkbookXml(part(name)) { reader, event ->
            if (event == EventType.START_ELEMENT && reader.localName == "Relationship" && reader.namespaceURI == REL_NS) {
                result += WorkbookLink(reader.cashAttribute("Id") ?: workbookUnreadable(), reader.cashAttribute("Type") ?: workbookUnreadable(),
                    reader.cashAttribute("Target") ?: workbookUnreadable(), reader.cashAttribute("TargetMode") == "External")
            }
        }
        return result
    }
    private fun target(owner: String, link: WorkbookLink): String {
        if (link.external) workbookUnreadable()
        val path = if (link.target.startsWith('/')) link.target.drop(1) else owner.substringBeforeLast('/', "")
            .let { if (it.isEmpty()) link.target else "$it/${link.target}" }
        val normalized = mutableListOf<String>()
        for (segment in path.split('/')) when (segment) {
            "", "." -> Unit
            ".." -> { if (normalized.isEmpty()) workbookUnreadable(); normalized.removeAt(normalized.lastIndex) }
            else -> normalized += segment
        }
        return normalized.joinToString("/")
    }
    fun document(source: String): CashStatementDocument {
        // OPC owns part locations; sheetId is not a worksheet filename or an array index.
        val contentTypes = contentTypes()
        val root = relationships("_rels/.rels").firstOrNull { it.type == OFFICE_REL + "officeDocument" } ?: workbookUnreadable()
        val workbook = target("", root)
        if (contentTypes[workbook] !in setOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.template.main+xml", "application/vnd.ms-excel.sheet.macroEnabled.main+xml",
                "application/vnd.ms-excel.template.macroEnabled.main+xml", "application/vnd.ms-excel.addin.macroEnabled.main+xml")) workbookUnreadable()
        var active = 0
        var firstView = true
        var use1904 = false
        val sheets = mutableListOf<String>()
        visitCashWorkbookXml(part(workbook)) { reader, event ->
            if (event == EventType.START_ELEMENT && reader.namespaceURI == SHEET_NS) when (reader.localName) {
                "workbookPr" -> use1904 = reader.cashAttribute("date1904") in setOf("true", "1")
                "workbookView" -> if (firstView) {
                    active = reader.cashAttribute("activeTab")?.let { it.toIntOrNull() ?: workbookUnreadable() } ?: 0
                    firstView = false
                }
                "sheet" -> sheets += reader.cashAttribute("id", OFFICE_REL.removeSuffix("/")) ?: workbookUnreadable()
            }
        }
        if (active !in sheets.indices) throw StatementDocumentFailure("statement_header_invalid")
        val relName = workbook.substringBeforeLast('/', "").let { if (it.isEmpty()) "_rels/" else "$it/_rels/" } + workbook.substringAfterLast('/') + ".rels"
        val links = relationships(relName)
        val selected = links.firstOrNull { it.id == sheets[active] && it.type == OFFICE_REL + "worksheet" } ?: workbookUnreadable()
        val styles = links.firstOrNull { it.type == OFFICE_REL + "styles" }?.let { styles(part(target(workbook, it))) }.orEmpty()
        val strings = links.firstOrNull { it.type == OFFICE_REL + "sharedStrings" }?.let { strings(part(target(workbook, it))) }.orEmpty()
        val bytes = part(target(workbook, selected))
        // Also guard worksheets outside conventional xl/worksheets paths before consuming cells.
        validateCashWorksheetXml(bytes)
        return CashPlatformStatementTextReader.collectRows(rows(bytes, strings, styles, use1904), source, "sheet:$active", padTrailing = true)
    }
    private fun contentTypes(): Map<String, String> {
        val defaults = mutableMapOf<String, String>()
        val overrides = mutableMapOf<String, String>()
        var root = false
        visitCashWorkbookXml(part("[Content_Types].xml")) { reader, event ->
            if (event == EventType.START_ELEMENT) {
                if (!root) {
                    if (reader.localName != "Types" || reader.namespaceURI != "http://schemas.openxmlformats.org/package/2006/content-types") workbookUnreadable()
                    root = true
                } else if (reader.namespaceURI == "http://schemas.openxmlformats.org/package/2006/content-types") when (reader.localName) {
                    "Default" -> defaults[reader.cashAttribute("Extension")?.lowercase() ?: workbookUnreadable()] = reader.cashAttribute("ContentType") ?: workbookUnreadable()
                    "Override" -> overrides[reader.cashAttribute("PartName")?.removePrefix("/") ?: workbookUnreadable()] = reader.cashAttribute("ContentType") ?: workbookUnreadable()
                }
            }
        }
        return parts.keys.filter { it != "[Content_Types].xml" }.associateWith { name ->
            overrides[name] ?: defaults[name.substringAfterLast('.', "").lowercase()] ?: workbookUnreadable()
        }
    }
    private fun styles(bytes: ByteArray): List<WorkbookStyle> {
        val formats = mutableMapOf<Int, String>()
        val ids = mutableListOf<Int>()
        val parents = mutableListOf<Pair<String, String>>()
        val formatParents = listOf(SHEET_NS to "styleSheet", SHEET_NS to "numFmts")
        val styleParents = listOf(SHEET_NS to "styleSheet", SHEET_NS to "cellXfs")
        visitCashWorkbookXml(bytes) { reader, event -> when (event) {
            EventType.START_ELEMENT -> {
                if (parents.isEmpty() && (reader.namespaceURI != SHEET_NS || reader.localName != "styleSheet")) workbookUnreadable()
                if (reader.namespaceURI == SHEET_NS) when (reader.localName) {
                    "numFmt" -> if (parents == formatParents) formats[reader.cashAttribute("numFmtId")?.toIntOrNull() ?: workbookUnreadable()] = reader.cashAttribute("formatCode") ?: workbookUnreadable()
                    "xf" -> if (parents == styleParents) ids += reader.cashAttribute("numFmtId")?.toIntOrNull() ?: 0
                }
                parents += reader.namespaceURI to reader.localName
            }
            EventType.END_ELEMENT -> parents.removeAt(parents.lastIndex)
            else -> Unit
        } }
        return ids.map { WorkbookStyle(it, formats[it]) }
    }
    private fun strings(bytes: ByteArray): List<String> {
        val values = mutableListOf<String>()
        var value: StringBuilder? = null
        var text = false
        var phonetic = 0
        visitCashWorkbookXml(bytes) { reader, event -> when (event) {
            EventType.START_ELEMENT -> if (reader.namespaceURI == SHEET_NS) when (reader.localName) {
                "si" -> value = StringBuilder()
                "rPh" -> phonetic++
                "t" -> text = phonetic == 0
            }
            EventType.TEXT, EventType.CDSECT, EventType.ENTITY_REF, EventType.IGNORABLE_WHITESPACE -> if (text) value?.append(reader.text)
            EventType.END_ELEMENT -> if (reader.namespaceURI == SHEET_NS) when (reader.localName) {
                "si" -> { values += decodeCashWorkbookString((value ?: workbookUnreadable()).toString()); value = null }
                "rPh" -> phonetic--
                "t" -> text = false
            }
            else -> Unit
        } }
        return values
    }
    private fun rows(bytes: ByteArray, strings: List<String>, styles: List<WorkbookStyle>, use1904: Boolean): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var rowIndex = 0
        var firstRow = 0
        var current: MutableList<String>? = null
        var column = -1
        var type: String? = null
        var style = 0
        var value = StringBuilder()
        var hasValue = false
        var inline = StringBuilder()
        var capture: String? = null
        var phonetic = 0
        visitCashWorkbookXml(bytes) { reader, event -> when (event) {
            EventType.START_ELEMENT -> if (reader.namespaceURI == SHEET_NS) when (reader.localName) {
                "row" -> {
                    rowIndex = reader.cashAttribute("r")?.toIntOrNull() ?: (rowIndex + 1)
                    if (firstRow == 0) firstRow = rowIndex
                    while (rows.size < rowIndex - firstRow) rows += emptyList<String>()
                    current = mutableListOf(); column = -1
                }
                "c" -> {
                    val reference = reader.cashAttribute("r")
                    column = if (reference == null) column + 1 else reference.takeWhile { it in 'A'..'Z' }.fold(0) { index, c -> index * 26 + (c - 'A') + 1 } - 1
                    if (current == null || column !in 0..127) workbookUnreadable()
                    type = reader.cashAttribute("t"); style = reader.cashAttribute("s")?.toIntOrNull() ?: 0
                    value = StringBuilder(); hasValue = false; inline = StringBuilder()
                }
                "v" -> { hasValue = true; capture = "v" }
                "f" -> workbookUnreadable()
                "rPh" -> phonetic++
                "t" -> if (phonetic == 0) capture = "t"
            }
            EventType.TEXT, EventType.CDSECT, EventType.ENTITY_REF, EventType.IGNORABLE_WHITESPACE -> when (capture) {
                "v" -> value.append(reader.text)
                "t" -> inline.append(reader.text)
            }
            EventType.END_ELEMENT -> if (reader.namespaceURI == SHEET_NS) when (reader.localName) {
                "v", "t" -> capture = null
                "rPh" -> phonetic--
                "c" -> {
                    val cells = current ?: workbookUnreadable()
                    while (cells.size <= column) cells += ""
                    cells[column] = when {
                        type == "inlineStr" -> decodeCashWorkbookString(inline.toString())
                        type == "s" -> value.toString().toIntOrNull()?.let(strings::getOrNull) ?: ""
                        type == "str" -> decodeCashWorkbookString(value.toString())
                        type == "b" -> if (value.toString() == "1") "TRUE" else "FALSE"
                        type == "e" -> if (!hasValue) "#NULL!" else value.toString().takeIf { it in WORKBOOK_ERROR_TEXT } ?: workbookUnreadable()
                        !hasValue -> ""
                        type == null || type == "n" -> {
                            val number = if (value.isEmpty()) 0.0 else value.toString().toDoubleOrNull() ?: workbookUnreadable()
                            val format = styles.getOrNull(style)
                            if (number >= 0 && format != null && cashWorkbookIsDateFormat(format.formatId, format.format)) cashPlatformWorkbookDateText(number, use1904)
                            else cashPlatformWorkbookNumberText(number)
                        }
                        else -> workbookUnreadable()
                    }
                }
                "row" -> { rows += (current ?: workbookUnreadable()); current = null }
            }
            else -> Unit
        } }
        return rows.map { it.toList() }
    }
}

/** SpreadsheetML's UTF-16 escapes are decoded once, including the escaped underscore form. */
internal fun decodeCashWorkbookString(value: String): String = buildString(value.length) {
    var index = 0
    while (index < value.length) {
        val code = if (value.startsWith("_x", index) && value.getOrNull(index + 6) == '_')
            value.substring(index + 2, index + 6).takeIf { it.all { char -> char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F' } }?.toInt(16)
            else null
        if (code == null) append(value[index++]) else { append(code.toChar()); index += 7 }
    }
}
