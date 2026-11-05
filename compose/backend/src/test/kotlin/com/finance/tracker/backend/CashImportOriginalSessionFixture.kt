package com.finance.tracker.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import kotlin.test.*
import kotlinx.serialization.json.*

internal class CashImportOriginalSessionFixture(name: String, limit: Int = CashImportContext.MAX_UPLOAD_BYTES,
    rootVariable: String = "FT_KOTLIN_IMPORT_SESSION_CONTRACT_DIR") {
    val base = CashImportOriginalApiFixture(name, rootVariable)
    val document = base.files("default").single().document!!
    val calls = mutableListOf<String>()
    var parser: (CashImportUpload) -> ParsedCashDocument = { document }
    val service = CashImportHttpService(base.database, base.store, limit) { upload, bytes ->
        calls += upload.clientFileRef
        assertContentEquals(upload.content, bytes)
        parser(upload)
    }
    private val resources = mutableListOf<CashMultipartResources>()
    val opened get() = resources.sumOf { state -> state.sinks.count { it.file } }
    fun assertClosed() {
        assertTrue(resources.all { state -> state.sinks.all { it.closed } && state.input.isClosedForRead &&
            state.forwarded.isClosedForRead && state.pump.isCompleted }, "All real request resources must be closed")
    }
    fun resetParts() = resources.clear()
    fun observe(application: Application) {
        application.intercept(ApplicationCallPipeline.Plugins) {
            call.attributes.put(cashMultipartObserverKey) { resources += it }
        }
    }
    fun metadata(vararg refs: String) = buildJsonObject { put("files", JsonArray(refs.map { ref -> buildJsonObject { put("client_file_ref", ref) } })) }
    suspend fun upload(client: HttpClient, metadata: String = metadata("client-a").toString(),
        files: List<Triple<String, String, ByteArray>> = listOf(Triple("client-a", "synthetic.csv", "synthetic-original".encodeToByteArray())),
        path: String = "/api/v1/cash-import/sessions", token: String = base.token) = client.post(path) {
        header(HttpHeaders.Authorization, "Bearer $token")
        setBody(MultiPartFormDataContent(formData {
            append("metadata", metadata, Headers.build { append(HttpHeaders.ContentType, "application/json") })
            for ((ref, filename, bytes) in files) append("file:$ref", bytes, Headers.build {
                append(HttpHeaders.ContentType, "application/octet-stream"); append(HttpHeaders.ContentDisposition, "filename=$filename")
            })
        }))
    }
    fun sessionPath(view: JsonObject) = "/api/v1/cash-import/sessions/${view.apiText("session_id")}"
}
