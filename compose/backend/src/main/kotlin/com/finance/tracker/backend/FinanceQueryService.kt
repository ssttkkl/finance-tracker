package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.time.Instant
import kotlinx.serialization.json.*

internal class FinanceQueryFailure(val code: String = "internal") : RuntimeException(code)

internal class FinanceQueryService(private val database: Database, private val now: () -> Instant = Instant::now,
    quoteProvider: QuoteProvider = CompositeQuoteProvider(), private val beforeReturn: () -> Unit = {}) {
    private val reads = ReadServices(database, now)
    private val valuation = ValuationService(quoteProvider, clock = { QuoteTime.from(now()) })
    private fun same(expected: Principal, actual: Principal) {
        if (expected.userId != actual.userId || expected.workspaceId != actual.workspaceId) throw AccessFailure(true)
    }
    fun accounts(token: String?, requestPrincipal: Principal? = null): JsonObject {
        try {
            val principal = reads.currentContext(token); requestPrincipal?.let { same(it, principal) }
            val result = database.read { connection ->
                if (!database.sqlite) connection.createStatement().use { it.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ") }
                val context = CashSqlContext(connection, requireNotNull(principal.workspaceId), database.sqlite, now = now)
                val repository = FinanceQueryRepository(context)
                val app = FinanceQueryApplication(accounts = repository::accounts, snapshot = { context.snapshot.load().value },
                    valuation = valuation)
                app.listAccounts()
            }
            beforeReturn(); same(principal, reads.currentContext(token)); return result
        } catch (failure: AccessFailure) { throw failure }
        catch (failure: StorageFailure) { throw failure }
        catch (failure: FinanceQueryFailure) { throw failure }
        catch (_: IllegalArgumentException) { throw FinanceQueryFailure() }
        catch (_: Exception) { throw FinanceQueryFailure() }
    }
}

internal class FinanceQueryRepository(private val context: CashSqlContext) {
    private val sql get() = context.sql
    private val workspace get() = context.workspace
    fun accounts(): List<FinanceQueryAccount> {
        val a = ACCOUNTS
        val result = sql.select(a.NAME, a.TYPE, a.ACTIVE, a.CURRENCIES).from(a).where(a.WORKSPACE_ID.eq(workspace))
            .orderBy(a.CREATED_AT, a.ID).fetch { row ->
                FinanceQueryAccount(row[a.NAME]!!, row[a.TYPE]!!, row[a.ACTIVE]!!,
                    row[a.CURRENCIES]?.takeIf(::pythonTruthy)?.jsonArray.orEmpty().filter(::pythonTruthy).map { valuationUpper(pythonString(it)) })
            }
        return result
    }
}
