package com.finance.tracker.backend

import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal data class WeChatRefundMatch(val expenseIndex: Int, val ruleId: String, val confidence: String = "strong")

/** 平台（支付宝/微信）退款配对的纯规则；不写关系。 */
internal object CashPlatformRefund {
    const val WECHAT_FULL_REFUND_STATUS = "已全额退款"
    const val WECHAT_TRANSFER_RETURN_STATUS = "对方已退还"

    fun alipayOrderMatch(refundTxnId: String?, originTxnId: String?): Boolean {
        val refund = refundTxnId.orEmpty().trim()
        val origin = originTxnId.orEmpty().trim()
        if (refund.isEmpty() || origin.isEmpty()) return false
        return refund == origin || refund.startsWith(origin + "_") || refund.startsWith(origin + "*")
    }
    fun alipayFindOriginIndex(refundTxnId: String?, originTxnIds: List<String?>): Int? {
        val hits = originTxnIds.indices.filter { alipayOrderMatch(refundTxnId, originTxnIds[it]) }
        return if (hits.size == 1) hits.single() else null
    }
    fun alipayIsUnpaidClosed(status: String?, direction: String?, paymentMethod: String?): Boolean =
        status.orEmpty().trim() in setOf("交易关闭", "已关闭") && direction.orEmpty().trim() != "支出" &&
            paymentMethod.orEmpty().trim().isEmpty()
    fun alipayIsFailedRepay(status: String?, direction: String?, paymentMethod: String?): Boolean =
        status.orEmpty().trim() == "还款失败" && direction.orEmpty().trim() == "不计收支" &&
            paymentMethod.orEmpty().trim().isEmpty()
    fun alipayIsPaidClosedExpense(status: String?, direction: String?): Boolean =
        status.orEmpty().trim() == "交易关闭" && direction.orEmpty().trim() == "支出"
    fun alipayIsAuthHold(status: String?): Boolean = status.orEmpty().trim() == "芝麻免押下单成功"
    fun alipayIsUnfreeze(status: String?): Boolean = status.orEmpty().trim() == "解冻成功"

    fun toDecimal(value: Any?): BigDecimal? = runCatching { BigDecimal(value.toString()) }.getOrNull()

    fun wechatEmbeddedRefundAmount(status: String?): BigDecimal? {
        val text = status.orEmpty().trim()
        val match = Regex("已退款[（(]?\\s*¥?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*[)）]?").find(text) ?: return null
        if (text == WECHAT_FULL_REFUND_STATUS) return null
        return toDecimal(match.groupValues[1])
    }
    fun wechatIsRefundOriginExpense(direction: String?, status: String?): Boolean {
        if (direction.orEmpty().trim() != "支出") return false
        val value = status.orEmpty().trim()
        return value == WECHAT_FULL_REFUND_STATUS || value == WECHAT_TRANSFER_RETURN_STATUS ||
            wechatEmbeddedRefundAmount(value) != null
    }
    fun wechatIsRefundIncomeLeg(direction: String?, status: String?, txnType: String? = ""): Boolean {
        if (direction.orEmpty().trim() != "收入") return false
        if ("退款" in status.orEmpty().trim()) return true
        return "-退款" in txnType.orEmpty().trim()
    }
    fun wechatRefundPayCompatible(expensePay: String?, incomePay: String?): Boolean {
        val e = expensePay.orEmpty().trim(); val i = incomePay.orEmpty().trim()
        if (e.isEmpty() || i.isEmpty() || e in setOf("/", "-") || i in setOf("/", "-")) return true
        return e == i
    }
    fun wechatMerchantKeyMatch(expenseMerchantOrderId: String?, incomeTxnId: String?): Boolean {
        val e = expenseMerchantOrderId.orEmpty().trim(); val i = incomeTxnId.orEmpty().trim()
        return e.isNotEmpty() && i.isNotEmpty() && e == i
    }
    fun wechatCounterpartyCompatible(expenseCp: String?, incomeCp: String?, incomeType: String? = ""): Boolean {
        val e = expenseCp.orEmpty().trim(); val i = incomeCp.orEmpty().trim(); val t = incomeType.orEmpty().trim()
        if (e.isEmpty() || i.isEmpty() || e in setOf("/", "-") || i in setOf("/", "-")) {
            if ("转账" in t || "退款" in t) return true
            return e.isEmpty() || i.isEmpty() || e in setOf("/", "-") || i in setOf("/", "-")
        }
        if (e == i || e in i || i in e) return true
        val brand = if ("-退款" in t) t.replace("-退款", "") else ""
        return brand.isNotEmpty() && (brand in e || e in brand)
    }

