package com.finance.tracker.backend

import java.security.MessageDigest
import kotlin.test.*

class CashSourceScopeOriginalTest {
    private fun file(nickname: String = "昵称甲", owner: String? = null): ParsedImportFile {
        val digest = MessageDigest.getInstance("SHA-256").digest(nickname.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val fields = mapOf("amount" to "-20.00", "currency" to "CNY", "occurred_at" to "2026-08-15T02:00:00+00:00",
            "counterparty" to "商户", "note" to "原始商品", "record_type" to "consumption", "record_subtype" to "not_applicable",
            "source_payload" to mapOf("交易单号" to "000123", "备注" to "原始商品"))
        val document = ParsedCashDocument("wechat", "sheet:0", "platform-rows-v1+native-v1",
            listOf(ParsedCashRow(18, fields, "零钱", "000123")), owner, nickname,
            CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1"), 1, 1, nickname)
        return ParsedImportFile("file_1", digest, "parsed", document,
            clientFileRef = "client_1", filename = "synthetic.xlsx", size = 100, sourceType = "wechat")
    }
    private fun alipayFile(owner: String, ref: String, digest: String, providerId: String = "000123",
        amount: String = "-20.00"): ParsedImportFile {
        val fields = mapOf("amount" to amount, "currency" to "CNY", "occurred_at" to "2026-08-15T02:00:00+00:00",
            "counterparty" to "商户", "note" to "原始说明", "record_type" to "consumption", "record_subtype" to "not_applicable",
            "source_payload" to mapOf("交易订单号" to providerId))
        val document = ParsedCashDocument("alipay", "csv:0", "platform-rows-v1+native-v1",
            listOf(ParsedCashRow(18, fields, "银行卡", providerId)), owner, owner,
            CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1"), 1, 1)
        return ParsedImportFile(ref, digest, "parsed", document,
            clientFileRef = "client-$ref", filename = "$ref.csv", size = 100, sourceType = "alipay")
    }
    private fun rows(file: ParsedImportFile, resolved: Map<Int, Map<String, Any?>>? = null) = CashSourceRows.sourceRows(file, resolved)
    private fun batch(rows: Map<String, SourceOccurrence>) =
        SourceBatchRows(LinkedHashMap(rows), emptyList(), emptyList(), emptyMap())

    @Test fun wechatIdentityIsGlobalAcrossNicknameRawOwnerAndAccountMapping() {
        val first = rows(file()).values.single()
        val second = rows(file(nickname = "昵称乙", owner = "actual-account")).values.single()
        val mapped = rows(file(), mapOf(18 to mapOf("account_id" to "other-account"))).values.single()
        assertEquals(CashSourceScope.WECHAT_DEFAULT_SCOPE, first.sourceAccountKey)
        assertEquals(first.sourceAccountKey, second.sourceAccountKey)
        assertEquals(second.sourceAccountKey, mapped.sourceAccountKey)
        assertEquals(first.providerBindingKey, second.providerBindingKey)
        assertEquals(second.providerBindingKey, mapped.providerBindingKey)
        assertEquals(second.fields["source_payload"], first.fields["source_payload"])
    }

    @Test fun sameTransactionNumberFromAnotherWechatAccountMatchesExistingRecord() {
        val first = rows(file(nickname = "家庭号", owner = "masked-1")).values.single()
        val second = rows(file(nickname = "工作号", owner = "masked-2")).values.single()
        val old = SourceRecord("1001", "wechat", first.sourceAccountKey, mapOf("state" to "posted") + first.fields,
            bindingKeys = first.bindingKeys.toSet())
        fun resolve(value: SourceOccurrence) = CashSourceResolver.resolveSourceFile(listOf(value), listOf(old))
        assertEquals("duplicate", resolve(second)[0].action)
        assertEquals("1001", resolve(second)[0].transactionId)
        val changed = second.copy(fields = second.fields + ("counterparty" to "新名称"))
        assertEquals("match", resolve(changed)[0].action)
        assertEquals("1001", resolve(changed)[0].transactionId)
    }

    @Test fun alipayMatchingScopeIsGlobalWhileFundingAccountScopeStaysSeparate() {
        assertEquals("fixture@example.test", CashSourceScope.sourceAccountKey("alipay", "fixture@example.test"))
        assertEquals("other@example.test", CashSourceScope.sourceAccountKey("alipay", "other@example.test"))
        assertNull(CashSourceScope.sourceAccountKey("ccb_debit"))
        assertEquals("bank-account-1", CashSourceScope.sourceTransactionScopeKey("ccb_debit", "bank-account-1"))
        assertEquals("bank-account-2", CashSourceScope.sourceTransactionScopeKey("ccb_debit", "bank-account-2"))
        val first = rows(alipayFile("fixture@example.test", "alipay-1", "a".repeat(64))).values.single()
        val second = rows(alipayFile("other@example.test", "alipay-2", "b".repeat(64))).values.single()
        assertEquals(first.sourceAccountKey, second.sourceAccountKey)
        assertEquals(first.providerBindingKey, second.providerBindingKey)
    }

    @Test fun alipayOrderNumberMatchesAcrossSourceAccountsAndConflictsOnFixedFields() {
        val first = rows(alipayFile("masked-account-1", "file-1", "a".repeat(64))).values.single()
        val second = rows(alipayFile("masked-account-2", "file-2", "b".repeat(64))).values.single()
        val old = SourceRecord("1001", "alipay", first.sourceAccountKey, mapOf("state" to "posted") + first.fields,
            bindingKeys = first.bindingKeys.toSet(), providerId = first.providerId)
        fun resolve(value: SourceOccurrence) = CashSourceResolver.resolveSourceFile(listOf(value), listOf(old))
        assertEquals("duplicate", resolve(second)[0].action)
        assertEquals("1001", resolve(second)[0].transactionId)

        val changed = second.copy(fields = second.fields + ("amount" to "-21.00"))
        assertEquals("conflict", resolve(changed)[0].action)
        assertEquals("1001", resolve(changed)[0].transactionId)

        val identical = CashSourceBatch.resolve(batch(mapOf("file-1:r18" to first, "file-2:r18" to second)),
            emptyList()).rows
        assertEquals(listOf("new", "batch_duplicate"), identical.map { it.action })
        val conflict = CashSourceBatch.resolve(batch(mapOf("file-1:r18" to first, "file-2:r18" to changed)),
            emptyList()).rows
        assertEquals(listOf("conflict", "conflict"), conflict.map { it.action })
    }

    @Test fun duplicateAlipayOrderNumberWithinOneFileUsesExistingConflictRule() {
        val first = rows(alipayFile("masked-account", "file-1", "a".repeat(64))).values.single()
        val repeated = first.copy(position = 19)
        val same = CashSourceBatch.resolve(batch(mapOf("file-1:r18" to first, "file-1:r19" to repeated)), emptyList()).rows
        assertEquals(listOf("new", "batch_duplicate"), same.map { it.action })

        val conflicting = repeated.copy(fields = repeated.fields + ("amount" to "-21.00"))
        val conflict = CashSourceBatch.resolve(batch(mapOf("file-1:r18" to first, "file-1:r19" to conflicting)),
            emptyList()).rows
        assertTrue(conflict.all { it.action == "conflict" && it.reason == "batch_identity_conflict" })
    }

    @Test fun bridgePreservesOriginalRowsAndIgnoresUnreadableOrSkippedRows() {
        val original = file()
        val before = original.document!!.rows[0].fields!!.toMap()
        val file = original.copy(document = original.document.copy(rows = original.document.rows +
            ParsedCashRow(19, null, errorCode = "row_unreadable", disposition = "skipped")))
        val result = rows(file, mapOf(18 to mapOf("account_id" to "101", "note" to "本次选择")))
        assertEquals(listOf("file_1:r18"), result.keys.toList())
        assertEquals("本次选择", result.getValue("file_1:r18").fields["note"])
        assertEquals(before, file.document!!.rows[0].fields)
    }

    @Test fun multiFileBridgePreservesSessionRefsAndResolvedFieldsWithoutRereading() {
        val first = file()
        val second = file(nickname = "昵称乙").copy(fileRef = "file_2", clientFileRef = "client_2")
        val failed = first.copy(fileRef = "failed", status = "failed", document = null, errorCode = "file_unreadable")
        val rows = CashSourceRows.sourceBatchRows(listOf(first, second, failed), mapOf(
            "file_1" to mapOf(18 to mapOf("account_id" to "101")), "file_2" to mapOf(18 to mapOf("account_id" to "101"))))
        assertEquals(listOf("file_1:r18", "file_2:r18"), rows.rows.keys.toList())
        assertTrue(rows.rows.values.all { it.fields["account_id"] == "101" })
        assertFalse("account_id" in first.document!!.rows[0].fields!!)
        val plan = CashSourceBatch.resolve(rows, emptyList())
        assertEquals(listOf("new", "batch_duplicate"), plan.rows.map { it.action })
        val error = assertFailsWith<IllegalArgumentException> { CashSourceRows.sourceBatchRows(listOf(first, first)) }
        assertContains(error.message.orEmpty(), "duplicate_source_file_ref")
    }
}
