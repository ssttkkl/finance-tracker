package com.finance.tracker.domain

class RestoreSessionUseCase(private val repository: SessionRepository) {
    suspend operator fun invoke(): Session = repository.restoreSession()
}

class AuthenticateUserUseCase(private val repository: SessionRepository) {
    suspend operator fun invoke(email: String, password: String, registering: Boolean): Session {
        val validation = validateCredentials(email, password)
        if (validation != CredentialValidation.Valid) {
            throw DomainFailure("auth_input_invalid_${validation.name.lowercase()}", 400, FailureCategory.RECOVERABLE)
        }
        return if (registering) repository.register(email.trim(), password) else repository.login(email.trim(), password)
    }
}

class SelectWorkspaceUseCase(private val repository: SessionRepository) {
    suspend operator fun invoke(id: String): Session = repository.selectWorkspace(id)
}

class CreateWorkspaceUseCase(private val repository: SessionRepository) {
    suspend operator fun invoke(name: String): Session {
        val normalized = normalizeDomainWorkspaceName(name) ?: error("invalid_workspace_name")
        return repository.createWorkspace(normalized)
    }
}

class AcceptInvitationUseCase(private val repository: SessionRepository) {
    suspend fun preview(token: String): InvitationPreview = repository.invitationPreview(token)
    suspend operator fun invoke(token: String): Session = repository.acceptInvitation(token)
}
