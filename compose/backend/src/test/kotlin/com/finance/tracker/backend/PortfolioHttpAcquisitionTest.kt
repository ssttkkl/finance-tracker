package com.finance.tracker.backend

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*

class PortfolioHttpAcquisitionTest {
    @Test fun cancellationAfterRegistrationBeforeDispatchReturnReleasesSubscription() = runBlocking {
        val f = PortfolioHttpFixture(); val db = f.database(); val service = PortfolioHttpService(db)
        val principal = ReadServices(db).currentContext(f.token); val registered = CountDownLatch(1); val release = CountDownLatch(1)
        service.use {
            val job = launch {
                withPortfolioSubscription({
                    val stream = service.subscribe(f.token, principal, PortfolioRefreshScope(null, "24h", null), null)
                    registered.countDown(); release.await(3, TimeUnit.SECONDS); stream
                }) { error("cancelled acquisition must not enter response") }
            }
            yield(); assertTrue(withContext(Dispatchers.IO) { registered.await(2, TimeUnit.SECONDS) }); assertEquals(1, service.activeSubscriptions())
            try { job.cancel() } finally { release.countDown() }
            job.join(); assertEquals(0, service.activeSubscriptions()); assertEquals(0, service.activeWorkers())
        }
    }
}
