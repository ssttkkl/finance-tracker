package com.finance.tracker.backend

import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.CategoryNode
import com.finance.tracker.business.domain.categoryNamePath
import com.finance.tracker.business.domain.orderedCategoryNodes
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

class AccessFailure(val forbidden: Boolean = false) : RuntimeException("access_denied")
data class Principal(val userId: String, val email: String, val workspaceId: String?, val role: Role? = null)

fun bearerToken(header: String?): String? {
    if (header == null) return null
    val separator = header.indexOf(' ')
    if (separator < 0 || !header.substring(0, separator).equals("bearer", ignoreCase = true)) return null
    return header.substring(separator + 1).trim().takeIf { it.isNotEmpty() && ' ' !in it }
}

class ReadServices(private val database: Database, private val now: () -> Instant = Instant::now) {
    internal fun session(connection: Connection, token: String?, lock: Boolean = false): Principal {
        if (token == null) throw AccessFailure()
        val digest = tokenDigest(token)
        connection.prepareStatement("""SELECT s.user_id, s.active_workspace_id, s.expires_at, u.email
            FROM user_sessions s JOIN users u ON u.id=s.user_id WHERE s.token_digest=?""" + if (lock && !database.sqlite) " FOR UPDATE OF s" else "").use { statement ->
            statement.setString(1, digest)
            statement.executeQuery().use { row ->
                if (!row.next()) throw AccessFailure()
                requireActiveExpiry(row.getString("expires_at"))
                return Principal(row.getString("user_id"), row.getString("email"), row.getString("active_workspace_id"))
            }
        }
    }

    private fun requireActiveExpiry(value:String) {
        val raw=value.replace(' ','T')
        val expiry=try {
            if(database.sqlite) LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC) else OffsetDateTime.parse(raw).toInstant()
        } catch(_:java.time.DateTimeException) {throw StorageFailure("storage.schema")}
        if(!expiry.isAfter(now())) throw AccessFailure()
    }

    internal fun lockedContext(connection:Connection,token:String?,expectedWorkspace:String,write:Boolean):Principal {
        if(token==null) throw AccessFailure()
        return connection.prepareStatement("""SELECT s.user_id,s.active_workspace_id,s.expires_at,u.email,m.role FROM user_sessions s
            JOIN users u ON u.id=s.user_id LEFT JOIN workspace_memberships m ON m.workspace_id=s.active_workspace_id AND m.user_id=s.user_id
            WHERE s.token_digest=?"""+(if(database.sqlite) "" else " FOR UPDATE OF s")).use {
            it.setString(1,tokenDigest(token));it.executeQuery().use {row ->
                if(!row.next()) throw AccessFailure()
                requireActiveExpiry(row.getString("expires_at"))
                if(row.getString("active_workspace_id")!=expectedWorkspace) throw AccessFailure(true)
                val currentRole=row.getString("role")?.let(::role) ?: throw AccessFailure(true)
                if(write && !currentRole.canWrite) throw AccessFailure(true)
                Principal(row.getString("user_id"),row.getString("email"),expectedWorkspace,currentRole)
            }
        }
    }

    fun state(token: String?): SessionDto {
      if (token == null) throw AccessFailure()
      return database.read { connection ->
        state(connection, session(connection, token))
      }
    }

    internal fun state(connection: Connection, principal: Principal): SessionDto {
        val workspaces = connection.prepareStatement("""SELECT w.id,w.name,m.role FROM workspace_memberships m
            JOIN workspaces w ON w.id=m.workspace_id WHERE m.user_id=? ORDER BY w.created_at""").use { statement ->
            statement.setString(1, principal.userId)
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(WorkspaceDto(rows.getString("id"), rows.getString("name"), role(rows.getString("role"))))
            } }
        }
        return SessionDto(UserDto(principal.email), principal.workspaceId, workspaces)
    }

    fun requireContext(token: String?, write: Boolean = false): Principal {
        if (token == null) throw AccessFailure()
        return database.read { connection -> context(connection, token, write) }
    }

    internal fun context(connection: Connection, token: String?, write: Boolean): Principal {
        val principal = session(connection, token)
        if (principal.workspaceId == null) throw AccessFailure(true)
        connection.prepareStatement("SELECT role FROM workspace_memberships WHERE workspace_id=? AND user_id=?").use { statement ->
            statement.setString(1, principal.workspaceId); statement.setString(2, principal.userId)
            statement.executeQuery().use { row ->
                if (!row.next()) throw AccessFailure(true)
                val role = role(row.getString("role"))
                if (write && !role.canWrite) throw AccessFailure(true)
                return principal.copy(role = role)
            }
        }
    }

    fun categories(token: String?): CategoryDirectory {
      if (token == null) throw AccessFailure()
      return database.read { connection ->
        // Re-read authorization on the same connection as the requested data.
        val principal = context(connection, token, false)
        val revision = connection.prepareStatement("SELECT revision FROM cash_category_states WHERE workspace_id=?").use {
            it.setString(1, principal.workspaceId)
            it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
        }
        val records = connection.prepareStatement("SELECT id,parent_id,name,description,depth,sort_order FROM cash_categories WHERE workspace_id=?").use {
            it.setString(1, principal.workspaceId)
            it.executeQuery().use { rows -> buildList {
                while (rows.next()) add(CategoryItem(rows.getString("id"), rows.getString("parent_id"), rows.getString("name"),
                    rows.getString("description"), emptyList(), rows.getInt("depth"), rows.getInt("sort_order"), revision))
            } }
        }
        val items = records.associateBy { it.id }
        val nodes = records.map { CategoryNode(it.id, it.parentId, it.name, it.sortOrder) }
        val byId = nodes.associateBy { it.id }
        CategoryDirectory(revision, orderedCategoryNodes(nodes).map { node ->
            items.getValue(node.id).copy(path = categoryNamePath(node, byId))
        })
      }
    }

    private fun role(value: String): Role = when (value) {
        "admin" -> Role.ADMIN; "editor" -> Role.EDITOR; "viewer" -> Role.VIEWER
        else -> throw AccessFailure(true)
    }
}

typealias CategoryDirectory = com.finance.tracker.business.contracts.CategoryDirectoryDto
typealias CategoryItem = com.finance.tracker.business.contracts.CategoryDirectoryItemDto
