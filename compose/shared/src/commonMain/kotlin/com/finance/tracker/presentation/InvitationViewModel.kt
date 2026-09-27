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

data class InvitationUiState(
    val loading: Boolean = true,
    val accepting: Boolean = false,
    val preview: InvitationPreview? = null,
    val terminalPreviewError: Boolean = false,
    val errorCode: String? = null,
    val acceptedSession: Session? = null,
)

class InvitationViewModel(
    private val repository: SessionRepository,
    private val diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
) : ViewModel() {
    private val mutableState = MutableStateFlow(InvitationUiState())
    val state: StateFlow<InvitationUiState> = mutableState.asStateFlow()
    private var loadedToken: String? = null

    fun load(token: String, force: Boolean = false) {
        if (!force && loadedToken == token && !mutableState.value.loading) return
        loadedToken = token
        mutableState.value = InvitationUiState(loading = true)
        viewModelScope.launch {
            try {
                val preview = repository.invitationPreview(token)
                mutableState.value = InvitationUiState(loading = false, preview = preview)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.INVITATION, DiagnosticAction.LOAD)
                val failure = cause.asDomainFailure()
                mutableState.value = InvitationUiState(
                    loading = false,
                    terminalPreviewError = failure.status == 404 || failure.status == 410 || failure.code in terminalInvitationCodes,
                    errorCode = failure.code,
                )
            }
        }
    }

    fun retry(token: String) = load(token, force = true)

    fun accept(token: String) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(accepting = true, errorCode = null)
            try {
                val session = repository.acceptInvitation(token)
                mutableState.value = mutableState.value.copy(accepting = false, acceptedSession = session)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.INVITATION, DiagnosticAction.ACCEPT_INVITATION)
                mutableState.value = mutableState.value.copy(
                    accepting = false,
                    terminalPreviewError = cause.asDomainFailure().let { it.status == 404 || it.status == 410 || it.code in terminalInvitationCodes },
                    errorCode = cause.asDomainFailure().code,
                )
            }
        }
    }

    fun consumeAcceptedSession() {
        mutableState.value = mutableState.value.copy(acceptedSession = null)
    }
}

private fun Throwable.asDomainFailure(): DomainFailure = this as? DomainFailure
    ?: DomainFailure("unknown_error", 0, FailureCategory.UNKNOWN)

private val terminalInvitationCodes = setOf(
    "invitation_invalid",
    "invitation_expired",
    "invitation_already_used",
)
