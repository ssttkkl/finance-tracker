package com.finance.tracker.backend

import com.finance.tracker.business.contracts.Role
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication

class AuthenticationServiceMigrationTest {
    @Test fun bootstrapRegistrationOwnsAnExistingDefaultWorkspaceAndUnrelatedUsersDoNot() {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_AUTH_CONTRACT_DIR")), "database.json").readText()).jsonObject
        val database = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        database.write { connection -> connection.createStatement().use {
            it.executeUpdate("INSERT INTO workspaces(id,name,created_at) VALUES('default','default','2026-01-01 00:00:00')")
        } }
        try {
            val authentication = AuthenticationService(database)
            val bootstrap = authentication.register("admin@ssttkkl.fun", "a secure password")
            assertEquals("default", bootstrap.activeWorkspaceId)
            assertEquals(1, bootstrap.workspaces.size)
            assertEquals("default", bootstrap.workspaces.single().id)
            assertEquals("default", bootstrap.workspaces.single().name)
            assertEquals(Role.ADMIN, bootstrap.workspaces.single().role)
            val unrelated = authentication.register("unrelated-default@example.invalid", "a secure password")
            assertEquals(emptyList(), unrelated.workspaces)
            assertNull(unrelated.activeWorkspaceId)
            assertEquals("default", ReadServices(database).state(bootstrap.accessToken).activeWorkspaceId)
            testApplication {
                application { financeModule(database) }
                val members = client.get("/api/v1/auth/members") { bearerAuth(bootstrap.accessToken) }
                assertEquals(200, members.status.value)
                val memberId = Json.parseToJsonElement(members.bodyAsText()).jsonObject.getValue("members").jsonArray.single()
                    .jsonObject.getValue("user_id").jsonPrimitive.content
                val demote = client.put("/api/v1/auth/members/$memberId") {
                    bearerAuth(bootstrap.accessToken); contentType(ContentType.Application.Json); setBody("""{"role":"editor"}""")
                }
                val remove = client.delete("/api/v1/auth/members/$memberId") { bearerAuth(bootstrap.accessToken) }
                for (response in listOf(demote, remove)) {
                    assertEquals(400, response.status.value)
                    assertEquals("last_admin", Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        .getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                }
                val workspaces = WorkspaceService(database)
                val invitation = workspaces.invite(bootstrap.accessToken, "editor")
                workspaces.accept(unrelated.accessToken, invitation.token)
                val editorMembers = client.get("/api/v1/auth/members") { bearerAuth(unrelated.accessToken) }
                assertEquals(200, editorMembers.status.value)
                assertEquals(setOf("admin", "editor"), Json.parseToJsonElement(editorMembers.bodyAsText()).jsonObject.getValue("members")
                    .jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content }.toSet())
                assertEquals(buildJsonObject { put("id", "default"); put("name", "default") },
                    Json.parseToJsonElement(editorMembers.bodyAsText()).jsonObject.getValue("workspace"))
                val forbiddenInvite = client.post("/api/v1/auth/invitations") {
                    bearerAuth(unrelated.accessToken); contentType(ContentType.Application.Json); setBody("""{"role":"viewer"}""")
                }
                val forbiddenEdit = client.put("/api/v1/auth/members/$memberId") {
                    bearerAuth(unrelated.accessToken); contentType(ContentType.Application.Json); setBody("""{"role":"viewer"}""")
                }
                val forbiddenDelete = client.delete("/api/v1/auth/workspace") {
                    bearerAuth(unrelated.accessToken); contentType(ContentType.Application.Json); setBody("""{"name":"default"}""")
                }
                for (response in listOf(forbiddenInvite, forbiddenEdit, forbiddenDelete)) {
                    assertEquals(403, response.status.value)
                    assertEquals("workspace_forbidden", Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        .getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                }
                assertEquals("default", workspaces.members(bootstrap.accessToken).workspace.id)
                val viewerInvitation=client.post("/api/v1/auth/invitations") {
                    bearerAuth(bootstrap.accessToken);contentType(ContentType.Application.Json);setBody("""{"role":"viewer"}""")
                }
                assertEquals(200,viewerInvitation.status.value)
                val invitationToken=Json.parseToJsonElement(viewerInvitation.bodyAsText()).jsonObject.getValue("token").jsonPrimitive.content
                val viewerRegistration=client.post("/api/v1/auth/register") {
                    contentType(ContentType.Application.Json);setBody("""{"email":"viewer-native@example.invalid","password":"a secure password"}""")
                }
                assertEquals(200,viewerRegistration.status.value)
                val viewerToken=Json.parseToJsonElement(viewerRegistration.bodyAsText()).jsonObject.getValue("access_token").jsonPrimitive.content
                val accept=client.post("/api/v1/auth/invitations/$invitationToken/accept") {bearerAuth(viewerToken)}
                assertEquals(200,accept.status.value)
                assertEquals(200,client.get("/api/v1/accounts?view=cash") {bearerAuth(viewerToken)}.status.value)
                assertEquals(403,client.post("/api/v1/cash-records") {bearerAuth(viewerToken);contentType(ContentType.Application.Json);setBody("{}")} .status.value)
                assertEquals(400,client.post("/api/v1/auth/invitations/$invitationToken/accept") {bearerAuth(viewerToken)}.status.value)

            }
        } finally {
            database.write { connection -> connection.createStatement().use {
                it.executeUpdate("DELETE FROM workspace_invitations WHERE workspace_id='default'")
                it.executeUpdate("DELETE FROM users WHERE email IN ('admin@ssttkkl.fun','unrelated-default@example.invalid','viewer-native@example.invalid')")
                it.executeUpdate("DELETE FROM workspaces WHERE id='default'")
            } }
        }
    }
}
