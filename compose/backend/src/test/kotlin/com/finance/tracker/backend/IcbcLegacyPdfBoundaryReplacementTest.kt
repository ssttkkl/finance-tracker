package com.finance.tracker.backend

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class IcbcLegacyPdfBoundaryReplacementTest {
    private val directory by lazy { Path.of(requireNotNull(System.getenv("FT_KOTLIN_LEGACY_PDF_BOUNDARY_DIR"))) }
    private val resource by lazy { Json.parseToJsonElement(requireNotNull(
        javaClass.getResourceAsStream("/icbc-legacy-pdf-boundaries.json")).bufferedReader().use { it.readText() }).jsonObject }
    private val manifest by lazy { Json.parseToJsonElement(Files.readString(directory.resolve("manifest.json"))).jsonObject }

    private fun bytes(name: String): ByteArray {
        val item = manifest.getValue(name).jsonObject
        val bytes = Files.readAllBytes(directory.resolve(item.getValue("file").jsonPrimitive.content))
        assertEquals(item.getValue("sha256").jsonPrimitive.content,
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        return bytes
    }

    private fun expected(name: String, index: Int): Map<String, String> {
        val page = resource.getValue("variants").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == name }.getValue("page").jsonObject
        val header = page.getValue("header").jsonArray.map { it.jsonPrimitive.content }
        val values = page.getValue("rows").jsonArray[index].jsonArray
        return header.mapIndexed { column, key -> key to values.getOrNull(column)?.takeIf { it != JsonNull }?.jsonPrimitive?.content.orEmpty() }.toMap()
    }

    private fun read(name: String): CashStatementDocument {
        val content = bytes(name)
        val doc = CashStatementDocuments.readDocument(content, "icbc_debit")
        assertEquals("icbc_debit", doc.sourceType)
        assertEquals("6212000000000001234", doc.sourceAccountIdentifier)
        assertEquals("pdf:0", doc.tableKey)
        assertEquals("icbc-pdf-tables-v1", doc.parserVersion)
        assertContentEquals(content, bytes(name))
        return doc
    }

    private fun resolve(name: String, parsed: ParsedCashDocument): List<CashFundingResolution> {
        val before = sourceJson(parsed.rows.map { it.fields }).toString()
        val file = ParsedImportFile(name, manifest.getValue(name).jsonObject.getValue("sha256").jsonPrimitive.content, "parsed", parsed)
        val rows = CashSourceRows.sourceRows(file)
        assertEquals(parsed.rows.size, rows.size)
        val scan = CashImportFunding.scan(listOf(file))
        assertEquals(parsed.rows.size, scan.rows.size)
        val group = scan.groups.single()
        assertEquals("file_account", group.identityKind)
        assertEquals("6212000000000001234", group.identityKey)
        assertEquals(rows.keys.toList(), group.rowRefs)
        val result = CashImportFunding.resolve(scan,
            mapOf(group.sourceAccountRef to mapOf("account_id" to "101", "expected_account_revision" to 1)),
            mapOf("101" to mapOf("id" to "101", "type" to "cash", "active" to true, "revision" to 1, "currencies" to listOf("CNY"))))
        for ((index, row) in parsed.rows.withIndex()) {
            val short = name == "short_in_full_grid" && index == 0
            val missingDate = name == "missing_date" && index == 0
            val payloadIndex = if (name.startsWith("blank")) 1 else index
            val fields = mapOf("amount" to if (short) null else "-20.00", "currency" to if (short) null else "CNY",
                "occurred_at" to if (missingDate) null else if (short) "2026-01-05T12:32:09+00:00" else "2026-01-10T02:00:17+00:00",
                "record_type" to if (short) null else "consumption", "record_subtype" to if (short) null else "not_applicable",
                "counterparty" to if (short) "" else "测试商户", "counterparty_account" to if (short) "" else "6222****1234",
                "counterparty_account_attrs" to if (short) emptyList() else listOf("masked"),
                "note" to if (short) "" else "无卡支付", "source_payload" to expected(name, payloadIndex))
            assertEquals(fields, row.fields)
            val resolvedFields = fields + mapOf("account_id" to "101", "cash_granularity" to "atomic",
                "components" to listOf(mapOf("ordinal" to 0, "account_id" to "101", "amount" to if (short) null else "-20.00",
                    "currency" to if (short) null else "CNY")))
            val issues = if (short) listOf("currency_unrecognized", "amount_unreadable", "allocation_missing", "incomplete_record")
                else if (missingDate) listOf("time_unreadable", "incomplete_record") else emptyList()
            assertEquals(CashFundingResolution("$name:r${row.position}", resolvedFields, listOf(group.sourceAccountRef), issues,
                !short && !missingDate, null, listOf(group.sourceAccountRef to "101")), result[index])
            if (short || missingDate) {
                val failure = assertFailsWith<CashPolicyError> { CashRecordPolicy.validateForPosting(result[index].fields) }
                assertEquals("incomplete_record", failure.code)
                assertEquals(if (short) listOf("amount", "currency", "record_subtype", "record_type") else listOf("occurred_at"), failure.fields)
            } else CashRecordPolicy.validateForPosting(result[index].fields)
        }
        assertEquals(before, sourceJson(parsed.rows.map { it.fields }).toString())
        return result
    }

    @Test fun retiredAllNoneExceptionUsesActualBlankPdfRowAndMissingDateCandidate() {
        val originals = resource.getValue("originals").jsonArray
        assertEquals(2, originals.size)
        assertEquals(2, originals.sumOf { it.jsonObject.getValue("original_assertions").jsonArray.size })
        val blank = originals.single { it.jsonObject.getValue("original_values").jsonArray.size == 13 }
        assertTrue(blank.jsonObject.getValue("original_values").jsonArray.all { it == JsonNull })
        for ((name, complete, declared) in listOf(Triple("blank_control", true, 1L), Triple("blank_count_mismatch", false, 2L))) {
            val doc = read(name)
            assertEquals(1, doc.observedCount)
            assertEquals(declared, doc.declaredCount)
            assertEquals(complete, doc.coverage.complete)
            val raw = doc.rows.single()
            assertEquals(3, raw.position)
            assertEquals("p1:t0:r2", raw.documentPosition)
            assertEquals(expected(name, 1), raw.payload)
            assertNull(raw.errorCode)
            val parsed = CashStatementRowNormalization.normalizeDocument(doc)
            assertEquals(1, parsed.observedCount)
            assertEquals(complete, parsed.coverage.complete)
            if (!complete) {
                assertNull(parsed.coverage.documentKey)
                assertNull(parsed.coverage.pageTotal)
                assertEquals(emptyMap(), parsed.coverage.pageFingerprints)
            }
            assertEquals("candidate", parsed.rows.single().disposition)
            assertEquals(emptyList(), parsed.rows.single().issueCodes)
            assertEquals("-20.00", parsed.rows.single().fields?.get("amount"))
            assertTrue(resolve(name, parsed).single().ready)
        }
        val doc = read("missing_date")
        assertEquals(2, doc.observedCount)
        assertEquals(listOf(2, 3), doc.rows.map { it.position })
        assertEquals(listOf(expected("missing_date", 0), expected("missing_date", 1)), doc.rows.map { it.payload })
        val parsed = CashStatementRowNormalization.normalizeDocument(doc)
        val bad = parsed.rows[0]
        assertEquals("candidate", bad.disposition)
        assertNull(bad.errorCode)
        assertEquals(listOf("time_unreadable"), bad.issueCodes)
        val fields = assertNotNull(bad.fields)
        assertNull(fields["occurred_at"])
        assertEquals("-20.00", fields["amount"])
        assertEquals("CNY", fields["currency"])
        assertEquals(expected("missing_date", 0), fields["source_payload"])
        assertFalse(parsed.coverage.complete)
        assertEquals("", parsed.coverage.evidence)
        assertNull(parsed.coverage.documentKey)
        assertNull(parsed.coverage.pageTotal)
        assertEquals(emptyMap(), parsed.coverage.pageFingerprints)
        assertEquals(emptyList(), parsed.rows[1].issueCodes)
        val resolved = resolve("missing_date", parsed)
        assertFalse(resolved[0].ready)
        assertNull(resolved[0].errorCode)
        assertEquals(listOf("time_unreadable", "incomplete_record"), resolved[0].issueCodes)
        assertTrue(resolved[1].ready)
        assertNull(resolved[1].errorCode)
        assertEquals(emptyList(), resolved[1].issueCodes)
    }

    @Test fun retiredTwoCellExceptionUsesActualGeometryAndHeaderRejection() {
        val original = resource.getValue("originals").jsonArray.single {
            it.jsonObject.getValue("original_values").jsonArray.size == 2 }.jsonObject.getValue("original_values").jsonArray
        assertEquals(listOf("2026-01-05\n20:32:09", "1614020101021984636"), original.map { it.jsonPrimitive.content })
        val doc = read("short_in_full_grid")
        assertEquals(2, doc.observedCount)
        assertEquals(listOf(2, 3), doc.rows.map { it.position })
        assertEquals(listOf(expected("short_in_full_grid", 0), expected("short_in_full_grid", 1)), doc.rows.map { it.payload })
        assertEquals(13, doc.rows[0].payload?.size)
        assertNull(doc.rows[0].errorCode)
        val parsed = CashStatementRowNormalization.normalizeDocument(doc)
        val bad = parsed.rows[0]
        assertEquals("candidate", bad.disposition)
        assertNull(bad.errorCode)
        assertEquals(listOf("currency_unrecognized", "amount_unreadable"), bad.issueCodes)
        val fields = assertNotNull(bad.fields)
        assertEquals("2026-01-05T12:32:09+00:00", fields["occurred_at"])
        assertNull(fields["amount"])
        assertNull(fields["currency"])
        assertNull(fields["record_type"])
        assertNull(fields["record_subtype"])
        assertEquals(expected("short_in_full_grid", 0), fields["source_payload"])
        assertFalse(parsed.coverage.complete)
        assertEquals("", parsed.coverage.evidence)
        assertNull(parsed.coverage.documentKey)
        assertNull(parsed.coverage.pageTotal)
        assertEquals(emptyMap(), parsed.coverage.pageFingerprints)
        val resolved = resolve("short_in_full_grid", parsed)
        assertFalse(resolved[0].ready)
        assertNull(resolved[0].errorCode)
        assertEquals(listOf("currency_unrecognized", "amount_unreadable", "allocation_missing", "incomplete_record"), resolved[0].issueCodes)
        assertTrue(resolved[1].ready)
        assertNull(resolved[1].errorCode)
        assertEquals(emptyList(), resolved[1].issueCodes)
        assertEquals("statement_header_invalid", assertFailsWith<StatementDocumentFailure> {
            CashStatementDocuments.readDocument(bytes("two_column_table"), "icbc_debit")
        }.code)
    }
}
