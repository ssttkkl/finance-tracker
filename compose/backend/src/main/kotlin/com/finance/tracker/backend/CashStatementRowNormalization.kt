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
    /** 按已读取文档的来源类型分派归一；原始文档与逐行归一保持同一边界。 */
    fun normalizeDocument(document: CashStatementDocument): ParsedCashDocument = when (document.sourceType) {
        "alipay", "wechat" -> normalizePlatformDocument(document)
        "ccb_debit", "icbc_asia" -> normalizeBankDocument(document)
        "icbc_credit", "icbc_debit" -> normalizeIcbcDocument(document)
        else -> throw StatementDocumentFailure("statement_source_unsupported")
    }

    /** 读取并归一一份账单；不执行上传、去重、关系或持久化。 */
    fun parseCashStatement(content: ByteArray, source: String? = null, password: String? = null): ParsedCashDocument =
        normalizeDocument(CashStatementDocuments.readDocument(content, source, password))

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
        val amount = parsedAmount?.let { amountOf(it, amountText) }
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
        val finalAmount = native?.get("_amount") as? Amount
        val cashAmount = finalAmount?.let { CashAmount.parseOrNull(it.format()) }
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
            "amount" to finalAmount?.format(),
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

    private fun alipayNative(payload: Map<String, String>, position: Int, initialAmount: Amount): Map<String, Any?>? {
        val direction = text(payload["收/支"]).trimPython()
        val txnType = text(payload["交易分类"]).trimPython()
        val paymentMethod = text(payload["收/付款方式"]).trimPython()
        var amount = initialAmount
        var category: String
        if (amount.value.signum() == 0) {
            category = if (direction == "支出") "expense" else "income"
        } else {
            category = if (amount.value.signum() < 0) "expense" else "income"
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
        if (amount.value.signum() != 0) category = if (amount.value.signum() < 0) "expense" else "income"
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
            "amount" to amount.value, "_amount" to amount, "payment_method" to method, "counterparty" to normalizedCounterparty,
            "counterparty_account" to text(payload["对方账号"]), "note" to enrichedDescription.takeCodePoints(80),
            "category" to category, "txn_type" to txnType, "txn_id" to text(payload["交易订单号"]).trimPython(),
            "merchant_order_id" to text(payload["商家订单号"]).trimPython(), "platform_status" to status,
            "_alipay_direction" to direction, "_refund_signal" to refundSignal, "_source_payload" to payload,
        )
    }

    private fun wechatNative(payload: Map<String, String>, position: Int, initialAmount: Amount): Map<String, Any?>? {
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
            category = if (amount.value.signum() < 0) "expense" else "income"
        } else if (direction == "收入") {
            category = if (amount.value.signum() < 0) "expense" else "income"
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
                "amount" to amount.value, "_amount" to amount, "payment_method" to paymentMethod, "counterparty" to normalized,
                "counterparty_account" to counterpartyAccount, "note" to enriched.takeCodePoints(80),
                "category" to category, "status" to status, "platform_status" to status, "txn_type" to txnType,
                "txn_id" to txnId, "merchant_order_id" to merchantOrderId,
                "_wechat_direction" to direction.ifEmpty { "/" },
                "_refund_signal" to if ("退款" in status) "wechat_status" else "", "_source_payload" to payload,
            )
        }
        if (description.isEmpty() || description in setOf("/", "-")) description = txnType
        category = if (amount.value.signum() < 0) "expense" else "income"
        val (normalized, enriched) = normalizeSourceCounterparty(counterparty, description.takeCodePoints(80))
        return linkedMapOf(
            "date" to text(payload["交易时间"]).trimPython().take(19).replace("/", "-"),
            "amount" to amount.value, "_amount" to amount, "payment_method" to paymentMethod, "counterparty" to normalized,
            "counterparty_account" to "", "note" to enriched.takeCodePoints(80), "category" to category,
            "status" to status, "platform_status" to status, "txn_type" to txnType, "txn_id" to txnId,
            "merchant_order_id" to merchantOrderId,
            "_wechat_direction" to direction.ifEmpty { if (category == "expense") "支出" else "收入" },
            "_refund_signal" to if ("退款" in status) "wechat_status" else "", "_source_payload" to payload,
        )
    }

    private fun routeWealth(amount: Amount, category: String, description: String, method: String):
        Triple<Amount, String, String> {
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


    private data class Amount(val value: BigDecimal, val zeroText: String = "", val negativeZero: Boolean = false) {
        fun abs(): Amount = if (value.signum() == 0) copy(negativeZero = false) else Amount(value.abs())
        fun negate(): Amount = if (value.signum() == 0) copy(negativeZero = !negativeZero) else Amount(value.negate())
        fun format(): String = if (value.signum() == 0) (if (negativeZero) "-" else "") + zeroText else value.toPlainString()
    }

    private fun amountOf(cash: CashAmount, original: String): Amount {
        val value = amountValue(cash)
        if (value.signum() != 0) return Amount(value)
        val normalized = normalizeDecimalText(original)
        val plain = try { BigDecimal(normalized).abs().toPlainString() } catch (_: NumberFormatException) { "0" }
        return Amount(value, plain, normalized.startsWith("-"))
    }

    private fun normalizeDecimalText(text: String): String {
        val result = StringBuilder()
        var index = 0
        while (index < text.length) {
            val code = text.codePointAt(index)
            val width = Character.charCount(code)
            when {
                code == '_'.code -> Unit
                code in pythonWhitespace -> Unit
                else -> {
                    val start = pythonIntegerDigitStarts.firstOrNull { code in it until it + 10 }
                    if (start != null) result.append(('0'.code + code - start).toChar())
                    else result.appendCodePoint(code)
                }
            }
            index += width
        }
        return result.toString()
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
        var amount: Amount? = null
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
                var parsedAmount = amountOf(parsed, value)
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
            if (!isCcb && amount.value.signum() == 0 && text(payload["業務類型"]).trimPython() in setOf("新開戶", "新开户") && issues.isEmpty())
                return ParsedCashRow(position, null, errorCode = "account_opening", disposition = "skipped")
        }
        val displayTail = if (owner.isNotEmpty() && owner.all { it in '0'..'9' }) owner.takeLast(4) else ""
        val displayName = if (isCcb) "建设银行" + if (displayTail.length == 4) "($displayTail)" else ""
        else "工银亚洲" + if (displayTail.isEmpty()) "" else "（尾号 $displayTail）"
        val identity = mapOf("_source_account_identifier" to owner, "card_number" to card,
            "source_display_name" to displayName)
        val cashAmount = amount?.let { CashAmount.parseOrNull(it.format()) }
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
            "amount" to amount?.format(),
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

    private fun parseCcbNative(payload: Map<String, String>, amount: Amount, date: String, currency: String,
        card: String): Map<String, Any?> {
        val summary = text(payload["摘要"]).trimPython()
        val location = text(payload["交易地点/附言"]).trimPython()
        val acctRaw = text(payload["对方账号与户名"]).trimPython()
        val balance = text(payload["账户余额"]).replace(",", "").trimPython()
        val locationCp = ccbCounterparty(location)
        val acctCp = ccbAcctCounterparty(acctRaw)
        val counterparty = locationCp ?: acctCp
        val refund = if (amount.value.signum() > 0 && listOf("退货", "退款", "冲正", "撤销").any { it in summary })
            "ccb_debit_refund" else ""
        val (normalized, enriched) = normalizeSourceCounterparty(counterparty, summary)
        return linkedMapOf(
            "date" to date, "amount" to amount.value, "_amount" to amount, "currency" to currency, "card_number" to card,
            "counterparty" to normalized, "counterparty_account" to ccbCounterpartyAccount(acctRaw),
            "note" to enriched, "category" to if (amount.value.signum() < 0) "expense" else "income",
            "payment_method" to inferCcbPayment(location, card), "summary" to summary, "location" to location,
            "acct_name_raw" to acctRaw, "_raw_cp" to acctCp.ifEmpty { counterparty },
            "_ccb_location_cp" to (locationCp ?: ""), "_ccb_acct_cp" to acctCp,
            "_ccb_refund_signal" to refund, "_source_payload" to payload,
        ).apply { if (refund.isNotEmpty()) put("_refund_signal", refund) }
    }

    private fun parseAsiaNative(payload: Map<String, String>, amount: Amount, date: String, currency: String,
        owner: String, card: String): Map<String, Any?> {
        val summary = text(payload.getValue("摘要")).trimPython()
        val note = listOf(summary, text(payload.getValue("備註")).trimPython(), text(payload.getValue("交易場所")).trimPython())
            .filter { it.isNotEmpty() }.distinct().joinToString(" ")
        return linkedMapOf(
            "date" to date, "amount" to amount.value, "_amount" to amount, "currency" to currency, "card_number" to card,
            "counterparty" to text(payload.getValue("對方戶名")).trimPython(),
            "counterparty_account" to text(payload.getValue("對方賬號")),
            "note" to note, "category" to if (amount.value.signum() >= 0) "income" else "expense",
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
    private val pythonWhitespaceRanges = pythonWhitespace.joinToString("") { "\\x{${it.toString(16)}}" }
    private val pythonWhitespaceClass = "[^$pythonWhitespaceRanges]"
    private val pythonWhitespacePattern = Regex("[$pythonWhitespaceRanges]+")
    private val securitiesTransfer = Regex("^(银行转证券|证券转银行|银转证|证转银)$pythonDigitClass+$pythonWhitespaceClass*$")
    private val ccbPrefixes = listOf("财付通-" to listOf("微信支付-", "微信转账", "消费-"),
        "支付宝-" to listOf("淘宝-", "支付宝外部商户-", "支付宝-转账-", "支付宝-", "消费-"),
        "美团支付-" to listOf("消费-"))


    fun normalizeIcbcDocument(document: CashStatementDocument): ParsedCashDocument {
        val rows = document.rows.map { row ->
            if (row.errorCode?.isNotEmpty() == true || row.payload == null) {
                ParsedCashRow(row.position, null, errorCode = row.errorCode?.takeIf { it.isNotEmpty() } ?: "row_unreadable",
                    disposition = "skipped")
            } else {
                try {
                    normalizeIcbcRow(document, row.payload, row.position, row.documentPosition)
                } catch (_: CashRecordRuleFailure) {
                    ParsedCashRow(row.position, null, errorCode = "row_unreadable", disposition = "skipped")
                } catch (_: IllegalArgumentException) {
                    ParsedCashRow(row.position, null, errorCode = "row_unreadable", disposition = "skipped")
                } catch (_: ArithmeticException) {
                    ParsedCashRow(row.position, null, errorCode = "row_unreadable", disposition = "skipped")
                }
            }
        }
        val declaredRange = document.coverage.copy(complete = true, evidence = "declared-range")
        val usable = rows.all { row ->
            row.issueCodes.isEmpty() && row.errorCode == null &&
                (row.fields == null || declaredRange.contains(row.fields["occurred_at"] as? String))
        }
        val coverage = if (usable) document.coverage else document.coverage.copy(complete = false, evidence = "",
            documentKey = null, pageTotal = null, pageFingerprints = emptyMap())
        return ParsedCashDocument(document.sourceType, document.tableKey, document.parserVersion + "+native-v1",
            rows, document.sourceAccountIdentifier, document.sourceAccountLabel, coverage,
            document.declaredCount, document.observedCount)
    }

    private fun normalizeIcbcRow(document: CashStatementDocument, payload: Map<String, String>, position: Int,
        documentPosition: String?): ParsedCashRow {
        val cells = payload.entries.associate { it.key.replace(pythonWhitespacePattern, "") to it.value }
        val credit = document.sourceType == "icbc_credit"
        val owner = if (credit) fullCard(cells["交易卡号"]) else document.sourceAccountIdentifier
        val issues = mutableListOf<String>()
        var date = (cells[if (credit) "入账日期" else "交易日期"] ?: "").replace("\n", " ").trimPython()
        val occurredAt = try {
            if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}").matches(date))
                throw IllegalArgumentException("bad date")
            normalizeStatementTimestamp(date, document.sourceType)
        } catch (_: IllegalArgumentException) {
            issues += "time_unreadable"; date = ""; null
        } catch (_: ArithmeticException) {
            issues += "time_unreadable"; date = ""; null
        }
        val currency = mapOf("人民币" to "CNY", "CNY" to "CNY", "美元" to "USD", "USD" to "USD",
            "港币" to "HKD", "HKD" to "HKD", "日元" to "JPY", "JPY" to "JPY", "欧元" to "EUR", "EUR" to "EUR")[
            (cells[if (credit) "交易币种" else "币种"] ?: "").replace("\n", "").trimPython()]
        if (currency == null) issues += "currency_unrecognized"
        val value = (cells[if (credit) "交易金额" else "收入/支出金额"] ?: "").replace("\n", "").trimPython()
        var amount: Amount? = null
        if (!Regex("[+-]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,18})?").matches(value))
            issues += "amount_unreadable"
        else amount = CashAmount.parseOrNull(value.replace(",", ""))?.let { amountOf(it, value) }
        if (amount == null && "amount_unreadable" !in issues) issues += "amount_unreadable"
        if (credit) {
            val direction = (cells["收支"] ?: "").trimPython()
            when {
                direction in setOf("借", "支", "支出") -> amount = amount?.abs()?.negate()
                direction in setOf("贷", "收", "收入") && !value.startsWith("-") -> amount = amount?.abs()
                direction.isNotEmpty() || !value.startsWith("+") && !value.startsWith("-") -> {
                    issues += "direction_unrecognized"; amount = null
                }
            }
        }
        var counterparty = (cells["对方户名"] ?: "").replace("\n", "").trimPython()
        var counterpartyAccount = if (credit) "" else (cells["对方账号"] ?: "").replace("\n", "").trimPython()
        var method = if (credit) "工商银行信用卡" else (cells["渠道"] ?: "").replace("\n", "").trimPython()
        var native: Map<String, Any?>? = null
        var nativeExplanation: Map<String, Any?> = emptyMap()
        if (amount != null) {
            native = if (credit) {
                val account = (cells["对方账号"] ?: "").replace("\n", "").trimPython()
                val candidates = if (Regex("[0-9]+[*＊]+[0-9]+").matches(account)) listOf(account) else emptyList()
                parseIcbcCreditNative(payload, date, amount, currency.orEmpty(), cells["交易卡号"], counterparty,
                    candidates, (cells["摘要"] ?: "").replace("\n", "").trimPython(),
                    (cells["交易场所"] ?: "").replace("\n", "").trimPython())
            } else if (date.isNotEmpty()) {
                parseIcbcDebitNative(cells, owner, payload, amount)
            } else {
                val summary = matchDebitSummary((cells["摘要"] ?: "").replace("\n", "").trimPython())
                mapOf("amount" to amount.value, "_amount" to amount, "counterparty" to counterparty, "counterparty_account" to counterpartyAccount,
                    "summary" to summary, "_is_refund" to (summary in setOf("退货", "退款")), "payment_method" to method)
            }
            native?.let {
                counterparty = text(it["counterparty"])
                counterpartyAccount = text(it["counterparty_account"])
                method = text(it["payment_method"])
                nativeExplanation = extractCashSourceExplanation(it)
            }
        }
        val displayName = if (credit) icbcCreditDisplayName(cells, owner) else icbcDebitDisplayName(owner)
        val identity = mapOf("_source_account_identifier" to owner.orEmpty(), "file_account_key" to owner.orEmpty(),
            "card_number" to owner.orEmpty().takeLast(4), "source_display_name" to displayName)
        val cashAmount = amount?.let { CashAmount.parseOrNull(it.format()) }
        val explanation = prepareCashSourceEffects(
            values = mapOf("source_payload" to payload, "amount" to cashAmount),
            patch = mapOf("source_payload" to payload, "amount" to cashAmount),
            sourceType = document.sourceType,
            sourceExplanation = nativeExplanation + identity,
        ).explanation ?: emptyMap()
        val account = CashRecordClassification.normalizeAccount(counterpartyAccount, source = document.sourceType,
            sourceAccountIdentifier = owner.orEmpty(), sourcePayload = payload)
        val record = native?.let { CashRecordClassification.classify(document.sourceType, it) }
        val fields = linkedMapOf<String, Any?>(
            "amount" to amount?.format(),
            "currency" to currency,
            "occurred_at" to occurredAt,
            "record_type" to record?.first,
            "record_subtype" to record?.second,
            "counterparty" to counterparty,
            "counterparty_account" to account.value,
            "counterparty_account_attrs" to account.attrs,
            "note" to cashSourceNote(document.sourceType, payload),
            "source_payload" to LinkedHashMap(payload),
        )
        return ParsedCashRow(position, fields, method, issueCodes = issues,
            sourceAccountIdentifier = if (credit) owner else null, documentPosition = documentPosition,
            sourceExplanation = explanation)
    }

    private fun parseIcbcCreditNative(payload: Map<String, String>, date: String, amount: Amount,
        currency: String, rawOwner: String?, counterparty: String, candidates: List<String>, summary: String,
        description: String): Map<String, Any?> {
        val offset = classifyIcbcCreditOffset(counterparty, description, summary)
        var normalizationDescription = description
        if (normalizationDescription.isEmpty() && inferIcbcPayment(counterparty, "") != "银行卡")
            normalizationDescription = counterparty
        val (normalized, enriched) = normalizeSourceCounterparty(counterparty,
            normalizationDescription.takeCodePoints(80))
        val owner = normalizeIcbcAccountIdentifier(rawOwner.orEmpty())
        val rec = linkedMapOf<String, Any?>(
            "date" to date, "amount" to amount.value, "_amount" to amount, "currency" to currency, "counterparty" to normalized,
            "counterparty_account" to if (summary in setOf("转账", "转帐") && candidates.distinct().size == 1)
                candidates.first() else "",
            "note" to enriched.takeCodePoints(80), "category" to if (amount.value.signum() < 0) "expense" else "income",
            "payment_method" to inferIcbcPayment(normalized, enriched.takeCodePoints(80)),
            "card_number" to owner.takeLast(4), "_source_account_identifier" to owner,
            "file_account_key" to owner, "source_display_name" to "工商银行信用卡", "_raw_cp" to counterparty,
            "summary" to summary, "refund_signal" to "", "_refund_signal" to "", "_source_payload" to payload,
        )
        if (offset.isNotEmpty()) rec["offset_type"] = offset
        if (offset == "merchant_refund") {
            val merchant = if (counterparty == "退货" && description.isNotEmpty()) description else counterparty
            rec.putAll(mapOf("_is_refund" to true, "_refund_signal" to "icbc_credit_return",
                "refund_signal" to if (summary == "退货") "icbc_credit_return" else "",
                "_icbc_refund_merchant_trusted" to !untrustedMerchant(merchant)))
        } else if (offset in setOf("benefit_rebate", "campaign_cashback", "fee_reversal")) {
            rec.putAll(mapOf("offset_type" to offset, "offset_strength" to "strong",
                "offset_action" to "keep_as_offset_income"))
        }
        return rec
    }

    private fun parseIcbcDebitNative(cells: Map<String, String>, rawOwner: String?, payload: Map<String, String>,
        amount: Amount): Map<String, Any?> {
        val row = listOf("交易日期", "账号", "储种", "序号", "币种", "钞汇", "摘要", "地区",
            "收入/支出金额", "余额", "对方户名", "对方账号", "渠道").map { cells[it] ?: "" }
        val dt = row[0].replace("\n", " ")
        val dateMatch = Regex("(\\d{4}-\\d{2}-\\d{2})").find(dt)
            ?: throw CashRecordRuleFailure("工行借记卡行无法提取日期")
        if (row.size < 13) throw CashRecordRuleFailure("工行借记卡行列数不足")
        val timeMatch = Regex("(\\d{2}:\\d{2}:\\d{2})").find(dt)
        val date = "${dateMatch.groupValues[1]} ${timeMatch?.groupValues?.get(1) ?: "00:00:00"}"
        val currency = mapOf("人民币" to "CNY", "美元" to "USD", "港币" to "HKD", "日元" to "JPY")[
            row[4].replace("\n", "").trimPython()] ?: "CNY"
        val summary = matchDebitSummary(row[6].replace("\n", "").trimPython())
        var counterparty = row[10].replace("\n", "").trimPython()
        val counterpartyAccount = row[11].replace("\n", "").trimPython()
        if (counterparty.isNotEmpty() && summary.isNotEmpty()) {
            if (isSubsequence("基金快速赎回", counterparty) || isSubsequence("基金赎回", counterparty) ||
                isSubsequence("基金购买", counterparty))
                counterparty = "中国工商银行股份有限公司基金清算专户"
        }
        val channel = row[12].replace("\n", "").trimPython()
        val owner = normalizeIcbcAccountIdentifier(rawOwner.orEmpty())
        val offset = if (summary == "撤销交易") "reversal" else if (summary in setOf("退款", "退货")) "refund" else ""
        return linkedMapOf(
            "date" to date, "amount" to amount.value, "_amount" to amount, "currency" to currency, "counterparty" to counterparty,
            "counterparty_account" to counterpartyAccount, "note" to summary,
            "category" to if (amount.value.signum() < 0) "expense" else "income", "payment_method" to channel,
            "card_number" to owner.takeLast(4), "_source_account_identifier" to owner,
            "file_account_key" to owner, "source_display_name" to "工商银行借记卡", "_raw_cp" to counterparty,
            "_debit_offset_type" to offset, "_is_refund" to (offset == "refund"), "_is_reversal" to (offset == "reversal"),
            "summary" to summary, "refund_signal" to if (summary == "退货") "icbc_debit_return" else "",
            "_refund_signal" to if (offset == "refund" && amount.value.signum() > 0) "icbc_debit_refund" else "",
            "_source_payload" to payload,
        )
    }

    private fun classifyIcbcCreditOffset(counterparty: String, description: String, summary: String): String {
        val text = listOf(summary, counterparty, description).filter { it.isNotEmpty() }.joinToString(" ")
        return when {
            "减免年费" in text -> "fee_reversal"
            "刷卡金入账" in text || "刷卡金退款" in text -> "benefit_rebate"
            "返现" in text || "Rebate" in text -> "campaign_cashback"
            "退货" in text || "退款" in text -> "merchant_refund"
            else -> ""
        }
    }

    private fun untrustedMerchant(value: String): Boolean {
        val text = value.trimPython()
        if (text.isEmpty() || text in setOf("A", "F", "D", "工商", "银行", "中国", "请扫描二维码")) return true
        if (Regex("[:${pythonDigitClass}$pythonWhitespaceRanges.-]+").matches(text)) return true
        return text.codePointCount(0, text.length) <= 1
    }

    private fun fullCard(value: String?): String? {
        val normalized = normalizeIcbcAccountIdentifier(value.orEmpty())
        return normalized.takeIf { Regex("[0-9]{12,19}").matches(it) }
    }

    private fun normalizeIcbcAccountIdentifier(value: String): String {
        val text = value.replace(Regex("[$pythonWhitespaceRanges\\-－—()（）]"), "")
        return when {
            Regex("\\d{12,19}").matches(text) -> text
            Regex("\\d{4,6}[*＊]{2,}\\d{4}").matches(text) -> text.replace('＊', '*')
            else -> ""
        }
    }

    private fun icbcCreditDisplayName(cells: Map<String, String>, owner: String?): String {
        val sourceCard = (cells["交易卡号"] ?: "").replace(pythonWhitespacePattern, "")
        val masked = if (owner == null) Regex("[0-9]{4,6}[*＊]{2,}([0-9]{4})").matchEntire(sourceCard) else null
        val tail = if (!owner.isNullOrEmpty()) owner.takeLast(4) else masked?.groupValues?.get(1).orEmpty()
        return cleanIcbcDisplayName("中国工商银行信用卡" + if (tail.isEmpty()) "" else "（尾号 $tail）")
    }

    private fun icbcDebitDisplayName(owner: String?): String {
        val tail = owner.orEmpty().takeLast(4)
        return cleanIcbcDisplayName("中国工商银行借记卡" + if (tail.isEmpty()) "" else "（尾号 $tail）")
    }

    private fun cleanIcbcDisplayName(raw: String): String {
        val compact = raw.replace(pythonWhitespacePattern, "")
        val tailMatch = Regex("[（(](?:尾号)?([0-9]{4})[）)]$").find(compact)
        val body = if (tailMatch != null) compact.substring(0, tailMatch.range.first) else compact
        val bank = Regex("([\u4e00-\u9fff]+银行)([\u4e00-\u9fff]*卡)?").matchEntire(body) ?: return raw
        var bankName = bank.groupValues[1]
        if (bankName.startsWith("中国") && bankName != "中国银行") bankName = bankName.substring(2)
        return bankName + bank.groupValues[2] + if (tailMatch != null) "(${tailMatch.groupValues[1]})" else ""
    }

    private fun matchDebitSummary(text: String): String {
        val clean = text.replace(Regex("[^\u4e00-\u9fff]"), "")
        val keywords = listOf("支付宝转账", "无卡支付", "工资", "转账", "购汇还款", "撤销交易", "个人购汇",
            "跨境汇款", "利息", "基金购买", "基金赎回", "他行汇入", "银联入账", "预约购汇", "网转", "跨行汇款")
        return keywords.firstOrNull { isSubsequence(it, clean) } ?: clean
    }

    private fun isSubsequence(pattern: String, text: String): Boolean {
        var index = 0
        for (char in text) if (index < pattern.length && pattern[index] == char) index++
        return index == pattern.length
    }

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
