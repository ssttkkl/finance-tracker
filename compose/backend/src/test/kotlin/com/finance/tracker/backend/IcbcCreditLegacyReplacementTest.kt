package com.finance.tracker.backend

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*
import kotlinx.serialization.json.*

class IcbcCreditLegacyReplacementTest {
    private val directory by lazy { Path.of(requireNotNull(System.getenv("FT_KOTLIN_ICBC_CREDIT_LEGACY_DIR"))) }
    private val resource by lazy { Json.parseToJsonElement(requireNotNull(
        javaClass.getResourceAsStream("/icbc-credit-legacy-replacements.json")).bufferedReader().use { it.readText() }).jsonObject }

    private fun read(name: String): ParsedImportFile {
        val cases = resource.getValue("cases").jsonArray
        assertEquals(2, cases.size)
        assertEquals(3, cases.sumOf { it.jsonObject.getValue("original_assertions").jsonArray.size })
        assertEquals(3, cases.sumOf { it.jsonObject.getValue("calls").jsonArray.size })
        val item = Json.parseToJsonElement(Files.readString(directory.resolve("manifest.json"))).jsonObject.getValue(name).jsonObject
        val bytes = Files.readAllBytes(directory.resolve(item.getValue("file").jsonPrimitive.content))
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(item.getValue("sha256").jsonPrimitive.content, digest)
        val page = resource.getValue("variants").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == name }.getValue("page").jsonObject
        val header = page.getValue("header").jsonArray.map { it.jsonPrimitive.content }
        val payloads = page.getValue("rows").jsonArray.map { row -> header.zip(row.jsonArray.map { it.jsonPrimitive.content }).toMap() }
        val doc = CashStatementDocuments.readDocument(bytes, "icbc_credit")
        assertNull(doc.sourceAccountIdentifier)
        assertEquals(payloads, doc.rows.map { it.payload })
        assertEquals(payloads.indices.map { it + 2 }, doc.rows.map { it.position })
        assertEquals(payloads.size, doc.observedCount)
        assertEquals(payloads.size.toLong(), doc.declaredCount)
        val parsed = CashStatementRowNormalization.normalizeDocument(doc)
        val missing = name == "legacy_missing_card"
        assertEquals(payloads.size, parsed.rows.size)
        assertEquals(!missing, parsed.coverage.complete)
        assertNull(parsed.coverage.documentKey)
        assertNull(parsed.coverage.pageTotal)
        assertEquals(emptyMap(), parsed.coverage.pageFingerprints)
        for ((index, row) in parsed.rows.withIndex()) {
            val owner = payloads[index].getValue("交易卡号").ifEmpty { null }
            assertEquals(owner, row.sourceAccountIdentifier)
            assertEquals(owner, row.sourceExplanation["_source_account_identifier"])
            assertEquals(owner, row.sourceExplanation["file_account_key"])
            assertEquals(owner?.takeLast(4), row.sourceExplanation["card_number"])
            assertEquals("工商银行信用卡" + if (owner == null) "" else "(${owner.takeLast(4)})", row.sourceExplanation["source_display_name"])
            assertEquals("candidate", row.disposition)
            assertNull(row.errorCode)
            assertEquals(if (missing) listOf("currency_unrecognized") else emptyList(), row.issueCodes)
            assertEquals("p1:t0:r${index + 1}", row.documentPosition)
            assertEquals(mapOf("amount" to if (missing) "-1234.56" else "-5.00", "currency" to if (missing) null else "CNY",
                "occurred_at" to if (missing) "2026-01-15T13:23:58+00:00" else "2026-03-17T10:32:25+00:00",
                "record_type" to "consumption", "record_subtype" to "not_applicable",
                "counterparty" to if (missing) "测试用户" else "测试商户", "counterparty_account" to "",
                "counterparty_account_attrs" to emptyList<String>(), "note" to "消费", "source_payload" to payloads[index]), row.fields)
        }
        assertEquals(payloads, doc.rows.map { it.payload })
        val file = ParsedImportFile(name, digest, "parsed", parsed)
        val before = sourceJson(parsed.rows.map { listOf(it.fields, it.sourceExplanation) }).toString()
        val sources = CashSourceRows.sourceRows(file)
        assertEquals(payloads.size, sources.size)
        for ((index, row) in sources.values.withIndex()) {
            assertEquals(parsed.rows[index].sourceAccountIdentifier, row.sourceAccountKey)
            assertEquals(parsed.rows[index].fields, row.fields)
            assertEquals(digest, row.fileDigest)
            assertEquals(index + 2, row.position)
            assertNull(row.providerId)
            assertNull(row.stableEvidence)
        }
        val scan = CashImportFunding.scan(listOf(file))
        assertEquals(payloads.size, scan.groups.size)
        val accounts = scan.groups.indices.associate { i -> (101 + i).toString() to mapOf("id" to (101 + i).toString(),
            "type" to "cash", "active" to true, "revision" to 1, "currencies" to listOf("CNY")) }
        val choices = scan.groups.mapIndexed { i, g -> g.sourceAccountRef to mapOf("account_id" to (101 + i).toString(), "expected_account_revision" to 1) }.toMap()
        val selected = CashImportFunding.resolve(scan, choices, accounts)
        val absent = CashImportFunding.resolve(scan, emptyMap<String, Any?>(), accounts)
        for ((index, group) in scan.groups.withIndex()) {
            val row = parsed.rows[index]
            val fields = assertNotNull(row.fields)
            assertEquals(if (missing) "file_local" else "file_account", group.identityKind)
            assertEquals(row.sourceAccountIdentifier ?: "unknown", group.identityKey)
            assertEquals(if (missing) emptyList() else listOf("CNY"), group.currencies)
            assertEquals(listOf("$name:r${index + 2}"), group.rowRefs)
            assertEquals(row.sourceExplanation["source_display_name"], group.displayName)
            if (!missing) {
                assertEquals(row.sourceAccountIdentifier, group.originKey)
                assertFalse(row.sourceAccountIdentifier!! in group.displayName)
            }
            val initial = if (missing) listOf("currency_unrecognized") else emptyList()
            assertEquals(CashFundingRow("$name:r${index + 2}", fields, listOf(CashFundingPart(group.sourceAccountRef, null)), initial), scan.rows[index])
            fun resolved(account: String?) = fields + mapOf("account_id" to account, "cash_granularity" to "atomic",
                "components" to listOf(mapOf("ordinal" to 0, "account_id" to account, "amount" to fields["amount"], "currency" to fields["currency"])))
            val account = (101 + index).toString()
            assertEquals(CashFundingResolution("$name:r${index + 2}", resolved(account), listOf(group.sourceAccountRef),
                initial + if (missing) listOf("incomplete_record") else emptyList(), !missing, null, listOf(group.sourceAccountRef to account)), selected[index])
            assertEquals(CashFundingResolution("$name:r${index + 2}", resolved(null), listOf(group.sourceAccountRef),
                initial + listOf("account_missing", "incomplete_record"), false, null, emptyList()), absent[index])
            if (missing) {
                val failure = assertFailsWith<CashPolicyError> { CashRecordPolicy.validateForPosting(selected[index].fields) }
                assertEquals("incomplete_record", failure.code)
                assertEquals(listOf("currency"), failure.fields)
                assertTrue(setOf("card_number", "_source_account_identifier", "file_account_key").none { it in row.sourceExplanation })
            } else CashRecordPolicy.validateForPosting(selected[index].fields)
        }
        assertEquals(before, sourceJson(parsed.rows.map { listOf(it.fields, it.sourceExplanation) }).toString())
        return file
    }

    @Test fun retiredCreditHashUsesActualSeparateCardScopesAndPhysicalOccurrences() {
        val firstFile = read("first_card")
        val secondFile = read("second_card")
        val first = CashSourceRows.sourceRows(firstFile).values.single()
        val second = CashSourceRows.sourceRows(secondFile).values.single()
        assertEquals("622599000000001200", first.sourceAccountKey)
        assertEquals("622599000000000851", second.sourceAccountKey)
        assertNotEquals(first.fileDigest, second.fileDigest)
        assertNotEquals(first.occurrenceKey, second.occurrenceKey)
        assertEquals(first.fields.filterKeys { it != "source_payload" }, second.fields.filterKeys { it != "source_payload" })
        val stored = SourceRecord("saved-first", first.sourceType, first.sourceAccountKey, first.fields + ("state" to "posted"),
            coverages = listOf(first.coverage), bindingKeys = first.bindingKeys.toSet(),
            fileOccurrences = mapOf(first.fileScopeKey to setOf(first.occurrenceKey)))
        val before = sourceJson(stored.fields).toString()
        assertEquals(listOf(SourceResolution(first.occurrenceKey, "new", bind = true)), CashSourceResolver.resolveSourceFile(listOf(first), emptyList()))
        assertEquals(listOf(SourceResolution(second.occurrenceKey, "new", bind = true)), CashSourceResolver.resolveSourceFile(listOf(second), listOf(stored)))
        val retriedFile = read("first_card").copy(fileRef = "retry")
        val retried = CashSourceRows.sourceRows(retriedFile).values.single()
        assertEquals(first, retried)
        assertEquals(listOf(SourceResolution(retried.occurrenceKey, "duplicate", "saved-first", bind = true)), CashSourceResolver.resolveSourceFile(listOf(retried), listOf(stored)))
        val batch = CashSourceRows.sourceBatchRows(listOf(firstFile, secondFile, retriedFile))
        assertEquals(SourceBatchPlan(listOf(SourceBatchResolution("first_card:r2", "new", bind = true),
            SourceBatchResolution("second_card:r2", "new", bind = true),
            SourceBatchResolution("retry:r2", "batch_duplicate", bind = true, duplicateOf = "first_card:r2"))), CashSourceBatch.resolve(batch, emptyList()))
        val together = read("two_cards_one_file")
        val rows = CashSourceRows.sourceRows(together).values.toList()
        assertEquals(listOf(first.sourceAccountKey, second.sourceAccountKey), rows.map { it.sourceAccountKey })
        assertEquals(2, rows.map { it.occurrenceKey }.distinct().size)
        assertEquals(rows.map { SourceResolution(it.occurrenceKey, "new", bind = true) }, CashSourceResolver.resolveSourceFile(rows, emptyList()))
        assertEquals(SourceBatchPlan(listOf(SourceBatchResolution("two_cards_one_file:r2", "new", bind = true),
            SourceBatchResolution("two_cards_one_file:r3", "new", bind = true))),
            CashSourceBatch.resolve(CashSourceRows.sourceBatchRows(listOf(together)), emptyList()))
        assertEquals(before, sourceJson(stored.fields).toString())
    }

    @Test fun retiredSevenTokenFormatKeepsMissingCurrencyAndRequiresCompletion() {
        val file = read("legacy_missing_card")
        val row = CashSourceRows.sourceRows(file).values.single()
        assertNull(row.sourceAccountKey)
        assertEquals("-1234.56", row.fields["amount"])
        assertNull(row.fields["currency"])
        assertFalse(row.coverage.complete)
        assertEquals("", row.coverage.evidence)
    }
}
