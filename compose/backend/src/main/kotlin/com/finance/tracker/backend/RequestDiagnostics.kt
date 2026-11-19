package com.finance.tracker.backend

import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.httpMethod
import io.ktor.server.routing.RoutingRoot
import io.ktor.util.AttributeKey
import java.util.UUID
import org.slf4j.LoggerFactory

internal const val REQUEST_ID_HEADER = "X-Request-ID"
internal val serverDiagnosticLog = LoggerFactory.getLogger("com.finance.tracker.diagnostics")

private val requestDiagnosticKey = AttributeKey<RequestDiagnostic>("finance.request.diagnostic")
private val commitPattern = Regex("[0-9a-f]{40}")
private data class RequestDiagnostic(val id: UUID = UUID.randomUUID(), val start: Long = System.nanoTime())

internal fun ApplicationCall.diagnosticRoute(): String = attributes.getOrNull(routeTemplateKey) ?: "-"
private val routeTemplateKey = AttributeKey<String>("finance.request.route-template")

internal val RequestDiagnostics = createApplicationPlugin("FinanceRequestDiagnostics") {
    val release = sequenceOf(System.getenv("FT_RELEASE"), System.getenv("RENDER_GIT_COMMIT"))
        .filterNotNull().firstOrNull(commitPattern::matches) ?: "unknown"

    application.intercept(ApplicationCallPipeline.Setup) {
        val request = RequestDiagnostic()
        call.attributes.put(requestDiagnosticKey, request)
        call.response.headers.append(REQUEST_ID_HEADER, request.id.toString())
    }
    on(MonitoringEvent(RoutingRoot.RoutingCallStarted)) { call ->
        call.attributes.put(routeTemplateKey, call.route.toString())
    }
    on(ResponseSent) { call ->
        val request = call.attributes.getOrNull(requestDiagnosticKey) ?: return@on
        val method = call.request.httpMethod.takeIf { it in HttpMethod.DefaultMethods }?.value ?: "OTHER"
        val elapsedMs = (System.nanoTime() - request.start) / 1_000_000
        serverDiagnosticLog.info("http_request request_id={} method={} route={} status={} duration_ms={} release={}",
            request.id, method, call.diagnosticRoute(), call.response.status()?.value, elapsedMs, release)
    }
}

internal val ApplicationCall.diagnosticRequestId: UUID?
    get() = attributes.getOrNull(requestDiagnosticKey)?.id

internal fun ApplicationCall.logUnexpectedRequestFailure(failure: Throwable) {
    serverDiagnosticLog.error("http_failure request_id={} route={} trace={}", diagnosticRequestId, diagnosticRoute(),
        generateSequence(failure) { it.cause }.take(4).joinToString(" <- ") { cause ->
            val frames = cause.stackTrace.take(32).joinToString(",") { frame ->
                "${frame.className}.${frame.methodName}:${frame.lineNumber}"
            }
            "${cause.javaClass.name}[$frames]"
        })
}
