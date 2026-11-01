package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.math.BigInteger
import java.sql.Connection
import java.time.*
import kotlinx.serialization.json.*
import org.jooq.impl.DSL

internal data class LegacyTransactionFilters(val month: String? = null, val account: String? = null,
    val categoryId: String? = null, val limit: Int = 30)

internal data class LegacyTransactionRow(val occurredAt: String? = "", val accountName: String? = "",
    val currency: String? = "CNY", val amount: CashStoredDecimal? = null, val categoryId: String? = null,
    val recordType: String? = "other", val recordSubtype: String? = "not_applicable",
    val note: String? = "", val counterparty: String? = "")

internal fun interface LegacyTransactionReader {
    fun list(filters: LegacyTransactionFilters): List<LegacyTransactionRow>
}

internal class LegacyTransactionQueryApplication(private val reader: LegacyTransactionReader) {
    fun list(filters: LegacyTransactionFilters = LegacyTransactionFilters()): JsonObject = buildJsonObject {
        put("items", JsonArray(reader.list(filters).map { row ->
            sourceJson(mapOf("occurred_at" to row.occurredAt, "account_name" to row.accountName,
                "currency" to row.currency.takeUnless { it.isNullOrEmpty() }.let { it ?: "CNY" },
                "amount" to (row.amount?.takeIf { it.text.toBigDecimalOrNull()?.signum()?.let { sign -> sign != 0 } == true }
                    ?.let(::fixedCashDecimal) ?: "0"), "category_id" to row.categoryId,
                "record_type" to row.recordType, "record_subtype" to row.recordSubtype, "note" to row.note,
                "counterparty" to row.counterparty, "transfer_account" to ""))
        }))
    }
}

internal class LegacyTransactionQueryFailure(val range: Boolean = false, val internal: Boolean = false) : RuntimeException("invalid_filter")

