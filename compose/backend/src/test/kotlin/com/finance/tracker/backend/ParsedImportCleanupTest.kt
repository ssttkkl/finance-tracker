package com.finance.tracker.backend

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ParsedImportCleanupTest {
    @Test fun workerVisitsNextPagesWithoutUserRequestsAndStops() {
        val visited = CountDownLatch(1)
        val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<String?, Int>>())
        val worker = ParsedImportCleanup({ cursor, limit, _ ->
            calls += cursor to limit
            if (cursor != null) visited.countDown()
            ParsedImportSweep(1, 1, if (cursor == null) "next-page" else null)
        }, intervalSeconds = 60, pageSize = 2)
        worker.start()
        try {
            worker.start()
            assertTrue(visited.await(2, TimeUnit.SECONDS))
        } finally { worker.stop() }
        assertFalse(worker.running); assertNotNull(worker.lastResult)
        assertEquals(listOf(null to 2, "next-page" to 2), calls)
        assertEquals(true, worker.healthy)
    }

    @Test fun initialFailurePreventsStartAndDoesNotExposeProviderDetails() {
        val worker = ParsedImportCleanup({ _, _, _ -> error("secret provider details") })
        val error = assertFailsWith<ParsedImportStorageUnavailable> { worker.start() }
        assertFalse(worker.running); assertEquals(false, worker.healthy)
        assertFalse(error.message.orEmpty().contains("secret")); assertNull(error.cause)
        worker.stop()
    }

    @Test fun stopDuringStartupReachesSweepAndPreventsWorkerCreation() {
        val entered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val worker = ParsedImportCleanup({ _, _, shouldStop ->
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!shouldStop() && System.nanoTime() < deadline) Thread.yield()
            check(shouldStop()) { "stop signal never reached initial sweep" }
            stopped.countDown()
            ParsedImportSweep(0, 0, null)
        })
        val pool = Executors.newFixedThreadPool(2)
        try {
            val starting = pool.submit { worker.start() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val stopping = pool.submit { worker.stop() }
            assertTrue(stopped.await(1, TimeUnit.SECONDS))
            stopping.get(2, TimeUnit.SECONDS); starting.get(2, TimeUnit.SECONDS)
            assertFalse(worker.running)
        } finally { worker.stop(); pool.shutdownNow() }
    }

    @Test fun failureRetainsCursorAndHealthCanRecoverWithoutLoggingPrivateValues() {
        var failed = false
        val calls = mutableListOf<String?>()
        val warnings = mutableListOf<String>()
        val worker = ParsedImportCleanup({ cursor, _, _ ->
            calls += cursor
            if (failed) error("secret provider details")
            ParsedImportSweep(1, 1, if (cursor == null) "next-page" else null)
        }, warn = warnings::add)
        assertTrue(worker.sweepOnce()); assertEquals(true, worker.healthy)
        assertEquals("next-page", worker.lastResult!!.nextCursor)
        failed = true
        assertFalse(worker.sweepOnce(raiseOnError = false)); assertEquals(false, worker.healthy)
        assertEquals("next-page", worker.lastResult!!.nextCursor)
        assertEquals(listOf("cash_import_cleanup_unavailable"), warnings)
        failed = false
        assertTrue(worker.sweepOnce()); assertEquals(true, worker.healthy); assertNull(worker.lastResult!!.nextCursor)
        assertEquals(listOf(null, "next-page", "next-page"), calls)
    }

    @Test fun boundedConfigurationAndRestartPreserveOneWorker() {
        for ((interval, size) in listOf(0 to 100, 61 to 100, 30 to 201, 30 to 0)) {
            assertEquals("import_session_cleanup_config_invalid", assertFailsWith<IllegalArgumentException> {
                ParsedImportCleanup({ _, _, _ -> ParsedImportSweep(0, 0, null) }, interval, size)
            }.message)
        }
        var calls = 0
        val worker = ParsedImportCleanup({ _, _, _ -> calls++; ParsedImportSweep(0, 0, null) }, 60)
        repeat(2) {
            worker.start(); assertTrue(worker.running)
            worker.start(); worker.stop(); worker.stop(); assertFalse(worker.running)
        }
        assertEquals(2, calls)
    }

    @Test fun interruptedStopStillWaitsForInFlightSweepAndRestoresInterruption() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val observedStop = CountDownLatch(1)
        val interrupted = java.util.concurrent.atomic.AtomicBoolean()
        val worker = ParsedImportCleanup({ cursor, _, shouldStop ->
            if (cursor != null) {
                entered.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (!shouldStop() && System.nanoTime() < deadline) Thread.yield()
                check(shouldStop()); observedStop.countDown()
                check(release.await(2, TimeUnit.SECONDS))
            }
            ParsedImportSweep(0, 0, if (cursor == null) "next" else null)
        }, 60)
        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val stopping = Thread {
            Thread.currentThread().interrupt()
            worker.stop()
            interrupted.set(Thread.currentThread().isInterrupted)
            finished.countDown()
        }
        try {
            stopping.start()
            assertTrue(observedStop.await(2, TimeUnit.SECONDS))
            assertEquals(1L, finished.count)
            worker.start()
        } finally { release.countDown(); stopping.join(2000); worker.stop() }
        assertEquals(0L, finished.count); assertTrue(interrupted.get()); assertFalse(worker.running)
    }
}
