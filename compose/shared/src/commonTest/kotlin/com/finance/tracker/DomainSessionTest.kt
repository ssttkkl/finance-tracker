package com.finance.tracker

import com.finance.tracker.app.*
import com.finance.tracker.core.*
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainSessionTest {
    @Test
    fun normalizesWorkspaceNameWithinExistingLimit() {
        assertEquals("Family ledger", normalizeDomainWorkspaceName("  Family ledger  "))
        assertNull(normalizeDomainWorkspaceName("   "))
        assertNull(normalizeDomainWorkspaceName("x".repeat(256)))
    }

    @Test
    fun workspaceSelectionPreservesExplicitRouteAndFallsBackToMembership() {
        val session = Session(
            user = User("user@example.com"),
            activeWorkspaceId = "workspace-1",
            workspaces = listOf(
                Workspace("workspace-1", "Home", WorkspaceRole.EDITOR),
                Workspace("workspace-2", "Travel", WorkspaceRole.VIEWER),
            ),
        )

        assertEquals("workspace-2", workspaceSelectionToRestore(session, "workspace-2"))
        assertNull(workspaceSelectionToRestore(session, "workspace-1"))
        assertNull(workspaceSelectionToRestore(session, "workspace-2", restoreRouteWorkspace = false))
        assertEquals(
            "workspace-1",
            workspaceSelectionToRestore(session.copy(activeWorkspaceId = "stale"), requestedWorkspaceId = null),
        )
    }

    @Test
    fun viewerCannotWriteAndEditorsCanWrite() {
        assertTrue(WorkspaceRole.ADMIN.canWrite)
        assertTrue(WorkspaceRole.EDITOR.canWrite)
        assertFalse(WorkspaceRole.VIEWER.canWrite)
    }
}
