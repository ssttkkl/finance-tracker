package com.finance.tracker.backend

import java.awt.geom.Point2D
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition

/** 工行信用卡/借记卡 PDF 的一次读取；只保留原始表格行和已核对的声明。 */
internal object IcbcPdfDocumentReader {
    private val CREDIT_HEADERS = listOf("入账日期", "交易卡号", "收支", "交易币种", "交易金额", "入账币种", "入账金额",
        "账户余额", "对方户名", "对方账号", "摘要", "交易场所")
    private val DEBIT_HEADERS = listOf("交易日期", "账号", "储种", "序号", "币种", "钞汇", "摘要", "地区",
        "收入/支出金额", "余额", "对方户名", "对方账号", "渠道")
    private val CREDIT_KEYS = CREDIT_HEADERS.map(::compact)
    private val DEBIT_KEYS = DEBIT_HEADERS.map(::compact)
    private const val TOLERANCE = 3.0
    private val STARTXREF = Regex("startxref\\s+[0-9]+\\s*%%EOF\\s*$")
    private val STARTXREF_POINTER = Regex("(?<![A-Za-z])startxref\\s+[0-9]+")
    private val FILTERS = setOf("全部", "全部交易", "全部类型", "所有")
    private val sourceTimezones = mapOf("icbc_credit" to "Asia/Shanghai", "icbc_debit" to "Asia/Shanghai")

    fun readIcbcDocument(content: ByteArray, source: String? = null, password: String? = null,
        limits: IcbcPdfLimits = IcbcPdfLimits()): CashStatementDocument {
        if (content.isEmpty() || content.size > limits.maxInputBytes || !content.startsWithPdf()) fail("statement_input_invalid")
        if (source != null && source !in setOf("icbc", "icbc_credit", "icbc_debit")) fail("statement_source_unsupported")
        if (!STARTXREF_POINTER.containsMatchIn(tail(content))) fail("statement_unreadable")
        val document = try {
            if (password == null) Loader.loadPDF(content) else Loader.loadPDF(content, password)
        } catch (_: InvalidPasswordException) {
            throw StatementPdfPasswordFailure(required = password == null)
        } catch (_: Exception) {
            fail("statement_unreadable")
        }
        document.use {
            try {
                return read(document, source, content, limits)
            } catch (failure: StatementDocumentFailure) {
                throw failure
            } catch (_: Exception) {
                fail("statement_unreadable")
            }
        }
    }

