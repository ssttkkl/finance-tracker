package com.finance.tracker.backend

internal data class CashSourceEffects(val values: Map<String, Any?>, val explanation: Map<String, Any?>?)

private val sourceTextKeys = setOf(
    "txn_type", "txn_id", "merchant_order_id", "platform_status", "status", "_wechat_direction",
    "_alipay_direction", "direction", "summary", "refund_signal", "_refund_signal", "_ccb_refund_signal",
    "_raw_cp", "offset_type", "_debit_offset_type", "location", "acct_name_raw", "_ccb_location_cp",
    "_ccb_acct_cp", "card_number", "_source_account_identifier", "file_account_key", "source_display_name", "payment_method",
)
private val sourceBooleanKeys = setOf("_is_refund", "_is_reversal", "_icbc_refund_merchant_trusted")
private val sourceIdentityKeys = setOf("_source_account_identifier", "file_account_key", "card_number", "source_display_name")

internal fun validCashSourceExplanation(value: Map<String, Any?>): Boolean = value.all { (key, item) ->
    key in sourceTextKeys && item is String || key in sourceBooleanKeys && item is Boolean
}

internal fun extractCashSourceExplanation(value: Any?): Map<String, Any?> =
    (value as? Map<*, *>)?.entries?.mapNotNull { (key, item) ->
        if (key is String && (key in sourceTextKeys && item is String && item.isNotEmpty() ||
                key in sourceBooleanKeys && item is Boolean)) key to item else null
    }?.toMap() ?: emptyMap()

private fun String.sourceTrim() = trim { it.code in pythonWhitespace }
private fun String.sourceTake(count: Int) = substring(0, offsetByCodePoints(0, minOf(count, codePointCount(0, length))))
private val sourceSpaces = pythonWhitespace.joinToString("") { "\\x{${it.toString(16)}}" }
private val sourceDigits = pythonIntegerDigitStarts.joinToString("") { "\\x{${it.toString(16)}}-\\x{${(it + 9).toString(16)}}" }
private fun sourceRegex(pattern: String) = Regex("(?d)" + pattern
    .replace("\\s", "[$sourceSpaces]").replace("\\S", "[^$sourceSpaces]").replace("\\d", "[$sourceDigits]"))
private val sourceSpacePattern = sourceRegex("\\s+")

private fun sourceCells(sourceType: String, payload: Any?): Map<*, *> {
    if (payload !is Map<*, *>) return emptyMap<String, Any?>()
    if (sourceType !in setOf("icbc_credit", "icbc_debit")) return payload
    val result = linkedMapOf<String, Any?>()
    val ambiguous = mutableSetOf<String>()
    for ((key, value) in payload) {
        val normalized = sourceSpacePattern.replace(key as String, "")
        if (normalized in result) ambiguous.add(normalized)
        result[normalized] = value
    }
    return result.filterKeys { it !in ambiguous }
}

private fun Map<*, *>.sourceText(key: String, pdf: Boolean = false): String {
    val value = this[key] as? String ?: return ""
    return (if (pdf) value.replace("\n", "") else value).sourceTrim()
}

internal fun cashSourceNote(sourceType: String, payload: Any?): String {
    val cells = sourceCells(sourceType, payload)
    return if (sourceType == "wechat") cells["商品"] as? String ?: "" else CashRecordPolicy.sourceNote(cells)
}

