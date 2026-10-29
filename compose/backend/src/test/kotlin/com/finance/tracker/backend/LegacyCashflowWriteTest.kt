package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class LegacyCashflowWriteTest {
    private fun fixture(action:(Database,String)->Unit) {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val workspaces=WorkspaceService(db);val name="旧现金写入测试";val workspace=workspaces.create("synthetic-admin",name).activeWorkspaceId!!
        try {
            db.write {c ->for((label,type) in listOf("账户" to "cash","投资账户" to "security")) deletionInsert(c,db,"accounts",linkedMapOf("workspace_id" to workspace,"name" to label,"type" to type,"active" to false,"revision" to 1L,
                "currencies" to JsonArray(emptyList()),"metadata_json" to JsonObject(emptyMap()),"created_at" to deletionTime,"updated_at" to deletionTime))}
            action(db,workspace)
        } finally {workspaces.delete("synthetic-admin",name);workspaces.select("synthetic-admin","fixture-a")}
    }
    private fun add(patch:Map<String,JsonElement> = emptyMap())=JsonObject(Json.parseToJsonElement("""{"account":"账户","amount":"-12.50","counterparty":"Coffee","currency":"CNY","date":"2026-07-20 09:00:00"}""").jsonObject+patch)
    private fun checkin(currency:String="CNY",balance:String="10000")=buildJsonObject {put("account","账户");put("balance",balance);put("currency",currency);put("date","2026-07-20")}
    private fun balances(db:Database,workspace:String)=db.read {CashSnapshotRepository(CashSqlContext(it,workspace,db.sqlite)).load().value.getValue("accounts").jsonObject.getValue("cash").jsonObject.getValue("账户").jsonObject}
    private val tables=listOf("cash_transactions","cash_transaction_components","ledger_snapshots","cash_entries","cash_entry_members","cash_projection_states","cash_projection_datasets","cash_projections","cash_projection_members","cash_projection_relations")
    private fun state(db:Database,workspace:String)=db.read {c ->tables.associateWith {table ->c.prepareStatement("SELECT * FROM $table WHERE workspace_id=?").use {s ->s.setString(1,workspace);s.executeQuery().use {r ->buildSet {while(r.next()) add((1..r.metaData.columnCount).associate {r.metaData.getColumnName(it) to r.getString(it)})}}}}}
    @Test fun legacyAddKeepsRawResponseSourceAndUnprotectedFieldsAndExactBalance()=fixture {db,workspace ->
        val result=LegacyCashflowService(db,now={deletionTime}).add("synthetic-admin",add(mapOf("source" to JsonPrimitive("手动来源"),"note" to JsonPrimitive(""),"category_id" to JsonNull)))
        assertEquals(JsonPrimitive(true),result["ok"])
        val row=result.getValue("row").jsonObject
        assertEquals(JsonPrimitive("-12.50"),row["amount"]);assertEquals(JsonPrimitive("2026-07-20 09:00:00"),row["occurred_at"])
        assertEquals(JsonPrimitive("手动来源"),row["source"]);assertEquals(JsonPrimitive("manual"),row["record_origin"])
        assertEquals(JsonPrimitive("-12.50"),balances(db,workspace)["CNY"])
        val saved=state(db,workspace);val fact=saved.getValue("cash_transactions").single()
        assertEquals("手动来源",fact["source_type"]);assertEquals("manual",fact["record_origin"])
        assertEquals(JsonObject(emptyMap()),Json.parseToJsonElement(fact.getValue("manual_overrides")!!))
        assertEquals(64,fact.getValue("source_fingerprint")!!.length)
        assertEquals(1,saved.getValue("cash_transaction_components").size);assertEquals(1,saved.getValue("cash_projections").size)
    }
    @Test fun addAndCheckinKeepSeparateCurrencyPocketsAndSourceZeroFacts()=fixture {db,workspace ->
        val service=LegacyCashflowService(db)
        for(body in listOf(add(),add(mapOf("amount" to JsonPrimitive("-500"),"currency" to JsonPrimitive("JPY"))))) assertEquals(JsonPrimitive(true),service.add("synthetic-admin",body)["ok"])
        for(body in listOf(checkin(),checkin("JPY","5000"))) assertEquals(JsonPrimitive(true),service.checkin("synthetic-admin",body)["ok"])
        assertEquals(JsonPrimitive("10000"),balances(db,workspace)["CNY"]);assertEquals(JsonPrimitive("5000"),balances(db,workspace)["JPY"])
        val saved=state(db,workspace);val calibration=saved.getValue("cash_transactions").filter {it["record_origin"]=="legacy"}
        assertEquals(2,calibration.size);assertEquals(setOf("手动"),calibration.map {it["source_type"]}.toSet())
        assertEquals(setOf("余额校准¥10000.00","余额校准5000.00"),calibration.map {it["note"]}.toSet())
        assertTrue(calibration.all {it["amount"]!!.toBigDecimal().signum()==0})
        assertEquals(JsonPrimitive("2026-07-20"),db.read {CashSnapshotRepository(CashSqlContext(it,workspace,db.sqlite)).load().value["updated_at"]})
    }
    @Test fun calibrationRoundsOnlyItsNoteAndPreservesNegativeZeroBalance()=fixture {db,workspace ->
        val service=LegacyCashflowService(db)
        val rounded=service.checkin("synthetic-admin",checkin("USD","-0.005"))
        assertEquals(JsonPrimitive("余额校准$-0.00"),rounded.getValue("row").jsonObject["note"])
        assertEquals(JsonPrimitive("-0.005"),balances(db,workspace)["USD"])
        service.checkin("synthetic-admin",checkin("USD","-0.00"))
        assertEquals(JsonPrimitive("-0.00"),balances(db,workspace)["USD"])
    }
    @Test fun missingCurrencyMissingAccountAndInvestmentAccountFailWithoutWrites()=fixture {db,workspace ->
        val service=LegacyCashflowService(db);val initial=state(db,workspace)
        for((body,code) in listOf(JsonObject(add()-"currency") to "currency_required",add(mapOf("currency" to JsonPrimitive("US"))) to "cashflow.currency_required",
            add(mapOf("account" to JsonPrimitive("missing"))) to "account.not_found",add(mapOf("account" to JsonPrimitive("投资账户"))) to "cashflow.unsupported_account_type")) {
            assertEquals(code,assertFailsWith<CashflowOperationFailure> {service.add("synthetic-admin",body)}.code);assertEquals(initial,state(db,workspace))
        }
    }
    @Test fun outOfRangeCalibrationAndLateFailuresRollbackEveryFinancialTable()=fixture {db,workspace ->
        val initial=state(db,workspace)
        assertFailsWith<IllegalArgumentException> {LegacyCashflowService(db).checkin("synthetic-admin",checkin(balance="100000000000000000000"))}
        assertEquals(initial,state(db,workspace))
        assertFailsWith<IllegalStateException> {LegacyCashflowService(db,beforeReturn={error("late")}).add("synthetic-admin",add())}
        assertEquals(initial,state(db,workspace))
        db.write {c ->c.prepareStatement("UPDATE cash_projection_states SET source_revision=9223372036854775807 WHERE workspace_id=?").use {it.setString(1,workspace);it.executeUpdate()}}
        val before=state(db,workspace)
        assertFailsWith<ArithmeticException> {LegacyCashflowService(db).checkin("synthetic-admin",checkin())}
        assertEquals(before,state(db,workspace))
    }
    @Test fun corruptBalancePocketsAndNonFiniteValuesStopWithoutChangingFinancialRows()=fixture {db,workspace ->
        for((pocket,message) in listOf(JsonArray(emptyList()) to "snapshot account balance must be a currency mapping",buildJsonObject {put("CNY","NaN")} to "value must be finite")) {
            db.write {c ->val repository=CashSnapshotRepository(CashSqlContext(c,workspace,db.sqlite));repository.save(CashSnapshotDocument(buildJsonObject {put("accounts",buildJsonObject {put("cash",buildJsonObject {put("账户",pocket)})})}))}
            val before=state(db,workspace)
            assertEquals(message,assertFailsWith<IllegalArgumentException> {LegacyCashflowService(db).add("synthetic-admin",add())}.message)
            assertEquals(before,state(db,workspace))
        }
    }
}
