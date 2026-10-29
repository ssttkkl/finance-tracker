package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashTransferWriteTest {
    private fun fixture(action:(Database,String)->Unit) {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val service=WorkspaceService(db);val name="手工转账事务测试";val workspace=service.create("synthetic-admin",name).activeWorkspaceId!!
        try {
            db.write {c ->for((name,type) in listOf("来源" to "cash","目标" to "cash","券商" to "security","加密" to "crypto","贷款" to "loan"))
                deletionInsert(c,db,"accounts",linkedMapOf("workspace_id" to workspace,"name" to name,"type" to type,"active" to true,"revision" to 1L,"currencies" to JsonArray(emptyList()),"metadata_json" to JsonObject(emptyMap()),"created_at" to deletionTime,"updated_at" to deletionTime))}
            action(db,workspace)
        } finally {service.delete("synthetic-admin",name);service.select("synthetic-admin","fixture-a")}
    }
    private fun body(patch:Map<String,JsonElement> = emptyMap())=JsonObject(Json.parseToJsonElement("""{"from_account":"来源","to_account":"目标","from_currency":"CNY","to_currency":"USD","amount":"123.4500","to_amount":"17.25000","date":"2026-10-24","time":"04:30:00","note":"response regression"}""").jsonObject+patch)
    private val tables=listOf("cash_transactions","cash_transaction_components","investment_events","transaction_relations","cash_relation_candidates","ledger_snapshots","cash_entries","cash_entry_members","cash_projection_states","cash_projection_datasets","cash_projections","cash_projection_members","cash_projection_relations")
    private fun state(db:Database,workspace:String)=db.read {c ->tables.associateWith {table ->c.prepareStatement("SELECT * FROM $table WHERE workspace_id=?").use {s ->s.setString(1,workspace);s.executeQuery().use {r ->buildSet {while(r.next()) add((1..r.metaData.columnCount).associate {r.metaData.getColumnName(it) to r.getString(it)})}}}}}
    @Test fun cashTransferWritesBothRowsOneConfirmedRelationAndExactBalances()=fixture {db,workspace ->
        val result=CashTransferService(db,now={deletionTime}).transfer("synthetic-admin",body())
        assertEquals(JsonPrimitive(true),result["ok"])
        val rows=result.getValue("rows").jsonArray.map {it.jsonObject}
        assertEquals(listOf("-123.4500","17.25000"),rows.map {it.getValue("amount").jsonPrimitive.content})
        val snapshot=state(db,workspace);val facts=snapshot.getValue("cash_transactions")
        assertEquals(2,facts.size);assertEquals(setOf("manual_transfer"),facts.map {it["record_origin"]}.toSet())
        assertEquals(1,facts.map {it["manual_transfer_id"]}.toSet().size);assertTrue(facts.first()["manual_transfer_id"].orEmpty().isNotEmpty())
        val relation=snapshot.getValue("transaction_relations").single()
        assertEquals("transfer_pair",relation["kind"]);assertEquals("currency_exchange",relation["subtype"])
        assertEquals("confirmed",relation["state"]);assertTrue(relation["active"] in setOf("1","t","true"));assertEquals("manual.transfer.v1",relation["rule_id"])
        assertEquals(2,snapshot.getValue("cash_transaction_components").size);assertEquals(1,snapshot.getValue("cash_projections").size)
        val balances=db.read {CashSnapshotRepository(CashSqlContext(it,workspace,db.sqlite)).load().value.getValue("accounts").jsonObject.getValue("cash").jsonObject}
        assertEquals(JsonPrimitive("-123.4500"),balances.getValue("来源").jsonObject["CNY"])
        assertEquals(JsonPrimitive("17.25000"),balances.getValue("目标").jsonObject["USD"])
    }
    @Test fun sameCurrencyIgnoresProvidedArrivalAndLoanGetsRepaymentRelation()=fixture {db,workspace ->
        val result=CashTransferService(db).transfer("synthetic-admin",body(mapOf("to_account" to JsonPrimitive("贷款"),"to_currency" to JsonPrimitive("CNY"))))
        assertEquals(JsonPrimitive("同币种转账无需 --to-amount，忽略"),result.getValue("details").jsonObject["warning"])
        assertEquals(listOf("-123.4500","123.4500"),result.getValue("rows").jsonArray.map {it.jsonObject.getValue("amount").jsonPrimitive.content})
        assertEquals(setOf("repayment"),state(db,workspace).getValue("cash_transactions").map {it["record_type"]}.toSet())
        assertEquals("credit_repayment",state(db,workspace).getValue("transaction_relations").single()["subtype"])
    }
    @Test fun investmentAndCryptoSidesWriteFundingEventsAndSharesAndCostWithoutFakeCashRelations()=fixture {db,workspace ->
        val service=CashTransferService(db)
        val precise=service.transfer("synthetic-admin",body(mapOf("to_account" to JsonPrimitive("券商"),"amount" to JsonPrimitive("1.230000000000000001"),"to_amount" to JsonPrimitive("0.123456789012345678"))))
        assertEquals(JsonPrimitive(true),precise["ok"])
        service.transfer("synthetic-admin",body(mapOf("from_account" to JsonPrimitive("券商"),"to_account" to JsonPrimitive("加密"))))
        val stored=state(db,workspace)
        assertEquals(1,stored.getValue("cash_transactions").size);assertEquals(3,stored.getValue("investment_events").size)
        assertTrue(stored.getValue("transaction_relations").isEmpty())
        val values=db.read {CashSnapshotRepository(CashSqlContext(it,workspace,db.sqlite)).load().value.getValue("accounts").jsonObject}
        assertEquals(JsonPrimitive("-1.230000000000000001"),values.getValue("cash").jsonObject.getValue("来源").jsonObject["CNY"])
        val usd=values.getValue("security").jsonObject.getValue("券商").jsonObject.getValue("positions").jsonObject.getValue("usd").jsonObject
        assertEquals(JsonPrimitive("0.123456789012345678"),usd["shares"]);assertEquals(usd["shares"],usd["total_cost"])
    }
    @Test fun nonPositiveAndMissingArrivalAmountsHaveNoFinancialEffects()=fixture {db,workspace ->
        val before=state(db,workspace);val service=CashTransferService(db)
        for((amount,toAmount) in listOf("0" to null,"-1" to null,"1" to "0","1" to "-1")) {
            val failure=assertFailsWith<CashflowOperationFailure> {service.transfer("synthetic-admin",body(mapOf("amount" to JsonPrimitive(amount),"to_amount" to (toAmount?.let(::JsonPrimitive) ?: JsonNull))))}
            assertEquals("transfer.invalid_amount",failure.code);assertEquals(before,state(db,workspace))
        }
        assertEquals("transfer.to_amount_required",assertFailsWith<CashflowOperationFailure> {service.transfer("synthetic-admin",body(mapOf("to_amount" to JsonNull)))}.code)
        assertEquals(before,state(db,workspace))
    }
    @Test fun lateProjectionAndReturnFailuresRollbackAllThirteenTables()=fixture {db,workspace ->
        db.write {c ->c.prepareStatement("UPDATE cash_projection_states SET source_revision=9223372036854775807 WHERE workspace_id=?").use {it.setString(1,workspace);it.executeUpdate()}}
        val before=state(db,workspace)
        assertFailsWith<ArithmeticException> {CashTransferService(db).transfer("synthetic-admin",body())}
        assertEquals(before,state(db,workspace))
        db.write {c ->c.prepareStatement("UPDATE cash_projection_states SET source_revision=0 WHERE workspace_id=?").use {it.setString(1,workspace);it.executeUpdate()}}
        val baseline=state(db,workspace)
        assertFailsWith<IllegalStateException> {CashTransferService(db,beforeReturn={error("late return")}).transfer("synthetic-admin",body(mapOf("to_account" to JsonPrimitive("券商"))))}
        assertEquals(baseline,state(db,workspace))
    }
}
