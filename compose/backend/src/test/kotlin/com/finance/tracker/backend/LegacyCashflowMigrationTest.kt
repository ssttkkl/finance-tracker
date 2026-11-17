package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class LegacyCashflowMigrationTest {
    private fun database():Database {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }

    @Test fun numericAmountsAndViewerRequestsAreRejectedBeforeTheApplicationRuns()=testApplication {
        val db=database();val calls=AtomicInteger()
        application {financeModule(db,legacyCashflow=LegacyCashflowService(db,beforeWrite={calls.incrementAndGet()}))}
        val invalid=client.post("/api/v1/cashflow/add") {
            bearerAuth("synthetic-admin");contentType(ContentType.Application.Json)
            setBody("""{"amount":1.2,"counterparty":"浮点数","account":"现金","currency":"CNY"}""")
        }
        assertEquals(400,invalid.status.value)
        assertEquals(JsonPrimitive("amount_must_be_decimal_string"),Json.parseToJsonElement(invalid.bodyAsText()).jsonObject.getValue("error").jsonObject["code"])
        val denied=client.post("/api/v1/cashflow/add") {
            bearerAuth("synthetic-viewer");contentType(ContentType.Application.Json)
            setBody("""{"amount":"1.00","counterparty":"不可写入","account":"现金","currency":"CNY"}""")
        }
        assertEquals(403,denied.status.value)
        assertEquals(JsonPrimitive("workspace_forbidden"),Json.parseToJsonElement(denied.bodyAsText()).jsonObject.getValue("error").jsonObject["code"])
        assertEquals(0,calls.get())
    }

    @Test fun httpAccountCreationAndCashWritePreserveAllEighteenFractionalDigits()=testApplication {
        val db=database();val workspaces=WorkspaceService(db);val name="旧现金精确金额原测试"
        val workspace=workspaces.create("synthetic-admin",name).activeWorkspaceId!!
        application {financeModule(db)}
        try {
            val created=client.post("/api/v1/accounts") {
                bearerAuth("synthetic-admin");contentType(ContentType.Application.Json);header("Idempotency-Key","create-api-cash")
                setBody("""{"name":"API现金","type":"cash","currencies":["CNY"]}""")
            }
            assertEquals(201,created.status.value,created.bodyAsText())
            val invalid=client.post("/api/v1/cashflow/add") {
                bearerAuth("synthetic-admin");contentType(ContentType.Application.Json)
                setBody("""{"amount":1.2,"counterparty":"浮点数","account":"API现金","currency":"CNY"}""")
            }
            assertEquals(400,invalid.status.value)
            val written=client.post("/api/v1/cashflow/add") {
                bearerAuth("synthetic-admin");contentType(ContentType.Application.Json)
                setBody("""{"amount":"-12.340000000000000001","counterparty":"API测试","account":"API现金","currency":"CNY","date":"2026-08-01 09:00:00"}""")
            }
            assertEquals(201,written.status.value,written.bodyAsText())
            assertEquals(JsonPrimitive("-12.340000000000000001"),Json.parseToJsonElement(written.bodyAsText()).jsonObject.getValue("row").jsonObject["amount"])
            db.read {c ->c.prepareStatement("SELECT amount FROM cash_transactions WHERE workspace_id=? AND counterparty='API测试'").use {
                it.setString(1,workspace);it.executeQuery().use {r ->
                    assertTrue(r.next());assertEquals("-12.340000000000000001",r.getBigDecimal(1).toPlainString());assertFalse(r.next())
                }
            }}
        } finally {workspaces.delete("synthetic-admin",name);workspaces.select("synthetic-admin","fixture-a")}
    }
}
