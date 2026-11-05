package com.finance.tracker.backend

import java.math.BigDecimal
import kotlin.test.*
import kotlinx.serialization.json.*

class IcbcLegacyIdentityReplacementTest {
    private val owner = "6212000000000003697"
    private val columns = listOf("交易日期", "账号", "储种", "序号", "币种", "钞汇", "摘要", "地区",
        "收入/支出金额", "余额", "对方户名", "对方账号", "渠道")

    private fun cases() = Json.parseToJsonElement(requireNotNull(
        javaClass.getResourceAsStream("/icbc-legacy-identity-replacements.json")).bufferedReader().use { it.readText() })
        .jsonArray.map { it.jsonObject }.also {
            assertEquals(2, it.size)
            assertEquals(6, it.sumOf { item -> item.getValue("original_assertions").jsonArray.size })
            assertEquals(4, it.sumOf { item -> item.getValue("calls").jsonArray.size })
        }

    private fun payload(call: JsonObject): Map<String, String> {
        val original = call.getValue("row").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(13, original.size)
        assertEquals(owner, call.getValue("owner").jsonPrimitive.content)
        val partial = call["original_partial_payload"]?.takeIf { it != JsonNull }?.jsonObject
            ?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        val current = call.getValue("current_payload").jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(columns.zip(original).toMap() + partial, current)
        return current
    }

    private fun file(ref: String, payloads: List<Map<String, String>>): ParsedImportFile {
        val before = sourceJson(payloads).toString()
        val raw = CashStatementDocument("icbc_debit", "pdf:0", "icbc-pdf-tables-v1",
            payloads.mapIndexed { index, payload -> RawCashRow(index + 1, payload, documentPosition = "p1:t0:r${index + 1}") },
            owner, owner, CashSourceCoverage(null, null, false, "", emptyMap(), "icbc-pdf-coverage-v1"),
            payloads.size.toLong(), payloads.size)
        val parsed = CashStatementRowNormalization.normalizeDocument(raw)
        assertEquals(owner, parsed.sourceAccountIdentifier)
        assertEquals(payloads.size, parsed.observedCount)
        assertEquals(raw.coverage, parsed.coverage)
        assertFalse(parsed.coverage.complete)
        assertEquals("", parsed.coverage.evidence)
        assertNull(parsed.coverage.documentKey)
        assertNull(parsed.coverage.pageTotal)
        assertEquals(emptyMap(), parsed.coverage.pageFingerprints)
        for ((index, row) in parsed.rows.withIndex()) {
            assertEquals("candidate", row.disposition)
            assertNull(row.errorCode)
            assertEquals(emptyList(), row.issueCodes)
            assertNull(row.providerId)
            assertEquals(owner, row.sourceExplanation["_source_account_identifier"])
            val fields = assertNotNull(row.fields)
            assertEquals(payloads[index], fields["source_payload"])
            assertEquals("CNY", fields["currency"])
            assertEquals(0, BigDecimal("-2000").compareTo(BigDecimal(assertIs<String>(fields["amount"]))))
            assertEquals("无卡支付", fields["note"])
            assertEquals("2026-01-10T02:00:17+00:00", fields["occurred_at"])
            assertEquals("梁碧玲", fields["counterparty"])
            assertEquals("网上银行", row.paymentMethod)
            assertEquals(mapOf("amount" to "-2000.00", "currency" to "CNY", "occurred_at" to "2026-01-10T02:00:17+00:00",
                "record_type" to "consumption", "record_subtype" to "not_applicable", "counterparty" to "梁碧玲",
                "counterparty_account" to "6217****8572", "counterparty_account_attrs" to listOf("masked"),
                "note" to "无卡支付", "source_payload" to payloads[index]), fields)
        }
        assertEquals(payloads, raw.rows.map { it.payload })
        val result = ParsedImportFile(ref, "a".repeat(64), "parsed", parsed)
        val rows = CashSourceRows.sourceRows(result)
        assertEquals(payloads.size, rows.size)
        for ((index, row) in rows.values.withIndex()) {
            assertEquals("icbc_debit", row.sourceType)
            assertEquals(owner, row.sourceAccountKey)
            assertEquals(index + 1, row.position)
            assertEquals("p1:t0:r${index + 1}", row.documentPosition)
            assertNull(row.providerId)
            assertNull(row.providerBindingKey)
            assertNull(row.stableEvidence)
            assertNull(row.stableEvidenceBindingKey)
            assertEquals(listOf("account" to "file_occurrence", "file_local" to "file_occurrence"),
                row.identityBindingSpecs.map { it.scopeKind to it.identityKind })
            assertEquals(payloads[index], row.fields["source_payload"])
            assertEquals(parsed.rows[index].fields, row.fields)
        }
        assertEquals(before, sourceJson(payloads).toString())
        return result
    }

