package com.finance.tracker.backend

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.net.URI
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Assume.assumeTrue

class WorkspaceExpectedScopeHttpTest {
    @Test
    fun sqlite() {
        val directory = Files.createTempDirectory("ft-workspace-scope-")
        try { exercise(Database("jdbc:sqlite:${directory.resolve("workspace_test.db")}")) }
        finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun postgres() {
        val url = System.getenv("FT_TEST_POSTGRES_URL")
        assumeTrue("Explicit PostgreSQL test database required", url != null)
        val configured = checkNotNull(url)
        require(URI(configured.replaceFirst("postgresql+psycopg://", "postgresql://")).path.endsWith("_test"))
        val config = databaseConnectionConfig(mapOf("FT_DATABASE_URL" to configured))
        exercise(Database(config.jdbcUrl, config.user, config.password))
    }

    private fun exercise(database: Database) {
        database.bootstrapSchema()
        val auth = AuthenticationService(database)
        val workspaces = WorkspaceService(database)
        fun register() = auth.register("workspace-scope-${UUID.randomUUID()}@example.invalid", "synthetic secure password")
        val admin = register().accessToken
        val workspaceA = assertNotNull(workspaces.create(admin, "Workspace A").activeWorkspaceId)
        val workspaceB = assertNotNull(workspaces.create(admin, "Workspace B").activeWorkspaceId)
        val viewer = workspaces.invite(admin, "viewer")
        val member = register().accessToken
        workspaces.accept(member, viewer.token)
        val memberId = workspaces.members(admin).members.single { !it.isSelf }.userId

        val invitations = com.finance.tracker.backend.persistence.generated.tables.references.WORKSPACE_INVITATIONS
        fun inviteCount() = database.read { it.jooq(database.sqlite).fetchCount(invitations, invitations.WORKSPACE_ID.eq(workspaceB)) }
        val beforeInvitations = inviteCount()

        testApplication {
            application { financeModule(database) }
            suspend fun send(method: HttpMethod, path: String, scope: String?, body: String? = null, token: String = admin) =
                client.request("/api/v1/auth/$path") {
                    this.method = method
                    bearerAuth(token)
                    scope?.let { this.url.parameters.append("workspace_id", it) }
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                }

            val changes = listOf(
                Triple(HttpMethod.Put, "workspace", """{"name":"Unexpected change"}"""),
                Triple(HttpMethod.Delete, "workspace", """{"name":"Workspace B"}"""),
                Triple(HttpMethod.Post, "invitations", """{"role":"editor"}"""),
                Triple(HttpMethod.Put, "members/$memberId", """{"role":"editor"}"""),
                Triple(HttpMethod.Delete, "members/$memberId", null),
            )
            workspaces.select(admin, workspaceA)
            assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, "workspaces/$workspaceB/select", null).status)
            for ((method, path, body) in changes) {
                val response = send(method, path, workspaceA, body)
                assertEquals(HttpStatusCode.Conflict, response.status, "${method.value} $path")
                assertEquals("workspace_conflict", kotlinx.serialization.json.Json.decodeFromString<kotlinx.serialization.json.JsonObject>(response.bodyAsText()).apiError())
            }
            assertEquals(beforeInvitations, inviteCount())
            assertEquals("Workspace B", workspaces.members(admin).workspace.name)
            assertEquals("viewer", workspaces.members(admin).members.single { !it.isSelf }.role.name.lowercase())
            assertEquals("Workspace A", workspaces.select(admin, workspaceA).workspaces.single { it.id == workspaceA }.name)
            workspaces.select(admin, workspaceB)
            assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Put, "workspace", workspaceB, changes.first().third, member).status)
            assertEquals(HttpStatusCode.OK, send(HttpMethod.Put, "workspace", workspaceB, """{"name":"Matched scope"}""").status)
            assertEquals(HttpStatusCode.OK, send(HttpMethod.Put, "workspace", null, """{"name":"Legacy caller"}""").status)
            assertEquals("Legacy caller", workspaces.members(admin).workspace.name)
        }
    }
}
