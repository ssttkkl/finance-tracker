package com.finance.tracker.backend

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*
import kotlinx.serialization.json.*

class IcbcCreditCardOriginalTest {
    private val directory by lazy { Path.of(requireNotNull(System.getenv("FT_KOTLIN_ICBC_CARD_ORIGINAL_DIR"))) }
    private val resource by lazy { Json.parseToJsonElement(requireNotNull(
        javaClass.getResourceAsStream("/icbc-credit-card-originals.json")).bufferedReader().use { it.readText() }).jsonObject }

    @Test fun originalFirstCard() = verify(0)
    @Test fun originalSecondCard() = verify(1)
    @Test fun originalCompleteIdentityAndTail() = verify(2)
    @Test fun originalMaskedCardAndCurrentFileLocalScope() = verify(3)
    @Test fun originalCardDoesNotLeakToNextRow() = verify(4)

    private fun verify(index: Int) {
        val cases = resource.getValue("cases").jsonArray
        assertEquals(5, cases.size)
        assertEquals(15, cases.sumOf { it.jsonObject.getValue("checks").jsonArray.size })
        assertEquals(5, cases.map { it.jsonObject.getValue("source") }.distinct().size)
        val case = cases[index].jsonObject
        val name = case.getValue("name").jsonPrimitive.content
        val manifest = Json.parseToJsonElement(Files.readString(directory.resolve("manifest.json"))).jsonObject.getValue(name).jsonObject
        val bytes = Files.readAllBytes(directory.resolve(manifest.getValue("file").jsonPrimitive.content))
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(manifest.getValue("sha256").jsonPrimitive.content, digest)
        val page = case.getValue("page").jsonObject
        val header = page.getValue("header").jsonArray.map { it.jsonPrimitive.content }
        val payloads = page.getValue("rows").jsonArray.map { values ->
            header.zip(values.jsonArray.map { it.jsonPrimitive.content }).toMap()
        }
        val document = CashStatementDocuments.readDocument(bytes, "icbc_credit")
        assertEquals("icbc_credit", document.sourceType)
        assertNull(document.sourceAccountIdentifier)
        assertNull(document.sourceAccountLabel)
        assertEquals(payloads, document.rows.map { it.payload })
        assertEquals(payloads.size, document.observedCount)
        assertEquals(payloads.size.toLong(), document.declaredCount)
        assertEquals(payloads.indices.map { it + 2 }, document.rows.map { it.position })
        assertEquals(payloads.indices.map { "p1:t0:r${it + 1}" }, document.rows.map { it.documentPosition })
        val observed = mutableListOf<Pair<Int, Map<String, Any?>>>()
        val parsed = CashStatementRowNormalization.normalizeIcbcDocument(document) { position, native -> observed += position to native }
        assertEquals(payloads.size, observed.size)
        assertEquals(payloads.size, parsed.rows.size)
        assertEquals(payloads.indices.map { it + 2 }, observed.map { it.first })
        for (raw in case.getValue("checks").jsonArray) {
            val check = raw.jsonObject
            if (check.getValue("kind").jsonPrimitive.content == "count") {
                assertEquals(check.getValue("expected").jsonPrimitive.int, observed.size)
                assertEquals(check.getValue("expected").jsonPrimitive.int, parsed.rows.count { it.fields != null && it.disposition == "candidate" })
            } else {
                val row = observed[check.getValue("row").jsonPrimitive.int].second
                val field = check.getValue("field").jsonPrimitive.content
                val actual = if (check.getValue("access").jsonPrimitive.content == "get") row.getOrDefault(field, check.getValue("default").jsonPrimitive.content) else row.getValue(field)
                assertEquals(check.getValue("expected").jsonPrimitive.content, actual)
            }
        }
        val before = sourceJson(parsed.rows.map { listOf(it.fields, it.sourceExplanation) }).toString()
        val file = ParsedImportFile(name, digest, "parsed", parsed)
        val sources = CashSourceRows.sourceRows(file)
        assertEquals(payloads.size, sources.size)
        val scan = CashImportFunding.scan(listOf(file))
        assertEquals(payloads.size, scan.groups.size)
        assertEquals(payloads.size, scan.rows.size)
        val choices = scan.groups.mapIndexed { i, group -> group.sourceAccountRef to
            mapOf("account_id" to (101 + i).toString(), "expected_account_revision" to 1) }.toMap()
        val accounts = scan.groups.indices.associate { i -> (101 + i).toString() to mapOf("id" to (101 + i).toString(),
            "active" to true, "type" to "cash", "revision" to 1, "currencies" to listOf("CNY", "USD")) }
        val resolved = CashImportFunding.resolve(scan, choices, accounts)
        val unselected = CashImportFunding.resolve(scan, emptyMap<String, Any?>(), accounts)
        for ((rowIndex, row) in parsed.rows.withIndex()) {
            val payload = payloads[rowIndex]
            val rawCard = payload.getValue("交易卡号")
            val owner = rawCard.takeIf { it.isNotEmpty() && '*' !in it }
            val display = "工商银行信用卡" + if (rawCard.isEmpty()) "" else "(${rawCard.takeLast(4)})"
            assertEquals("candidate", row.disposition)
            assertNull(row.errorCode)
            assertEquals(emptyList(), row.issueCodes)
            assertEquals(owner, row.sourceAccountIdentifier)
            assertEquals(owner, row.sourceExplanation["_source_account_identifier"])
            assertEquals(owner, row.sourceExplanation["file_account_key"])
            assertEquals(owner?.takeLast(4), row.sourceExplanation["card_number"])
            if (owner == null) assertTrue(setOf("_source_account_identifier", "file_account_key", "card_number").none { it in row.sourceExplanation })
            assertEquals(display, row.sourceExplanation["source_display_name"])
            val fields = assertNotNull(row.fields)
            val expectedFields = mapOf("amount" to listOf(listOf("-15.80"), listOf("-5.00"), listOf("-5.00"), listOf("-5.00"), listOf("-15.80", "-30.00"))[index][rowIndex],
                "currency" to if (index == 1) "USD" else "CNY",
                "occurred_at" to listOf(listOf("2026-03-01T09:39:57+00:00"), listOf("2026-03-17T10:32:25+00:00"),
                    listOf("2026-03-17T10:32:25+00:00"), listOf("2026-03-17T10:32:25+00:00"),
                    listOf("2026-03-01T09:39:57+00:00", "2026-03-02T02:00:00+00:00"))[index][rowIndex],
                "record_type" to "consumption", "record_subtype" to "not_applicable",
                "counterparty" to listOf(listOf("高德"), listOf("DEEPINFRA.COM"), listOf("测试商户"),
                    listOf("测试商户"), listOf("测试商家A", "测试商家B"))[index][rowIndex],
                "counterparty_account" to "", "counterparty_account_attrs" to emptyList<String>(),
                "note" to "消费", "source_payload" to payload)
            assertEquals(expectedFields, fields)
            assertEquals(listOf(listOf("-15.80"), listOf("-5.00"), listOf("-5.00"), listOf("-5.00"), listOf("-15.80", "-30.00"))[index][rowIndex], fields["amount"])
            assertEquals(if (index == 1) "USD" else "CNY", fields["currency"])
            assertEquals("consumption", fields["record_type"])
            assertEquals("not_applicable", fields["record_subtype"])
            val occurrence = sources.getValue("$name:r${row.position}")
            assertEquals(owner, occurrence.sourceAccountKey)
            assertEquals(fields, occurrence.fields)
            assertEquals(digest, occurrence.fileDigest)
            assertEquals(row.position, occurrence.position)
            assertEquals(row.documentPosition, occurrence.documentPosition)
            val group = scan.groups[rowIndex]
            assertEquals(if (owner == null) "file_local" else "file_account", group.identityKind)
            assertEquals(rawCard.ifEmpty { "unknown" }, group.identityKey)
            assertEquals(display, group.displayName)
            assertEquals("icbc_credit", group.sourceType)
            assertEquals(listOf(if (index == 1) "USD" else "CNY"), group.currencies)
            assertEquals(listOf("$name:r${row.position}"), group.rowRefs)
            if (owner != null) {
                assertEquals(owner, group.originKey)
                assertFalse(owner in group.displayName)
            } else {
                assertTrue(group.originKey.isNotEmpty())
                assertNotEquals(rawCard, group.originKey)
                assertFalse(group.displayName.contains("****"))
            }
            assertEquals(CashFundingRow("$name:r${row.position}", fields, listOf(CashFundingPart(group.sourceAccountRef, null))), scan.rows[rowIndex])
            val account = (101 + rowIndex).toString()
            fun resolvedFields(accountId: String?) = fields + mapOf("account_id" to accountId, "cash_granularity" to "atomic",
                "components" to listOf(mapOf("ordinal" to 0, "account_id" to accountId, "amount" to fields["amount"], "currency" to fields["currency"])))
            assertEquals(CashFundingResolution("$name:r${row.position}", resolvedFields(account), listOf(group.sourceAccountRef),
                emptyList(), true, null, listOf(group.sourceAccountRef to account)), resolved[rowIndex])
            assertEquals(CashFundingResolution("$name:r${row.position}", resolvedFields(null), listOf(group.sourceAccountRef),
                listOf("account_missing", "incomplete_record"), false, null, emptyList()), unselected[rowIndex])
            CashRecordPolicy.validateForPosting(resolved[rowIndex].fields)
        }
        assertEquals(before, sourceJson(parsed.rows.map { listOf(it.fields, it.sourceExplanation) }).toString())
        assertEquals(payloads, document.rows.map { it.payload })
        assertContentEquals(bytes, Files.readAllBytes(directory.resolve(manifest.getValue("file").jsonPrimitive.content)))
    }
}
