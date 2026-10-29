package com.finance.tracker.backend

import java.nio.file.Path
import java.sql.Connection
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class ReceiptStoreMigrationTest {
    private fun inWorkspace(action: (Database,String)->Unit) {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val database=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val workspaces=WorkspaceService(database);val workspace=workspaces.create("synthetic-admin","收据存储测试").activeWorkspaceId!!
        try {action(database,workspace)} finally {workspaces.delete("synthetic-admin","收据存储测试");workspaces.select("synthetic-admin","fixture-a")}
    }
    private fun repo(c: Connection,db: Database,workspace: String)=ReceiptRepository(c,workspace,db.sqlite)
    private fun save(repo: ReceiptRepository,changes: Map<String,Any?> = emptyMap()): JsonObject {
        val values=mapOf("user" to "fixture-user","key" to "request-1","digest" to "a".repeat(64),"operation" to "import","plan" to "plan-1","session" to "b".repeat(64),"edit" to null,
            "summary" to mapOf("status" to "completed","summary" to mapOf("created_posted" to 1),"needs_attention_count" to 0),
            "results" to listOf(mapOf("result_kind" to "row","item_ref" to "f1:r1","outcome" to "created_posted","transaction_id" to "1001","operation_ref" to "op-1")))+changes
        return repo.create(values["user"],values["key"],values["digest"],values["operation"],values["summary"],values["results"],values["plan"],values["session"],values["edit"])
    }
    @Test fun receiptsItemsAndBusinessChangeCommitOrRollBackTogether() = inWorkspace {db,workspace ->
        val transaction=db.write {c ->c.prepareStatement("""INSERT INTO cash_transactions(workspace_id,state,record_origin,cash_granularity,record_id,manual_overrides,counterparty,counterparty_account,counterparty_account_attrs,note,created_at,deleted_by,delete_reason)
            VALUES(?,'draft','manual','atomic','','{}','','','[]','original',?,'','') RETURNING id""").use {
            it.setString(1,workspace);it.instant(2,Instant.parse("2026-01-01T00:00:00Z"),db.sqlite);it.executeQuery().use {rows ->check(rows.next());rows.getLong(1)}
        }}
        assertFailsWith<IllegalStateException> {db.write {c ->
            c.prepareStatement("UPDATE cash_transactions SET note='rolled back together' WHERE id=? AND workspace_id=?").use {it.setLong(1,transaction);it.setString(2,workspace);assertEquals(1,it.executeUpdate())}
            save(repo(c,db,workspace));error("synthetic outer failure")
        }}
        db.write {c ->
            val repo=repo(c,db,workspace);assertNull(repo.byKey("fixture-user","request-1"))
            c.prepareStatement("SELECT note FROM cash_transactions WHERE id=? AND workspace_id=?").use {it.setLong(1,transaction);it.setString(2,workspace);it.executeQuery().use {rows ->assertTrue(rows.next());assertNotEquals("rolled back together",rows.getString(1))}}
            val receipt=save(repo);assertEquals(1,repo.results("fixture-user",receipt.getValue("receipt_id").jsonPrimitive.content)!!.getValue("items").jsonArray.size)
        }
    }
    @Test fun exactRetryReturnsTheUnchangedPermanentReceipt() = inWorkspace {db,workspace ->
        val original=db.write {c ->val repo=repo(c,db,workspace);val value=save(repo)
            assertEquals(value,repo.replay("fixture-user","request-1","a".repeat(64),"import","plan-1"));value}
        db.read {c ->assertEquals(original,repo(c,db,workspace).byKey("fixture-user","request-1"))}
    }
    @Test fun sameKeyRejectsEachChangedRequestDimension() = inWorkspace {db,workspace ->db.write {c ->
        val repo=repo(c,db,workspace);val original=save(repo)
        for((digest,operation,plan) in listOf(Triple("c".repeat(64),"import","plan-1"),Triple("a".repeat(64),"cash_edit","plan-1"),Triple("a".repeat(64),"import","other-plan"))) {
            val failure=assertFailsWith<ReceiptConflict> {repo.replay("fixture-user","request-1",digest,operation,plan)}
            assertEquals("idempotency_conflict",failure.code);assertEquals(original["receipt_id"]?.jsonPrimitive?.content,failure.receiptId)
        }
    }}
    @Test fun sessionIsConsumedOnceWhileDifferentContentContextsRemainIndependent() = inWorkspace {db,workspace ->db.write {c ->
        val repo=repo(c,db,workspace);val original=save(repo)
        assertEquals(original,repo.forContext("fixture-user",sessionDigest="b".repeat(64)))
        val failure=assertFailsWith<ReceiptConflict> {save(repo,mapOf("key" to "request-2"))}
        assertEquals("session_committed",failure.code);assertEquals(original["receipt_id"]?.jsonPrimitive?.content,failure.receiptId)
        assertNotEquals(original["receipt_id"],save(repo,mapOf("key" to "request-2","session" to "c".repeat(64)))["receipt_id"])
    }}
    @Test fun editContextsAreConsumedAndAccountReceiptsNeedNoContext() = inWorkspace {db,workspace ->db.write {c ->
        val repo=repo(c,db,workspace);val changes=mapOf("operation" to "cash_edit","session" to null,"edit" to "d".repeat(64));val first=save(repo,changes)
        assertEquals(first,repo.forContext("fixture-user",editContextDigest="d".repeat(64)))
        assertEquals("edit_context_committed",assertFailsWith<ReceiptConflict> {save(repo,changes+mapOf("key" to "other-edit"))}.code)
        repeat(2) {save(repo,mapOf("key" to "account-$it","operation" to "account_update","plan" to null,"session" to null))}
    }}
    @Test fun keysAndContextsAreScopedToUserAndWorkspace() = inWorkspace {db,workspace ->
        val other="other-${java.util.UUID.randomUUID()}"
        db.write {c ->
            c.prepareStatement("INSERT INTO workspaces(id,name,created_at) VALUES(?,'Other',?)").use {it.setString(1,other);it.instant(2,Instant.now(),db.sqlite);it.executeUpdate()}
            val repo=repo(c,db,workspace);val original=save(repo);val user=save(repo,mapOf("user" to "different-user"));val foreign=save(repo(c,db,other))
            assertEquals(3,setOf(original["receipt_id"],user["receipt_id"],foreign["receipt_id"]).size)
            val id=original.getValue("receipt_id").jsonPrimitive.content
            assertNull(repo.get("unknown-user",id));assertNull(repo(c,db,other).get("fixture-user",id));assertNull(repo.results("different-user",id))
        }
        db.write {c ->c.prepareStatement("DELETE FROM workspaces WHERE id=?").use {it.setString(1,other);assertEquals(1,it.executeUpdate())}}
    }
    @Test fun invalidAndSensitiveResultsAreRejectedBeforeWritingAnyReceipt() = inWorkspace {db,workspace ->db.write {c ->
        val repo=repo(c,db,workspace)
        val duplicate=mapOf("result_kind" to "row","item_ref" to "r1","outcome" to "duplicate")
        val vectors=listOf(mapOf("summary" to mapOf("source_payload" to mapOf("secret" to "must not persist"))),
            mapOf("results" to listOf(mapOf("result_kind" to "row","item_ref" to "r1","outcome" to "failed","message" to "SQL details must not persist"))),
            mapOf("results" to listOf(mapOf("result_kind" to "row","item_ref" to "r1","outcome" to "failed","code" to "SQL: private details"))),
            mapOf("results" to listOf(duplicate,duplicate)),mapOf("session" to ""),mapOf("edit" to "c".repeat(64)),mapOf("summary" to mapOf("status" to emptyList<String>())),
            mapOf("results" to listOf(mapOf("result_kind" to emptyList<String>(),"item_ref" to "r1","outcome" to "failed"))))
        for(vector in vectors) {
            assertTrue(assertFailsWith<IllegalArgumentException> {save(repo,vector)}.message!!.matches(Regex("mutation_.*invalid")))
            assertNull(repo.byKey("fixture-user","request-1"))
        }
    }}
    @Test fun accountResultsRequireMatchingWhitelistedAccountFields() = inWorkspace {db,workspace ->db.write {c ->
        val repo=repo(c,db,workspace)
        val account=mapOf("account_id" to "101","account_revision" to 2,"name" to "Ordinary account","type" to "cash","active" to true,"currencies" to listOf("CNY"),"deleted" to false)
        for(change in listOf(mapOf("source_payload" to mapOf("private" to "must not persist")),mapOf("account_revision" to 0),mapOf("account_id" to "different-account"),mapOf("currencies" to listOf("CNY","CNY")))) {
            val failure=assertFailsWith<IllegalArgumentException> {save(repo,mapOf("operation" to "account_update","session" to null,"plan" to null,"summary" to mapOf("status" to "completed","account_id" to "101","account_revision" to 2,"account" to account+change)))}
            assertEquals("mutation_summary_invalid",failure.message);assertNull(repo.byKey("fixture-user","request-1"))
        }
    }}
    @Test fun projectionReferencesAndCommitOutcomePersistWithoutInventingLegacyFields() = inWorkspace {db,workspace ->
        val refs=listOf(mapOf("transaction_id" to "1001","projection_id" to "p_73"))
        val outcome=mapOf("has_new_failures" to true,"has_business_writes" to false,"reason_codes" to listOf("row_save_failed"))
        val saved=db.write {c ->val repo=repo(c,db,workspace)
            val old=save(repo,mapOf("summary" to mapOf("status" to "completed","projection_refs" to refs)))
            assertFalse("commit_outcome" in old);assertEquals(Json.parseToJsonElement("""[{"transaction_id":"1001","projection_id":"p_73"}]"""),old["projection_refs"])
            assertEquals(old["projection_refs"],repo.replay("fixture-user","request-1","a".repeat(64),"import","plan-1")!!["projection_refs"])
            save(repo,mapOf("key" to "new-outcome","session" to "e".repeat(64),"summary" to mapOf("status" to "partial","commit_outcome" to outcome)))
        }
        val expected=Json.parseToJsonElement("""{"has_new_failures":true,"has_business_writes":false,"reason_codes":["row_save_failed"]}""")
        assertEquals(expected,saved["commit_outcome"])
        db.read {c ->assertEquals(expected,repo(c,db,workspace).get("fixture-user",saved.getValue("receipt_id").jsonPrimitive.content)!!["commit_outcome"])}
    }
    @Test fun invalidOrSensitiveCommitOutcomeIsRejected() = inWorkspace {db,workspace ->db.write {c ->
        val valid=mapOf("has_new_failures" to true,"has_business_writes" to false,"reason_codes" to listOf("row_save_failed"))
        val vectors=listOf(valid+mapOf("has_new_failures" to "false","reason_codes" to emptyList<String>()),valid+mapOf("has_new_failures" to false),valid+mapOf("reason_codes" to emptyList<String>()),
            valid+mapOf("reason_codes" to listOf("private SQL /path")),valid+mapOf("reason_codes" to List(40) {"row_save_failed"}),valid+mapOf("has_new_failures" to false,"reason_codes" to emptyList<String>(),"payload" to "private"))
        for(outcome in vectors) assertEquals("mutation_summary_invalid",assertFailsWith<IllegalArgumentException> {save(repo(c,db,workspace),mapOf("summary" to mapOf("commit_outcome" to outcome)))}.message)
    }}
    @Test fun legacyCountsRejectArbitraryJsonAndMalformedUnicodeNames() = inWorkspace {db,workspace ->db.write {c ->
        assertEquals("mutation_summary_invalid",assertFailsWith<IllegalArgumentException> {save(repo(c,db,workspace),mapOf("key" to "synthetic-bad-legacy-result","operation" to "legacy_import","session" to null,"plan" to null,"summary" to mapOf("legacy_by_account" to mapOf("name" to mapOf("private" to "secret"))),"results" to emptyList<Any>()))}.message)
        assertEquals("mutation_summary_invalid",assertFailsWith<IllegalArgumentException> {normalizeReceiptSummary(mapOf("legacy_by_account" to mapOf("\uD800" to 1)))}.message)
    }}    @Test fun summaryBudgetUsesTheOriginalAsciiEscapingAtDelAndExactLimit() {
        val empty=normalizeReceiptSummary(mapOf("legacy_by_account" to mapOf("" to 1)))
        val overhead=receiptAsciiJson(empty).length
        assertEquals(30,overhead)
        assertEquals(1024*1024,receiptAsciiJson(normalizeReceiptSummary(mapOf("legacy_by_account" to mapOf("x".repeat(1024*1024-overhead) to 1)))).length)
        for(key in listOf("x".repeat(1024*1024-overhead+1),"\u007F".repeat(174763)))
            assertEquals("mutation_summary_invalid",assertFailsWith<IllegalArgumentException> {normalizeReceiptSummary(mapOf("legacy_by_account" to mapOf(key to 1)))}.message)
        assertEquals("\"\\u007f\"",receiptAsciiJson(JsonPrimitive("\u007F")))
    }
    @Test fun normalizationAndPersistenceDetachMutableCallerInput() = inWorkspace {db,workspace ->
        val counters=mutableMapOf("created_posted" to 1)
        val input=mutableMapOf<String,Any?>("summary" to counters)
        val normalized=normalizeReceiptSummary(input)
        counters["created_posted"]=9
        assertEquals(JsonPrimitive(1),normalized.getValue("summary").jsonObject["created_posted"])
        val item=mutableMapOf<String,Any?>("result_kind" to "row","item_ref" to "r1","outcome" to "created_posted","transaction_id" to "1001")
        val items=mutableListOf(item)
        val normalizedItems=normalizeReceiptResults(items)
        val saved=db.write {c ->save(repo(c,db,workspace),mapOf("summary" to normalized,"results" to items))}
        item["transaction_id"]="9999";items.clear();input.clear()
        assertEquals(JsonPrimitive("1001"),normalizedItems.single()["transaction_id"])
        db.read {c ->
            val repo=repo(c,db,workspace)
            assertEquals(normalized["summary"],repo.get("fixture-user",saved.getValue("receipt_id").jsonPrimitive.content)!!["summary"])
            assertEquals(JsonPrimitive("1001"),repo.results("fixture-user",saved.getValue("receipt_id").jsonPrimitive.content)!!.getValue("items").jsonArray.single().jsonObject["transaction_id"])
        }
    }
    private class VerifiedIntegrity: RuntimeException()
    @Test fun databaseFencesSessionConsumptionEvenWhenRepositoryIsBypassed() = inWorkspace {db,workspace ->
        db.write {c ->save(repo(c,db,workspace))}
        assertFailsWith<VerifiedIntegrity> {db.write {c ->
            val failure=assertFailsWith<java.sql.SQLException> {c.prepareStatement("INSERT INTO cash_mutation_receipts(id,workspace_id,user_id,idempotency_key,request_digest,operation_kind,plan_id,session_digest,result_summary,created_at) VALUES(?,?,'fixture-user','different-key',?,'import','p2',?,'{}',?)").use {
                it.setString(1,java.util.UUID.randomUUID().toString());it.setString(2,workspace);it.setString(3,"c".repeat(64));it.setString(4,"b".repeat(64));it.instant(5,Instant.now(),db.sqlite);it.executeUpdate()
            }}
            if(db.sqlite) assertEquals(19,failure.errorCode and 255) else assertEquals("23505",failure.sqlState)
            throw VerifiedIntegrity()
        }}
        db.read {c ->c.prepareStatement("SELECT count(*) FROM cash_mutation_receipts WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {rows ->assertTrue(rows.next());assertEquals(1,rows.getInt(1))}}}
    }
    @Test fun databaseRejectsResultsAttachedToAnotherWorkspaceReceipt() = inWorkspace {db,workspace ->
        val receipt=db.write {c ->save(repo(c,db,workspace))}.getValue("receipt_id").jsonPrimitive.content
        assertFailsWith<VerifiedIntegrity> {db.write {c ->
            val failure=assertFailsWith<java.sql.SQLException> {c.prepareStatement("INSERT INTO cash_mutation_results(workspace_id,receipt_id,result_kind,item_ref,outcome) VALUES('different-workspace',?,'row','wrong-owner','duplicate')").use {it.setString(1,receipt);it.executeUpdate()}}
            if(db.sqlite) assertEquals(19,failure.errorCode and 255) else assertEquals("23503",failure.sqlState)
            throw VerifiedIntegrity()
        }}
    }
    @Test fun sqliteUnfilteredPagingUsesItsBoundedIndexWithoutSortingEntireReceipt() = inWorkspace {db,workspace ->
        if(db.sqlite) db.write {c ->
            val receipt=save(repo(c,db,workspace)).getValue("receipt_id").jsonPrimitive.content
            c.prepareStatement("EXPLAIN QUERY PLAN SELECT id FROM cash_mutation_results WHERE workspace_id=? AND receipt_id=? AND id>0 ORDER BY id LIMIT 51").use {
                it.setString(1,workspace);it.setString(2,receipt);it.executeQuery().use {rows ->while(rows.next()) assertFalse("TEMP B-TREE" in rows.getString(4))}
            }
        }
    }

}
