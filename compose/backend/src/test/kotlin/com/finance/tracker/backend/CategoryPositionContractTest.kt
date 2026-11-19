package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.USERS
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CategoryPositionContractTest(private val backend: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun backends(): List<Array<String>> = listOf(arrayOf("sqlite"), arrayOf("postgres"))
    }

    @Test fun theTargetIsACurrentSiblingAndTheGroupIsReorderedInOneWrite() = withCashWriteSetTestWorkspace(backend) { db, _, _ ->
        val email = "category-${UUID.randomUUID()}@example.invalid"
        val token = AuthenticationService(db).register(email, "synthetic-password").accessToken
        val workspaces = WorkspaceService(db)
        val workspace = workspaces.create(token, "Category position").activeWorkspaceId!!
        val categories = CategoryService(db)
        fun add(name: String, parent: String? = null) =
            categories.create(token, name, parent, expectedRevision = categories.list(token).revision)
        try {
            val first = add("First")
            val second = add("Second")
            val third = add("Third")
            val child = add("Child", first.id)
            val base = categories.list(token).revision
            testApplication {
                application { financeModule(db) }
                suspend fun move(direction: String, target: String?, revision: Long): HttpStatusCode =
                    client.post("/api/v1/cash-categories/${third.id}/reorder") {
                        bearerAuth(token)
                        header("X-FT-Workspace-ID", workspace)
                        contentType(ContentType.Application.Json)
                        val before = target?.let { ",\"target_id\":\"$it\"" }.orEmpty()
                        setBody("""{"direction":"$direction","expected_revision":$revision$before}""")
                    }.status
                assertEquals(HttpStatusCode.OK, move("before_target", first.id, base))
                assertEquals(listOf(third.id, first.id, child.id, second.id), categories.list(token).items.map { it.id })
                val moved = categories.list(token)
                assertEquals(base + 1, moved.revision)
                assertEquals(HttpStatusCode.Conflict, move("last", null, base))
                assertEquals(HttpStatusCode.BadRequest, move("before_target", child.id, moved.revision))
                assertEquals(moved, categories.list(token))
                assertEquals(HttpStatusCode.OK, move("last", null, moved.revision))
                assertEquals(listOf(first.id, child.id, second.id, third.id), categories.list(token).items.map { it.id })
            }
        } finally {
            workspaces.delete(token, "Category position")
            db.write { connection -> connection.jooq(db.sqlite).deleteFrom(USERS).where(USERS.EMAIL.eq(email)).execute() }
        }
    }
}
