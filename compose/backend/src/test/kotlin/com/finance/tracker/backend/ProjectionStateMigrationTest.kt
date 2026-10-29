package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class ProjectionStateMigrationTest {
    private fun inWorkspace(action: (Database,String)->Unit) {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        val database=Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val workspaces=WorkspaceService(database);val workspace=workspaces.create("synthetic-admin","投影状态测试").activeWorkspaceId!!
        try {action(database,workspace)} finally {setAccountTestRole(database,workspace,"admin");workspaces.delete("synthetic-admin","投影状态测试");workspaces.select("synthetic-admin","fixture-a")}
    }
    @Test fun statusWithoutStateIsUninitializedAndNeverCreatesState() = inWorkspace {db,workspace ->
        db.write {c ->c.prepareStatement("DELETE FROM cash_projection_states WHERE workspace_id=?").use {it.setString(1,workspace);assertEquals(1,it.executeUpdate())}}
        val status=ProjectionStatusService(db).status("synthetic-admin")
        assertEquals(JsonPrimitive("uninitialized"),status["availability"]);assertEquals(JsonPrimitive("never"),status["last_build_status"])
        assertEquals(Json.parseToJsonElement("""{"availability":"uninitialized","projection_version":0,"source_revision":0,"rules_version":"cash-projection-v2","last_build_status":"never","active_dataset_id":null,"projection_count":0,"member_count":0,"last_error_code":null,"last_error_summary":null}"""),status)
        db.read {c ->c.prepareStatement("SELECT count(*) FROM cash_projection_states WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {rows ->assertTrue(rows.next());assertEquals(0,rows.getInt(1))}}}
    }
    @Test fun statusReturnsEveryStoredFieldIncludingFailedBuildWithAnActiveDataset() = inWorkspace {db,workspace ->testApplication {
        application {financeModule(db)}
        val dataset=db.read {c ->c.prepareStatement("SELECT active_dataset_id FROM cash_projection_states WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {rows ->check(rows.next());rows.getString(1)}}}
        db.write {c ->c.prepareStatement("UPDATE cash_projection_states SET projection_version=9223372036854775807,source_revision=8,rules_version='old-rules',last_build_status='failed',last_build_id='safe-build',last_error_code='projection.data_invalid',last_error_summary='收支投影构建失败',projection_count=3,member_count=4 WHERE workspace_id=?").use {it.setString(1,workspace);assertEquals(1,it.executeUpdate())}}
        val before=snapshot(db,workspace)
        val response=client.get("/api/v1/operations/projections") {bearerAuth("synthetic-admin")}
        assertEquals(200,response.status.value,response.bodyAsText())
        assertEquals(buildJsonObject {
            put("availability","ready");put("projection_version",Long.MAX_VALUE);put("source_revision",8);put("rules_version","old-rules")
            put("last_build_status","failed");put("active_dataset_id",dataset);put("projection_count",3);put("member_count",4)
            put("last_error_code","projection.data_invalid");put("last_error_summary","收支投影构建失败");put("last_build_id","safe-build")
        },Json.parseToJsonElement(response.bodyAsText()))
        assertEquals(before,snapshot(db,workspace))
        assertEquals(JsonPrimitive(0),ProjectionStatusService(db).status("synthetic-new-directory")["projection_count"])
    }}
    @Test fun onlyCurrentWorkspaceAdministratorsCanReadStatus() = inWorkspace {db,workspace ->testApplication {
        application {financeModule(db)}
        for(role in listOf("editor","viewer","admin")) {
            setAccountTestRole(db,workspace,role)
            val response=client.get("/api/v1/operations/projections") {bearerAuth("synthetic-admin")}
            assertEquals(if(role=="admin") 200 else 403,response.status.value)
            if(role!="admin") assertEquals(Json.parseToJsonElement("""{"error":{"code":"workspace_forbidden","message":"只有管理员可以执行该操作。"}}"""),Json.parseToJsonElement(response.bodyAsText()))
        }
        for(token in listOf(null,"synthetic-expired","synthetic-removed")) {
            val response=client.get("/api/v1/operations/projections") {token?.let {bearerAuth(it)}}
            assertEquals(if(token=="synthetic-removed") 403 else 401,response.status.value)
        }
    }}
    @Test fun staleMiddlewarePermissionAndStorageFailureDoNotExposeStatus() = inWorkspace {db,workspace ->
        testApplication {
            val service=ProjectionStatusService(db,beforeRead={setAccountTestRole(db,workspace,"viewer")})
            application {financeModule(db,projectionStatus=service)}
            assertEquals(403,client.get("/api/v1/operations/projections") {bearerAuth("synthetic-admin")}.status.value)
        }
        setAccountTestRole(db,workspace,"admin")
        testApplication {
            val unavailable=ProjectionStatusService(Database("jdbc:sqlite:/Users/huangwenlong/Library/Caches/finance-kotlin-backend/nonexistent-projection-status_test.db"))
            application {financeModule(db,projectionStatus=unavailable)}
            val response=client.get("/api/v1/operations/projections") {bearerAuth("synthetic-admin")}
            assertEquals(503,response.status.value)
            assertEquals(Json.parseToJsonElement("""{"error":{"code":"projection.unavailable","message":"账本投影状态暂不可用。"}}"""),Json.parseToJsonElement(response.bodyAsText()))
        }
    }
    private fun snapshot(db: Database,workspace: String)=db.read {c ->c.prepareStatement("SELECT * FROM cash_projection_states WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {rows ->
        check(rows.next());(1..rows.metaData.columnCount).map {index ->rows.getString(index)}
    }}}
}