    private fun read(document: PDDocument, source: String?, content: ByteArray,
        limits: IcbcPdfLimits): CashStatementDocument {
        if (document.numberOfPages > limits.maxPdfPages) fail("statement_rows_too_large")
        val rows = mutableListOf<RawCashRow>()
        val texts = mutableListOf<String>()
        val pageCounts = mutableListOf<Int>()
        val fingerprints = mutableListOf<String>()
        var detected: String? = null
        var physical = 0
        var parsedBytes = 0L
        var complete = true
        fun processPage(index: Int, page: PDPage, positions: List<TextPosition>) {
            val pageNumber = index + 1
            if (pageNumber > limits.maxPdfPages) fail("statement_rows_too_large")
            val all = positions.map(PdfChar::from)
            if (!compact(all.joinToString("") { it.text }).contains("中国工商银行")) fail("statement_source_unsupported")
            val filtered = all.filter { it.size < 15 }
            val text = textOf(filtered)
            parsedBytes += text.toByteArray(StandardCharsets.UTF_8).size
            if (parsedBytes > limits.maxParsedBytes) fail("statement_rows_too_large")
            texts += text
            val declaredPage = one(findAllGroups("共[0-9]{1,6}页第([0-9]{1,6})页", compact(text))
                .mapNotNull { it.first().toIntOrNull() })
            val tables = extractTables(filtered, page)
            if (tables.isEmpty()) fail("statement_header_not_found")
            if (tables.size > limits.maxTablesPerPage) fail("statement_rows_too_large")
            val before = rows.size
            fingerprints += digest(tables)
            for ((tableIndex, table) in tables.withIndex()) {
                if (table.isEmpty() || table[0].isEmpty() || table[0].size > limits.maxColumns) fail("statement_header_invalid")
                val header = table[0].map { it ?: "" }
                val keys = header.map(::compact)
                val kind = when (keys) {
                    CREDIT_KEYS -> "icbc_credit"
                    DEBIT_KEYS -> "icbc_debit"
                    else -> null
                } ?: fail("statement_header_invalid")
                if (detected?.let { it != kind } == true || source != null && source != "icbc" && source != kind)
                    fail("statement_source_unsupported")
                detected = kind
                physical++
                for ((rowIndex, rawValues) in table.drop(1).withIndex()) {
                    val values = rawValues.map { it ?: "" }
                    physical++
                    if (physical > limits.maxPhysicalRows) fail("statement_rows_too_large")
                    if (values.all { it.isBlank() }) continue
                    if (rows.size >= limits.maxRows) fail("statement_rows_too_large")
                    val payload = if (values.size == header.size) LinkedHashMap<String, String>().apply {
                        header.zip(values).forEach { put(it.first, it.second) }
                    } else null
                    parsedBytes += values.sumOf { it.toByteArray(StandardCharsets.UTF_8).size.toLong() }
                    if (parsedBytes > limits.maxParsedBytes) fail("statement_rows_too_large")
                    val location = declaredPage?.takeIf { it in 1..limits.maxPdfPages }?.let { "p$it:t$tableIndex:r${rowIndex + 1}" }
                    rows += RawCashRow(physical, payload, if (payload == null) "row_shape_invalid" else null, location)
                    complete = complete && payload != null
                }
            }
            pageCounts += rows.size - before
        }
        CharacterCollector(limits.maxPdfChars, ::processPage).getText(document)
        val kind = detected ?: fail("statement_header_not_found")
        val owner = if (kind == "icbc_debit") one(texts.map { headerOwner(it, kind) }) else null
        complete = complete && STARTXREF.containsMatchIn(tail(content))
        val declared = coverage(kind, texts, pageCounts, fingerprints, rows.size, complete, limits)
        return CashStatementDocument(kind, "pdf:0", "icbc-pdf-tables-v1", rows, owner, owner,
            declared.coverage, declared.declared, rows.size)
    }

    private data class Declared(val declared: Long?, val coverage: CashSourceCoverage)

    internal data class IcbcPdfLimits(
        val maxInputBytes: Int = 100 * 1024 * 1024,
        val maxParsedBytes: Long = 25L * 1024 * 1024,
        val maxRows: Int = 100_000,
        val maxPhysicalRows: Int = 200_000,
        val maxColumns: Int = 128,
        val maxPdfPages: Int = 2_000,
        val maxPdfChars: Long = 5_000_000,
        val maxTablesPerPage: Int = 16,
    )

    private fun coverage(kind: String, texts: List<String>, pageCounts: List<Int>, fingerprints: List<String>,
        rowCount: Int, complete: Boolean, limits: IcbcPdfLimits): Declared {
        val ranges = mutableListOf<List<String>?>()
        val counts = mutableListOf<Long?>()
        val totals = mutableListOf<Int?>()
        val orders = mutableListOf<Int?>()
        val filters = mutableListOf<String>()
        val owners = mutableListOf<String?>()
        val stamps = mutableListOf<String?>()
        val date = "(?:[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{8})"
        for (text in texts) {
            val compacted = compact(text)
            owners += headerOwner(text, kind)
            ranges += one(findAllGroups("起止(?:交易)?日期[:：]?($date)[至—–~～-]+($date)", compacted))
            counts += one(findAllGroups("本页交易笔数[:：]([0-9][0-9,]{0,11})", compacted)
                .mapNotNull { it.first().replace(",", "").toLongOrNull() })
            val pages = findAllGroups("共([0-9]{1,6})页第([0-9]{1,6})页", compacted)
            totals += one(pages.mapNotNull { it.first().toIntOrNull() })
            orders += one(pages.mapNotNull { it.getOrNull(1)?.toIntOrNull() })
            filters += findAllGroups("(?:交易类型|收支类型|交易筛选)[:：]([^，,;；\\n]+)", compacted).map { it.first() }
            stamps += one(findAllGroups("下单时间[:：]?([0-9]{4}-[0-9]{2}-[0-9]{2}[0-9]{2}:[0-9]{2}:[0-9]{2})(?![0-9])", compacted)
                .map { it.first() }.map { it.takeIf(::validStamp) })
        }
        val interval = one(ranges)
        var start: LocalDate? = null
        var end: LocalDate? = null
        if (interval != null && interval.size == 2) {
            try {
                start = parseDate(interval[0])
                end = parseDate(interval[1])?.plusDays(1)
            } catch (_: Exception) {
                start = null; end = null
            }
        }
        val declared = if (counts.isNotEmpty() && counts.all { it != null }) counts.sumOf { it ?: 0L } else null
        val verified = complete && start != null && end != null && start.isBefore(end) &&
            counts == pageCounts.map { it.toLong() } && declared == rowCount.toLong() && filters.all { it in FILTERS }
        val coverageComplete = verified && orders == texts.indices.map { it + 1 } && totals.all { it == texts.size }
        val total = one(totals)
        val stamp = one(stamps)
        val owner = one(owners)
        val key = if (verified && owner != null && stamp != null && total != null && total in 1..limits.maxPdfPages &&
            orders.isNotEmpty() && orders.all { it != null && it in 1..total } &&
            orders == (orders.first()!! until orders.first()!! + orders.size).toList())
            digest("icbc-export-v1", kind, owner, interval, stamp, total, "all") else null
        val pageFingerprints = if (key != null) orders.mapIndexed { index, order ->
            order.toString() to fingerprints[index]
        }.toMap() else emptyMap()
        val startText = start?.let { normalizeTimestamp(it.atStartOfDay(), kind) }
        val endText = end?.let { normalizeTimestamp(it.atStartOfDay(), kind) }
        return Declared(declared, CashSourceCoverage(startText, endText, coverageComplete,
            if (coverageComplete) "icbc-pdf-range-pages-count-v1" else "", mapOf("export_type" to "all"),
            "icbc-pdf-coverage-v1", startInclusive = true, endInclusive = false, documentKey = key,
            pageTotal = if (key != null) total else null, pageFingerprints = pageFingerprints))
    }

