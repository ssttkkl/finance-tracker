package com.finance.tracker.backend

import com.finance.tracker.business.contracts.AuthResponseDto
import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.jooq.exception.DataAccessException

class CredentialFailure : RuntimeException("invalid_credentials")

internal fun PreparedStatement.instant(index: Int, value: Instant, sqlite: Boolean) {
    val timestamp=value.truncatedTo(java.time.temporal.ChronoUnit.MICROS)
    if (sqlite) setString(index, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC).format(timestamp))
    else setObject(index, timestamp.atOffset(ZoneOffset.UTC))
}

class AuthenticationService(private val database: Database, private val passwords: Passwords = Passwords(),
    private val now: () -> Instant = Instant::now, private val newToken: () -> String = ::secureToken) {
    private val reads = ReadServices(database, now)

    fun register(email: String, password: String): AuthResponseDto {
        val normalized = email(email)
        if (password.codePointCount(0, password.length) !in 12..256) throw CredentialFailure()
        val encoded = passwords.hash(password)
        return database.write { connection ->
            val sql = connection.jooq(database.sqlite)
            if (sql.fetchExists(USERS, USERS.EMAIL.eq(normalized))) throw CredentialFailure()
            val user = UUID.randomUUID().toString()
            try {
                sql.insertInto(USERS).set(USERS.ID, user).set(USERS.EMAIL, normalized)
                    .set(USERS.PASSWORD_HASH, encoded).set(USERS.CREATED_AT, now()).execute()
            } catch (failure: DataAccessException) {
                val cause = failure.sqlCause() ?: throw failure
                val emailConflict = if (database.sqlite) cause.errorCode == 19 && cause.message.orEmpty().contains("users.email")
                    else cause.sqlState == "23505" && (cause as? org.postgresql.util.PSQLException)?.serverErrorMessage?.constraint == "users_email_key"
                if (emailConflict) throw CredentialFailure()
                throw failure
            }
            if (normalized == "admin@ssttkkl.fun") {
                if (sql.fetchExists(WORKSPACES, WORKSPACES.ID.eq("default"))) sql.insertInto(WORKSPACE_MEMBERSHIPS)
                    .set(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID, "default").set(WORKSPACE_MEMBERSHIPS.USER_ID, user)
                    .set(WORKSPACE_MEMBERSHIPS.ROLE, "admin").set(WORKSPACE_MEMBERSHIPS.CREATED_AT, now()).execute()
            }
            newSession(connection, user, normalized)
        }
    }

    fun login(email: String, password: String): AuthResponseDto {
        val normalized = email(email)
        val verified = database.read { connection ->
            connection.jooq(database.sqlite).select(USERS.ID, USERS.PASSWORD_HASH).from(USERS).where(USERS.EMAIL.eq(normalized))
                .fetchOne()?.let { it[USERS.ID]!! to it[USERS.PASSWORD_HASH]!! }
        }
        if (verified == null || !passwords.verify(verified.second, password)) throw AccessFailure()
        return database.write { connection ->
            val query = connection.jooq(database.sqlite).select(USERS.PASSWORD_HASH).from(USERS)
                .where(USERS.ID.eq(verified.first)).and(USERS.EMAIL.eq(normalized))
            val current = (if (database.sqlite) query else query.forUpdate()).fetchOne(USERS.PASSWORD_HASH)
            if (current != verified.second) throw AccessFailure()
            newSession(connection, verified.first, normalized)
        }
    }

    fun logout(token: String?) {
        if (token == null) return
        database.write { it.jooq(database.sqlite).deleteFrom(USER_SESSIONS).where(USER_SESSIONS.TOKEN_DIGEST.eq(tokenDigest(token))).execute() }
    }

    private fun newSession(connection: Connection, user: String, email: String): AuthResponseDto {
        val sql = connection.jooq(database.sqlite)
        val workspace = sql.select(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID).from(WORKSPACE_MEMBERSHIPS)
            .where(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).orderBy(WORKSPACE_MEMBERSHIPS.CREATED_AT).limit(1)
            .fetchOne(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID)
        val token = newToken()
        val instant = now()
        sql.insertInto(USER_SESSIONS).set(USER_SESSIONS.ID, UUID.randomUUID().toString()).set(USER_SESSIONS.USER_ID, user)
            .set(USER_SESSIONS.TOKEN_DIGEST, tokenDigest(token)).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, workspace)
            .set(USER_SESSIONS.EXPIRES_AT, instant.plusSeconds(30L * 86400)).set(USER_SESSIONS.CREATED_AT, instant).execute()
        val state = reads.state(connection, Principal(user, email, workspace))
        return AuthResponseDto(token, state.user, state.activeWorkspaceId, state.workspaces)
    }

    private fun email(value: String): String {
        val normalized = value.trim { it.code in pythonWhitespace }.lowercase(java.util.Locale.ROOT)
        if (normalized.isEmpty() || '@' !in normalized || normalized.codePointCount(0, normalized.length) > 320) throw CredentialFailure()
        strictUtf8(normalized)
        return normalized
    }
}
