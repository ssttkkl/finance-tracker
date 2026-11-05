package com.finance.tracker.backend

import com.finance.tracker.business.contracts.*
import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import org.jooq.TableField
import org.jooq.impl.DSL

class WorkspaceFailure(val code: String) : RuntimeException(code)
@Serializable data class InvitationCreated(val token: String, val role: String, val expires_in_days: Int = 7)

class WorkspaceService(private val database: Database, private val now: () -> Instant = Instant::now,
    private val newWorkspaceId: () -> String = { UUID.randomUUID().toString() },
    private val newInvitationToken: () -> String = ::secureToken) {
    private val reads = ReadServices(database, now)
    private fun lockWorkspace(connection: Connection, workspace: String): Boolean = connection.lockWorkspace(database.sqlite, workspace)

    private fun <T> writeActive(token: String?, roles: Set<Role>, action: (Connection, Principal) -> T): T {
        if (token == null) throw AccessFailure()
        return database.write { connection ->
            val initial = reads.session(connection, token)
            val workspace = initial.workspaceId ?: throw AccessFailure(true)
            if (!lockWorkspace(connection, workspace)) throw AccessFailure(true)
            val current = reads.session(connection, token, lock = true)
            if (current.workspaceId != workspace) throw AccessFailure(true)
            val principal = reads.context(connection, token, false)
            if (principal.role !in roles) throw AccessFailure(true)
            action(connection, principal)
        }
    }

    private fun name(value: String): String = value.trim { it.code in pythonWhitespace }.also {
        if (it.isEmpty() || it.codePointCount(0, it.length) > 255) throw WorkspaceFailure("invalid_workspace")
        strictUtf8(it)
    }

    private fun activate(connection: Connection, token: String, workspace: String?) {
        check(connection.jooq(database.sqlite).update(USER_SESSIONS).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, workspace)
            .where(USER_SESSIONS.TOKEN_DIGEST.eq(tokenDigest(token))).execute() == 1)
    }

    fun create(token: String?, value: String): SessionDto {
        val name = name(value)
        if (token == null) throw AccessFailure()
        return database.write { connection ->
            val user = reads.session(connection, token, lock = true)
            val workspace = newWorkspaceId()
            val sql = connection.jooq(database.sqlite)
            sql.insertInto(WORKSPACES).set(WORKSPACES.ID, workspace).set(WORKSPACES.NAME, name).set(WORKSPACES.CREATED_AT, now()).execute()
            sql.insertInto(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID, workspace)
                .set(WORKSPACE_MEMBERSHIPS.USER_ID, user.userId).set(WORKSPACE_MEMBERSHIPS.ROLE, "admin").set(WORKSPACE_MEMBERSHIPS.CREATED_AT, now()).execute()
            initializeEmptyLedger(connection, workspace)
            activate(connection, token, workspace)
            reads.state(connection, user.copy(workspaceId = workspace))
        }
    }

    fun select(token: String?, workspace: String): SessionDto {
        if (token == null) throw AccessFailure()
        return database.write { connection ->
            reads.session(connection, token)
            if (!lockWorkspace(connection, workspace)) throw AccessFailure(true)
            val user = reads.session(connection, token, lock = true)
            val member = connection.jooq(database.sqlite).fetchExists(WORKSPACE_MEMBERSHIPS,
                WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(workspace).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user.userId)))
            if (!member) throw AccessFailure(true)
            activate(connection, token, workspace)
            reads.state(connection, user.copy(workspaceId = workspace))
        }
    }

    fun members(token: String?): WorkspaceMembersDto {
        if (token == null) throw AccessFailure()
        return database.read { connection -> members(connection, reads.context(connection, token, false)) }
    }

    private fun members(connection: Connection, user: Principal): WorkspaceMembersDto {
        val sql = connection.jooq(database.sqlite)
        val name = sql.select(WORKSPACES.NAME).from(WORKSPACES).where(WORKSPACES.ID.eq(user.workspaceId)).fetchOne(WORKSPACES.NAME)
            ?: throw AccessFailure(true)
        val workspace = WorkspaceDetailsDto(user.workspaceId!!, name)
        val m = WORKSPACE_MEMBERSHIPS; val u = USERS
        val members = sql.select(m.USER_ID, u.EMAIL, m.ROLE).from(m).join(u).on(u.ID.eq(m.USER_ID))
            .where(m.WORKSPACE_ID.eq(user.workspaceId)).orderBy(m.CREATED_AT).fetch().map {
                MemberDto(it[m.USER_ID]!!, it[u.EMAIL]!!, role(it[m.ROLE]!!), it[m.USER_ID] == user.userId)
            }
        return WorkspaceMembersDto(workspace, members)
    }

    fun rename(token: String?, value: String): SessionDto {
        val name = name(value)
        return writeActive(token, setOf(Role.ADMIN)) { connection, user ->
            check(connection.jooq(database.sqlite).update(WORKSPACES).set(WORKSPACES.NAME, name).where(WORKSPACES.ID.eq(user.workspaceId)).execute() == 1)
            reads.state(connection, user)
        }
    }

    fun invite(token: String?, value: String): InvitationCreated = writeActive(token, setOf(Role.ADMIN)) { connection, user ->
        if (value !in setOf("editor", "viewer")) throw WorkspaceFailure("invalid_role")
        val raw = newInvitationToken()
        val i = WORKSPACE_INVITATIONS
        connection.jooq(database.sqlite).insertInto(i).set(i.ID, UUID.randomUUID().toString()).set(i.WORKSPACE_ID, user.workspaceId)
            .set(i.ROLE, value).set(i.TOKEN_DIGEST, tokenDigest(raw)).set(i.EXPIRES_AT, now().plusSeconds(7L * 86400))
            .setNull(i.ACCEPTED_AT).set(i.CREATED_BY_USER_ID, user.userId).set(i.CREATED_AT, now()).execute()
        InvitationCreated(raw, value)
    }

    private data class Invitation(val workspace: String, val role: String, val expires: Instant, val accepted: Boolean)
    private fun invitation(connection: Connection, raw: String, lock: Boolean = false): Invitation {
        val i = WORKSPACE_INVITATIONS
        val query = connection.jooq(database.sqlite).select(i.WORKSPACE_ID, i.ROLE, i.EXPIRES_AT, i.ACCEPTED_AT)
            .from(i).where(i.TOKEN_DIGEST.eq(tokenDigest(raw)))
        val row = (if (lock && !database.sqlite) query.forUpdate() else query).fetchOne() ?: throw WorkspaceFailure("invalid_invitation")
        val value = Invitation(row[i.WORKSPACE_ID]!!, row[i.ROLE]!!, row[i.EXPIRES_AT]!!, row[i.ACCEPTED_AT] != null)
        if (value.accepted || !value.expires.isAfter(now())) throw WorkspaceFailure("invalid_invitation")
        return value
    }

    fun preview(raw: String): InvitationPreviewDto = database.read { connection ->
        val invite = invitation(connection, raw)
        val name = connection.jooq(database.sqlite).select(WORKSPACES.NAME).from(WORKSPACES)
            .where(WORKSPACES.ID.eq(invite.workspace)).fetchOne(WORKSPACES.NAME) ?: throw WorkspaceFailure("invalid_invitation")
        InvitationPreviewDto(InvitationWorkspaceDto(name), role(invite.role), true)
    }

    fun accept(token: String?, raw: String): SessionDto {
        if (token == null) throw AccessFailure()
        return database.write { connection ->
            reads.session(connection, token)
            val initial = invitation(connection, raw)
            if (!lockWorkspace(connection, initial.workspace)) throw WorkspaceFailure("invalid_invitation")
            val user = reads.session(connection, token, lock = true)
            val invite = invitation(connection, raw, lock = true)
            if (invite.workspace != initial.workspace) throw WorkspaceFailure("invalid_invitation")
            val sql = connection.jooq(database.sqlite); val m = WORKSPACE_MEMBERSHIPS; val i = WORKSPACE_INVITATIONS
            sql.insertInto(m).set(m.WORKSPACE_ID, invite.workspace).set(m.USER_ID, user.userId).set(m.ROLE, invite.role).set(m.CREATED_AT, now())
                .onConflict(m.WORKSPACE_ID, m.USER_ID).doNothing().execute()
            check(sql.update(i).set(i.ACCEPTED_AT, now()).where(i.TOKEN_DIGEST.eq(tokenDigest(raw))).execute() == 1)
            activate(connection, token, invite.workspace)
            reads.state(connection, user.copy(workspaceId = invite.workspace))
        }
    }

    private fun role(value: String): Role = when (value) {
        "admin" -> Role.ADMIN; "editor" -> Role.EDITOR; "viewer" -> Role.VIEWER
        else -> throw WorkspaceFailure("invalid_role")
    }

    private fun requireMemberChange(connection: Connection, workspace: String, target: String, newRole: String?) {
        val sql = connection.jooq(database.sqlite); val m = WORKSPACE_MEMBERSHIPS
        val old = sql.select(m.ROLE).from(m).where(m.WORKSPACE_ID.eq(workspace)).and(m.USER_ID.eq(target)).fetchOne(m.ROLE) ?: throw AccessFailure(true)
        if (old == "admin" && newRole != "admin") {
            val count = sql.select(DSL.count()).from(m).where(m.WORKSPACE_ID.eq(workspace)).and(m.ROLE.eq("admin")).fetchSingle().value1()
            if (count == 1) throw WorkspaceFailure("last_admin")
        }
    }

    fun updateMember(token: String?, target: String, value: String): WorkspaceMembersDto {
        role(value)
        return writeActive(token, setOf(Role.ADMIN)) { connection, user ->
            requireMemberChange(connection, user.workspaceId!!, target, value)
            val m = WORKSPACE_MEMBERSHIPS
            check(connection.jooq(database.sqlite).update(m).set(m.ROLE, value).where(m.WORKSPACE_ID.eq(user.workspaceId)).and(m.USER_ID.eq(target)).execute() == 1)
            members(connection, user)
        }
    }

    fun removeMember(token: String?, target: String) = writeActive(token, setOf(Role.ADMIN)) { connection, user ->
        requireMemberChange(connection, user.workspaceId!!, target, null)
        val m = WORKSPACE_MEMBERSHIPS
        check(connection.jooq(database.sqlite).deleteFrom(m).where(m.WORKSPACE_ID.eq(user.workspaceId)).and(m.USER_ID.eq(target)).execute() == 1)
    }

    fun delete(token: String?, confirmation: String): SessionDto = writeActive(token, setOf(Role.ADMIN)) { connection, user ->
        val workspace = user.workspaceId!!
        val sql = connection.jooq(database.sqlite)
        val currentName = sql.select(WORKSPACES.NAME).from(WORKSPACES).where(WORKSPACES.ID.eq(workspace)).fetchSingle(WORKSPACES.NAME)
        if (confirmation != currentName) throw WorkspaceFailure("workspace_name_mismatch")
        sql.update(USER_SESSIONS).setNull(USER_SESSIONS.ACTIVE_WORKSPACE_ID).where(USER_SESSIONS.ACTIVE_WORKSPACE_ID.eq(workspace)).execute()
        val beforeCategories = listOf(CASH_PROJECTION_MEMBERS.WORKSPACE_ID, CASH_PROJECTION_RELATIONS.WORKSPACE_ID, CASH_PROJECTIONS.WORKSPACE_ID,
            CASH_PROJECTION_STATES.WORKSPACE_ID, CASH_PROJECTION_DATASETS.WORKSPACE_ID, CASH_ENTRY_MEMBERS.WORKSPACE_ID, CASH_ENTRIES.WORKSPACE_ID,
            CASH_INVESTMENT_FUNDING_RELATIONS.WORKSPACE_ID, TRANSACTION_RELATIONS.WORKSPACE_ID, CASH_TRANSACTIONS.WORKSPACE_ID, INVESTMENT_EVENTS.WORKSPACE_ID)
        for (field in beforeCategories) deleteOwned(connection, field, workspace)
        sql.update(CASH_CATEGORIES).setNull(CASH_CATEGORIES.PARENT_ID).where(CASH_CATEGORIES.WORKSPACE_ID.eq(workspace)).execute()
        for (field in listOf(CASH_CATEGORIES.WORKSPACE_ID, CASH_CATEGORY_STATES.WORKSPACE_ID, STATEMENT_ACCOUNT_MAPPINGS.WORKSPACE_ID,
            ACCOUNT_ALIASES.WORKSPACE_ID, SYNC_CURSORS.WORKSPACE_ID, LEDGER_SNAPSHOTS.WORKSPACE_ID, ACCOUNTS.WORKSPACE_ID)) deleteOwned(connection, field, workspace)
        check(sql.deleteFrom(WORKSPACES).where(WORKSPACES.ID.eq(workspace)).execute() == 1)
        val m = WORKSPACE_MEMBERSHIPS; val w = WORKSPACES
        val query = sql.select(m.WORKSPACE_ID).from(m).join(w).on(w.ID.eq(m.WORKSPACE_ID))
            .where(m.USER_ID.eq(user.userId)).orderBy(m.CREATED_AT, m.WORKSPACE_ID).limit(1)
        val replacement = (if (database.sqlite) query else query.forKeyShare().of(w).noWait()).fetchOne(m.WORKSPACE_ID)
        activate(connection, token!!, replacement)
        reads.state(connection, user.copy(workspaceId = replacement))
    }

    private fun deleteOwned(connection: Connection, field: TableField<*, String?>, workspace: String) {
        connection.jooq(database.sqlite).deleteFrom(field.table).where(field.eq(workspace)).execute()
    }

    private fun initializeEmptyLedger(connection: Connection, workspace: String) {
        CashProjectionService.initializeInSession(CashSqlContext(connection,workspace,database.sqlite),now)
    }
}