    fun parseDt(value: Any?): LocalDateTime? {
        var text = value?.toString().orEmpty().trim().replace("/", "-")
        if (text.isEmpty()) return null
        runCatching {
            val parsed = java.time.OffsetDateTime.parse(text.replace("Z", "+00:00"))
            return parsed.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()
        }
        runCatching { return LocalDateTime.parse(text.replace("Z", "")) }
        text = text.take(19)
        for (format in listOf("uuuu-MM-dd HH:mm:ss", "uuuu-MM-dd HH:mm", "uuuu-MM-dd")) {
            runCatching {
                val formatter = DateTimeFormatter.ofPattern(format)
                return if (format == "uuuu-MM-dd") LocalDate.parse(text, formatter).atStartOfDay()
                else LocalDateTime.parse(text, formatter)
            }
        }
        return null
    }

    private fun field(row: Map<String, Any?>, vararg keys: String, default: Any? = ""): Any? {
        for (key in keys) if (key in row && row[key] != null) return row[key]
        return default
    }
    private fun text(value: Any?): String = value?.toString().orEmpty()
    private fun seconds(from: LocalDateTime, to: LocalDateTime): Double =
        Duration.between(from, to).toNanos() / 1_000_000_000.0

    fun wechatFindExpenseForRefund(income: Map<String, Any?>, expenses: List<Map<String, Any?>>,
        usedExpenseIndexes: Set<Int> = emptySet(), maxWindow: Duration = Duration.ofDays(60)): WeChatRefundMatch? {
        val incomeDt = parseDt(income["date"] ?: income["occurred_at"])
        val incomeAmountRaw = toDecimal(income["amount"]) ?: return null
        val incomeAmount = incomeAmountRaw.abs()
        val incomePay = text(income["pay"] ?: income["payment_method"])
        val incomeCp = text(income["cp"] ?: income["counterparty"])
        val incomeType = text(income["type"] ?: income["txn_type"])
        val incomeTxn = text(income["txn"] ?: income["txn_id"])
        val incomeMerchant = text(income["mer"] ?: income["merchant_order_id"])
        if (listOf("转账", "红包", "群收款").any { it in incomeType }) return null

        val r1 = mutableListOf<Int>()
        for ((index, expense) in expenses.withIndex()) {
            if (index in usedExpenseIndexes) continue
            if (text(field(expense, "record_type")) !in setOf("consumption", "refund")) continue
            val amount = toDecimal(field(expense, "amount", default = 0)) ?: continue
            if (amount.signum() >= 0) continue
            val merchant = text(field(expense, "mer", "merchant_order_id"))
            val txn = text(field(expense, "txn", "txn_id"))
            if (wechatMerchantKeyMatch(merchant, incomeTxn)) r1 += index
            else if (incomeMerchant.isNotEmpty() && merchant.isNotEmpty() && merchant == incomeMerchant) r1 += index
            else if (txn.isNotEmpty() && incomeTxn.isNotEmpty() && txn == incomeTxn) r1 += index
        }
        if (r1.size == 1) return WeChatRefundMatch(r1.single(), "import.wechat.mer_txn.v1")
        if (r1.size > 1) return null

        val full = mutableListOf<Pair<Double, Int>>()
        for ((index, expense) in expenses.withIndex()) {
            if (index in usedExpenseIndexes) continue
            if (text(field(expense, "status")) != WECHAT_FULL_REFUND_STATUS) continue
            val amount = toDecimal(field(expense, "amount")) ?: continue
            if (amount.abs().compareTo(incomeAmount) != 0) continue
            if (!wechatRefundPayCompatible(text(field(expense, "pay", "payment_method")), incomePay)) continue
            val expenseDt = parseDt(field(expense, "date", "occurred_at"))
            if (incomeDt != null && expenseDt != null) {
                val delta = seconds(expenseDt, incomeDt)
                if (delta < 0 || delta > maxWindow.seconds.toDouble()) continue
                full += delta to index
            } else full += 0.0 to index
        }
        closest(full, "import.wechat.full_status_pay.v1")?.let { return it }
        if (full.size > 1) return null

        val partial = mutableListOf<Pair<Double, Int>>()
        for ((index, expense) in expenses.withIndex()) {
            if (index in usedExpenseIndexes) continue
            val embedded = wechatEmbeddedRefundAmount(text(field(expense, "status"))) ?: continue
            if (embedded.compareTo(incomeAmount) != 0) continue
            if (!wechatRefundPayCompatible(text(field(expense, "pay", "payment_method")), incomePay)) continue
            if (!wechatCounterpartyCompatible(text(field(expense, "cp", "counterparty")), incomeCp, incomeType)) continue
            val expenseDt = parseDt(field(expense, "date", "occurred_at"))
            if (incomeDt != null && expenseDt != null) {
                val delta = seconds(expenseDt, incomeDt)
                if (delta < 0 || delta > maxWindow.seconds.toDouble()) continue
                partial += delta to index
            } else partial += 0.0 to index
        }
        closest(partial, "import.wechat.partial_embedded.v1")?.let { return it }
        if (partial.size > 1) return null

        val residual = mutableListOf<Pair<Double, Int>>()
        for ((index, expense) in expenses.withIndex()) {
            if (index in usedExpenseIndexes) continue
            val embedded = wechatEmbeddedRefundAmount(text(field(expense, "status"))) ?: continue
            val amount = toDecimal(field(expense, "amount")) ?: continue
            if (embedded <= incomeAmount) continue
            if (amount.abs() < embedded) continue
            if (!wechatRefundPayCompatible(text(field(expense, "pay", "payment_method")), incomePay)) continue
            val expenseCp = text(field(expense, "cp", "counterparty"))
            if (!(wechatCounterpartyCompatible(expenseCp, incomeCp, incomeType) ||
                    expenseCp.isNotEmpty() && incomeCp.isNotEmpty() && expenseCp.take(2) == incomeCp.take(2))) continue
            val expenseDt = parseDt(field(expense, "date", "occurred_at"))
            if (incomeDt != null && expenseDt != null) {
                val delta = seconds(expenseDt, incomeDt)
                if (delta < 0 || delta > Duration.ofDays(7).seconds.toDouble()) continue
                residual += delta to index
            } else residual += 0.0 to index
        }
        if (residual.size == 1) return WeChatRefundMatch(residual.single().second, "import.wechat.residual.v1")
        if (residual.size > 1) {
            val sorted = residual.sortedWith(compareBy({ it.first }, { it.second }))
            if (sorted[0].first != sorted[1].first) return WeChatRefundMatch(sorted[0].second, "import.wechat.residual.v1")
        }
        return null
    }

