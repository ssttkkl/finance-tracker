package com.finance.tracker.cli

import com.finance.tracker.business.contracts.Role
import com.finance.tracker.client.ClientOperationFailure

internal suspend fun Suite.sessionBenchmarks() {
    val initialName = withClient(origin) { it.currentWorkspace().name }
    val member = fixture.members[0] as String
    val invitationPath = "/api/v1/auth/invitations"
    scenario("session.register") {
        stateSlot("registration-$index")
        val email = "perf-register-$index@example.test"
        val mark = markRequests()
        val value = withClient(origin, createKey = true) { measure { it.register(email, password) } }
        check(value.user.email == email && requestCount(mark, "POST", "/api/v1/auth/register") == 1)
        check(withClient(origin) { it.status().user.email } == email)
    }
    scenario("session.login") {
        stateSlot("login-$index")
        val mark = markRequests()
        val value = withClient(origin, createKey = true) { measure { it.login(ownerEmail, password) } }
        check(value.user.email == ownerEmail && value.workspaces.size == 20)
        check(requestCount(mark, "POST", "/api/v1/auth/login") == 1)
    }
    scenario("session.status") {
        select(workspace)
        val value = withClient(origin) { measure { it.status() } }
        check(value.user.email == ownerEmail && value.activeWorkspaceId == workspace && value.workspaces.size == 20)
    }
    scenario("session.logout") {
        stateSlot("logout-$index")
        withClient(origin, createKey = true) { it.login(ownerEmail, password) }
        val mark = markRequests()
        withClient(origin) { measure { it.logout() } }
        check(requestCount(mark, "POST", "/api/v1/auth/logout") == 1)
        val failure = runCatching { withClient(origin) { it.status() } }.exceptionOrNull()
        check(failure is ClientOperationFailure && failure.code == "authentication_required")
    }
    scenario("session.current") {
        select(workspace)
        val value = withClient(origin) { measure { it.currentWorkspace() } }
        check(value.id == workspace && value.role == Role.ADMIN && value.name == initialName)
    }
    scenario("session.workspace-create") {
        select(workspace)
        val name = "Perf created workspace $index"
        val value = withClient(origin) { measure { it.createWorkspace(name) } }
        val added = value.workspaces.single { it.name == name }
        check(value.activeWorkspaceId == added.id && value.workspaces.size == 21 && count("workspaces", added.id) == 1)
        withClient(origin) { it.deleteWorkspace(added.id, name) }
    }
    scenario("session.workspace-switch") {
        select(workspace)
        val value = withClient(origin) { measure { it.selectWorkspace(emptyWorkspace) } }
        check(value.activeWorkspaceId == emptyWorkspace && value.workspaces.size == 20)
    }
    scenario("session.workspace-rename") {
        select(workspace)
        val name = "Perf renamed workspace $index"
        val value = withClient(origin) { measure { it.renameWorkspace(workspace, name) } }
        check(value.workspaces.single { it.id == workspace }.name == name && value.workspaces.size == 20)
        withClient(origin) { it.renameWorkspace(workspace, initialName) }
    }
    for (id in listOf("session.workspace-delete", "session.workspace-delete-server")) {
        scenario(id) {
            val target = "00000000-0000-4005-8000-${index.toString().padStart(12, '0')}"
            host.seedDeletion(target, ownerId)
            select(target)
            check(count("activities", target) == 1000 && count("rows", target) == 1000 && count("accounts", target) == 50)
            val mark = markRequests()
            val name = "Perf deletion workspace"
            val value = if (id == "session.workspace-delete-server") {
                val result = withClient(origin) { it.deleteWorkspace(target, name) }
                val elapsed = host.backendDuration(mark, "DELETE", "/api/v1/auth/workspace").unsafeCast<kotlin.js.Promise<Double>>().value()
                recordBackendDuration(elapsed)
                result
            } else {
                withClient(origin) { measure { it.deleteWorkspace(target, name) } }
            }
            check(value.workspaces.none { it.id == target } && value.workspaces.size == 20)
            check(count("workspaces", target) == 0 && count("activities", target) == 0 && count("rows", target) == 0)
            host.deleteDeletionRemainder(target)
        }
    }
    scenario("session.members") {
        select(workspace)
        val value = withClient(origin) { measure { it.members(workspace) } }
        check(value.workspace.id == workspace && value.members.size == 50)
        check(value.members.single { it.isSelf }.role == Role.ADMIN)
        check(value.members.single { it.userId == member }.role == Role.VIEWER)
    }
    scenario("session.member-role") {
        select(workspace)
        val value = withClient(origin) { measure { it.setRole(workspace, member, Role.EDITOR) } }
        check(value.members.single { it.userId == member }.role == Role.EDITOR && value.members.size == 50)
        withClient(origin) { it.setRole(workspace, member, Role.VIEWER) }
    }
    scenario("session.member-remove") {
        select(workspace)
        val value = withClient(origin) { measure { it.removeMember(workspace, member) } }
        check(value && count("memberships") == 49)
        host.restoreMember(workspace, member)
    }
    scenario("session.invitation-preview") {
        select(workspace)
        val token = withClient(origin) { it.createInvitation(workspace, Role.VIEWER).token }
        val value = measure { access.previewInvitation(token) }
        check(value.valid && value.workspace.name == initialName && value.role == Role.VIEWER)
    }
    scenario("session.invitation-create") {
        select(workspace)
        val mark = markRequests()
        val value = withClient(origin) { measure { it.createInvitation(workspace, Role.EDITOR) } }
        check(value.role == Role.EDITOR && value.expiresInDays > 0 && value.token.isNotBlank())
        check(requestCount(mark, "POST", invitationPath) == 1)
        check(access.previewInvitation(value.token).valid)
    }
    scenario("session.invitation-accept") {
        select(workspace)
        val token = withClient(origin) { it.createInvitation(workspace, Role.VIEWER).token }
        stateSlot("invitation-$index")
        val email = "perf-invited-$index@example.test"
        withClient(origin, createKey = true) { it.register(email, password) }
        val value = withClient(origin) { measure { it.acceptInvitation(token) } }
        check(value.user.email == email && value.workspaces.single { it.id == workspace }.role == Role.VIEWER)
        check(value.activeWorkspaceId == workspace && count("memberships") == 51)
        stateSlot()
        val invited = withClient(origin) { it.members(workspace).members.single { it.email == email } }
        check(withClient(origin) { it.removeMember(workspace, invited.userId) })
    }
}
