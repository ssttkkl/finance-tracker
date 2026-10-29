package com.finance.tracker.backend

import java.math.BigDecimal

/** 已解码平台账单行的逐条归一结果。 */
internal data class ParsedCashRow(
    val position: Int,
    val fields: Map<String, Any?>?,
    val paymentMethod: String = "",
    val providerId: String? = null,
    val issueCodes: List<String> = emptyList(),
    val errorCode: String? = null,
    val disposition: String = "candidate",
    val sourceAccountIdentifier: String? = null,
    val documentPosition: String? = null,
    val sourceExplanation: Map<String, Any?> = emptyMap(),
)

internal data class ParsedCashDocument(
    val sourceType: String,
    val tableKey: String,
    val parserVersion: String,
    val rows: List<ParsedCashRow>,
    val sourceAccountIdentifier: String?,
    val sourceAccountLabel: String?,
    val coverage: CashSourceCoverage,
    val declaredCount: Long?,
    val observedCount: Int,
)

/** 平台账单行归一；复用来源正文、分类和对方账号规则，不执行跨行配对。 */
internal object CashStatementRowNormalization {
    fun normalizePlatformDocument(document: CashStatementDocument): ParsedCashDocument {
        if (document.sourceType !in setOf("alipay", "wechat"))
            throw StatementDocumentFailure("statement_source_unsupported")
        val rows = document.rows.map { row ->
            if (row.errorCode?.isNotEmpty() == true || row.payload == null) {
                ParsedCashRow(row.position, null, errorCode = row.errorCode?.takeIf { it.isNotEmpty() } ?: "row_unreadable", disposition = "skipped")
            } else {
                try {
                    normalizePlatformRow(document.sourceType, row.payload, row.position)
                } catch (_: CashRecordRuleFailure) {
                    ParsedCashRow(row.position, null, errorCode = "row_unreadable", disposition = "skipped")
                } catch (_: IllegalArgumentException) {
                    ParsedCashRow(row.position, null, errorCode = "row_unreadable", disposition = "skipped")
                } catch (_: ArithmeticException) {
                    ParsedCashRow(row.position, null, errorCode = "row_unreadable", disposition = "skipped")
                }
            }
        }
        val usable = rows.all { it.issueCodes.isEmpty() &&
            it.errorCode in setOf(null, "unpaid_closed", "failed_repay") }
        val coverage = if (usable) document.coverage else document.coverage.copy(complete = false, evidence = "")
        return ParsedCashDocument(document.sourceType, document.tableKey, document.parserVersion + "+native-v1",
            rows, document.sourceAccountIdentifier, document.sourceAccountLabel, coverage,
            document.declaredCount, document.observedCount)
    }

