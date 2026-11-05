package com.finance.tracker.backend

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class ParsedImportCleanup(
    private val sweep: (String?, Int, () -> Boolean) -> ParsedImportSweep,
    private val intervalSeconds: Int = 30,
    private val pageSize: Int = 100,
    private val warn: (String) -> Unit = { System.getLogger("cash-import-cleanup").log(System.Logger.Level.WARNING, it) },
) {
    constructor(store: ParsedImportStore, intervalSeconds: Int = 30, pageSize: Int = 100) :
        this(store::sweepExpired, intervalSeconds, pageSize)

    init { require(intervalSeconds in 1..60 && pageSize in 1..200) { "import_session_cleanup_config_invalid" } }
    private val lifecycle = Any()
    private val sweepLock = Any()
    @Volatile private var stopping = CountDownLatch(1)
    @Volatile private var worker: Thread? = null
    private var starting = false
    private var stopCount = 0
    private var startFinished = CountDownLatch(0)
    private var cursor: String? = null
    @Volatile var lastResult: ParsedImportSweep? = null
        private set
    @Volatile var healthy: Boolean? = null
        private set
    val running: Boolean get() = worker?.isAlive == true

    fun sweepOnce(raiseOnError: Boolean = true): Boolean = synchronized(sweepLock) {
        val result = try { sweep(cursor, pageSize) { stopping.count == 0L } }
        catch (_: Exception) {
            healthy = false
            if (raiseOnError) throw ParsedImportStorageUnavailable()
            warn("cash_import_cleanup_unavailable")
            return@synchronized false
        }
        lastResult = result; cursor = result.nextCursor; healthy = true
        true
    }

    fun start() {
        val stop: CountDownLatch
        val finished: CountDownLatch
        synchronized(lifecycle) {
            if (running || starting || stopCount != 0) return
            starting = true
            stopping = CountDownLatch(1); startFinished = CountDownLatch(1)
            stop = stopping; finished = startFinished
        }
        try {
            sweepOnce()
            synchronized(lifecycle) {
                if (stop.count != 0L) {
                    worker = Thread({ run(stop) }, "cash-import-cleanup").apply { isDaemon = true; start() }
                }
            }
        } finally {
            synchronized(lifecycle) { starting = false; finished.countDown() }
        }
    }

    fun stop() {
        val current: Thread?
        val finished: CountDownLatch
        synchronized(lifecycle) {
            stopCount++
            stopping.countDown()
            current = worker; finished = startFinished
        }
        var interrupted = false
        try {
            while (true) {
                try {
                    finished.await()
                    if (current !== Thread.currentThread()) current?.join()
                    break
                } catch (_: InterruptedException) { interrupted = true }
            }
        } finally {
            synchronized(lifecycle) { stopCount-- }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun run(stop: CountDownLatch) {
        var waitSeconds = synchronized(sweepLock) { if (cursor != null) 0 else intervalSeconds }
        while (!stop.await(waitSeconds.toLong(), TimeUnit.SECONDS)) {
            val success = sweepOnce(raiseOnError = false)
            waitSeconds = synchronized(sweepLock) { if (success && cursor != null) 0 else intervalSeconds }
        }
    }
}
