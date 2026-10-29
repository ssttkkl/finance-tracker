package com.finance.tracker.backend

import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.CategoryNode
import com.finance.tracker.business.domain.categoryNamePath
import com.finance.tracker.business.domain.orderedCategoryNodes
import com.finance.tracker.backend.persistence.generated.tables.references.*
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
        val sessions = USER_SESSIONS.`as`("s")
        val users = USERS.`as`("u")
        val expiry = sessions.EXPIRES_AT.coerce(String::class.java)
        val query = connection.jooq(database.sqlite).select(sessions.USER_ID, sessions.ACTIVE_WORKSPACE_ID, expiry, users.EMAIL)
            .from(sessions).join(users).on(users.ID.eq(sessions.USER_ID)).where(sessions.TOKEN_DIGEST.eq(tokenDigest(token)))
        val row = (if (lock && !database.sqlite) query.forUpdate().of(sessions) else query).fetchOne() ?: throw AccessFailure()
        requireActiveExpiry(row[expiry]!!)
        return Principal(row[sessions.USER_ID]!!, row[users.EMAIL]!!, row[sessions.ACTIVE_WORKSPACE_ID])
    }

    private fun requireActiveExpiry(value:String) {
        val raw=value.replace(' ','T')
        val expiry=try {
            if(database.sqlite) LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC) else OffsetDateTime.parse(raw).toInstant()
        } catch(_:java.time.DateTimeException) {throw StorageFailure("storage.schema")}
        if(!expiry.isAfter(now())) throw AccessFailure()
    }

    internal fun lockedContext(connection: Connection, token: String?, expectedWorkspace: String, write: Boolean): Principal =
        joinedContext(connection, token, expectedWorkspace, write, lock = true)

    private fun joinedContext(connection: Connection, token: String?, expectedWorkspace: String?, write: Boolean, lock: Boolean): Principal {
        if (token == null) throw AccessFailure()
        val sessions = USER_SESSIONS.`as`("s")
        val users = USERS.`as`("u")
        val members = WORKSPACE_MEMBERSHIPS.`as`("m")
        val expiry = sessions.EXPIRES_AT.coerce(String::class.java)
        val query = connection.jooq(database.sqlite).select(sessions.USER_ID, sessions.ACTIVE_WORKSPACE_ID, expiry, users.EMAIL, members.ROLE)
            .from(sessions).join(users).on(users.ID.eq(sessions.USER_ID))
            .leftJoin(members).on(members.WORKSPACE_ID.eq(sessions.ACTIVE_WORKSPACE_ID)).and(members.USER_ID.eq(sessions.USER_ID))
            .where(sessions.TOKEN_DIGEST.eq(tokenDigest(token)))
        val row = (if (lock && !database.sqlite) query.forUpdate().of(sessions) else query).fetchOne() ?: throw AccessFailure()
        requireActiveExpiry(row[expiry]!!)
        val workspace = row[sessions.ACTIVE_WORKSPACE_ID] ?: throw AccessFailure(true)
        if (expectedWorkspace != null && workspace != expectedWorkspace) throw AccessFailure(true)
        val currentRole = row[members.ROLE]?.let(::role) ?: throw AccessFailure(true)
        if (write && !currentRole.canWrite) throw AccessFailure(true)
        return Principal(row[sessions.USER_ID]!!, row[users.EMAIL]!!, workspace, currentRole)
    }

    fun state(token: String?): SessionDto {
      if (token == null) throw AccessFailure()
      return database.read { connection ->
        state(connection, session(connection, token))
      }
    }

    internal fun state(connection: Connection, principal: Principal): SessionDto {
        val members = WORKSPACE_MEMBERSHIPS
        val spaces = WORKSPACES
        val workspaces = connection.jooq(database.sqlite).select(spaces.ID, spaces.NAME, members.ROLE).from(members)
            .join(spaces).on(spaces.ID.eq(members.WORKSPACE_ID)).where(members.USER_ID.eq(principal.userId)).orderBy(spaces.CREATED_AT)
            .fetch { WorkspaceDto(it[spaces.ID]!!, it[spaces.NAME]!!, role(it[members.ROLE]!!)) }
        return SessionDto(UserDto(principal.email), principal.workspaceId, workspaces)
    }

    fun requireContext(token: String?, write: Boolean = false): Principal {
        if (token == null) throw AccessFailure()
        return database.read { connection -> context(connection, token, write) }
    }

    internal fun currentContext(token: String?): Principal {
        if (token == null) throw AccessFailure()
        return database.read { connection -> joinedContext(connection, token, null, write = false, lock = false) }
    }

    internal fun context(connection: Connection, token: String?, write: Boolean): Principal {
        val principal = session(connection, token)
        if (principal.workspaceId == null) throw AccessFailure(true)
        val members = WORKSPACE_MEMBERSHIPS
        val currentRole = connection.jooq(database.sqlite).select(members.ROLE).from(members)
            .where(members.WORKSPACE_ID.eq(principal.workspaceId)).and(members.USER_ID.eq(principal.userId))
            .fetchOne(members.ROLE)?.let(::role) ?: throw AccessFailure(true)
        if (write && !currentRole.canWrite) throw AccessFailure(true)
        return principal.copy(role = currentRole)
    }

    fun categories(token: String?): CategoryDirectory {
      if (token == null) throw AccessFailure()
      return database.read { connection ->
        // Re-read authorization on the same connection as the requested data.
        val principal = context(connection, token, false)
        val sql = connection.jooq(database.sqlite)
        val state = CASH_CATEGORY_STATES
        val revision = sql.select(state.REVISION).from(state).where(state.WORKSPACE_ID.eq(principal.workspaceId)).fetchOne(state.REVISION) ?: 0L
        val category = CASH_CATEGORIES
        val records = sql.select(category.ID, category.PARENT_ID, category.NAME, category.DESCRIPTION, category.DEPTH, category.SORT_ORDER)
            .from(category).where(category.WORKSPACE_ID.eq(principal.workspaceId)).fetch {
                CategoryItem(it[category.ID]!!, it[category.PARENT_ID], it[category.NAME]!!, it[category.DESCRIPTION],
                    emptyList(), it[category.DEPTH]!!.toInt(), it[category.SORT_ORDER]!!.toInt(), revision)
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
