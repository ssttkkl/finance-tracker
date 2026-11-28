package com.finance.tracker.client

import kotlinx.serialization.Serializable

@Serializable
enum class LedgerAssistantDeliveryStatus { WAITING_FOR_RECORD, NOT_DELIVERED, SENDING, DELIVERED }

@Serializable
data class LedgerAssistantDelivery(
    val messageId: String,
    val input: String,
    val cancelledToolCallId: String? = null,
    val status: LedgerAssistantDeliveryStatus = LedgerAssistantDeliveryStatus.NOT_DELIVERED,
    val attempt: Long = 0,
) {
    fun retry(): LedgerAssistantDelivery {
        check(status == LedgerAssistantDeliveryStatus.NOT_DELIVERED || status == LedgerAssistantDeliveryStatus.WAITING_FOR_RECORD)
        check(attempt < Long.MAX_VALUE)
        return copy(status = LedgerAssistantDeliveryStatus.SENDING, attempt = attempt + 1)
    }

    fun interrupted(): LedgerAssistantDelivery =
        if (status == LedgerAssistantDeliveryStatus.SENDING) {
            copy(status = LedgerAssistantDeliveryStatus.NOT_DELIVERED)
        } else {
            this
        }

    fun delivered(responseAttempt: Long): LedgerAssistantDelivery? =
        if (status == LedgerAssistantDeliveryStatus.SENDING && attempt == responseAttempt) {
            copy(status = LedgerAssistantDeliveryStatus.DELIVERED)
        } else {
            null
        }

    override fun toString(): String =
        "LedgerAssistantDelivery(messageId=$messageId, status=$status, attempt=$attempt)"
}
