package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.domain.encodeLocalCashValue
import com.finance.tracker.business.domain.prepareLocalCashImportOperations
import com.finance.tracker.business.domain.prepareLocalCashImportSources
import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

class CashSourceRevisionContractTest {
    @Test fun sourceCandidatesAndOperationsKeep64BitRevisionsFromBothAuthoritativeReadPaths()=withLedgerTestWorkspace { db,workspace,token ->
        val time=Instant.parse("2026-10-01T00:00:00Z")
        val account=AccountService(db).create(token,buildJsonObject {
            put("name","Revision account");put("type","cash");put("currencies",JsonArray(listOf(JsonPrimitive("CNY"))))
        },"synthetic-revision-account").getValue("account_id").jsonPrimitive.long
        val fields=mapOf<String,Any?>("amount" to "-20","currency" to "CNY","occurred_at" to time.toString(),
            "record_type" to "consumption","record_subtype" to "not_applicable")
        val document=ParsedCashDocument("ccb_debit","table","v1",listOf(ParsedCashRow(1,fields,providerId="synthetic-id",sourceExplanation=mapOf("source_display_name" to "Synthetic bank"))),
            "6222",null,CashSourceCoverage(null,null,false,"",emptyMap(),"v1"),1L,1)
        val files=listOf(ParsedImportFile("f","f".repeat(64),"parsed",document))
        val occurrence=CashSourceRows.sourceBatchRows(files).rows.values.single()
        val id=db.write {c ->
            check(c.lockWorkspace(db.sqlite,workspace))
            val context=CashSqlContext(c,workspace,db.sqlite)
            val id=context.insertAtomicCash(account,time,CashAmount.parse("-20"),"CNY","consumption","not_applicable","import",sourceType="ccb_debit").first
            CashSourceWriteRepository(context).saveOccurrence(id,occurrence)
            id
        }
        val selections=CashImportFunding.scan(files).groups.map {group ->mapOf<String,Any?>(
            "source_account_ref" to group.sourceAccountRef,"account_id" to account.toString(),"expected_account_revision" to 1L)}
        for(revision in listOf(2147483647L,2147483648L,9007199254740993L,Long.MAX_VALUE)) {
            db.write {c ->
                check(c.lockWorkspace(db.sqlite,workspace));c.markLedgerChanged(workspace)
                c.jooq(db.sqlite).update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.ROW_REVISION,revision)
                .where(CASH_TRANSACTIONS.ID.eq(id)).and(CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace)).execute()}
            val before=detailDatabaseState(db)
            val snapshot=LedgerReadService(db).snapshot(token)
            val native=db.read {c ->
                val context=CashSqlContext(c,workspace,db.sqlite)
                assertEquals(revision.toString(),CashSourceReadRepository(context).recordsForFile(listOf(occurrence)).single().rowRevision.toString())
                CashImportOperationPlanner(context).prepare(files,selections)
            }
            val localSources=prepareLocalCashImportSources(files,snapshot)
            assertEquals(revision.toString(),localSources.records.single().rowRevision.toString())
            val local=prepareLocalCashImportOperations(files,snapshot,sourceJson(selections))
            assertTrue(native.operations.isNotEmpty())
            val nativeWire=sourceJson(native.operations)
            val localWire=encodeLocalCashValue(local.operations)
            assertEquals(nativeWire,localWire)
            assertEquals(revision.toString(),nativeWire.jsonArray.single().jsonObject.getValue("expected_revision").jsonPrimitive.content)
            assertEquals(before,detailDatabaseState(db))
        }
    }
}
