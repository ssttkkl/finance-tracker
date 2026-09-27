package com.finance.tracker.data

import com.finance.tracker.core.*
import com.finance.tracker.domain.*
import kotlinx.coroutines.CancellationException

class KtorSessionRepository(private val apiClient: FinanceApiClient) : SessionRepository {
    override suspend fun hasStoredSessionToken(): Boolean = guarded { apiClient.hasStoredSessionToken() }

    override suspend fun restoreSession(): Session = guarded { apiClient.restoreSession().toDomain() }

    override suspend fun session(): Session = guarded { apiClient.session().toDomain() }

    override suspend fun login(email: String, password: String): Session = guarded { apiClient.login(email, password).toDomain() }

    override suspend fun register(email: String, password: String): Session = guarded { apiClient.register(email, password).toDomain() }

    override suspend fun logout() {
        guarded { apiClient.logout() }
    }

    override suspend fun selectWorkspace(id: String): Session = guarded { apiClient.selectWorkspace(id).toDomain() }

    override suspend fun createWorkspace(name: String): Session = guarded { apiClient.createWorkspace(name).toDomain() }

    override suspend fun invitationPreview(token: String): InvitationPreview = guarded { apiClient.invitationPreview(token).toDomain() }

    override suspend fun acceptInvitation(token: String): Session = guarded { apiClient.acceptInvitation(token).toDomain() }

    override suspend fun workspaceDetails(): WorkspaceMembers = guarded { apiClient.workspaceDetails().toDomain() }

    override suspend fun updateWorkspace(name: String): Session = guarded { apiClient.updateWorkspace(name).toDomain() }

    override suspend fun deleteWorkspace(name: String): Session = guarded { apiClient.deleteWorkspace(name).toDomain() }

    override suspend fun invite(role: WorkspaceRole): String = guarded { apiClient.invite(role.toData()).token }

    override suspend fun members(): WorkspaceMembers = guarded { apiClient.members().toDomain() }

    override suspend fun updateMember(id: String, role: WorkspaceRole) {
        guarded { apiClient.updateMember(id, role.toData()) }
    }

    override suspend fun removeMember(id: String) {
        guarded { apiClient.removeMember(id) }
    }

    private suspend fun <T> guarded(operation: suspend () -> T): T = try {
        operation()
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Throwable) {
        throw cause.toDomainFailure()
    }
}