    private fun normalizePlatformRow(source: String, payload: Map<String, String>, position: Int): ParsedCashRow {
        val direction = text(payload["收/支"]).trimPython()
        val amountText = text(payload[if (source == "alipay") "金额" else "金额(元)"]).trimPython()
        val issues = mutableListOf<String>()
        val parsedAmount = CashAmount.parseOrNull(amountText)
        if (parsedAmount == null) issues += "amount_unreadable"
        val amount = parsedAmount?.let(::amountValue)
        val occurredAt = try {
            normalizeStatementTimestamp(text(payload["交易时间"]).replace("/", "-"), source)
        } catch (_: java.time.DateTimeException) {
            issues += "time_unreadable"; null
        } catch (_: IllegalArgumentException) {
            issues += "time_unreadable"; null
        } catch (_: ArithmeticException) {
            issues += "time_unreadable"; null
        }
        if (source == "alipay" && direction !in setOf("支出", "收入", "不计收支")) issues += "direction_unrecognized"
        if (source == "wechat" && direction !in setOf("支出", "收入", "/", "", "不计收支")) issues += "direction_unrecognized"

        var native: Map<String, Any?>? = null
        if ("amount_unreadable" !in issues && "direction_unrecognized" !in issues) {
            native = if (source == "alipay") alipayNative(payload, position, amount!!)
            else wechatNative(payload, position, amount!!)
            if (native == null) issues += "direction_unrecognized"
            else if (native["_skipped"] == true) {
                return ParsedCashRow(position, null, errorCode = native["_skip_reason"] as? String, disposition = "skipped")
            }
        }
        val nativeAmount = native?.get("amount") as? BigDecimal
        val cashAmount = nativeAmount?.let { CashAmount.parseOrNull(it.toPlainString()) }
        val explanation = prepareCashSourceEffects(
            values = mapOf("source_payload" to payload, "amount" to cashAmount),
            patch = mapOf("source_payload" to payload, "amount" to cashAmount),
            sourceType = source,
            sourceExplanation = extractCashSourceExplanation(native),
        ).explanation ?: emptyMap()
        val record = if (native != null) CashRecordClassification.classify(source, native) else null
        val rawAccount = text(if (source == "alipay") payload["对方账号"] else "")
        val rawCounterparty = text(payload["交易对方"])
        val counterparts = if (native == null || source == "wechat") rawCounterparty
        else text(native["counterparty"])
        val account = if (native == null) CounterpartyAccount(rawAccount, emptyList())
        else CashRecordClassification.normalizeAccount(
            native["counterparty_account"]?.takeIf { text(it).isNotEmpty() } ?: rawAccount,
            source = source, sourcePayload = payload)
        val providerKey = if (source == "alipay") "交易订单号" else "交易单号"
        val identifier = text(payload[providerKey]).trimPython()
        val provider = identifier.takeIf { it !in setOf("", "/", "-", "--") &&
            it.codePointCount(0, it.length) <= 512 }
        val fields = linkedMapOf<String, Any?>(
            "amount" to nativeAmount?.toPlainString(),
            "currency" to "CNY",
            "occurred_at" to occurredAt,
            "record_type" to record?.first,
            "record_subtype" to record?.second,
            "counterparty" to counterparts,
            "counterparty_account" to account.value,
            "counterparty_account_attrs" to account.attrs,
            "note" to cashSourceNote(source, payload),
            "source_payload" to LinkedHashMap(payload),
        )
        val nativeMethod = text(native?.get("payment_method"))
        val method = if ("payment_method" in explanation) text(explanation["payment_method"]) else nativeMethod
        return ParsedCashRow(position, fields, method, provider, issues, sourceExplanation = explanation)
    }

