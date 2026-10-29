package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashSnapshotMigrationTest {
    private class Rollback:RuntimeException()
    private fun inWorkspace(action:(Database,String,Long)->Unit) {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val workspaces=WorkspaceService(db);val name="现金余额快照测试";val workspace=workspaces.create("synthetic-admin",name).activeWorkspaceId!!;val account=deletionAccount(db,workspace)
        try {action(db,workspace,account)} finally {workspaces.delete("synthetic-admin",name);workspaces.select("synthetic-admin","fixture-a")}
    }
    private fun row(state:String="posted",amount:String="-12.5",time:String="2026-07-05T09:00:00+00:00",type:String="consumption",origin:String="import")=buildJsonObject {
        put("state",state);put("account_name","账户");put("account_type","cash");put("amount",amount);put("currency","CNY");put("occurred_at",time);put("record_type",type);put("record_origin",origin);put("components",JsonArray(emptyList()))
    }
    private fun balance(document:CashSnapshotDocument,name:String="账户",type:String="cash",currency:String="CNY")=document.value.getValue("accounts").jsonObject.getValue(type).jsonObject.getValue(name).jsonObject.getValue(currency).jsonPrimitive.content
    @Test fun defaultSnapshotIsReadOnlyAndStableAccountIdsFollowRenamesAndKeepUnresolvedKeys()=inWorkspace {db,workspace,account ->
        db.write {c ->val repository=CashSnapshotRepository(CashSqlContext(c,workspace,db.sqlite));val document=repository.load()
            assertEquals(Json.parseToJsonElement("""{"updated_at":"","accounts":{"cash":{},"loan":{},"lend":{},"security":{}}}"""),document.value)
            c.prepareStatement("SELECT count(*) FROM ledger_snapshots WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {r ->assertTrue(r.next());assertEquals(0,r.getInt(1))}}
            repository.setBalance(document,"账户","cash","CNY","12.50");repository.setBalance(document,"未匹配账户","cash","USD","3");repository.save(document)
            c.prepareStatement("SELECT payload FROM ledger_snapshots WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {r ->assertTrue(r.next());val cash=Json.parseToJsonElement(r.getString(1)).jsonObject.getValue("accounts").jsonObject.getValue("cash").jsonObject;assertTrue(account.toString() in cash);assertTrue("账户" !in cash);assertTrue("未匹配账户" in cash)}}
        }
        db.write {c ->c.prepareStatement("UPDATE accounts SET name='改名账户' WHERE workspace_id=? AND id=?").use {it.setString(1,workspace);it.setLong(2,account);it.executeUpdate()}}
        db.read {c ->val document=CashSnapshotRepository(CashSqlContext(c,workspace,db.sqlite)).load();assertEquals("12.50",balance(document,"改名账户"));assertEquals("3",balance(document,"未匹配账户",currency="USD"))}
    }
    @Test fun exactIncrementRetainsAllSupportedDigitsAndSplitComponentsUseTheirOwnAccounts()=inWorkspace {db,workspace,_ ->db.write {c ->
        val context=CashSqlContext(c,workspace,db.sqlite);val repository=context.snapshot;val document=repository.load(lock=true)
        repository.setBalance(document,"账户","cash","CNY","12345678901234567890.123456789012345678")
        repository.updateBalance(document,"账户","cash","CNY","0.000000000000000001")
        assertEquals("12345678901234567890.123456789012345679",balance(document))
        repository.save(document)
        val service=CashBalanceService(context)
        val parts=Json.parseToJsonElement("""[{"account_name":"账户","account_type":"cash","amount":"-0.000000000000000001","currency":"CNY"},{"account_name":"第二账户","account_type":"loan","amount":"-2.50","currency":"USD"}]""")
        service.applyChanges(listOf(null to JsonObject(row()+mapOf("components" to parts))))
        assertEquals("12345678901234567890.123456789012345678",balance(repository.load()))
        assertEquals("-2.50",balance(repository.load(),"第二账户","loan","USD"))
    }}
    @Test fun signedZeroAndScaleMatchDecimalArithmetic()=inWorkspace {db,workspace,_ ->db.write {c ->
        val repository=CashSnapshotRepository(CashSqlContext(c,workspace,db.sqlite));val document=repository.load()
        repository.setBalance(document,"账户","cash","CNY","-0.00");assertEquals("-0.00",balance(document))
        repository.updateBalance(document,"账户","cash","CNY","-0.0");assertEquals("-0.00",balance(document))
        repository.updateBalance(document,"账户","cash","CNY","0");assertEquals("0.00",balance(document))
        repository.setBalance(document,"账户","cash","CNY","-10.50");repository.updateBalance(document,"账户","cash","CNY","10.50");assertEquals("0.00",balance(document))
        for(exponent in listOf("2147483648","999999999999999999")) {
            repository.setBalance(document,"账户","cash","CNY","-0E$exponent");assertEquals("-0",balance(document))
            repository.setBalance(document,"账户","cash","CNY","0E$exponent");assertEquals("0",balance(document))
        }
    }}
    @Test fun sparseSnapshotPreservesMissingKeysAndSecurityBucketMapsCryptoAccounts()=inWorkspace {db,workspace,_ ->
        val crypto=db.write {c ->deletionInsert(c,db,"accounts",linkedMapOf("workspace_id" to workspace,"name" to "币账户","type" to "crypto","active" to true,"revision" to 1L,"currencies" to JsonArray(listOf(JsonPrimitive("USD"))),"metadata_json" to JsonObject(emptyMap()),"created_at" to deletionTime,"updated_at" to deletionTime))!!}
        db.write {c ->val repository=CashSnapshotRepository(CashSqlContext(c,workspace,db.sqlite));val document=repository.load();document.value=Json.parseToJsonElement("""{"private":{"retained":true}}""").jsonObject;repository.save(document)}
        db.write {c ->val repository=CashSnapshotRepository(CashSqlContext(c,workspace,db.sqlite));val document=repository.load();assertEquals(Json.parseToJsonElement("""{"private":{"retained":true}}"""),document.value)
            repository.setBalance(document,"币账户","security","USD","3.000");repository.save(document)
            c.prepareStatement("SELECT payload FROM ledger_snapshots WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {r ->assertTrue(r.next());val value=Json.parseToJsonElement(r.getString(1)).jsonObject;assertEquals(document.value["private"],value["private"]);assertTrue(crypto.toString() in value.getValue("accounts").jsonObject.getValue("security").jsonObject)}}
        }
        db.read {c ->assertEquals("3.000",balance(CashSnapshotRepository(CashSqlContext(c,workspace,db.sqlite)).load(),"币账户","security","USD"))}
    }
    @Test fun draftTransitionAffectsBalanceOnceAndOuterRollbackRestoresSnapshot()=inWorkspace {db,workspace,_ ->
        val before=db.read {CashSnapshotRepository(CashSqlContext(it,workspace,db.sqlite)).load().value}
        assertFailsWith<Rollback> {db.write {c ->val context=CashSqlContext(c,workspace,db.sqlite);val repository=context.snapshot;val service=CashBalanceService(context);val draft=row("draft");val posted=row()
            service.applyChanges(listOf(null to draft));service.applyChanges(listOf(draft to draft));assertEquals(before,repository.load().value)
            service.applyChanges(listOf(draft to posted));val after=repository.load().value;assertEquals("-12.5",balance(repository.load()))
            service.applyChanges(listOf(posted to posted));assertEquals(after,repository.load().value)
            throw Rollback()
        }}
        assertEquals(before,db.read {CashSnapshotRepository(CashSqlContext(it,workspace,db.sqlite)).load().value})
    }
    @Test fun originalThreeBalanceEntrancesPreserveEverySupportedDigit()=inWorkspace {db,workspace,_ ->
        for(entrance in listOf("snapshot_repository","ledger_delta","statement_delta")) assertFailsWith<Rollback> {db.write {c ->
            val context=CashSqlContext(c,workspace,db.sqlite);val repository=context.snapshot;val document=repository.load(lock=true)
            repository.setBalance(document,"账户","cash","CNY","12345678901234567890.123456789012345678");repository.save(document)
            val delta="0.000000000000000001";val posted=row(amount=delta,type="income")
            val result=when(entrance) {
                "snapshot_repository" ->{repository.updateBalance(document,"账户","cash","CNY",delta);document}
                "ledger_delta" ->{CashBalanceService(context).applyChanges(listOf(null to posted));repository.load()}
                else ->{CashBalanceService(context).applyComponents(document,posted,1);document}
            }
            assertEquals("12345678901234567890.123456789012345679",balance(result),entrance)
            throw Rollback()
        }}
    }
    @Test fun removingPostedRecordsNeverRoundsBeforeEitherOriginalBalanceEntrance()=inWorkspace {db,workspace,_ ->
        for(entrance in listOf("ledger_delta","statement_delta")) assertFailsWith<Rollback> {db.write {c ->
            val context=CashSqlContext(c,workspace,db.sqlite);val repository=context.snapshot;val document=repository.load(lock=true)
            val amount="12345678901234567890.123456789012345678";repository.setBalance(document,"账户","cash","CNY",amount);repository.save(document)
            val posted=row(amount=amount,type="income")
            val result=if(entrance=="ledger_delta") {CashBalanceService(context).applyChanges(listOf(posted to null));repository.load()}
                else {CashBalanceService(context).applyComponents(document,posted,-1);document}
            assertEquals(0,balance(result).toBigDecimal().signum(),entrance)
            throw Rollback()
        }}
    }
    @Test fun balanceEligibilityKeepsManualTransfersAndIgnoresDraftDeletedAndImportedTransfers() {
        assertFalse(cashCountsTowardBalance(null));assertFalse(cashCountsTowardBalance(JsonObject(emptyMap())))
        for(type in listOf("transfer_in","transfer_out")) {assertFalse(cashCountsTowardBalance(row(type=type)));assertTrue(cashCountsTowardBalance(row(type=type,origin="manual")))}
        for(value in listOf(row("draft"),JsonObject(row()+mapOf("deleted" to JsonPrimitive(true))),JsonObject(row()+mapOf("deleted_at" to JsonPrimitive("2026-07-05"))))) assertFalse(cashCountsTowardBalance(value))
        assertTrue(cashCountsTowardBalance(row()));assertTrue(cashCountsTowardBalance(buildJsonObject {put("amount","1")}))
    }
    @Test fun mixedChangesKeepTheLastEligibleTimeAndRemovalDoesNotRoundFirst()=inWorkspace {db,workspace,_ ->db.write {c ->
        val context=CashSqlContext(c,workspace,db.sqlite);val repository=context.snapshot;val service=CashBalanceService(context);val document=repository.load(lock=true)
        val amount="12345678901234567890.123456789012345678";repository.setBalance(document,"账户","cash","CNY",amount);repository.save(document)
        val posted=row(amount=amount);service.applyChanges(listOf(posted to null,row("draft",time="2026-07-09T00:00:00Z") to null))
        assertEquals(0,balance(repository.load()).toBigDecimal().signum());assertEquals(posted["occurred_at"],repository.load().value["updated_at"])
    }}
    @Test fun saveCopiesValuesAndVersionsAdvanceWhileInvalidMoneyAndBucketsRollback()=inWorkspace {db,workspace,_ ->
        db.write {c ->val repository=CashSnapshotRepository(CashSqlContext(c,workspace,db.sqlite));val first=repository.load();repository.setBalance(first,"账户","cash","CNY","1.00");repository.save(first)
            repository.setBalance(first,"账户","cash","CNY","2.00");assertEquals("1.00",balance(repository.load()))
            repository.save(first);assertEquals("2.00",balance(repository.load()))
            c.prepareStatement("SELECT version FROM ledger_snapshots WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {r ->assertTrue(r.next());assertEquals(2L,r.getLong(1))}}
        }
        val before=db.read {CashSnapshotRepository(CashSqlContext(it,workspace,db.sqlite)).load().value}
        assertFailsWith<IllegalArgumentException> {db.write {c ->val repository=CashSnapshotRepository(CashSqlContext(c,workspace,db.sqlite));val document=repository.load(lock=true);repository.setBalance(document,"账户","cash","CNY","99999999999999999999");repository.save(document);repository.updateBalance(document,"账户","cash","CNY","1")}}
        assertEquals(before,db.read {CashSnapshotRepository(CashSqlContext(it,workspace,db.sqlite)).load().value})
        db.write {c ->val repository=CashSnapshotRepository(CashSqlContext(c,workspace,db.sqlite));val document=repository.load();document.value=Json.parseToJsonElement("""{"accounts":{"cash":{"账户":"broken"}}}""").jsonObject
            assertEquals("snapshot account balance must be a currency mapping",assertFailsWith<IllegalArgumentException> {repository.updateBalance(document,"账户","cash","CNY","1")}.message)
        }
    }
    @Test fun savepointRollbackRestoresCachedBalanceBeforeFurtherUpdates()=inWorkspace {db,workspace,_ ->db.write {c ->
        val repository=CashSnapshotRepository(CashSqlContext(c,workspace,db.sqlite));val first=repository.load(lock=true);repository.setBalance(first,"账户","cash","CNY","10.50");repository.save(first)
        c.createStatement().use {it.execute("SAVEPOINT balance_boundary")}
        val modified=repository.load();repository.updateBalance(modified,"账户","cash","CNY","2.5");repository.save(modified)
        c.createStatement().use {it.execute("ROLLBACK TO balance_boundary");it.execute("RELEASE balance_boundary")}
        assertEquals("10.50",balance(repository.load()));val restored=repository.load();repository.updateBalance(restored,"账户","cash","CNY","1.00");repository.save(restored);assertEquals("11.50",balance(repository.load()))
    }}
    @Test fun nonStringAccountTypesStopTheWholeChangeWithoutCreatingBalanceBuckets()=inWorkspace {db,workspace,_ ->
        for(type in listOf(JsonArray(listOf(JsonPrimitive("cash"))),JsonArray(emptyList()),buildJsonObject {put("bad",true)},JsonPrimitive(true),JsonPrimitive(false),JsonPrimitive(1),JsonPrimitive(1.0),JsonPrimitive(7),JsonPrimitive(7.0))) {
            assertFailsWith<IllegalArgumentException> {db.write {c ->val component=JsonObject(row()+mapOf("account_type" to type));CashBalanceService(CashSqlContext(c,workspace,db.sqlite)).applyChanges(listOf(null to component))}}
            db.read {c ->c.prepareStatement("SELECT count(*) FROM ledger_snapshots WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {r ->assertTrue(r.next());assertEquals(0,r.getInt(1))}}}
        }
    }
    @Test fun canonicalNegativeAccountIdsResolveBeforeTheUnicodeDigitFallback()=inWorkspace {db,workspace,_ ->db.write {c ->
        val id=-400001L
        deletionInsert(c,db,"accounts",linkedMapOf("id" to id,"workspace_id" to workspace,"name" to "负号账户","type" to "cash","active" to true,"revision" to 1L,"currencies" to JsonArray(listOf(JsonPrimitive("CNY"))),"metadata_json" to JsonObject(emptyMap()),"created_at" to deletionTime,"updated_at" to deletionTime))
        val repository=CashSnapshotRepository(CashSqlContext(c,workspace,db.sqlite));val document=repository.load()
        document.value=Json.parseToJsonElement("""{"accounts":{"cash":{"-400001":{"CNY":"4.00"}}}}""").jsonObject;repository.save(document)
        val fresh=CashSnapshotRepository(CashSqlContext(c,workspace,db.sqlite));assertEquals("4.00",balance(fresh.load(),"负号账户"));fresh.updateBalance(fresh.load(),"负号账户","cash","CNY","1.00");fresh.save(fresh.load())
        val reloaded=CashSnapshotRepository(CashSqlContext(c,workspace,db.sqlite)).load();assertEquals("5.00",balance(reloaded,"负号账户"));assertFalse("-400001" in reloaded.value.getValue("accounts").jsonObject.getValue("cash").jsonObject)
    }}
}
