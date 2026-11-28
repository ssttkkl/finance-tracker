package com.finance.tracker.data

import kotlin.test.Test
import kotlin.test.assertEquals

class AssistantConversationFilesTest {
    @Test
    fun sentFilesUseReverseMessageOrderAndKeepNamesAndExpiredRecords() {
        val original = LedgerAssistantFile("old", "report.csv", "text/csv", 1, attached = true)
        val latest = original.copy(id = "new", expiresAtMs = 500)
        val messages = listOf(
            LedgerAssistantMessage(attachment = original),
            LedgerAssistantMessage(attachment = original.copy(id = "temporary", attached = false)),
            LedgerAssistantMessage(reply = "The file is ready."),
            LedgerAssistantMessage(attachment = latest),
        )
        assertEquals(listOf(latest, original), sentAssistantConversationFiles(messages))
        assertEquals(emptyList(), sentAssistantConversationFiles(emptyList()))
    }
}