    private fun alipayNative(payload: Map<String, String>, position: Int, initialAmount: BigDecimal): Map<String, Any?>? {
        val direction = text(payload["收/支"]).trimPython()
        val txnType = text(payload["交易分类"]).trimPython()
        val paymentMethod = text(payload["收/付款方式"]).trimPython()
        var amount = initialAmount
        var category: String
        if (amount.signum() == 0) {
            category = if (direction == "支出") "expense" else "income"
        } else {
            category = if (amount.signum() < 0) "expense" else "income"
            when (direction) {
                "支出" -> amount = amount.negate()
                "收入" -> Unit
                "不计收支" -> {
                    val description = text(payload["商品说明"]).trimPython()
                    val investmentOutflow = txnType == "投资理财" && "收益发放" !in description &&
                        listOf("转入", "买入", "单次转入", "支付宝转入到余利宝").any { it in description }
                    val wealthToBank = "转出到银行卡" in description &&
                        listOf("余额宝", "余利宝").any { it in description }
                    val wealthToBalance = "余额宝-转出到余额" in description
                    if (txnType == "账户提现" || txnType == "信用借还" || "提现-实时提现" in description ||
                        "转出到网商银行" in description || investmentOutflow || wealthToBank || wealthToBalance) {
                        amount = amount.abs().negate(); category = "expense"
                    }
                }
                else -> throw CashRecordRuleFailure("unknown alipay direction")
            }
        }
        val counterparty = text(payload["交易对方"]).trimPython()
        val rawDescription = text(payload["商品说明"]).trimPython()
        var description = rawDescription.ifEmpty { counterparty }
        val status = text(payload["交易状态"]).trimPython()
        if (alipayUnpaidClosed(status, direction, paymentMethod))
            return mapOf("_skipped" to true, "_skip_reason" to "unpaid_closed")
        if (alipayFailedRepay(status, direction, paymentMethod))
            return mapOf("_skipped" to true, "_skip_reason" to "failed_repay")
        if (amount.signum() != 0) category = if (amount.signum() < 0) "expense" else "income"
        var (normalizedCounterparty, enrichedDescription) = normalizeSourceCounterparty(counterparty, description.takeCodePoints(80))
        val refundSignal = when {
            status == "退款成功" -> "alipay_status"
            txnType == "退款" && direction == "不计收支" -> "alipay_category_nocount"
            "退款" in enrichedDescription -> "alipay_desc"
            else -> ""
        }
        val wealth = routeWealth(amount, category, enrichedDescription.takeCodePoints(80), paymentMethod)
        amount = wealth.first; category = wealth.second
        val method = wealth.third
        return linkedMapOf(
            "date" to text(payload["交易时间"]).trimPython().take(19).replace("/", "-"),
            "amount" to amount, "payment_method" to method, "counterparty" to normalizedCounterparty,
            "counterparty_account" to text(payload["对方账号"]), "note" to enrichedDescription.takeCodePoints(80),
            "category" to category, "txn_type" to txnType, "txn_id" to text(payload["交易订单号"]).trimPython(),
            "merchant_order_id" to text(payload["商家订单号"]).trimPython(), "platform_status" to status,
            "_alipay_direction" to direction, "_refund_signal" to refundSignal, "_source_payload" to payload,
        )
    }

    private fun wechatNative(payload: Map<String, String>, position: Int, initialAmount: BigDecimal): Map<String, Any?>? {
        val direction = text(payload["收/支"])
        val status = text(payload["当前状态"])
        var amount = initialAmount
        var paymentMethod = text(payload["支付方式"])
        var counterpartyAccount = ""
        var counterparty = text(payload["交易对方"])
        var description = text(payload["商品"])
        val txnType = text(payload["交易类型"])
        val txnId = text(payload["交易单号"])
        val merchantOrderId = text(payload["商户单号"])
        var category: String
        if (direction == "支出") {
            amount = amount.negate()
            category = if (amount.signum() < 0) "expense" else "income"
        } else if (direction == "收入") {
            category = if (amount.signum() < 0) "expense" else "income"
        } else {
            when (txnType) {
                "零钱提现" -> {
                    amount = amount.abs().negate(); category = "expense"
                    counterpartyAccount = paymentMethod
                    if (paymentMethod.isNotEmpty() && (counterparty.isEmpty() || counterparty in setOf("/", "-")))
                        counterparty = paymentMethod
                    paymentMethod = "零钱"
                }
                "充值", "零钱充值", "购买理财通", "信用卡还款" -> {
                    amount = amount.abs().negate(); category = "expense"
                }
                "零钱通存取", "理财通" -> {
                    val text = "$counterparty$description$status"
                    if (listOf("转出", "取出", "赎回", "到账").any { it in text }) category = "income"
                    else { amount = amount.abs().negate(); category = "expense" }
                }
                else -> return null
            }
            if (description.isEmpty() || description in setOf("/", "-")) description = txnType
            val (normalized, enriched) = normalizeSourceCounterparty(counterparty, description.takeCodePoints(80))
            return linkedMapOf(
                "date" to text(payload["交易时间"]).trimPython().take(19).replace("/", "-"),
                "amount" to amount, "payment_method" to paymentMethod, "counterparty" to normalized,
                "counterparty_account" to counterpartyAccount, "note" to enriched.takeCodePoints(80),
                "category" to category, "status" to status, "platform_status" to status, "txn_type" to txnType,
                "txn_id" to txnId, "merchant_order_id" to merchantOrderId,
                "_wechat_direction" to direction.ifEmpty { "/" },
                "_refund_signal" to if ("退款" in status) "wechat_status" else "", "_source_payload" to payload,
            )
        }
        if (description.isEmpty() || description in setOf("/", "-")) description = txnType
        category = if (amount.signum() < 0) "expense" else "income"
        val (normalized, enriched) = normalizeSourceCounterparty(counterparty, description.takeCodePoints(80))
        return linkedMapOf(
            "date" to text(payload["交易时间"]).trimPython().take(19).replace("/", "-"),
            "amount" to amount, "payment_method" to paymentMethod, "counterparty" to normalized,
            "counterparty_account" to "", "note" to enriched.takeCodePoints(80), "category" to category,
            "status" to status, "platform_status" to status, "txn_type" to txnType, "txn_id" to txnId,
            "merchant_order_id" to merchantOrderId,
            "_wechat_direction" to direction.ifEmpty { if (category == "expense") "支出" else "收入" },
            "_refund_signal" to if ("退款" in status) "wechat_status" else "", "_source_payload" to payload,
        )
    }

