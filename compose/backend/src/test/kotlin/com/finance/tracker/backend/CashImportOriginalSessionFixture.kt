package com.finance.tracker.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import java.util.concurrent.atomic.AtomicBoolean
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
    private val delivered = mutableListOf<AtomicBoolean>()
    val opened get() = delivered.size
    fun assertClosed() = assertTrue(delivered.all { it.get() }, "Every delivered Ktor file part must be disposed")
    fun resetParts() = delivered.clear()
    fun observe(application: Application) {
        application.receivePipeline.intercept(ApplicationReceivePipeline.After) {
            val original = subject as? MultiPartData
            if (original != null) proceedWith(object : MultiPartData {
                override suspend fun readPart(): PartData? {
                    val part = original.readPart() ?: return null
                    if (part !is PartData.FileItem) return part
                    val closed = AtomicBoolean(false); delivered += closed
                    return PartData.FileItem(part.provider, { part.dispose(); closed.set(true) }, part.headers)
                }
            })
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
