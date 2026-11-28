package com.finance.tracker.presentation

import com.finance.tracker.client.LedgerAssistantDelivery
import com.finance.tracker.client.LedgerAssistantDeliveryStatus
import com.finance.tracker.data.LedgerAssistantFile
import com.finance.tracker.data.LedgerAssistantMessage
import com.finance.tracker.data.LedgerAssistantToolResult
import com.finance.tracker.data.LedgerAssistantToolStatus
import com.finance.tracker.data.LedgerAssistantToolTrace
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class AssistantReplyCopyTest {
    @Test
    fun rawMarkdownAppearsOnceAfterEachCompletedTurnWithText() {
        val rows = listOf(
            listOf(delivery("first")),
            listOf(LedgerAssistantMessage(reply = "**第一段**")),
            listOf(LedgerAssistantMessage(result = LedgerAssistantToolResult("list_accounts", JsonObject(emptyMap())))),
            listOf(LedgerAssistantMessage(reply = "[第二段](https://example.org)")),
            listOf(LedgerAssistantMessage(attachment = LedgerAssistantFile("a", "report.csv", "text/csv", 51, attached = true))),
            listOf(delivery("second", LedgerAssistantDeliveryStatus.SENDING)),
            listOf(LedgerAssistantMessage(reply = "流式文字")),
        )
        assertEquals(mapOf(4 to AssistantReplyCopy("**第一段**\n\n[第二段](https://example.org)", null)), assistantReplyCopies(rows, latestPending = false))
        val completed = rows.toMutableList().apply { this[5] = listOf(delivery("second")) }
        assertEquals(mapOf(4 to AssistantReplyCopy("**第一段**\n\n[第二段](https://example.org)", null), 6 to AssistantReplyCopy("流式文字", null)),
            assistantReplyCopies(completed, latestPending = false))
        assertEquals(mapOf(4 to AssistantReplyCopy("**第一段**\n\n[第二段](https://example.org)", null)),
            assistantReplyCopies(completed, latestPending = true))
    }

    @Test
    fun missingBodyOrPendingToolDoesNotOfferCopy() {
        val rows = listOf(
            listOf(delivery("empty")),
            listOf(LedgerAssistantMessage(reply = "  ")),
            listOf(delivery("draft")),
            listOf(LedgerAssistantMessage(reply = "请核对")),
            listOf(LedgerAssistantMessage(toolTrace = LedgerAssistantToolTrace(
                "create_transaction", JsonObject(emptyMap()), LedgerAssistantToolStatus.WAITING,
            ))),
        )
        assertEquals(emptyMap(), assistantReplyCopies(rows, latestPending = false))
    }

    @Test
    fun yesterdayTakesPriorityAcrossNewYear() {
        assertEquals(AssistantReplyDay.YESTERDAY, assistantReplyDay("2025-12-31", "2026-01-01"))
        assertEquals(AssistantReplyDay.YESTERDAY, assistantReplyDay("2024-02-29", "2024-03-01"))
        assertEquals(AssistantReplyDay.TODAY, assistantReplyDay("2026-01-01", "2026-01-01"))
        assertEquals(AssistantReplyDay.THIS_YEAR, assistantReplyDay("2026-01-01", "2026-02-01"))
        assertEquals(AssistantReplyDay.OTHER_YEAR, assistantReplyDay("2025-12-30", "2026-01-01"))
    }

    private fun delivery(id: String, status: LedgerAssistantDeliveryStatus = LedgerAssistantDeliveryStatus.DELIVERED) =
        LedgerAssistantMessage(delivery = LedgerAssistantDelivery(id, "用户文本", status = status))
}