    private fun routeWealth(amount: BigDecimal, category: String, description: String, method: String):
        Triple<BigDecimal, String, String> {
        var resultAmount = amount
        var resultCategory = category
        var resultMethod = method
        when {
            "转出到银行卡" in description && "余额宝" in description -> {
                resultAmount = amount.abs().negate(); resultCategory = "expense"; resultMethod = "余额宝"
            }
            "转出到银行卡" in description && "余利宝" in description -> {
                resultAmount = amount.abs().negate(); resultCategory = "expense"; resultMethod = "余利宝"
            }
            "余额宝-转出到余额" in description -> {
                resultAmount = amount.abs().negate(); resultCategory = "expense"; resultMethod = "余额宝"
            }
            "支付宝转入到余利宝" in description || "余额宝-单次转入" in description -> Unit
            "收益发放" in description && "余额宝" in description || "卖出至余额宝" in description -> {
                resultAmount = amount.abs(); resultCategory = "income"; resultMethod = "余额宝"
            }
            method.startsWith("余额宝") -> resultMethod = "余额宝"
            method.startsWith("余利宝") -> resultMethod = "余利宝"
        }
        return Triple(resultAmount, resultCategory, resultMethod)
    }

    private fun amountValue(amount: CashAmount): BigDecimal {
        val representation = amount.representation
        val value = BigDecimal(representation.coefficient.ifEmpty { "0" }).scaleByPowerOfTen(representation.exponent.toInt())
        return if (representation.negative) value.negate() else value
    }


    fun normalizeBankDocument(document: CashStatementDocument): ParsedCashDocument {
        if (document.sourceType !in setOf("ccb_debit", "icbc_asia"))
            throw StatementDocumentFailure("statement_source_unsupported")
        val rows = document.rows.map { row ->
            if (row.errorCode?.isNotEmpty() == true || row.payload == null) {
                ParsedCashRow(row.position, null, errorCode = row.errorCode?.takeIf { it.isNotEmpty() } ?: "row_unreadable", disposition = "skipped")
            } else {
                try {
                    normalizeBankRow(document, row.payload, row.position)
                } catch (_: CashRecordRuleFailure) {
                    ParsedCashRow(row.position, null, errorCode = "row_unreadable", disposition = "skipped")
                } catch (_: IllegalArgumentException) {
                    ParsedCashRow(row.position, null, errorCode = "row_unreadable", disposition = "skipped")
                } catch (_: ArithmeticException) {
                    ParsedCashRow(row.position, null, errorCode = "row_unreadable", disposition = "skipped")
                }
            }
        }
        val usable = rows.all { row ->
            row.issueCodes.isEmpty() && row.errorCode in setOf(null, "account_opening") &&
                (row.fields == null || document.coverage.contains(row.fields["occurred_at"] as? String))
        }
        val coverage = if (usable) document.coverage else document.coverage.copy(complete = false, evidence = "")
        return ParsedCashDocument(document.sourceType, document.tableKey, document.parserVersion + "+native-v1",
            rows, document.sourceAccountIdentifier, document.sourceAccountLabel, coverage,
            document.declaredCount, document.observedCount)
    }

