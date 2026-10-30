package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportFundingTest {
    private fun text(o: JsonObject, key: String) = o[key]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull
    private fun fields(o: JsonObject, key: String) = (o[key] as? JsonObject)?.mapValues { cashSourceValue(it.value) }
    private fun row(o: JsonObject) = CashFundingRow(o.getValue("row_ref").jsonPrimitive.content, fields(o, "fields")!!,
        o.getValue("parts").jsonArray.map { CashFundingPart(text(it.jsonObject, "group_ref"), text(it.jsonObject, "amount")) },
        o.getValue("issue_codes").jsonArray.map { it.jsonPrimitive.content }, text(o, "error_code"))
    private fun scan(o: JsonObject) = CashFundingScan(o.getValue("groups").jsonArray.map { value ->
        val g = value.jsonObject
        CashFundingGroup(text(g, "source_account_ref")!!, text(g, "source_type")!!, text(g, "identity_kind")!!,
            text(g, "origin_key")!!, text(g, "identity_key")!!, text(g, "display_name")!!,
            g.getValue("currencies").jsonArray.map { it.jsonPrimitive.content }, g.getValue("row_refs").jsonArray.map { it.jsonPrimitive.content })
    }, o.getValue("rows").jsonArray.map { row(it.jsonObject) })
    private fun file(o: JsonObject): ParsedImportFile {
        val d = o["document"]?.takeIf { it != JsonNull }?.jsonObject
        val document = d?.let { ParsedCashDocument(text(it, "source_type")!!, text(it, "table_key")!!,
            text(it, "parser_version")!!, it.getValue("rows").jsonArray.map { raw -> val r = raw.jsonObject
                ParsedCashRow(r.getValue("position").jsonPrimitive.int, fields(r, "fields"), text(r, "payment_method").orEmpty(),
                    text(r, "provider_id"), r.getValue("issue_codes").jsonArray.map { v -> v.jsonPrimitive.content },
                    text(r, "error_code"), text(r, "disposition")!!, text(r, "source_account_identifier"),
                    text(r, "document_position"), fields(r, "source_explanation") ?: emptyMap())
            }, text(it, "source_account_identifier"), text(it, "source_account_label"),
            CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1"),
            it["declared_count"]?.jsonPrimitive?.intOrNull, it.getValue("observed_count").jsonPrimitive.int) }
        return ParsedImportFile(text(o, "file_ref")!!, text(o, "digest")!!, text(o, "status")!!, document,
            text(o, "source_scope_label"))
    }
    @Test fun completeFundingScanAndResolutionMatchPythonContracts() {
        val cases = Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-funding.json")!!
            .bufferedReader().use { it.readText() }).jsonArray
        assertEquals(115, cases.size)
        for ((index, raw) in cases.withIndex()) {
            val c = raw.jsonObject
            val result = runCatching {
                if (text(c, "kind") == "scan") CashImportFunding.scan(c.getValue("files").jsonArray.map { file(it.jsonObject) }).json()
                else JsonArray(CashImportFunding.resolve(scan(c.getValue("scan").jsonObject),
                    cashSourceValue(c["selections"]), cashSourceValue(c["accounts"])).map { it.json() })
            }
            if (c.getValue("ok").jsonPrimitive.boolean) assertEquals(c.getValue("result"), result.getOrThrow(), "case $index")
            else assertEquals(text(c, "error"), result.exceptionOrNull()?.message, "case $index")
        }
    }
    private fun fixture(methods: List<String>, source: String = "alipay", amount: String? = "-30",
        owner: String? = "owner", ref: String = "a", scope: String? = null, product: String = ""): ParsedImportFile {
        val rows = methods.mapIndexed { index, method -> ParsedCashRow(index + 1,
            linkedMapOf("amount" to amount, "currency" to "CNY", "occurred_at" to "2026-10-01T10:00:00+00:00",
                "record_type" to "consumption", "record_subtype" to "not_applicable", "note" to "  原始备注  ",
                "source_payload" to mapOf("付款方式" to method, "商品说明" to product)), method,
            sourceExplanation = if (source in setOf("icbc_credit", "icbc_debit", "ccb_debit", "icbc_asia"))
                mapOf("source_display_name" to "银行卡(${owner?.takeLast(4).orEmpty()})") else emptyMap()) }
        return ParsedImportFile(ref, ref.repeat(64), "parsed", ParsedCashDocument(source, "table", "native-v1", rows,
            owner, owner, CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1"), rows.size, rows.size), scope)
    }
    private fun resolve(scan: CashFundingScan, selections: Any? = scan.groups.mapIndexed { index, g ->
        g.sourceAccountRef to mapOf("account_id" to "${index+1}", "expected_account_revision" to 3)
    }.toMap(), accounts: Any? = scan.groups.indices.associate { index -> "${index+1}" to mapOf("id" to "${index+1}",
        "active" to true, "type" to "cash", "revision" to 3, "currencies" to listOf("CNY")) }) = CashImportFunding.resolve(scan, selections, accounts)
    @Test fun explicitAmountsAreSignedAndNeverPartOfIdentity() {
        val scan = CashImportFunding.scan(listOf(fixture(listOf("银行卡(1234)10元&余额20元", "银行卡(1234)20元&余额10元"))))
        assertEquals(2, scan.groups.size); assertEquals(scan.rows[0].groupRefs, scan.rows[1].groupRefs)
        val rows = resolve(scan); assertTrue(rows.all { it.ready })
        val components = rows[0].fields["components"] as List<Map<String, Any?>>
        assertEquals(listOf("-10", "-20"), components.map { it["amount"] })
    }
    @Test fun repeatedAndSelectedSameAccountMergeWithoutInventingRemainder() {
        val scan = CashImportFunding.scan(listOf(fixture(listOf("银行卡(1234)&余额"))))
        val selections = scan.groups.associate { it.sourceAccountRef to mapOf("account_id" to "1", "expected_account_revision" to 3) }
        val row = resolve(scan, selections).single(); assertTrue(row.ready)
        assertEquals("1", row.fields["account_id"])
        assertEquals(1, (row.fields["components"] as List<*>).size)
        val partial = resolve(CashImportFunding.scan(listOf(fixture(listOf("银行卡(1234)10元&余额"))))).single()
        assertFalse(partial.ready); assertTrue("allocation_missing" in partial.issueCodes)
        assertEquals(listOf("-10", null), (partial.fields["components"] as List<Map<String, Any?>>).map { it["amount"] })
    }
    @Test fun fallbackPromotionsAndProductDoNotChangeRawText() {
        for (parent in listOf("0", "-10", "10", null)) for (method in listOf("", "红包5元&优惠券5元", "30元")) {
            val file = fixture(listOf(method), amount = parent, product = "余额宝收益发放")
            val before = sourceJson(file.document!!.rows.single().fields)
            val scan = CashImportFunding.scan(listOf(file)); assertEquals("余额宝", scan.groups.single().displayName)
            val row = resolve(scan).single(); assertFalse("funding_unrecognized" in row.issueCodes)
            assertEquals(before, sourceJson(file.document.rows.single().fields))
        }
    }
    @Test fun bankCompleteIdentityAndWechatScopeStayDistinct() {
        val bank = CashImportFunding.scan(listOf(fixture(listOf("ignored"), "ccb_debit", owner = "6212000000001234"),
            fixture(listOf("ignored"), "ccb_debit", owner = "6222000000001234", ref = "b")))
        assertEquals(2, bank.groups.size); assertNotEquals(bank.rows[0].groupRefs, bank.rows[1].groupRefs)
        val wx = CashImportFunding.scan(listOf(fixture(listOf("零钱"), "wechat", owner = null),
            fixture(listOf("微信零钱"), "wechat", owner = "nickname", ref = "b"),
            fixture(listOf("零钱"), "wechat", owner = null, ref = "c", scope = "工作")))
        assertEquals(2, wx.groups.size); assertEquals(wx.rows[0].groupRefs, wx.rows[1].groupRefs)
        assertNotEquals(wx.rows[0].groupRefs, wx.rows[2].groupRefs)
        val invalid = CashImportFunding.scan(listOf(fixture(listOf("余额"), scope = "unsupported")))
        assertEquals("funding_unreadable", invalid.rows.single().errorCode); assertTrue(invalid.groups.isEmpty())
    }
    @Test fun formattedFullNumbersAreMaskedAndSourceFieldsAreDetached() {
        val file = fixture(listOf("银行卡(6222\u00a01234\t5678 9012)"), "wechat")
        val scan = CashImportFunding.scan(listOf(file)); val display = scan.groups.single().displayName
        assertFalse("6222123456789012" in display.filter { it.isDigit() }); assertTrue("9012" in display)
        val row = resolve(scan).single()
        (row.fields["source_payload"] as MutableMap<String, Any?>)["付款方式"] = "modified"
        assertEquals("银行卡(6222\u00a01234\t5678 9012)", (file.document!!.rows.single().fields!!["source_payload"] as Map<*, *>)["付款方式"])
    }
    @Test fun selectionShapesAndUnknownReferencesRejectEntirePreparation() {
        val scan = CashImportFunding.scan(listOf(fixture(listOf("余额"))))
        val ref = scan.groups.single().sourceAccountRef
        for (choice in listOf(null, emptyList<Any>(), mapOf("account_id" to 1, "expected_account_revision" to 3),
            mapOf("account_id" to "1", "expected_account_revision" to true), mapOf("account_id" to "1"),
            mapOf("account_id" to null, "expected_account_revision" to 3), mapOf("arbitrary" to "x"))) {
            assertFailsWith<CashFundingInputError> { resolve(scan, mapOf(ref to choice)) }
        }
        assertFailsWith<CashFundingInputError> { resolve(scan, mapOf("other" to emptyMap<String, Any?>())) }
        val file = fixture(listOf("余额")); assertFailsWith<CashFundingInputError> { CashImportFunding.scan(listOf(file, file)) }
    }
    @Test fun currentEligibilityAndRequiredCurrencyAffectOnlySelectedRows() {
        val scan = CashImportFunding.scan(listOf(fixture(listOf("银行卡(1234)", "余额"))))
        for ((patch, code) in listOf(mapOf("active" to false) to "account_unavailable",
            mapOf("type" to "security") to "account_unavailable", mapOf("currencies" to listOf("USD")) to "account_currency_missing")) {
            val accounts = mapOf("1" to (mapOf("id" to "1", "active" to true, "type" to "cash", "currencies" to listOf("CNY")) + patch),
                "2" to mapOf("id" to "2", "active" to true, "type" to "cash", "currencies" to listOf("CNY")))
            val rows = resolve(scan, accounts = accounts); assertFalse(rows[0].ready); assertTrue(code in rows[0].issueCodes); assertTrue(rows[1].ready)
        }
        val row = resolve(CashImportFunding.scan(listOf(fixture(listOf("余额")))), accounts = mapOf("1" to mapOf("id" to "1",
            "active" to true, "type" to "cash", "revision" to 4, "currencies" to listOf("CNY", "USD")))).single()
        assertTrue(row.ready)
    }
    @Test fun exactAmountAndInvalidTokensKeepTheirOriginalFailure() {
        val value = "-12345678901234567890.123456789012345678"
        val scan = CashImportFunding.scan(listOf(fixture(listOf("银行卡(1234)"), amount = value)))
        val row = resolve(scan).single(); assertTrue(row.ready)
        assertEquals(value, (row.fields["components"] as List<Map<String, Any?>>).single()["amount"])
        assertEquals("  原始备注  ", row.fields["note"])
        for (method in listOf("银行卡(1234)¥1e3", "银行卡(1234)-10元", "银行卡(1234)10元20元")) {
            val invalid = resolve(CashImportFunding.scan(listOf(fixture(listOf(method))))).single()
            assertFalse(invalid.ready); assertTrue("allocation_amount_invalid" in invalid.issueCodes)
        }
    }
    @Test fun excessivePartsFailOnlyTheirRowAndInternalFailurePropagates() {
        val scan = CashImportFunding.scan(listOf(fixture(listOf(List(65) { "余额" }.joinToString("&"), "余额"))))
        assertEquals("funding_unreadable", scan.rows[0].errorCode); assertTrue(resolve(scan)[1].ready)
        val file = fixture(listOf("ignored"), "ccb_debit")
        val broken = object : Map<String, Any?> by emptyMap() {
            override fun get(key: String): Any? = throw IllegalStateException("synthetic internal failure")
        }
        val row = file.document!!.rows.single().copy(sourceExplanation = broken)
        assertFailsWith<IllegalStateException> { CashImportFunding.scan(listOf(file.copy(document = file.document.copy(rows = listOf(row))))) }
    }

    @Test fun copiesPreserveExactScalarTypesAndSharedContainers() {
        val original = fixture(listOf("余额"))
        val amount = java.math.BigDecimal("-12345678901234567890.123456789012345678")
        val nested = mutableListOf<Any?>(amount, 17)
        val input = original.document!!.rows.single().fields!! + mapOf("amount" to amount,
            "source_payload" to linkedMapOf("first" to nested, "second" to nested))
        val file = original.copy(document = original.document.copy(rows = listOf(original.document.rows.single().copy(fields = input))))
        val scan = CashImportFunding.scan(listOf(file))
        val scanPayload = scan.rows.single().fields["source_payload"] as Map<*, *>
        val scanValues = scanPayload["first"] as MutableList<Any?>
        assertEquals(amount, scan.rows.single().fields["amount"])
        assertSame(scanValues, scanPayload["second"])
        assertEquals(amount, scanValues[0]); assertEquals(17, scanValues[1])
        assertNotSame(nested, scanValues)
        val result = resolve(scan).single(); assertTrue(result.ready)
        val payload = result.fields["source_payload"] as Map<*, *>
        val values = payload["first"] as MutableList<Any?>
        assertSame(values, payload["second"]); assertNotSame(scanValues, values)
        assertEquals(amount, values[0]); assertEquals(17, values[1])
        values[0] = "changed"
        assertEquals(amount, nested[0]); assertEquals(amount, scanValues[0])
    }
    @Test fun accountCurrenciesKeepMembershipAndInternalFailureBoundary() {
        val scan = CashImportFunding.scan(listOf(fixture(listOf("余额"))))
        fun accounts(currencies: Any?) = mapOf("1" to mapOf("id" to "1", "active" to true,
            "type" to "cash", "currencies" to currencies))
        for (currencies in listOf("CNY,USD", mapOf("CNY" to false))) assertTrue(resolve(scan, accounts = accounts(currencies)).single().ready)
        for (currencies in listOf(null, 17, true)) assertFailsWith<IllegalStateException> { resolve(scan, accounts = accounts(currencies)) }
        assertTrue("account_currency_missing" in resolve(scan, accounts = accounts(emptyList<Any>())).single().issueCodes)
    }

    @Test fun bankPayloadIsReadOnlyForUnidentifiedCreditCards() {
        for ((source, owner) in listOf("ccb_debit" to null, "icbc_credit" to "6222000000001234")) {
            val original = fixture(listOf("ignored"), source, owner = owner)
            val safeFields = original.document!!.rows.single().fields!! + mapOf("source_payload" to mapOf(17 to "raw value"))
            val file = original.copy(document = original.document.copy(rows = listOf(original.document.rows.single().copy(fields = safeFields))))
            assertNull(CashImportFunding.scan(listOf(file)).rows.single().errorCode)
        }
        val original = fixture(listOf("ignored"), "icbc_credit", owner = null)
        for (payload in listOf(null, "unexpected", mapOf("交易卡号" to 17))) {
            val fields = original.document!!.rows.single().fields!! + mapOf("source_payload" to payload)
            val file = original.copy(document = original.document.copy(rows = listOf(original.document.rows.single().copy(fields = fields))))
            assertFailsWith<IllegalStateException> { CashImportFunding.scan(listOf(file)) }
        }
    }

}
