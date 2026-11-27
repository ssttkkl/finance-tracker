package com.finance.tracker.data

import com.finance.tracker.client.ClientCashMaintenanceOperations
import com.finance.tracker.client.ClientCashWorkspaceTransport
import com.finance.tracker.client.CashAssociationQuery
import com.finance.tracker.client.ClientOperationFailure
import com.finance.tracker.client.freezeMaintenanceCommand
import kotlinx.serialization.DeserializationStrategy
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.*

private const val MAINTENANCE = "/api/v1/cash-mutations"

suspend fun FinanceApiClient.readCashAssociationTargets(transactionId: String, search: com.finance.tracker.domain.CashAssociationSearch): CashAssociationPageDto =
    cashMaintenance {
        associationTargets(expectedWorkspace, transactionId, CashAssociationQuery(search.query,
            com.finance.tracker.core.getLocalTimeZoneId(), search.kind, search.amount, search.accountId,
            search.dateFrom, search.dateTo, search.cursor))
    }

suspend fun FinanceApiClient.readCurrentCashRecord(transactionId: String): CashCurrentRecordDto =
    request(HttpMethod.Get, "/api/v1/cash-records/${encodePathSegment(transactionId)}")

suspend fun FinanceApiClient.saveCashDraft(transactionId: String, revision: Long, patch: JsonObject, key: String): CashDraftSaveDto {
    requireCashRevision(revision)
    return request(HttpMethod.Patch, "/api/v1/cash-records/${encodePathSegment(transactionId)}/draft",
        buildJsonObject { put("expected_revision", revision); put("patch", patch) }, cashKeyHeaders(key))
}

suspend fun FinanceApiClient.previewCashMaintenance(input: CashMaintenanceInput): CashMaintenancePlanDto =
    cashMaintenance { preview(expectedWorkspace, input) }

suspend fun FinanceApiClient.readCashMaintenancePlan(planId: String): CashMaintenancePlanDto =
    cashMaintenance { plan(expectedWorkspace, planId) }

suspend fun FinanceApiClient.readCurrentCashMaintenancePlan(contextId: String): CashMaintenancePlanDto =
    request(HttpMethod.Get, "$MAINTENANCE/edit-contexts/${encodePathSegment(contextId)}/plan")

suspend fun FinanceApiClient.readCashMaintenanceItems(planId: String, cursor: String? = null, limit: Int = 50, confirmationStatus: String = "all"): CashPlanItemPageDto {
    if (confirmationStatus !in setOf("all", "confirmed", "pending")) invalidCashInput()
    return request(HttpMethod.Get, appendQuery(maintenancePlanPath(planId) + "/items", cashPageQuery(cursor, limit) + ("confirmation_status" to confirmationStatus)))
}
suspend fun FinanceApiClient.readCashMaintenanceItem(planId: String, itemId: String): CashPlanItemDetailDto =
    request(HttpMethod.Get, maintenancePlanPath(planId) + "/items/${encodePathSegment(itemId)}")

suspend fun FinanceApiClient.commitCashMaintenance(planId: String, revision: Long, key: String): CashMutationReceiptDto =
    executeFixedCashCommand(freezeMaintenanceCommand(planId, revision, key, ::encodePathSegment))
private fun maintenancePlanPath(id: String) = "$MAINTENANCE/plans/${encodePathSegment(id)}"


internal suspend fun <T> FinanceApiClient.cashMaintenance(action: suspend ClientCashMaintenanceOperations.() -> T): T {
    val api = this
    val transport = object : ClientCashWorkspaceTransport {
        override suspend fun <R> request(
            expected: String?, method: String, path: String, body: JsonObject?, write: Boolean, mutates: Boolean,
            response: DeserializationStrategy<R>,
        ): R {
            check(expected == api.expectedWorkspace) { "workspace_header_override" }
            val credentials = api.boundCredentials ?: api.captureCredentials()
            val origin = normalizeApiOrigin(credentials.apiOrigin, api.allowInsecureHttp)
            return api.exchange(HttpMethod.parse(method), path, origin, credentials.token, body ?: NoRequestBody,
                emptyMap(), preserveHttpRejectionOnBodyFailure = true) { status, text ->
                if (status !in 200..299) api.rejectApiPayload(status, text, credentials.token, clearUnauthorizedToken = true)
                try {
                    apiJson.decodeFromString(response, text)
                } catch (failure: kotlinx.serialization.SerializationException) {
                    throw ApiFailure("request_failed", status, cause = failure)
                }
            }
        }
    }
    return try {
        ClientCashMaintenanceOperations(transport, ::encodePathSegment).action()
    } catch (failure: ClientOperationFailure) {
        throw ApiFailure(failure.code, failure.status ?: 0, commitState = failure.commitState, cause = failure)
    }
}
