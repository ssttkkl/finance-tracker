package com.finance.tracker.backend

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

class CashLegacySourceReplacementTest {
    private fun cases(): List<JsonObject> = Json.parseToJsonElement(requireNotNull(
        javaClass.getResourceAsStream("/legacy-source-replacements.json")).bufferedReader().use { it.readText() })
        .jsonArray.map { it.jsonObject }.also { cases ->
            assertEquals(5, cases.size)
            assertEquals(5, cases.map { it.getValue("source") }.toSet().size)
            assertEquals(7, cases.sumOf { it.getValue("original_assertions").jsonArray.size })
        }

    @Test fun retiredFixedLabelsUseCurrentSourceAndFundingContracts() {
        val cases = cases().filter { it.getValue("kind").jsonPrimitive.content == "fixed_source" }
        assertEquals(4, cases.size)
        assertEquals(5, cases.sumOf { it.getValue("calls").jsonArray.size })
        for (item in cases) {
            val source = item.getValue("source_type").jsonPrimitive.content
            val owner = item["owner"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
            val payloads = item.getValue("payloads").jsonArray.map { raw ->
                raw.jsonObject.mapValues { it.value.jsonPrimitive.content }
            }
            val calls = item.getValue("calls").jsonArray
            assertEquals(calls.size, payloads.size)
            for ((index, call) in calls.withIndex()) {
                val args = call.jsonObject.getValue("arguments").jsonArray.map { it.jsonPrimitive.content }
                assertEquals(source, args[0])
                val cpKey = when (source) { "icbc_debit" -> "对方户名"; "ccb_debit" -> "对方账号与户名"; else -> "交易对方" }
                val noteKey = when (source) { "alipay" -> "商品说明"; "wechat" -> "商品"; else -> "摘要" }
                assertEquals(args[1], payloads[index][cpKey])
                assertEquals(args[2], payloads[index][noteKey])
            }
            val bank = source in setOf("icbc_debit", "ccb_debit")
            val document = CashStatementDocument(source, if (source == "icbc_debit") "pdf:0" else "sheet:0",
                "replacement-decoded-v1", payloads.mapIndexed { index, payload -> RawCashRow(index + 1, payload) },
                owner, owner, CashSourceCoverage(null, null, false, "", emptyMap(), "replacement-coverage-v1"),
                payloads.size.toLong(), payloads.size)
            val parsed = CashStatementRowNormalization.normalizeDocument(document)
            assertEquals(source, parsed.sourceType)
            assertEquals(owner, parsed.sourceAccountIdentifier)
            assertEquals(payloads.size, parsed.observedCount)
            val file = ParsedImportFile("original-$source", "a".repeat(64), "parsed", parsed)
            val occurrences = CashSourceRows.sourceRows(file)
            val scan = CashImportFunding.scan(listOf(file))
            assertEquals(payloads.size, occurrences.size)
            assertEquals(payloads.size, scan.rows.size)
            val group = scan.groups.single()
            assertEquals(source, group.sourceType)
            assertEquals(if (bank) "file_account" else "payment_method", group.identityKind)
            assertEquals(listOf("CNY"), group.currencies)
            assertEquals(occurrences.keys.toList(), group.rowRefs)
            for ((index, row) in parsed.rows.withIndex()) {
                assertEquals("candidate", row.disposition)
                assertNull(row.errorCode)
                assertEquals(emptyList(), row.issueCodes)
                val fields = assertNotNull(row.fields)
                assertEquals(0, BigDecimal("-10.00").compareTo(BigDecimal(assertIs<String>(fields["amount"]))))
                assertEquals("CNY", fields["currency"])
                assertEquals(payloads[index], fields["source_payload"])
                val occurrence = occurrences.values.elementAt(index)
                assertEquals(source, occurrence.sourceType)
                assertEquals(fields, occurrence.fields)
                assertNull(occurrence.providerId)
                assertEquals(owner ?: if (source == "wechat") "wechat:default:v1" else null, occurrence.sourceAccountKey)
                val fundingRow = scan.rows[index]
                assertNull(fundingRow.errorCode)
                assertEquals(emptyList(), fundingRow.issueCodes)
                assertEquals(listOf(group.sourceAccountRef), fundingRow.groupRefs)
                assertEquals(fields, fundingRow.fields)
            }
            when (source) {
                "alipay" -> {
                    assertEquals("支付宝余额", group.identityKey)
                    assertEquals("支付宝余额", group.displayName)
                    assertTrue(group.originKey.matches(Regex("[0-9a-f]{64}")))
                    val other = CashImportFunding.scan(listOf(file.copy(fileRef = "other", digest = "b".repeat(64)))).groups.single()
                    assertNotEquals(group.originKey, other.originKey)
                    assertNotEquals(group.sourceAccountRef, other.sourceAccountRef)
                }
                "wechat" -> {
                    assertEquals("微信零钱", group.identityKey)
                    assertEquals("微信零钱", group.displayName)
                    assertEquals("wechat:default:v1", group.originKey)
                }
                else -> {
                    assertEquals(owner, group.identityKey)
                    assertEquals(owner, group.originKey)
                    assertEquals(if (source == "icbc_debit") "工商银行借记卡(3697)" else "建设银行(2222)", group.displayName)
                    assertFalse(group.displayName.contains(requireNotNull(owner)))
                    if (source == "icbc_debit") assertEquals(2, group.rowRefs.size)
                }
            }
            if (!bank) {
                val paymentKey = if (source == "alipay") "收/付款方式" else "支付方式"
                val changedPayload = payloads.single() + (paymentKey to "工商银行储蓄卡(1234)")
                val changed = CashStatementRowNormalization.normalizeDocument(document.copy(rows = listOf(RawCashRow(1, changedPayload))))
                val changedFile = file.copy(document = changed)
                val changedGroup = CashImportFunding.scan(listOf(changedFile)).groups.single()
                assertEquals(source, changedGroup.sourceType)
                assertEquals(source, CashSourceRows.sourceRows(changedFile).values.single().sourceType)
                assertEquals("工商银行储蓄卡(1234)", changedGroup.identityKey)
                assertEquals(group.originKey, changedGroup.originKey)
                assertNotEquals(group.sourceAccountRef, changedGroup.sourceAccountRef)
                assertEquals(changedPayload, changed.rows.single().fields?.get("source_payload"))
            }
            assertEquals(payloads, document.rows.map { it.payload })
        }
    }

    @Test fun retiredAcceptanceWrapperUsesActualSkippedRowAndNoFundingCandidates() {
        val item = cases().single { it.getValue("kind").jsonPrimitive.content == "unpaid_closed" }
        val directory = Path.of(requireNotNull(System.getenv("FT_KOTLIN_LEGACY_SOURCE_DIR")))
        val path = directory.resolve(item.getValue("file").jsonPrimitive.content)
        val bytes = Files.readAllBytes(path)
        val header = item.getValue("header").jsonArray.map { it.jsonPrimitive.content }
        val values = item.getValue("rows").jsonArray.single().jsonArray.map { it.jsonPrimitive.content }
        val payload = header.zip(values).toMap()
        assertEquals(8, payload.size)
        val document = CashStatementDocuments.readDocument(bytes, "alipay")
        assertEquals(1, document.observedCount)
        assertEquals(payload, document.rows.single().payload)
        assertEquals(2, document.rows.single().position)
        val parsed = CashStatementRowNormalization.normalizeDocument(document)
        assertEquals(1, parsed.observedCount)
        val row = parsed.rows.single()
        assertEquals(2, row.position)
        assertEquals("skipped", row.disposition)
        assertEquals("unpaid_closed", row.errorCode)
        assertEquals(emptyList(), row.issueCodes)
        assertNull(row.fields)
        val file = ParsedImportFile("closed", "c".repeat(64), "parsed", parsed)
        assertEquals(emptyMap(), CashSourceRows.sourceRows(file))
        val scan = CashImportFunding.scan(listOf(file))
        assertEquals(emptyList(), scan.rows)
        assertEquals(emptyList(), scan.groups)
        assertEquals(payload, document.rows.single().payload)
        assertContentEquals(bytes, Files.readAllBytes(path))
    }
}
