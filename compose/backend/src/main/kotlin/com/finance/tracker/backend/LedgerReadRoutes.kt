package com.finance.tracker.backend

import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal fun Route.ledgerReadRoutes(service: LedgerReadService, principalKey: AttributeKey<Principal>) {
    get("/api/v1/ledger/version") {
        val token = bearerToken(call.request.headers[HttpHeaders.Authorization])
        val request = call.attributes[principalKey]
        val expectedWorkspace = call.request.queryParameters["workspace_id"]
        if (expectedWorkspace != null && expectedWorkspace != request.workspaceId) throw AccessFailure(true)
        val result = runInterruptible(Dispatchers.IO) { service.version(token, request) }
        call.respond(result)
    }
    get("/api/v1/ledger/snapshot") {
        val token = bearerToken(call.request.headers[HttpHeaders.Authorization])
        val request = call.attributes[principalKey]
        val expectedWorkspace = call.request.queryParameters["workspace_id"]
        if (expectedWorkspace != null && expectedWorkspace != request.workspaceId) throw AccessFailure(true)
        val result = runInterruptible(Dispatchers.IO) { service.snapshot(token, request) }
        call.respond(result)
    }

}
