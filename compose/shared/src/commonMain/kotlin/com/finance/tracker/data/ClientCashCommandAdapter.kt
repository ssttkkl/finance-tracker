package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.client.ClientNetworkUnavailable
import com.finance.tracker.client.ClientOperationFailure
import com.finance.tracker.client.ClientPendingCommand
import com.finance.tracker.client.executeCashCommand
import io.ktor.http.HttpMethod

internal suspend fun FinanceApiClient.executeFixedCashCommand(
    command: ClientPendingCommand, lookupFirst: Boolean = false,
): CashMutationReceiptDto = executeCashCommand(command, lookupFirst,
    findReceipt = { originalKey ->
        commandRequest {
            try {
                readCashMutationReceiptByKey(originalKey)
            } catch (failure: ApiFailure) {
                if (failure.status == 404 && failure.code == "receipt_not_found") null else throw failure
            }
        }
    },
    submit = { original ->
        commandRequest {
            val headers = buildMap {
                put("Idempotency-Key", original.id)
                if (original.accountIds.isNotEmpty()) put("X-FT-Account-IDs", original.accountIds.joinToString(","))
            }
            request<CashMutationReceiptDto>(HttpMethod.parse(original.method), original.path, FrozenJsonBody(original.body),
                extraHeaders = headers, preserveHttpRejectionOnBodyFailure = true, requireResponseBody = true)
        }
    }).receipt

private suspend fun <T> commandRequest(request: suspend () -> T): T = try {
    request()
} catch (failure: ApiFailure) {
    if (failure.status in setOf(401, 403)) throw failure
    val code = if (failure.status in 200..299) "protocol_error" else failure.code
    throw ClientOperationFailure(code, failure.status, failure, failure.commitState)
} catch (failure: FinanceTransportFailure) {
    throw ClientNetworkUnavailable(failure)
}