private val platformRules = listOf(
    listOf("滴滴", "嘀嘀", "广州骑安", "先乘车后付款") to "滴滴",
    listOf("高德打车", "高德地图", "高德信息技术", "高德云信") to "高德",
    listOf("麦当劳") to "麦当劳", listOf("汉堡王") to "汉堡王", listOf("肯德基", "KFC") to "肯德基",
    listOf("必胜客") to "必胜客", listOf("海底捞") to "海底捞", listOf("食其家") to "食其家",
    listOf("争鲜寿司") to "争鲜寿司", listOf("和府捞面") to "和府捞面", listOf("袁记云饺") to "袁记云饺",
    listOf("霸王茶姬") to "霸王茶姬", listOf("茶百道") to "茶百道",
    listOf("瑞幸咖啡", "luckin", "瑞幸", "luckincoffee") to "瑞幸咖啡",
    listOf("奈雪") to "奈雪", listOf("星巴克", "starbucks") to "星巴克", listOf("LINLEE", "林里") to "LINLEE林里",
    listOf("便利蜂", "梦想蜂") to "便利蜂", listOf("7-11", "7-ELEVEN") to "7-11", listOf("喜家德") to "喜家德",
    listOf("新又好", "NewUhoo") to "新又好", listOf("西部马华") to "西部马华", listOf("立普世") to "立普世",
    listOf("小红书") to "小红书", listOf("bilibili", "哔哩哔哩", "B站", "b站") to "B站",
    listOf("DeepSeek", "深度求索") to "DeepSeek", listOf("携程", "去哪儿") to "携程", listOf("飞猪") to "飞猪",
    listOf("首开易生活", "预付费充电") to "首开易生活", listOf("哈啰好物", "上海钧哈", "哈啰", "电动车租车") to "哈啰",
    listOf("美团平台商户", "新渔阳滑雪场") to "美团", listOf("先骑后付") to "美团",
    listOf("北京象鲜科技", "小象超市", "美团买菜") to "美团", listOf("三快在线", "三快科技") to "美团",
    listOf("微信红包", "群收款", "转账") to "微信", listOf("猫眼") to "猫眼", listOf("Steam") to "Steam",
    listOf("PIXIV", "FANBOX", "pixiv") to "pixiv", listOf("中国电信") to "中国电信",
    listOf("杭州乐读", "网易云音乐") to "网易云音乐", listOf("天猫") to "天猫", listOf("淘宝") to "淘宝",
    listOf("拼多多") to "拼多多", listOf("京东", "京东超市") to "京东",
)
private val paymentSourceRules = listOf(
    "美团支付" to "美团支付", "京东支付" to "京东支付", "财付通(银联云闪付)" to "银联云闪付",
    "财付通" to "微信支付", "支付宝" to "支付宝", "网银在线" to "网银在线", "Apple.com/bill" to "Apple Pay",
    "Apple Pay" to "Apple Pay", "拼多多支付" to "拼多多支付", "程支付" to "携程", "抖音支付" to "抖音支付",
    "银联云闪付" to "银联云闪付", "云闪付" to "云闪付",
)
private val paymentPrefixes = listOf("美团支付-", "京东支付-", "财付通(银联云闪付)", "财付通-", "支付宝-",
    "网银在线-", "拼多多支付-", "程支付-", "抖音支付-")
private val platformPrefixes = listOf("美团App", "饿了么", "大众点评", "高德团购")
private val ecommerceSuffixes = listOf("自营旗舰店", "官方旗舰店", "旗舰店", "官方店", "专卖店", "专营店")
private val installmentPrefix = sourceRegex("^\\d+/\\d+\\s*")
private val leftoverStart = sourceRegex("^[\\s（(【\\[、,，\\-—]+")
private val leftoverEnd = sourceRegex("[\\s）)】\\]、,，\\-—]+$")
private val intermediaryDescription = sourceRegex("^(.+?)(?:外卖订单|订单|购物车)")
private val merchantAddress = sourceRegex("[（(][^)）]*[)）]")
private val gaodeMerchant = sourceRegex("^(.+?)\\s*-\\s*高德地图")

internal fun inferIcbcPayment(counterparty: String, description: String): String {
    val text = pythonCashLower("$counterparty $description")
    return paymentSourceRules.firstOrNull { (keyword, _) -> pythonCashLower(keyword) in text }?.second ?: "银行卡"
}

private fun stripPlatformPrefix(value: String): String {
    val prefix = platformPrefixes.firstOrNull(value::startsWith) ?: return value
    return value.substring(prefix.length).trimStart { it.code in pythonWhitespace }.ifEmpty { value }
}

private fun extractLeftover(counterparty: String, brand: String): String {
    val keywords = platformRules.firstOrNull { it.second == brand }?.first ?: return ""
    var result = counterparty
    for (keyword in keywords.sortedByDescending { it.codePointCount(0, it.length) }) {
        if (pythonCashLower(keyword) in pythonCashLower(result)) {
            result = Regex(Regex.escape(keyword), RegexOption.IGNORE_CASE).replace(result, "")
        }
    }
    result = stripPlatformPrefix(result)
    ecommerceSuffixes.firstOrNull(result::endsWith)?.let { result = result.dropLast(it.length) }
    return leftoverEnd.replace(leftoverStart.replace(result, ""), "").sourceTrim()
}

