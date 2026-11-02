package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.server.routing.*
import io.ktor.server.response.respondText
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

class OpenApiContractTest {
    private fun offlineDatabase() = Database("jdbc:sqlite:" + Path.of(System.getProperty("user.home"), ".cache", "finance-tracker",
        "kotlin-migration", "openapi-no-database-1101ep.db")) { error("OpenAPI must not open a database connection") }
    private fun ApplicationTestBuilder.module(imports: Boolean = true) {
        application {
            val database = offlineDatabase()
            financeModule(database, cashImports = if (imports) CashImportHttpService(database, InMemoryParsedImportStore()) else null)
        }
    }
    private suspend fun ApplicationTestBuilder.document(): JsonObject {
        val response = client.get("/openapi.json")
        assertEquals(200, response.status.value, response.bodyAsText())
        assertTrue(response.contentType().toString().startsWith("application/json"))
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }
    private fun schema(document: JsonObject, path: String, method: String = "post", type: String = "application/json") = document.getValue("paths").jsonObject
        .getValue(path).jsonObject.getValue(method).jsonObject.getValue("requestBody").jsonObject.getValue("content").jsonObject.getValue(type).jsonObject.getValue("schema").jsonObject
    private fun visit(value: JsonElement, block: (JsonObject) -> Unit) {
        when (value) {
            is JsonObject -> { block(value); value.values.forEach { visit(it, block) } }
            is JsonArray -> value.forEach { visit(it, block) }
            else -> Unit
        }
    }
    private fun references(document: JsonObject) = visit(document) { value ->
        (value["\$ref"] as? JsonPrimitive)?.content?.let { reference ->
            assertTrue(reference.startsWith("#/"), reference)
            var resolved: JsonElement = document
            for (part in reference.removePrefix("#/").split('/')) resolved = resolved.jsonObject.getValue(part.replace("~1", "/").replace("~0", "~"))
            assertNotEquals(JsonNull, resolved, reference)
        }
    }
    @Test fun maintenanceOpenApiHasSixStrictSelfContainedIntents() = testApplication {
        module(); val schema = schema(document(), "/api/v1/cash-mutations/plans")
        val variants = schema.getValue("oneOf").jsonArray.associate { item ->
            item.jsonObject.getValue("properties").jsonObject.getValue("intent").jsonObject.getValue("const").jsonPrimitive.content to item.jsonObject
        }
        assertEquals(setOf("save_draft", "edit_entry", "edit_record", "finalize_draft", "resolve_issue", "maintain_relations"), variants.keys)
        assertTrue(variants.values.all { it["additionalProperties"] == JsonPrimitive(false) })
        assertFalse("issue_resolutions" in variants.getValue("maintain_relations").getValue("properties").jsonObject)
        assertEquals(JsonPrimitive(1), variants.getValue("resolve_issue").getValue("properties").jsonObject.getValue("issue_resolutions").jsonObject["minItems"])
        assertEquals(JsonPrimitive(100), variants.getValue("edit_record").getValue("properties").jsonObject.getValue("issue_resolutions").jsonObject["maxItems"])
        visit(schema) { value ->
            assertFalse("\$ref" in value)
            assertFalse("mapping" in (value["discriminator"] as? JsonObject ?: JsonObject(emptyMap())))
        }
    }
    @Test fun accountOpenApiDescribesStrictRevisionAndRequiredKey() = testApplication {
        module(); val document = document(); val paths = document.getValue("paths").jsonObject
        assertFalse("/api/v1/accounts/{account_name}" in paths)
        val create = paths.getValue("/api/v1/accounts").jsonObject.getValue("post").jsonObject
        assertTrue(create.getValue("parameters").jsonArray.any { it.jsonObject["name"] == JsonPrimitive("Idempotency-Key") && it.jsonObject["required"] == JsonPrimitive(true) })
        val schema = schema(document, "/api/v1/accounts/by-id/{account_id}", "patch")
        assertEquals(JsonPrimitive("integer"), schema.getValue("properties").jsonObject.getValue("expected_revision").jsonObject["type"])
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
    }
    @Test fun planOpenApiExposesCompleteInputsAndBoundedPagesWithoutDanglingReferences() = testApplication {
        module(); val document = document(); references(document)
        val schema = schema(document, "/api/v1/cash-import/sessions/{session_id}/plans")
        assertEquals(setOf("expected_session_revision", "previous_plan_id", "account_selections", "row_choices", "relation_choices"), schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        assertEquals(JsonPrimitive("object"), schema.getValue("properties").jsonObject.getValue("row_choices").jsonObject.getValue("items").jsonObject
            .getValue("properties").jsonObject.getValue("patch").jsonObject["type"])
        val paths = document.getValue("paths").jsonObject
        val page = paths.getValue("/api/v1/cash-import/plans/{plan_id}/items").jsonObject.getValue("get").jsonObject
        val parameters = page.getValue("parameters").jsonArray.associate { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject }
        assertEquals(JsonPrimitive(200), parameters.getValue("limit").getValue("schema").jsonObject["maximum"])
        assertEquals(JsonPrimitive(0), parameters.getValue("start_index").getValue("schema").jsonObject["minimum"])
        assertEquals(setOf("all", "confirmed", "pending"), parameters.getValue("confirmation_status").getValue("schema").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet())
        val commit = paths.getValue("/api/v1/cash-import/plans/{plan_id}/commit").jsonObject.getValue("post").jsonObject
        assertTrue(commit.getValue("parameters").jsonArray.any { it.jsonObject["name"] == JsonPrimitive("Idempotency-Key") && it.jsonObject["required"] == JsonPrimitive(true) })
        assertTrue(commit.getValue("responses").jsonObject.getValue("503").jsonObject.getValue("content").jsonObject.getValue("application/json").jsonObject
            .getValue("schema").jsonObject.getValue("\$ref").jsonPrimitive.content.endsWith("CashPlanErrorView"))
    }
    @Test fun sessionOpenApiExposesBoundedDiagnosticContract() = testApplication {
        module(); val document = document()
        val error = document.getValue("paths").jsonObject.getValue("/api/v1/cash-import/sessions/{session_id}").jsonObject.getValue("get").jsonObject
            .getValue("responses").jsonObject.getValue("503").jsonObject
        assertTrue(error.getValue("content").jsonObject.getValue("application/json").jsonObject.getValue("schema").jsonObject.getValue("\$ref").jsonPrimitive.content.endsWith("CashPlanErrorView"))
        assertEquals(JsonPrimitive(100), document.getValue("components").jsonObject.getValue("schemas").jsonObject.getValue("CashDiagnosticView").jsonObject
            .getValue("properties").jsonObject.getValue("locations").jsonObject["maxItems"])
    }
    @Test fun uploadOpenApiHasResolvableMultipartReferences() = testApplication {
        module(); val document = document(); references(document)
        val schema = schema(document, "/api/v1/cash-import/sessions", type = "multipart/form-data")
        val metadata = schema.getValue("properties").jsonObject.getValue("metadata").jsonObject.getValue("contentSchema").jsonObject
        assertEquals(JsonPrimitive(false), metadata["additionalProperties"])
        assertEquals(JsonPrimitive("string"), metadata.getValue("properties").jsonObject.getValue("files").jsonObject.getValue("items").jsonObject
            .getValue("properties").jsonObject.getValue("client_file_ref").jsonObject["type"])
    }
    @Test fun manualCreateSchemaContainsOnlyPublicFields() = testApplication {
        module(); val schema = schema(document(), "/api/v1/cash-records")
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        assertTrue(schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.containsAll(listOf("amount", "currency", "occurred_at")))
        assertFalse("manual_overrides" in schema.getValue("properties").jsonObject); assertFalse("source_payload" in schema.getValue("properties").jsonObject)
    }
    @Test fun fullRuntimeDocumentsExactlyConnectedOperationsAndCanonicalParameterAliases() = testApplication {
        module(); val document = document(); val paths = document.getValue("paths").jsonObject
        assertEquals(81, paths.values.sumOf { it.jsonObject.keys.count { method -> method in setOf("get", "post", "put", "patch", "delete") } })
        assertTrue("/api/v1/queries/accounts" in paths)
        assertTrue("/api/v1/reports/finance" in paths)
        assertTrue("get" in paths.getValue("/api/v1/queries/transactions").jsonObject)
        assertTrue("post" in paths.getValue("/api/v1/relations/aliases").jsonObject)
        assertTrue("get" in paths.getValue("/api/v1/investment-events").jsonObject)
        assertTrue("post" in paths.getValue("/api/v1/investment-events").jsonObject)
        assertTrue("get" in paths.getValue("/api/v1/evidence/investment-events/{event_id}").jsonObject)
        assertTrue("delete" in paths.getValue("/api/v1/cash-records/{fact_id}").jsonObject)
        assertTrue("get" in paths.getValue("/api/v1/cash-records/{transaction_id}").jsonObject)
        assertTrue("get" in paths.getValue("/api/v1/cash-mutations/receipts/{receipt_id}/rows").jsonObject)
        assertTrue("get" in paths.getValue("/api/v1/cash-mutations/receipts/{receipt_id}/relations").jsonObject)
    }
    @Test fun internalParameterNamesDoNotReplaceOriginalPublicParameterNames() = testApplication {
        application {
            val root = routing { get("/api/v1/cash-records/{internal_identifier}") { call.respondText("unused") } }
            LegacyOpenApi.install(root)
        }
        val paths = document().getValue("paths").jsonObject
        assertEquals(setOf("/api/v1/cash-records/{transaction_id}"), paths.keys)
        val parameters = paths.values.single().jsonObject.getValue("get").jsonObject.getValue("parameters").jsonArray
        assertTrue(parameters.any { it.jsonObject["in"] == JsonPrimitive("path") && it.jsonObject["name"] == JsonPrimitive("transaction_id") })
    }
    @Test fun actualApiWithoutContractIsRejectedAtRegistration() {
        val failure = assertFailsWith<IllegalArgumentException> {
            testApplication {
                application {
                    val root = routing { get("/api/v1/unregistered-contract") { call.respondText("unused") } }
                    LegacyOpenApi.install(root)
                }
                startApplication()
            }
        }
        assertEquals("Registered API operation lacks an OpenAPI contract", failure.message)
    }
    @Test fun optionalImportRoutesAreOmittedAndDocumentationNeedsNoDatabaseOrAuthentication() = testApplication {
        module(imports = false); val document = document(); references(document)
        assertFalse("/api/v1/cash-import/sessions" in document.getValue("paths").jsonObject)
        assertTrue("/api/v1/cash-mutations/plans" in document.getValue("paths").jsonObject)
        assertEquals(404, client.get("/docs").status.value); assertEquals(404, client.get("/redoc").status.value)
    }
    @Test fun completeDocumentOperationsAndComponentsMatchOriginalContract() = testApplication {
        module(); val document = document(); references(document)
        val original = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/openapi-contract.json")).bufferedReader().use { it.readText() }).jsonObject
        assertEquals(original - "paths", document - "paths")
        for ((path, methods) in document.getValue("paths").jsonObject) for ((method, operation) in methods.jsonObject)
            assertEquals(original.getValue("paths").jsonObject.getValue(path).jsonObject[method], operation, "$method $path")
    }
}
