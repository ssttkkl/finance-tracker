package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.MathContext
import kotlin.test.*

class CashImportFundingOriginalTest {
    private fun file(source: String = "alipay", methods: List<String> = listOf("招商银行储蓄卡(1234)"),
        amount: String? = "-30", owner: String? = "owner", label: String? = null, ref: String = "a",
        issues: List<String> = emptyList(), product: String = ""): ParsedImportFile {
        val names = mapOf("icbc_credit" to "工商银行信用卡", "icbc_debit" to "工商银行借记卡",
            "ccb_debit" to "建设银行", "icbc_asia" to "工银亚洲")
        val rows = methods.mapIndexed { i, method -> ParsedCashRow(i + 1,
            mapOf("amount" to amount, "currency" to "CNY", "occurred_at" to "2026-10-01T10:00:00+00:00",
                "record_type" to "consumption", "record_subtype" to "not_applicable", "note" to "  原始备注  ",
                "source_payload" to mapOf("付款方式" to method, "商品说明" to product)), method, issueCodes = issues,
            sourceExplanation = names[source]?.let { mapOf("source_display_name" to
                (it + if (!owner.isNullOrEmpty()) "(${owner.takeLast(4)})" else "")) } ?: emptyMap()) }
        return ParsedImportFile(ref, ref.repeat(64), "parsed", ParsedCashDocument(source, "table", "native-v1", rows,
            owner, owner, CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1"), rows.size, rows.size),
            sourceScopeLabel = label, clientFileRef = ref, filename = "fixture.csv", size = 100, sourceType = source)
    }
    private fun scan(vararg files: ParsedImportFile) = CashImportFunding.scan(files.toList())
    private fun accounts(scan: CashFundingScan) = scan.groups.indices.associate { i -> (i + 1).toString() to
        mapOf("id" to (i + 1).toString(), "revision" to 3, "active" to true, "type" to "cash", "currencies" to listOf("CNY")) }
    private fun resolve(scan: CashFundingScan, selections: Any? = scan.groups.mapIndexed { i, g -> g.sourceAccountRef to
        mapOf("account_id" to (i + 1).toString(), "expected_account_revision" to 3) }.toMap(),
        accounts: Any? = accounts(scan)) = CashImportFunding.resolve(scan, selections, accounts)
    private fun CashFundingResolution.components() = (fields["components"] as List<*>).map { it as Map<*, *> }
    private fun rows(file: ParsedImportFile, rows: List<ParsedCashRow>) = file.copy(document = file.document!!.copy(rows = rows))

    @Test fun test_missing_mapping_keeps_known_values_and_does_not_block_mapped_row() {
        val scan = scan(file(methods = listOf("银行卡(1234)", "余额")))
        val results = resolve(scan, selections = mapOf(scan.groups[1].sourceAccountRef to
            mapOf("account_id" to "2", "expected_account_revision" to 3)))
        assertEquals(2, results.size)
        val (first, second) = results
        assertTrue(!first.ready && first.fields["account_id"] == null && "account_missing" in first.issueCodes)
        assertTrue(first.fields["amount"] == "-30" && first.components()[0]["amount"] == "-30")
        assertTrue(second.ready && second.fields["account_id"] == "2")
    }
    @Test fun test_unknown_composite_allocation_keeps_one_parent_and_each_known_account() {
        val scan = scan(file(methods = listOf("银行卡(1234)&余额&红包")))
        assertEquals(2, scan.groups.size)
        val row = resolve(scan).single()
        assertTrue(!row.ready && row.fields["cash_granularity"] == "aggregate" && row.fields["account_id"] == null)
        assertEquals(listOf(null, null), row.components().map { it["amount"] })
        assertEquals(listOf("1", "2"), row.components().map { it["account_id"] })
        assertTrue("allocation_missing" in row.issueCodes && row.fields["amount"] == "-30")
    }
    @Test fun test_explicit_allocations_are_signed_and_amount_is_not_part_of_group_identity() {
        val scan = scan(file(methods = listOf("银行卡(1234)10元&余额20元", "银行卡(1234)20元&余额10元")))
        assertEquals(2, scan.groups.size)
        val rows = resolve(scan)
        assertTrue(rows.all { it.ready })
        assertEquals(listOf("-10", "-20"), rows[0].components().map { it["amount"] })
        assertEquals(rows[0].groupRefs, rows[1].groupRefs)
    }
    @Test fun test_repeated_funding_account_is_one_component() {
        for (method in listOf("银行卡(1234)&银行卡(1234)", "银行卡(1234)10元&银行卡(1234)20元")) {
            val row = resolve(scan(file(methods = listOf(method)))).single()
            assertTrue(row.ready && row.fields["cash_granularity"] == "atomic")
            assertTrue(row.components().size == 1 && row.components()[0]["amount"] == "-30")
        }
    }
    @Test fun test_two_source_labels_mapped_to_one_internal_account_need_no_allocation() {
        val scan = scan(file(methods = listOf("银行卡(1234)&余额")))
        val selections = scan.groups.associate { it.sourceAccountRef to mapOf("account_id" to "1", "expected_account_revision" to 3) }
        val row = resolve(scan, selections = selections).single()
        assertTrue(row.ready && row.fields["cash_granularity"] == "atomic" && row.components().size == 1)
        assertTrue(row.fields["account_id"] == "1" && row.components()[0]["amount"] == "-30")
    }
    @Test fun test_explicit_amount_conflicts_remain_drafts() {
        for (method in listOf("银行卡(1234)10元&余额10元", "银行卡(1234)40元&余额", "银行卡(1234)31元")) {
            val row = resolve(scan(file(methods = listOf(method)))).single()
            assertTrue(!row.ready && "component_total_mismatch" in row.issueCodes)
        }
    }
    @Test fun test_partial_allocation_is_not_filled_from_the_remainder() {
        val row = resolve(scan(file(methods = listOf("银行卡(1234)10元&余额")))).single()
        assertEquals(listOf("-10", null), row.components().map { it["amount"] })
        assertTrue(!row.ready && "allocation_missing" in row.issueCodes)
    }
    @Test fun test_missing_alipay_funding_defaults_to_wallet_for_any_direction() {
        for (method in listOf("红包&优惠券", "", " & ", "30元")) for (amount in listOf("0", null, "-10", "10")) {
            val file = file(methods = listOf(method), amount = amount)
            val originalPayload = CashRecordPolicy.copyValue(file.document!!.rows[0].fields!!["source_payload"])
            val scan = scan(file)
            val group = scan.groups.single()
            val row = resolve(scan).single()
            assertEquals("支付宝余额", group.displayName)
            assertEquals(listOf(group.sourceAccountRef), row.groupRefs)
            assertFalse("funding_unrecognized" in row.issueCodes)
            assertEquals("1", row.components()[0]["account_id"])
            assertEquals(originalPayload, file.document.rows[0].fields!!["source_payload"])
        }
    }
    @Test fun test_yuebao_product_description_is_the_specific_missing_account_fallback() {
        for (amount in listOf("0", "-12", "12", null)) {
            val file = file(methods = listOf(""), amount = amount, product = "余额宝-2025.06.19-收益发放")
            val scan = scan(file)
            val group = scan.groups.single()
            val row = resolve(scan).single()
            assertEquals("余额宝", group.displayName)
            assertEquals(listOf(group.sourceAccountRef), row.groupRefs)
            assertFalse("funding_unrecognized" in row.issueCodes)
            assertEquals("余额宝-2025.06.19-收益发放", (file.document!!.rows[0].fields!!["source_payload"] as Map<*, *>)["商品说明"])
        }
    }
    @Test fun test_identified_payment_account_takes_precedence_over_yuebao_description() {
        val group = scan(file(methods = listOf("招商银行储蓄卡(1234)"), product = "余额宝收益发放")).groups.single()
        assertEquals("招商银行储蓄卡(1234)", group.displayName)
    }
    @Test fun test_unknown_alipay_direction_still_gets_wallet_fallback_without_hiding_direction_issue() {
        val scan = scan(file(methods = listOf(""), issues = listOf("direction_unrecognized")))
        val row = resolve(scan).single()
        assertEquals("支付宝余额", scan.groups[0].displayName)
        assertFalse("funding_unrecognized" in row.issueCodes)
        assertTrue("direction_unrecognized" in row.issueCodes)
    }
    @Test fun test_missing_wechat_funding_is_a_row_issue_not_an_account_mapping_group() {
        val scan = scan(file(source = "wechat", methods = listOf(""), owner = null))
        val row = resolve(scan, selections = emptyMap<String, Any?>()).single()
        assertTrue(scan.groups.isEmpty() && row.groupRefs.isEmpty())
        assertTrue(!row.ready && "funding_unrecognized" in row.issueCodes)
        assertNull(row.components()[0]["account_id"])
    }
    @Test fun test_amount_without_a_funding_name_uses_wallet_fallback() {
        val scan = scan(file(methods = listOf("30元")))
        val group = scan.groups.single()
        val row = resolve(scan).single()
        assertEquals("支付宝余额", group.displayName)
        assertEquals(listOf(group.sourceAccountRef), row.groupRefs)
        assertFalse("funding_unrecognized" in row.issueCodes)
        assertEquals("1", row.components()[0]["account_id"])
    }
    @Test fun test_known_and_unidentified_payment_parts_keep_separate_preview_components() {
        val scan = scan(file(methods = listOf("银行卡(1234)&30元")))
        assertTrue(scan.groups.size == 1 && scan.groups[0].displayName == "银行卡(1234)")
        val row = resolve(scan).single()
        assertEquals(listOf(scan.groups[0].sourceAccountRef), row.groupRefs)
        assertTrue(!row.ready && "funding_unrecognized" in row.issueCodes && "allocation_missing" in row.issueCodes)
        assertEquals(listOf("1", null), row.components().map { it["account_id"] })
        assertEquals(listOf(null, "-30"), row.components().map { it["amount"] })
    }
    @Test fun test_bank_group_uses_the_display_name_prepared_by_its_channel_converter() {
        val group = scan(file("ccb_debit", listOf("ignored by bank group identity"), owner = "6217000000000000001")).groups.single()
        assertEquals("建设银行(0001)", group.displayName)
    }
    @Test fun test_bank_labels_with_missing_card_type_remain_distinct_from_full_card_labels() {
        val scan = scan(file(methods = listOf("工商银行(3697)", "工商银行信用卡(3697)")))
        assertEquals(2, scan.groups.size)
        assertEquals(listOf("工商银行(3697)", "工商银行信用卡(3697)"), scan.groups.map { it.displayName })
    }
    @Test fun test_government_subsidy_is_not_a_funding_group_or_component_and_raw_text_survives() {
        val method = "工商银行信用卡(1200)&政府补贴"
        val file = file(methods = listOf(method))
        val group = scan(file).groups.single()
        val row = resolve(scan(file)).single()
        assertEquals("工商银行信用卡(1200)", group.displayName)
        assertEquals(1, row.components().size)
        assertEquals(method, (file.document!!.rows[0].fields!!["source_payload"] as Map<*, *>)["付款方式"])
    }
    @Test fun test_zero_payment_does_not_treat_promotion_face_value_as_funding() {
        for (method in listOf("优惠券10元", "红包5元&优惠券5元")) {
            val row = resolve(scan(file(methods = listOf(method), amount = "0"))).single()
            assertTrue(row.ready && row.components()[0]["amount"] == "0")
        }
    }
    @Test fun test_unavailable_account_only_affects_its_rows() {
        val patches = listOf(mapOf("active" to false) to "account_unavailable", mapOf("type" to "security") to "account_unavailable",
            mapOf("currencies" to listOf("USD")) to "account_currency_missing")
        for ((patch, code) in patches) {
            val scan = scan(file(methods = listOf("银行卡(1234)", "余额")))
            val accounts = accounts(scan).toMutableMap()
            accounts["1"] = accounts.getValue("1") + patch
            val rows = resolve(scan, accounts = accounts)
            assertTrue(!rows[0].ready && code in rows[0].issueCodes && rows[1].ready)
        }
    }
    @Test fun test_new_account_revision_revalidates_current_eligibility_without_reselection() {
        val row = resolve(scan(file()), accounts = mapOf("1" to mapOf("id" to "1", "revision" to 4,
            "active" to true, "type" to "cash", "currencies" to listOf("CNY", "USD")))).single()
        assertTrue(row.ready && row.fields["account_id"] == "1" && row.issueCodes.isEmpty())
    }
    @Test fun test_wechat_default_scope_reuses_groups_but_named_sources_are_isolated() {
        val scan = scan(file("wechat", listOf("零钱"), owner = null, ref = "a"),
            file("wechat", listOf("零钱"), owner = "nickname", ref = "b"),
            file("wechat", listOf("零钱"), owner = null, label = "工作", ref = "c"))
        assertEquals(2, scan.groups.size)
        assertTrue(scan.rows[0].groupRefs == scan.rows[1].groupRefs && scan.rows[1].groupRefs != scan.rows[2].groupRefs)
    }
    @Test fun test_bank_uses_complete_owner_and_never_collapses_identical_card_tails() {
        val scan = scan(file("ccb_debit", listOf("建设银行"), owner = "6212000000001234", ref = "a"),
            file("ccb_debit", listOf("建设银行"), owner = "6222000000001234", ref = "b"))
        assertEquals(2, scan.groups.size)
        assertTrue(scan.groups.all { "6212000000001234" !in it.displayName && "6222000000001234" !in it.displayName })
        assertNotEquals(scan.rows[0].groupRefs, scan.rows[1].groupRefs)
    }
    @Test fun test_unknown_bank_owner_is_file_local_and_credit_uses_each_row_owner() {
        val first = file("icbc_credit", listOf("信用卡", "信用卡"), owner = null)
        val document = first.document!!.copy(rows = first.document.rows.zip(listOf("6222000000001234", "6222000000005678"))
            .map { (row, owner) -> row.copy(sourceAccountIdentifier = owner) })
        val scan = scan(first.copy(document = document), file("icbc_debit", listOf("借记卡"), owner = null, ref = "b"),
            file("icbc_debit", listOf("借记卡"), owner = null, ref = "c"))
        assertEquals(4, scan.groups.size)
    }
    @Test fun test_distinct_masked_credit_cards_are_separate_local_mapping_groups() {
        val file = file("icbc_credit", listOf("信用卡", "信用卡"), owner = null)
        val rows = file.document!!.rows.zip(listOf("6222****1234", "6222****5678")).map { (row, masked) ->
            row.copy(fields = row.fields!! + ("source_payload" to mapOf("交易卡号" to masked))) }
        val scan = scan(rows(file, rows))
        assertTrue(scan.groups.size == 2 && scan.rows[0].groupRefs != scan.rows[1].groupRefs)
        assertTrue(scan.groups.all { it.identityKind == "file_local" })
    }
    @Test fun test_financial_precision_and_source_text_survive_low_decimal_context() {
        val amount = "12345678901234567890.123456789012345678"
        val file = file(methods = listOf("银行卡(1234)"), amount = "-$amount")
        val before = file.copy(document = file.document!!.copy(rows = file.document.rows.map {
            @Suppress("UNCHECKED_CAST")
            it.copy(fields = CashRecordPolicy.copyValue(it.fields) as Map<String, Any?>)
        }))
        val rounded = BigDecimal("-$amount", MathContext(6))
        val row = resolve(scan(file)).single()
        val actual = BigDecimal(row.components()[0]["amount"] as String)
        assertTrue(row.ready && actual.compareTo(BigDecimal("-$amount")) == 0)
        assertNotEquals(0, actual.compareTo(rounded))
        assertEquals(before, file)
        assertEquals("  原始备注  ", row.fields["note"])
        assertEquals(file.document.rows[0].fields!!["source_payload"], row.fields["source_payload"])
    }
    @Test fun test_invalid_allocation_tokens_are_not_silently_replaced_by_parent() {
        for (method in listOf("银行卡(1234)¥1e3", "银行卡(1234)-10元", "银行卡(1234)10元20元")) {
            val row = resolve(scan(file(methods = listOf(method)))).single()
            assertTrue(!row.ready && "allocation_amount_invalid" in row.issueCodes)
        }
    }
    @Test fun test_installment_is_a_base_account_and_does_not_create_a_new_account() {
        val scan = scan(file(methods = listOf("招商银行信用卡分期(1234)", "招商银行信用卡(1234)")))
        assertTrue(scan.groups.size == 1 && resolve(scan).all { it.ready })
    }
    @Test fun test_wallet_base_identity_is_the_same_with_or_without_explicit_amount() {
        val scan = scan(file(methods = listOf("余额", "余额30元", "账户余额30元", "支付宝余额30元")))
        assertTrue(scan.groups.size == 1 && scan.groups[0].displayName == "支付宝余额")
        assertTrue(resolve(scan).all { it.ready })
    }
    @Test fun test_allocation_wrapping_and_spaces_do_not_create_a_different_wallet() {
        val methods = listOf("余额", "余额( 30 元 )", "余额【 30元 】").map { value ->
            prepareCashSourceEffects(mapOf("source_payload" to mapOf("收/付款方式" to value)),
                mapOf("source_payload" to mapOf("收/付款方式" to value)), "alipay", emptyMap<String, Any?>())
                .explanation!!.getValue("payment_method") as String }
        val scan = scan(file(methods = methods))
        assertTrue(scan.groups.size == 1 && resolve(scan).all { it.ready })
    }
    @Test fun test_group_labels_mask_formatted_full_account_numbers() {
        for ((source, method) in listOf("wechat" to "银行卡(6222 1234 5678 9012)",
            "wechat" to "银行卡(6222-1234-5678-9012)", "alipay" to "银行卡(6222-1234-5678-9012)")) {
            val group = scan(file(source, listOf(method))).groups.single()
            val digits = group.displayName.filter { it.isDigit() }
            assertTrue("6222123456789012" !in digits && "9012" in digits)
        }
    }
    @Test fun test_unreadable_row_isolated_and_skipped_file_does_not_create_a_fake_group() {
        val original = file(methods = listOf("余额", "银行卡(1234)"))
        val bad = original.document!!.rows[0].copy(fields = null, disposition = "skipped", errorCode = "row_unreadable")
        val file = rows(original, listOf(bad, original.document.rows[1]))
        val scan = scan(file, file.copy(fileRef = "b", clientFileRef = "b", status = "failed", document = null, errorCode = "statement_unreadable"))
        assertTrue(scan.rows.size == 1 && scan.groups.size == 1 && resolve(scan)[0].ready)
    }
    @Test fun test_invalid_row_does_not_leave_unused_source_account_groups() {
        val file = file(methods = listOf("异常账户", "余额"))
        val (first, second) = file.document!!.rows
        val scan = scan(rows(file, listOf(first.copy(fields = first.fields!! + ("currency" to listOf("invalid"))), second)))
        assertTrue(scan.groups.size == 1 && scan.groups[0].rowRefs == listOf("a:r2"))
        assertEquals("funding_unreadable", scan.rows[0].errorCode)
    }
    @Test fun test_too_many_payment_parts_only_fails_that_row() {
        val scan = scan(file(methods = listOf(List(65) { "余额" }.joinToString("&"), "余额")))
        val results = resolve(scan)
        assertEquals(2, results.size)
        val (first, second) = results
        assertTrue(first.errorCode == "funding_unreadable" && second.ready)
    }
    @Test fun test_selection_structure_is_strict() {
        val choices = listOf(mapOf("account_id" to 1, "expected_account_revision" to 3),
            mapOf("account_id" to "1", "expected_account_revision" to true), mapOf("account_id" to "1"),
            mapOf("account_id" to null, "expected_account_revision" to 3), mapOf("arbitrary" to "value"), emptyList<Any?>())
        for (choice in choices) {
            val scan = scan(file())
            assertFailsWith<CashFundingInputError> { resolve(scan, selections = mapOf(scan.groups[0].sourceAccountRef to choice)) }
        }
    }
    @Test fun test_a_selection_for_another_scan_is_not_applied_to_current_rows() {
        assertFailsWith<CashFundingInputError> { resolve(scan(file()), selections = mapOf("other" to
            mapOf("account_id" to "1", "expected_account_revision" to 3))) }
    }
}
