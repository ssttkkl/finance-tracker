package com.finance.tracker

import com.finance.tracker.business.contracts.*
import com.finance.tracker.core.LocalCashLedgerOwner
import com.finance.tracker.domain.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LocalCashLedgerOwnerConcurrencyTest {
    @Test fun revocationDuringActualDerivationPreventsReturningTheCapturedPageEvenIfErasureFails() {
        for (status in listOf(401, 403)) for (failErase in listOf(false, true)) {
            val scope = LedgerCacheScope("https://one.test", "person", "workspace")
            val base = localCashLedgerOwnerSnapshot()
            val deriving = CountDownLatch(1); val release = CountDownLatch(1); val erasing = CountDownLatch(1)
            val guardedRows = object : AbstractList<LedgerCashTransactionFactDto>() {
                override val size get() = base.facts.cashTransactions.size
                override fun get(index: Int): LedgerCashTransactionFactDto {
                    deriving.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "synthetic derivation barrier timed out" }
                    return base.facts.cashTransactions[index]
                }
            }
            val initial = base.copy(facts = base.facts.copy(cashTransactions = guardedRows))
            var cached: LedgerSnapshotDto? = initial
            val eraseFailure = IllegalStateException("synthetic erasure failure")
            val denied = DomainFailure("access_denied", status, FailureCategory.RECOVERABLE)
            val cache = object : LedgerFactCache {
                override suspend fun read(scope: LedgerCacheScope) = cached
                override suspend fun replace(scope: LedgerCacheScope, snapshot: LedgerSnapshotDto) { error("unexpected write") }
                override suspend fun close() = Unit
                override suspend fun clear(scope: LedgerCacheScope) {
                    erasing.countDown()
                    if (failErase) throw eraseFailure
                    cached = null
                }
            }
            val source = object : LedgerSnapshotSource {
                override suspend fun version(workspaceId: String): LedgerVersionDto = throw denied
                override suspend fun snapshot(workspaceId: String): LedgerSnapshotDto = error("unexpected download")
            }
            val owner = LocalCashLedgerOwner(scope, cache, source)
            val threads = Executors.newFixedThreadPool(2)
            try {
                val page = threads.submit<Result<CashPageDto?>> { runBlocking { runCatching { owner.page(timeZoneId = "UTC") } } }
                assertTrue(deriving.await(10, TimeUnit.SECONDS), "The real reader must have consumed its committed cache")
                val refresh = threads.submit<Result<Unit>> { runBlocking { runCatching { owner.refresh() } } }
                assertTrue(erasing.await(10, TimeUnit.SECONDS), "A real auth failure must close sync before the page continues")
                release.countDown()
                assertTrue(page.get(10, TimeUnit.SECONDS).exceptionOrNull() is CancellationException,
                    "A page captured before revocation must never return after its auth scope is closed")
                val failure = refresh.get(10, TimeUnit.SECONDS).exceptionOrNull()
                val chain = generateSequence(assertNotNull(failure) as Throwable?) { it.cause }.toList()
                assertTrue(chain.any { it === if (failErase) eraseFailure else denied })
                if (failErase) assertTrue(chain.any { it.suppressedExceptions.contains(denied) })
            } finally {
                release.countDown(); threads.shutdown()
                assertTrue(threads.awaitTermination(15, TimeUnit.SECONDS))
            }
        }
    }
}
