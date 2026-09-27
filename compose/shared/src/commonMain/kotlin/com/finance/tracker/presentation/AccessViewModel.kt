package com.finance.tracker.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finance.tracker.core.DiagnosticAction
import com.finance.tracker.core.DiagnosticFeature
import com.finance.tracker.core.DiagnosticLogger
import com.finance.tracker.core.NoOpDiagnosticLogger
import com.finance.tracker.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AccessStatus {
    LOADING,
    SIGNED_OUT,
    WORKSPACE,
    AUTHENTICATED,
}

data class AccessUiState(
    val status: AccessStatus = AccessStatus.LOADING,
    val session: Session? = null,
    val busy: Boolean = false,
    val errorCode: String? = null,
)

class AccessViewModel(
    private val repository: SessionRepository,
    private val authenticateUser: AuthenticateUserUseCase = AuthenticateUserUseCase(repository),
    private val createWorkspaceUseCase: CreateWorkspaceUseCase = CreateWorkspaceUseCase(repository),
    private val diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AccessUiState())
    val state: StateFlow<AccessUiState> = mutableState.asStateFlow()
    private var initialized = false

    fun initialize(requestedWorkspaceId: String? = null) {
        if (initialized) return
        initialized = true
        mutableState.value = AccessUiState(status = AccessStatus.LOADING, busy = true)
        viewModelScope.launch {
            try {
                if (!repository.hasStoredSessionToken()) {
                    mutableState.value = AccessUiState(status = AccessStatus.SIGNED_OUT)
                } else {
                    resolveSession(repository.restoreSession(), requestedWorkspaceId)
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.AUTHENTICATION, DiagnosticAction.RESTORE_SESSION)
                mutableState.value = AccessUiState(
                    status = AccessStatus.SIGNED_OUT,
                    errorCode = cause.safeErrorCode(),
                )
            }
        }
    }

    fun authenticate(email: String, password: String, registering: Boolean, requestedWorkspaceId: String? = null) {
        mutableState.value = mutableState.value.copy(
            status = AccessStatus.SIGNED_OUT,
            session = null,
            errorCode = null,
        )
        launchBusy(DiagnosticAction.AUTHENTICATE) {
            resolveSession(authenticateUser(email, password, registering), requestedWorkspaceId)
        }
    }

    fun recordCredentialValidationFailure(validation: CredentialValidation) {
        if (validation == CredentialValidation.Valid) return
        diagnostics.recordInputValidationFailure(
            DiagnosticFeature.AUTHENTICATION,
            "auth_input_invalid_${validation.name.lowercase()}",
        )
    }

    fun recordWorkspaceNameValidationFailure() {
        diagnostics.recordInputValidationFailure(DiagnosticFeature.WORKSPACE, "invalid_workspace_name")
    }

    fun selectWorkspace(id: String) {
        launchBusy(DiagnosticAction.SELECT_WORKSPACE) { resolveSession(repository.selectWorkspace(id), requestedWorkspaceId = null) }
    }

    fun createWorkspace(name: String) {
        launchBusy(DiagnosticAction.CREATE_WORKSPACE) { resolveSession(createWorkspaceUseCase(name), requestedWorkspaceId = null) }
    }

    fun retrySession(requestedWorkspaceId: String? = null) {
        launchBusy(DiagnosticAction.RESTORE_SESSION) { resolveSession(repository.session(), requestedWorkspaceId) }
    }

    fun signOut() {
        launchBusy(DiagnosticAction.SIGN_OUT) {
            try {
                repository.logout()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.AUTHENTICATION, DiagnosticAction.SIGN_OUT)
                // The API client clears the local token even when the remote sign-out fails.
            }
            mutableState.value = AccessUiState(status = AccessStatus.SIGNED_OUT)
        }
    }

    fun replaceSession(session: Session) {
        mutableState.value = AccessUiState(
            status = if (session.activeWorkspaceId == null) AccessStatus.WORKSPACE else AccessStatus.AUTHENTICATED,
            session = session,
        )
    }

    fun showWorkspaceSelection(session: Session, errorCode: String? = null) {
        mutableState.value = AccessUiState(
            status = AccessStatus.WORKSPACE,
            session = session,
            errorCode = errorCode,
        )
    }

    private suspend fun resolveSession(initial: Session, requestedWorkspaceId: String?) {
        var selected = initial
        var errorCode: String? = null
        val workspaceToRestore = workspaceSelectionToRestore(initial, requestedWorkspaceId)
        if (workspaceToRestore != null) {
            try {
                selected = repository.selectWorkspace(workspaceToRestore)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.AUTHENTICATION, DiagnosticAction.SELECT_WORKSPACE)
                errorCode = cause.safeErrorCode()
            }
        }
        val authenticated = selected.activeWorkspaceId != null && errorCode == null
        mutableState.value = AccessUiState(
            status = if (authenticated) AccessStatus.AUTHENTICATED else AccessStatus.WORKSPACE,
            session = selected,
            errorCode = errorCode,
        )
    }

    private fun launchBusy(action: DiagnosticAction, operation: suspend () -> Unit) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, errorCode = null)
            try {
                operation()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.AUTHENTICATION, action)
                mutableState.value = mutableState.value.copy(errorCode = cause.safeErrorCode())
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }
}

private fun Throwable.safeErrorCode(): String = when (this) {
    is DomainFailure -> if (category == FailureCategory.RECOVERABLE) code else "unknown_error"
    else -> "unknown_error"
}
