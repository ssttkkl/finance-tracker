package com.finance.tracker

import com.finance.tracker.domain.*
import com.finance.tracker.presentation.WorkspaceManagementViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceManagementViewModelTest {
    @Test
    fun loadsUpdatesMembersAndCreatesRoleSpecificInviteLink() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeWorkspaceRepository()
            val viewModel = WorkspaceManagementViewModel(repository)
            viewModel.load()
            advanceUntilIdle()
            assertEquals("Workspace", viewModel.state.value.details?.workspace?.name)

            viewModel.updateName("New workspace")
            viewModel.saveName(canManage = true)
            advanceUntilIdle()
            assertEquals("New workspace", repository.updatedName)
            assertEquals("New workspace", viewModel.state.value.updatedSession?.workspaces?.first()?.name)
            assertEquals("New workspace", viewModel.state.value.details?.workspace?.name)

            viewModel.setInvitationRole(WorkspaceRole.VIEWER)
            repeat(2) { viewModel.createInvitation(canManage = true, webOrigin = "https://example.test", currentPath = "/w/workspace-1/") }
            viewModel.setInvitationRole(WorkspaceRole.EDITOR)
            advanceUntilIdle()
            assertEquals(1, repository.invitationCalls)
            assertEquals(WorkspaceRole.VIEWER, repository.invitedRole)
            assertEquals(WorkspaceRole.VIEWER, viewModel.state.value.generatedInvitation?.role)
            assertEquals(true, viewModel.state.value.invitationLink.contains("invite=invite-token"))

            viewModel.updateMember("member-1", WorkspaceRole.EDITOR, canManage = true)
            advanceUntilIdle()
            assertEquals(WorkspaceRole.EDITOR, repository.memberRole)
            assertEquals(WorkspaceRole.EDITOR, viewModel.state.value.details?.members?.first { !it.isSelf }?.role)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun deleteRequiresExactWorkspaceNameAndAdminPermission() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeWorkspaceRepository()
            val viewModel = WorkspaceManagementViewModel(repository)
            viewModel.load()
            advanceUntilIdle()
            viewModel.openDeleteDialog()
            viewModel.updateDeleteName("workspace")
            viewModel.deleteWorkspace(canManage = true)
            advanceUntilIdle()
            assertNull(repository.deletedName)

            viewModel.updateDeleteName("Workspace")
            viewModel.deleteWorkspace(canManage = false)
            advanceUntilIdle()
            assertNull(repository.deletedName)

            viewModel.deleteWorkspace(canManage = true)
            advanceUntilIdle()
            assertEquals("Workspace", repository.deletedName)
            assertEquals("workspace-2", viewModel.state.value.deletedSession?.activeWorkspaceId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun missingDeleteResponseRefreshesTheOriginalWorkspaceWithoutRepeatingTheWrite() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val base = FakeWorkspaceRepository()
            var writes = 0
            var sessionReads = 0
            val repository = object : SessionRepository by base {
                override suspend fun deleteWorkspace(name: String): Session {
                    writes++
                    throw DomainFailure("network_unavailable", 0, FailureCategory.RECOVERABLE)
                }
                override suspend fun session(): Session {
                    sessionReads++
                    return base.session()
                }
            }
            val model = WorkspaceManagementViewModel(repository)
            model.load()
            advanceUntilIdle()
            model.openDeleteDialog()
            model.updateDeleteName("Workspace")
            model.deleteWorkspace(canManage = true)
            advanceUntilIdle()
            assertEquals(1, writes)
            assertEquals(1, sessionReads)
            assertFalse(model.state.value.deleteDialogOpen)
            assertNull(model.state.value.deletedSession)
            assertEquals("action_current_state", model.state.value.deleteFailure?.code)
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class FakeWorkspaceRepository : SessionRepository {
    var updatedName: String? = null
    var deletedName: String? = null
    var invitationCalls = 0
    var invitedRole: WorkspaceRole? = null
    var memberRole = WorkspaceRole.VIEWER
    private var workspaceName = "Workspace"

    private val member = WorkspaceMember("member-1", "member@example.test", memberRole, isSelf = false)
    private val owner = WorkspaceMember("owner-1", "owner@example.test", WorkspaceRole.ADMIN, isSelf = true)

    override suspend fun hasStoredSessionToken() = true
    override suspend fun restoreSession() = session()
    override suspend fun session() = Session(
        User("owner@example.test"),
        "workspace-1",
        listOf(Workspace("workspace-1", workspaceName, WorkspaceRole.ADMIN), Workspace("workspace-2", "Other", WorkspaceRole.EDITOR)),
    )
    override suspend fun login(email: String, password: String) = session()
    override suspend fun register(email: String, password: String) = session()
    override suspend fun logout() = Unit
    override suspend fun selectWorkspace(id: String) = session().copy(activeWorkspaceId = id)
    override suspend fun createWorkspace(name: String) = session()
    override suspend fun invitationPreview(token: String) = InvitationPreview(InvitationWorkspace(workspaceName), WorkspaceRole.EDITOR, true)
    override suspend fun acceptInvitation(token: String) = session()
    override suspend fun workspaceDetails() = WorkspaceMembers(WorkspaceDetails("workspace-1", workspaceName), listOf(owner, member.copy(role = memberRole)))
    override suspend fun updateWorkspace(name: String): Session {
        updatedName = name
        workspaceName = name
        return session()
    }
    override suspend fun deleteWorkspace(name: String): Session {
        deletedName = name
        return session().copy(activeWorkspaceId = "workspace-2")
    }
    override suspend fun invite(role: WorkspaceRole): String {
        invitationCalls++
        invitedRole = role
        return "invite-token"
    }
    override suspend fun members() = workspaceDetails()
    override suspend fun updateMember(id: String, role: WorkspaceRole) {
        memberRole = role
    }
    override suspend fun removeMember(id: String) = Unit
}