    private fun headerOwner(text: String, source: String): String? {
        val label = if (source == "icbc_credit") "入\\s*账\\s*日\\s*期" else "交\\s*易\\s*日\\s*期"
        val boundary = Regex(label).find(text) ?: return null
        return try {
            fullCard(extractIcbcDebitCardIdentifier(text.substring(0, boundary.range.first).lines()))
        } catch (_: Exception) {
            null
        }
    }

    private fun extractIcbcDebitCardIdentifier(lines: List<String>): String {
        val identifiers = mutableSetOf<String>()
        for ((index, line) in lines.withIndex()) {
            val label = Regex("(?<!交易)卡号").find(line) ?: continue
            val window = (listOf(line) + lines.drop(index + 1).take(3)).joinToString("\n")
            val segment = Regex("户名|姓名|起止日期|交易日期").split(window.substring(label.range.last + 1), 2).first()
            val compacted = segment.replace(Regex("[\\s:：]"), "")
            for (match in Regex("(?<!\\d)(?:\\d{12,19}|\\d{4,6}[*＊]{2,}\\d{4})(?!\\d)").findAll(compacted)) {
                normalizeIcbcAccountIdentifier(match.value).takeIf { it.isNotEmpty() }?.let(identifiers::add)
            }
        }
        if (identifiers.isEmpty()) throw IllegalArgumentException("missing")
        if (identifiers.size != 1) throw IllegalArgumentException("ambiguous")
        return identifiers.first()
    }

    private fun normalizeIcbcAccountIdentifier(value: String): String {
        val text = value.replace(Regex("[\\s\\-－—()（）]"), "")
        return when {
            Regex("\\d{12,19}").matches(text) -> text
            Regex("\\d{4,6}[*＊]{2,}\\d{4}").matches(text) -> text.replace('＊', '*')
            else -> ""
        }
    }

    private fun fullCard(value: String): String? = value.takeIf { Regex("[0-9]{12,19}").matches(it) }

