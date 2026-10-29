package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class LegacyCashflowResponseEncodingTest {
    private val tables=listOf("cash_transactions","cash_transaction_components","transaction_relations","investment_events","ledger_snapshots","cash_entries","cash_entry_members","cash_projection_states","cash_projection_datasets","cash_projections","cash_projection_members","cash_projection_relations","cash_relation_candidates")
    private fun state(db:Database,workspace:String)=db.read {c ->tables.associateWith {table ->c.prepareStatement("SELECT * FROM $table WHERE workspace_id=?").use {s ->s.setString(1,workspace);s.executeQuery().use {r ->buildSet {while(r.next()) add((1..r.metaData.columnCount).associate {r.metaData.getColumnName(it) to r.getString(it)})}}}}}
    @Test fun legacyAddRejectsUnencodableSuccessBeforeCommittingEveryFinancialTable()=testApplication {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val workspaces=WorkspaceService(db);val name="响应编码事务回归";val workspace=workspaces.create("synthetic-admin",name).activeWorkspaceId!!;deletionAccount(db,workspace)
        application {financeModule(db)}
        try {
            val before=state(db,workspace)
            for(separator in listOf("\\ud800","\\udfff")) {
                val body="""{"account":"账户","amount":"-1.2500","currency":"CNY","counterparty":"response-test","date":"2026-08-04${separator}12:00"}"""
                val response=client.post("/api/v1/cashflow/add") {bearerAuth("synthetic-admin");contentType(ContentType.Application.Json);setBody(body)}
                assertEquals(400,response.status.value,response.bodyAsText())
                val error=Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
                assertTrue(error.getValue("code").jsonPrimitive.content.contains("surrogates not allowed"))
                assertEquals(JsonPrimitive("请求参数无效。"),error["message"])
                assertEquals(before,state(db,workspace))
            }
            val success=client.post("/api/v1/cashflow/add") {bearerAuth("synthetic-admin");contentType(ContentType.Application.Json)
                setBody("""{"account":"账户","amount":"-1.2500","currency":"CNY","counterparty":"response-test","date":"2026-08-04😀12:00"}""")}
            assertEquals(201,success.status.value,success.bodyAsText())
            val row=Json.parseToJsonElement(success.bodyAsText()).jsonObject.getValue("row").jsonObject
            assertEquals(JsonPrimitive("2026-08-04😀12:00"),row["occurred_at"]);assertEquals(JsonPrimitive("-1.2500"),row["amount"])
            val after=state(db,workspace)
            for(table in listOf("cash_transactions","cash_transaction_components")) assertEquals(before.getValue(table).size+1,after.getValue(table).size,table)
        } finally {workspaces.delete("synthetic-admin",name);workspaces.select("synthetic-admin","fixture-a")}
    }
    @Test fun defaultOperationTimeTruncatesSubMicrosecondsBeforeFormatting() {
        for(nano in listOf(0L,1L,999L)) assertEquals("2026-08-04T12:00:00+00:00",manualInstantText(Instant.parse("2026-08-04T12:00:00Z").plusNanos(nano)))
        assertEquals("2026-08-04T12:00:00.000001+00:00",manualInstantText(Instant.parse("2026-08-04T12:00:00Z").plusNanos(1999)))
    }
}