internal fun normalizeSourceCounterparty(raw: String, description: String): Pair<String, String> {
    if (raw.isEmpty()) return raw to description
    var counterparty = installmentPrefix.replace(raw, "")
    paymentPrefixes.firstOrNull(counterparty::startsWith)?.let {
        counterparty = counterparty.substring(it.length).trimStart('-')
    }
    val search = pythonCashLower("$counterparty $description").replace("北京东子", "")
    val brand = platformRules.firstOrNull { (keywords, _) -> keywords.any { pythonCashLower(it) in search } }?.second
    if (brand == null) return stripPlatformPrefix(counterparty) to description
    if ("先骑后付" in counterparty) return "美团" to if (description.isEmpty()) "先骑后付" else "先骑后付|$description"
    val leftover = extractLeftover(counterparty, brand)
    var note = if (leftover.isNotEmpty() && (description.isEmpty() || description == raw)) leftover else description
    counterparty = brand
    if (description.isNotEmpty()) {
        if (brand == "淘宝" || brand == "天猫") {
            var merchant = intermediaryDescription.find(description)?.groupValues?.get(1)?.trim(' ', '·', '-', '—') ?: description
            if (merchant !in setOf("超级吃货卡", "外卖红包", "淘宝") && !merchant.startsWith("淘宝")) {
                merchant = merchantAddress.replace(merchant, "").sourceTrim()
                if (merchant.codePointCount(0, merchant.length) >= 2) {
                    counterparty = merchant
                    note = description
                }
            }
        } else if (brand == "高德") {
            gaodeMerchant.find(description)?.let {
                counterparty = it.groupValues[1].sourceTrim()
                note = description
            }
        }
    }
    return counterparty to note
}

private val amountToken = "(?:[¥￥]\\s*[+-]?[0-9][0-9,]*(?:\\.[0-9]+)?|[+-]?[0-9][0-9,]*(?:\\.[0-9]+)?\\s*元)"
private val parenthesizedAmount = sourceRegex("[（(\\[【]\\s*($amountToken)\\s*[）)\\]】]")
private val installmentCount = sourceRegex("分期\\s*[（(]\\s*\\d+\\s*期\\s*[）)]")
private val remainingInstallment = sourceRegex("\\s*[（(]?\\d+\\s*期[）)]?")
private val paymentAmountSuffix = sourceRegex("$amountToken$")
private val bankTail = sourceRegex("(?:[（(](?:尾号)?([0-9]{4})[）)]|尾号([0-9]{4})|([0-9]{4}))$")
private val bankLabel = sourceRegex("([\\u4e00-\\u9fff]+银行)([\\u4e00-\\u9fff]*卡)?")

private fun normalizeBankLabel(label: String): String? {
    val compact = sourceSpacePattern.replace(label, "")
    val tail = bankTail.find(compact)
    val digits = tail?.groups?.drop(1)?.firstNotNullOfOrNull { it?.value }.orEmpty()
    val body = if (tail == null) compact else compact.substring(0, tail.range.first)
    val bank = bankLabel.matchEntire(body) ?: return null
    val name = bank.groupValues[1].let { if (it.startsWith("中国") && it != "中国银行") it.substring(2) else it }
    return name + bank.groupValues[2] + if (digits.isEmpty()) "" else "($digits)"
}

private fun normalizePlatformPayment(method: String, alipay: Boolean): String {
    if (!alipay) {
        val token = method.sourceTrim().removePrefix("云闪付-")
        return normalizeBankLabel(token) ?: token
    }
    return method.split('&').joinToString("&") { raw ->
        var token = raw.sourceTrim().removePrefix("云闪付-")
        token = parenthesizedAmount.replace(token) { it.groupValues[1] }
        token = installmentCount.replace(token, "").replace("分期", "")
        token = remainingInstallment.replace(token, "").sourceTrim()
        val amount = paymentAmountSuffix.find(token)
        val suffix = amount?.let { token.substring(it.range.first) }.orEmpty()
        val label = amount?.let { token.substring(0, it.range.first) } ?: token
        normalizeBankLabel(label)?.let { it + suffix } ?: token
    }
}

private fun wealthPayment(description: String, method: String): String = when {
    "转出到银行卡" in description && "余额宝" in description -> "余额宝"
    "转出到银行卡" in description && "余利宝" in description -> "余利宝"
    "余额宝-转出到余额" in description -> "余额宝"
    "支付宝转入到余利宝" in description || "余额宝-单次转入" in description -> method
    "收益发放" in description && "余额宝" in description || "卖出至余额宝" in description -> "余额宝"
    method.startsWith("余额宝") -> "余额宝"
    method.startsWith("余利宝") -> "余利宝"
    else -> method
}