    private fun old(first: SourceOccurrence, id: String = "existing-1") = SourceRecord(id, first.sourceType, first.sourceAccountKey,
        first.fields + ("state" to "posted"), coverages = listOf(first.coverage), bindingKeys = first.bindingKeys.toSet(),
        fileOccurrences = mapOf(first.fileScopeKey to setOf(first.occurrenceKey)))

    @Test fun retiredShortHashUsesStableOccurrenceAndPreservesIdenticalPhysicalRows() {
        val item = cases().single { it.getValue("calls").jsonArray.size == 1 }
        val original = payload(item.getValue("calls").jsonArray.single().jsonObject)
        val before = sourceJson(original).toString()
        val single = CashSourceRows.sourceRows(file("original", listOf(original))).values.single()
        assertEquals(listOf(SourceResolution(single.occurrenceKey, "new", bind = true)),
            CashSourceResolver.resolveSourceFile(listOf(single), emptyList()))
        val firstFile = file("first", listOf(original, original))
        val rows = CashSourceRows.sourceRows(firstFile).values.toList()
        assertEquals(2, rows.map { it.occurrenceKey }.toSet().size)
        assertEquals(listOf(SourceResolution(rows[0].occurrenceKey, "new", bind = true),
            SourceResolution(rows[1].occurrenceKey, "new", bind = true)), CashSourceResolver.resolveSourceFile(rows, emptyList()))
        val retry = file("retry", listOf(original, original))
        val repeated = CashSourceRows.sourceRows(retry).values.toList()
        assertEquals(rows.map { it.occurrenceKey }, repeated.map { it.occurrenceKey })
        val stored = listOf(old(rows.first()))
        val storedBefore = sourceJson(stored.single().fields).toString()
        assertEquals(listOf(SourceResolution(repeated[0].occurrenceKey, "duplicate", "existing-1", bind = true),
            SourceResolution(repeated[1].occurrenceKey, "new", bind = true)), CashSourceResolver.resolveSourceFile(repeated, stored))
        val batch = CashSourceBatch.resolve(CashSourceRows.sourceBatchRows(listOf(firstFile, retry)), emptyList())
        assertEquals(SourceBatchPlan(listOf(SourceBatchResolution("first:r1", "new", bind = true),
            SourceBatchResolution("first:r2", "new", bind = true),
            SourceBatchResolution("retry:r1", "batch_duplicate", bind = true, duplicateOf = "first:r1"),
            SourceBatchResolution("retry:r2", "batch_duplicate", bind = true, duplicateOf = "first:r2"))), batch)
        assertEquals("duplicate_source_locator", assertFailsWith<IllegalArgumentException> {
            CashSourceResolver.resolveSourceFile(listOf(single, single), emptyList())
        }.message)
        assertEquals(before, sourceJson(original).toString())
        assertEquals(storedBefore, sourceJson(stored.single().fields).toString())
    }

