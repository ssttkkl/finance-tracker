package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.associationMagnitude
import com.finance.tracker.backend.persistence.generated.tables.references.CASH_TRANSACTIONS
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*

internal class CashAssociationLimitFailure(val input: String) : RuntimeException("association_limit_invalid")

class CashAssociationSearchService(private val database: Database, private val now: () -> Instant = Instant::now,
    private val beforeReturn: () -> Unit = {}) {
    private val reads = ReadServices(database, now)
    private data class Filters(val query: String, val account: Long?, val value: BigDecimal?, val start: Instant?, val end: Instant?, val after: Long, val limit: Int)
    private fun invalid(): Nothing = throw CashRecordDetailFailure("record_input_invalid")
    private fun identifier(value: String?, code: String = "record_input_invalid"): Long {
        if (value == null || value.length !in 1..19 || value.any { it !in '0'..'9' }) throw CashRecordDetailFailure(code)
        return value.toLongOrNull()?.takeIf { it > 0 } ?: throw CashRecordDetailFailure(code)
    }
    private fun limit(raw: String?): BigInteger {
        if (raw == null) return BigInteger.valueOf(20)
        val text = raw.trim { it.code in pythonWhitespace && it.code !in 0x1c..0x1f }
        if (!Regex("[+-]?[0-9](?:_?[0-9])*(?:\\.0+)?").matches(text)) throw CashAssociationLimitFailure(raw)
        return text.substringBefore('.').replace("_", "").toBigIntegerOrNull() ?: throw CashAssociationLimitFailure(raw)
    }
    private fun magnitude(raw: String): BigDecimal {
        val parsed = CashAmount.parseOrNull(raw) ?: invalid()
        val representation = parsed.representation
        var exponent = BigInteger.valueOf(representation.exponent)
        var length = representation.coefficient.length
        while (exponent.signum() < 0 && length > 0 && representation.coefficient[length - 1] == '0') { length--; exponent += BigInteger.ONE }
        val scale = exponent.negate().max(BigInteger.ZERO)
        val integer = if (representation.coefficient.all { it == '0' }) BigInteger.ZERO else (exponent + BigInteger.valueOf(length.toLong())).max(BigInteger.ZERO)
        if (scale > BigInteger.valueOf(18) || integer > BigInteger.valueOf(20) || integer + scale > BigInteger.valueOf(38)) invalid()
        return if (representation.coefficient.all { it == '0' }) BigDecimal.ZERO else BigDecimal(parsed.toString()).abs()
    }
    private fun validTime(value: String, offset: Boolean = false): Boolean {
        if (!Regex("(?:[0-9]{2}(?::[0-9]{2}(?::[0-9]{2})?)?|[0-9]{4}|[0-9]{6})(?:[.,][0-9]+)?").matches(value)) return false
        val digits = value.substringBefore('.').substringBefore(',').replace(":", "")
        val hour = digits.take(2).toInt(); val minute = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
        val second = if (digits.length == 6) digits.substring(4, 6).toInt() else 0
        return if (offset) hour * 3600 + minute * 60 + second < 86400 else hour <= 23 && minute <= 59 && second <= 59
    }
    private fun date(raw: String): LocalDate {
        val matched = Regex("^(?:[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{8}|[0-9]{4}-W[0-9]{2}(?:-[0-9])?|[0-9]{4}W[0-9]{2}[0-9]?)").find(raw) ?: invalid()
        val value = matched.value
        val result = try {
            if ('W' in value) {
                val week = value.replace("-", ""); val day = if (week.length == 8) week.last() else '1'
                LocalDate.parse("${week.take(4)}-W${week.substring(5, 7)}-$day", DateTimeFormatter.ISO_WEEK_DATE)
            } else LocalDate.parse(value, if (value.length == 8) DateTimeFormatter.BASIC_ISO_DATE else DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: java.time.DateTimeException) { invalid() }
        if (result.year !in 1..9999) invalid()
        var rest = raw.substring(value.length)
        if (rest.isNotEmpty()) {
            rest = rest.substring(rest.offsetByCodePoints(0, 1))
            val zone = rest.indexOfFirst { it == '+' || it == '-' || it == 'Z' }
            val clock = if (zone < 0) rest else rest.substring(0, zone)
            if (!validTime(clock)) invalid()
            if (zone >= 0) {
                val suffix = rest.substring(zone)
                if (suffix != "Z" && (suffix.first() !in "+-" || !validTime(suffix.drop(1), offset = true))) invalid()
            }
        }
        return result
    }
    private fun filters(values: Map<String, String>, pageSize: BigInteger): Filters {
        if (pageSize < BigInteger.ONE || pageSize > BigInteger.valueOf(100)) invalid()
        val query = values["query"] ?: ""
        if (query.codePointCount(0, query.length) > 256) invalid()
        val zoneName = values["timezone_name"] ?: "UTC"
        val zone = when (zoneName) {
            "EST" -> ZoneOffset.ofHours(-5)
            "MST" -> ZoneOffset.ofHours(-7)
            "HST" -> ZoneOffset.ofHours(-10)
            else -> if (zoneName in ZoneId.getAvailableZoneIds()) ZoneId.of(zoneName) else invalid()
        }
        val start = values["date_from"]?.takeIf { it.isNotEmpty() }?.let(::date)?.atStartOfDay(zone)?.toInstant()
        val end = values["date_to"]?.takeIf { it.isNotEmpty() }?.let(::date)?.let {
            if (it == LocalDate.of(9999, 12, 31)) throw CashRecordReadUnavailable(ArithmeticException("date bound overflow"))
            it.plusDays(1).atStartOfDay(zone).toInstant()
        }
        if (start != null && end != null && start >= end) invalid()
        return Filters(query.trim { it.code in pythonWhitespace }, values["account_id"]?.let { identifier(it) },
            values["amount"]?.let(::magnitude), start, end, values["cursor"]?.let { identifier(it) } ?: 0L, pageSize.toInt())
    }
    fun list(token: String?, sourceId: String?, values: Map<String, String> = emptyMap(), requestPrincipal: Principal? = null): JsonObject {
        val principal = reads.currentContext(token)
        if (requestPrincipal != null) same(principal, requestPrincipal)
        val pageSize = limit(values["limit"])
        val identifier = identifier(sourceId, "record_not_found")
        val filters = filters(values, pageSize)
        try {
            val result = database.read { connection ->
                if (!database.sqlite) connection.createStatement().use { it.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ") }
                val context = CashSqlContext(connection, principal.workspaceId!!, database.sqlite, now)
                val records = CashRecordDetailRepository(context, principal)
                records.requireAssociationAnchor(identifier)
                val excluded = CashRelationReadRepository(context).confirmedClosure(setOf(identifier))
                val table = CASH_TRANSACTIONS
                var condition = table.WORKSPACE_ID.eq(context.workspace).and(table.DELETED_AT.isNull)
                    .and(table.RECORD_ORIGIN.`in`("import", "legacy")).and(table.ID.gt(filters.after)).and(table.ID.notIn(excluded))
                if (filters.query.isNotEmpty()) {
                    val pattern = "%" + filters.query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
                    condition = condition.and(table.COUNTERPARTY.like(pattern, '\\').or(table.NOTE.like(pattern, '\\')).or(table.RECORD_ID.like(pattern, '\\')))
                }
                filters.account?.let { condition = condition.and(table.ACCOUNT_ID.eq(it)) }
                filters.value?.let { condition = condition.and(associationMagnitude(database.sqlite, table.AMOUNT, it)) }
                filters.start?.let { condition = condition.and(table.OCCURRED_AT.ge(it)) }
                filters.end?.let { condition = condition.and(table.OCCURRED_AT.lt(it)) }
                val identifiers = context.sql.select(table.ID).from(table).where(condition).orderBy(table.ID).limit(filters.limit + 1).fetch(table.ID)
                buildJsonObject {
                    put("items", JsonArray(identifiers.take(filters.limit).map { records.associationCandidate(requireNotNull(it)) }))
                    put("next_cursor", if (identifiers.size > filters.limit) JsonPrimitive(identifiers[filters.limit - 1].toString()) else JsonNull)
                }
            }
            beforeReturn()
            same(principal, reads.currentContext(token))
            return result
        } catch (failure: AccessFailure) { throw failure }
        catch (failure: CashRecordDetailFailure) { throw failure }
        catch (failure: MutationPlanFieldFailure) { throw failure }
        catch (failure: CashRecordReadUnavailable) { throw failure }
        catch (failure: Exception) { throw CashRecordReadUnavailable(failure) }
    }
    private fun same(expected: Principal, actual: Principal) {
        if (expected.userId != actual.userId || expected.workspaceId != actual.workspaceId) throw AccessFailure(true)
    }
}
