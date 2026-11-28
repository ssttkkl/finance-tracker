package com.finance.tracker.presentation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistantMarkdownTest {
    @Test
    fun allowsBrowserLinksAndAnonymousHttpsImages() {
        assertTrue(assistantMarkdownWebUrl("https://example.org/report?month=11", httpsOnly = true))
        assertTrue(assistantMarkdownWebUrl("http://example.org/report", httpsOnly = false))
        assertFalse(assistantMarkdownWebUrl("http://example.org/file.png", httpsOnly = true))
        assertFalse(assistantMarkdownWebUrl("https://user:secret@example.org/file.png", httpsOnly = true))
        assertFalse(assistantMarkdownWebUrl("javascript:alert(1)", httpsOnly = false))
        assertFalse(assistantMarkdownWebUrl("file:///tmp/example.png", httpsOnly = true))
        assertFalse(assistantMarkdownWebUrl("//example.org/image.png", httpsOnly = true))
    }
}
