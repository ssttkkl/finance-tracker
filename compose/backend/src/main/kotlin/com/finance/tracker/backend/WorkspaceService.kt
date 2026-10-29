package com.finance.tracker.backend

import com.finance.tracker.business.contracts.*
import java.sql.Connection
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.Serializable

class WorkspaceFailure(val code: String) : RuntimeException(code)
@Serializable data class InvitationCreated(val token: String, val role: String, val expires_in_days: Int = 7)

class WorkspaceService(private val database: Database, private val now: () -> Instant = Instant::now,
    private val newWorkspaceId: () -> String = { UUID.randomUUID().toString() },
    private val newInvitationToken: () -> String = ::secureToken) {
    private val reads = ReadServices(database, now)
    private val lockSuffix get() = if (database.sqlite) "" else " FOR UPDATE"

    private fun lockWorkspace(connection: Connection, workspace: String): Boolean = connection.prepareStatement("SELECT id FROM workspaces WHERE id=?$lockSuffix").use {
        it.setString(1, workspace); it.executeQuery().use { rows -> rows.next() }
    }

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
        connection.prepareStatement("UPDATE user_sessions SET active_workspace_id=? WHERE token_digest=?").use {
            it.setString(1, workspace); it.setString(2, tokenDigest(token)); check(it.executeUpdate() == 1)
        }
    }

    fun create(token: String?, value: String): SessionDto {
        val name = name(value)
        if (token == null) throw AccessFailure()
        return database.write { connection ->
            val user = reads.session(connection, token, lock = true)
            val workspace = newWorkspaceId()
            connection.prepareStatement("INSERT INTO workspaces(id,name,created_at) VALUES(?,?,?)").use {
                it.setString(1, workspace); it.setString(2, name); it.instant(3, now(), database.sqlite); it.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO workspace_memberships(workspace_id,user_id,role,created_at) VALUES(?,?,'admin',?)").use {
                it.setString(1, workspace); it.setString(2, user.userId); it.instant(3, now(), database.sqlite); it.executeUpdate()
            }
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
            val member = connection.prepareStatement("SELECT role FROM workspace_memberships WHERE workspace_id=? AND user_id=?").use {
                it.setString(1, workspace); it.setString(2, user.userId); it.executeQuery().use { rows -> rows.next() }
            }
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
        val workspace = connection.prepareStatement("SELECT name FROM workspaces WHERE id=?").use {
            it.setString(1, user.workspaceId); it.executeQuery().use { row ->
                if (!row.next()) throw AccessFailure(true)
                WorkspaceDetailsDto(user.workspaceId!!, row.getString(1))
            }
        }
        val members = connection.prepareStatement("""SELECT m.user_id,u.email,m.role FROM workspace_memberships m
            JOIN users u ON u.id=m.user_id WHERE m.workspace_id=? ORDER BY m.created_at""").use {
            it.setString(1, user.workspaceId)
            it.executeQuery().use { rows -> buildList {
                while (rows.next()) add(MemberDto(rows.getString(1), rows.getString(2), role(rows.getString(3)), rows.getString(1) == user.userId))
            } }
        }
        return WorkspaceMembersDto(workspace, members)
    }

    fun rename(token: String?, value: String): SessionDto {
        val name = name(value)
        return writeActive(token, setOf(Role.ADMIN)) { connection, user ->
            connection.prepareStatement("UPDATE workspaces SET name=? WHERE id=?").use {
                it.setString(1, name); it.setString(2, user.workspaceId); check(it.executeUpdate() == 1)
            }
            reads.state(connection, user)
        }
    }

    fun invite(token: String?, value: String): InvitationCreated = writeActive(token, setOf(Role.ADMIN)) { connection, user ->
        if (value !in setOf("editor", "viewer")) throw WorkspaceFailure("invalid_role")
        val raw = newInvitationToken()
        connection.prepareStatement("""INSERT INTO workspace_invitations(id,workspace_id,role,token_digest,expires_at,accepted_at,created_by_user_id,created_at)
            VALUES(?,?,?,?,?,NULL,?,?)""").use {
            it.setString(1, UUID.randomUUID().toString()); it.setString(2, user.workspaceId); it.setString(3, value)
            it.setString(4, tokenDigest(raw)); it.instant(5, now().plusSeconds(7L * 86400), database.sqlite)
            it.setString(6, user.userId); it.instant(7, now(), database.sqlite); it.executeUpdate()
        }
        InvitationCreated(raw, value)
    }

    private data class Invitation(val workspace: String, val role: String, val expires: Instant, val accepted: Boolean)
    private fun invitation(connection: Connection, raw: String, lock: Boolean = false): Invitation {
        val value = connection.prepareStatement("SELECT workspace_id,role,expires_at,accepted_at FROM workspace_invitations WHERE token_digest=?" + if (lock) lockSuffix else "").use {
            it.setString(1, tokenDigest(raw)); it.executeQuery().use { rows ->
                if (!rows.next()) throw WorkspaceFailure("invalid_invitation")
                val date = rows.getString(3).replace(' ', 'T')
                Invitation(rows.getString(1), rows.getString(2), if (database.sqlite) LocalDateTime.parse(date).toInstant(ZoneOffset.UTC)
                    else OffsetDateTime.parse(date).toInstant(), rows.getString(4) != null)
            }
        }
        if (value.accepted || !value.expires.isAfter(now())) throw WorkspaceFailure("invalid_invitation")
        return value
    }

    fun preview(raw: String): InvitationPreviewDto = database.read { connection ->
        val invite = invitation(connection, raw)
        connection.prepareStatement("SELECT name FROM workspaces WHERE id=?").use {
            it.setString(1, invite.workspace); it.executeQuery().use { rows ->
                if (!rows.next()) throw WorkspaceFailure("invalid_invitation")
                InvitationPreviewDto(InvitationWorkspaceDto(rows.getString(1)), role(invite.role), true)
            }
        }
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
            connection.prepareStatement("""INSERT INTO workspace_memberships(workspace_id,user_id,role,created_at) VALUES(?,?,?,?)
                ON CONFLICT(workspace_id,user_id) DO NOTHING""").use {
                it.setString(1, invite.workspace); it.setString(2, user.userId); it.setString(3, invite.role)
                it.instant(4, now(), database.sqlite); it.executeUpdate()
            }
            connection.prepareStatement("UPDATE workspace_invitations SET accepted_at=? WHERE token_digest=?").use {
                it.instant(1, now(), database.sqlite); it.setString(2, tokenDigest(raw)); check(it.executeUpdate() == 1)
            }
            activate(connection, token, invite.workspace)
            reads.state(connection, user.copy(workspaceId = invite.workspace))
        }
    }

    private fun role(value: String): Role = when (value) {
        "admin" -> Role.ADMIN; "editor" -> Role.EDITOR; "viewer" -> Role.VIEWER
        else -> throw WorkspaceFailure("invalid_role")
    }

    private fun requireMemberChange(connection: Connection, workspace: String, target: String, newRole: String?) {
        val old = connection.prepareStatement("SELECT role FROM workspace_memberships WHERE workspace_id=? AND user_id=?").use {
            it.setString(1, workspace); it.setString(2, target); it.executeQuery().use { rows ->
                if (!rows.next()) throw AccessFailure(true)
                rows.getString(1)
            }
        }
        if (old == "admin" && newRole != "admin") {
            val count = connection.prepareStatement("SELECT COUNT(*) FROM workspace_memberships WHERE workspace_id=? AND role='admin'").use {
                it.setString(1, workspace); it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
            if (count == 1) throw WorkspaceFailure("last_admin")
        }
    }

    fun updateMember(token: String?, target: String, value: String): WorkspaceMembersDto {
        role(value)
        return writeActive(token, setOf(Role.ADMIN)) { connection, user ->
            requireMemberChange(connection, user.workspaceId!!, target, value)
            connection.prepareStatement("UPDATE workspace_memberships SET role=? WHERE workspace_id=? AND user_id=?").use {
                it.setString(1, value); it.setString(2, user.workspaceId); it.setString(3, target); check(it.executeUpdate() == 1)
            }
            members(connection, user)
        }
    }

    fun removeMember(token: String?, target: String) = writeActive(token, setOf(Role.ADMIN)) { connection, user ->
        requireMemberChange(connection, user.workspaceId!!, target, null)
        connection.prepareStatement("DELETE FROM workspace_memberships WHERE workspace_id=? AND user_id=?").use {
            it.setString(1, user.workspaceId); it.setString(2, target); check(it.executeUpdate() == 1)
        }
    }

    fun delete(token: String?, confirmation: String): SessionDto = writeActive(token, setOf(Role.ADMIN)) { connection, user ->
        val workspace = user.workspaceId!!
        val currentName = connection.prepareStatement("SELECT name FROM workspaces WHERE id=?").use {
            it.setString(1, workspace); it.executeQuery().use { rows -> check(rows.next()); rows.getString(1) }
        }
        if (confirmation != currentName) throw WorkspaceFailure("workspace_name_mismatch")
        connection.prepareStatement("UPDATE user_sessions SET active_workspace_id=NULL WHERE active_workspace_id=?").use {
            it.setString(1, workspace); it.executeUpdate()
        }
        val beforeCategories = listOf("cash_projection_members", "cash_projection_relations", "cash_projections", "cash_projection_states",
            "cash_projection_datasets", "cash_entry_members", "cash_entries", "cash_investment_funding_relations", "transaction_relations",
            "cash_transactions", "investment_events")
        for (table in beforeCategories) deleteOwned(connection, table, workspace)
        connection.prepareStatement("UPDATE cash_categories SET parent_id=NULL WHERE workspace_id=?").use {
            it.setString(1, workspace); it.executeUpdate()
        }
        for (table in listOf("cash_categories", "cash_category_states", "statement_account_mappings", "account_aliases", "sync_cursors", "ledger_snapshots", "accounts"))
            deleteOwned(connection, table, workspace)
        connection.prepareStatement("DELETE FROM workspaces WHERE id=?").use { it.setString(1, workspace); check(it.executeUpdate() == 1) }
        val replacement = connection.prepareStatement("""SELECT m.workspace_id FROM workspace_memberships m JOIN workspaces w ON w.id=m.workspace_id
            WHERE m.user_id=? ORDER BY m.created_at,m.workspace_id LIMIT 1""" + if (database.sqlite) "" else " FOR KEY SHARE OF w NOWAIT").use {
            it.setString(1, user.userId); it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
        activate(connection, token!!, replacement)
        reads.state(connection, user.copy(workspaceId = replacement))
    }

    private fun deleteOwned(connection: Connection, table: String, workspace: String) {
        connection.prepareStatement("DELETE FROM $table WHERE workspace_id=?").use { it.setString(1, workspace); it.executeUpdate() }
    }

    private fun initializeEmptyLedger(connection: Connection, workspace: String) {
        CashProjectionService.initializeInSession(CashSqlContext(connection,workspace,database.sqlite),now)
    }
}
