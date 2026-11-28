package com.finance.tracker.client

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LedgerAssistantDeliveryTest {
    @Test
    fun interruptedCancellationKeepsOriginalInputAndRequiresExplicitRetry() {
        val original = LedgerAssistantDelivery("message-1", "  改成 36 元\n", "tool-1")
        val interrupted = Json.encodeToString(original.retry())
        val reloaded = Json.decodeFromString<LedgerAssistantDelivery>(interrupted).interrupted()
        assertEquals(LedgerAssistantDeliveryStatus.NOT_DELIVERED, reloaded.status)
        assertEquals("tool-1", reloaded.cancelledToolCallId)
        val retry = reloaded.retry()
        assertEquals(original.messageId, retry.messageId)
        assertEquals(original.input, retry.input)
        assertEquals(original.cancelledToolCallId, retry.cancelledToolCallId)
        assertEquals(LedgerAssistantDeliveryStatus.SENDING, retry.status)
        assertEquals(reloaded.copy(attempt = retry.attempt), retry.interrupted())
        assertFailsWith<IllegalStateException> { retry.retry() }
    }

    @Test
    fun retriesHaveDistinctAttemptNumbersAndIgnoreLateResponses() {
        val first = LedgerAssistantDelivery("message-1", "重试前的消息").retry()
        val second = first.interrupted().retry()
        assertEquals(first.attempt + 1, second.attempt)
        assertNull(second.delivered(first.attempt))
        assertEquals(LedgerAssistantDeliveryStatus.DELIVERED, second.delivered(second.attempt)?.status)
    }
}
