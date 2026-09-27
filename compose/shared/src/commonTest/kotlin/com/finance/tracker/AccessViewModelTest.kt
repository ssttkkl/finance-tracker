package com.finance.tracker

import com.finance.tracker.app.*
import com.finance.tracker.core.*
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccessViewModelTest {
    @Test
    fun restoresSessionAndHonorsWorkspaceFromDeepLink() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeSessionRepository()
            val viewModel = AccessViewModel(repository)

            viewModel.initialize(requestedWorkspaceId = "workspace-2")
            advanceUntilIdle()

            assertEquals(AccessStatus.AUTHENTICATED, viewModel.state.value.status)
            assertEquals("workspace-2", viewModel.state.value.session?.activeWorkspaceId)
            assertEquals(listOf("workspace-2"), repository.selectedWorkspaceIds)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun keepsAuthenticationFailureAsSafeCodeAndDoesNotExposeExceptionText() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeSessionRepository().apply {
                loginFailure = DomainFailure("invalid_credentials", 401, FailureCategory.RECOVERABLE)
            }
            val viewModel = AccessViewModel(repository)

            viewModel.initialize()
            advanceUntilIdle()
            viewModel.authenticate("user@example.com", "not-the-password", registering = false)
            advanceUntilIdle()

            assertEquals(AccessStatus.SIGNED_OUT, viewModel.state.value.status)
            assertEquals("invalid_credentials", viewModel.state.value.errorCode)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun convertsUnexpectedAuthenticationFailuresToTheGenericUnknownCode() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeSessionRepository().apply {
                loginFailure = IllegalStateException("user@example.com token=secret")
            }
            val viewModel = AccessViewModel(repository)

            viewModel.initialize()
            advanceUntilIdle()
            viewModel.authenticate("user@example.com", "a-valid-password", registering = false)
            advanceUntilIdle()

            assertEquals("unknown_error", viewModel.state.value.errorCode)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun rejectsInvalidRegistrationInputBeforeCallingTheRepository() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeSessionRepository()
            val viewModel = AccessViewModel(repository)

            viewModel.initialize()
            advanceUntilIdle()
            viewModel.authenticate("invalid", "short", registering = true)
            advanceUntilIdle()

            assertEquals("auth_input_invalid_emailinvalid", viewModel.state.value.errorCode)
            assertEquals(0, repository.registerCalls)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun doesNotSurfaceNonRecoverableDomainCodesAsUserErrors() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeSessionRepository().apply {
                loginFailure = DomainFailure("backend_trace_123", 503, FailureCategory.UNKNOWN)
            }
            val viewModel = AccessViewModel(repository)

            viewModel.initialize()
            advanceUntilIdle()
            viewModel.authenticate("user@example.com", "a-valid-password", registering = false)
            advanceUntilIdle()

            assertEquals("unknown_error", viewModel.state.value.errorCode)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun recordsCredentialAndWorkspaceInputValidationWithoutInputValues() {
        val logger = RecordingDiagnosticLogger()
        val viewModel = AccessViewModel(FakeSessionRepository(), diagnostics = logger)

        viewModel.recordCredentialValidationFailure(CredentialValidation.EmailRequired)
        viewModel.recordWorkspaceNameValidationFailure()
        viewModel.recordCredentialValidationFailure(CredentialValidation.Valid)

        assertEquals(
            listOf(
                RecordedDiagnostic(
                    DiagnosticFeature.AUTHENTICATION,
                    DiagnosticAction.VALIDATE_INPUT,
                    "auth_input_invalid_emailrequired",
                    null,
                    "InputValidationFailure",
                ),
                RecordedDiagnostic(
                    DiagnosticFeature.WORKSPACE,
                    DiagnosticAction.VALIDATE_INPUT,
                    "invalid_workspace_name",
                    null,
                    "InputValidationFailure",
                ),
            ),
            logger.entries,
        )
    }
}

internal class FakeSessionRepository : SessionRepository {
    val selectedWorkspaceIds = mutableListOf<String>()
    var loginFailure: Throwable? = null
    var registerCalls = 0
    var invitationPreviewFailure: Throwable? = null
    var invitationAcceptFailure: Throwable? = null
    private val signedIn = Session(
        user = User("user@example.com"),
        activeWorkspaceId = "workspace-1",
        workspaces = listOf(
            Workspace("workspace-1", "Home", WorkspaceRole.EDITOR),
            Workspace("workspace-2", "Travel", WorkspaceRole.VIEWER),
        ),
    )

    override suspend fun hasStoredSessionToken(): Boolean = true
    override suspend fun restoreSession(): Session = signedIn
    override suspend fun session(): Session = signedIn
    override suspend fun login(email: String, password: String): Session {
        loginFailure?.let { throw it }
        return signedIn
    }
    override suspend fun register(email: String, password: String): Session {
        registerCalls += 1
        return signedIn
    }
    override suspend fun logout() = Unit
    override suspend fun selectWorkspace(id: String): Session {
        selectedWorkspaceIds += id
        return signedIn.copy(activeWorkspaceId = id)
    }
    override suspend fun createWorkspace(name: String): Session = signedIn
    override suspend fun invitationPreview(token: String): InvitationPreview {
        invitationPreviewFailure?.let { throw it }
        return InvitationPreview(
            workspace = InvitationWorkspace("Travel"),
            role = WorkspaceRole.VIEWER,
            valid = true,
        )
    }
    override suspend fun acceptInvitation(token: String): Session {
        invitationAcceptFailure?.let { throw it }
        return signedIn
    }
    override suspend fun workspaceDetails(): WorkspaceMembers = WorkspaceMembers(WorkspaceDetails("workspace-1", "Home"), emptyList())
    override suspend fun updateWorkspace(name: String): Session = signedIn
    override suspend fun deleteWorkspace(name: String): Session = signedIn
    override suspend fun invite(role: WorkspaceRole): String = "invite-token"
    override suspend fun members(): WorkspaceMembers = workspaceDetails()
    override suspend fun updateMember(id: String, role: WorkspaceRole) = Unit
    override suspend fun removeMember(id: String) = Unit
}
