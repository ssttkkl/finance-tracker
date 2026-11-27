package com.finance.tracker.backend

import com.finance.tracker.business.contracts.CashImportConfirmation
import com.finance.tracker.business.contracts.CashImportConfirmationStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

internal suspend fun HttpClient.awaitCashImport(
    acceptance: HttpResponse, workspace: String, token: String, key: String,
): CashImportConfirmation {
    assertTrue(
        acceptance.status == HttpStatusCode.Accepted || acceptance.status == HttpStatusCode.OK,
        acceptance.bodyAsText(),
    )
    var operation = Json.decodeFromString<CashImportConfirmation>(acceptance.bodyAsText())
    val pending = setOf(CashImportConfirmationStatus.ACCEPTED, CashImportConfirmationStatus.RUNNING,
        CashImportConfirmationStatus.CANCEL_REQUESTED)
    assertEquals(key, operation.operationId)
    assertEquals(if (operation.status in pending) HttpStatusCode.Accepted else HttpStatusCode.OK, acceptance.status)
    return withTimeout(15_000) {
        while (operation.status in pending) {
            delay(25)
            val response = get("/api/v1/cash-import/write-set/status?workspace_id=$workspace") {
                bearerAuth(token); header("Idempotency-Key", key)
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            operation = Json.decodeFromString<CashImportConfirmation>(response.bodyAsText())
            assertEquals(key, operation.operationId)
        }
        operation
    }
}

internal suspend fun HttpClient.originalCashImportReceipt(
    operation: CashImportConfirmation, workspace: String, token: String, acceptEncoding: String = "identity",
): HttpResponse {
    assertEquals(CashImportConfirmationStatus.COMMITTED, operation.status, operation.toString())
    return get("/api/v1/cash-import/write-set/receipt?workspace_id=$workspace") {
        bearerAuth(token); header("Idempotency-Key", operation.operationId)
        header(HttpHeaders.AcceptEncoding, acceptEncoding)
    }
}
