package com.finance.tracker.backend

import java.math.BigInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.JsonObject

internal data class PortfolioRefreshScope(val displayCurrency: String?, val period: String, val timezone: String?)
internal data class PortfolioStreamUpdate(val version: BigInteger, val kind: String, val portfolio: JsonObject? = null)
internal data class PortfolioRefreshEntry(var snapshot: JsonObject? = null, var version: BigInteger = BigInteger.ZERO,
    var latest: PortfolioStreamUpdate? = null, var subscribers: Int = 0, var requested: Boolean = true,
    var manualRequested: Boolean = false, var generation: BigInteger = BigInteger.ZERO, var nextRefreshAt: Double = 0.0, var failureCount: Int = 0)
internal fun interface PortfolioRefreshQuery { fun get(scope: PortfolioRefreshScope, publish: (JsonObject) -> Unit): JsonObject }
internal fun portfolioNextEntry(entries: Map<PortfolioRefreshScope, PortfolioRefreshEntry>, now: Double): Pair<PortfolioRefreshScope, PortfolioRefreshEntry>? =
    entries.entries.filter { (_, entry) -> entry.requested || (entry.subscribers > 0 && entry.nextRefreshAt <= now) }
        .minWithOrNull(compareBy<Map.Entry<PortfolioRefreshScope, PortfolioRefreshEntry>> { if (it.value.manualRequested) 0 else if (it.value.requested) 1 else 2 }.thenBy { it.value.nextRefreshAt })?.let { it.key to it.value }

