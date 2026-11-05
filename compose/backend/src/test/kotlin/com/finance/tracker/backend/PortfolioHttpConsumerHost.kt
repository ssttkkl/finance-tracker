package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.writeText

internal object PortfolioHttpConsumerHost {
    @JvmStatic fun main(args: Array<String>) {
        val fixture = PortfolioHttpFixture(); val db = fixture.database()
        val service = PortfolioHttpService(db, now = { fixture.now.instant() }, quotes = fixture.quotes(), fx = fixture.fx())
        val server = PortfolioHttpServer(db, service)
        val stopped = java.util.concurrent.CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread { server.close(); stopped.countDown() })
        Path.of(requireNotNull(System.getenv("FT_KOTLIN_PORTFOLIO_HOST_FILE"))).writeText(server.origin)
        stopped.await()
    }
}
