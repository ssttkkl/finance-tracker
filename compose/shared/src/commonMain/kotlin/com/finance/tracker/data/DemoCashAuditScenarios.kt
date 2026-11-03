package com.finance.tracker.data

import com.finance.tracker.core.getLocalDateForInstant
import com.finance.tracker.domain.*

internal data class DemoAuditRelation(
    val id: String, val kind: String, val members: List<String>, val suggestionId: String?,
    var state: String = "draft", var active: Boolean = true, var revision: Long = 1,
)
internal data class DemoImportRow(
    val id: String, val sourceRef: String, val sourceType: String, val accountName: String,
    val currency: String?, val accountId: String?, val values: Map<String, String?>,
)

internal fun demoBasicImportRows() = listOf(
    DemoImportRow("coffee", "demo-source", "demo_bank", "日常账户", "CNY", "1", // i18n-allow: demo-fixture
        mapOf("occurred_at" to "2026-10-09T04:30:00Z", "amount" to "-28.00", "counterparty" to "山海咖啡", "note" to "午后咖啡", "record_type" to "consumption", "category_id" to "food")), // i18n-allow: demo-fixture
    DemoImportRow("income", "demo-source", "demo_bank", "日常账户", "CNY", "1", // i18n-allow: demo-fixture
        mapOf("occurred_at" to "2026-10-10T01:00:00Z", "amount" to "12000.00", "counterparty" to "示例公司", "note" to "月度收入", "record_type" to "income", "category_id" to "income")), // i18n-allow: demo-fixture
)

internal fun demoAuditImportRows(): List<DemoImportRow> = demoBasicImportRows() + listOf(
    DemoImportRow("wechat", "audit-wechat", "wechat", "小林的微信零钱", "CNY", "4", // i18n-allow: demo-fixture
        mapOf("occurred_at" to "2026-10-28T09:00:00Z", "amount" to "-56.80", "counterparty" to "街角餐厅", "note" to "晚餐", "record_type" to "consumption", "category_id" to "food")), // i18n-allow: demo-fixture
    DemoImportRow("alipay", "audit-alipay", "alipay", "支付宝（demo***@example.test）", "CNY", "5", // i18n-allow: demo-fixture
        mapOf("occurred_at" to "2026-10-27T09:00:00Z", "amount" to "36.50", "counterparty" to "城市书店", "note" to "退货退款", "record_type" to "refund", "record_subtype" to "refund")), // i18n-allow: demo-fixture
    DemoImportRow("usd", "audit-card", "demo_bank", "银行卡（尾号 1024）", "USD", "2", // i18n-allow: demo-fixture
        mapOf("occurred_at" to "2026-10-26T09:00:00Z", "amount" to "-19.99", "counterparty" to "Cloud Music", "note" to "年度订阅", "record_type" to "consumption")), // i18n-allow: demo-fixture
    DemoImportRow("hkd", "audit-hkd", "demo_bank", "旅行账户（尾号 2048）", "HKD", "6", // i18n-allow: demo-fixture
        mapOf("occurred_at" to "2026-10-25T09:00:00Z", "amount" to "-88.00", "counterparty" to "海港茶餐厅", "note" to "旅行午餐", "record_type" to "consumption", "category_id" to "food")), // i18n-allow: demo-fixture
    DemoImportRow("unknown", "audit-wechat", "wechat", "小林的微信零钱", null, "4", // i18n-allow: demo-fixture
        mapOf("occurred_at" to null, "amount" to null, "counterparty" to "待补齐账单", "note" to "账单未提供金额和日期", "record_type" to "consumption")), // i18n-allow: demo-fixture
)

