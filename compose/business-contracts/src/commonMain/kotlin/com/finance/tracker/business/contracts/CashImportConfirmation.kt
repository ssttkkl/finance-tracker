package com.finance.tracker.business.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CashImportConfirmationStatus {
    @SerialName("accepted") ACCEPTED,
    @SerialName("running") RUNNING,
    @SerialName("cancel_requested") CANCEL_REQUESTED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("rejected") REJECTED,
    @SerialName("committed") COMMITTED,
}

@Serializable
data class CashImportConfirmation(
    @SerialName("operation_id") val operationId: String,
    val status: CashImportConfirmationStatus,
    @SerialName("receipt_id") val receiptId: String? = null,
    val rejection: CashImportConfirmationRejection? = null,
)

@Serializable
data class CashImportConfirmationRejection(val code: String, @SerialName("http_status") val httpStatus: Int)
