package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.USER_SESSIONS
import com.finance.tracker.backend.persistence.generated.tables.references.USERS
import com.finance.tracker.business.contracts.SessionDto
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

/** Uses only the explicitly configured synthetic fixture database; never discovers or starts a service. */
@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class LedgerSessionRevocationContractTest {
    private fun database(): Database {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),
            "database.json").readText()).jsonObject
        val url = config.getValue("jdbc_url").jsonPrimitive.content
        if (url.startsWith("jdbc:postgresql:")) {
            requireNotNull(System.getenv("FT_TEST_POSTGRES_URL"))
            require(url.substringAfterLast('/').substringBefore('?').endsWith("_test"))
        }
        return Database(url, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }

    @Test fun removingTheOnlyMembershipReturnsNoActiveWorkspace() = verifyRevocation(false)
    @Test fun removingTheSelectedMembershipReturnsNoActiveWorkspace() = verifyRevocation(true)

    private fun verifyRevocation(keepOtherWorkspace: Boolean) = testApplication {
        val database = database(); val reads = ReadServices(database); val workspaces = WorkspaceService(database)
        application { financeModule(database) }
        val owner = "synthetic-admin"
        val originalOwner = reads.state(owner)
        val suffix = UUID.randomUUID().toString()
        var member: String? = null
        var memberId: String? = null
        var createdName: String? = null
        var otherName: String? = null
        var otherId: String? = null
        var completed = false
        val cleanupFailures = mutableListOf<Throwable>()
        fun cleanup(step: () -> Unit) { runCatching(step).onFailure(cleanupFailures::add) }
        try {
            val token = AuthenticationService(database).register(
                "ledger-session-$suffix@example.invalid", "synthetic secure password").accessToken
            member = token
            memberId = database.read { connection -> reads.session(connection, token).userId }
            if (keepOtherWorkspace) {
                otherName = "still-accessible-$suffix"
                otherId = requireNotNull(workspaces.create(token, requireNotNull(otherName)).activeWorkspaceId)
            }
            val originalMember = reads.state(token)
            createdName = "ledger-session-revocation-$suffix"
            val workspace = requireNotNull(workspaces.create(owner, requireNotNull(createdName)).activeWorkspaceId)
            workspaces.accept(token, workspaces.invite(owner, "viewer").token)
            workspaces.select(token, workspace)
            assertEquals(workspace, reads.state(token).activeWorkspaceId)
            workspaces.removeMember(owner, requireNotNull(memberId))
            val persisted = sessionRow(database, token)
            assertEquals(workspace, persisted["active_workspace_id"])
            assertTrue(assertFailsWith<AccessFailure> { LedgerReadService(database).snapshot(token) }.forbidden)
            val verified = reads.state(token)
            assertEquals(originalMember.user, verified.user)
            assertEquals(originalMember.workspaces, verified.workspaces)
            assertNull(verified.activeWorkspaceId)
            val response = client.get("/api/v1/auth/session") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(verified, Json.decodeFromString<SessionDto>(response.bodyAsText()))
            assertTrue(body.containsKey("active_workspace_id"), "a revoked selection must be explicit, not omitted")
            assertEquals(JsonNull, body["active_workspace_id"])
            assertEquals(verified.workspaces.size, body.getValue("workspaces").jsonArray.size)
            assertEquals(persisted, sessionRow(database, token), "reading the session must not write its selection")
            assertEquals(workspace, reads.state(owner).activeWorkspaceId)
            assertEquals((originalOwner.workspaces.map { it.id } + workspace).toSet(),
                reads.state(owner).workspaces.map { it.id }.toSet())
            completed = true
        } finally {
            createdName?.let { name -> cleanup { workspaces.delete(owner, name) } }
            cleanup { restoreSelection(database, owner, originalOwner) }
            if (keepOtherWorkspace) {
                val token = member
                val name = otherName
                val id = otherId
                if (token != null && name != null && id != null) {
                    cleanup { workspaces.select(token, id) }
                    cleanup { workspaces.delete(token, name) }
                }
            }
            memberId?.let { id -> cleanup {
                database.write { connection ->
                    val sql = connection.jooq(database.sqlite)
                    sql.deleteFrom(USER_SESSIONS).where(USER_SESSIONS.USER_ID.eq(id)).execute()
                    assertEquals(1, sql.deleteFrom(USERS).where(USERS.ID.eq(id)).execute())
                }
            } }
            if (completed) {
                cleanupFailures.drop(1).forEach { it.printStackTrace() }
                cleanupFailures.firstOrNull()?.let { throw it }
            } else {
                cleanupFailures.forEach { it.printStackTrace() }
            }
        }
    }

    private fun sessionRow(database: Database, token: String): Map<String, Any?> = database.read { connection ->
        connection.jooq(database.sqlite).selectFrom(USER_SESSIONS)
            .where(USER_SESSIONS.TOKEN_DIGEST.eq(tokenDigest(token))).fetchSingle().intoMap()
    }

    private fun restoreSelection(database: Database, token: String, session: SessionDto) = database.write { connection ->
        assertEquals(1, connection.jooq(database.sqlite).update(USER_SESSIONS)
            .set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, session.activeWorkspaceId)
            .where(USER_SESSIONS.TOKEN_DIGEST.eq(tokenDigest(token))).execute())
    }
}