internal fun DemoFinanceRepository.seedAuditScenarios() {
    saveDemoAccount("4", "微信零钱", "cash", true, listOf("CNY")) // i18n-allow: demo-fixture
    saveDemoAccount("5", "支付宝余额", "cash", true, listOf("CNY")) // i18n-allow: demo-fixture
    saveDemoAccount("6", "旅行账户", "cash", true, listOf("HKD")) // i18n-allow: demo-fixture
    fun add(id: String, day: String?, amount: String?, name: String, account: String?, currency: String? = "CNY",
            type: String = "consumption", label: String? = null, note: String = "", state: String = "posted", source: String = "wechat") {
        fun value(text: String?): StructuredValue = text?.let(::StructuredText) ?: StructuredNull
        val fields = mapOf("occurred_at" to value(day?.let { "2026-$it" }), "amount" to value(amount), "currency" to value(currency),
            "counterparty" to value(name), "account_id" to value(account), "account_name" to value(demoAccounts().firstOrNull { it.id.toString() == account }?.name),
            "record_type" to value(type), "record_subtype" to value(label ?: "not_applicable"), "note" to value(note),
            "source_type" to value(source), "counterparty_account" to value(""), "counterparty_account_attrs" to StructuredArray(emptyList()),
            "category_id" to (if (type == "consumption") value("food") else StructuredNull),
            "components" to StructuredArray(listOf(StructuredObject(mapOf("component_id" to value("part-$id"), "amount" to value(amount),
                "currency" to value(currency), "account_id" to value(account), "label" to value(""))))))
        seedAuditRecord(id, StructuredObject(fields + ("source_payload" to StructuredObject(fields.filterKeys { it != "components" }))), state, label)
    }
    add("audit-single", "10-29T04:30:00Z", "-28.00", "山海咖啡", "4", note = "午后咖啡，待核对另一份账单") // i18n-allow: demo-fixture
    add("audit-single-card", "10-29T04:31:00Z", "-28.00", "山海咖啡", "4", source = "icbc") // i18n-allow: demo-fixture
    add("audit-unavailable-currency", "10-29T05:00:00Z", "-28.00", "山海咖啡（外币订单）", "2", "USD", source = "icbc") // i18n-allow: demo-fixture
    add("audit-unavailable-amount", "10-29T05:30:00Z", "-35.00", "山海咖啡（另一笔订单）", "4", source = "icbc") // i18n-allow: demo-fixture
    add("audit-multiple", "10-28T10:00:00Z", "-168.00", "云杉超市", "5", source = "alipay") // i18n-allow: demo-fixture
    add("audit-multiple-a", "10-28T10:01:00Z", "-168.00", "云杉超市（账单 A）", "5", source = "icbc") // i18n-allow: demo-fixture
    add("audit-multiple-b", "10-28T10:02:00Z", "-168.00", "云杉超市（账单 B）", "5", source = "icbc") // i18n-allow: demo-fixture
    add("audit-joint", "10-27T06:00:00Z", "-120.00", "青禾家居", "4", note = "同笔支付与退款一起核对") // i18n-allow: demo-fixture
    add("audit-joint-card", "10-27T06:01:00Z", "-120.00", "青禾家居", "4", source = "icbc") // i18n-allow: demo-fixture
    add("audit-joint-refund", "10-27T07:00:00Z", "120.00", "青禾家居退款", "4", type = "refund") // i18n-allow: demo-fixture
    add("audit-return", "10-26T08:00:00Z", "-88.00", "小周", "4", type = "transfer_reversal", label = "p2p_return", note = "转账退回") // i18n-allow: demo-fixture
    add("audit-return-in", "10-26T08:01:00Z", "88.00", "小周", "4", type = "transfer_reversal", label = "p2p_return") // i18n-allow: demo-fixture
    auditRelations += DemoAuditRelation("audit-return-pair", "refund_offset", listOf("audit-return", "audit-return-in"), null, "confirmed")
    add("audit-usd-income", "10-25T09:00:00Z", "1500.00", "Studio North", "2", "USD", "income", source = "icbc")
    add("audit-usd-expense", "10-25T10:00:00Z", "-19.99", "Cloud Music", "2", "USD", note = "年度订阅", source = "icbc") // i18n-allow: demo-fixture
    add("audit-hkd-income", "10-24T09:00:00Z", "800.00", "旅途报销", "6", "HKD", "income", source = "icbc") // i18n-allow: demo-fixture
    add("audit-hkd-expense", "10-24T10:00:00Z", "-88.00", "海港茶餐厅", "6", "HKD", source = "icbc") // i18n-allow: demo-fixture
    add("audit-long", "10-23T11:00:00Z", "-2580.50", "青山湖畔生活用品与家居设计集合店（城东旗舰店）", "5", note = "床上用品、收纳盒与家居用品；核对长商户名称和完整金额的展示", source = "alipay") // i18n-allow: demo-fixture
    add("audit-draft-account", "10-22T09:00:00Z", "-46.50", "街角餐厅", null, state = "draft", note = "尚未选择对应账户") // i18n-allow: demo-fixture
    add("audit-unknown", null, null, "待补齐账单", null, null, state = "draft", note = "账单未提供金额、币种和日期") // i18n-allow: demo-fixture
    add("audit-zero", "10-21T09:00:00Z", "0.00", "城市书店", "5", note = "零金额记录", source = "alipay") // i18n-allow: demo-fixture
    add("audit-september", "09-30T09:00:00Z", "-72.80", "日用百货", "1", source = "icbc") // i18n-allow: demo-fixture
    add("audit-confirmed", "10-20T09:00:00Z", "-52.00", "街角餐厅", "4") // i18n-allow: demo-fixture
    add("audit-confirmed-card", "10-20T09:01:00Z", "-52.00", "街角餐厅", "4", source = "icbc") // i18n-allow: demo-fixture
    auditRelations += DemoAuditRelation("audit-existing-mirror", "payment_mirror", listOf("audit-confirmed", "audit-confirmed-card"), "audit-existing", "confirmed")
    fun suggest(id: String, targets: List<List<String>>, joint: Boolean = false) {
        val refs = listOf(ImportMemberRef(null, id))
        val candidates = targets.mapIndexed { index, target ->
            val members = refs + target.map { ImportMemberRef(null, it) }
            val relations = listOf(ImportPairRelation("payment_mirror", members.take(2))) +
                if (joint) listOf(ImportPairRelation("refund_offset", listOf(refs[0], members.last()))) else emptyList()
            ImportPairCandidate("candidate-$id-$index", null, members, null, relations)
        }
        val suggestionId = "suggestion-$id"
        auditSuggestions[id] = ImportPairOption(suggestionId, "payment_mirror", "defer", null, candidates, refs, listOf("accept", "ignore"), null)
        auditRelations += DemoAuditRelation("relation-$id", "payment_mirror", listOf(id) + targets.first(), suggestionId)
    }
    suggest("audit-single", listOf(listOf("audit-single-card")))
    suggest("audit-multiple", listOf(listOf("audit-multiple-a"), listOf("audit-multiple-b")))
    suggest("audit-joint", listOf(listOf("audit-joint-card", "audit-joint-refund")), true)
    createDemoCashTransfer(StructuredObject(mapOf("from_account" to StructuredText("日常账户"), "to_account" to StructuredText("银行卡"), // i18n-allow: demo-fixture
        "from_currency" to StructuredText("CNY"), "to_currency" to StructuredText("CNY"), "amount" to StructuredText("500.00"),
        "date" to StructuredText("2026-10-19"), "time" to StructuredText("09:00:00"), "note" to StructuredText("账户间转账")))) // i18n-allow: demo-fixture
    createDemoCashTransfer(StructuredObject(mapOf("from_account" to StructuredText("银行卡"), "to_account" to StructuredText("银行卡"), // i18n-allow: demo-fixture
        "from_currency" to StructuredText("CNY"), "to_currency" to StructuredText("USD"), "amount" to StructuredText("710.00"), "to_amount" to StructuredText("100.00"),
        "date" to StructuredText("2026-10-18"), "time" to StructuredText("09:00:00"), "note" to StructuredText("换汇")))) // i18n-allow: demo-fixture
}