    @Test fun retiredContentHashPreservesFileOwnerAndDistinguishesPositionsFromPayloadChanges() {
        val item = cases().single { it.getValue("calls").jsonArray.size == 3 }
        val calls = item.getValue("calls").jsonArray.map { it.jsonObject }
        assertEquals(listOf("account_a", "account_b", "balance_b"), calls.map { it.getValue("binding").jsonPrimitive.content })
        val inputs = calls.map(::payload)
        val before = sourceJson(inputs).toString()
        assertEquals("1614020101021984636", inputs[0]["账号"])
        assertEquals("1614020101021984637", inputs[1]["账号"])
        assertEquals("15,851.26", calls[2].getValue("row").jsonArray[9].jsonPrimitive.content)
        assertEquals("13,851.26", calls[2].getValue("original_partial_payload").jsonObject.getValue("余额").jsonPrimitive.content)
        assertEquals("13,851.26", inputs[2]["余额"])
        val together = file("multi", inputs + inputs.first())
        val rows = CashSourceRows.sourceRows(together).values.toList()
        assertEquals(1, rows.map { it.fields.filterKeys { key -> key != "source_payload" } }.distinct().size)
        assertEquals(4, rows.map { it.occurrenceKey }.toSet().size)
        assertEquals(rows.map { SourceResolution(it.occurrenceKey, "new", bind = true) },
            CashSourceResolver.resolveSourceFile(rows, emptyList()))
        assertEquals(SourceBatchPlan((1..4).map { SourceBatchResolution("multi:r$it", "new", bind = true) }),
            CashSourceBatch.resolve(CashSourceRows.sourceBatchRows(listOf(together)), emptyList()))
        val stored = listOf(old(rows.first()))
        val storedBefore = sourceJson(stored.single().fields).toString()
        assertEquals(listOf(SourceResolution(rows[0].occurrenceKey, "duplicate", "existing-1", bind = true)) +
            rows.drop(1).map { SourceResolution(it.occurrenceKey, "new", bind = true) },
            CashSourceResolver.resolveSourceFile(rows, stored))
        val retry = file("retry", inputs + inputs.first())
        val repeatedBatch = CashSourceRows.sourceBatchRows(listOf(together, retry))
        assertEquals(SourceBatchPlan((1..4).map { SourceBatchResolution("multi:r$it", "new", bind = true) } +
            (1..4).map { SourceBatchResolution("retry:r$it", "batch_duplicate", bind = true, duplicateOf = "multi:r$it") }),
            CashSourceBatch.resolve(repeatedBatch, emptyList()))
        val saved = rows.mapIndexed { index, row -> old(row, "existing-${index + 1}") }
        assertEquals(SourceBatchPlan((1..4).map { SourceBatchResolution("multi:r$it", "duplicate", "existing-$it", bind = true) } +
            (1..4).map { SourceBatchResolution("retry:r$it", "batch_duplicate", "existing-$it", bind = true, duplicateOf = "multi:r$it") }),
            CashSourceBatch.resolve(repeatedBatch, saved))
        for (changedPayload in inputs.drop(1)) {
            val changedFile = file("changed", listOf(changedPayload))
            val changed = CashSourceRows.sourceRows(changedFile).values.single()
            assertEquals(rows.first().occurrenceKey, changed.occurrenceKey)
            assertNotEquals(rows.first().fields["source_payload"], changed.fields["source_payload"])
            assertEquals(listOf(SourceResolution(changed.occurrenceKey, "match", "existing-1", bind = true)),
                CashSourceResolver.resolveSourceFile(listOf(changed), stored))
            val firstFile = file("first", listOf(inputs.first()))
            assertEquals(SourceBatchPlan(listOf(SourceBatchResolution("first:r1", "conflict", reason = "batch_identity_conflict"),
                SourceBatchResolution("changed:r1", "conflict", reason = "batch_identity_conflict"))),
                CashSourceBatch.resolve(CashSourceRows.sourceBatchRows(listOf(firstFile, changedFile)), emptyList()))
            assertEquals(SourceBatchPlan(listOf("first:r1", "changed:r1").map {
                SourceBatchResolution(it, "conflict", candidateIds = listOf("existing-1"), reason = "batch_identity_conflict") }),
                CashSourceBatch.resolve(CashSourceRows.sourceBatchRows(listOf(firstFile, changedFile)), stored))
        }
        for (input in inputs) {
            val otherFile = file("other", listOf(input)).copy(digest = "b".repeat(64))
            val incoming = CashSourceRows.sourceRows(otherFile).values.single()
            assertNotEquals(rows.first().occurrenceKey, incoming.occurrenceKey)
            assertEquals(listOf(SourceResolution(incoming.occurrenceKey, "pending", candidateIds = listOf("existing-1"),
                reason = "source_identity_ambiguous")), CashSourceResolver.resolveSourceFile(listOf(incoming), stored))
        }
        assertEquals(rows.first().fields + ("state" to "posted"), stored.single().fields)
        assertEquals(before, sourceJson(inputs).toString())
        assertEquals(storedBefore, sourceJson(stored.single().fields).toString())
    }
}