    private fun closest(values: List<Pair<Double, Int>>, ruleId: String): WeChatRefundMatch? {
        if (values.size == 1) return WeChatRefundMatch(values.single().second, ruleId)
        if (values.size > 1) {
            val sorted = values.sortedWith(compareBy({ it.first }, { it.second }))
            return if (sorted[0].first != sorted[1].first) WeChatRefundMatch(sorted[0].second, ruleId) else null
        }
        return null
    }

    fun pairWechatRefunds(rows: List<Map<String, Any?>>): List<Triple<Int, Int, String>> {
        val expenses = mutableListOf<Int>()
        val incomes = mutableListOf<Int>()
        for ((index, row) in rows.withIndex()) {
            val amount = toDecimal(row["amount"]) ?: continue
            val recordType = text(row["record_type"])
            if (amount.signum() < 0 && recordType in setOf("consumption", "refund")) expenses += index
            if (amount.signum() > 0 && recordType == "refund") incomes += index
        }
        val expenseRows = expenses.map { index ->
            rows[index].toMutableMap().apply { if ("direction" !in this) put("direction", "支出"); if ("dir" !in this) put("dir", "支出") }
        }
        val used = mutableSetOf<Int>()
        val pairs = mutableListOf<Triple<Int, Int, String>>()
        for (incomeIndex in incomes.sortedBy { text(rows[it]["date"]) }) {
            val income = rows[incomeIndex].toMutableMap().apply {
                if ("direction" !in this) put("direction", "收入"); if ("dir" !in this) put("dir", "收入") }
            if (!wechatIsRefundIncomeLeg("收入", text(income["status"]),
                    text(income["txn_type"] ?: income["type"]))) continue
            val match = wechatFindExpenseForRefund(income, expenseRows, used)
            if (match == null) {
                val retry = wechatFindExpenseForRefund(income, expenseRows, emptySet())
                if (retry != null && retry.ruleId == "import.wechat.residual.v1")
                    pairs += Triple(expenses[retry.expenseIndex], incomeIndex, retry.ruleId)
                continue
            }
            if (match.ruleId != "import.wechat.residual.v1") used += match.expenseIndex
            pairs += Triple(expenses[match.expenseIndex], incomeIndex, match.ruleId)
        }
        return pairs
    }

