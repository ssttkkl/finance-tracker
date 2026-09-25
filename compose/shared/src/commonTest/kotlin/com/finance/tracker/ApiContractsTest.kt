package com.finance.tracker

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiContractsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesSnakeCaseSessionAndRoleContracts() {
        val session = json.decodeFromString<SessionDto>("""
            {"user":{"email":"owner@example.com"},"active_workspace_id":"w-1","workspaces":[{"id":"w-1","name":"账本","role":"editor"}]}
        """.trimIndent())

        assertEquals("owner@example.com", session.user.email)
        assertEquals("w-1", session.activeWorkspaceId)
        assertEquals(Role.EDITOR, session.workspaces.single().role)
        assertTrue(Role.EDITOR.canWrite)
        assertFalse(Role.VIEWER.canWrite)
    }

    @Test
    fun decodesAuthInvitationAndMemberFixtures() {
        val auth = json.decodeFromString<AuthResponseDto>("""
            {"access_token":"token-fixture","user":{"email":"owner@example.com"},"active_workspace_id":null,"workspaces":[]}
        """.trimIndent())
        val invitation = json.decodeFromString<InvitationPreviewDto>("""
            {"workspace":{"name":"共同账本"},"role":"viewer","valid":true}
        """.trimIndent())
        val member = json.decodeFromString<MemberDto>("""
            {"user_id":"user-1","email":"viewer@example.com","role":"viewer","is_self":false}
        """.trimIndent())

        assertEquals("token-fixture", auth.accessToken)
        assertEquals("共同账本", invitation.workspace.name)
        assertEquals(Role.VIEWER, invitation.role)
        assertFalse(member.isSelf)
    }

    @Test
    fun mapsApiErrorCodesWithoutRetainingMessagesOrBodies() {
        assertEquals("workspace_forbidden", apiErrorCode(403, """{"error":{"code":"workspace_forbidden","message":"private"}}"""))
        assertEquals("conflict", apiErrorCode(409, """{"code":"conflict"}"""))
        assertEquals("authentication_required", apiErrorCode(401, "not-json"))
        assertEquals("request_failed", apiErrorCode(500, "not-json"))
        assertNull(apiErrorCode(200, "{}"))
    }

    @Test
    fun validatesEmailPasswordAndWorkspaceNameInputs() {
        assertEquals(CredentialValidation.Valid, validateCredentials(" owner@example.com ", "long-password"))
        assertEquals(CredentialValidation.EmailInvalid, validateCredentials("bad-address", "long-password"))
        assertEquals(CredentialValidation.PasswordTooShort, validateCredentials("owner@example.com", "short"))
        assertEquals("个人账本", normalizeWorkspaceName(" 个人账本 "))
        assertNull(normalizeWorkspaceName("  "))
        assertNull(normalizeWorkspaceName("x".repeat(256)))
    }

    @Test
    fun restoresRequestedWorkspaceOrFirstAvailableWorkspace() {
        val session = SessionDto(
            user = UserDto("owner@example.com"),
            activeWorkspaceId = null,
            workspaces = listOf(WorkspaceDto("w-1", "第一本", Role.ADMIN), WorkspaceDto("w-2", "第二本", Role.VIEWER)),
        )
        assertEquals("w-2", workspaceSelectionForSession(session, "w-2"))
        assertEquals("w-1", workspaceSelectionForSession(session, null))
        assertEquals(null, workspaceSelectionForSession(session.copy(activeWorkspaceId = "w-2"), null))
        assertEquals("w-1", workspaceSelectionForSession(session.copy(activeWorkspaceId = "missing"), null))
    }

    @Test
    fun manualWorkspaceSelectionDoesNotRestoreAStaleRouteWorkspace() {
        val selectedSession = SessionDto(
            user = UserDto("viewer@example.com"),
            activeWorkspaceId = "w-2",
            workspaces = listOf(WorkspaceDto("w-1", "第一本", Role.ADMIN), WorkspaceDto("w-2", "第二本", Role.VIEWER)),
        )

        assertEquals("w-1", workspaceSelectionForSession(selectedSession, "w-1"))
        assertNull(workspaceSelectionForSession(selectedSession, "workspace-created", restoreRequestedWorkspace = false))
    }

    @Test
    fun authTabFocusMovesForwardAndBackwardThroughControls() {
        assertEquals(AuthFocusControl.EMAIL, nextAuthFocusControl(null, backwards = false))
        assertEquals(AuthFocusControl.PASSWORD, nextAuthFocusControl(AuthFocusControl.EMAIL, backwards = false))
        assertEquals(AuthFocusControl.SUBMIT, nextAuthFocusControl(AuthFocusControl.PASSWORD, backwards = false))
        assertEquals(AuthFocusControl.TOGGLE_MODE, nextAuthFocusControl(AuthFocusControl.SUBMIT, backwards = false))
        assertEquals(AuthFocusControl.TOGGLE_MODE, nextAuthFocusControl(null, backwards = true))
        assertEquals(AuthFocusControl.SUBMIT, nextAuthFocusControl(AuthFocusControl.TOGGLE_MODE, backwards = true))
        assertEquals(AuthFocusControl.PASSWORD, nextAuthFocusControl(AuthFocusControl.SUBMIT, backwards = true))
        assertEquals(AuthFocusControl.EMAIL, nextAuthFocusControl(AuthFocusControl.PASSWORD, backwards = true))
    }

    @Test
    fun validatesDecimalStringsAndConservesAllocationsExactly() {
        assertTrue(isExactDecimalString("-12345678901234567890.00000001"))
        assertFalse(isExactDecimalString("1e3"))
        assertFalse(isExactDecimalString(".5"))
        assertEquals(AllocationBalance("complete", "0.00", "100.00"), allocationBalance("100.00", listOf("0.1", "99.90")))
        assertEquals(AllocationBalance("incomplete", "0.01", "10.00"), allocationBalance("10.00", listOf("1.00", "8.99")))
        assertEquals("invalid", allocationBalance("10", listOf("-2", "12")).state)
    }

    @Test
    fun keepsMigratedSemanticIdsStable() {
        assertEquals("auth.email", SemanticIds.authEmail)
        assertEquals("ledger.record", SemanticIds.ledgerRecord)
        assertEquals("record.counterparty", SemanticIds.recordCounterparty)
        assertEquals("record.note", SemanticIds.recordNote)
        assertEquals("record.type", SemanticIds.recordType)
        assertEquals("import.confirm", SemanticIds.importConfirm)
        assertEquals("cash-category.name", SemanticIds.cashCategoryName)
        assertEquals("workspace.management.invite", SemanticIds.workspaceManagementInvite)
        assertEquals("workspace-management.name", SemanticIds.workspaceManagementName)
        assertEquals("workspace-management.invite-link", SemanticIds.workspaceManagementInviteLink)
        assertEquals("investment.event.detail", SemanticIds.investmentEventDetail)
        assertEquals("investment-events.load-more", SemanticIds.investmentEventsLoadMore)
        assertEquals("investment.event.detail.close", SemanticIds.investmentEventDetailClose)
    }

    @Test
    fun importsUseSha1ContentIdentityAndWebContractFileLimits() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", sha1Hex(byteArrayOf()))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1Hex("abc".encodeToByteArray()))
        assertEquals(ImportFileValidation.Valid, validateImportFile("statement.csv", 100L * 1024 * 1024))
        assertEquals(ImportFileValidation.UnsupportedType, validateImportFile("statement.txt", 12))
        assertEquals(ImportFileValidation.TooLarge, validateImportFile("statement.pdf", 100L * 1024 * 1024 + 1))
        assertEquals(ImportFileValidation.SizeUnavailable, validateImportFile("statement.pdf", -1))
    }

    @Test
    fun capsImportFileCountAndDeduplicatesIdenticalContent() {
        val original = (1..MAX_IMPORT_FILES).map { ImportFileIdentity("$it.pdf", "digest-$it", 1) }
        assertEquals(ImportFileSelection(original, emptyList()), addImportFiles(original, listOf(ImportFileIdentity("duplicate.pdf", "digest-1", 1))))
        val result = addImportFiles(original, listOf(ImportFileIdentity("extra.pdf", "new-digest", 1)))
        assertEquals(MAX_IMPORT_FILES, result.files.size)
        assertEquals(listOf(ImportFileValidation.TooManyFiles), result.rejections)
    }
}
