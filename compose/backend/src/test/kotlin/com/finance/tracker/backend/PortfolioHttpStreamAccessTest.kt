package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.USER_SESSIONS
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class PortfolioHttpStreamAccessTest {
    @Test fun workspaceChangesAfterFirstEventCloseStreamBeforeNextPortfolioCanLeak() {
        val f = PortfolioHttpFixture(); val db = f.database(); val release = CountDownLatch(1)
        val service = PortfolioHttpService(db, factory = { object : PortfolioRead {
            override fun holdings() = portfolioHttpValue()
            override fun portfolio(scope: PortfolioRefreshScope, publish: ((JsonObject) -> Unit)?): JsonObject {
                publish!!.invoke(holdings()); release.await(3, TimeUnit.SECONDS)
                return JsonObject(holdings() + ("private_next" to JsonPrimitive("MUST_NOT_BE_DELIVERED")))
            }
        } }, coordinatorFactory = { PortfolioRefreshCoordinator(it, heartbeatSeconds = 0.1) })
        PortfolioHttpServer(db, service).use { server ->
            val connection = java.net.URI.create(server.origin + "/api/v1/investment-portfolio/stream").toURL().openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
            connection.setRequestProperty("Authorization", "Bearer " + f.token); connection.readTimeout = 3000
            val received = StringBuilder()
            try {
                assertEquals(200, connection.responseCode)
                connection.inputStream.bufferedReader().use { reader ->
                    while (!received.contains("event: portfolio")) received.append(reader.readLine() ?: error("no initial portfolio")).append('\n')
                    while (true) { val line = reader.readLine() ?: error("incomplete first event"); received.append(line).append('\n'); if (line.isEmpty()) break }
                    db.write { c -> assertEquals(1, c.jooq(db.sqlite).update(USER_SESSIONS).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, "other-workspace").where(USER_SESSIONS.TOKEN_DIGEST.eq(tokenDigest(f.token))).execute()) }
                    release.countDown()
                    try { while (true) received.append(reader.readLine() ?: break).append('\n') } catch (_: java.io.IOException) { /* The already-started response may end with a closed socket. */ }
                }
                assertFalse("MUST_NOT_BE_DELIVERED" in received)
                val until = System.nanoTime() + 2_000_000_000
                while (service.activeSubscriptions() != 0 && System.nanoTime() < until) Thread.sleep(10)
                assertEquals(0, service.activeSubscriptions())
            } finally {
                release.countDown(); connection.disconnect()
                db.write { c -> c.jooq(db.sqlite).update(USER_SESSIONS).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, f.workspace).where(USER_SESSIONS.TOKEN_DIGEST.eq(tokenDigest(f.token))).execute() }
            }
        }
        assertEquals(0, service.activeWorkers())
    }
}
