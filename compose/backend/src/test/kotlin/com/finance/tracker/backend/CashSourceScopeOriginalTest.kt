package com.finance.tracker.backend

import java.security.MessageDigest
import kotlin.test.*

class CashSourceScopeOriginalTest {
    private fun file(label: String? = null, nickname: String = "昵称甲", owner: String? = null): ParsedImportFile {
        val digest = MessageDigest.getInstance("SHA-256").digest(nickname.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val fields = mapOf("amount" to "-20.00", "currency" to "CNY", "occurred_at" to "2026-08-15T02:00:00+00:00",
            "counterparty" to "商户", "note" to "原始商品", "record_type" to "consumption", "record_subtype" to "not_applicable",
            "source_payload" to mapOf("交易单号" to "000123", "备注" to "原始商品"))
        val document = ParsedCashDocument("wechat", "sheet:0", "platform-rows-v1+native-v1",
            listOf(ParsedCashRow(18, fields, "零钱", "000123")), owner, nickname,
            CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1"), 1, 1)
        return ParsedImportFile("file_1", digest, "parsed", document, label,
            clientFileRef = "client_1", filename = "synthetic.xlsx", size = 100, sourceType = "wechat")
    }
    private fun rows(file: ParsedImportFile, resolved: Map<Int, Map<String, Any?>>? = null) = CashSourceRows.sourceRows(file, resolved)

    @Test fun test_default_scope_is_stable_across_nickname_raw_owner_and_internal_mapping() {
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
    @Test fun test_named_scope_isolation_includes_same_file_occurrence_alias() {
        val first = rows(file(label = "工作微信")).values.single()
        val second = rows(file(label = "个人微信")).values.single()
        val default = rows(file()).values.single()
        assertEquals(3, setOf(first.providerBindingKey, second.providerBindingKey, default.providerBindingKey).size)
        assertTrue(first.bindingKeys.toSet().intersect(second.bindingKeys.toSet()).isEmpty())
        assertNotEquals(first.occurrenceKey, second.occurrenceKey)
    }
    @Test fun test_scope_names_are_normalized_and_cannot_collide_with_default_namespace() {
        assertEquals("café", CashSourceScope.normalizeScopeLabel("  cafe\u0301  "))
        assertEquals(CashSourceScope.sourceAccountKey("wechat", scopeLabel = "  cafe\u0301  "),
            CashSourceScope.sourceAccountKey("wechat", scopeLabel = "café"))
        assertNotEquals(CashSourceScope.WECHAT_DEFAULT_SCOPE, CashSourceScope.sourceAccountKey("wechat", scopeLabel = "default"))
        assertEquals(CashSourceScope.WECHAT_DEFAULT_SCOPE, CashSourceScope.sourceAccountKey("wechat", scopeLabel = "  "))
        assertEquals("fixture@example.test", CashSourceScope.sourceAccountKey("alipay", "fixture@example.test"))
        assertNull(CashSourceScope.sourceAccountKey("ccb_debit"))
    }
    @Test fun originalRepresentableInvalidLabelsKeepSafeDomainError() {
        for (label in listOf("x".repeat(65), "\n工作", "工作\t微信", "\u0000", "\ud800")) {
            val error = assertFailsWith<CashSourceScopeError> { CashSourceScope.normalizeScopeLabel(label) }
            assertEquals("source_scope_invalid", error.code)
            assertContains(error.message.orEmpty(), "source_scope_invalid")
        }
    }
    @Test fun protocolRejectsNumericAndListLabelsBeforeDomainInput() {
        for (label in listOf("1", "[]")) {
            val error = assertFailsWith<CashImportProtocolError> {
                CashImportProtocol.scopeInput("""{"expected_session_revision":1,"source_scope_label":$label}""")
            }
            assertEquals("import_payload_invalid", error.code)
        }
    }
    @Test fun test_setting_other_channel_scope_is_rejected() {
        val error = assertFailsWith<CashSourceScopeError> {
            CashSourceScope.sourceAccountKey("alipay", "fixture@example.test", scopeLabel = "工作")
        }
        assertContains(error.message.orEmpty(), "source_scope_unsupported")
    }
    @Test fun test_bridge_preserves_original_rows_and_ignores_unreadable_or_skipped_rows() {
        val original = file()
        val before = original.document!!.rows[0].fields!!.toMap()
        val file = original.copy(document = original.document.copy(rows = original.document.rows +
            ParsedCashRow(19, null, errorCode = "row_unreadable", disposition = "skipped")))
        val result = rows(file, mapOf(18 to mapOf("account_id" to "101", "note" to "本次选择")))
        assertEquals(listOf("file_1:r18"), result.keys.toList())
        assertEquals("本次选择", result.getValue("file_1:r18").fields["note"])
        assertEquals(before, file.document!!.rows[0].fields)
    }
    @Test fun test_same_number_reimports_update_and_fixed_conflict_under_default_scope() {
        val first = rows(file()).values.single()
        val old = SourceRecord("1001", "wechat", first.sourceAccountKey, mapOf("state" to "posted") + first.fields,
            bindingKeys = first.bindingKeys.toSet())
        fun resolve(value: SourceOccurrence) = CashSourceResolver.resolveSourceFile(listOf(value), listOf(old))
        assertEquals("duplicate", resolve(first)[0].action)
        val changed = first.copy(fileDigest = "b".repeat(64), fields = first.fields + ("counterparty" to "新名称"))
        assertEquals("match", resolve(changed)[0].action)
        for (patch in listOf(mapOf("amount" to "-21"), mapOf("amount" to "20"), mapOf("occurred_at" to "2026-08-16T02:00:00Z"))) {
            val result = resolve(changed.copy(fields = changed.fields + patch)).single()
            assertTrue(result.action == "conflict" && result.transactionId == "1001")
        }
        val other = rows(file(label = "另一个微信")).values.single()
        assertEquals("new", resolve(other)[0].action)
    }
    @Test fun test_multi_file_bridge_preserves_session_refs_and_resolved_fields_without_rereading() {
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
