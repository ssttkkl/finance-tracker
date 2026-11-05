package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportCapacityHttpTest {
    @Test fun sessionBudgetErrorExplainsHowToReduceTheImport() = checkError(
        "import_session_draft_too_large", 422, "本次导入内容过多，请减少文件后重试。", false)

    @Test fun temporaryTotalCapacityErrorRemainsRetryable() = checkError(
        "import_session_capacity_exceeded", 503, "暂时无法处理账单，请稍后重试。", true)

    @Test fun malformedStoredInputKeepsTheSafeGenericError() = checkError(
        "import_session_json_invalid", 422, "导入信息有误，请检查后重试。", false, "import_payload_invalid")

    private fun checkError(code: String, status: Int, message: String, retryable: Boolean, responseCode: String = code) {
        val directory = Files.createTempDirectory("ft-import-capacity-")
        try {
            val database = Database("jdbc:sqlite:${directory.resolve("capacity_test.db")}")
            database.bootstrapSchema()
            val token = AuthenticationService(database).register("capacity@example.invalid", "synthetic secure password").accessToken
            WorkspaceService(database).create(token, "容量回归")
            var reads = 0
            val staging = object : ParsedImportStore() {
                override fun readRaw(id: String): ParsedImportObject { reads++; throw ParsedImportDataError(code) }
                override fun writeRaw(id: String, content: ByteArray, expected: String?, expiresAt: Instant) = error("unexpected write")
                override fun deleteRaw(id: String) = error("unexpected delete")
                override fun sweepExpired(cursor: String?, limit: Int, shouldStop: () -> Boolean) = ParsedImportSweep(0, 0, null)
            }
            testApplication {
                application { financeModule(database, cashImports = CashImportHttpService(database, staging)) }
                val response = client.post("/api/v1/cash-import/sessions/${"a".repeat(43)}/plans") {
                    bearerAuth(token); contentType(ContentType.Application.Json)
                    setBody("""{"expected_session_revision":1,"previous_plan_id":null,"account_selections":[],"row_choices":[],"relation_choices":[]}""")
                }
                assertEquals(status, response.status.value)
                assertEquals(buildJsonObject { put("error", buildJsonObject {
                    put("code", responseCode); put("message", message)
                    put("field_errors", JsonArray(emptyList())); put("retryable", retryable)
                }) }, Json.parseToJsonElement(response.bodyAsText()))
            }
            assertEquals(1, reads)
            database.read { connection ->
                for (table in listOf("accounts", "cash_transactions", "statement_account_mappings", "cash_mutation_receipts")) {
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT count(*) FROM $table").use { rows ->
                            assertTrue(rows.next()); assertEquals(0, rows.getInt(1), table)
                        }
                    }
                }
            }
        } finally {
            Files.list(directory).use { paths -> paths.forEach(Files::deleteIfExists) }
            Files.deleteIfExists(directory)
        }
    }
}