private fun platformExplanation(sourceType: String, cells: Map<*, *>, amount: CashAmount?): Map<String, Any?> {
    if (sourceType == "wechat") {
        val status = cells.sourceText("当前状态")
        val direction = cells.sourceText("收/支")
        val kind = cells.sourceText("交易类型")
        val method = if (direction !in setOf("支出", "收入") && kind == "零钱提现") "零钱" else cells.sourceText("支付方式")
        return mapOf("txn_type" to kind, "txn_id" to cells.sourceText("交易单号"), "merchant_order_id" to cells.sourceText("商户单号"),
            "status" to status, "platform_status" to status, "_wechat_direction" to direction,
            "payment_method" to normalizePlatformPayment(method, false), "_refund_signal" to if ("退款" in status) "wechat_status" else "")
    }
    val kind = cells.sourceText("交易分类")
    val status = cells.sourceText("交易状态")
    val direction = cells.sourceText("收/支")
    val counterparty = cells.sourceText("交易对方")
    val description = normalizeSourceCounterparty(counterparty, cells.sourceText("商品说明").ifEmpty { counterparty }.sourceTake(80)).second
    val method = cells.sourceText("收/付款方式").let { if (amount == null) it else wealthPayment(description, it) }
    val refund = when {
        status == "退款成功" -> "alipay_status"
        kind == "退款" && direction == "不计收支" -> "alipay_category_nocount"
        "退款" in description -> "alipay_desc"
        else -> ""
    }
    return mapOf("txn_type" to kind, "txn_id" to cells.sourceText("交易订单号"), "merchant_order_id" to cells.sourceText("商家订单号"),
        "platform_status" to status, "_alipay_direction" to direction, "payment_method" to normalizePlatformPayment(method, true),
        "_refund_signal" to refund)
}

private val ccbPrefixes = listOf("财付通-" to listOf("微信支付-", "微信转账", "消费-"),
    "支付宝-" to listOf("淘宝-", "支付宝外部商户-", "支付宝-转账-", "支付宝-", "消费-"), "美团支付-" to listOf("消费-"))
private val securitiesLocation = sourceRegex("^(银行转证券|证券转银行|银转证|证转银)\\d+\\S*$")
private fun ccbCounterparty(location: String): String {
    if (location.isEmpty() || location == "***") return ""
    val stripped = location.removePrefix("消费-")
    for ((prefix, suffixes) in ccbPrefixes) if (stripped.startsWith(prefix)) {
        var rest = stripped.substring(prefix.length)
        while (true) {
            val suffix = suffixes.firstOrNull(rest::startsWith) ?: return rest
            rest = rest.substring(suffix.length)
        }
    }
    return securitiesLocation.find(stripped)?.groupValues?.get(1) ?: stripped
}
private fun CashAmount?.sourcePositive() = this != null && !representation.negative && numericText() != "0"

private fun bankExplanation(sourceType: String, cells: Map<*, *>, amount: CashAmount?, identity: Map<String, Any?>): Map<String, Any?> {
    val card = identity["card_number"] as? String ?: ""
    val suffix = if (card.isEmpty()) "" else "($card)"
    if (sourceType == "icbc_asia") return mapOf("summary" to cells.sourceText("摘要"), "txn_type" to cells.sourceText("業務類型"),
        "location" to cells.sourceText("交易場所"), "_raw_cp" to cells.sourceText("對方戶名"), "payment_method" to "工银亚洲活期账户$suffix")
    val summary = cells.sourceText("摘要")
    val location = cells.sourceText("交易地点/附言")
    val account = cells.sourceText("对方账号与户名")
    val locationCounterparty = ccbCounterparty(location)
    val accountCounterparty = account.substringAfter('/').sourceTrim()
    val upper = buildString { location.codePoints().forEach { append(pythonUpperCase[it] ?: String(Character.toChars(it))) } }
    val method = when {
        location.startsWith("财付通-") -> "微信支付"
        location.startsWith("支付宝-") -> "支付宝"
        location.startsWith("美团支付-") -> "美团支付"
        "PAYPAL" in upper -> "PayPal"
        else -> "建行储蓄卡$suffix"
    }
    val refund = if (amount.sourcePositive() && listOf("退货", "退款", "冲正", "撤销").any(summary::contains)) "ccb_debit_refund" else ""
    return mapOf("summary" to summary, "location" to location, "acct_name_raw" to account,
        "_raw_cp" to accountCounterparty.ifEmpty { locationCounterparty }, "_ccb_location_cp" to locationCounterparty,
        "_ccb_acct_cp" to accountCounterparty, "payment_method" to method, "_ccb_refund_signal" to refund, "_refund_signal" to refund)
}

private val debitSummaries = listOf("支付宝转账", "无卡支付", "工资", "转账", "购汇还款", "撤销交易", "个人购汇", "跨境汇款",
    "利息", "基金购买", "基金赎回", "他行汇入", "银联入账", "预约购汇", "网转", "跨行汇款")