    private fun normalizeBankRow(document: CashStatementDocument, payload: Map<String, String>, position: Int): ParsedCashRow {
        val source = document.sourceType
        val isCcb = source == "ccb_debit"
        val owner = document.sourceAccountIdentifier.orEmpty()
        val card = if (owner.isNotEmpty() && owner.all { it in '0'..'9' }) {
            if (isCcb) owner.takeLast(4) else (owner.dropLast(1) + "0").takeLast(4)
        } else ""
        val issues = mutableListOf<String>()
        val acctRaw = text(payload[if (isCcb) "对方账号与户名" else "對方賬號"])
        var counterparty = if (isCcb) ccbAcctCounterparty(text(payload["对方账号与户名"]))
        else text(payload["對方戶名"]).trimPython()
        var counterpartyAccount = if (isCcb) ccbCounterpartyAccount(text(payload["对方账号与户名"])) else acctRaw
        var method = if (isCcb) inferCcbPayment(text(payload["交易地点/附言"]).trimPython(), card)
        else if (card.isNotEmpty()) "工银亚洲活期账户($card)" else "工银亚洲活期账户"
        var amount: BigDecimal? = null
        var date = ""
        var nativeExplanation: Map<String, Any?> = emptyMap()
        var native: Map<String, Any?>? = null
        val value = if (isCcb) text(payload["交易金额"]) else {
            val income = text(payload["收入金額"]).trimPython()
            val expense = text(payload["支出金額"]).trimPython()
            if (income.isNotEmpty() == expense.isNotEmpty()) issues += "direction_unrecognized"
            income.ifEmpty { expense }
        }
        if ("direction_unrecognized" !in issues) {
            val parsed = CashAmount.parseOrNull(value.replace(",", "").trimPython())
            if (parsed == null) issues += "amount_unreadable"
            else {
                var parsedAmount = amountValue(parsed)
                if (!isCcb) parsedAmount = if (value == text(payload["支出金額"]).trimPython())
                    parsedAmount.abs().negate() else parsedAmount.abs()
                amount = parsedAmount
            }
        }
        try {
            date = if (isCcb) {
                val raw = text(payload["交易日期"]).trimPython()
                if (!Regex("[0-9]{8}").matches(raw)) throw IllegalArgumentException("bad date")
                java.time.LocalDate.parse(raw, java.time.format.DateTimeFormatter.BASIC_ISO_DATE).toString()
            } else {
                val raw = "${text(payload["交易時間"]).trimPython()} ${text(payload[""]).trimPython()}"
                if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}").matches(raw))
                    throw IllegalArgumentException("bad date")
                raw
            }
        } catch (_: IllegalArgumentException) {
            issues += "time_unreadable"
        } catch (_: java.time.DateTimeException) {
            issues += "time_unreadable"
        }
        val occurredAt = if ("time_unreadable" in issues) null else try {
            normalizeStatementTimestamp(date, source)
        } catch (_: IllegalArgumentException) {
            issues += "time_unreadable"; null
        } catch (_: ArithmeticException) {
            issues += "time_unreadable"; null
        }
        val currency = if (isCcb) mapOf("人民币元" to "CNY", "人民币" to "CNY", "美元" to "USD",
            "港币" to "HKD", "日元" to "JPY")[text(payload["币别"]).trimPython()] else document.sourceCurrency
        if (currency == null) issues += "currency_unrecognized"
        if (amount != null) {
            native = if (isCcb) parseCcbNative(payload, amount, date, currency.orEmpty(), card)
            else parseAsiaNative(payload, amount, date, currency.orEmpty(), owner, card)
            native?.let {
                counterparty = text(it["counterparty"])
                counterpartyAccount = text(it["counterparty_account"])
                method = text(it["payment_method"])
                nativeExplanation = extractCashSourceExplanation(it)
            }
            if (!isCcb && amount.signum() == 0 && text(payload["業務類型"]).trimPython() in setOf("新開戶", "新开户") && issues.isEmpty())
                return ParsedCashRow(position, null, errorCode = "account_opening", disposition = "skipped")
        }
        val displayTail = if (owner.isNotEmpty() && owner.all { it in '0'..'9' }) owner.takeLast(4) else ""
        val displayName = if (isCcb) "建设银行" + if (displayTail.length == 4) "($displayTail)" else ""
        else "工银亚洲" + if (displayTail.isEmpty()) "" else "（尾号 $displayTail）"
        val identity = mapOf("_source_account_identifier" to owner, "card_number" to card,
            "source_display_name" to displayName)
        val cashAmount = amount?.let { CashAmount.parseOrNull(it.toPlainString()) }
        val explanation = prepareCashSourceEffects(
            values = mapOf("source_payload" to payload, "amount" to cashAmount),
            patch = mapOf("source_payload" to payload, "amount" to cashAmount),
            sourceType = source,
            sourceExplanation = nativeExplanation + identity,
        ).explanation ?: emptyMap()
        val account = CashRecordClassification.normalizeAccount(counterpartyAccount, source = source,
            sourceAccountIdentifier = owner, sourcePayload = payload)
        val record = native?.let { CashRecordClassification.classify(source, it) }
        val fields = linkedMapOf<String, Any?>(
            "amount" to amount?.toPlainString(),
            "currency" to currency,
            "occurred_at" to occurredAt,
            "record_type" to record?.first,
            "record_subtype" to record?.second,
            "counterparty" to counterparty,
            "counterparty_account" to account.value,
            "counterparty_account_attrs" to account.attrs,
            "note" to CashRecordPolicy.sourceNote(payload),
            "source_payload" to LinkedHashMap(payload),
        )
        return ParsedCashRow(position, fields, method, issueCodes = issues, sourceExplanation = explanation)
    }

    private fun parseCcbNative(payload: Map<String, String>, amount: BigDecimal, date: String, currency: String,
        card: String): Map<String, Any?> {
        val summary = text(payload["摘要"]).trimPython()
        val location = text(payload["交易地点/附言"]).trimPython()
        val acctRaw = text(payload["对方账号与户名"]).trimPython()
        val balance = text(payload["账户余额"]).replace(",", "").trimPython()
        val locationCp = ccbCounterparty(location)
        val acctCp = ccbAcctCounterparty(acctRaw)
        val counterparty = locationCp ?: acctCp
        val refund = if (amount.signum() > 0 && listOf("退货", "退款", "冲正", "撤销").any { it in summary })
            "ccb_debit_refund" else ""
        val (normalized, enriched) = normalizeSourceCounterparty(counterparty, summary)
        return linkedMapOf(
            "date" to date, "amount" to amount, "currency" to currency, "card_number" to card,
            "counterparty" to normalized, "counterparty_account" to ccbCounterpartyAccount(acctRaw),
            "note" to enriched, "category" to if (amount.signum() < 0) "expense" else "income",
            "payment_method" to inferCcbPayment(location, card), "summary" to summary, "location" to location,
            "acct_name_raw" to acctRaw, "_raw_cp" to acctCp.ifEmpty { counterparty },
            "_ccb_location_cp" to (locationCp ?: ""), "_ccb_acct_cp" to acctCp,
            "_ccb_refund_signal" to refund, "_source_payload" to payload,
        ).apply { if (refund.isNotEmpty()) put("_refund_signal", refund) }
    }

    private fun parseAsiaNative(payload: Map<String, String>, amount: BigDecimal, date: String, currency: String,
        owner: String, card: String): Map<String, Any?> {
        val summary = text(payload.getValue("摘要")).trimPython()
        val note = listOf(summary, text(payload.getValue("備註")).trimPython(), text(payload.getValue("交易場所")).trimPython())
            .filter { it.isNotEmpty() }.distinct().joinToString(" ")
        return linkedMapOf(
            "date" to date, "amount" to amount, "currency" to currency, "card_number" to card,
            "counterparty" to text(payload.getValue("對方戶名")).trimPython(),
            "counterparty_account" to text(payload.getValue("對方賬號")),
            "note" to note, "category" to if (amount.signum() >= 0) "income" else "expense",
            "payment_method" to if (card.isNotEmpty()) "工银亚洲活期账户($card)" else "工银亚洲活期账户",
            "summary" to summary, "txn_type" to text(payload.getValue("業務類型")).trimPython(),
            "_source_account_identifier" to owner, "location" to text(payload.getValue("交易場所")).trimPython(),
            "_raw_cp" to text(payload.getValue("對方戶名")).trimPython(), "_source_payload" to payload,
        )
    }

    private fun ccbCounterparty(location: String): String? {
        if (location.isEmpty() || location == "***") return null
        var stripped = location.removePrefix("消费-")
        for ((prefix, suffixes) in ccbPrefixes) if (stripped.startsWith(prefix)) {
            var rest = stripped.substring(prefix.length)
            while (true) {
                val suffix = suffixes.firstOrNull(rest::startsWith) ?: return rest
                rest = rest.substring(suffix.length)
            }
        }
        return securitiesTransfer.find(stripped)?.groupValues?.get(1) ?: stripped
    }

    private fun ccbAcctCounterparty(raw: String): String =
        if ("/" in raw) raw.substringAfter("/").trimPython() else raw.trimPython()

    private fun ccbCounterpartyAccount(raw: String): String =
        if ("/" in raw) raw.substringBefore("/").trimPython() else ""

    private fun inferCcbPayment(location: String, card: String): String = when {
        location.startsWith("财付通-") -> "微信支付"
        location.startsWith("支付宝-") -> "支付宝"
        location.startsWith("美团支付-") -> "美团支付"
        "PAYPAL" in location.uppercase() -> "PayPal"
        card.isNotEmpty() -> "建行储蓄卡($card)"
        else -> "建行储蓄卡"
    }

    private val pythonDigitClass = pythonIntegerDigitStarts.joinToString("", "[", "]") {
        "\\x{${it.toString(16)}}-\\x{${(it + 9).toString(16)}}"
    }
    private val pythonWhitespaceClass = pythonWhitespace.joinToString("", "[^", "]") { "\\x{${it.toString(16)}}" }
    private val securitiesTransfer = Regex("^(银行转证券|证券转银行|银转证|证转银)$pythonDigitClass+$pythonWhitespaceClass*$")
    private val ccbPrefixes = listOf("财付通-" to listOf("微信支付-", "微信转账", "消费-"),
        "支付宝-" to listOf("淘宝-", "支付宝外部商户-", "支付宝-转账-", "支付宝-", "消费-"),
        "美团支付-" to listOf("消费-"))

    private fun alipayUnpaidClosed(status: String, direction: String, paymentMethod: String): Boolean =
        status.trimPython() in setOf("交易关闭", "已关闭") && direction.trimPython() != "支出" &&
            paymentMethod.trimPython().isEmpty()

    private fun alipayFailedRepay(status: String, direction: String, paymentMethod: String): Boolean =
        status.trimPython() == "还款失败" && direction.trimPython() == "不计收支" && paymentMethod.trimPython().isEmpty()

    private fun text(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Boolean -> if (value) "True" else "False"
        is BigDecimal -> value.toPlainString()
        else -> value.toString()
    }

    private fun String.trimPython(): String = trim { it.code in pythonWhitespace }

    private fun String.takeCodePoints(count: Int): String =
        substring(0, offsetByCodePoints(0, minOf(count, codePointCount(0, length))))
}
