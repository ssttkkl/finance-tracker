package com.finance.tracker.backend

import java.math.BigInteger
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.json.*

internal class InvestmentReadFailure(val code: String = "invalid_filter") : RuntimeException(code)
internal data class InvestmentReadPosition(val version: BigInteger, val occurredAt: Instant, val id: BigInteger)
internal data class InvestmentReadInputs(val filters: Map<String, String?>, val limit: Int, val account: BigInteger?,
    val startDate: LocalDate?, val endDate: LocalDate?, val zone: ZoneId, val cursor: String?) {
    companion object {
        private fun invalid(): Nothing = throw InvestmentReadFailure()
        private fun integer(raw: String): BigInteger = try { pythonIntegerValue(JsonPrimitive(raw)) }
            catch (_: CategoryFailure) { invalid() }
        private fun date(raw: String): LocalDate = try {
            when {
                raw.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) -> LocalDate.parse(raw)
                raw.matches(Regex("[0-9]{8}")) -> LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE)
                raw.matches(Regex("[0-9]{4}(?:-W[0-9]{2}(?:-[0-9])?|W[0-9]{2}[0-9]?)")) -> {
                    val compact = raw.replace("-", "")
                    LocalDate.parse(compact.take(4) + "-W" + compact.substring(5, 7) + "-" +
                        if (compact.length == 8) compact.last() else '1', DateTimeFormatter.ISO_WEEK_DATE)
                }
                else -> invalid()
            }.also { if (it.year !in 1..9999) invalid() }
        } catch (_: DateTimeException) { invalid() }
        fun parse(values: Map<String, String>): InvestmentReadInputs {
            val size = values["limit"]?.let(::integer) ?: BigInteger.valueOf(50)
            if (size < BigInteger.ONE || size > BigInteger.valueOf(50)) invalid()
            val first = values["date_from"]?.takeIf { it.isNotEmpty() }?.let(::date)
            val last = values["date_to"]?.takeIf { it.isNotEmpty() }?.let(::date)
            if (first != null && last != null && first > last) invalid()
            val name = (values["timezone"]?.takeIf { it.isNotEmpty() } ?: "UTC").trim { it.code in pythonWhitespace }
            val zone = when (name) {
                "EST" -> ZoneOffset.ofHours(-5)
                "MST" -> ZoneOffset.ofHours(-7)
                "HST" -> ZoneOffset.ofHours(-10)
                "Factory" -> ZoneOffset.UTC
                else -> if (name in ZoneId.getAvailableZoneIds()) ZoneId.of(name) else invalid()
            }
            val account = values["account_id"]?.takeIf { it.isNotEmpty() }?.let(::integer)
            if (account != null && account.signum() <= 0) invalid()
            val type = values["record_type"]?.takeIf { it.isNotEmpty() }
            if (type !in setOf(null, "funding", "trade", "income", "expense", "reversal", "subscription", "adjustment", "snapshot")) invalid()
            val ticker = values["ticker"]?.takeIf { it.isNotEmpty() }?.let { raw ->
                val text = raw.trim { it.code in pythonWhitespace }
                if (text.isEmpty() || text.codePointCount(0, text.length) > 64) invalid()
                text.lowercase(Locale.ROOT)
            }
            val filters = linkedMapOf("account_id" to account?.toString(), "date_from" to first?.toString(),
                "date_to" to last?.toString(), "record_type" to type, "ticker" to ticker, "timezone" to name)
            return InvestmentReadInputs(filters, size.toInt(), account, first, last, zone, values["cursor"])
        }
    }
    fun bounds(): Pair<Instant?, Instant?> {
        if (endDate == LocalDate.of(9999, 12, 31)) throw InvestmentReadFailure("internal")
        return startDate?.atStartOfDay(zone)?.toInstant() to endDate?.plusDays(1)?.atStartOfDay(zone)?.toInstant()
    }
    fun position(workspace: String): InvestmentReadPosition? {
        if (cursor.isNullOrEmpty()) return null
        try {
            if (cursor.any { it.code > 0x7f }) throw InvestmentReadFailure("invalid_cursor")
            val bytes = Base64.getMimeDecoder().decode(cursor.replace('-', '+').replace('_', '/') + "=".repeat((4 - cursor.length % 4) % 4))
            val payload = categoryDeleteRequest(pythonJsonText(bytes)).value as? JsonObject ?: throw InvestmentReadFailure("invalid_cursor")
            fun integer(name: String): BigInteger {
                val value = payload[name] as? JsonPrimitive ?: throw InvestmentReadFailure("invalid_cursor")
                if (value.isString || value.booleanOrNull != null || !value.content.matches(Regex("-?[0-9]+"))) throw InvestmentReadFailure("invalid_cursor")
                return value.content.toBigInteger()
            }
            if (integer("v") != BigInteger.ONE || payload["workspace"] != JsonPrimitive(workspace) || payload["filters"] != sourceJson(filters))
                throw InvestmentReadFailure("invalid_cursor")
            val version = integer("version")
            val id = integer("row_id")
            if (id.signum() <= 0) throw InvestmentReadFailure("invalid_cursor")
            val date = payload["occurred_at"] as? JsonPrimitive ?: throw InvestmentReadFailure("invalid_cursor")
            if (!date.isString) throw InvestmentReadFailure("invalid_cursor")
            return InvestmentReadPosition(version, pythonCashTimestamp(date.content, preserveZSeparator = true), id)
        } catch (failure: InvestmentReadFailure) { throw failure }
        catch (_: Exception) { throw InvestmentReadFailure("invalid_cursor") }
    }
    fun encode(workspace: String, version: Long, occurredAt: Instant, id: Long): String {
        val value = sourceJson(linkedMapOf("filters" to filters, "occurred_at" to manualInstantText(occurredAt), "row_id" to id,
            "v" to 1, "version" to version, "workspace" to workspace)).toString()
        val ascii = buildString { value.forEach { if (it.code >= 127) append("\\u" + it.code.toString(16).padStart(4, '0')) else append(it) } }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(ascii.toByteArray(Charsets.US_ASCII))
    }
}
