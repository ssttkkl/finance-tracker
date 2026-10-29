package com.finance.tracker.backend

import com.finance.tracker.business.contracts.AuthResponseDto
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

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
            connection.prepareStatement("SELECT id FROM users WHERE email=?").use { query ->
                query.setString(1, normalized)
                if (query.executeQuery().use { it.next() }) throw CredentialFailure()
            }
            val user = UUID.randomUUID().toString()
            try {
                connection.prepareStatement("INSERT INTO users(id,email,password_hash,created_at) VALUES(?,?,?,?)").use { statement ->
                    statement.setString(1, user); statement.setString(2, normalized); statement.setString(3, encoded)
                    statement.instant(4, now(), database.sqlite); statement.executeUpdate()
                }
            } catch (failure: SQLException) {
                val emailConflict = if (database.sqlite) failure.errorCode == 19 && failure.message.orEmpty().contains("users.email")
                    else failure.sqlState == "23505" && (failure as? org.postgresql.util.PSQLException)?.serverErrorMessage?.constraint == "users_email_key"
                if (emailConflict) throw CredentialFailure()
                throw failure
            }
            if (normalized == "admin@ssttkkl.fun") {
                val exists = connection.createStatement().use { it.executeQuery("SELECT id FROM workspaces WHERE id='default'").use { it.next() } }
                if (exists) connection.prepareStatement("INSERT INTO workspace_memberships(workspace_id,user_id,role,created_at) VALUES('default',?,'admin',?)").use {
                    it.setString(1, user); it.instant(2, now(), database.sqlite); it.executeUpdate()
                }
            }
            newSession(connection, user, normalized)
        }
    }

    fun login(email: String, password: String): AuthResponseDto {
        val normalized = email(email)
        val verified = database.read { connection ->
            connection.prepareStatement("SELECT id,password_hash FROM users WHERE email=?").use {
                it.setString(1, normalized)
                it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getString(2) else null }
            }
        }
        if (verified == null || !passwords.verify(verified.second, password)) throw AccessFailure()
        return database.write { connection ->
            val current = connection.prepareStatement("SELECT password_hash FROM users WHERE id=? AND email=?" + if (database.sqlite) "" else " FOR UPDATE").use {
                it.setString(1, verified.first); it.setString(2, normalized)
                it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
            if (current != verified.second) throw AccessFailure()
            newSession(connection, verified.first, normalized)
        }
    }

    fun logout(token: String?) {
        if (token == null) return
        database.write { connection -> connection.prepareStatement("DELETE FROM user_sessions WHERE token_digest=?").use {
            it.setString(1, tokenDigest(token)); it.executeUpdate()
        } }
    }

    private fun newSession(connection: Connection, user: String, email: String): AuthResponseDto {
        val workspace = connection.prepareStatement("SELECT workspace_id FROM workspace_memberships WHERE user_id=? ORDER BY created_at LIMIT 1").use {
            it.setString(1, user); it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
        val token = newToken()
        val instant = now()
        connection.prepareStatement("INSERT INTO user_sessions(id,user_id,token_digest,active_workspace_id,expires_at,created_at) VALUES(?,?,?,?,?,?)").use {
            it.setString(1, UUID.randomUUID().toString()); it.setString(2, user); it.setString(3, tokenDigest(token)); it.setString(4, workspace)
            it.instant(5, instant.plusSeconds(30L * 86400), database.sqlite); it.instant(6, instant, database.sqlite); it.executeUpdate()
        }
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
