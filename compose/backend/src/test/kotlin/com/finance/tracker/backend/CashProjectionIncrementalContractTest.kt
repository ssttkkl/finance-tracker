package com.finance.tracker.backend

import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashProjectionIncrementalContractTest {
    private class Rollback:RuntimeException()
    @Test fun everyColumnMatchesPythonAcrossSplitStandaloneDraftRemovalAndManualDisplayChanges() {
        val root=Path.of(requireNotNull(System.getenv("FT_KOTLIN_PROJECTION_INCREMENTAL_CONTRACT_DIR")))
        val config=Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val expected=Json.parseToJsonElement(root.resolve("projection-incremental.json").readText()).jsonArray
        val helper=CashProjectionDatasetContractTest();val clock=AtomicReference(deletionTime)
        val sources=listOf("cash_transactions","cash_transaction_components","cash_investment_funding_relations","transaction_relations","cash_transaction_issues")
        val before=db.read {c ->(helper.tables+sources).associateWith {helper.raw(c,it).toSet()}}
        assertFailsWith<Rollback> {db.write {c ->
            val context=CashSqlContext(c,"fixture-a",db.sqlite,clock::get)
            for((index,raw) in expected.withIndex()) {
                val case=raw.jsonObject;clock.set(Instant.parse(case.getValue("at").jsonPrimitive.content))
                when(index) {
                    1,3 ->c.prepareStatement("UPDATE cash_transactions SET counterparty='增量商家',note=?,row_revision=row_revision+1 WHERE workspace_id='fixture-a' AND id=1009").use {it.setString(1,if(index==1) "增量来源" else "独立刷新");it.executeUpdate()}
                    2 ->c.createStatement().use {it.executeUpdate("UPDATE transaction_relations SET active=false,row_revision=row_revision+1 WHERE workspace_id='fixture-a' AND id=3001")}
                    4 ->deletionInsert(c,db,"cash_transactions",linkedMapOf("id" to 10010L,"workspace_id" to "fixture-a","account_id" to null,"state" to "draft","record_origin" to "legacy","row_revision" to 1L,
                        "cash_granularity" to "atomic","record_id" to "new-draft","manual_overrides" to JsonObject(emptyMap()),"occurred_at" to null,"amount" to null,"currency" to null,
                        "counterparty" to "","counterparty_account" to "","counterparty_account_attrs" to JsonArray(emptyList()),"note" to "","category_id" to null,"source_type" to "incremental-fixture",
                        "record_type" to null,"record_subtype" to null,"created_at" to clock.get(),"deleted_at" to null,"deleted_by" to "","delete_reason" to ""))
                }
                val sourceBefore=sources.associateWith {helper.raw(c,it).toSet()}
                when(index) {
                    0 ->CashProjectionService.initializeInSession(context)
                    1 ->CashProjectionService.maintainIfReadyInSession(context,setOf(1009))
                    2 ->CashProjectionService.maintainInSession(context,setOf(1002))
                    3 ->CashProjectionService.replaceStandaloneFactIfReadyInSession(context,readCashFactRows(context,setOf(1009)).single())
                    4 ->CashProjectionService.maintainStandaloneFactIfReadyInSession(context,readCashFactRows(context,setOf(10010)).single())
                    5 ->CashProjectionService.removeIfReadyInSession(context,setOf(10010))
                    6 ->{val entry=context.entries.current(1009)!!;context.entries.patch(1009,mapOf("note" to "人工增量备注"),entry.revision)
                        CashProjectionService.refreshDisplayFieldsIfReadyInSession(context,1009,entry.values.getValue("counterparty").jsonPrimitive.content,entry.values.getValue("note").jsonPrimitive.content)}
                }
                assertEquals(sourceBefore,sources.associateWith {helper.raw(c,it).toSet()},"$index derived maintenance cannot rewrite sources")
                if(index==5) c.createStatement().use {assertEquals(1,it.executeUpdate("DELETE FROM cash_transactions WHERE workspace_id='fixture-a' AND id=10010"))}
                val status=CashProjectionRepository(context).status();val dataset=status.getValue("active_dataset_id").jsonPrimitive.content
                val actual=helper.snapshot(c,db,dataset)
                for((table,rows) in helper.normalizedExpected(case.getValue("effects")).jsonObject) {
                    assertEquals(rows.jsonArray.groupingBy {it}.eachCount(),actual.getValue(table).jsonArray.groupingBy {it}.eachCount(),"$index complete $table columns")
                }
                assertEquals(index+1L,status.getValue("projection_version").jsonPrimitive.long);assertEquals(index.toLong(),status.getValue("source_revision").jsonPrimitive.long)
            }
            throw Rollback()
        }}
        assertEquals(before,db.read {c ->(helper.tables+sources).associateWith {helper.raw(c,it).toSet()}},"restore dedicated fixture through caller rollback")
    }
}