internal class PortfolioRefreshCoordinator(private val query: PortfolioRefreshQuery, refreshIntervalSeconds: Double = 5.0,
    heartbeatSeconds: Double = 15.0, maxBackoffSeconds: Double = 60.0, private val monotonic: () -> Double = { System.nanoTime() / 1e9 }) : AutoCloseable {
    private val refreshInterval = maxOf(refreshIntervalSeconds, 0.1)
    private val heartbeat = maxOf(heartbeatSeconds, 0.1)
    private val maxBackoff = maxOf(maxBackoffSeconds, refreshInterval)
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val entries = linkedMapOf<PortfolioRefreshScope, PortfolioRefreshEntry>()
    @Volatile private var stopping = false
    private var worker: Thread? = null
    fun start() = lock.withLock {
        if (worker?.isAlive == true) return@withLock
        stopping = false
        worker = Thread(::run, "portfolio-refresh").apply { isDaemon = true; start() }
    }
    fun stop() {
        stopping = true
        val running = lock.withLock { changed.signalAll(); worker }
        running?.join()
    }
    override fun close() = stop()
    fun workerAlive() = lock.withLock { worker?.isAlive == true }
    internal fun state(scope: PortfolioRefreshScope) = lock.withLock { entries[scope]?.copy() }
    fun requestRefresh(displayCurrency: String? = null, period: String = "24h", timezone: String? = null) {
        val scope = scope(displayCurrency, period, timezone); start()
        lock.withLock {
            val entry = entries.getOrPut(scope, ::PortfolioRefreshEntry)
            entry.requested = true; entry.manualRequested = true; entry.generation += BigInteger.ONE; changed.signalAll()
        }
    }
    fun subscribe(displayCurrency: String? = null, period: String = "24h", timezone: String? = null, lastVersion: BigInteger? = null): PortfolioSubscription =
        PortfolioSubscription(this, scope(displayCurrency, period, timezone), lastVersion ?: BigInteger.valueOf(-1))
    internal fun enter(scope: PortfolioRefreshScope) {
        start()
        lock.withLock {
            val entry = entries.getOrPut(scope, ::PortfolioRefreshEntry)
            entry.subscribers++; entry.requested = true; entry.generation += BigInteger.ONE; changed.signalAll()
        }
    }
    internal fun leave(scope: PortfolioRefreshScope) = lock.withLock {
        entries[scope]?.let { it.subscribers = maxOf(it.subscribers - 1, 0) }; changed.signalAll()
    }
    internal fun next(scope: PortfolioRefreshScope, delivered: BigInteger, closed: () -> Boolean): PortfolioStreamUpdate = lock.withLock {
        if (stopping || closed()) throw NoSuchElementException("portfolio subscription closed")
        val latest = entries.getValue(scope).latest
        if (latest != null && latest.version > delivered) latest else {
            changed.awaitNanos((heartbeat * 1e9).toLong())
            if (closed()) throw NoSuchElementException("portfolio subscription closed")
            PortfolioStreamUpdate(delivered, "heartbeat")
        }
    }
    private fun run() {
        while (!stopping) {
            val selected = lock.withLock {
                val next = portfolioNextEntry(entries, monotonic())
                if (next == null) {
                    val upcoming = entries.values.filter { it.subscribers > 0 }.minOfOrNull { it.nextRefreshAt }
                    val wait = upcoming?.let { maxOf(it - monotonic(), 0.01) } ?: heartbeat
                    changed.awaitNanos((wait * 1e9).toLong()); null
                } else next.first to next.second.generation
            }
            selected?.let { refresh(it.first, it.second) }
        }
    }
    private fun refresh(scope: PortfolioRefreshScope, generation: BigInteger) {
        var emitted: JsonObject? = null
        val snapshot = try { query.get(scope) { emitted = it; publish(scope, it) } }
        catch (_: Exception) { fail(scope, generation); return }
        if (!portfolioSnapshotsEqual(emitted, snapshot)) publish(scope, snapshot)
        lock.withLock {
            val entry = entries.getValue(scope)
            if (entry.generation == generation) { entry.requested = false; entry.manualRequested = false }
            entry.failureCount = 0; entry.nextRefreshAt = monotonic() + refreshInterval; changed.signalAll()
        }
    }
    private fun publish(scope: PortfolioRefreshScope, incoming: JsonObject) = lock.withLock {
        val entry = entries.getValue(scope)
        entry.snapshot = portfolioMergeLastSuccess(entry.snapshot, incoming); entry.version += BigInteger.ONE
        entry.latest = PortfolioStreamUpdate(entry.version, "portfolio", entry.snapshot); changed.signalAll()
    }
    private fun fail(scope: PortfolioRefreshScope, generation: BigInteger) = lock.withLock {
        val entry = entries.getValue(scope)
        entry.version += BigInteger.ONE; entry.latest = PortfolioStreamUpdate(entry.version, "refresh_error")
        entry.failureCount = minOf(entry.failureCount + 1, 4)
        if (entry.generation == generation) { entry.requested = false; entry.manualRequested = false }
        entry.nextRefreshAt = monotonic() + minOf(refreshInterval * (1 shl entry.failureCount), maxBackoff); changed.signalAll()
    }
    private fun scope(currency: String?, period: String, timezone: String?) = PortfolioRefreshScope(
        valuationUpper(valuationStrip(currency.orEmpty())).ifEmpty { null }, period.ifEmpty { "24h" }, valuationStrip(timezone.orEmpty()).ifEmpty { null })
}
internal class PortfolioSubscription(private val coordinator: PortfolioRefreshCoordinator, private val scope: PortfolioRefreshScope,
    private var delivered: BigInteger) : AutoCloseable {
    private val lifecycle = Any()
    private var entered = false
    @Volatile private var closed = false
    fun next(): PortfolioStreamUpdate {
        synchronized(lifecycle) {
            if (closed) throw NoSuchElementException("portfolio subscription closed")
            if (!entered) { coordinator.enter(scope); entered = true }
        }
        return try { coordinator.next(scope, delivered) { closed }.also { if (it.kind != "heartbeat") delivered = it.version } }
        catch (failure: Exception) { close(); throw failure }
    }
    override fun close() = synchronized(lifecycle) { if (!closed) { closed = true; if (entered) coordinator.leave(scope) } }
}
