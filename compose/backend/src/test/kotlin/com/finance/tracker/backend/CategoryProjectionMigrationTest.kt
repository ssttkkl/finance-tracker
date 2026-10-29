package com.finance.tracker.backend

import java.nio.file.Path
import java.sql.Connection
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CategoryProjectionMigrationTest {
    private fun inWorkspace(action:(Database,String)->Unit) {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val db=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val workspaces=WorkspaceService(db);val name="分类移动投影测试";val workspace=workspaces.create("synthetic-admin",name).activeWorkspaceId!!
        try {action(db,workspace)} finally {workspaces.delete("synthetic-admin",name);workspaces.select("synthetic-admin","fixture-a")}
    }
    private val tables=listOf("cash_categories","cash_category_states","cash_transactions","cash_entries","cash_entry_members","cash_projection_states","cash_projection_datasets","cash_projections","cash_projection_members","cash_projection_relations")
    private fun snapshot(db:Database,workspace:String)=db.read {c ->tables.associateWith {table ->c.prepareStatement("SELECT * FROM $table WHERE workspace_id=?").use {
        it.setString(1,workspace);it.executeQuery().use {r ->val columns=(1..r.metaData.columnCount).associateWith {r.metaData.getColumnName(it)}
            buildSet {while(r.next()) add(JsonObject(columns.entries.associate {(index,key) ->key to (r.getString(index)?.let(::JsonPrimitive) ?: JsonNull)}))}
        }
    }}}
    @Test fun standaloneMoveMaintainsSourceAndManualEntryCategoriesWithoutRewritingSourcesOrUnrelatedRows()=inWorkspace {db,workspace ->
        val service=CategoryService(db);val parent=service.create("synthetic-admin","工作");val category=service.create("synthetic-admin","餐饮");val child=service.create("synthetic-admin","午餐",category.id)
        val account=deletionAccount(db,workspace)
        val ids=db.write {c ->listOf(deletionFact(c,db,workspace,account,child.id),deletionFact(c,db,workspace,account,null),deletionFact(c,db,workspace,account,null))}
        CashProjectionService(db,workspace).rebuild()
        db.write {c ->val context=CashSqlContext(c,workspace,db.sqlite);val entry=context.entries.current(ids[1])!!;context.entries.patch(ids[1],mapOf("category_id" to child.id),entry.revision);CashProjectionService.maintainInSession(context,setOf(ids[1]))}
        val before=snapshot(db,workspace);val previous=CashProjectionService(db,workspace).status()
        service.move("synthetic-admin",category.id,parent.id,child.revision)
        val after=snapshot(db,workspace);val status=CashProjectionService(db,workspace).status()
        assertEquals(previous.getValue("projection_version").jsonPrimitive.long+1,status.getValue("projection_version").jsonPrimitive.long)
        assertEquals(before["cash_transactions"],after["cash_transactions"])
        assertEquals(before["cash_entry_members"],after["cash_entry_members"])
        val projections=after.getValue("cash_projections").associateBy {it.getValue("root_cash_transaction_id").jsonPrimitive.long}
        for(id in ids.take(2)) assertEquals(JsonPrimitive("/${parent.id}//${category.id}//${child.id}/"),projections.getValue(id).getValue("category_path"))
        assertEquals(before.getValue("cash_projections").single {it.getValue("root_cash_transaction_id").jsonPrimitive.long==ids[2]},projections.getValue(ids[2]))
        val entries=after.getValue("cash_entries").associateBy {it.getValue("root_transaction_id").jsonPrimitive.long}
        assertEquals(JsonPrimitive(child.id),entries.getValue(ids[1]).getValue("category_id"));assertEquals(Json.parseToJsonElement("[\"category_id\"]"),Json.parseToJsonElement(entries.getValue(ids[1]).getValue("manual_fields").jsonPrimitive.content))
    }
    @Test fun unusedMoveDoesNotAdvanceProjectionAndUpdateKeepsTheOriginalPythonBoundary()=inWorkspace {db,workspace ->
        val service=CategoryService(db);val parent=service.create("synthetic-admin","工作");val category=service.create("synthetic-admin","餐饮")
        val before=CashProjectionService(db,workspace).status();val moved=service.move("synthetic-admin",category.id,parent.id,category.revision)
        assertEquals(before,CashProjectionService(db,workspace).status())
        val account=deletionAccount(db,workspace);db.write {deletionFact(it,db,workspace,account,category.id)};CashProjectionService(db,workspace).rebuild()
        val old=snapshot(db,workspace);service.update("synthetic-admin",category.id,name="外食",parentId=null,changeParent=true,expectedRevision=moved.revision)
        val current=snapshot(db,workspace)
        for(table in tables.filter {it !in setOf("cash_categories","cash_category_states")}) assertEquals(old[table],current[table],table)
    }
    @Test fun notReadyMoveReconcilesStableEntriesWithoutPublishingADataset()=inWorkspace {db,workspace ->
        val service=CategoryService(db);val parent=service.create("synthetic-admin","工作");val category=service.create("synthetic-admin","餐饮")
        val account=deletionAccount(db,workspace);val id=db.write {c ->
            c.prepareStatement("DELETE FROM cash_projection_states WHERE workspace_id=?").use {it.setString(1,workspace);it.executeUpdate()}
            c.prepareStatement("DELETE FROM cash_projection_datasets WHERE workspace_id=?").use {it.setString(1,workspace);it.executeUpdate()}
            deletionFact(c,db,workspace,account,category.id)
        }
        service.move("synthetic-admin",category.id,parent.id,category.revision)
        val after=snapshot(db,workspace);assertTrue(after.getValue("cash_projection_states").isEmpty());assertTrue(after.getValue("cash_projection_datasets").isEmpty());assertTrue(after.getValue("cash_projections").isEmpty())
        assertEquals(JsonPrimitive(category.id),after.getValue("cash_entries").single {it.getValue("root_transaction_id").jsonPrimitive.long==id}.getValue("category_id"))
    }
    @Test fun aDeletedSourceWithoutAStableEntryDoesNotAdvanceProjectionState()=inWorkspace {db,workspace ->
        val service=CategoryService(db);val parent=service.create("synthetic-admin","工作");val category=service.create("synthetic-admin","餐饮")
        val account=deletionAccount(db,workspace);db.write {deletionFact(it,db,workspace,account,category.id,true)}
        val before=snapshot(db,workspace);service.move("synthetic-admin",category.id,parent.id,category.revision);val after=snapshot(db,workspace)
        for(table in tables.filter {it !in setOf("cash_categories","cash_category_states")}) assertEquals(before[table],after[table],table)
    }
    @Test fun lateIncrementalOverflowRollsBackTreePathsDepthsAndAllFinancialRows()=inWorkspace {db,workspace ->
        val service=CategoryService(db);val parent=service.create("synthetic-admin","工作");val category=service.create("synthetic-admin","餐饮");val child=service.create("synthetic-admin","午餐",category.id)
        val account=deletionAccount(db,workspace);db.write {deletionFact(it,db,workspace,account,child.id)};CashProjectionService(db,workspace).rebuild()
        db.write {c ->c.prepareStatement("UPDATE cash_projection_states SET source_revision=9223372036854775807 WHERE workspace_id=?").use {it.setString(1,workspace);it.executeUpdate()}}
        val before=snapshot(db,workspace)
        assertFailsWith<ArithmeticException> {service.move("synthetic-admin",category.id,parent.id,child.revision)}
        assertEquals(before,snapshot(db,workspace))
    }
}