    private fun extractTables(chars: List<PdfChar>, page: PDPage): List<List<List<String?>>> {
        val height = page.cropBox.height.toDouble()
        val collector = LineCollector(page)
        collector.processPage(page)
        val horizontal = mutableListOf<Segment>()
        val vertical = mutableListOf<Segment>()
        for (segment in collector.segments) {
            val x1 = minOf(segment.x1, segment.x2); val x2 = maxOf(segment.x1, segment.x2)
            val y1 = height - maxOf(segment.y1, segment.y2); val y2 = height - minOf(segment.y1, segment.y2)
            when {
                segment.y1 == segment.y2 && x2 - x1 > TOLERANCE ->
                    horizontal += Segment(x1, y1, x2, y1)
                segment.x1 == segment.x2 && y2 - y1 > TOLERANCE ->
                    vertical += Segment(x1, y1, x1, y2)
            }
        }
        val xSnap = snapValues(vertical.map { it.x1 } + horizontal.flatMap { listOf(it.x1, it.x2) })
        val ySnap = snapValues(horizontal.map { it.y1 } + vertical.flatMap { listOf(it.y1, it.y2) })
        fun snappedX(value: Double) = xSnap[value] ?: value
        fun snappedY(value: Double) = ySnap[value] ?: value
        val hs = join(horizontal.map { it.copy(x1 = snappedX(it.x1), x2 = snappedX(it.x2),
            y1 = snappedY(it.y1)).let { s -> s.copy(y2 = s.y1) } }, vertical = false)
        val vs = join(vertical.map { it.copy(x1 = snappedX(it.x1), x2 = snappedX(it.x1),
            y1 = snappedY(it.y1), y2 = snappedY(it.y2)) }, vertical = true)
        val hEdges = hs.map { Edge('h', it.x1, it.y1, it.x2, it.y1) }
        val vEdges = vs.map { Edge('v', it.x1, it.y1, it.x1, it.y2) }
        val intersections = linkedMapOf<Point, Edges>()
        for (vertical in vEdges.sortedWith(compareBy({ it.x0 }, { it.top }))) {
            for (horizontal in hEdges.sortedWith(compareBy({ it.top }, { it.x0 }))) {
                if (vertical.top <= horizontal.top + TOLERANCE && vertical.bottom >= horizontal.top - TOLERANCE &&
                    vertical.x0 >= horizontal.x0 - TOLERANCE && vertical.x0 <= horizontal.x1 + TOLERANCE) {
                    val point = Point(vertical.x0, horizontal.top)
                    val edges = intersections.getOrPut(point) { Edges() }
                    edges.vertical += vertical
                    edges.horizontal += horizontal
                }
            }
        }
        val points = intersections.keys.sortedWith(compareBy({ it.x }, { it.y }))
        fun edgeConnects(first: Point, second: Point): Boolean {
            if (first.x == second.x &&
                intersections[first]?.vertical.orEmpty().any { it in intersections[second]?.vertical.orEmpty() })
                return true
            if (first.y == second.y &&
                intersections[first]?.horizontal.orEmpty().any { it in intersections[second]?.horizontal.orEmpty() })
                return true
            return false
        }
        fun smallestCell(index: Int): BBox? {
            if (index == points.size - 1) return null
            val point = points[index]
            for (belowIndex in index + 1 until points.size) {
                val belowPoint = points[belowIndex]
                if (belowPoint.x != point.x || !edgeConnects(point, belowPoint)) continue
                for (rightIndex in index + 1 until points.size) {
                    val rightPoint = points[rightIndex]
                    if (rightPoint.y != point.y || !edgeConnects(point, rightPoint)) continue
                    val bottomRight = Point(rightPoint.x, belowPoint.y)
                    if (intersections.containsKey(bottomRight) && edgeConnects(bottomRight, rightPoint) &&
                        edgeConnects(bottomRight, belowPoint))
                        return BBox(point.x, point.y, bottomRight.x, bottomRight.y)
                }
            }
            return null
        }
        val cells = points.indices.mapNotNull(::smallestCell)
        if (cells.isEmpty()) return emptyList()
        val tables = mutableListOf<List<BBox>>()
        val remainingCells = cells.toMutableList()
        var currentCorners = mutableSetOf<Point>()
        val currentCells = mutableListOf<BBox>()
        while (remainingCells.isNotEmpty()) {
            val initial = currentCells.size
            for (cell in remainingCells.toList()) {
                val corners = listOf(Point(cell.x0, cell.top), Point(cell.x0, cell.bottom),
                    Point(cell.x1, cell.top), Point(cell.x1, cell.bottom))
                if (currentCells.isEmpty() || corners.any { it in currentCorners }) {
                    currentCorners += corners
                    currentCells += cell
                    remainingCells.remove(cell)
                }
            }
            if (currentCells.size == initial) {
                tables += currentCells.toList()
                currentCorners = mutableSetOf()
                currentCells.clear()
            }
        }
        if (currentCells.isNotEmpty()) tables += currentCells.toList()
        fun tableKey(table: List<BBox>): BBox = table.minWith(compareBy({ it.top }, { it.x0 }))
        return tables.map { it to tableKey(it) }
            .sortedWith(compareBy({ it.second.top }, { it.second.x0 }))
            .map { it.first }
            .filter { it.size > 1 }
            .map { table ->
                val xs = table.map { it.x0 }.distinct().sorted()
                val sortedCells = table.sortedWith(compareBy({ it.top }, { it.x0 }))
                val rows = sortedCells.groupBy { it.top }.values.map { rowCells ->
                    val byX = rowCells.associateBy { it.x0 }
                    val row = xs.map { byX[it] }
                    val present = row.filterNotNull()
                    val rowBox = BBox(present.minOf { it.x0 }, present.minOf { it.top },
                        present.maxOf { it.x1 }, present.maxOf { it.bottom })
                    val rowChars = chars.filter { char ->
                        val cx = char.x + char.width / 2; val cy = char.y - char.height / 2
                        cx >= rowBox.x0 && cx < rowBox.x1 && cy >= rowBox.top && cy < rowBox.bottom
                    }
                    row.map { cell ->
                        if (cell == null) null else textOf(rowChars.filter { char ->
                            val cx = char.x + char.width / 2; val cy = char.y - char.height / 2
                            cx >= cell.x0 && cx < cell.x1 && cy >= cell.top && cy < cell.bottom
                        })
                    }.toMutableList()
                }
                rows
            }
    }

