package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.*

internal class UsmartRow(private val fields: JsonObject, val decimalFields: Set<String> = UsmartImporter.decimalFields.intersect(fields.keys)) : Map<String, JsonElement> by fields

internal object UsmartImporter {
    val decimalFields = setOf("qty", "gross", "net", "commission", "amount", "shares", "from_amount", "to_amount")
    private val context = MathContext(28, RoundingMode.HALF_EVEN)
    private val digit = "[" + pythonIntegerDigitStarts.joinToString("") { "\\x{${it.toString(16)}}-\\x{${(it + 9).toString(16)}}" } + "]"
    private val spaces = pythonWhitespace.joinToString("") { "\\x{${it.toString(16)}}" }
    private val numericWord = "\\x{30}-\\x{39}\\x{b2}-\\x{b3}\\x{b9}-\\x{b9}\\x{bc}-\\x{be}\\x{660}-\\x{669}\\x{6f0}-\\x{6f9}\\x{7c0}-\\x{7c9}\\x{966}-\\x{96f}\\x{9e6}-\\x{9ef}\\x{9f4}-\\x{9f9}\\x{a66}-\\x{a6f}\\x{ae6}-\\x{aef}\\x{b66}-\\x{b6f}\\x{b72}-\\x{b77}\\x{be6}-\\x{bf2}\\x{c66}-\\x{c6f}\\x{c78}-\\x{c7e}\\x{ce6}-\\x{cef}\\x{d58}-\\x{d5e}\\x{d66}-\\x{d78}\\x{de6}-\\x{def}\\x{e50}-\\x{e59}\\x{ed0}-\\x{ed9}\\x{f20}-\\x{f33}\\x{1040}-\\x{1049}\\x{1090}-\\x{1099}\\x{1369}-\\x{137c}\\x{16ee}-\\x{16f0}\\x{17e0}-\\x{17e9}\\x{17f0}-\\x{17f9}\\x{1810}-\\x{1819}\\x{1946}-\\x{194f}\\x{19d0}-\\x{19da}\\x{1a80}-\\x{1a89}\\x{1a90}-\\x{1a99}\\x{1b50}-\\x{1b59}\\x{1bb0}-\\x{1bb9}\\x{1c40}-\\x{1c49}\\x{1c50}-\\x{1c59}\\x{2070}-\\x{2070}\\x{2074}-\\x{2079}\\x{2080}-\\x{2089}\\x{2150}-\\x{2182}\\x{2185}-\\x{2189}\\x{2460}-\\x{249b}\\x{24ea}-\\x{24ff}\\x{2776}-\\x{2793}\\x{2cfd}-\\x{2cfd}\\x{3007}-\\x{3007}\\x{3021}-\\x{3029}\\x{3038}-\\x{303a}\\x{3192}-\\x{3195}\\x{3220}-\\x{3229}\\x{3248}-\\x{324f}\\x{3251}-\\x{325f}\\x{3280}-\\x{3289}\\x{32b1}-\\x{32bf}\\x{3405}-\\x{3405}\\x{3483}-\\x{3483}\\x{382a}-\\x{382a}\\x{3b4d}-\\x{3b4d}\\x{4e00}-\\x{4e00}\\x{4e03}-\\x{4e03}\\x{4e07}-\\x{4e07}\\x{4e09}-\\x{4e09}\\x{4e5d}-\\x{4e5d}\\x{4e8c}-\\x{4e8c}\\x{4e94}-\\x{4e94}\\x{4e96}-\\x{4e96}\\x{4ebf}-\\x{4ec0}\\x{4edf}-\\x{4edf}\\x{4ee8}-\\x{4ee8}\\x{4f0d}-\\x{4f0d}\\x{4f70}-\\x{4f70}\\x{5104}-\\x{5104}\\x{5146}-\\x{5146}\\x{5169}-\\x{5169}\\x{516b}-\\x{516b}\\x{516d}-\\x{516d}\\x{5341}-\\x{5341}\\x{5343}-\\x{5345}\\x{534c}-\\x{534c}\\x{53c1}-\\x{53c4}\\x{56db}-\\x{56db}\\x{58f1}-\\x{58f1}\\x{58f9}-\\x{58f9}\\x{5e7a}-\\x{5e7a}\\x{5efe}-\\x{5eff}\\x{5f0c}-\\x{5f0e}\\x{5f10}-\\x{5f10}\\x{62fe}-\\x{62fe}\\x{634c}-\\x{634c}\\x{67d2}-\\x{67d2}\\x{6f06}-\\x{6f06}\\x{7396}-\\x{7396}\\x{767e}-\\x{767e}\\x{8086}-\\x{8086}\\x{842c}-\\x{842c}\\x{8cae}-\\x{8cae}\\x{8cb3}-\\x{8cb3}\\x{8d30}-\\x{8d30}\\x{9621}-\\x{9621}\\x{9646}-\\x{9646}\\x{964c}-\\x{964c}\\x{9678}-\\x{9678}\\x{96f6}-\\x{96f6}\\x{a620}-\\x{a629}\\x{a6e6}-\\x{a6ef}\\x{a830}-\\x{a835}\\x{a8d0}-\\x{a8d9}\\x{a900}-\\x{a909}\\x{a9d0}-\\x{a9d9}\\x{a9f0}-\\x{a9f9}\\x{aa50}-\\x{aa59}\\x{abf0}-\\x{abf9}\\x{f96b}-\\x{f96b}\\x{f973}-\\x{f973}\\x{f978}-\\x{f978}\\x{f9b2}-\\x{f9b2}\\x{f9d1}-\\x{f9d1}\\x{f9d3}-\\x{f9d3}\\x{f9fd}-\\x{f9fd}\\x{ff10}-\\x{ff19}\\x{10107}-\\x{10133}\\x{10140}-\\x{10178}\\x{1018a}-\\x{1018b}\\x{102e1}-\\x{102fb}\\x{10320}-\\x{10323}\\x{10341}-\\x{10341}\\x{1034a}-\\x{1034a}\\x{103d1}-\\x{103d5}\\x{104a0}-\\x{104a9}\\x{10858}-\\x{1085f}\\x{10879}-\\x{1087f}\\x{108a7}-\\x{108af}\\x{108fb}-\\x{108ff}\\x{10916}-\\x{1091b}\\x{109bc}-\\x{109bd}\\x{109c0}-\\x{109cf}\\x{109d2}-\\x{109ff}\\x{10a40}-\\x{10a48}\\x{10a7d}-\\x{10a7e}\\x{10a9d}-\\x{10a9f}\\x{10aeb}-\\x{10aef}\\x{10b58}-\\x{10b5f}\\x{10b78}-\\x{10b7f}\\x{10ba9}-\\x{10baf}\\x{10cfa}-\\x{10cff}\\x{10d30}-\\x{10d39}\\x{10e60}-\\x{10e7e}\\x{10f1d}-\\x{10f26}\\x{10f51}-\\x{10f54}\\x{10fc5}-\\x{10fcb}\\x{11052}-\\x{1106f}\\x{110f0}-\\x{110f9}\\x{11136}-\\x{1113f}\\x{111d0}-\\x{111d9}\\x{111e1}-\\x{111f4}\\x{112f0}-\\x{112f9}\\x{11450}-\\x{11459}\\x{114d0}-\\x{114d9}\\x{11650}-\\x{11659}\\x{116c0}-\\x{116c9}\\x{11730}-\\x{1173b}\\x{118e0}-\\x{118f2}\\x{11950}-\\x{11959}\\x{11c50}-\\x{11c6c}\\x{11d50}-\\x{11d59}\\x{11da0}-\\x{11da9}\\x{11fc0}-\\x{11fd4}\\x{12400}-\\x{1246e}\\x{16a60}-\\x{16a69}\\x{16ac0}-\\x{16ac9}\\x{16b50}-\\x{16b59}\\x{16b5b}-\\x{16b61}\\x{16e80}-\\x{16e96}\\x{1d2e0}-\\x{1d2f3}\\x{1d360}-\\x{1d378}\\x{1d7ce}-\\x{1d7ff}\\x{1e140}-\\x{1e149}\\x{1e2f0}-\\x{1e2f9}\\x{1e8c7}-\\x{1e8cf}\\x{1e950}-\\x{1e959}\\x{1ec71}-\\x{1ecab}\\x{1ecad}-\\x{1ecaf}\\x{1ecb1}-\\x{1ecb4}\\x{1ed01}-\\x{1ed2d}\\x{1ed2f}-\\x{1ed3d}\\x{1f100}-\\x{1f10c}\\x{1fbf0}-\\x{1fbf9}\\x{20001}-\\x{20001}\\x{20064}-\\x{20064}\\x{200e2}-\\x{200e2}\\x{20121}-\\x{20121}\\x{2092a}-\\x{2092a}\\x{20983}-\\x{20983}\\x{2098c}-\\x{2098c}\\x{2099c}-\\x{2099c}\\x{20aea}-\\x{20aea}\\x{20afd}-\\x{20afd}\\x{20b19}-\\x{20b19}\\x{22390}-\\x{22390}\\x{22998}-\\x{22998}\\x{23b1b}-\\x{23b1b}\\x{2626d}-\\x{2626d}\\x{2f890}-\\x{2f890}"
    private val word by lazy { "[" + pythonLetterRanges.toList().chunked(2).joinToString("") { "\\x{${it[0].toString(16)}}-\\x{${it[1].toString(16)}}" } + numericWord + "_]" }
    private fun re(pattern: String) = Regex("(?d)" + pattern.replace("\\d", digit).replace("\\s", "[$spaces]").replace("\\S", "[^$spaces]").replace("\\b", "(?:(?<!$word)(?=$word)|(?<=$word)(?!$word))"))
    private fun MatchResult.g(name: String) = groups[name]?.value.orEmpty()
    private fun lines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.split(Regex("\\r\\n|[\\n\\r\\u000b\\u000c\\u001c-\\u001e\\u0085\\u2028\\u2029]")).let { if (it.last().isEmpty()) it.dropLast(1) else it }
    private fun source(units: List<String>): JsonObject {
        require(units.any { valuationStrip(it).isNotEmpty() }) { "uSmart HK 来源文本单元为空" }
        return buildJsonObject { put("原始文本单元", JsonArray(units.map(::JsonPrimitive))) }
    }
    private fun source(text: String) = source(lines(text))
    private fun lineAt(text: String, offset: Int): String = text.substring(text.lastIndexOf('\n', offset - 1) + 1, text.indexOf('\n', offset).takeIf { it >= 0 } ?: text.length)
    private fun row(vararg values: Pair<String, Any?>): UsmartRow = UsmartRow(buildJsonObject { for ((key, value) in values) put(key, when (value) { is JsonElement -> value; is Int -> JsonPrimitive(value); else -> JsonPrimitive(value?.toString()) }) })
    private fun text(row: Map<String, JsonElement>, key: String) = pythonString(row.getValue(key))
    private fun value(row: Map<String, JsonElement>, key: String, fallback: String = ""): String = row[key]?.takeIf { pythonTruthy(it) && !(row is UsmartRow && key in row.decimalFields && pythonString(it).toBigDecimalOrNull()?.signum() == 0) }?.let(::pythonString) ?: fallback
    private fun decimal(value: String): String = try { BrokerText.decimal(value.replace(",", "")) } catch (_: ArithmeticException) { throw IllegalArgumentException("invalid decimal in uSmart HK statement: ${pythonStringRepr(value)}") }
    private fun number(row: Map<String, JsonElement>, field: String): String {
        val raw = row.getValue(field)
        if (row is UsmartRow && field in row.decimalFields) return BrokerText.decimal(text(row, field))
        require(raw is JsonPrimitive && raw != JsonNull) { "conversion to Decimal is not supported" }
        if (raw.isString) return BrokerText.decimal(raw.content)
        raw.booleanOrNull?.let { return if (it) "1" else "0" }
        if (raw.content.any { it in ".eE" }) {
            val d = raw.double
            return if (d.isFinite()) (if (d == 0.0 && d.toRawBits() < 0) "-" else "") + BigDecimal(d).toString() else BrokerText.decimal(raw.content)
        }
        return BrokerText.decimal(raw.content)
    }
    private fun direct(row: Map<String, JsonElement>, field: String): String {
        val raw = row.getValue(field)
        require(row is UsmartRow && field in row.decimalFields || raw is JsonPrimitive && raw != JsonNull && !raw.isString) { "bad operand type for abs(): 'str'" }
        return number(row, field)
    }
    private fun fmt(raw: String): String {
        val d = BrokerText.decimal(raw); val n = d.toBigDecimalOrNull() ?: return d
        if (n.scale() > 18) {
            val rounded = n.setScale(18, RoundingMode.HALF_UP)
            if (rounded.precision() > 28) throw ArithmeticException("invalid decimal operation")
            return (if (rounded.signum() == 0 && d.startsWith('-')) "-" else "") + rounded.toPlainString().trimEnd('0').trimEnd('.').ifEmpty { "0" }
        }
        return (if (n.signum() == 0 && d.startsWith('-')) "-" else "") + n.toPlainString()
    }
    private fun formatted(row: Map<String, JsonElement>, field: String) = fmt(number(row, field))
    private fun minus(a: String, b: String) = BigDecimal(a).subtract(BigDecimal(b), context).toString()
    private fun divide(row: Map<String, JsonElement>): String {
        val a = direct(row, "gross"); val b = direct(row, "qty")
        fun isDecimal(key: String) = row is UsmartRow && key in row.decimalFields
        fun isFloat(key: String) = !isDecimal(key) && row.getValue(key).jsonPrimitive.content.any { it in ".eE" }
        require(!(isDecimal("gross") && isFloat("qty") || isDecimal("qty") && isFloat("gross"))) { "unsupported operand types for /" }
        if (!isDecimal("gross") && !isDecimal("qty")) {
            if (b.toDouble() == 0.0) throw ArithmeticException("division by zero")
            val result = if (!isFloat("gross") && !isFloat("qty")) integerDivide(BigInteger(a), BigInteger(b)) else a.toDouble() / b.toDouble()
            return if (result.isFinite()) (if (result == 0.0 && result.toRawBits() < 0) "-" else "") + BigDecimal(result).toString() else BrokerText.decimal(result.toString())
        }
        val values = listOf(a, b)
        if (values.any { it.removePrefix("-").startsWith("sNaN") }) throw ArithmeticException("invalid decimal operation")
        values.firstOrNull { it.removePrefix("-").startsWith("NaN") }?.let { return it }
        val negative = a.startsWith('-') != b.startsWith('-')
        if (a.endsWith("Infinity") && b.endsWith("Infinity")) throw ArithmeticException("invalid decimal operation")
        if (a.endsWith("Infinity")) return (if (negative) "-" else "") + "Infinity"
        if (b.endsWith("Infinity")) return if (negative) "-0E-1000026" else "0E-1000026"
        val result = BigDecimal(a).divide(BigDecimal(b), context)
        return (if (negative) "-" else "") + BrokerText.abs(result.toString())
    }
    private fun integerDivide(a: BigInteger, b: BigInteger): Double {
        if (b.signum() == 0) throw ArithmeticException("division by zero")
        val negative = (a.signum() < 0) != (b.signum() < 0); val numerator = a.abs(); val denominator = b.abs()
        if (numerator.signum() == 0) return if (negative) -0.0 else 0.0
        var exponent = numerator.bitLength() - denominator.bitLength()
        if (if (exponent >= 0) numerator < denominator.shiftLeft(exponent) else numerator.shiftLeft(-exponent) < denominator) exponent--
        if (exponent > 1023) throw ArithmeticException("integer division result too large for a float")
        val shift = if (exponent < -1022) 1074 else 52 - exponent
        val scaled = if (shift >= 0) numerator.shiftLeft(shift) else numerator
        val divisor = if (shift < 0) denominator.shiftLeft(-shift) else denominator
        val (quotient, remainder) = scaled.divideAndRemainder(divisor); val half = remainder.shiftLeft(1).compareTo(divisor)
        val rounded = if (half > 0 || half == 0 && quotient.testBit(0)) quotient + BigInteger.ONE else quotient
        val result = Math.scalb(rounded.toDouble(), -shift)
        if (!result.isFinite()) throw ArithmeticException("integer division result too large for a float")
        return if (negative) -result else result
    }
    private fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.entries.sortedWith { a, b -> pythonStringOrder(a.key, b.key) }.joinToString(",", "{", "}") { JsonPrimitive(it.key).toString() + ":" + canonical(it.value) }
        is JsonArray -> value.joinToString(",", "[", "]", transform = ::canonical)
        is JsonPrimitive -> if (value.isString || value == JsonNull || value.booleanOrNull != null) value.toString() else if (value.content.any { it in ".eE" }) pythonFloatText(value.double) else value.content
    }
    fun identity(row: Map<String, JsonElement>): String {
        val kind = text(row, "kind"); val prefix = if (row["_profile"] == JsonPrimitive("day") || row["profile"] == JsonPrimitive("day")) "usmart_hk:day" else "usmart_hk"
        val ident = when (kind) {
            "trade" -> "$prefix:trade:${text(row, "date")}:${text(row, "ticker")}:${text(row, "side")}:${formatted(row, "qty")}:${formatted(row, "gross")}:${formatted(row, "net")}:${if (value(row, "commission").isEmpty()) "0" else formatted(row, "commission")}:${text(row, "ccy")}"
            "cash" -> {
                val base = "$prefix:cash:${text(row, "date")}:${text(row, "flag_norm")}:${text(row, "ccy")}:${formatted(row, "amount")}"
                val payload = row["_source_payload"]; require(payload is JsonObject && payload.isNotEmpty()) { "uSmart HK 现金流水缺少来源行快照" }
                "$base:${tokenDigest(canonical(payload)).take(16)}"
            }
            "fx" -> "$prefix:fx:${text(row, "from_date")}:${text(row, "from_ccy")}:${formatted(row, "from_amount")}:${text(row, "to_date")}:${text(row, "to_ccy")}:${formatted(row, "to_amount")}"
            "checkin_cash" -> "$prefix:checkin:cash:${text(row, "period")}:${text(row, "ccy")}:${formatted(row, "amount")}"
            "checkin_position" -> "$prefix:checkin:pos:${text(row, "period")}:${text(row, "ticker")}:${formatted(row, "shares")}"
            else -> throw IllegalArgumentException("unsupported uSmart HK row kind: $kind")
        }
        return ident + if (pythonTruthy(row["_id_seq"])) "#${text(row, "_id_seq")}" else ""
    }
    fun event(row: Map<String, JsonElement>, account: String, currency: String? = null): JsonObject {
        val kind = text(row, "kind"); val ccy = valuationUpper(value(row, "ccy", currency?.takeIf { it.isNotEmpty() } ?: "USD")); val cash = pythonCashLower(ccy); val note = value(row, "note")
        fun result(type: String, subtype: String, from: String, fromAmount: String, to: String, toAmount: String, price: String = "1", fee: String = "0", feeAsset: String = "", outputNote: String = note) = buildJsonObject {
            put("date", row.getValue("date")); put("account_name", account); put("currency", ccy); put("note", outputNote); put("record_type", type); put("record_subtype", subtype); put("from_ticker", from); put("from_amount", fromAmount); put("to_ticker", to); put("to_amount", toAmount); put("price", price); put("commission", fee); put("commission_asset", feeAsset)
        }
        fun cashEvent(type: String, subtype: String): JsonObject {
            val n = BrokerText.decimal(text(row, "amount")); val amount = fmt(BrokerText.abs(n)); val incoming = FinanceDecimal.parse(n) >= FinanceDecimal.ZERO
            return result(type, subtype, if (incoming) "" else cash, if (incoming) "0" else amount, if (incoming) cash else "", if (incoming) amount else "0")
        }
        when (kind) {
            "trade" -> {
                val buy = text(row, "side") == "BUY"; val ticker = pythonCashLower(text(row, "ticker")); val gross = formatted(row, "gross"); val qty = formatted(row, "qty")
                return result("trade", "security", if (buy) cash else ticker, if (buy) gross else qty, if (buy) ticker else cash, if (buy) qty else gross, fmt(divide(row)), formatted(row, "commission"), cash)
            }
            "fx" -> return result("trade", "fx", pythonCashLower(text(row, "from_ccy")), formatted(row, "from_amount"), pythonCashLower(text(row, "to_ccy")), formatted(row, "to_amount"), "0")
            "cash" -> {
                val n = direct(row, "amount"); val amount = if (row is UsmartRow && "amount" in row.decimalFields) BrokerText.abs(n) else n.removePrefix("-"); val flag = value(row, "flag_norm", value(row, "flag")); val sourceText = "$flag $note"; val lower = pythonCashLower(sourceText)
                if (flag.startsWith("红利入账")) return result("income", "dividend_cash", "", "0", cash, fmt(amount))
                val taxRefund = ("税" in sourceText || "tax" in lower || "withhold" in lower) && ("退" in sourceText || "refund" in lower || flag == "资金存")
                if ("IPO认购手续费" in flag || flag.startsWith("IPO") && "手续费" in flag || "IPO" in flag && "Handling" in note) return cashEvent("expense", "handling_fee")
                fun nonnegative(message: String) { require(FinanceDecimal.parse(n) >= FinanceDecimal.ZERO) { message } }
                if (taxRefund) { nonnegative("税费返还必须为现金入账"); return cashEvent("reversal", "expense_tax") }
                val charge = mapOf("融资利息" to "interest", "融券利息" to "interest", "融券罚息转出" to "penalty", "罚息转出" to "penalty", "美股股息税" to "tax", "红利税费" to "tax", "股息税" to "tax", "股息代收费" to "handling_fee")[flag]
                if (charge != null) return if (FinanceDecimal.parse(n) > FinanceDecimal.ZERO) cashEvent("reversal", "expense_$charge") else cashEvent("expense", charge)
                val reversal = mapOf("平台费返还" to "expense_handling_fee", "佣金返还" to "expense_commission", "手续费返还" to "expense_commission")[flag]
                if (reversal != null) { nonnegative("费用返还必须为现金入账"); return cashEvent("reversal", reversal) }
                if ("IPO" in flag && "扣款" in flag || "IPO Debit" in note) return cashEvent("subscription", "ipo_debit")
                if ("IPO" in flag && "退款" in flag || "IPO Refund" in note) return cashEvent("subscription", "ipo_refund")
                if (flag == "出金退款") { nonnegative("出金退款必须为现金入账"); return cashEvent("reversal", "funding_withdrawal") }
                if (flag == "优惠券") { nonnegative("奖励必须为现金入账"); return cashEvent("income", "reward") }
                if (flag in setOf("转入到日内融账户", "转入到保证金账户", "从保证金账户转入", "从日内融账户转出", "从日内融账户转入")) return cashEvent("funding", "subaccount")
                if (flag in setOf("入金", "出金", "提取", "资金存", "EDDA入金", "EDDA出金")) return cashEvent("funding", "external")
                throw IllegalArgumentException("unsupported uSmart HK cash flag for normalized funding: ${pythonStringRepr(flag)}")
            }
            "checkin_cash" -> return result("snapshot", "cash", "", "0", cash, formatted(row, "amount"), outputNote = "")
            "checkin_position" -> return result("snapshot", "position", "", "0", pythonCashLower(text(row, "ticker")), formatted(row, "shares"), "0", outputNote = "")
            else -> throw IllegalArgumentException("unsupported uSmart HK row kind: $kind")
        }
    }
    private fun normalize(text: String): String {
        val from = "⾦⼾⼊⽉⽴⼦⼿⽇⻩⾕⽅"; val to = "金户入月立子手日黄谷方"
        return text.map { c -> from.indexOf(c).takeIf { it >= 0 }?.let { to[it] } ?: c }.joinToString("")
    }
    fun profile(text: String): String = if ("交易明细" in text && "开仓记录" in text || "日内融" in text.substring(0, text.offsetByCodePoints(0, minOf(300, text.codePointCount(0, text.length))))) "day" else "margin"
    fun parse(text: List<String>): List<UsmartRow> = parse(text.joinToString("\n"))
    fun parse(text: String): List<UsmartRow> {
        val original = text.replace("\r", ""); val rendered = normalize(original); val profile = profile(rendered)
        val day = re("结单日期\\s*[：:]?\\s*(\\d{4})-(\\d{2})-(\\d{2})").find(rendered)
        val period: String; val end: String
        if (day != null) { period = day.groupValues.drop(1).joinToString("-"); end = period }
        else {
            val month = re("结单日期\\s*[：:]?\\s*(\\d{4})-(\\d{2})").find(rendered) ?: throw IllegalArgumentException("uSmart HK statement is missing 结单日期")
            period = month.groupValues.drop(1).joinToString("-")
            val year = pythonIntegerValue(JsonPrimitive(month.groupValues[1])).toInt(); val m = pythonIntegerValue(JsonPrimitive(month.groupValues[2])).toInt()
            require(m in 1..12) { "bad month number $m; must be 1-12" }; end = "$period-${YearMonth.of(year, m).lengthOfMonth()}"
        }
        val trades = trades(rendered, original); val (cash, ignored) = cashMovements(rendered, original)
        val ipo = re("(?:IPO|认购|中签|新股)[^\\n]{0,40}(?<!\\d)(0\\d{4}|\\d{5})(?!\\d)|(?<!\\d)(0\\d{4}|\\d{5})(?!\\d)[^\\n]{0,40}(?:IPO|认购|中签|新股)").findAll(rendered).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.distinct().toList()
        val cashWithIpo = cash.map { r -> val flag = value(r, "flag_norm", value(r, "flag")); if (ipo.size == 1 && ("IPO" in flag || "认购" in flag)) UsmartRow(JsonObject(r + ("ipo_code" to JsonPrimitive(ipo.single())))) else r }
        val cashSnapshots = cashCheckins(rendered, original, period, end, profile)
        val held = holdings(rendered, original, period, end); val heldCodes = held.map { pythonCashLower(text(it, "ticker")) }.toSet()
        val holdingStart = rendered.indexOf("持仓明细"); val holdingEnd = rendered.indexOf("资金出入", maxOf(holdingStart, 0)).takeIf { it >= 0 } ?: rendered.indexOf("市值汇总", maxOf(holdingStart, 0))
        require(holdingStart >= 0 && holdingEnd >= 0) { "uSmart HK statement is missing 持仓明细 source text" }; val closedSource = source(original.substring(holdingStart, holdingEnd))
        val closed = linkedMapOf<String, String>(); for (t in trades) if (pythonCashLower(text(t, "ticker")) !in heldCodes) closed[text(t, "ticker")] = text(t, "ccy")
        val closedRows = closed.entries.sortedWith { a, b -> pythonStringOrder(pythonCashLower(a.key), pythonCashLower(b.key)) }.map { (ticker, ccy) -> row("kind" to "checkin_position", "date" to end, "period" to period, "ticker" to ticker, "ccy" to ccy, "shares" to "0", "note" to "", "_source_payload" to closedSource) }
        val rows = trades + cashWithIpo + cashSnapshots + held + closedRows
        val seen = mutableMapOf<String, Int>()
        return rows.mapIndexed { i, r ->
            val fields = r.toMutableMap(); if (i == 0) { fields["_usmart_ignored_trade_mirrors"] = JsonPrimitive(ignored); fields["_usmart_statement_profile"] = JsonPrimitive(profile) }; fields["_profile"] = JsonPrimitive(profile)
            val key = identity(UsmartRow(JsonObject(fields))); val n = seen.getOrDefault(key, 0); if (n > 0) fields["_id_seq"] = JsonPrimitive(n); seen[key] = n + 1
            UsmartRow(JsonObject(fields))
        }
    }
    private val fill = re("(?:(?<market>美股|港股)\\s+)?(?<side>买入|卖出)\\s+(?<qty>[\\d,.]+)\\s+(?<ccy>USD|HKD|CNY)\\s+[\\d,.]+\\s+(?<amount>[\\d,.]+)\\s+(?<date>\\d{4}-\\d{2}-\\d{2})")
    private fun groupTicker(block: String, start: Int): String {
        val lead = block.substring(0, start).substringAfterLast('\u000c')
        val noise = listOf("证监会", "證監會", "usmart", "盈立", "電話", "传真", "傳真", "地址", "邮箱", "郵箱", "网址", "網址", "SFC", "证券/编号", "买/卖", "成交时间", "交收日期", "是否强平")
        val cleaned = lines(lead).map(::valuationStrip).filter { it.isNotEmpty() && noise.none { n -> n in it } }
        val pattern = re("^\\s*(?<code>[A-Z]{1,5}|0\\d{4})(?:\\s*\\(|\\s+\\S|\\s*$)")
        for (line in cleaned) pattern.find(line)?.let { return it.g("code") }
        re("(?<![A-Z0-9])(?<code>[A-Z]{1,5}|0\\d{4})(?=\\s*\\(|\\s+(?:美股|港股)\\b)").find(cleaned.joinToString(" "))?.let { return it.g("code") }
        val count = lead.codePointCount(0, lead.length); throw IllegalArgumentException("trade group missing ticker near ${pythonStringRepr(lead.substring(lead.offsetByCodePoints(0, maxOf(count - 120, 0))))}")
    }
    private fun trades(text: String, original: String): List<UsmartRow> {
        val start = text.indexOf("交易明细"); val end = text.indexOf("开仓记录", maxOf(0, start)).takeIf { it >= 0 } ?: text.indexOf("持仓明细", maxOf(0, start))
        require(start >= 0 && end >= 0) { "uSmart HK statement is missing 交易明细 or 持仓明细" }
        val section = text.substring(start, end); val sourceSection = original.substring(start, end); val rows = mutableListOf<UsmartRow>(); var cursor = 0
        for (match in re("变动金额合计\\s*\\n?\\s*(-?[\\d,]+(?:\\.\\d+)?)").findAll(section)) {
            val block = section.substring(cursor, match.range.first); val sourceBlock = sourceSection.substring(cursor, match.range.last + 1); cursor = match.range.last + 1
            val fills = fill.findAll(block).toList(); if (fills.isEmpty()) continue
            val first = fills.first(); val code = groupTicker(block, first.range.first); val ccy = first.g("ccy")
            val ticker = BrokerText.equity(code, if (first.g("market") == "港股") "HKD" else ccy)
            require(fills.map { it.g("side") }.distinct().size == 1 && fills.map { it.g("ccy") }.distinct().size == 1) { "mixed trade group near ${pythonStringRepr(first.value)}" }
            val grossMatch = re("交易金额(?:合计)?\\s*\\n?\\s*([\\d,]+(?:\\.\\d+)?)").find(block) ?: throw IllegalArgumentException("trade group missing 交易金额 near ${pythonStringRepr(first.value)}")
            val gross = decimal(grossMatch.groupValues[1]); val net = decimal(match.groupValues[1]); val side = if (first.g("side") == "买入") "BUY" else "SELL"
            var fee = if (side == "BUY") minus(BrokerText.abs(net), gross) else minus(gross, BrokerText.abs(net))
            require(BigDecimal(fee) >= BigDecimal("-0.02")) { "fee imbalance for $ticker: side=$side gross=$gross net=$net" }; if (BigDecimal(fee).signum() < 0) fee = "0"
            val qty = fills.fold(BigDecimal.ZERO) { total, f -> total.add(BigDecimal(decimal(f.g("qty"))), context) }.toString()
            rows += row("kind" to "trade", "date" to first.g("date"), "ticker" to ticker, "side" to side, "qty" to qty, "gross" to gross, "net" to net, "commission" to fee, "ccy" to ccy, "note" to "", "_source_payload" to source(sourceBlock))
        }
        return rows
    }
    fun holdings(text: String, original: String, period: String, end: String): List<UsmartRow> {
        val start = text.indexOf("持仓明细"); if (start < 0) return emptyList()
        val stop = text.indexOf("资金出入", start).takeIf { it >= 0 } ?: text.indexOf("市值汇总", start).takeIf { it >= 0 } ?: text.length
        val section = text.substring(start, stop); val sourceSection = original.substring(start, minOf(stop, original.length))
        if ("暂无数据" in section && !re("\\b(?:USD|HKD|CNY)\\b").containsMatchIn(section)) return emptyList()
        fun holding(code: String, ccy: String, shares: String, payload: JsonObject) = row("kind" to "checkin_position", "date" to end, "period" to period, "ticker" to BrokerText.equity(code, ccy), "ccy" to ccy, "shares" to decimal(shares), "note" to "", "_source_payload" to payload)
        val pattern = re("(?m)^\\s*(?<code>[A-Z]{1,5}|0\\d{4})(?:[^\\S\\n]*\\([^\\n]*\\))?[^\\S\\n]+(?<ccy>USD|HKD|CNY)[^\\S\\n]+(?<shares>[\\d,]+(?:\\.\\d+)?)")
        val horizontal = pattern.findAll(section).map { holding(it.g("code"), it.g("ccy"), it.g("shares"), source(lineAt(sourceSection, it.groups["code"]!!.range.first))) }.toList()
        if (horizontal.isNotEmpty()) return horizontal
        val sourceLines = lines(sourceSection); val normalizedLines = lines(section); require(sourceLines.size == normalizedLines.size) { "uSmart HK 持仓来源文本无法逐行对应" }
        val pairs = normalizedLines.zip(sourceLines).map { valuationStrip(it.first) to it.second }.filter { it.first.isNotEmpty() }; val rows = mutableListOf<UsmartRow>(); var i = 0
        val headers = setOf("持仓明细", "证券", "币种", "持有数量", "未交收数量", "收市价", "市值", "抵押比率", "抵押市值", "市值汇总", "抵押市值汇总", "HKD", "USD", "CNY")
        val codePattern = re("^(?<code>[A-Z]{1,5}|0\\d{4})\\b")
        while (i < pairs.size) {
            val match = codePattern.find(pairs[i].first); if (pairs[i].first in headers || match == null) { i++; continue }
            var j = i + 1; var ccy: String? = null; var shares: String? = null
            while (j < pairs.size && j < i + 12) {
                if (pairs[j].first in setOf("USD", "HKD", "CNY")) { ccy = pairs[j].first; shares = pairs.getOrNull(j + 1)?.first?.takeIf { re("^[\\d,]+(?:\\.\\d+)?$").containsMatchIn(it) }; break }; j++
            }
            if (ccy != null && shares != null) {
                var recordEnd = j + 2; while (recordEnd < pairs.size && codePattern.find(pairs[recordEnd].first) == null) recordEnd++
                rows += holding(match.g("code"), ccy, shares, source(pairs.subList(i, recordEnd).map { it.second })); i = recordEnd
            } else i++
        }
        return rows
    }
    private fun cashCheckins(text: String, original: String, period: String, end: String, profile: String): List<UsmartRow> {
        var values = emptyList<String>(); var payloads = emptyList<JsonObject?>()
        val amount = "--|-?[\\d,]+(?:\\.\\d+)?"; val inline = re("期末账户结余(?<line>[^\\n]+)").find(text)
        if (inline != null && re(amount).containsMatchIn(inline.g("line"))) { values = re(amount).findAll(inline.g("line")).map { it.value }.toList(); payloads = List(values.size) { source(original.substring(inline.range)) } }
        fun stacked(label: String) = re("$label\\s*\\n\\s*($amount)").findAll(text).toList()
        fun use(entries: List<MatchResult>) { values = entries.map { it.groupValues[1] }; payloads = entries.map { source(original.substring(it.range)) } }
        if (values.size < 3) { val entries = stacked("期末账户结余"); if (entries.size >= 3) use(entries.take(3)) }
        if (values.size < 3 && profile == "day") {
            val assets = stacked("期末净资产"); if (assets.size >= 2) use(assets.take(2)) else { val changes = stacked("变动金额汇总"); if (changes.size >= 2) use(changes.take(2)) }
        }
        require(values.size >= 2) { "uSmart HK statement is missing 期末账户结余/变动金额汇总" }
        return listOf("HKD", "USD", "CNY").zip(values).mapIndexedNotNull { i, (ccy, amount) -> if (amount == "--") null else row("kind" to "checkin_cash", "date" to end, "period" to period, "ccy" to ccy, "amount" to decimal(amount), "note" to "", "_source_payload" to requireNotNull(payloads[i]) { "uSmart HK 现金快照缺少来源文本单元" }) }
    }
    private fun cashMovements(text: String, original: String): Pair<List<UsmartRow>, Int> {
        val start = text.indexOf("资金出入"); require(start >= 0) { "uSmart HK statement is missing 资金出入" }
        val end = text.indexOf("证券提存", start).takeIf { it >= 0 } ?: text.indexOf("重要提示", start).takeIf { it >= 0 } ?: text.length
        val section = text.substring(start, end); val sourceSection = original.substring(start, end); val rows = mutableListOf<UsmartRow>(); val fx = mutableListOf<UsmartRow>(); var ignored = 0
        val flags = setOf("IPO认购退款", "IPO认购扣款", "IPO认购手续费", "出金", "出金退款", "融资利息", "转入到日内融账户", "入金", "提取", "转出", "转入", "从保证金账户转入", "转入到保证金账户", "从日内融账户转出", "从日内融账户转入", "优惠券", "红利入账", "美股股息税", "股息代收费", "红利税费", "股息税", "资金存", "融券罚息转出", "融券利息", "罚息转出", "EDDA入金", "EDDA出金", "平台费返还", "佣金返还", "手续费返还")
        fun consume(match: MatchResult, payload: JsonObject) {
            val flag = normalize(match.g("flag")); val r = row("kind" to "cash", "date" to match.g("date"), "flag" to flag, "flag_norm" to flag, "ccy" to match.g("ccy"), "amount" to decimal(match.g("amount")), "note" to valuationStrip(match.g("note")).ifEmpty { flag }, "_source_payload" to payload)
            val n = flag.replace("續", "续").replace("手續", "手续").replace("（", "(").replace("）", ")")
            if (flag == "换汇") fx += r
            else if ("手续费" in n && ("股票" in n || "沽空" in n) || "股票" in n && (n.startsWith("买") || n.startsWith("卖") || "沽空" in n)) ignored++
            else if (flag in flags || flag.endsWith("入金") || flag.endsWith("出金")) rows += r
            else if (flag.endsWith("费") && "股票" !in flag && "沽空" !in flag && flag.length <= 6) ignored++
            else throw IllegalArgumentException("unknown cash flag ${pythonStringRepr(flag)}")
        }
        val pattern = re("^\\s*(?<flag>[A-Za-z0-9\\u4e00-\\u9fff]+)\\s+(?<ccy>USD|HKD|CNY)\\s+(?<amount>-?[\\d,]+(?:\\.\\d+)?)\\s+(?<date>\\d{4}-\\d{2}-\\d{2})(?<note>.*)$")
        var hits = 0; val sourceLines = lines(sourceSection); val normalizedLines = lines(section)
        require(sourceLines.size == normalizedLines.size) { "uSmart HK 资金流水来源文本无法逐行对应" }
        for ((line, sourceLine) in normalizedLines.zip(sourceLines)) pattern.find(line)?.let { hits++; consume(it, source(sourceLine)) }
        if (hits == 0) {
            val stacked = re("(?<flag>[A-Za-z0-9\\u4e00-\\u9fff]+)\\s*\\n\\s*(?<ccy>USD|HKD|CNY)\\s*\\n\\s*(?<amount>-?[\\d,]+(?:\\.\\d+)?)\\s*\\n\\s*(?<date>\\d{4}-\\d{2}-\\d{2})(?:\\s*\\n\\s*(?<note>[A-Za-z][^\\n]*))?")
            for (m in stacked.findAll(section)) if (m.g("flag") !in setOf("币种", "业务标志", "金额", "交易时间", "备注")) consume(m, source(sourceSection.substring(m.range)))
        }
        fun date(row: UsmartRow): LocalDate {
            val value = text(row, "date")
            require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) { "Invalid isoformat string: ${pythonStringRepr(value)}" }
            val year = value.take(4).toInt(); val month = value.substring(5, 7).toInt(); val day = value.takeLast(2).toInt()
            require(year in 1..9999) { "year $year is out of range" }; require(month in 1..12) { "month must be in 1..12" }; require(day in 1..YearMonth.of(year, month).lengthOfMonth()) { "day is out of range for month" }
            return LocalDate.of(year, month, day)
        }
        fun days(a: UsmartRow, b: UsmartRow): Long = kotlin.math.abs(ChronoUnit.DAYS.between(date(a), date(b)))
        while (fx.isNotEmpty()) {
            val first = fx.removeAt(0); val candidates = fx.filter { text(it, "ccy") != text(first, "ccy") && BigDecimal(text(it, "amount")).multiply(BigDecimal(text(first, "amount")), context).signum() < 0 && days(it, first) <= 3 }
            require(candidates.isNotEmpty()) { "无法配对换汇流水：${text(first, "date")} ${text(first, "ccy")} ${formatted(first, "amount")}" }
            fun score(other: UsmartRow): BigDecimal {
                val a = BigDecimal(BrokerText.abs(text(first, "amount"))); val b = BigDecimal(BrokerText.abs(text(other, "amount")))
                val rate = when { text(first, "ccy") == "HKD" && text(other, "ccy") == "USD" -> a.divide(b, context); text(first, "ccy") == "USD" && text(other, "ccy") == "HKD" -> b.divide(a, context); else -> maxOf(a, b).divide(minOf(a, b), context) }
                return rate.subtract(BigDecimal("7.8"), context).abs(context).add(BigDecimal(days(first, other)).multiply(BigDecimal("0.05"), context), context)
            }
            val other = candidates.minBy(::score); fx.remove(other); val (out, incoming) = if (BigDecimal(text(first, "amount")).signum() < 0) first to other else other to first
            val units = out.getValue("_source_payload").jsonObject.getValue("原始文本单元").jsonArray + incoming.getValue("_source_payload").jsonObject.getValue("原始文本单元").jsonArray
            rows += row("kind" to "fx", "date" to minOf(text(first, "date"), text(other, "date")), "from_date" to text(out, "date"), "from_ccy" to text(out, "ccy"), "from_amount" to BrokerText.abs(text(out, "amount")), "to_date" to text(incoming, "date"), "to_ccy" to text(incoming, "ccy"), "to_amount" to BrokerText.abs(text(incoming, "amount")), "ccy" to text(out, "ccy"), "note" to text(out, "note"), "_source_payload" to buildJsonObject { put("原始文本单元", JsonArray(units)) })
        }
        return rows to ignored
    }
}
