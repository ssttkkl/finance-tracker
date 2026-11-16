package com.finance.tracker.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.serialization.json.*

internal class CashImportOriginalApiFixture(name: String, rootVariable: String = "FT_KOTLIN_IMPORT_PLAN_API_CONTRACT_DIR") :
    CashImportOriginalDataFixture(name, rootVariable) {
    val commands by lazy { CashImportCommandService(database, store, now = { now }, afterStep = { step ->
        if (mode == "empty" && step == "source_evidence") { faultCount++; error("private empty evidence failure") }
    }) }
    private var activeFiles = files("default")
    val service by lazy { CashImportHttpService(database, store, commands) { upload, _ ->
        val file = activeFiles.single { it.clientFileRef == upload.clientFileRef }
        file.document ?: throw StatementDocumentFailure("statement_unreadable")
    } }

    suspend fun upload(client: HttpClient, variant: String = "default"): JsonObject {
        activeFiles = files(variant)
        val response = client.post("/api/v1/cash-import/sessions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(MultiPartFormDataContent(formData {
                append("metadata", buildJsonObject { put("files", JsonArray(activeFiles.map { file ->
                    buildJsonObject { put("client_file_ref", file.clientFileRef) }
                })) }.toString(), Headers.build { append(HttpHeaders.ContentType, "application/json") })
                for (file in activeFiles) append("file:${file.clientFileRef}", "synthetic-${file.clientFileRef}".encodeToByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentDisposition, "filename=synthetic.csv")
                })
            }))
        }
        assertEquals(201, response.status.value, response.bodyAsText())
        return response.apiBody()
    }
    fun input(view: JsonObject) = buildJsonObject {
        put("expected_session_revision", view.getValue("session_revision")); put("previous_plan_id", JsonNull)
        put("account_selections", JsonArray(view.apiArray("source_accounts").map { group -> buildJsonObject {
            put("source_account_ref", group.jsonObject.getValue("source_account_ref")); put("account_id", "101"); put("expected_account_revision", 1)
        } }))
        put("row_choices", JsonArray(emptyList())); put("relation_choices", JsonArray(emptyList()))
    }
    suspend fun post(client: HttpClient, path: String, body: String, token: String = this.token, key: String? = null) = client.post(path) {
        header(HttpHeaders.Authorization, "Bearer $token"); key?.let { header("Idempotency-Key", it) }
        contentType(ContentType.Application.Json); setBody(body)
    }
    suspend fun get(client: HttpClient, path: String, token: String = this.token) = client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }
    suspend fun preview(client: HttpClient, view: JsonObject, patch: JsonObject = JsonObject(emptyMap()), token: String = this.token) =
        post(client, "/api/v1/cash-import/sessions/${view.apiText("session_id")}/plans", JsonObject(input(view) + patch).toString(), token)
    suspend fun plan(client: HttpClient, view: JsonObject, patch: JsonObject = JsonObject(emptyMap())): JsonObject {
        val response = preview(client, view, patch)
        assertEquals(200, response.status.value, response.bodyAsText()); return response.apiBody()
    }
    suspend fun commit(client: HttpClient, plan: JsonObject, key: String? = "synthetic-api-confirm", token: String = this.token) =
        post(client, "/api/v1/cash-import/plans/${plan.apiText("plan_id")}/commit",
            buildJsonObject { put("expected_session_revision", plan.getValue("session_revision")) }.toString(), token, key)
    suspend fun resolve(client: HttpClient, plan: JsonObject, key: String = "synthetic-api-confirm") =
        post(client, "/api/v1/cash-mutations/resolve", buildJsonObject { put("plan_id", plan.getValue("plan_id")); put("idempotency_key", key) }.toString())
}