internal fun demoMonthlySummaries(items: List<CashProjection>): List<CashMonthlySummary> = items
    .filter { it.statisticsEligible && it.economicType != "internal_transfer" && it.amount != null && it.currency != null && it.occurredAt != null }
    .groupBy { getLocalDateForInstant(it.occurredAt!!).take(7) }.entries.sortedByDescending { it.key }
    .map { (month, rows) -> CashMonthlySummary(month, rows.groupBy { it.currency!! }.entries.sortedBy { it.key }.map { (currency, records) ->
        CashMonthlyCurrencySummary(currency, demoUnsignedSum(records.mapNotNull { it.amount }.filter { compareDemoDecimals(it, "0") >= 0 }),
            demoUnsignedSum(records.mapNotNull { it.amount }.filter { compareDemoDecimals(it, "0") < 0 }.map { it.removePrefix("-") }))
    }) }

private fun demoUnsignedSum(values: List<String>): String {
    if (values.isEmpty()) return "0"
    val scale = values.maxOf { it.substringAfter('.', "").length }
    var sum = "0"
    values.forEach { value ->
        require(isExactDecimalString(value) && !value.startsWith('-'))
        val unsigned = value.removePrefix("+")
        val digits = unsigned.substringBefore('.') + unsigned.substringAfter('.', "").padEnd(scale, '0')
        val width = maxOf(sum.length, digits.length)
        val a = sum.padStart(width, '0'); val b = digits.padStart(width, '0'); var carry = 0
        val output = StringBuilder()
        for (index in width - 1 downTo 0) {
            val digit = a[index].digitToInt() + b[index].digitToInt() + carry
            output.append(('0'.code + digit % 10).toChar()); carry = digit / 10
        }
        if (carry > 0) output.append(carry)
        sum = output.reverse().toString().trimStart('0').ifEmpty { "0" }
    }
    val padded = sum.padStart(scale + 1, '0')
    return if (scale == 0) padded else padded.dropLast(scale) + "." + padded.takeLast(scale)
}