    private fun textOf(chars: List<PdfChar>): String {
        if (chars.isEmpty()) return ""
        val sorted = chars.sortedWith(compareBy({ it.y }, { it.x }))
        val lines = mutableListOf<MutableList<PdfChar>>()
        for (char in sorted) {
            val line = lines.lastOrNull()
            if (line == null || kotlin.math.abs(char.y - line.first().y) > TOLERANCE) lines += mutableListOf(char) else line += char
        }
        return lines.joinToString("\n") { line ->
            line.sortBy { it.x }
            val text = StringBuilder()
            var end: Double? = null
            for (char in line) {
                if (end != null && char.x - end > TOLERANCE) text.append(' ')
                text.append(char.text)
                end = char.x + char.width
            }
            text.toString().trim()
        }
    }

    /** 与 pdfplumber `snap_objects` 一致：相邻容差内的坐标合并到其平均值。 */
    private fun snapValues(values: List<Double>): Map<Double, Double> {
        val groups = mutableListOf<MutableList<Double>>()
        for (value in values.sorted()) {
            if (groups.isEmpty() || value - groups.last().last() > TOLERANCE) groups += mutableListOf(value)
            else groups.last() += value
        }
        return groups.flatMap { group -> group.map { it to group.average() } }.toMap()
    }

    private fun join(segments: List<Segment>, vertical: Boolean): List<Segment> {
        val result = mutableListOf<Segment>()
        for (group in segments.groupBy { if (vertical) it.x1 else it.y1 }.values) {
            val sorted = group.sortedBy { if (vertical) it.y1 else it.x1 }
            var current = sorted.first()
            for (next in sorted.drop(1)) {
                val gap = if (vertical) next.y1 - current.y2 else next.x1 - current.x2
                current = if (gap <= TOLERANCE) {
                    if (vertical) current.copy(y2 = maxOf(current.y2, next.y2)) else current.copy(x2 = maxOf(current.x2, next.x2))
                } else {
                    result += current; next
                }
            }
            result += current
        }
        return result
    }

