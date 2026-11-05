package com.finance.tracker.backend

import java.math.BigInteger
import java.time.Instant
import kotlinx.serialization.json.*

internal interface PortfolioRead {
    fun holdings(): JsonObject
    fun portfolio(scope: PortfolioRefreshScope, publish: ((JsonObject) -> Unit)? = null): JsonObject
}
internal class PortfolioHttpFailure(val code: String = "invalid_filter") : RuntimeException(code)

internal class PortfolioHttpService(private val database: Database, private val now: () -> Instant = Instant::now,
    private val quotes: QuoteProvider = CompositeQuoteProvider(), private val fx: FxRateProvider = FxRateProvider(),
    private val factory: ((String) -> PortfolioRead)? = null,
    private val coordinatorFactory: (PortfolioRefreshQuery) -> PortfolioRefreshCoordinator = { PortfolioRefreshCoordinator(it) },
    private val beforeReturn: () -> Unit = {}, private val beforeRefresh: (PortfolioRefreshScope) -> Unit = {}) : AutoCloseable {
    private val reads = ReadServices(database, now)
    private val lifecycle = Any()
    private val coordinators = linkedMapOf<String, PortfolioRefreshCoordinator>()
    private val subscriptions = mutableSetOf<PortfolioHttpSubscription>()
    private var closed = false
    private fun service(workspace: String): PortfolioRead = factory?.invoke(workspace) ?: run {
        val repository = object : PortfolioRepository {
            private fun load(holdings: Boolean) = database.read { connection ->
                if (!database.sqlite) connection.createStatement().use { it.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ") }
                val repository = RelationalPortfolioRepository(CashSqlContext(connection, workspace, database.sqlite, now = now))
                if (holdings) repository.loadHoldings() else repository.loadPortfolio()
            }
            override fun loadPortfolio() = load(false)
            override fun loadHoldings() = load(true)
        }
        val query = PortfolioQueryService(repository, ValuationService(quotes, clock = { QuoteTime.from(now()) }), fx, clock = { QuoteTime.from(now()) })
        object : PortfolioRead {
            override fun holdings() = query.getHoldings()
            override fun portfolio(scope: PortfolioRefreshScope, publish: ((JsonObject) -> Unit)?) = query.getPortfolio(scope.displayCurrency, scope.period, scope.timezone, publish)
        }
    }
    internal fun verify(token: String?, expected: Principal, write: Boolean = false): Principal {
        val actual = reads.currentContext(token)
        if (actual.userId != expected.userId || actual.workspaceId != expected.workspaceId || write && actual.role?.canWrite != true) throw AccessFailure(true)
        return actual
    }
    fun get(token: String?, principal: Principal, scope: PortfolioRefreshScope, phase: String): JsonObject {
        verify(token, principal)
        if (phase !in setOf("holdings", "valuation")) throw PortfolioHttpFailure()
        val query = service(requireNotNull(principal.workspaceId))
        val result = try { if (phase == "holdings") query.holdings() else query.portfolio(scope) }
        catch (failure: ValuationError) { throw PortfolioHttpFailure(failure.code) }
        catch (_: IllegalArgumentException) { throw PortfolioHttpFailure() }
        beforeReturn(); verify(token, principal); return result
    }
    private fun coordinator(workspace: String): PortfolioRefreshCoordinator {
        check(!closed) { "portfolio runtime closed" }
        return coordinators.getOrPut(workspace) {
            val query = service(workspace)
            coordinatorFactory(PortfolioRefreshQuery { scope, publish -> query.portfolio(scope, publish) })
        }
    }
    fun request(token: String?, principal: Principal, scope: PortfolioRefreshScope) {
        verify(token, principal, write = true)
        beforeRefresh(scope)
        synchronized(lifecycle) { coordinator(requireNotNull(principal.workspaceId)).requestRefresh(scope.displayCurrency, scope.period, scope.timezone) }
    }
    fun subscribe(token: String?, principal: Principal, scope: PortfolioRefreshScope, lastVersion: BigInteger?): PortfolioHttpSubscription {
        verify(token, principal)
        return synchronized(lifecycle) {
            val stream = coordinator(requireNotNull(principal.workspaceId)).subscribe(scope.displayCurrency, scope.period, scope.timezone, lastVersion)
            PortfolioHttpSubscription(stream) { subscription -> synchronized(lifecycle) { subscriptions.remove(subscription) } }.also(subscriptions::add)
        }
    }
    internal fun activeSubscriptions() = synchronized(lifecycle) { subscriptions.size }
    internal fun activeWorkers() = synchronized(lifecycle) { coordinators.values.count { it.workerAlive() } }
    override fun close() {
        val owned = synchronized(lifecycle) {
            if (closed) return
            closed = true; subscriptions.toList() to coordinators.values.toList()
        }
        owned.first.forEach { it.close() }; owned.second.forEach { it.stop() }
    }
}
internal class PortfolioHttpSubscription(private val stream: PortfolioSubscription, private val release: (PortfolioHttpSubscription) -> Unit) : AutoCloseable {
    fun next() = stream.next()
    override fun close() { stream.close(); release(this) }
}
internal fun portfolioLastVersion(value: String?): BigInteger? = try { value?.let { pythonIntegerValue(JsonPrimitive(it)) } }
    catch (_: CategoryFailure) { null }
internal fun portfolioSseFrame(update: PortfolioStreamUpdate): String {
    if (update.kind == "heartbeat") return ": keepalive\n\n"
    val data = buildJsonObject { put("version", JsonPrimitive(update.version)); update.portfolio?.let { put("portfolio", it) } }
    return "id: ${update.version}\nevent: ${update.kind}\ndata: $data\n\n"
}
