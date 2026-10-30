package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportHttpContractTest {
    private fun database() = Json.parseToJsonElement(Path.of(requireNotNull(
        System.getenv("FT_KOTLIN_PLAN_BUILD_CONTRACT_DIR"))).resolve("database.json").readText()).jsonObject.let {
        Database(it.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    private val workspace = "plan-a"
    private val admin = "admin"
    private val wechat = ParsedCashDocument("wechat", "fixture:0", "fixture-v1",
        listOf(ParsedCashRow(1, linkedMapOf("amount" to "-12.30", "currency" to "CNY",
            "occurred_at" to "2026-10-01T10:00:00+00:00", "record_type" to "consumption",
            "record_subtype" to "not_applicable", "counterparty" to "商户", "counterparty_account" to "",
            "counterparty_account_attrs" to emptyList<String>(), "note" to "",
            "source_payload" to mapOf("交易单号" to "ORDER-1", "付款方式" to "零钱")),
            paymentMethod = "零钱", providerId = "ORDER-1")),
        CashSourceScope.WECHAT_DEFAULT_SCOPE, "合成微信",
        CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1"), 1, 1)
    private fun service(db: Database, store: ParsedImportStore = InMemoryParsedImportStore(),
        parser: (CashImportUpload, ByteArray) -> ParsedCashDocument = { _, _ -> wechat }) =
        CashImportHttpService(db, store, parser)

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private suspend fun error(response: io.ktor.client.statement.HttpResponse) =
        json(response.bodyAsText()).getValue("error").jsonObject.getValue("code").jsonPrimitive.content

    private fun multipart(metadata: JsonObject, files: Map<String, ByteArray>, filename: String = "账单.csv") =
        MultiPartFormDataContent(formData {
            append("metadata", metadata.toString(), Headers.build {
                append(HttpHeaders.ContentType, "application/json")
            })
            for ((reference, bytes) in files) append("file:$reference", bytes, Headers.build {
                append(HttpHeaders.ContentType, "application/octet-stream")
                append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
            })
        })

    private fun files(vararg references: String) = buildJsonObject {
        put("files", JsonArray(references.map { reference ->
            buildJsonObject { put("client_file_ref", reference) }
        }))
    }

    private suspend fun create(builder: io.ktor.server.testing.ApplicationTestBuilder, token: String = "synthetic-admin",
        metadata: JsonObject = files("client-1"), parts: Map<String, ByteArray> = mapOf("client-1" to "synthetic".encodeToByteArray())) =
        builder.client.post("/api/v1/cash-import/sessions") {
            header(HttpHeaders.Authorization, "Bearer $token"); setBody(multipart(metadata, parts))
        }

    @Test fun uploadLifecyclePublishesSessionViews() = testApplication {
        val db = database()
        val store = InMemoryParsedImportStore()
        application { financeModule(db, cashImports = service(db, store)) }
        val created = create(this)
        assertEquals(201, created.status.value, created.bodyAsText())
        val first = json(created.bodyAsText())
        val sessionId = first.getValue("session_id").jsonPrimitive.content
        assertTrue(sessionId.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertEquals(1, first.getValue("session_revision").jsonPrimitive.int)
        assertEquals("open", first.getValue("state").jsonPrimitive.content)
        assertEquals("ready", first.getValue("files").jsonArray.single().jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals("账单.csv", first.getValue("files").jsonArray.single().jsonObject.getValue("display_name").jsonPrimitive.content)
        assertEquals(1, first.getValue("source_accounts").jsonArray.size)
        assertEquals(JsonNull, first.getValue("receipt_id"))
        assertEquals(first, json(client.get("/api/v1/cash-import/sessions/$sessionId") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
        }.bodyAsText()))

        val added = client.post("/api/v1/cash-import/sessions/$sessionId/files") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            setBody(multipart(JsonObject(files("client-2") + ("expected_session_revision" to JsonPrimitive(1))),
                mapOf("client-2" to "synthetic".encodeToByteArray())))
        }
        assertEquals(200, added.status.value, added.bodyAsText())
        val afterAdd = json(added.bodyAsText())
        assertEquals(2, afterAdd.getValue("session_revision").jsonPrimitive.int)
        assertEquals(2, afterAdd.getValue("files").jsonArray.size)

        val stale = client.post("/api/v1/cash-import/sessions/$sessionId/files") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            setBody(multipart(JsonObject(files("client-3") + ("expected_session_revision" to JsonPrimitive(1))),
                mapOf("client-3" to "synthetic".encodeToByteArray())))
        }
        assertEquals(409, stale.status.value)
        assertEquals("session_changed", error(stale))

        val fileRef = afterAdd.getValue("files").jsonArray[0].jsonObject.getValue("file_ref").jsonPrimitive.content
        val replaced = client.post("/api/v1/cash-import/sessions/$sessionId/files") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            setBody(multipart(JsonObject(files("client-1") + mapOf(
                "expected_session_revision" to JsonPrimitive(2), "file_ref" to JsonPrimitive(fileRef))),
                mapOf("client-1" to "replacement".encodeToByteArray()), filename = "新账单.csv"))
        }
        assertEquals(200, replaced.status.value, replaced.bodyAsText())
        val afterReplace = json(replaced.bodyAsText())
        assertEquals(3, afterReplace.getValue("session_revision").jsonPrimitive.int)
        assertNotEquals(fileRef, afterReplace.getValue("files").jsonArray[0].jsonObject.getValue("file_ref").jsonPrimitive.content)
        assertEquals("新账单.csv", afterReplace.getValue("files").jsonArray[0].jsonObject.getValue("display_name").jsonPrimitive.content)

        val currentRef = afterReplace.getValue("files").jsonArray[0].jsonObject.getValue("file_ref").jsonPrimitive.content
        val scoped = client.patch("/api/v1/cash-import/sessions/$sessionId/files/$currentRef") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("expected_session_revision", 3); put("source_scope_label", "工作微信") }.toString())
        }
        assertEquals(200, scoped.status.value, scoped.bodyAsText())
        val afterScope = json(scoped.bodyAsText())
        assertEquals(4, afterScope.getValue("session_revision").jsonPrimitive.int)
        assertEquals("工作微信", afterScope.getValue("files").jsonArray[0].jsonObject.getValue("source_scope_label").jsonPrimitive.content)

        val otherRef = afterScope.getValue("files").jsonArray[1].jsonObject.getValue("file_ref").jsonPrimitive.content
        val removed = client.delete("/api/v1/cash-import/sessions/$sessionId/files/$otherRef") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin"); header(HttpHeaders.IfMatch, "\"4\"")
        }
        assertEquals(200, removed.status.value, removed.bodyAsText())
        assertEquals(1, json(removed.bodyAsText()).getValue("files").jsonArray.size)
        val missingHeader = client.delete("/api/v1/cash-import/sessions/$sessionId/files/$otherRef") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
        }
        assertEquals(422, missingHeader.status.value)

        val cancelled = client.delete("/api/v1/cash-import/sessions/$sessionId") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
        }
        assertEquals(200, cancelled.status.value, cancelled.bodyAsText())
        val afterCancel = json(cancelled.bodyAsText())
        assertEquals("cancelled", afterCancel.getValue("state").jsonPrimitive.content)
        assertTrue(afterCancel.getValue("files").jsonArray.isEmpty())
        assertEquals(409, client.post("/api/v1/cash-import/sessions/$sessionId/files") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            setBody(multipart(JsonObject(files("late") + ("expected_session_revision" to JsonPrimitive(6))),
                mapOf("late" to "x".encodeToByteArray())))
        }.status.value)
    }

    @Test fun viewerCannotWriteAndForeignSessionIsNotFound() = testApplication {
        val db = database()
        application { financeModule(db, cashImports = service(db)) }
        val forbidden = create(this, token = "synthetic-viewer")
        assertEquals(403, forbidden.status.value)
        assertEquals("workspace_forbidden", error(forbidden))
        val created = create(this)
        val sessionId = json(created.bodyAsText()).getValue("session_id").jsonPrimitive.content
        val foreign = client.get("/api/v1/cash-import/sessions/$sessionId") {
            header(HttpHeaders.Authorization, "Bearer synthetic-viewer")
        }
        assertEquals(404, foreign.status.value)
        assertEquals("session_not_found", error(foreign))
    }

    @Test fun committedSessionIsReadableAndFenced() = testApplication {
        val db = database()
        val service = service(db)
        application { financeModule(db, cashImports = service) }
        val created = json(create(this).bodyAsText())
        val sessionId = created.getValue("session_id").jsonPrimitive.content
        val receipt = db.write { connection ->
            ReceiptRepository(connection, workspace, db.sqlite).create(user = admin, key = "cash-import-committed-" + java.util.UUID.randomUUID(),
                requestDigest = "a".repeat(64), operation = "import", resultSummary = mapOf("status" to "no_changes"),
                results = emptyList<Any?>(), planId = "plan-1",
                sessionDigest = MessageDigest.getInstance("SHA-256").digest(sessionId.encodeToByteArray())
                    .joinToString("") { "%02x".format(it) })
        }
        val receiptId = receipt.getValue("receipt_id").jsonPrimitive.content
        val view = json(client.get("/api/v1/cash-import/sessions/$sessionId") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
        }.bodyAsText())
        assertEquals("committed", view.getValue("state").jsonPrimitive.content)
        assertEquals(receiptId, view.getValue("receipt_id").jsonPrimitive.content)
        assertEquals("plan-1", view.getValue("last_plan_id").jsonPrimitive.content)
        assertTrue(view.getValue("files").jsonArray.isEmpty())
        val cancel = client.delete("/api/v1/cash-import/sessions/$sessionId") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
        }
        assertEquals(409, cancel.status.value)
        assertEquals("session_committed", error(cancel))
        assertEquals(receiptId, json(cancel.bodyAsText()).getValue("receipt_id").jsonPrimitive.content)
    }

    @Test fun lateCommitDuringParsePreventsPublication() = testApplication {
        val db = database()
        val store = InMemoryParsedImportStore()
        val normal = service(db, store)
        val created = normal.create("synthetic-admin",
            listOf(CashImportUpload("client-1", "账单.csv", "synthetic".encodeToByteArray())))
        val sessionId = created.getValue("session_id") as String
        val contesting = CashImportHttpService(db, store) { _, _ ->
            db.write { connection ->
                ReceiptRepository(connection, workspace, db.sqlite).create(user = admin, key = "cash-import-late-" + java.util.UUID.randomUUID(),
                    requestDigest = "b".repeat(64), operation = "import", resultSummary = mapOf("status" to "no_changes"),
                    results = emptyList<Any?>(), planId = "plan-2",
                    sessionDigest = MessageDigest.getInstance("SHA-256").digest(sessionId.encodeToByteArray())
                        .joinToString("") { "%02x".format(it) })
            }
            wechat
        }
        application { financeModule(db, cashImports = contesting) }
        val response = client.post("/api/v1/cash-import/sessions/$sessionId/files") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            setBody(multipart(JsonObject(files("late") + ("expected_session_revision" to JsonPrimitive(1))),
                mapOf("late" to "x".encodeToByteArray())))
        }
        assertEquals(409, response.status.value)
        assertEquals("session_committed", error(response))
        val saved = store.get(sessionId, workspace, admin)
        assertEquals(1, saved.revision.toInt())
        assertEquals(1, saved.files.size)
    }

    @Test fun strictMultipartAndMetadataErrorsAreRejected() = testApplication {
        val db = database()
        application { financeModule(db, cashImports = service(db)) }
        suspend fun post(body: JsonObject, parts: Map<String, ByteArray> = mapOf("client-1" to "x".encodeToByteArray()),
            filename: String = "账单.csv") = client.post("/api/v1/cash-import/sessions") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin"); setBody(multipart(body, parts, filename))
        }
        assertEquals(422, post(JsonObject(files("client-1") + ("extra" to JsonPrimitive(1)))).status.value, "extra key")
        assertEquals(422, post(JsonObject(files("client-1")).let { JsonObject(it + ("files" to JsonPrimitive("x"))) }).status.value, "files type")
        assertEquals(422, post(files("client-1", "client-1")).status.value, "duplicate ref")
        assertEquals(422, post(JsonObject(emptyMap())).status.value, "empty metadata")
        assertEquals(422, post(files("client-1"), parts = mapOf("other" to "x".encodeToByteArray())).status.value, "part name")
        val oversizedMetadata = buildJsonObject { put("files", JsonArray(listOf(buildJsonObject {
            put("client_file_ref", "client-1"); put("password", "x".repeat(CashImportProtocol.MAX_METADATA_BYTES + 1)) }))) }
        val oversized = post(oversizedMetadata); assertEquals(422, oversized.status.value, oversized.bodyAsText())
        assertEquals(422, post(JsonObject(files("client-1") + mapOf(
            "last_modified_at_ms" to JsonPrimitive(-1)))).status.value, "negative timestamp")
        val plain = client.post("/api/v1/cash-import/sessions") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            contentType(ContentType.Application.Json); setBody(files("client-1").toString())
        }
        assertEquals(422, plain.status.value, "plain json")
    }
}
