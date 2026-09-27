package com.finance.tracker.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finance.tracker.core.DiagnosticAction
import com.finance.tracker.core.DiagnosticFeature
import com.finance.tracker.core.DiagnosticLogger
import com.finance.tracker.core.NoOpDiagnosticLogger
import com.finance.tracker.core.invitationLinkFor
import com.finance.tracker.domain.DomainFailure
import com.finance.tracker.domain.FailureCategory
import com.finance.tracker.domain.Session
import com.finance.tracker.domain.SessionRepository
import com.finance.tracker.domain.WorkspaceMembers
import com.finance.tracker.domain.WorkspaceRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WorkspaceManagementUiState(
    val details: WorkspaceMembers? = null,
    val loading: Boolean = true,
    val errorCode: String? = null,
    val nameDraft: String = "",
    val invitationRole: WorkspaceRole = WorkspaceRole.EDITOR,
    val invitationLink: String = "",
    val feedbackCode: String? = null,
    val busyMemberId: String? = null,
    val deleteDialogOpen: Boolean = false,
    val deleteName: String = "",
    val deleteErrorCode: String? = null,
    val deleting: Boolean = false,
    val updatedSession: Session? = null,
    val deletedSession: Session? = null,
)

class WorkspaceManagementViewModel(
    private val repository: SessionRepository,
    private val diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
) : ViewModel() {
    private val mutableState = MutableStateFlow(WorkspaceManagementUiState())
    val state: StateFlow<WorkspaceManagementUiState> = mutableState.asStateFlow()
    private var loadRequestId = 0

    fun load() {
        val requestId = ++loadRequestId
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, errorCode = null)
            try {
                val details = repository.workspaceDetails()
                if (requestId == loadRequestId) {
                    mutableState.value = mutableState.value.copy(
                        details = details,
                        nameDraft = details.workspace.name,
                        loading = false,
                    )
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.WORKSPACE, DiagnosticAction.LOAD_DETAILS)
                if (requestId == loadRequestId) mutableState.value = mutableState.value.copy(loading = false, errorCode = cause.safeErrorCode())
            }
        }
    }

    fun updateName(value: String) {
        mutableState.value = mutableState.value.copy(nameDraft = value.take(255), errorCode = null)
    }

    fun saveName(canManage: Boolean) {
        val current = mutableState.value
        val details = current.details ?: return
        val name = current.nameDraft.trim()
        if (!canManage || name.isEmpty() || name == details.workspace.name || current.loading) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, errorCode = null, feedbackCode = null)
            try {
                val updated = repository.updateWorkspace(name)
                mutableState.value = mutableState.value.copy(updatedSession = updated, feedbackCode = "workspace_name_saved")
                load()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.WORKSPACE, DiagnosticAction.UPDATE)
                mutableState.value = mutableState.value.copy(errorCode = cause.safeErrorCode(), loading = false)
            }
        }
    }

    fun consumeUpdatedSession() {
        mutableState.value = mutableState.value.copy(updatedSession = null)
    }

    fun setFeedback(code: String) {
        if (code == "copy_failed") {
            diagnostics.record(DiagnosticFeature.WORKSPACE, DiagnosticAction.COPY_LINK, code, null, "ClipboardWriteFailure")
        }
        mutableState.value = mutableState.value.copy(feedbackCode = code)
    }

    fun setInvitationRole(role: WorkspaceRole) {
        if (role != WorkspaceRole.ADMIN) mutableState.value = mutableState.value.copy(invitationRole = role)
    }

    fun createInvitation(canManage: Boolean, webOrigin: String, currentPath: String?) {
        if (!canManage || mutableState.value.loading) return
        val role = mutableState.value.invitationRole
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(errorCode = null, feedbackCode = null)
            try {
                val token = repository.invite(role)
                val link = invitationLinkFor(webOrigin.trimEnd('/'), currentPath, token)
                mutableState.value = mutableState.value.copy(
                    invitationLink = link,
                    feedbackCode = if (webOrigin.isBlank()) "workspace_app_invite_link_generated" else "workspace_invite_link_generated",
                )
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.WORKSPACE, DiagnosticAction.CREATE_INVITATION)
                mutableState.value = mutableState.value.copy(errorCode = cause.safeErrorCode())
            }
        }
    }

    fun updateMember(memberId: String, role: WorkspaceRole, canManage: Boolean) {
        if (!canManage || mutableState.value.busyMemberId != null) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busyMemberId = memberId, errorCode = null)
            try {
                repository.updateMember(memberId, role)
                load()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.WORKSPACE, DiagnosticAction.UPDATE)
                mutableState.value = mutableState.value.copy(errorCode = cause.safeErrorCode())
            } finally {
                mutableState.value = mutableState.value.copy(busyMemberId = null)
            }
        }
    }

    fun removeMember(memberId: String, canManage: Boolean) {
        if (!canManage || mutableState.value.busyMemberId != null) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busyMemberId = memberId, errorCode = null)
            try {
                repository.removeMember(memberId)
                load()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.WORKSPACE, DiagnosticAction.DELETE)
                mutableState.value = mutableState.value.copy(errorCode = cause.safeErrorCode())
            } finally {
                mutableState.value = mutableState.value.copy(busyMemberId = null)
            }
        }
    }

    fun openDeleteDialog() {
        mutableState.value = mutableState.value.copy(deleteDialogOpen = true, deleteName = "", deleteErrorCode = null)
    }

    fun updateDeleteName(value: String) {
        mutableState.value = mutableState.value.copy(deleteName = value, deleteErrorCode = null)
    }

    fun dismissDeleteDialog() {
        if (!mutableState.value.deleting) {
            mutableState.value = mutableState.value.copy(deleteDialogOpen = false, deleteErrorCode = null)
        }
    }

    fun deleteWorkspace(canManage: Boolean) {
        val current = mutableState.value
        val expectedName = current.details?.workspace?.name ?: return
        if (!canManage || !current.deleteDialogOpen || current.deleting || current.deleteName != expectedName) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(deleting = true, deleteErrorCode = null)
            try {
                val updated = repository.deleteWorkspace(current.deleteName)
                mutableState.value = mutableState.value.copy(deletedSession = updated, deleteDialogOpen = false)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.WORKSPACE, DiagnosticAction.DELETE)
                mutableState.value = mutableState.value.copy(deleteErrorCode = cause.safeErrorCode())
            } finally {
                mutableState.value = mutableState.value.copy(deleting = false)
            }
        }
    }

    fun consumeDeletedSession() {
        mutableState.value = mutableState.value.copy(deletedSession = null)
    }
}

private fun Throwable.safeErrorCode(): String = when (this) {
    is DomainFailure -> if (category == FailureCategory.RECOVERABLE) code else "unknown_error"
    else -> "unknown_error"
}
