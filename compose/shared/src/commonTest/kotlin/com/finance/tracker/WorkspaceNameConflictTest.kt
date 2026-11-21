package com.finance.tracker

import com.finance.tracker.presentation.hasSameWorkspaceName
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceNameConflictTest {
    @Test
    fun matchesTrimmedNamesIgnoringLatinCase() {
        assertTrue(hasSameWorkspaceName("家庭", listOf("  家庭  ")))
        assertTrue(hasSameWorkspaceName("Family", listOf("family")))
        assertTrue(hasSameWorkspaceName("Family", listOf("FAMILY")))
    }

    @Test
    fun keepsDifferentNamesAndEmptyList() {
        assertFalse(hasSameWorkspaceName("家庭", listOf("工作")))
        assertFalse(hasSameWorkspaceName("家庭", emptyList()))
    }
}
