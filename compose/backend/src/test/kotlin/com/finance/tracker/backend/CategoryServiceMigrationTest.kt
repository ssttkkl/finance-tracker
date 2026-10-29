package com.finance.tracker.backend

import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CategoryServiceMigrationTest {
    private fun withService(now: () -> Instant = Instant::now, block: (CategoryService, String, Database) -> Unit) {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")), "database.json").readText()).jsonObject
        val database = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val workspaces = WorkspaceService(database)
        val created = workspaces.create("synthetic-admin", "分类规则测试")
        try { block(CategoryService(database, now = now), "synthetic-admin", database) }
        finally {
            workspaces.select("synthetic-admin", created.activeWorkspaceId!!)
            workspaces.delete("synthetic-admin", "分类规则测试")
            workspaces.select("synthetic-admin", "fixture-a")
        }
    }

    @Test fun categoryTreeEnforcesWorkspaceDepthAndSiblingNameRules() = withService { service, token, _ ->
        val root = service.create(token, "生活")
        val child = service.create(token, "日常", parentId = root.id, expectedRevision = root.revision)
        assertEquals(listOf("生活", "日常"), child.path)
        assertEquals(2, child.depth)
        assertEquals(2L, service.list(token).revision)
        assertEquals("category.duplicate_name", assertFailsWith<CategoryFailure> {
            service.create(token, " 日常 ", parentId = root.id, expectedRevision = child.revision)
        }.code)
        var current = child
        for (name in listOf("三级", "四级", "五级")) current = service.create(token, name, current.id, expectedRevision = current.revision)
        assertEquals("category.depth_limit", assertFailsWith<CategoryFailure> {
            service.create(token, "六级", current.id, expectedRevision = current.revision)
        }.code)
        assertEquals("category.cycle", assertFailsWith<CategoryFailure> {
            service.move(token, root.id, current.id, current.revision)
        }.code)
        assertEquals("category.not_found", assertFailsWith<CategoryFailure> {
            service.move("synthetic-new-directory", root.id, null, 0)
        }.code)
    }

    @Test fun categoryMoveKeepsStableIdentityAndCorrectDescendantPathsAndDepths() = withService { service, token, _ ->
        val work = service.create(token, "工作")
        val food = service.create(token, "餐饮")
        val lunch = service.create(token, "午餐", food.id, expectedRevision = food.revision)
        val moved = service.move(token, food.id, work.id, lunch.revision)
        assertEquals(food.id, moved.id)
        assertEquals(listOf("工作", "餐饮"), moved.path)
        assertEquals(2, moved.depth)
        val listed = service.list(token)
        val descendant = listed.items.single { it.id == lunch.id }
        assertEquals(listOf("工作", "餐饮", "午餐"), descendant.path)
        assertEquals(3, descendant.depth)
        val fourth = service.create(token, "四级", lunch.id, expectedRevision = listed.revision)
        val fifth = service.create(token, "五级", fourth.id, expectedRevision = fourth.revision)
        assertEquals("category.depth_limit", assertFailsWith<CategoryFailure> {
            service.create(token, "六级", fifth.id, expectedRevision = fifth.revision)
        }.code)
        val promoted = service.move(token, food.id, null, fifth.revision)
        assertEquals(1, promoted.depth)
        val depths = service.list(token).items.associate { it.id to it.depth }
        assertEquals(listOf(1, 2, 3, 4), listOf(food, lunch, fourth, fifth).map { depths.getValue(it.id) })
        assertEquals(5, service.create(token, "新的五级", fifth.id, expectedRevision = promoted.revision).depth)
    }

    @Test fun categoryReorderChangesSiblingOrderAndRequiresCurrentRevision() = withService { service, token, _ ->
        val first = service.create(token, "第一项")
        val second = service.create(token, "第二项", expectedRevision = first.revision)
        val moved = service.reorder(token, second.id, "before", second.revision)
        assertEquals(listOf("第二项", "第一项"), service.list(token).items.map { it.name })
        assertEquals("第二项", moved.name)
        assertEquals("category.revision_conflict", assertFailsWith<CategoryFailure> {
            service.reorder(token, first.id, "after", second.revision)
        }.code)
    }

    @Test fun categoryMoveRejectsDuplicateDestinationSiblingWithoutAnyWrites() = withService { service, token, _ ->
        val destination = service.create(token, "目标")
        val source = service.create(token, "来源", expectedRevision = destination.revision)
        service.create(token, "重复", destination.id, expectedRevision = source.revision)
        val duplicate = service.create(token, "重复", expectedRevision = service.list(token).revision)
        val before = service.list(token)
        assertEquals("category.duplicate_name", assertFailsWith<CategoryFailure> {
            service.move(token, duplicate.id, destination.id, duplicate.revision)
        }.code)
        assertEquals(before, service.list(token))
    }

    @Test fun reorderingUpdatesBothChangedSiblingTimestampsWithoutChangingTheOtherRevision() {
        val clock = AtomicReference(Instant.parse("2026-10-28T14:00:00Z"))
        withService(now = clock::get) { service, token, database ->
            val first = service.create(token, "第一项")
            val second = service.create(token, "第二项", expectedRevision = first.revision)
            clock.set(Instant.parse("2026-10-28T14:00:01Z"))
            service.reorder(token, second.id, "before", second.revision)
            database.read { connection -> connection.prepareStatement("SELECT revision,updated_at FROM cash_categories WHERE id=?").use { statement ->
                for ((id, revision) in listOf(first.id to 1L, second.id to 2L)) {
                    statement.setString(1, id)
                    statement.executeQuery().use {
                        assertTrue(it.next())
                        assertEquals(revision, it.getLong(1))
                        val raw = it.getString(2).replace(' ', 'T')
                        val updated = if (database.sqlite) java.time.LocalDateTime.parse(raw).toInstant(java.time.ZoneOffset.UTC)
                            else java.time.OffsetDateTime.parse(raw).toInstant()
                        assertEquals(clock.get(), updated, id)
                    }
                }
            } }
        }
    }

    @Test fun invalidIntegerWhitespaceIsRejectedByEveryWriteRouteWithoutInitializingTheDirectory() = withService { service, token, database ->
        val before = service.list(token)
        val workspace = ReadServices(database).state(token).activeWorkspaceId!!
        testApplication {
            application { financeModule(database) }
            for (code in 0x1c..0x1f) for (text in listOf("${code.toChar()}0", "0${code.toChar()}")) {
                for ((method, path) in listOf(HttpMethod.Post to "/api/v1/cash-categories",
                    HttpMethod.Patch to "/api/v1/cash-categories/missing",
                    HttpMethod.Post to "/api/v1/cash-categories/missing/reorder")) {
                    val response = client.request(path) {
                        this.method = method
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(buildJsonObject { put("name", "拒绝"); put("direction", "before"); put("expected_revision", text) }.toString())
                    }
                    assertEquals(400, response.status.value)
                    assertTrue(Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code")
                        .jsonPrimitive.content.startsWith("invalid literal for int() with base 10:"))
                    assertEquals(before, service.list(token))
                }
            }
        }
        database.read { connection -> connection.prepareStatement("SELECT COUNT(*) FROM cash_category_states WHERE workspace_id=?").use {
            it.setString(1, workspace); it.executeQuery().use { rows -> rows.next(); assertEquals(0, rows.getInt(1)) }
        } }
    }
}