private fun isSourceSubsequence(pattern: String, text: String): Boolean {
    var cursor = 0
    for (character in pattern) {
        val index = text.indexOf(character, cursor)
        if (index < 0) return false
        cursor = index + 1
    }
    return true
}
private val untrustedMerchant = sourceRegex("[:\\d\\s.-]+")
private fun icbcExplanation(sourceType: String, cells: Map<*, *>, amount: CashAmount?): Map<String, Any?> {
    var summary = cells.sourceText("摘要", true)
    var counterparty = cells.sourceText("对方户名", true)
    if (sourceType == "icbc_debit") {
        val clean = summary.filter { it in '\u4e00'..'\u9fff' }
        summary = debitSummaries.firstOrNull { isSourceSubsequence(it, clean) } ?: clean
        if (counterparty.isNotEmpty() && summary.isNotEmpty() && listOf("基金快速赎回", "基金赎回", "基金购买").any { isSourceSubsequence(it, counterparty) }) {
            counterparty = "中国工商银行股份有限公司基金清算专户"
        }
        val offset = when (summary) { "撤销交易" -> "reversal"; "退款", "退货" -> "refund"; else -> "" }
        return mapOf("summary" to summary, "_raw_cp" to counterparty, "payment_method" to cells.sourceText("渠道", true),
            "_debit_offset_type" to offset, "_is_refund" to (offset == "refund"), "_is_reversal" to (offset == "reversal"),
            "refund_signal" to if (summary == "退货") "icbc_debit_return" else "",
            "_refund_signal" to if (offset == "refund" && amount.sourcePositive()) "icbc_debit_refund" else "")
    }
    val description = cells.sourceText("交易场所", true)
    val offsetText = listOf(summary, counterparty, description).filter(String::isNotEmpty).joinToString(" ")
    val offset = when {
        "减免年费" in offsetText -> "fee_reversal"
        "刷卡金入账" in offsetText || "刷卡金退款" in offsetText -> "benefit_rebate"
        "返现" in offsetText || "Rebate" in offsetText -> "campaign_cashback"
        "退货" in offsetText || "退款" in offsetText -> "merchant_refund"
        else -> ""
    }
    val normalization = description.ifEmpty { if (inferIcbcPayment(counterparty, "") != "银行卡") counterparty else "" }
    val (normalized, enriched) = normalizeSourceCounterparty(counterparty, normalization.sourceTake(80))
    val result = linkedMapOf<String, Any?>("summary" to summary, "_raw_cp" to counterparty, "offset_type" to offset,
        "payment_method" to inferIcbcPayment(normalized, enriched.sourceTake(80)))
    if (offset == "merchant_refund") {
        val merchant = (if (counterparty == "退货" && description.isNotEmpty()) description else counterparty).sourceTrim()
        result.putAll(mapOf("_is_refund" to true, "_refund_signal" to "icbc_credit_return",
            "refund_signal" to if (summary == "退货") "icbc_credit_return" else "",
            "_icbc_refund_merchant_trusted" to (merchant.codePointCount(0, merchant.length) > 1 &&
                merchant !in setOf("A", "F", "D", "工商", "银行", "中国", "请扫描二维码") && !untrustedMerchant.matches(merchant))))
    }
    return result
}

internal fun prepareCashSourceEffects(values: Map<String, Any?>, patch: Map<String, Any?>, sourceType: String,
    sourceExplanation: Any?, protectedFields: Set<String> = emptySet()): CashSourceEffects {
    val result = values.toMutableMap()
    val explanation = if ("source_payload" in patch || "amount" in patch) {
        if ("source_payload" in patch && "note" !in patch && "note" !in protectedFields) {
            result["note"] = cashSourceNote(sourceType, result["source_payload"])
        }
        val identity = extractCashSourceExplanation(sourceExplanation).filterKeys { it in sourceIdentityKeys }
        val cells = sourceCells(sourceType, result["source_payload"])
        val amount = result["amount"] as CashAmount?
        val evidence = when (sourceType) {
            "wechat", "alipay" -> platformExplanation(sourceType, cells, amount)
            "ccb_debit", "icbc_asia" -> bankExplanation(sourceType, cells, amount, identity)
            "icbc_credit", "icbc_debit" -> icbcExplanation(sourceType, cells, amount)
            else -> emptyMap()
        }
        extractCashSourceExplanation(evidence + identity)
    } else if ("record_type" in patch || "record_subtype" in patch) {
        extractCashSourceExplanation(sourceExplanation)
    } else null
    return CashSourceEffects(result, explanation)
}