    private fun digest(vararg values: Any?): String {
        val bytes = toJson(values.toList()).toString().toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map(::toJson))
        is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to toJson(item) })
        else -> JsonPrimitive(value.toString())
    }

    private fun parseDate(value: String): LocalDate? = try {
        if (value.matches(Regex("[0-9]{8}"))) LocalDate.parse(value, DateTimeFormatter.ofPattern("uuuuMMdd"))
        else LocalDate.parse(value)
    } catch (_: DateTimeParseException) {
        throw IllegalArgumentException("invalid date")
    }

    private fun validStamp(value: String): Boolean = try {
        LocalDateTime.parse(value, DateTimeFormatter.ofPattern("uuuu-MM-ddHH:mm:ss")); true
    } catch (_: DateTimeParseException) {
        false
    }

    private fun normalizeTimestamp(value: LocalDateTime, source: String): String =
        manualInstantText(value.atZone(ZoneId.of(sourceTimezones[source] ?: "UTC")).toInstant())

    private fun compact(value: String): String = value.replace(Regex("\\s+"), "")

    private fun findAllGroups(pattern: String, text: String): List<List<String>> =
        Regex(pattern).findAll(text).map { match -> match.groupValues.drop(1) }.toList()

    private fun <T> one(values: List<T>): T? {
        val distinct = values.toSet()
        return if (distinct.size == 1) distinct.first() else null
    }

    private fun tail(content: ByteArray, size: Int = 1024 * 1024): String {
        val start = maxOf(0, content.size - size)
        return String(content, start, content.size - start, StandardCharsets.ISO_8859_1)
    }

    private fun ByteArray.startsWithPdf(): Boolean =
        size >= 5 && this[0] == '%'.code.toByte() && this[1] == 'P'.code.toByte() && this[2] == 'D'.code.toByte() &&
            this[3] == 'F'.code.toByte() && this[4] == '-'.code.toByte()

    private fun fail(code: String): Nothing = throw StatementDocumentFailure(code)

    private data class PdfChar(val text: String, val x: Double, val y: Double, val width: Double, val height: Double,
        val size: Double) {
        companion object {
            fun from(position: TextPosition) = PdfChar(position.unicode, position.xDirAdj.toDouble(),
                position.yDirAdj.toDouble(), position.widthDirAdj.toDouble(), position.heightDir.toDouble(),
                position.fontSizeInPt.toDouble())
        }
    }

    private data class Segment(val x1: Double, val y1: Double, val x2: Double, val y2: Double)
    private data class Edge(val orientation: Char, val x0: Double, val top: Double, val x1: Double, val bottom: Double)
    private data class Point(val x: Double, val y: Double)
    private data class BBox(val x0: Double, val top: Double, val x1: Double, val bottom: Double)
    private class Edges {
        val vertical = mutableSetOf<Edge>()
        val horizontal = mutableSetOf<Edge>()
    }

    private class CharacterCollector(private val maxChars: Long,
        private val onPage: (Int, PDPage, List<TextPosition>) -> Unit) : PDFTextStripper() {
        private var count = 0L
        private var index = -1
        private var current = mutableListOf<TextPosition>()
        init { setSuppressDuplicateOverlappingText(false) }
        override fun startPage(page: PDPage) { index++; current = mutableListOf() }
        override fun writeString(text: String, positions: List<TextPosition>) {
            count += positions.size
            if (count > maxChars) throw StatementDocumentFailure("statement_rows_too_large")
            current.addAll(positions)
        }
        override fun endPage(page: PDPage) {
            onPage(index, page, current)
            current = mutableListOf()
        }
    }

    private class LineCollector(page: PDPage) : PDFGraphicsStreamEngine(page) {
        val segments = mutableListOf<Segment>()
        private var current: Point2D? = null
        private var start: Point2D? = null
        override fun appendRectangle(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D) {
            add(p0, p1); add(p1, p2); add(p2, p3); add(p3, p0)
        }
        override fun clip(windingRule: Int) {}
        override fun moveTo(x: Float, y: Float) { current = Point2D.Float(x, y); start = current }
        override fun lineTo(x: Float, y: Float) {
            current?.let { add(it, Point2D.Float(x, y)) }; current = Point2D.Float(x, y)
        }
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            current?.let { start ->
                val first = Point2D.Float(x1, y1)
                val second = Point2D.Float(x2, y2)
                val end = Point2D.Float(x3, y3)
                add(start, first); add(first, second); add(second, end)
            }
            current = Point2D.Float(x3, y3)
        }
        override fun getCurrentPoint(): Point2D = current ?: Point2D.Float()
        override fun closePath() { current?.let { end -> start?.let { add(end, it) } } }
        override fun endPath() { current = null; start = null }
        override fun strokePath() { current = null; start = null }
        override fun fillPath(windingRule: Int) { current = null; start = null }
        override fun fillAndStrokePath(windingRule: Int) { current = null; start = null }
        override fun shadingFill(shadingName: COSName) {}
        override fun drawImage(image: PDImage) {}
        private fun add(from: Point2D, to: Point2D) { segments += Segment(from.x, from.y, to.x, to.y) }
    }
}
