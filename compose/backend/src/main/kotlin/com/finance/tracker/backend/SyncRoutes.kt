package com.finance.tracker.backend

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*

internal fun Route.syncRoutes(service: SyncHttpService, principalKey: AttributeKey<Principal>) {
    post("/api/v1/operations/sync") {
        suspend fun failure(code: String, status: Int, message: String) = call.respond(HttpStatusCode.fromValue(status), buildJsonObject {
            put("error", buildJsonObject { put("code", code); put("message", message) })
        })
        try {
            val principal = call.attributes[principalKey]
            service.requireAdmin(principal)
            val request = service.parse(call.receive<ByteArray>())
            val token = bearerToken(call.request.headers[HttpHeaders.Authorization])
            val result = runInterruptible(Dispatchers.IO) { service.sync(token, principal, request) }
            call.respondText(result.toString(), ContentType.Application.Json)
        } catch (error: SyncConfigurationFailure) { failure("sync_configuration_invalid", 400, "同步配置无效，请联系管理员。") }
        catch (error: SyncHttpFailure) { failure(error.code, error.status, error.message.orEmpty()) }
        catch (error: AccessFailure) { throw error }
        catch (error: StorageFailure) { throw error }
        catch (error: CancellationException) { throw error }
        catch (error: InterruptedException) { Thread.currentThread().interrupt(); throw error }
        catch (_: Exception) { failure("sync_failed", 502, "同步失败，请检查服务端连接配置。") }
    }
}
