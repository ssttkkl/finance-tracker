package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.*

internal class FinanceQueryFailure(val code: String = "internal") : RuntimeException(code)

internal class FinanceQueryService(private val database: Database, private val now: () -> Instant = Instant::now,
    quoteProvider: QuoteProvider = CompositeQuoteProvider(), private val afterAccountsRead: () -> Unit = {},
    private val afterProjectionStateRead: () -> Unit = {}, private val beforeReturn: () -> Unit = {}) {
    private val reads = ReadServices(database, now)
    private val valuation = ValuationService(quoteProvider, clock = { QuoteTime.from(now()) })
    fun accounts(token: String?, requestPrincipal: Principal? = null): JsonObject = execute(token, requestPrincipal, false, null)
    fun report(token: String?, month: String? = null, requestPrincipal: Principal? = null): JsonObject = execute(token, requestPrincipal, true, month)
    private fun same(expected: Principal, actual: Principal) {
        if (expected.userId != actual.userId || expected.workspaceId != actual.workspaceId) throw AccessFailure(true)
    }
    private fun execute(token: String?, requestPrincipal: Principal?, report: Boolean, month: String?): JsonObject {
        try {
            val principal = reads.currentContext(token); requestPrincipal?.let { same(it, principal) }
            val result = database.read { connection ->
                if (!database.sqlite) connection.createStatement().use { it.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ") }
                val context = CashSqlContext(connection, requireNotNull(principal.workspaceId), database.sqlite, now = now)
                val repository = FinanceQueryRepository(context, afterAccountsRead, afterProjectionStateRead)
                val app = FinanceQueryApplication(accounts = repository::accounts, snapshot = { context.snapshot.load().value },
                    valuation = valuation, cashReporter = repository::report)
                if (report) app.report(month) else app.listAccounts()
            }
            beforeReturn(); same(principal, reads.currentContext(token)); return result
        } catch (failure: AccessFailure) { throw failure }
        catch (failure: StorageFailure) { throw failure }
        catch (failure: FinanceQueryFailure) { throw failure }
        catch (_: IllegalArgumentException) { throw FinanceQueryFailure(if (report) "invalid_filter" else "internal") }
        catch (_: Exception) { throw FinanceQueryFailure() }
    }
}

internal class FinanceQueryRepository(private val context: CashSqlContext, private val afterAccountsRead: () -> Unit = {},
    private val afterStateRead: () -> Unit = {}) {
    private val sql get() = context.sql
    private val workspace get() = context.workspace
    fun accounts(): List<FinanceQueryAccount> {
        val a = ACCOUNTS
        val result = sql.select(a.NAME, a.TYPE, a.ACTIVE, a.CURRENCIES).from(a).where(a.WORKSPACE_ID.eq(workspace))
            .orderBy(a.CREATED_AT, a.ID).fetch { row ->
                FinanceQueryAccount(row[a.NAME]!!, row[a.TYPE]!!, row[a.ACTIVE]!!,
                    row[a.CURRENCIES]?.takeIf(::pythonTruthy)?.jsonArray.orEmpty().filter(::pythonTruthy).map { valuationUpper(pythonString(it)) })
            }
        afterAccountsRead(); return result
    }
    private fun bounds(raw: String?): Pair<Instant, Instant>? {
        if (raw.isNullOrEmpty()) return null
        val parts = raw.split('-')
        if (parts.size != 2 || !parts[1].matches(Regex("(?:[1-9]|0[1-9]|1[0-2])"))) throw FinanceQueryFailure("invalid_filter")
        val digits = parts[0].codePoints().toArray()
        if (digits.size != 4) throw FinanceQueryFailure("invalid_filter")
        val year = digits.fold(0) { value, code ->
            val zero = pythonIntegerDigitStarts.firstOrNull { code in it until it + 10 } ?: throw FinanceQueryFailure("invalid_filter")
            value * 10 + code - zero
        }
        if (year !in 1..9999) throw FinanceQueryFailure("invalid_filter")
        val start = LocalDate.of(year, parts[1].toInt(), 1); val end = start.plusMonths(1)
        if (end.year > 9999) throw FinanceQueryFailure("invalid_filter")
        return start.atStartOfDay().toInstant(ZoneOffset.UTC) to end.atStartOfDay().toInstant(ZoneOffset.UTC)
    }
    fun report(month: String?): JsonObject {
        val bounds = bounds(month)
        val state = CASH_PROJECTION_STATES
        val row = sql.select(state.ACTIVE_DATASET_ID, state.AVAILABILITY).from(state).where(state.WORKSPACE_ID.eq(workspace)).fetchOne()
            ?: throw FinanceQueryFailure("projection.unavailable")
        val dataset = row[state.ACTIVE_DATASET_ID]?.takeIf { it.isNotEmpty() }
        if (row[state.AVAILABILITY] != "ready" || dataset == null) throw FinanceQueryFailure("projection.unavailable")
        afterStateRead()
        val p = CASH_PROJECTIONS.`as`("p")
        val renderer = CurrentProjectionReadRepository(context.connection, workspace, context.sqlite) {}
        val base = p.WORKSPACE_ID.eq(workspace).and(p.DATASET_ID.eq(dataset)).and(p.STATE.eq("posted")).and(renderer.listActiveSources())
        var totals = base.and(p.STATISTICS_ELIGIBLE.isTrue)
        bounds?.let { (start, end) -> totals = totals.and(p.OCCURRED_AT.ge(start)).and(p.OCCURRED_AT.lt(end)) }
        val income = linkedMapOf<String, FinanceDecimal>(); val expenses = linkedMapOf<String, FinanceDecimal>()
        sql.select(p.CURRENCY, p.ECONOMIC_TYPE, p.NET_AMOUNT).from(p).where(totals).fetchLazy().use { cursor ->
            for (item in cursor) {
                val kind = item[p.ECONOMIC_TYPE]
                if (kind !in setOf("income", "expense")) continue
                val currency = requireNotNull(item[p.CURRENCY]); val amount = FinanceDecimal.parse(requireNotNull(item[p.NET_AMOUNT]).text).abs()
                val target = if (kind == "income") income else expenses
                target[currency] = (target[currency] ?: FinanceDecimal.ZERO).add(amount, 80)
            }
        }
        val member = CASH_PROJECTION_MEMBERS; val cash = CASH_TRANSACTIONS.`as`("cash")
        val flows = linkedMapOf<Pair<String, String>, FinanceDecimal>()
        sql.select(cash.NOTE, cash.CURRENCY, cash.AMOUNT).from(p).join(member)
            .on(member.WORKSPACE_ID.eq(p.WORKSPACE_ID)).and(member.DATASET_ID.eq(p.DATASET_ID)).and(member.PROJECTION_ROW_ID.eq(p.ID))
            .join(cash).on(cash.WORKSPACE_ID.eq(member.WORKSPACE_ID)).and(cash.ID.eq(member.CASH_TRANSACTION_ID))
            .where(base).and(cash.STATE.eq("posted")).and(cash.DELETED_AT.isNull).and(cash.RECORD_TYPE.eq("transfer_out"))
            .fetchLazy().use { cursor ->
                for (item in cursor) {
                    val key = requireNotNull(item[cash.NOTE]) to requireNotNull(item[cash.CURRENCY])
                    flows[key] = (flows[key] ?: FinanceDecimal.ZERO).add(FinanceDecimal.parse(requireNotNull(item[cash.AMOUNT]).text).abs(), 80)
                }
            }
        val ordered = flows.entries.sortedWith { a, b ->
            b.value.compareTo(a.value).takeIf { it != 0 } ?: pythonStringOrder(a.key.first, b.key.first).takeIf { it != 0 }
                ?: pythonStringOrder(a.key.second, b.key.second)
        }.take(10)
        fun money(values: Map<String, FinanceDecimal>) = buildJsonObject { values.forEach { (currency, value) -> put(currency, value.fixed()) } }
        return buildJsonObject {
            put("income", money(income)); put("expenses", money(expenses))
            put("flows", JsonArray(ordered.map { (key, value) -> buildJsonObject { put("note", key.first); put("currency", key.second); put("amount", value.fixed()) } }))
        }
    }
}
