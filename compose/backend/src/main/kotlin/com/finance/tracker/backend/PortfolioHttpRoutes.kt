package com.finance.tracker.backend

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal fun Application.installPortfolioRuntime(service: PortfolioHttpService) {
    monitor.subscribe(ApplicationStopPreparing) { service.close() }
    monitor.subscribe(ApplicationStopped) { service.close() }
}
private fun ApplicationCall.portfolioScope(): PortfolioRefreshScope {
    fun last(name: String) = request.queryParameters.getAll(name)?.lastOrNull()
    return PortfolioRefreshScope(last("display_currency"), last("period") ?: "24h", last("timezone"))
}
internal fun Route.portfolioRoutes(service: PortfolioHttpService, principalKey: AttributeKey<Principal>) {
    get("/api/v1/investment-portfolio") {
        val phase = call.request.queryParameters.getAll("phase")?.lastOrNull() ?: "valuation"
        val scope = call.portfolioScope(); val token = bearerToken(call.request.headers[HttpHeaders.Authorization]); val principal = call.attributes[principalKey]
        try { call.respond(withContext(Dispatchers.IO) { service.get(token, principal, scope, phase) }) }
        catch (failure: PortfolioHttpFailure) {
            call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", buildJsonObject {
                put("code", failure.code); put("message", if (failure.code == "valuation.invalid_display_currency") "展示币种无效。" else "持仓估值参数无效。")
            }) })
        }
    }
    post("/api/v1/investment-portfolio/refresh") {
        val scope = call.portfolioScope(); val token = bearerToken(call.request.headers[HttpHeaders.Authorization]); val principal = call.attributes[principalKey]
        withContext(Dispatchers.IO) { service.request(token, principal, scope) }
        call.respond(HttpStatusCode.Accepted, buildJsonObject { put("accepted", true) })
    }
    get("/api/v1/investment-portfolio/stream") {
        val scope = call.portfolioScope(); val token = bearerToken(call.request.headers[HttpHeaders.Authorization]); val principal = call.attributes[principalKey]
        withPortfolioSubscription({ service.subscribe(token, principal, scope, portfolioLastVersion(call.request.headers["Last-Event-ID"])) }) { stream ->
            call.response.headers.append(HttpHeaders.CacheControl, "no-cache"); call.response.headers.append("X-Accel-Buffering", "no")
            call.respondTextWriter(ContentType.Text.EventStream) {
                try {
                    while (currentCoroutineContext().isActive) {
                        val update = try { runInterruptible(Dispatchers.IO) { stream.next() } } catch (_: NoSuchElementException) { break }
                        withContext(Dispatchers.IO) { service.verify(token, principal) }
                        write(portfolioSseFrame(update)); flush()
                    }
                } finally { stream.close() }
            }
        }
    }
}

internal suspend fun <T> withPortfolioSubscription(acquire: () -> PortfolioHttpSubscription, block: suspend (PortfolioHttpSubscription) -> T): T {
    var owned: PortfolioHttpSubscription? = null
    try {
        withContext(Dispatchers.IO) { owned = acquire() }
        return block(requireNotNull(owned))
    } finally { owned?.close() }
}