internal class LegacyTransactionQueryService(private val database: Database, now: () -> Instant = Instant::now,
    private val afterPageRead: () -> Unit = {}, private val beforeReturn: () -> Unit = {}) {
    private val reads = ReadServices(database, now)
    private fun size(raw: String?): Int {
        if (raw == null) return 30
        val text = raw.trim { it.code in pythonWhitespace && it.code !in 0x1c..0x1f }
        if (!Regex("[+-]?[0-9](?:_?[0-9])*+(?:\\.0++)?").matches(text)) throw LegacyCashSearchLimitFailure(raw)
        val digits = text.substringBefore('.').replace("_", "").removePrefix("+").removePrefix("-").trimStart('0')
        if (digits.length > 4300) throw LegacyCashSearchLimitFailure(raw, oversized = true)
        val value = text.substringBefore('.').replace("_", "").toBigInteger()
        if (value < BigInteger.ONE || value > BigInteger.valueOf(200)) throw LegacyTransactionQueryFailure(range = true)
        return value.toInt()
    }
    private fun month(raw: String?): Pair<Instant, Instant>? {
        if (raw.isNullOrEmpty()) return null
        val parts = raw.split('-')
        if (parts.size != 2 || !parts[1].matches(Regex("(?:[1-9]|0[1-9]|1[0-2])"))) throw LegacyTransactionQueryFailure()
        val yearDigits = parts[0].codePoints().toArray()
        if (yearDigits.size != 4) throw LegacyTransactionQueryFailure()
        val year = yearDigits.fold(0) { value, point ->
            val zero = pythonIntegerDigitStarts.firstOrNull { point in it until it + 10 } ?: throw LegacyTransactionQueryFailure()
            value * 10 + point - zero
        }
        if (year !in 1..9999) throw LegacyTransactionQueryFailure()
        val start = LocalDate.of(year, parts[1].toInt(), 1)
        val end = start.plusMonths(1)
        if (end.year > 9999) throw LegacyTransactionQueryFailure()
        return start.atStartOfDay().toInstant(ZoneOffset.UTC) to end.atStartOfDay().toInstant(ZoneOffset.UTC)
    }
    fun list(token: String?, values: Map<String, String> = emptyMap(), requestPrincipal: Principal? = null): JsonObject {
        try {
            val principal = reads.currentContext(token)
            requestPrincipal?.let { same(it, principal) }
            val filters = LegacyTransactionFilters(values["month"], values["account"], values["category_id"], size(values["limit"]))
            val bounds = month(filters.month)
            val result = database.read { connection ->
                if (!database.sqlite) connection.createStatement().use { it.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ") }
                LegacyTransactionQueryApplication { read(connection, requireNotNull(principal.workspaceId), it, bounds) }.list(filters)
            }
            beforeReturn()
            same(principal, reads.currentContext(token))
            return result
        } catch (failure: AccessFailure) { throw failure }
        catch (failure: LegacyTransactionQueryFailure) { throw failure }
        catch (failure: LegacyCashSearchLimitFailure) { throw failure }
        catch (failure: StorageFailure) { throw failure }
        catch (_: Exception) { throw LegacyTransactionQueryFailure(internal = true) }
    }
    private fun read(connection: Connection, workspace: String, filters: LegacyTransactionFilters,
        bounds: Pair<Instant, Instant>?): List<LegacyTransactionRow> {
        val sql = connection.jooq(database.sqlite)
        val t = CASH_TRANSACTIONS; val a = ACCOUNTS; val p = CASH_TRANSACTION_COMPONENTS
        var condition = t.WORKSPACE_ID.eq(workspace).and(t.STATE.eq("posted")).and(t.DELETED_AT.isNull)
        bounds?.let { (start, end) -> condition = condition.and(t.OCCURRED_AT.ge(start)).and(t.OCCURRED_AT.lt(end)) }
        filters.account?.takeIf { it.isNotEmpty() }?.let { account ->
            val partAccount = ACCOUNTS.`as`("part_account")
            condition = condition.and(a.NAME.eq(account).or(DSL.exists(sql.select(p.ID).from(p).join(partAccount)
                .on(partAccount.WORKSPACE_ID.eq(p.WORKSPACE_ID)).and(partAccount.ID.eq(p.ACCOUNT_ID))
                .where(p.WORKSPACE_ID.eq(t.WORKSPACE_ID)).and(p.CASH_TRANSACTION_ID.eq(t.ID)).and(partAccount.NAME.eq(account)))))
        }
        filters.categoryId?.takeIf { it.isNotEmpty() }?.let { condition = condition.and(t.CATEGORY_ID.eq(it)) }
        val parentName = a.NAME.`as`("parent_name")
        val page = sql.select(t.asterisk(), parentName).from(t).leftJoin(a)
            .on(a.WORKSPACE_ID.eq(t.WORKSPACE_ID)).and(a.ID.eq(t.ACCOUNT_ID)).where(condition)
            .orderBy(t.OCCURRED_AT.desc(), t.ID.desc()).limit(filters.limit).fetch()
        afterPageRead()
        val parts = if (page.isEmpty()) emptyMap() else sql.select(p.CASH_TRANSACTION_ID, a.NAME).from(p).join(a)
            .on(a.WORKSPACE_ID.eq(p.WORKSPACE_ID)).and(a.ID.eq(p.ACCOUNT_ID))
            .where(p.WORKSPACE_ID.eq(workspace)).and(p.CASH_TRANSACTION_ID.`in`(page.map { it[t.ID] }))
            .fetch().groupBy { it[p.CASH_TRANSACTION_ID] }
        return page.map { row ->
            val fallback = parts[row[t.ID]].orEmpty().singleOrNull()?.get(a.NAME)
            LegacyTransactionRow(row[t.OCCURRED_AT]?.let(::manualInstantText), row[parentName] ?: fallback ?: "",
                row[t.CURRENCY], row[t.AMOUNT], row[t.CATEGORY_ID], row[t.RECORD_TYPE], row[t.RECORD_SUBTYPE],
                row[t.NOTE], row[t.COUNTERPARTY])
        }
    }
    private fun same(expected: Principal, actual: Principal) {
        if (expected.userId != actual.userId || expected.workspaceId != actual.workspaceId) throw AccessFailure(true)
    }
}
