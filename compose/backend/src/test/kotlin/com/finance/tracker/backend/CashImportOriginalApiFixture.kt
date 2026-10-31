package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

internal suspend fun HttpResponse.apiBody() = Json.parseToJsonElement(bodyAsText()).jsonObject
internal fun JsonObject.apiText(key: String) = getValue(key).jsonPrimitive.content
internal fun JsonObject.apiNumber(key: String) = getValue(key).jsonPrimitive.int
internal fun JsonObject.apiArray(key: String) = getValue(key).jsonArray
internal fun JsonObject.apiError() = getValue("error").jsonObject.apiText("code")

internal class CashImportOriginalApiFixture(name: String, rootVariable: String = "FT_KOTLIN_IMPORT_PLAN_API_CONTRACT_DIR") {
    private val directory = Path.of(requireNotNull(System.getenv(rootVariable))).resolve(name)
    private val config = Json.parseToJsonElement(directory.resolve("database.json").readText()).jsonObject
    private val inputs = Json.parseToJsonElement(directory.resolve("api-input.json").readText()).jsonObject
    val workspace = config.apiText("workspace")
    val user = config.apiText("user")
    val token = "original-import-owner"
    var now: Instant = Instant.parse("2026-10-01T09:00:00Z")
    @Volatile var mode: String? = null
        set(value) { field = value; replaySeen = false }
    @Volatile var faultCount = 0
    @Volatile private var replaySeen = false
    val database = Database(config.apiText("jdbc_url"), System.getenv("FT_KOTLIN_TEST_DB_USER"),
        System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"), ::decorate)
    class Store(clock: () -> Instant, ttl: Int) : ParsedImportStore(clock = clock, ttlSeconds = ttl) {
        private val values = linkedMapOf<String, ParsedImportObject>()
        var poisonDocuments = false
        @Synchronized fun clear() = values.clear()
        @Synchronized fun raw(id: String) = values.getValue(id).content.copyOf()
        @Synchronized override fun readRaw(id: String): ParsedImportObject {
            val value = values[id] ?: throw ParsedImportNotFound()
            if (!poisonDocuments) return value
            val root = Json.parseToJsonElement(value.content.decodeToString()).jsonObject
            val files = root.getValue("files").jsonArray.map { JsonObject(it.jsonObject + ("document" to JsonNull)) }
            val poisoned = JsonObject(root + ("files" to JsonArray(files))).toString().encodeToByteArray()
            return ParsedImportObject(poisoned, digest(poisoned))
        }
        @Synchronized override fun writeRaw(id: String, content: ByteArray, expected: String?, expiresAt: Instant) {
            if (values[id]?.etag != expected) throw ParsedImportRevisionConflict()
            values[id] = ParsedImportObject(content.copyOf(), digest(content))
        }
        @Synchronized override fun deleteRaw(id: String) { values.remove(id) }
        override fun sweepExpired(cursor: String?, limit: Int, shouldStop: () -> Boolean) = ParsedImportSweep(0, 0, null)
    }
    val store = Store({ now }, if (name in setOf("expired", "cancelled")) 60 else 1800)
    val commands = CashImportCommandService(database, store, now = { now }, afterStep = { step ->
        if (mode == "empty" && step == "source_evidence") { faultCount++; error("private empty evidence failure") }
    })
    private var activeFiles = files("default")
    val service = CashImportHttpService(database, store, commands) { upload, _ ->
        val file = activeFiles.single { it.clientFileRef == upload.clientFileRef }
        file.document ?: throw StatementDocumentFailure("statement_unreadable")
    }

    fun files(variant: String): List<ParsedImportFile> {
        val data = inputs.getValue(variant).jsonObject
        return ParsedImportCodec().decode(data.apiText("session_id"), data.getValue("session").toString().encodeToByteArray()).files
    }
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
    fun role(value: String, owner: String = user) = database.write { connection -> connection.jooq(database.sqlite).update(WORKSPACE_MEMBERSHIPS)
        .set(WORKSPACE_MEMBERSHIPS.ROLE, value).where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(workspace).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq(owner))).execute() }
    fun snapshot() = database.read { connection -> DefaultSchema.DEFAULT_SCHEMA.tables.sortedBy { it.name }.associate { table ->
        val values = connection.createStatement().use { statement -> statement.executeQuery("SELECT * FROM \"${table.name}\"").use { result ->
            val metadata = result.metaData
            buildList { while (result.next()) add(JsonArray((1..metadata.columnCount).map { index ->
                result.getString(index)?.let(::JsonPrimitive) ?: JsonNull
            }).toString()) }.sorted()
        } }
        table.name to values
    } }
    fun count() = database.read { it.jooq(database.sqlite).fetchCount(CASH_TRANSACTIONS) }
    private fun invoke(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? =
        try { method.invoke(target, *(args ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.targetException }
    private fun decorate(connection: Connection): Connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
        val result = invoke(connection, method, args)
        if (method.name == "commit" && (mode == "commit_after" || mode == "replay_exit" && replaySeen)) {
            faultCount++; throw SQLException("private lost connection after commit")
        }
        if (result is Statement && method.name in setOf("prepareStatement", "createStatement")) statement(result, args?.firstOrNull() as? String) else result
    } as Connection
    private fun statement(statement: Statement, prepared: String?): Statement {
        val type = if (statement is PreparedStatement) PreparedStatement::class.java else Statement::class.java
        return Proxy.newProxyInstance(Statement::class.java.classLoader, arrayOf(type)) { _, method, args ->
            val result = invoke(statement, method, args)
            if (method.name in setOf("execute", "executeUpdate", "executeLargeUpdate", "executeQuery", "executeBatch", "executeLargeBatch")) {
                val sql = (prepared ?: args?.firstOrNull() as? String).orEmpty().replace("\"", "").replace("`", "").lowercase()
                val replay = Thread.currentThread().stackTrace.any { it.className == "com.finance.tracker.backend.ReceiptRepository" && it.methodName == "replay" }
                val sessionRead = mode == "session_read" && Thread.currentThread().stackTrace.any {
                    it.className == "com.finance.tracker.backend.CashImportHttpService" && it.methodName == "view"
                }
                if (replay) replaySeen = true
                val table = mapOf("source" to "cash_source_bindings", "relation" to "transaction_relations", "receipt" to "cash_mutation_receipts")[mode]
                if (sessionRead || table != null && Regex("\\binsert\\s+into\\s+(?:\\w+\\.)?$table\\b").containsMatchIn(sql) || mode == "replay_query" && replay ||
                    sql == "commit" && (mode == "commit_after" || mode == "replay_exit" && replaySeen)) {
                    faultCount++; throw SQLException("private database SQL failure")
                }
            }
            result
        } as Statement
    }
}
