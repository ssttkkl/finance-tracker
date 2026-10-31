package com.finance.tracker.backend

import com.finance.tracker.business.domain.LedgerDecimal
import java.math.BigDecimal
import java.math.BigInteger
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.json.*

internal class CashProjectionListFailure(val code: String) : RuntimeException(code)
internal class CashProjectionListBooleanFailure(val input: String) : RuntimeException("bool_parsing")
internal fun invalidProjectionFilter(): Nothing = throw CashProjectionListFailure("invalid_filter")
internal fun invalidProjectionCursor(): Nothing = throw CashProjectionListFailure("invalid_cursor")

internal data class CashProjectionListInputs(
    val filters: Map<String, String?>, val limit: Int, val index: BigInteger?, val version: BigInteger?,
    val account: BigInteger?, val minimum: BigDecimal?, val maximum: BigDecimal?, val zone: ZoneId,
    val start: Instant?, val end: Instant?, val cursor: String?,
) {
    companion object {
        private fun integer(raw: String): BigInteger = try { pythonIntegerValue(JsonPrimitive(raw)) }
            catch (_: CategoryFailure) { invalidProjectionFilter() }
        private fun date(raw: String): LocalDate = try {
            when {
                raw.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) -> LocalDate.parse(raw)
                raw.matches(Regex("[0-9]{8}")) -> LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE)
                raw.matches(Regex("[0-9]{4}(?:-W[0-9]{2}(?:-[0-9])?|W[0-9]{2}[0-9]?)")) -> {
                    val compact = raw.replace("-", "")
                    LocalDate.parse(compact.take(4) + "-W" + compact.substring(5, 7) + "-" + if (compact.length == 8) compact.last() else '1', DateTimeFormatter.ISO_WEEK_DATE)
                }
                else -> invalidProjectionFilter()
            }.also { if (it.year !in 1..9999) invalidProjectionFilter() }
        } catch (_: DateTimeException) { invalidProjectionFilter() }
        private fun decimal(raw: String?): Pair<String?, BigDecimal?> {
            if (raw.isNullOrEmpty()) return null to null
            val representation = LedgerDecimal.parseRepresentationOrNull(raw) ?: invalidProjectionFilter()
            val exponent = representation.exponent
            if (exponent !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) throw CashProjectionListFailure("internal")
            val number = BigDecimal((if (representation.negative) "-" else "") + representation.coefficient + "E" + exponent)
            val text = (if (representation.negative && number.signum() == 0) "-" else "") + number.toPlainString()
            return text to number
        }
        fun parse(values: Map<String, String>): CashProjectionListInputs {
            val size = values["limit"]?.let(::integer) ?: BigInteger.valueOf(50)
            if (size < BigInteger.ONE || size > BigInteger.valueOf(50)) invalidProjectionFilter()
            val index = values["start_index"]?.let(::integer)
            val version = values["projection_version"]?.let(::integer)
            if (index != null && (index.signum() < 0 || "cursor" in values) || version != null && (index == null || version.signum() < 0)) invalidProjectionCursor()
            val startDate = values["date_from"]?.takeIf { it.isNotEmpty() }?.let(::date)
            val endDate = values["date_to"]?.takeIf { it.isNotEmpty() }?.let(::date)
            if (startDate != null && endDate != null && startDate > endDate) invalidProjectionFilter()
            if (endDate == LocalDate.of(9999, 12, 31)) throw CashProjectionListFailure("internal")
            val zoneName = (values["timezone"]?.takeIf { it.isNotEmpty() } ?: "UTC").trim { it.code in pythonWhitespace }
            val zone = when (zoneName) {
                "EST" -> ZoneOffset.ofHours(-5)
                "MST" -> ZoneOffset.ofHours(-7)
                "HST" -> ZoneOffset.ofHours(-10)
                else -> if (zoneName in ZoneId.getAvailableZoneIds()) ZoneId.of(zoneName) else invalidProjectionFilter()
            }
            val account = values["account_id"]?.takeIf { it.isNotEmpty() }?.let(::integer)
            if (account != null && account.signum() <= 0) invalidProjectionFilter()
            val (minimumText, minimum) = decimal(values["amount_min"])
            val (maximumText, maximum) = decimal(values["amount_max"])
            if (minimum != null && maximum != null && minimum > maximum) invalidProjectionFilter()
            var economic = values["economic_type"]?.takeIf { it.isNotEmpty() }
            var subtype = values["transfer_subtype"]?.takeIf { it.isNotEmpty() }?.trim { it.code in pythonWhitespace }
            if (subtype != null && (subtype.isEmpty() || subtype.codePointCount(0, subtype.length) > 32)) invalidProjectionFilter()
            if (economic == "bank_security_transfer") {
                if (subtype != null && subtype != "bank_security_transfer") invalidProjectionFilter()
                economic = "internal_transfer"; subtype = "bank_security_transfer"
            }
            if (economic !in setOf(null, "expense", "income", "internal_transfer")) invalidProjectionFilter()
            if (subtype != null) {
                if (economic == null) economic = "internal_transfer" else if (economic != "internal_transfer") invalidProjectionFilter()
            }
            val composition = values["composition"]?.takeIf { it.isNotEmpty() }
            if (composition !in setOf(null, "single", "payment_mirror", "refund_offset", "combined")) invalidProjectionFilter()
            val category = values["category_id"]?.takeIf { it.isNotEmpty() }?.trim { it.code in pythonWhitespace }
            if (category != null && (category.isEmpty() || category.codePointCount(0, category.length) > 64)) invalidProjectionFilter()
            val uncategorized = values["uncategorized"]?.let { raw -> when (raw.lowercase(Locale.ROOT)) {
                "1", "true", "yes", "y", "on", "t" -> true
                "0", "false", "no", "n", "off", "f" -> false
                else -> throw CashProjectionListBooleanFailure(raw)
            } } ?: false
            if (category != null && uncategorized) invalidProjectionFilter()
            val currency = values["currency"]?.takeIf { it.isNotEmpty() }
            if (currency != null && (currency.codePointCount(0, currency.length) != 3 || !currency.codePoints().allMatch(Character::isLetter))) invalidProjectionFilter()
            val state = values["state"]?.takeIf { it.isNotEmpty() } ?: "all"
            val confirmation = values["confirmation_status"]?.takeIf { it.isNotEmpty() }
            if (state !in setOf("all", "posted", "draft") || confirmation !in setOf(null, "confirmed", "pending")) invalidProjectionFilter()
            val filters = linkedMapOf("date_from" to startDate?.toString(), "date_to" to endDate?.toString(), "account_id" to account?.toString(),
                "counterparty" to values["counterparty"]?.trim { it.code in pythonWhitespace }?.takeIf { it.isNotEmpty() },
                "category_id" to category, "uncategorized" to if (uncategorized) "True" else "False", "currency" to currency?.uppercase(Locale.ROOT),
                "amount_min" to minimumText, "amount_max" to maximumText, "economic_type" to economic, "transfer_subtype" to subtype,
                "composition" to composition, "timezone" to zoneName, "state" to state, "confirmation_status" to confirmation)
            return CashProjectionListInputs(filters, size.toInt(), index, version, account, minimum, maximum, zone,
                startDate?.atStartOfDay(zone)?.toInstant(), endDate?.plusDays(1)?.atStartOfDay(zone)?.toInstant(), values["cursor"])
        }
    }
    fun cursor(workspace: String): Triple<BigInteger, Instant, String>? {
        if (cursor.isNullOrEmpty()) return null
        try {
            val text = String(Base64.getMimeDecoder().decode(cursor.replace('-', '+').replace('_', '/') + "=".repeat((4 - cursor.length % 4) % 4)), Charsets.UTF_8)
            val value = Json.parseToJsonElement(text).jsonObject
            if (value.keys != setOf("v", "workspace", "version", "filters", "sort_at", "projection_id")) invalidProjectionCursor()
            fun integer(name: String): BigInteger {
                val raw = value.getValue(name).jsonPrimitive
                if (raw.isString) invalidProjectionCursor()
                return raw.content.toBigIntegerOrNull() ?: invalidProjectionCursor()
            }
            if (integer("v") != BigInteger.TWO || value["workspace"] != JsonPrimitive(workspace) || value["filters"] != sourceJson(filters)) invalidProjectionCursor()
            val sort = value.getValue("sort_at").jsonPrimitive
            val id = value.getValue("projection_id").jsonPrimitive
            if (!sort.isString || !id.isString) invalidProjectionCursor()
            return Triple(integer("version"), pythonCashTimestamp(sort.content, preserveZSeparator = true), id.content)
        } catch (failure: CashProjectionListFailure) { throw failure }
        catch (_: Exception) { invalidProjectionCursor() }
    }
    fun encodeCursor(workspace: String, version: Long, sort: Instant, id: String): String {
        val value = sourceJson(mapOf("v" to 2, "workspace" to workspace, "version" to version, "filters" to filters,
            "sort_at" to manualInstantText(sort), "projection_id" to id)).toString()
        val ascii = buildString { value.forEach { char -> if (char.code >= 127) append("\\u" + char.code.toString(16).padStart(4, '0')) else append(char) } }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(ascii.toByteArray(Charsets.US_ASCII))
    }
}
