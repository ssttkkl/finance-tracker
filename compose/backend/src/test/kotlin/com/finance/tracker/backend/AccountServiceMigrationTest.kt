package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class AccountServiceMigrationTest {
    private fun database(): Database {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")), "database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    private fun inWorkspace(test: (AccountService, Database, String) -> Unit) {
        val database = database()
        val workspaces = WorkspaceService(database)
        val state = workspaces.create("synthetic-admin", "账户原生测试")
        val workspace=requireNotNull(state.activeWorkspaceId)
        setAccountTestRole(database,workspace,"editor")
        try { test(AccountService(database), database, workspace) }
        finally { setAccountTestRole(database,workspace,"admin");workspaces.delete("synthetic-admin", "账户原生测试"); workspaces.select("synthetic-admin", "fixture-a") }
    }
    private fun payload(name: String = "新增测试账户") = buildJsonObject {
        put("name", name); put("type", "cash"); put("currencies", JsonArray(listOf(JsonPrimitive("CNY"))))
    }
    @Test fun accountIsImmediatelyAvailableAndExactCreateReplayIsStable() = inWorkspace { service, database, workspace ->
        val result = service.create("synthetic-admin", payload(), "create")
        val account = result.getValue("account").jsonObject
        val id = account.getValue("account_id").jsonPrimitive.content
        assertEquals(1, account.getValue("account_revision").jsonPrimitive.int)
        assertEquals(JsonPrimitive(true), result["committed"])
        assertEquals("新增测试账户", account.getValue("name").jsonPrimitive.content)
        assertEquals(1L, service.get("synthetic-admin", id).accountRevision)
        assertEquals(result, service.create("synthetic-admin", payload(), "create"))
        database.read { c -> c.prepareStatement("SELECT count(*) FROM accounts WHERE workspace_id=? AND name=?").use {
            it.setString(1, workspace); it.setString(2, "新增测试账户"); it.executeQuery().use { rows -> rows.next(); assertEquals(1,rows.getInt(1)) }
        } }
    }
    @Test fun revisionChecksPrecedeMutationsButFollowDurableReplay() = inWorkspace { service, _, _ ->
        val created = service.create("synthetic-admin", payload(), "create")
        val id = created.getValue("account_id").jsonPrimitive.content
        val patch = buildJsonObject { put("name", "其他修改") }
        val updated = service.update("synthetic-admin", id, patch, 1, "edit")
        assertEquals(2, updated.getValue("account_revision").jsonPrimitive.int)
        assertEquals("account_changed", assertFailsWith<AccountFailure> {
            service.update("synthetic-admin", id, buildJsonObject { put("name", "过时补偿") }, 1, "undo")
        }.code)
        assertEquals("其他修改", service.get("synthetic-admin", id).name)
        assertEquals(updated, service.update("synthetic-admin", id, patch, 1, "edit"))
        assertEquals(created, service.create("synthetic-admin", payload(), "create"))
    }
    @Test fun noopPatchAdvancesRevisionAndInvalidTypePatchIsAtomic() = inWorkspace { service, _, _ ->
        val id = service.create("synthetic-admin", payload(), "create").getValue("account_id").jsonPrimitive.content
        assertFailsWith<AccountFailure> { service.update("synthetic-admin", id, buildJsonObject { put("name", "不能部分应用");put("type", "loan") }, 1, "invalid") }
        assertEquals("新增测试账户", service.get("synthetic-admin", id).name)
        assertEquals(1L, service.get("synthetic-admin", id).accountRevision)
        assertEquals(2, service.update("synthetic-admin", id, buildJsonObject {}, 1, "noop").getValue("account_revision").jsonPrimitive.int)
        assertEquals(2L, service.get("synthetic-admin", id).accountRevision)
    }
    @Test fun deleteReplayOutlivesAccountAndIdentityIsNotReused() = inWorkspace { service, _, _ ->
        val created = service.create("synthetic-admin", payload(), "create")
        val id = created.getValue("account_id").jsonPrimitive.content
        assertEquals("account_active", assertFailsWith<AccountFailure> { service.delete("synthetic-admin", id, 1, "early-delete") }.code)
        val inactive = service.update("synthetic-admin", id, buildJsonObject { put("active", false) }, 1, "deactivate")
        val deleted = service.delete("synthetic-admin", id, 2, "delete")
        assertEquals(2, inactive.getValue("account_revision").jsonPrimitive.int)
        assertEquals(JsonPrimitive(true), deleted.getValue("account").jsonObject["deleted"])
        assertEquals(deleted, service.delete("synthetic-admin", id, 2, "delete"))
        assertEquals("account_not_found", assertFailsWith<AccountFailure> { service.get("synthetic-admin", id) }.code)
        assertEquals(created, service.create("synthetic-admin", payload(), "create"))
        assertNotEquals(id, service.create("synthetic-admin", payload("另一个账户"), "new-create").getValue("account_id").jsonPrimitive.content)
    }
    @Test fun sameKeyDifferentCommandRejectsWithoutMutation() = inWorkspace { service, _, _ ->
        val created = service.create("synthetic-admin", payload(), "create")
        val id = created.getValue("account_id").jsonPrimitive.content
        assertEquals("idempotency_conflict", assertFailsWith<AccountFailure> { service.create("synthetic-admin", payload("不同内容"), "create") }.code)
        assertEquals("idempotency_conflict", assertFailsWith<AccountFailure> { service.update("synthetic-admin", id, buildJsonObject { put("name","冲突") }, 1, "create") }.code)
        assertEquals(1L,service.get("synthetic-admin",id).accountRevision)
    }
    private fun storeSnapshot(database: Database, workspace: String, payload: JsonObject) = database.write { c ->
        val parameter=if(database.sqlite) "?" else "CAST(? AS JSON)"
        c.prepareStatement("UPDATE ledger_snapshots SET payload=$parameter WHERE workspace_id=?").use {
            it.setString(1,payload.toString());it.setString(2,workspace);assertEquals(1,it.executeUpdate())
        }
    }
    @Test fun currencyExtensionsAndRenamePreserveExistingBalancesAndDuplicateFailureIsAtomic() = inWorkspace { service,database,workspace ->
        val id=service.create("synthetic-admin",payload(),"create").getValue("account_id").jsonPrimitive.content
        storeSnapshot(database,workspace,Json.parseToJsonElement("""{"accounts":{"cash":{"$id":{"CNY":"123.456"}}}}""").jsonObject)
        service.update("synthetic-admin",id,buildJsonObject { put("name","更名账户");put("currencies",JsonArray(listOf(JsonPrimitive("usd")))) },1,"currency")
        database.read { c -> c.prepareStatement("SELECT payload FROM ledger_snapshots WHERE workspace_id=?").use {
            it.setString(1,workspace);it.executeQuery().use { rows -> rows.next()
                assertEquals(Json.parseToJsonElement("""{"CNY":"123.456","USD":"0"}"""),Json.parseToJsonElement(rows.getString(1)).jsonObject.getValue("accounts").jsonObject.getValue("cash").jsonObject[id])
            }
        } }
        service.create("synthetic-admin",payload("已有名称"),"second")
        assertEquals("account_name_unavailable",assertFailsWith<AccountFailure> {
            service.update("synthetic-admin",id,buildJsonObject {put("name","已有名称");put("currencies",JsonArray(listOf(JsonPrimitive("HKD"))))},2,"duplicate")
        }.code)
        assertEquals(listOf("USD"),service.get("synthetic-admin",id).currencies)
        val unchanged=service.create("synthetic-admin",payload("原币种账户"),"original-currencies").getValue("account_id").jsonPrimitive.content
        assertEquals("account_name_unavailable",assertFailsWith<AccountFailure> {
            service.update("synthetic-admin",unchanged,buildJsonObject {put("name","已有名称");put("currencies",JsonArray(listOf(JsonPrimitive("USD"))))},1,"original-duplicate")
        }.code)
        assertEquals(listOf("CNY"),service.get("synthetic-admin",unchanged).currencies)
    }
    @Test fun nonzeroCryptoAndUnreadableSnapshotsPreventAccountDeletion() = inWorkspace { service,database,workspace ->
        val id=service.create("synthetic-admin",payload(),"create").getValue("account_id").jsonPrimitive.content
        service.update("synthetic-admin",id,buildJsonObject {put("active",false)},1,"deactivate")
        for(bucket in listOf("""{"$id":{"CNY":"1"}}""","\"invalid bucket\"","null","[]")) {
            storeSnapshot(database,workspace,Json.parseToJsonElement("""{"accounts":{"cash":$bucket}}""").jsonObject)
            assertEquals(false,service.get("synthetic-admin",id).capabilities["can_delete"],bucket)
            assertEquals("account_in_use",assertFailsWith<AccountFailure> {service.delete("synthetic-admin",id,2,"delete")}.code,bucket)
        }
        storeSnapshot(database,workspace,Json.parseToJsonElement("""{"accounts":{"cash":{}}}""").jsonObject)
        val crypto=service.create("synthetic-admin",JsonObject(payload("加密账户")+mapOf("type" to JsonPrimitive("crypto"),"currencies" to JsonArray(listOf(JsonPrimitive("USD"))))),"crypto").getValue("account_id").jsonPrimitive.content
        storeSnapshot(database,workspace,Json.parseToJsonElement("""{"accounts":{"security":{"$crypto":{"cash":{"USD":"100"},"positions":[]}}}}""").jsonObject)
        service.update("synthetic-admin",crypto,buildJsonObject {put("active",false)},1,"deactivate-crypto")
        assertEquals(false,service.get("synthetic-admin",crypto).capabilities["can_delete"])
        assertEquals("account_in_use",assertFailsWith<AccountFailure> {service.delete("synthetic-admin",crypto,2,"delete-crypto")}.code)
    }
    @Test fun invalidPatchesDoNotWriteOrAdvanceRevision() = inWorkspace { service,_,_ ->
        val id=service.create("synthetic-admin",payload(),"create").getValue("account_id").jsonPrimitive.content
        for(raw in listOf("""{"currencies":"CNY"}""","""{"active":1}""","""{"name":""}""",
            buildJsonObject {put("name","x".repeat(256))}.toString(),"""{"currencies":["人民币"]}""","""{"source_payload":{}}""",
            """{"name":"\uD800"}""")) {
            assertEquals("account_payload_invalid",assertFailsWith<AccountFailure> {service.update("synthetic-admin",id,Json.parseToJsonElement(raw).jsonObject,1,"invalid")}.code)
            assertEquals(1L,service.get("synthetic-admin",id).accountRevision)
        }
    }
    @Test fun accountAndReceiptRollbackTogetherAfterLateFailure() = inWorkspace { _,database,workspace ->
        val service=AccountService(database,beforeCommit={throw IllegalStateException("synthetic outer failure")})
        assertFailsWith<IllegalStateException> {service.create("synthetic-admin",payload(),"create")}
        database.read { c -> for(table in listOf("accounts","cash_mutation_receipts","cash_mutation_results","ledger_snapshots"))
            c.prepareStatement("SELECT count(*) FROM $table WHERE workspace_id=?").use {
                it.setString(1,workspace);it.executeQuery().use { rows ->rows.next();assertEquals(0,rows.getInt(1),table)}
            }
        }
    }
    @Test fun concurrentRetriesPublishOnlyOneAccountAndOneResult() = inWorkspace { service,_,_ ->
        val executor=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val replies=(1..2).map {executor.submit<JsonObject> { service.create("synthetic-admin",payload(),"create") }}.map {it.get(10,java.util.concurrent.TimeUnit.SECONDS)}
            assertEquals(replies[0],replies[1])
        } finally {executor.shutdownNow()}
    }

    @Test fun signedNumericAccountNamesKeepTheirOwnLegacyBalances() = inWorkspace { service,database,workspace ->
        val first=service.create("synthetic-admin",payload("普通账户"),"ordinary").getValue("account_id").jsonPrimitive.content
        val name="+"+first
        val second=service.create("synthetic-admin",payload(name),"signed").getValue("account_id").jsonPrimitive.content
        storeSnapshot(database,workspace,Json.parseToJsonElement("""{"accounts":{"cash":{"$name":{"CNY":"100"}}}}""").jsonObject)
        service.update("synthetic-admin",second,buildJsonObject {put("active",false)},1,"deactivate")
        assertEquals(false,service.get("synthetic-admin",second).capabilities["can_delete"])
        assertEquals("account_in_use",assertFailsWith<AccountFailure> {service.delete("synthetic-admin",second,2,"delete")}.code)
        database.read { c ->c.prepareStatement("SELECT payload FROM ledger_snapshots WHERE workspace_id=?").use {
            it.setString(1,workspace);it.executeQuery().use { rows ->rows.next()
                assertEquals(Json.parseToJsonElement("""{"CNY":"100"}"""),Json.parseToJsonElement(rows.getString(1)).jsonObject.getValue("accounts").jsonObject.getValue("cash").jsonObject[second])
            }
        } }
    }
    @Test fun invalidSnapshotRootCannotCommitCryptoMutationOrReceipt() = inWorkspace { service,database,workspace ->
        val id=service.create("synthetic-admin",JsonObject(payload()+("type" to JsonPrimitive("crypto"))),"crypto").getValue("account_id").jsonPrimitive.content
        storeSnapshot(database,workspace,Json.parseToJsonElement("""{"accounts":[]}""").jsonObject)
        assertFailsWith<IllegalStateException> {service.update("synthetic-admin",id,buildJsonObject {put("active",false)},1,"bad-snapshot")}
        assertEquals(1L,service.get("synthetic-admin",id).accountRevision)
        database.read { c ->c.prepareStatement("SELECT count(*) FROM cash_mutation_receipts WHERE workspace_id=? AND idempotency_key='bad-snapshot'").use {
            it.setString(1,workspace);it.executeQuery().use { rows ->rows.next();assertEquals(0,rows.getInt(1)) }
        } }
    }

    @Test fun existingReceiptMetadataAndStrictKeysPreserveReplayBoundaries() = inWorkspace { service,database,workspace ->
        for(key in listOf("a b","中文","","bad/key")) assertFailsWith<AccountFailure> {service.create("synthetic-admin",payload(),key)}
        val created=service.create("synthetic-admin",payload(),"create")
        val receipt=created.getValue("receipt_id").jsonPrimitive.content
        database.write { c ->c.prepareStatement("UPDATE cash_mutation_receipts SET plan_id='old-plan' WHERE id=?").use {it.setString(1,receipt);it.executeUpdate()} }
        assertEquals("idempotency_conflict",assertFailsWith<AccountFailure> {service.create("synthetic-admin",payload(),"create")}.code)
        val digest="a".repeat(64)
        database.write { c ->c.prepareStatement("UPDATE cash_mutation_receipts SET plan_id=NULL,session_digest=? WHERE id=?").use {it.setString(1,digest);it.setString(2,receipt);it.executeUpdate()} }
        assertEquals(JsonPrimitive(digest),service.create("synthetic-admin",payload(),"create")["session_digest"])
        database.read {c ->c.prepareStatement("SELECT count(*) FROM accounts WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {rows ->rows.next();assertEquals(1,rows.getInt(1))}}}
    }
    @Test fun referencesPreventCompensationFromCascadingAndPermissionsAreFresh() = inWorkspace { service,database,workspace ->
        for(kind in listOf("alias","mapping","draft_component")) {
            val id=service.create("synthetic-admin",payload("引用账户-$kind"),"create-$kind").getValue("account_id").jsonPrimitive.content.toLong()
            database.write {c ->
                val stamp=if(database.sqlite) "'2026-01-01 00:00:00'" else "TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00+00'"
                when(kind) {
                    "alias" ->c.prepareStatement("INSERT INTO account_aliases(workspace_id,account_id,alias_type,alias_value,created_at,updated_at) VALUES(?,?,'other','synthetic',$stamp,$stamp)").use {it.setString(1,workspace);it.setLong(2,id);it.executeUpdate()}
                    "mapping" ->c.prepareStatement("INSERT INTO statement_account_mappings(workspace_id,account_id,source_type,identity_kind,source_account_key,confirmed_by,revision,created_at,updated_at) VALUES(?,?,'alipay','account','synthetic','',1,$stamp,$stamp)").use {it.setString(1,workspace);it.setLong(2,id);it.executeUpdate()}
                    else -> {
                        val transaction=c.prepareStatement("INSERT INTO cash_transactions(workspace_id,state,cash_granularity,record_id,manual_overrides,counterparty,counterparty_account,counterparty_account_attrs,note,created_at,deleted_by,delete_reason) VALUES(?,'draft','aggregate','',${if(database.sqlite) "'{}'" else "'{}'::json"},'','',${if(database.sqlite) "'[]'" else "'[]'::json"},'',$stamp,'','') RETURNING id").use {it.setString(1,workspace);it.executeQuery().use {rows ->rows.next();rows.getLong(1)}}
                        c.prepareStatement("INSERT INTO cash_transaction_components(workspace_id,cash_transaction_id,account_id,ordinal,label,source_key,metadata_json,created_at) VALUES(?,?,?,0,'','',${if(database.sqlite) "'{}'" else "'{}'::json"},$stamp)").use {it.setString(1,workspace);it.setLong(2,transaction);it.setLong(3,id);it.executeUpdate()}
                    }
                }
            }
            service.update("synthetic-admin",id.toString(),buildJsonObject {put("active",false)},1,"deactivate-$kind")
            assertEquals("account_in_use",assertFailsWith<AccountFailure> {service.delete("synthetic-admin",id.toString(),2,"delete-$kind")}.code)
            assertFalse(service.get("synthetic-admin",id.toString()).active)
        }
        val foreign=database.write {c ->
            val json=if(database.sqlite) "'[]'" else "'[]'::json"
            val metadata=if(database.sqlite) "'{}'" else "'{}'::json"
            c.prepareStatement("INSERT INTO accounts(workspace_id,name,type,active,revision,currencies,metadata_json,created_at,updated_at) VALUES('fixture-b','account-native-foreign','cash',true,1,$json,$metadata,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id").use {
                it.executeQuery().use {rows ->rows.next();rows.getLong(1).toString()}
            }
        }
        try {
            for(id in listOf("999999",foreign)) assertEquals("account_not_found",assertFailsWith<AccountFailure> {service.get("synthetic-admin",id)}.code)
            assertEquals("account_not_found",assertFailsWith<AccountFailure> {service.update("synthetic-admin",foreign,buildJsonObject {put("name","不可跨工作区")},1,"foreign-account")}.code)
        } finally {database.write {c ->c.prepareStatement("DELETE FROM accounts WHERE workspace_id='fixture-b' AND id=?").use {it.setLong(1,foreign.toLong());it.executeUpdate()}}}
        database.write {c ->c.prepareStatement("UPDATE workspace_memberships SET role='viewer' WHERE workspace_id=? AND user_id='admin'").use {it.setString(1,workspace);it.executeUpdate()}}
        try {
            assertEquals("workspace_forbidden",assertFailsWith<AccountFailure> {service.create("synthetic-admin",payload(),"forbidden")}.code)
            val id=database.read {c ->c.prepareStatement("SELECT id FROM accounts WHERE workspace_id=? ORDER BY id LIMIT 1").use {it.setString(1,workspace);it.executeQuery().use {rows ->rows.next();rows.getString(1)}}}
            assertEquals(false,service.get("synthetic-admin",id).capabilities["can_update"])
        } finally {database.write {c ->c.prepareStatement("UPDATE workspace_memberships SET role='admin' WHERE workspace_id=? AND user_id='admin'").use {it.setString(1,workspace);it.executeUpdate()}}}
    }

    @Test fun invalidLegacySnapshotNumericKeysReturnInputErrorsWithoutWrites() = inWorkspace {service,database,workspace ->
        val id=service.create("synthetic-admin",payload(),"create").getValue("account_id").jsonPrimitive.content
        for(key in listOf("²","1".repeat(4301))) {
            storeSnapshot(database,workspace,buildJsonObject {put("accounts",buildJsonObject {put("cash",buildJsonObject {put(key,buildJsonObject {put("CNY","0")})})})})
            assertEquals("account_payload_invalid",assertFailsWith<AccountFailure> {service.update("synthetic-admin",id,buildJsonObject {put("active",false)},1,"invalid-key")}.code)
            assertEquals(1L,service.get("synthetic-admin",id).accountRevision)
        }
    }

}
