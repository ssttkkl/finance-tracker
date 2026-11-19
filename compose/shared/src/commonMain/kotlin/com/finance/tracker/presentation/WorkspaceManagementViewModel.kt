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
    val nameErrorCode: String? = null,
    val busyMemberId: String? = null,
    val deleteDialogOpen: Boolean = false,
    val deleteName: String = "",
    val deleteFailure: ActionFailure? = null,
    val deleting: Boolean = false,
    val updatedSession: Session? = null,
    val nameSaved: Boolean = false,
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
        mutableState.value = mutableState.value.copy(nameDraft = value.take(255), nameErrorCode = null)
    }

    fun saveName(canManage: Boolean) {
        val current = mutableState.value
        val details = current.details ?: return
        val name = current.nameDraft.trim()
        if (!canManage || name.isEmpty() || name == details.workspace.name || current.loading) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, nameErrorCode = null)
            try {
                val updated = repository.updateWorkspace(name)
                mutableState.value = mutableState.value.copy(updatedSession = updated, nameSaved = true)
                load()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.WORKSPACE, DiagnosticAction.UPDATE)
                mutableState.value = mutableState.value.copy(nameErrorCode = cause.safeErrorCode(), loading = false)
            }
        }
    }

    fun consumeUpdatedSession() {
        mutableState.value = mutableState.value.copy(updatedSession = null, nameSaved = false)
    }

    fun recordClipboardFailure() {
        diagnostics.record(DiagnosticFeature.WORKSPACE, DiagnosticAction.COPY_LINK, "copy_failed", null, "ClipboardWriteFailure")
    }

    fun setInvitationRole(role: WorkspaceRole) {
        if (role != WorkspaceRole.ADMIN) mutableState.value = mutableState.value.copy(invitationRole = role)
    }

    fun createInvitation(canManage: Boolean, webOrigin: String, currentPath: String?) {
        if (!canManage || mutableState.value.loading) return
        val role = mutableState.value.invitationRole
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(errorCode = null)
            try {
                val token = repository.invite(role)
                val link = invitationLinkFor(webOrigin.trimEnd('/'), currentPath, token)
                mutableState.value = mutableState.value.copy(invitationLink = link)
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
        val current = mutableState.value
        if (current.loading || current.deleting || current.deleteFailure?.short == false) return
        mutableState.value = current.copy(deleteDialogOpen = true, deleteName = "", deleteFailure = null)
    }

    fun updateDeleteName(value: String) {
        val current = mutableState.value
        if (current.deleteFailure?.unknown == true) return
        mutableState.value = current.copy(deleteName = value)
    }

    fun dismissDeleteDialog() {
        mutableState.value = mutableState.value.copy(deleteDialogOpen = false)
    }

    fun deleteWorkspace(canManage: Boolean) {
        val current = mutableState.value
        val expectedName = current.details?.workspace?.name ?: return
        if (!canManage || !current.deleteDialogOpen || current.deleting || current.deleteFailure?.short == false || current.deleteName != expectedName) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(deleting = true, deleteFailure = null)
            try {
                val updated = repository.deleteWorkspace(current.deleteName)
                mutableState.value = mutableState.value.copy(deletedSession = updated, deleteDialogOpen = false)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.WORKSPACE, DiagnosticAction.DELETE)
                val failure = cause.writeActionFailure()
                mutableState.value = mutableState.value.copy(deleteFailure = failure)
                if (failure.unknown) refreshAfterDeletion(requireNotNull(current.details).workspace.id)
            } finally {
                mutableState.value = mutableState.value.copy(deleting = false)
            }
        }
    }

    fun consumeDeleteNotice(failure: ActionFailure) {
        val current = mutableState.value
        if (current.deleteFailure === failure && failure.short) {
            mutableState.value = current.copy(deleteFailure = null)
        }
    }

    fun verifyDeletion(workspaceId: String) {
        if (state.value.deleting) return
        viewModelScope.launch {
            mutableState.value = state.value.copy(deleting = true, errorCode = null)
            try {
                refreshAfterDeletion(workspaceId)
            } finally {
                mutableState.value = state.value.copy(deleting = false)
            }
        }
    }

    private suspend fun refreshAfterDeletion(workspaceId: String) {
        try {
            val session = repository.session()
            val stillAccessible = session.workspaces.any { it.id == workspaceId }
            val sameWorkspace = session.activeWorkspaceId == workspaceId && stillAccessible
            val details = if (sameWorkspace) repository.workspaceDetails() else null
            if (details != null && details.workspace.id != workspaceId) return
            mutableState.value = state.value.copy(
                deleteFailure = if (sameWorkspace && state.value.deleteFailure?.unknown == true)
                    ActionFailure("action_current_state", ActionFailurePresentation.SHORT) else null,
                deleteDialogOpen = false,
                details = details ?: state.value.details,
                nameDraft = details?.workspace?.name ?: state.value.nameDraft,
                deletedSession = session.takeUnless { sameWorkspace },
                updatedSession = session.takeIf { sameWorkspace },
                nameSaved = false,
            )
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.WORKSPACE, DiagnosticAction.LOAD_DETAILS)
            mutableState.value = state.value.copy(errorCode = cause.safeErrorCode())
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
