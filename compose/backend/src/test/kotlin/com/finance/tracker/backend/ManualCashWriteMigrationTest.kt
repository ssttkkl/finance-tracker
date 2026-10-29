package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class ManualCashWriteMigrationTest {
    private fun inWorkspace(action:(Database,String,Long)->Unit) {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val service=WorkspaceService(db);val name="手工收支写入测试";val workspace=service.create("synthetic-admin",name).activeWorkspaceId!!
        val account=deletionAccount(db,workspace)
        try {action(db,workspace,account)} finally {service.delete("synthetic-admin",name);service.select("synthetic-admin","fixture-a")}
    }
    private fun body(patch:Map<String,JsonElement> = emptyMap())=JsonObject(Json.parseToJsonElement("""{"account_name":"账户","amount":"-10.50","currency":"CNY","occurred_at":"2026-08-04","record_type":"consumption"}""").jsonObject+patch)
    private val tables=listOf("cash_transactions","cash_transaction_components","ledger_snapshots","cash_entries","cash_entry_members","cash_projection_states","cash_projection_datasets","cash_projections","cash_projection_members","cash_projection_relations")
    private fun snapshot(db:Database,workspace:String)=db.read {c ->tables.associateWith {table ->
        c.prepareStatement("SELECT * FROM $table WHERE workspace_id=?").use {s ->s.setString(1,workspace);s.executeQuery().use {r ->buildSet {
            while(r.next()) add(JsonObject((1..r.metaData.columnCount).associate {r.metaData.getColumnName(it) to (r.getString(it)?.let(::JsonPrimitive) ?: JsonNull)}))
        }}}
    }}
    @Test fun explicitEmptyFieldsCreateProtectionAndPublishRealComponentsBalanceAndProjection()=inWorkspace {db,workspace,account ->
        val result=ManualCashService(db,now={deletionTime}).create("synthetic-admin",body(mapOf("note" to JsonPrimitive(""),"category_id" to JsonNull,
            "components" to Json.parseToJsonElement("""[{"account_name":"账户","amount":"10.50","label":"现金"}]"""))))
        val record=result.getValue("record").jsonObject;val id=record.getValue("id").jsonPrimitive.long
        assertEquals("2026-08-04T00:00:00+00:00",record.getValue("occurred_at").jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()),result["relations"]);assertTrue(result.getValue("options").jsonObject.getValue("record_types").jsonArray.isNotEmpty())
        val state=snapshot(db,workspace);val fact=state.getValue("cash_transactions").single()
        assertEquals(JsonPrimitive("posted"),fact["state"]);assertEquals(JsonPrimitive("manual"),fact["record_origin"])
        assertEquals(JsonNull,fact["source_type"]);assertEquals(JsonPrimitive(""),fact["record_id"])
        assertTrue(fact["source_payload"]==JsonNull || fact["source_payload"]==JsonPrimitive("null"))
        assertEquals(Json.parseToJsonElement("""{"note":{"value":""},"category_id":{"value":null}}"""),Json.parseToJsonElement(fact.getValue("manual_overrides").jsonPrimitive.content))
        val component=record.getValue("components").jsonArray.single().jsonObject
        assertEquals(id,component.getValue("cash_transaction_id").jsonPrimitive.long);assertEquals(account,component.getValue("account_id").jsonPrimitive.long)
        assertEquals(JsonPrimitive("现金"),component["label"]);assertEquals(JsonPrimitive(""),component["source_key"]);assertEquals(JsonObject(emptyMap()),component["metadata"])
        assertEquals(0,component.getValue("amount").jsonPrimitive.content.toBigDecimal().compareTo("-10.50".toBigDecimal()))
        val balance=db.read {CashSnapshotRepository(CashSqlContext(it,workspace,db.sqlite)).load().value}
        assertEquals(if(db.sqlite) "-10.50" else "-10.5",balance.getValue("accounts").jsonObject.getValue("cash").jsonObject.getValue("账户").jsonObject.getValue("CNY").jsonPrimitive.content)
        assertEquals(1,state.getValue("cash_entries").size);assertEquals(1,state.getValue("cash_projections").size)
    }
    @Test fun omittedFieldsStayUnprotectedAndAggregateKeepsExactPerAccountAllocations()=inWorkspace {db,workspace,account ->
        val second=db.write {c ->deletionInsert(c,db,"accounts",linkedMapOf("workspace_id" to workspace,"name" to "借款账户","type" to "loan","active" to false,"revision" to 1L,"currencies" to JsonArray(emptyList()),"metadata_json" to JsonObject(emptyMap()),"created_at" to deletionTime,"updated_at" to deletionTime))!!}
        val parts=Json.parseToJsonElement("""[{"account_id":$account,"amount":"1.000000000000000001"},{"account_id":$second,"amount":"2"}]""")
        val result=ManualCashService(db).create("synthetic-admin",body(mapOf("amount" to JsonPrimitive("-3.000000000000000001"),"components" to parts)))
        val record=result.getValue("record").jsonObject;assertEquals(JsonPrimitive("aggregate"),record["cash_granularity"]);assertEquals(JsonPrimitive(""),record["account_name"])
        val state=snapshot(db,workspace);val fact=state.getValue("cash_transactions").single();assertEquals(JsonNull,fact["account_id"])
        assertEquals(JsonObject(emptyMap()),Json.parseToJsonElement(fact.getValue("manual_overrides").jsonPrimitive.content))
        assertEquals(2,state.getValue("cash_transaction_components").size)
        val balances=db.read {CashSnapshotRepository(CashSqlContext(it,workspace,db.sqlite)).load().value.getValue("accounts").jsonObject}
        assertEquals("-1.000000000000000001",balances.getValue("cash").jsonObject.getValue("账户").jsonObject.getValue("CNY").jsonPrimitive.content)
        assertEquals("-2",balances.getValue("loan").jsonObject.getValue("借款账户").jsonObject.getValue("CNY").jsonPrimitive.content)
    }
    @Test fun invalidAccountsAmountsCurrenciesComponentsAndPrivateFieldsCannotSaveAnything()=inWorkspace {db,workspace,_ ->
        val before=snapshot(db,workspace);val service=ManualCashService(db)
        for(patch in listOf(mapOf("account_name" to JsonPrimitive("missing")),mapOf("currency" to JsonPrimitive("USD")),mapOf("amount" to JsonPrimitive("NaN")),
            mapOf("components" to JsonArray(emptyList())),mapOf("components" to Json.parseToJsonElement("""[{"account_name":"账户","amount":"2"}]""")))) {
            assertFailsWith<ManualCashBusinessFailure> {service.create("synthetic-admin",body(patch))};assertEquals(before,snapshot(db,workspace))
        }
        assertFailsWith<ManualCashInputFailure> {service.create("synthetic-admin",body(mapOf("state" to JsonPrimitive("draft"))))}
        assertEquals(before,snapshot(db,workspace))
    }
    @Test fun lateProjectionFailureAndReturnFailureRollbackEveryFinancialTable()=inWorkspace {db,workspace,_ ->
        db.write {c ->c.prepareStatement("UPDATE cash_projection_states SET source_revision=9223372036854775807 WHERE workspace_id=?").use {it.setString(1,workspace);it.executeUpdate()}}
        val before=snapshot(db,workspace)
        assertFailsWith<ArithmeticException> {ManualCashService(db).create("synthetic-admin",body())}
        assertEquals(before,snapshot(db,workspace))
        db.write {c ->c.prepareStatement("UPDATE cash_projection_states SET source_revision=0 WHERE workspace_id=?").use {it.setString(1,workspace);it.executeUpdate()}}
        val baseline=snapshot(db,workspace)
        assertFailsWith<IllegalStateException> {ManualCashService(db,beforeReturn={error("late return")}).create("synthetic-admin",body())}
        assertEquals(baseline,snapshot(db,workspace))
    }
}
