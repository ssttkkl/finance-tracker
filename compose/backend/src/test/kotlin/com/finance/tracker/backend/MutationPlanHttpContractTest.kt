package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class MutationPlanHttpContractTest {
    private fun database() = Json.parseToJsonElement(Path.of(requireNotNull(
        System.getenv("FT_KOTLIN_PLAN_BUILD_CONTRACT_DIR"))).resolve("database.json").readText()).jsonObject.let {
        Database(it.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }

    private val planBody = buildJsonObject {
        put("intent", "save_draft"); put("transaction_id", "1001"); put("expected_revision", 1)
        put("patch", buildJsonObject { put("note", "新备注") })
    }

    @Test fun saveDraftPlanPublishesAndReadsThroughHttp() = testApplication {
        val db = database()
        val expected = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv(
            "FT_KOTLIN_PLAN_BUILD_CONTRACT_DIR"))).resolve("builds.json").readText()).jsonArray.first().jsonObject
        application { financeModule(db) }
        val created = client.post("/api/v1/cash-mutations/plans") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            contentType(ContentType.Application.Json); setBody(planBody.toString())
        }
        assertEquals(200, created.status.value, created.bodyAsText())
        val view = Json.parseToJsonElement(created.bodyAsText()).jsonObject
        val expectedView = expected.getValue("view").jsonObject
        assertEquals(expectedView.getValue("items"), view.getValue("items"))
        assertEquals(expectedView.getValue("summary"), view.getValue("summary"))
        assertEquals(expectedView.getValue("can_commit"), view.getValue("can_commit"))
        assertEquals(expectedView.getValue("item_counts"), view.getValue("item_counts"))
        assertEquals(expectedView.getValue("invalidated_choices"), view.getValue("invalidated_choices"))
        assertEquals(expectedView.getValue("additional_effects"), view.getValue("additional_effects"))
        assertEquals(JsonNull, view.getValue("pages").jsonObject.getValue("items"))
        val planId = view.getValue("plan_id").jsonPrimitive.content
        val contextId = view.getValue("edit_context_id").jsonPrimitive.content
        assertTrue(planId.matches(Regex("mp_[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{16}")))
        assertEquals(contextId, planId.substringAfter("mp_").substringBefore('.'))

        for (path in listOf("/api/v1/cash-mutations/plans/$planId",
                "/api/v1/cash-mutations/edit-contexts/$contextId/plan")) {
            val response = client.get(path) { header(HttpHeaders.Authorization, "Bearer synthetic-admin") }
            assertEquals(200, response.status.value, response.bodyAsText())
            assertEquals(view, Json.parseToJsonElement(response.bodyAsText()), path)
        }
        val items = client.get("/api/v1/cash-mutations/plans/$planId/items") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
        }
        assertEquals(200, items.status.value)
        assertEquals(buildJsonObject {
            put("items", view.getValue("items")); put("next_cursor", JsonNull)
        }, Json.parseToJsonElement(items.bodyAsText()))
        val item = client.get("/api/v1/cash-mutations/plans/$planId/items/pi_1") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
        }
        assertEquals(200, item.status.value, item.bodyAsText())
        assertEquals(expected.getValue("items").jsonObject.getValue("pi_1"),
            Json.parseToJsonElement(item.bodyAsText()))
    }

    @Test fun editRecordPlanPublishesPostedViewAndItem() = testApplication {
        val db = database()
        val expected = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv(
            "FT_KOTLIN_PLAN_BUILD_CONTRACT_DIR"))).resolve("builds.json").readText()).jsonArray[5].jsonObject
        application { financeModule(db) }
        val body = buildJsonObject {
            put("intent", "edit_record"); put("transaction_id", "1002"); put("expected_revision", 1)
            put("patch", buildJsonObject { put("note", "改备注") })
        }
        val created = client.post("/api/v1/cash-mutations/plans") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            contentType(ContentType.Application.Json); setBody(body.toString())
        }
        assertEquals(200, created.status.value, created.bodyAsText())
        val view = Json.parseToJsonElement(created.bodyAsText()).jsonObject
        val expectedView = expected.getValue("view").jsonObject
        assertEquals(expectedView.getValue("items"), view.getValue("items"))
        assertEquals(expectedView.getValue("summary"), view.getValue("summary"))
        assertEquals(expectedView.getValue("item_counts"), view.getValue("item_counts"))
        assertEquals(expectedView.getValue("additional_effects"), view.getValue("additional_effects"))
        val planId = view.getValue("plan_id").jsonPrimitive.content
        val item = client.get("/api/v1/cash-mutations/plans/$planId/items/pi_1") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
        }
        assertEquals(expected.getValue("items").jsonObject.getValue("pi_1"), Json.parseToJsonElement(item.bodyAsText()))
    }

    @Test fun planErrorsStayStableAndDoNotWrite() = testApplication {
        val db = database()
        application { financeModule(db) }
        suspend fun post(value: JsonObject, token: String? = "synthetic-admin"): Pair<Int, String> {
            val response = client.post("/api/v1/cash-mutations/plans") {
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                contentType(ContentType.Application.Json); setBody(value.toString())
            }
            return response.status.value to response.bodyAsText()
        }
        val stale = post(buildJsonObject {
            put("intent", "save_draft"); put("transaction_id", "1001"); put("expected_revision", 2)
        })
        val fixed = post(buildJsonObject {
            put("intent", "edit_record"); put("transaction_id", "1002"); put("expected_revision", 1)
            put("patch", buildJsonObject { put("amount", "16") })
        })
        assertEquals(422, fixed.first); assertEquals("fixed_field_conflict", errorCode(fixed.second))
        assertEquals(409, stale.first); assertEquals("record_changed", errorCode(stale.second))
        val unknown = post(buildJsonObject {
            put("intent", "save_draft"); put("transaction_id", "1001"); put("expected_revision", 1)
            put("patch", buildJsonObject { put("unknown_field", "x") })
        })
        assertEquals(422, unknown.first); assertEquals("field_not_editable", errorCode(unknown.second))
        val invalidIntent = post(buildJsonObject { put("intent", "unknown"); put("transaction_id", "1001")
            put("expected_revision", 1) })
        assertEquals(422, invalidIntent.first); assertEquals("plan_input_invalid", errorCode(invalidIntent.second))
        val viewer = post(planBody, "synthetic-viewer")
        assertEquals(403, viewer.first); assertEquals("workspace_forbidden", errorCode(viewer.second))
        val anonymous = post(planBody, null)
        assertEquals(401, anonymous.first); assertEquals("authentication_required", errorCode(anonymous.second))
        val malformed = client.get("/api/v1/cash-mutations/plans/not-a-plan") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
        }
        assertEquals(404, malformed.status.value); assertEquals("plan_not_found", errorCode(malformed.bodyAsText()))
        val missingContext = client.get("/api/v1/cash-mutations/plans/mp_${"z".repeat(43)}.${"y".repeat(16)}") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
        }
        assertEquals(410, missingContext.status.value); assertEquals("plan_expired", errorCode(missingContext.bodyAsText()))
        val row = db.read { connection ->
            connection.createStatement().use { statement -> statement.executeQuery(
                "SELECT note,row_revision FROM cash_transactions WHERE workspace_id='plan-a' AND id=1001").use { rows ->
                rows.next(); rows.getString(1) to rows.getLong(2)
            } }
        }
        assertEquals("旧备注" to 1L, row)
    }

    private fun errorCode(body: String): String? =
        (Json.parseToJsonElement(body) as? JsonObject)?.get("error")?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
}
