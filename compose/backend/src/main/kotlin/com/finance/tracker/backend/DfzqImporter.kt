package com.finance.tracker.backend

import kotlinx.serialization.json.*

internal class DfzqRow(private val fields: JsonObject, val decimalFields: Set<String> = DfzqImporter.decimalFields) : Map<String, JsonElement> by fields
internal object DfzqImporter {
    val decimalFields = setOf("shares", "price", "amount", "total_amount", "fee", "stamp_tax", "transfer_fee", "balance")
    private val actions = mapOf("证券买入" to "BUY", "证券卖出" to "SELL", "银行转证券" to "DEPOSIT", "证券转银行" to "WITHDRAW", "OTC资金划入" to "DEPOSIT", "OTC资金划出" to "WITHDRAW", "融券回购" to "BUY", "融券购回" to "SELL", "红利入账" to "DIVIDEND", "红股入账" to "DIVIDEND", "股息红利差异扣税" to "WITHDRAW", "利息归本" to "DEPOSIT")
    private val columns = setOf("发生日期", "买卖类别", "证券代码", "证券名称", "成交数量", "成交价格", "总发生金额", "手续费", "印花税", "过户费", "资金余额")
    private fun text(row: Map<String, JsonElement>, key: String) = row[key]?.let(::pythonString).orEmpty()
    private fun value(row: Map<String, JsonElement>, key: String, fallback: String = "0"): String {
        val raw = row[key] ?: return fallback; if (!pythonTruthy(raw)) return fallback
        val s = pythonString(raw)
        return if (row is DfzqRow && key in row.decimalFields && s.toBigDecimalOrNull()?.signum() == 0) fallback else s
    }
    private fun suffix(code: String) = when { code == "204001" -> ""; code.startsWith("85") || code.startsWith("007") -> ".otc"; code.firstOrNull() in listOf('0', '1', '2') -> ".sz"; code.firstOrNull() in listOf('5', '6') -> ".sh"; else -> "" }
    private val digit = "[" + pythonIntegerDigitStarts.joinToString("") { "\\x{${it.toString(16)}}-\\x{${(it + 9).toString(16)}}" } + "]"
    private val space = "[" + pythonWhitespace.joinToString("") { "\\x{${it.toString(16)}}" } + "]"
    private fun numeric(s: String): Boolean = try { BrokerText.decimal(s); true } catch (_: ArithmeticException) { false }
    private fun source(lines: List<String>) = buildJsonObject { put("原始文本单元", JsonArray(lines.map(::JsonPrimitive))) }
    private fun fixed(value: String) = FinanceDecimal.parse(value).fixed()
    private fun slice(value: String, start: Int, end: Int): String {
        val count = value.codePointCount(0, value.length)
        return value.substring(value.offsetByCodePoints(0, minOf(start, count)), value.offsetByCodePoints(0, minOf(end, count)))
    }
    private fun multiply(a: String, b: String): String {
        val result = FinanceDecimal.parse(a).multiply(FinanceDecimal.parse(b), 28).text
        val magnitude = BrokerText.abs(result)
        return if (result.startsWith('-') && !magnitude.startsWith('-')) "-$magnitude" else magnitude
    }
    private fun make(date: String, raw: String, code: String, name: String, shares: String, price: String, amount: String, fee: String, stamp: String, transfer: String, balance: String): JsonObject {
        val action = actions[raw] ?: raw
        var ticker = if (raw in setOf("融券回购", "融券购回")) "204001" else code
        ticker += suffix(ticker)
        val stock = raw == "红股入账" && FinanceDecimal.parse(shares) > FinanceDecimal.ZERO && ticker.isNotEmpty()
        if (action in setOf("DEPOSIT", "WITHDRAW", "DIVIDEND") && !stock || action in setOf("DEPOSIT", "WITHDRAW")) ticker = ""
        val clear = action in setOf("DEPOSIT", "WITHDRAW", "DIVIDEND") && !stock
        return buildJsonObject {
            put("date", slice(date, 0, 4) + "-" + slice(date, 4, 6) + "-" + slice(date, 6, 8) + " 00:00:00"); put("action", action); put("action_raw", raw); put("ticker", ticker); put("name", name)
            put("shares", if (clear) "0" else shares); put("price", if (clear) "0" else price); put("amount", amount); put("total_amount", amount); put("fee", fee); put("stamp_tax", stamp); put("transfer_fee", transfer); put("balance", balance); put("note", raw)
        }
    }
    fun parse(lines: List<String>): List<DfzqRow> {
        val start = lines.indexOfFirst { "资金流水明细" in it }; if (start < 0) return emptyList()
        val data = mutableListOf<String>(); var summary = false
        for (line in lines.drop(start + 1)) {
            val s = valuationStrip(line)
            if (Regex("^第${digit}+页，共${digit}+页").containsMatchIn(s) || s == "\u000c") { summary = false; continue }
            if (listOf("股票资料", "汇总", "成交汇总", "证券持有", "市值").any { it in s }) { summary = true; continue }
            if (summary || "资金流水明细" in s || s in columns || s.isEmpty()) continue
            data += s
        }
        val dates = data.indices.filter { i -> data[i].codePointCount(0, data[i].length) == 8 && data[i].codePoints().allMatch { code -> code in pythonNondecimalDigits || pythonIntegerDigitStarts.any { code in it until it + 10 } } }
        val rows = mutableListOf<JsonObject>()
        for ((index, di) in dates.withIndex()) {
            val b = data.subList(di, dates.getOrNull(index + 1) ?: data.size); if (b.size < 3) continue
            val raw = b[1]
            fun d(i: Int) = BrokerText.decimal(b[i])
            val txn: JsonObject
            if (raw == "红股入账") {
                if (b.size < 6) continue
                txn = make(b[0], raw, b[2], b[3], d(4), d(5), "0", "0", "0", "0", "0")
            } else {
                val named = b.size > 3 && !numeric(b[3]); val bank = !named && '.' in b[3]
                val minimum = if (named) 9 else if (bank) 7 else 8; if (b.size < minimum) continue
                val amountIndex = if (named) 6 else if (bank) 4 else 5
                val shares = if (bank) "0" else d(if (named) 4 else 3); val price = d(if (named) 5 else if (bank) 3 else 4)
                if (b[amountIndex] == "--") continue
                val amount = d(amountIndex); val fee = d(amountIndex + 1)
                val extended = if (named) b.size >= 11 else b.size >= minimum + 1
                val stamp = if (extended) d(amountIndex + 2) else "0"; val transfer = if (extended) d(amountIndex + 3) else "0"
                val balance = if (extended) { if (b.size > amountIndex + 4) d(amountIndex + 4) else "0" } else d(amountIndex + 2)
                txn = make(b[0], raw, b[2], if (named) b[3] else "", shares, price, amount, fee, stamp, transfer, balance)
            }
            rows += JsonObject(txn + ("_source_payload" to source(b.toList())))
        }
        rows.sortWith { a, b -> pythonStringOrder(text(a, "date"), text(b, "date")) }
        val holdings = holdings(lines); val cashPair = cash(lines); var cash = cashPair.first; var cashSource = cashPair.second
        val date = rows.lastOrNull()?.let { text(it, "date") } ?: if (holdings.isNotEmpty() || cash != null) printDate(lines) ?: "1970-01-01 00:00:00" else null
        if (date != null) {
            if (cash == null && rows.isNotEmpty()) {
                val row = rows.lastOrNull { text(it, "action") in setOf("BUY", "SELL", "DEPOSIT", "WITHDRAW") || text(it, "action") == "DIVIDEND" && text(it, "ticker").isEmpty() } ?: rows.last()
                cash = text(row, "balance"); cashSource = row["_source_payload"]
            }
            fun snapshot(ticker: String, name: String, shares: String, price: String, amount: String, balance: String, source: JsonElement) = buildJsonObject {
                put("date", date); put("action", "CHECKIN"); put("ticker", ticker); put("name", name); put("shares", shares); put("price", price); put("amount", amount); put("total_amount", amount); put("fee", "0"); put("stamp_tax", "0"); put("transfer_fee", "0"); put("balance", balance); put("note", ""); put("_source_payload", source)
            }
            if (cash != null) rows += snapshot("", "", "0", "0", cash, cash, requireNotNull(cashSource) { "东方证券现金快照缺少来源行" })
            for (h in holdings) {
                val shares = text(h, "shares"); val price = text(h, "cost_price")
                rows += snapshot(text(h, "ticker"), text(h, "name"), shares, price, multiply(shares, price), "0", h.getValue("_source_payload"))
            }
        }
        return rows.map(::DfzqRow)
    }
    private fun printDate(lines: List<String>) = lines.take(20).firstNotNullOfOrNull { Regex("打印日期[:：]$space*($digit{4}-$digit{2}-$digit{2})").find(valuationStrip(it))?.groupValues?.get(1)?.plus(" 00:00:00") }
    private fun cash(lines: List<String>): Pair<String?, JsonElement?> {
        for ((i, line) in lines.take(80).withIndex()) if ("资金余额(RMB)" in line || "资金余额（RMB）" in line) {
            val m = Regex("资金余额\\(?RMB\\)?[:：]?$space*([0-9,]+\\.$digit+)").find(line)
            if (m != null) return BrokerText.decimal(m.groupValues[1].replace(",", "")) to source(listOf(line.trimEnd('\r', '\n')))
            for (j in i + 1 until minOf(i + 4, lines.size)) {
                val s = valuationStrip(lines[j]).replace(",", ""); if (s.isEmpty()) continue
                val number = try { BrokerText.decimal(s) } catch (_: ArithmeticException) { break }
                return number to source(listOf(line.trimEnd('\r', '\n'), lines[j].trimEnd('\r', '\n')))
            }
        }
        return null to null
    }
    private fun holdings(lines: List<String>): List<JsonObject> {
        var start = -1; var end = -1
        for ((i, line) in lines.withIndex()) { if ("汇总股票资料" in line) start = i; if (start >= 0 && "资金流水明细" in line) { end = i; break } }
        if (start < 0) return emptyList(); if (end < 0) end = minOf(start + 80, lines.size)
        val headers = setOf("汇总股票资料", "交易市场", "证券代码", "证券名称", "持仓数量", "市价", "成本价", "证券市值")
        val markets = setOf("深市A股", "沪市A股", "沪市B股", "深市B股", "港股", "美股", "场外")
        val values = lines.subList(start, end).filter { valuationStrip(it).isNotEmpty() }.map { valuationStrip(it) to it.trimEnd('\r', '\n') }.filter { it.first !in headers }
        val result = mutableListOf<JsonObject>(); var i = 0
        while (i < values.size) {
            val rowStart = i; if (values[i].first in markets) { i++; if (i >= values.size) break }
            if (i + 5 >= values.size) break
            val code = values[i].first; if (!Regex("$digit{5,6}").matches(code)) { i++; continue }
            val numbers = try { (2..4).map { BrokerText.decimal(values[i + it].first.replace(",", "")) } } catch (_: ArithmeticException) { i++; continue }
            if (!FinanceDecimal.parse(numbers[0]).zero()) result += buildJsonObject {
                put("ticker", code + suffix(code)); put("name", values[i + 1].first); put("shares", numbers[0]); put("market_price", numbers[1]); put("cost_price", numbers[2]); put("_source_payload", source(values.subList(rowStart, i + 6).map { it.second }))
            }
            i += 6
        }
        return result
    }
    private fun formatted(row: Map<String, JsonElement>, field: String): String {
        val raw = row.getValue(field)
        if (row is DfzqRow && field in row.decimalFields) return fixed(text(row, field))
        require(raw != JsonNull) { "unsupported format string passed to NoneType.__format__" }
        require(raw is JsonPrimitive && !raw.isString) { "Unknown format code 'f' for object of type 'str'" }
        val value = raw.booleanOrNull?.let { if (it) "1" else "0" } ?: raw.content
        val number = value.toDouble()
        val rounded = java.math.BigDecimal(number).setScale(6, java.math.RoundingMode.HALF_EVEN)
        return (if (rounded.signum() == 0 && number.toRawBits() < 0) "-" else "") + rounded.toPlainString()
    }
    private fun snapshotPrice(row: Map<String, JsonElement>): String {
        if (row is DfzqRow && "price" in row.decimalFields) return BrokerText.abs(text(row, "price"))
        val raw = row.getValue("price")
        require(raw is JsonPrimitive && raw != JsonNull && !raw.isString) { "bad operand type for abs()" }
        val value = raw.booleanOrNull?.let { if (it) "1" else "0" } ?: raw.content
        return java.math.BigDecimal(value.toDouble()).abs().setScale(6, java.math.RoundingMode.HALF_EVEN).toPlainString()
    }
    fun identity(row: Map<String, JsonElement>) = "dfzq:${slice(pythonString(row.getValue("date")), 0, 10).replace("-", "")}:${value(row, "ticker", "cash")}:${pythonString(row.getValue("action"))}:${formatted(row, "amount")}:${formatted(row, "balance")}"
    fun event(row: Map<String, JsonElement>, account: String, currency: String = "CNY"): JsonObject {
        val action = pythonString(row.getValue("action")); val raw = value(row, "action_raw", action); val note = value(row, "action_raw", value(row, "note", "")); val ticker = row["ticker"] ?: JsonPrimitive(""); val cash = pythonCashLower(currency)
        val date = row.getValue("date")
        fun amount(field: String): String {
            val number = value(row, field)
            try { exactCashAmount(number, "东方证券账单$field") } catch (e: IllegalArgumentException) {
                if (e.message.orEmpty().contains("at most 18 decimal places")) throw IllegalArgumentException("东方证券账单${field}最多保留 18 位小数")
                throw e
            }
            return BrokerText.decimal(number)
        }
        val amount = BrokerText.abs(amount("amount")); val shares = BrokerText.abs(amount("shares")); val fees = listOf("fee", "stamp_tax", "transfer_fee").map(::amount)
        val fee = if (fees.any { FinanceDecimal.parse(it) < FinanceDecimal.ZERO }) "0" else fees.fold(FinanceDecimal.ZERO) { a, b -> a.add(FinanceDecimal.parse(BrokerText.abs(b)), 28) }.text.let(BrokerText::decimal)
        val price = BrokerText.abs(amount("price")); var commission = "0"; var cashAmount = amount
        if (FinanceDecimal.parse(fee) > FinanceDecimal.ZERO && (action == "SELL" || action == "BUY" && FinanceDecimal.parse(fee) < FinanceDecimal.parse(amount))) {
            commission = fee; cashAmount = FinanceDecimal.parse(amount).add(FinanceDecimal.parse(if (action == "BUY") "-$fee" else fee), 28).text
        }
        fun result(type: String, subtype: String, from: JsonElement = JsonPrimitive(""), fromAmount: String = "0", to: JsonElement = JsonPrimitive(""), toAmount: String = "0", eventPrice: String = "1", eventFee: String = "0", feeAsset: String = "") = buildJsonObject {
            put("date", date); put("account_name", account); put("currency", currency); put("note", if (action == "CHECKIN") "" else note); put("record_type", type); put("record_subtype", subtype); put("from_ticker", from); put("from_amount", fixed(fromAmount)); put("to_ticker", to); put("to_amount", fixed(toAmount)); put("price", fixed(eventPrice)); put("commission", fixed(eventFee)); put("commission_asset", feeAsset)
        }
        val cashTicker = JsonPrimitive(cash)
        return when (action) {
            "BUY" -> result("trade", if (ticker == JsonPrimitive("204001")) "repo" else "security", cashTicker, cashAmount, ticker, shares, price, commission, if (FinanceDecimal.parse(commission).zero()) "" else cash)
            "SELL" -> result("trade", if (ticker == JsonPrimitive("204001")) "repo" else "security", ticker, shares, cashTicker, cashAmount, price, commission, if (FinanceDecimal.parse(commission).zero()) "" else cash)
            "DEPOSIT", "WITHDRAW" -> when (raw) {
                "利息归本" -> result("income", "interest", to = cashTicker, toAmount = amount)
                "股息红利差异扣税" -> result("expense", "tax", cashTicker, amount)
                else -> if (action == "DEPOSIT") result("funding", if (raw in setOf("OTC资金划入", "OTC资金划出")) "subaccount" else "external", to = cashTicker, toAmount = amount) else result("funding", if (raw in setOf("OTC资金划入", "OTC资金划出")) "subaccount" else "external", cashTicker, amount)
            }
            "DIVIDEND" -> if (pythonTruthy(ticker)) result("income", "dividend_stock", ticker, to = ticker, toAmount = shares, eventPrice = price) else result("income", "dividend_cash", row["name"] ?: JsonPrimitive(""), to = cashTicker, toAmount = amount)
            "CHECKIN" -> if (pythonTruthy(ticker)) result("snapshot", "position", to = ticker, toAmount = shares, eventPrice = snapshotPrice(row)) else result("snapshot", "cash", to = cashTicker, toAmount = amount)
            else -> throw IllegalArgumentException("Unsupported DFZQ action: $action")
        }
    }
}
