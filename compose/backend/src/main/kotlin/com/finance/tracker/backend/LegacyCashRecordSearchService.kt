package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlinx.serialization.json.*
import org.jooq.impl.DSL

internal class LegacyCashSearchFailure(val code: String) : RuntimeException(code)
internal class LegacyCashSearchLimitFailure(val input: String, val oversized: Boolean = false) : RuntimeException("limit_invalid")

private fun legacySearchJson(value: Any?): JsonElement = when (value) {
    is JsonElement -> value
    is List<*> -> JsonArray(value.map(::legacySearchJson))
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to legacySearchJson(it.value) })
    else -> sourceJson(value)
}

class LegacyCashRecordSearchService(private val database: Database, private val now: () -> Instant = Instant::now,
    private val afterPageRead: () -> Unit = {}, private val beforeReturn: () -> Unit = {}) {
    private val reads = ReadServices(database, now)
    private fun invalid(code: String = "invalid_filter"): Nothing = throw LegacyCashSearchFailure(code)
    private fun limit(raw: String?): Int {
        if (raw == null) return 20
        val text = raw.trim { it.code in pythonWhitespace && it.code !in 0x1c..0x1f }
        if (!Regex("[+-]?[0-9](?:_?[0-9])*(?:\\.0+)?").matches(text)) throw LegacyCashSearchLimitFailure(raw)
        val digits = text.substringBefore('.').replace("_", "").removePrefix("+").removePrefix("-").trimStart('0')
        if (digits.length > 4300) throw LegacyCashSearchLimitFailure(raw, oversized = true)
        return text.substringBefore('.').replace("_", "").toBigInteger().max(BigInteger.ONE).min(BigInteger.valueOf(20)).toInt()
    }
    private fun date(raw: String): LocalDate = try {
        when {
            raw.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) -> LocalDate.parse(raw)
            raw.matches(Regex("[0-9]{8}")) -> LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE)
            raw.matches(Regex("[0-9]{4}(?:-W[0-9]{2}(?:-[0-9])?|W[0-9]{2}[0-9]?)")) -> {
                val compact = raw.replace("-", "")
                LocalDate.parse(compact.take(4) + "-W" + compact.substring(5, 7) + "-" + if (compact.length == 8) compact.last() else '1', DateTimeFormatter.ISO_WEEK_DATE)
            }
            else -> invalid()
        }.also { if (it.year !in 1..9999) invalid() }
    } catch (_: DateTimeException) { invalid() }
    private fun integer(value: JsonElement, code: String): BigInteger {
        if (value !is JsonPrimitive || value == JsonNull) invalid(code)
        return try { pythonIntegerValue(value) } catch (_: CategoryFailure) { invalid(code) }
    }
    private fun cursor(raw: String?): Pair<Instant, BigInteger>? {
        if (raw.isNullOrEmpty()) return null
        try {
            if (raw.any { it.code > 127 }) invalid("invalid_cursor")
            val bytes = Base64.getMimeDecoder().decode(raw.replace('-', '+').replace('_', '/') + "=".repeat((4 - raw.length % 4) % 4))
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val body = categoryDeleteBody(text) as? JsonObject ?: invalid("invalid_cursor")
            val at = body["occurred_at"] ?: invalid("invalid_cursor")
            val id = body["id"] ?: invalid("invalid_cursor")
            return pythonCashTimestamp(pythonString(at), allowNaive = true) to integer(id, "invalid_cursor")
        } catch (_: IllegalArgumentException) { invalid("invalid_cursor") }
        catch (_: java.nio.charset.CharacterCodingException) { invalid("invalid_cursor") }
        catch (_: CategoryFailure) { invalid("invalid_cursor") }
        catch (_: InvalidCashValue) { invalid("invalid_cursor") }
    }
    private fun bound(date: LocalDate?, zone: ZoneId, end: Boolean = false): Instant? = date?.let {
        if (end && it == LocalDate.of(9999, 12, 31)) throw LegacyCashSearchFailure("internal")
        (if (end) it.plusDays(1) else it).atStartOfDay(zone).toInstant().also { instant ->
            if (instant.atOffset(ZoneOffset.UTC).year !in 1..9999) throw LegacyCashSearchFailure("internal")
        }
    }
    private fun long(value: BigInteger): Long {
        if (value < BigInteger.valueOf(Long.MIN_VALUE) || value > BigInteger.valueOf(Long.MAX_VALUE)) {
            if (database.sqlite) throw LegacyCashSearchFailure("internal")
            throw StorageFailure("storage.connect")
        }
        return value.toLong()
    }
    fun list(token: String?, values: Map<String, String> = emptyMap(), requestPrincipal: Principal? = null): JsonObject {
        try {
            val principal = reads.currentContext(token)
            if (requestPrincipal != null) same(principal, requestPrincipal)
            val size = limit(values["limit"])
            val before = cursor(values["cursor"])
            val from = values["date_from"]?.takeIf { it.isNotEmpty() }?.let(::date)
            val to = values["date_to"]?.takeIf { it.isNotEmpty() }?.let(::date)
            if (from != null && to != null && from > to) invalid()
            val zoneName = values["timezone"]?.takeIf { it.isNotEmpty() } ?: "UTC"
            val zone = when (zoneName) {
                "EST" -> ZoneOffset.ofHours(-5)
                "MST" -> ZoneOffset.ofHours(-7)
                "HST" -> ZoneOffset.ofHours(-10)
                else -> if (zoneName in ZoneId.getAvailableZoneIds()) ZoneId.of(zoneName) else invalid()
            }
            val excluded = values["exclude_id"]?.takeIf { it.isNotEmpty() }?.let { integer(JsonPrimitive(it), "invalid_filter") }
            val result = database.read { connection ->
                if (!database.sqlite) connection.createStatement().use { it.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ") }
                val sql = connection.jooq(database.sqlite)
                val t = CASH_TRANSACTIONS; val a = ACCOUNTS
                var condition = t.WORKSPACE_ID.eq(principal.workspaceId).and(t.DELETED_AT.isNull)
                excluded?.let { condition = condition.and(t.ID.ne(long(it))) }
                bound(from, zone)?.let { condition = condition.and(t.OCCURRED_AT.ge(it)) }
                bound(to, zone, end = true)?.let { condition = condition.and(t.OCCURRED_AT.lt(it)) }
                val term = values["query"].orEmpty().trim { it.code in pythonWhitespace }
                if (term.isNotEmpty()) {
                    val pattern = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
                    condition = condition.and(t.COUNTERPARTY.likeIgnoreCase(pattern, '\\').or(t.NOTE.likeIgnoreCase(pattern, '\\'))
                        .or(t.CURRENCY.likeIgnoreCase(pattern, '\\')).or(a.NAME.likeIgnoreCase(pattern, '\\'))
                        .or(t.AMOUNT.cast(String::class.java).likeIgnoreCase(pattern, '\\')))
                }
                before?.let { (at, id) -> condition = condition.and(t.OCCURRED_AT.lt(at).or(t.OCCURRED_AT.eq(at).and(t.ID.lt(long(id))))) }
                val name = a.NAME.`as`("parent_name"); val type = a.TYPE.`as`("parent_type")
                val rows = sql.select(t.asterisk(), name, type).from(t).leftJoin(a).on(a.WORKSPACE_ID.eq(t.WORKSPACE_ID)).and(a.ID.eq(t.ACCOUNT_ID))
                    .where(condition).orderBy(t.OCCURRED_AT.desc(), t.ID.desc()).limit(size + 1).fetch()
                afterPageRead()
                val page = rows.take(size)
                val p = CASH_TRANSACTION_COMPONENTS
                val partName = a.NAME.`as`("part_name"); val partType = a.TYPE.`as`("part_type")
                val parts = if (page.isEmpty()) emptyMap() else sql.select(p.asterisk(), partName, partType).from(p).leftJoin(a)
                    .on(a.WORKSPACE_ID.eq(p.WORKSPACE_ID)).and(a.ID.eq(p.ACCOUNT_ID))
                    .where(p.WORKSPACE_ID.eq(principal.workspaceId)).and(p.CASH_TRANSACTION_ID.`in`(page.map { it[t.ID] }))
                    .orderBy(p.ORDINAL, p.ID).fetch().groupBy { it[p.CASH_TRANSACTION_ID] }
                val leaf = CASH_CATEGORIES.`as`("leaf"); val ancestor = CASH_CATEGORIES.`as`("ancestor")
                val categoryIds = page.mapNotNull { it[t.CATEGORY_ID]?.takeIf(String::isNotEmpty) }.toSet()
                val categories = if (categoryIds.isEmpty()) emptyMap() else sql.select(leaf.ID, ancestor.ID, ancestor.NAME).from(leaf).join(ancestor)
                    .on(ancestor.WORKSPACE_ID.eq(leaf.WORKSPACE_ID)).and(leaf.CATEGORY_PATH.like(DSL.concat(ancestor.CATEGORY_PATH, DSL.inline("%"))))
                    .where(leaf.WORKSPACE_ID.eq(principal.workspaceId)).and(leaf.ID.`in`(categoryIds)).orderBy(leaf.ID, ancestor.DEPTH).fetch()
                    .groupBy { it[leaf.ID] }.mapValues { (id, path) -> sourceJson(mapOf("id" to id, "name" to path.last()[ancestor.NAME],
                        "path" to path.map { mapOf("id" to it[ancestor.ID], "name" to it[ancestor.NAME]) })) }
                val items = page.map { row ->
                    val components = parts[row[t.ID]].orEmpty()
                    val accountParts = components.filter { it[partName] != null }
                    val fallback = if (row[name] == null && row[t.STATE] != "draft" && accountParts.size == 1) accountParts.single() else null
                    val noAccount = if (row[t.STATE] == "draft") null else ""
                    val fields = linkedMapOf<String, Any?>("id" to row[t.ID], "occurred_at" to row[t.OCCURRED_AT]?.let(::manualInstantText),
                        "amount" to row[t.AMOUNT]?.let(::fixedCashDecimal), "currency" to row[t.CURRENCY], "counterparty" to row[t.COUNTERPARTY],
                        "counterparty_account" to row[t.COUNTERPARTY_ACCOUNT], "note" to row[t.NOTE], "category_id" to row[t.CATEGORY_ID],
                        "record_type" to row[t.RECORD_TYPE], "record_subtype" to row[t.RECORD_SUBTYPE], "source_type" to row[t.SOURCE_TYPE].orEmpty(),
                        "account_name" to (row[name] ?: fallback?.get(partName) ?: noAccount), "account_type" to (row[type] ?: fallback?.get(partType) ?: noAccount),
                        "cash_granularity" to row[t.CASH_GRANULARITY], "components" to components.map { part ->
                            mapOf("id" to part[p.ID], "cash_transaction_id" to part[p.CASH_TRANSACTION_ID], "account_id" to part[p.ACCOUNT_ID],
                                "account_name" to part[partName], "account_type" to part[partType], "amount" to part[p.AMOUNT]?.let(::fixedCashDecimal),
                                "currency" to part[p.CURRENCY], "ordinal" to part[p.ORDINAL], "label" to part[p.LABEL], "source_key" to part[p.SOURCE_KEY],
                                "metadata" to part[p.METADATA_JSON].takeIf(::pythonTruthy).let { it ?: JsonObject(emptyMap()) })
                        })
                    row[t.CATEGORY_ID]?.takeIf(String::isNotEmpty)?.let { fields["category"] = categories[it] }
                    legacySearchJson(fields)
                }
                val next = if (rows.size <= size) null else {
                    val row = page.last(); val at = row[t.OCCURRED_AT] ?: invalid()
                    Base64.getUrlEncoder().withoutPadding().encodeToString(sourceJson(mapOf("id" to row[t.ID], "occurred_at" to manualInstantText(at))).toString().toByteArray(Charsets.UTF_8))
                }
                buildJsonObject { put("items", JsonArray(items)); put("next_cursor", next?.let(::JsonPrimitive) ?: JsonNull) }
            }
            beforeReturn(); same(principal, reads.currentContext(token))
            return result
        } catch (failure: AccessFailure) { throw failure }
        catch (failure: LegacyCashSearchFailure) { throw failure }
        catch (failure: LegacyCashSearchLimitFailure) { throw failure }
        catch (failure: StorageFailure) { throw failure }
        catch (_: Exception) { throw LegacyCashSearchFailure("internal") }
    }
    private fun same(expected: Principal, actual: Principal) {
        if (expected.userId != actual.userId || expected.workspaceId != actual.workspaceId) throw AccessFailure(true)
    }
}
