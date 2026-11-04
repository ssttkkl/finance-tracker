package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*
import kotlinx.serialization.json.*

class LedgerReadContractTest {
    @Test fun versionHttpReturnsOnlyExactSelectedWorkspaceVersionWithoutWriting() = withLedgerTestWorkspace { db, workspace, token -> testApplication {
        db.write { c -> c.jooq(db.sqlite).update(WORKSPACES).set(WORKSPACES.LEDGER_SEQ, Long.MAX_VALUE)
            .where(WORKSPACES.ID.eq(workspace)).execute() }
        val before=detailDatabaseState(db)
        application { financeModule(db) }
        val response=client.get("/api/v1/ledger/version") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK,response.status,response.bodyAsText())
        assertEquals(buildJsonObject { put("ledger_seq",Long.MAX_VALUE.toString()) },Json.parseToJsonElement(response.bodyAsText()))
        assertEquals(before,detailDatabaseState(db))
        assertEquals(HttpStatusCode.Unauthorized,client.get("/api/v1/ledger/version").status)
    } }
    @Test fun viewerMayReadButUnselectedWorkspaceIsForbidden() = withLedgerTestWorkspace { db,workspace,token -> testApplication {
        setAccountTestRole(db,workspace,"viewer")
        application { financeModule(db) }
        assertEquals(HttpStatusCode.OK,client.get("/api/v1/ledger/version") { bearerAuth(token) }.status)
        db.write { c -> c.jooq(db.sqlite).update(USER_SESSIONS).setNull(USER_SESSIONS.ACTIVE_WORKSPACE_ID)
            .where(USER_SESSIONS.TOKEN_DIGEST.eq(tokenDigest(token))).execute() }
        try { assertEquals(HttpStatusCode.Forbidden,client.get("/api/v1/ledger/version") { bearerAuth(token) }.status) }
        finally { WorkspaceService(db).select(token,workspace) }
    } }
    @Test fun changedSelectionBeforeResponseAndForeignRequestContextAreRejected() = withLedgerTestWorkspace { db,workspace,token ->
        assertFailsWith<AccessFailure> { LedgerReadService(db).version(token,Principal("admin","", "fixture-b")) }
        val service=LedgerReadService(db,afterRead={ db.write { c -> c.jooq(db.sqlite).update(USER_SESSIONS)
            .setNull(USER_SESSIONS.ACTIVE_WORKSPACE_ID).where(USER_SESSIONS.TOKEN_DIGEST.eq(tokenDigest(token))).execute() } })
        try { assertTrue(assertFailsWith<AccessFailure> { service.version(token) }.forbidden) }
        finally { WorkspaceService(db).select(token,workspace) }
    }

}