    fun pairWechatTransferReturns(rows: List<Map<String, Any?>>,
        maxWindow: Duration = Duration.ofDays(60)): List<Triple<Int, Int, String>> {
        fun value(row: Map<String, Any?>, vararg keys: String): String {
            for (key in keys) row[key]?.let { return it.toString().trim() }
            return ""
        }
        val expenses = mutableListOf<Int>(); val incomes = mutableListOf<Int>()
        for ((index, row) in rows.withIndex()) {
            val amount = toDecimal(row["amount"]) ?: continue
            val recordType = text(row["record_type"])
            if (amount.signum() < 0 && recordType == "transfer_reversal") expenses += index
            else if (amount.signum() > 0 && recordType == "transfer_reversal") {
                val status = text(row["status"] ?: row["platform_status"])
                val txnType = text(row["txn_type"] ?: row["type"])
                if ("退款" in status || "退款" in txnType) incomes += index
            }
        }
        fun transactionLink(expense: Map<String, Any?>, income: Map<String, Any?>): Boolean {
            val expenseTxn = value(expense, "txn_id", "txn"); val expenseMerchant = value(expense, "merchant_order_id", "mer")
            val incomeTxn = value(income, "txn_id", "txn"); val incomeMerchant = value(income, "merchant_order_id", "mer")
            return (expenseMerchant.isNotEmpty() && incomeTxn.isNotEmpty() && expenseMerchant == incomeTxn) ||
                (incomeMerchant.isNotEmpty() && expenseTxn.isNotEmpty() && incomeMerchant == expenseTxn)
        }
        fun sameAccount(expense: Map<String, Any?>, income: Map<String, Any?>): Boolean {
            val expenseAccount = value(expense, "account_id"); val incomeAccount = value(income, "account_id")
            if (expenseAccount.isNotEmpty() && incomeAccount.isNotEmpty()) return expenseAccount == incomeAccount
            val expensePay = value(expense, "payment_method", "pay"); val incomePay = value(income, "payment_method", "pay")
            return expensePay.isNotEmpty() && incomePay.isNotEmpty() && expensePay == incomePay
        }
        fun dateKey(index: Int): String = listOf(value(rows[index], "date", "occurred_at"),
            value(rows[index], "txn_id", "txn"), value(rows[index], "id")).joinToString(" ")
        val used = mutableSetOf<Int>(); val pairs = mutableListOf<Triple<Int, Int, String>>()
        for (incomeIndex in incomes.sortedBy(::dateKey)) {
            val income = rows[incomeIndex]
            val incomeAmount = toDecimal(income["amount"]) ?: continue
            val incomeCurrency = value(income, "currency").uppercase()
            val incomeDt = parseDt(value(income, "date", "occurred_at"))
            val candidates = mutableListOf<Int>()
            for (expenseIndex in expenses) {
                if (expenseIndex in used) continue
                val expense = rows[expenseIndex]
                val expenseAmount = toDecimal(expense["amount"]) ?: continue
                if (expenseAmount.abs().compareTo(incomeAmount.abs()) != 0) continue
                val expenseCurrency = value(expense, "currency").uppercase()
                if (expenseCurrency.isEmpty() || incomeCurrency.isEmpty() || expenseCurrency != incomeCurrency) continue
                if (!sameAccount(expense, income) || !transactionLink(expense, income)) continue
                val expenseDt = parseDt(value(expense, "date", "occurred_at"))
                if (incomeDt == null || expenseDt == null) continue
                val delta = seconds(expenseDt, incomeDt)
                if (delta < 0 || delta > maxWindow.seconds.toDouble()) continue
                candidates += expenseIndex
            }
            if (candidates.size != 1) continue
            val expenseIndex = candidates.single()
            used += expenseIndex
            pairs += Triple(expenseIndex, incomeIndex, "import.wechat.transfer_return.v1")
        }
        return pairs
    }
}
