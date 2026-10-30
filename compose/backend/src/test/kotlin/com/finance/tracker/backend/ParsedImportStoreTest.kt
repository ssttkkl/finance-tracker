package com.finance.tracker.backend

import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ParsedImportStoreTest(private val backend: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun backends() = listOf(arrayOf("memory"), arrayOf("r2"))
    }
    private fun store(ttlSeconds: Int = 1800, clock: () -> Instant = Instant::now,
        codec: ParsedImportCodec = ParsedImportCodec()): ParsedImportStore = when (backend) {
        "memory" -> InMemoryParsedImportStore(ttlSeconds = ttlSeconds, clock = clock, codec = codec)
        else -> R2ParsedImportStore(R2ParsedImportStoreTest.Client(), "bucket", ttlSeconds = ttlSeconds, clock = clock, codec = codec)
    }
    private fun file(ref: String = "f1") = ParsedImportFile(ref, "a".repeat(64), "parsed",
        ParsedCashDocument("alipay", "csv:0", "native-v1", listOf(ParsedCashRow(8,
            mutableMapOf("amount" to "-20.00", "note" to "原始说明", "source_payload" to mapOf("金额" to "20.00")),
            "fixture-bank", "001")), "fixture-owner", "fixture-owner",
            CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1"), 1L, 1),
        clientFileRef = "client-$ref", filename = "synthetic.csv", size = 100, sourceType = "alipay")

    @Test fun ownerRevisionCancellationAndOriginalLifetimeArePreserved() {
        var now = Instant.parse("2026-10-21T12:00:00Z")
        val store = store(ttlSeconds = 60, clock = { now })
        val first = store.create("workspace-a", "user-a", listOf(file()))
        assertEquals(43, first.sessionId.length)
        assertEquals(first, store.get(first.sessionId, "workspace-a", "user-a"))
        assertFailsWith<ParsedImportForbidden> { store.get(first.sessionId, "workspace-b", "user-a") }
        assertFailsWith<ParsedImportForbidden> { store.get(first.sessionId, "workspace-a", "user-b") }
        now = now.plusSeconds(30)
        val next = store.replace(first.sessionId, "workspace-a", "user-a", 1, listOf(file("f2")),
            mapOf("rows" to emptyList<Any>()), mapOf("plan_id" to "plan-1"))!!
        assertEquals(java.math.BigInteger.TWO, next.revision); assertEquals(first.expiresAt, next.expiresAt)
        assertEquals("f2", next.files.single().fileRef)
        assertEquals("plan-1", next.plan!!["plan_id"])
        assertEquals(mapOf("rows" to emptyList<Any>()), next.previewInput)
        assertEquals(next, store.get(first.sessionId, "workspace-a", "user-a"))
        assertFailsWith<ParsedImportRevisionConflict> {
            store.replace(first.sessionId, "workspace-a", "user-a", 1, first.files, emptyMap(), null)
        }
        val cancelled = store.cancel(first.sessionId, "workspace-a", "user-a", 2)
        assertTrue(cancelled.cancelled); assertTrue(cancelled.files.isEmpty()); assertTrue(cancelled.contextData.isEmpty())
        assertTrue(cancelled.previewInput.isEmpty()); assertNull(cancelled.plan)
        assertEquals(cancelled, store.cancel(first.sessionId, "workspace-a", "user-a", 3))
        assertFailsWith<ParsedImportClosed> {
            store.replace(first.sessionId, "workspace-a", "user-a", 3, first.files, emptyMap(), null)
        }
        now = first.expiresAt
        assertFailsWith<ParsedImportForbidden> { store.get(first.sessionId, "workspace-a", "user-b") }
        assertFailsWith<ParsedImportExpired> { store.get(first.sessionId, "workspace-a", "user-a") }
        assertFailsWith<ParsedImportNotFound> { store.get(first.sessionId, "workspace-a", "user-a") }
    }

    @Test fun oneConcurrentReplacementWinsAndFailedPayloadKeepsPreviousState() {
        val store = store()
        val first = store.create("w", "u", listOf(file()))
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll((1..8).map { index -> Callable {
                runCatching { store.replace(first.sessionId, "w", "u", 1, first.files, emptyMap(), mapOf("version" to index)) }
            } }).map { it.get() }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(java.math.BigInteger.TWO, results.single { it.isSuccess }.getOrThrow()!!.revision)
            assertEquals(7, results.count { it.exceptionOrNull() is ParsedImportRevisionConflict })
        } finally { pool.shutdownNow() }
        val current = store.get(first.sessionId, "w", "u")
        assertEquals(java.math.BigInteger.TWO, current.revision)
        assertFailsWith<ParsedImportDataError> {
            store.replace(first.sessionId, "w", "u", 2, current.files, emptyMap(), mapOf("value" to Double.NaN))
        }
        assertEquals(current, store.get(first.sessionId, "w", "u"))
        val details = mutableMapOf("note" to "before")
        val plan = mutableMapOf<String, Any?>("details" to mutableListOf(details))
        assertNull(store.replace(first.sessionId, "w", "u", 2, current.files, emptyMap(), plan, returnSession = false))
        details["note"] = "caller mutation"
        plan.clear()
        val published = store.get(first.sessionId, "w", "u")
        assertEquals(java.math.BigInteger.valueOf(3), published.revision)
        assertEquals(listOf(mapOf("note" to "before")), published.plan!!["details"])
        assertFailsWith<ParsedImportRevisionConflict> {
            store.replace(first.sessionId, "w", "u", 2, current.files, emptyMap(), emptyMap(), returnSession = false)
        }
        assertEquals(published, store.get(first.sessionId, "w", "u"))
    }

    @Test fun mutationContextCannotGainFilesAndInheritsBoundedLifetime() {
        var now = Instant.parse("2026-10-21T12:00:00Z")
        val store = store(ttlSeconds = 60, clock = { now })
        assertFailsWith<ParsedImportDataError> { store.create("w", "u", emptyList()) }
        assertFailsWith<ParsedImportDataError> { store.create("w", "u", listOf(file()), workflowKind = "mutation") }
        val original = store.create("w", "u", listOf(file()))
        now = now.plusSeconds(30)
        val child = store.create("w", "u", emptyList(), "mutation", mapOf("transaction_id" to "1"),
            original.createdAt, original.expiresAt)
        assertEquals(original.createdAt, child.createdAt); assertEquals(original.expiresAt, child.expiresAt)
        assertEquals("mutation", store.get(child.sessionId, "w", "u").workflowKind)
        assertFailsWith<ParsedImportDataError> { store.replace(child.sessionId, "w", "u", 1, listOf(file()), emptyMap(), null) }
        assertEquals(child, store.get(child.sessionId, "w", "u"))
        for ((start, end) in listOf(now.plusSeconds(1) to now.plusSeconds(60), now to now.plusSeconds(1801),
            now.minusSeconds(60) to now.minusSeconds(1)))
            assertFailsWith<ParsedImportDataError> { store.create("w", "u", listOf(file()), createdAt = start, expiresAt = end) }
    }

    @Test fun snapshotsRetainOriginalBytesAndCannotReopenCancelledOrExpiredSessions() {
        var now = Instant.parse("2026-10-21T12:00:00Z")
        val store = store(ttlSeconds = 60, clock = { now })
        val first = store.create("w", "u", listOf(file()))
        val snapshot = store.planUpdateSnapshot(first.sessionId, "w", "u")
        assertFailsWith<ParsedImportDataError> {
            InMemoryParsedImportStore().replacePlan(snapshot, "w", "u", 1, emptyMap(), emptyMap())
        }
        snapshot.parsed().fill(0)
        assertEquals(first, snapshot.session())
        store.replacePlan(snapshot, "w", "u", 1, emptyMap(), mapOf("plan_id" to "p1"))
        val current = store.get(first.sessionId, "w", "u")
        assertEquals(first.files, current.files); assertEquals(java.math.BigInteger.TWO, current.revision)
        assertFailsWith<ParsedImportRevisionConflict> { store.replacePlan(snapshot, "w", "u", 1, emptyMap(), emptyMap()) }
        assertFailsWith<ParsedImportForbidden> { store.replacePlan(snapshot, "w", "other", 1, emptyMap(), emptyMap()) }
        val fresh = store.planUpdateSnapshot(first.sessionId, "w", "u")
        store.cancel(first.sessionId, "w", "u", 2)
        assertFailsWith<ParsedImportRevisionConflict> { store.replacePlan(fresh, "w", "u", 2, emptyMap(), emptyMap()) }
        val closed = store.get(first.sessionId, "w", "u")
        assertTrue(closed.cancelled); assertTrue(closed.files.isEmpty())
        val cancelled = store.planUpdateSnapshot(first.sessionId, "w", "u")
        assertFailsWith<ParsedImportClosed> { store.replacePlan(cancelled, "w", "u", 3, emptyMap(), emptyMap()) }
        now = first.expiresAt
        assertFailsWith<ParsedImportExpired> { store.replacePlan(fresh, "w", "u", 2, emptyMap(), emptyMap()) }
    }

    @Test fun capacityAndBoundedSweepReclaimOnlyExpiredSessions() {
        var now = Instant.parse("2026-10-21T12:00:00Z")
        val store = InMemoryParsedImportStore(ttlSeconds = 60, clock = { now }, maxSessions = 3)
        repeat(3) { store.create("w", "u", listOf(file())) }
        assertEquals("import_session_capacity_exceeded", assertFailsWith<ParsedImportDataError> {
            store.create("w", "u", listOf(file()))
        }.code)
        val before = store.sweepExpired(limit = 2)
        assertEquals(2, before.scanned); assertEquals(0, before.deleted); assertNotNull(before.nextCursor)
        now = now.plusSeconds(60)
        val reclaim = InMemoryParsedImportStore(ttlSeconds = 1, maxSessions = 1, clock = { now })
        reclaim.create("w", "u", listOf(file()))
        assertEquals("import_session_capacity_exceeded", assertFailsWith<ParsedImportDataError> {
            reclaim.create("w", "u", listOf(file()))
        }.code)
        now = now.plusSeconds(1)
        assertEquals(java.math.BigInteger.ONE, reclaim.create("w", "u", listOf(file())).revision)
        val first = store.sweepExpired(limit = 2)
        assertEquals(2, first.scanned); assertEquals(2, first.deleted)
        val last = store.sweepExpired(cursor = first.nextCursor, limit = 2)
        assertEquals(1, last.deleted); assertNull(last.nextCursor)
        val fresh = store.create("w", "u", listOf(file()))
        assertEquals(fresh, store.get(fresh.sessionId, "w", "u"))
        val stopped = store.sweepExpired(shouldStop = { true })
        assertEquals(0, stopped.scanned); assertEquals(0, stopped.deleted)
        for (limit in listOf(-1, 0, 201)) assertFailsWith<ParsedImportDataError> { store.sweepExpired(limit = limit) }
    }

    @Test fun publicPageCacheIsIsolatedByNestedKeyTypeOwnerRevisionAndExpiry() {
        var now = Instant.parse("2026-10-21T12:00:00Z")
        val store = store(ttlSeconds = 60, clock = { now })
        val first = store.create("w", "u", listOf(file()))
        store.replace(first.sessionId, "w", "u", 1, first.files, emptyMap(), mapOf("plan_id" to "one"))
        var calls = 0
        fun read(key: Any?, user: String = "u") = store.readPlanProjection(first.sessionId, "w", user, listOf("one", key)) {
            calls++; mapOf("result" to calls, "revision" to it.revision)
        }
        val keys = listOf(arrayListOf(1), arrayListOf("1"), arrayListOf("a, b"), arrayListOf("a", "b"))
        keys.forEachIndexed { index, key -> assertEquals(index + 1, read(key)["result"]) }
        keys.forEachIndexed { index, key -> assertEquals(index + 1, read(key)["result"]) }
        assertEquals(4, calls)
        assertFailsWith<ParsedImportForbidden> { read(keys.first(), "other") }
        store.replace(first.sessionId, "w", "u", 2, first.files, emptyMap(), mapOf("plan_id" to "one"))
        assertEquals(5, read(keys.first())["result"])
        now = first.expiresAt
        assertFailsWith<ParsedImportExpired> { read(keys.first()) }
    }

    @Test fun lateOldPageFillNeverReplacesCurrentRevision() {
        val store = store()
        val first = store.create("w", "u", listOf(file()))
        store.replace(first.sessionId, "w", "u", 1, first.files, emptyMap(), mapOf("plan_id" to "one"))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        fun read() = store.readPlanProjection(first.sessionId, "w", "u", listOf("one", "items")) {
            if (it.revision == java.math.BigInteger.TWO) { entered.countDown(); assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            mapOf("revision" to it.revision)
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val old = pool.submit(Callable { read() })
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                store.replace(first.sessionId, "w", "u", 2, first.files, emptyMap(), mapOf("plan_id" to "one"))
                assertEquals(mapOf("revision" to 3), read())
            } finally { release.countDown() }
            assertEquals(mapOf("revision" to 2), old.get())
            assertEquals(mapOf("revision" to 3), read())
        } finally { pool.shutdownNow() }
    }

    @Test fun publicCacheBoundsAndCopiesResponsesWithoutCachingPrivatePlan() {
        val store = store()
        val first = store.create("w", "u", listOf(file()))
        store.replace(first.sessionId, "w", "u", 1, first.files, emptyMap(), mapOf("plan_id" to "one", "private" to "secret"))
        var calls = 0
        fun read(index: Int, payload: String = "public") = store.readPlanProjection(first.sessionId, "w", "u", listOf("one", index)) {
            calls++; mapOf("items" to mutableListOf(mutableMapOf("value" to payload)))
        }
        val one = read(0)
        @Suppress("UNCHECKED_CAST")
        (one["items"] as List<MutableMap<String, Any?>>).single()["value"] = "changed"
        assertEquals(mapOf("items" to listOf(mapOf("value" to "public"))), read(0))
        assertEquals(1, calls)
        (1..19).forEach { read(it) }
        assertEquals(20, calls)
        read(19); assertEquals(20, calls)
        read(0); assertEquals(21, calls)
        val huge = "x".repeat(4 * 1024 * 1024)
        read(100, huge); read(100, huge)
        assertEquals(23, calls)
        val medium = "x".repeat(2 * 1024 * 1024)
        read(101, medium); read(102, medium); assertEquals(25, calls)
        read(102, medium); assertEquals(25, calls)
        read(101, medium); assertEquals(26, calls)
        val cycle = mutableListOf<Any?>(); cycle.add(cycle)
        assertFailsWith<ParsedImportDataError> {
            store.readPlanProjection(first.sessionId, "w", "u", listOf("one", cycle)) { emptyMap() }
        }
    }

    @Test fun publicationWithoutEchoValidatesDeepFilesProofsAndJsonBeforeReplacing() {
        val store = store()
        val first = store.create("w", "u", listOf(file()))
        val file = first.files.single()
        var nested: Any = "合法标量"
        repeat(58) { nested = listOf(nested) }
        val proof = mutableMapOf("1" to "b".repeat(64))
        val coverage = file.document!!.coverage.copy(documentKey = "a".repeat(64), pageTotal = 1, pageFingerprints = proof)
        proof["1"] = "invalid-page-proof"
        val invalid = listOf(
            file.copy(document = file.document.copy(rows = listOf(file.document.rows.single().copy(fields = mapOf("nested" to nested))))),
            file.copy(document = file.document.copy(coverage = coverage)))
        invalid.forEach { bad ->
            assertFailsWith<ParsedImportDataError> {
                store.replace(first.sessionId, "w", "u", 1, listOf(bad), emptyMap(), emptyMap(), returnSession = false)
            }
            assertEquals(first, store.get(first.sessionId, "w", "u"))
        }
        for (bad in listOf(Double.NaN, mapOf("bad" to "\uD800"), mapOf("bad" to Any()))) {
            assertFailsWith<ParsedImportDataError> {
                store.replace(first.sessionId, "w", "u", 1, first.files, emptyMap(), mapOf("value" to bad), returnSession = false)
            }
            assertEquals(first, store.get(first.sessionId, "w", "u"))
        }
    }

    @Test fun parsedAndCompleteBudgetsProtectPreviouslyPublishedPlan() {
        val store = store(codec = ParsedImportCodec(maxBytes = 12000, maxParsedBytes = 3000))
        val first = store.create("w", "u", listOf(file()))
        val plan = mapOf("synthetic" to "x".repeat(5000))
        val current = store.replace(first.sessionId, "w", "u", 1, first.files, emptyMap(), plan)!!
        assertEquals(plan, current.plan); assertEquals(java.math.BigInteger.TWO, current.revision)
        val file = current.files.single()
        fun sized(size: Int) = file.copy(document = file.document!!.copy(rows = listOf(file.document.rows.single().copy(
            fields = mapOf("note" to "x".repeat(size))))))
        val one = sized(1700); val two = one.copy(fileRef = "file_two", clientFileRef = "client_two")
        store.validateFile(one); store.validateFile(two)
        for (files in listOf(listOf(sized(4000)), listOf(one, two))) {
            assertEquals("import_session_draft_too_large", assertFailsWith<ParsedImportDataError> {
                store.replace(first.sessionId, "w", "u", 2, files, emptyMap(), null)
            }.code)
            assertEquals(current, store.get(first.sessionId, "w", "u"))
        }
        for (value in listOf("x".repeat(13000), "\"".repeat(7000))) {
            assertEquals("import_session_draft_too_large", assertFailsWith<ParsedImportDataError> {
                store.replace(first.sessionId, "w", "u", 2, current.files, emptyMap(), mapOf("synthetic" to value))
            }.code)
            assertEquals(current, store.get(first.sessionId, "w", "u"))
        }
    }

    @Test fun planOnlyReadsRemainDetachedAndObserveReplacementCancellationAndExpiry() {
        var now = Instant.parse("2026-10-21T12:00:00Z")
        val store = store(ttlSeconds = 60, clock = { now })
        val first = store.create("w", "u", listOf(file()))
        store.replace(first.sessionId, "w", "u", 1, first.files, emptyMap(), mapOf("plan_id" to "one", "items" to listOf(mapOf("value" to "original"))))
        val snapshot = store.getPlanSnapshot(first.sessionId, "w", "u")
        assertTrue(snapshot.files.isEmpty()); assertEquals(java.math.BigInteger.TWO, snapshot.revision)
        @Suppress("UNCHECKED_CAST")
        (snapshot.plan!!["items"] as List<MutableMap<String, Any?>>).single()["value"] = "changed"
        assertEquals(listOf(mapOf("value" to "original")), store.getPlanSnapshot(first.sessionId, "w", "u").plan!!["items"])
        assertFailsWith<ParsedImportForbidden> { store.getPlanSnapshot(first.sessionId, "w", "other") }
        assertFailsWith<ParsedImportForbidden> { store.getPlanSnapshot(first.sessionId, "other", "u") }
        store.replace(first.sessionId, "w", "u", 2, first.files, emptyMap(), mapOf("plan_id" to "two"))
        assertEquals(mapOf("plan_id" to "two"), store.getPlanSnapshot(first.sessionId, "w", "u").plan)
        store.cancel(first.sessionId, "w", "u", 3)
        val cancelled = store.getPlanSnapshot(first.sessionId, "w", "u")
        assertTrue(cancelled.cancelled); assertNull(cancelled.plan)
        now = first.expiresAt
        assertFailsWith<ParsedImportExpired> { store.getPlanSnapshot(first.sessionId, "w", "u") }
    }

    @Test fun replacementCrossingExpiryDeletesBodyWithoutReturningSuccess() {
        val start = Instant.parse("2026-10-21T12:00:00Z")
        var publishing = false
        var checks = 0
        val store = store(ttlSeconds = 60, clock = {
            if (publishing && ++checks >= 3) start.plusSeconds(60) else start
        })
        val first = store.create("w", "u", listOf(file()))
        publishing = true
        assertFailsWith<ParsedImportExpired> {
            store.replace(first.sessionId, "w", "u", 1, first.files, emptyMap(), null)
        }
        assertEquals(3, checks)
        assertFailsWith<ParsedImportNotFound> { store.get(first.sessionId, "w", "u") }
    }
}
