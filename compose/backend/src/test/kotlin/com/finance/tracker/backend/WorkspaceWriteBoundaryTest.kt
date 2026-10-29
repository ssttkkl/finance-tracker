package com.finance.tracker.backend

import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class WorkspaceWriteBoundaryTest {
    private fun database(): Database {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_WORKSPACE_CONTRACT_DIR")))
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }

    @Test fun deletingAWorkspaceCannotSelectAnotherConcurrentDeletionAsItsReplacement() {
        val database = database()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            database.write { connection ->
                connection.createStatement().use {
                    it.executeUpdate("INSERT INTO users(id,email,password_hash,created_at) VALUES('delete-race-user','delete-race@example.invalid','unused','2026-01-01 00:00:00')")
                    for (name in listOf("a", "b")) {
                        it.executeUpdate("INSERT INTO workspaces(id,name,created_at) VALUES('delete-race-$name','$name','2026-01-01 00:00:00')")
                        it.executeUpdate("INSERT INTO workspace_memberships(workspace_id,user_id,role,created_at) VALUES('delete-race-$name','delete-race-user','admin','2026-01-01 00:00:00')")
                        it.executeUpdate("""INSERT INTO user_sessions(id,user_id,token_digest,active_workspace_id,expires_at,created_at)
                            VALUES('delete-race-$name','delete-race-user','${tokenDigest("delete-race-$name")}','delete-race-$name','2099-01-01 00:00:00','2026-01-01 00:00:00')""")
                    }
                }
            }
            val deletingB = executor.submit { database.write { connection ->
                connection.createStatement().use {
                    if (!database.sqlite) it.executeQuery("SELECT id FROM workspaces WHERE id='delete-race-b' FOR UPDATE").close()
                    it.executeUpdate("UPDATE user_sessions SET active_workspace_id=NULL WHERE active_workspace_id='delete-race-b'")
                    it.executeUpdate("DELETE FROM workspaces WHERE id='delete-race-b'")
                }
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
            } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            if (database.sqlite) {
                // SQLite serializes writers, so B commits before the deletion of A can enter.
                release.countDown(); deletingB.get(5, TimeUnit.SECONDS)
                assertNull(WorkspaceService(database).delete("delete-race-a", "a").activeWorkspaceId)
            } else {
                val failure = assertFailsWith<StorageFailure> { WorkspaceService(database).delete("delete-race-a", "a") }
                assertEquals("storage.busy", failure.code)
                release.countDown(); deletingB.get(5, TimeUnit.SECONDS)
                assertEquals("delete-race-a", ReadServices(database).state("delete-race-a").activeWorkspaceId)
            }
            database.read { connection -> connection.createStatement().use {
                it.executeQuery("""SELECT COUNT(*) FROM user_sessions s LEFT JOIN workspaces w ON w.id=s.active_workspace_id
                    WHERE s.user_id='delete-race-user' AND s.active_workspace_id IS NOT NULL AND w.id IS NULL""").use {
                    it.next(); assertEquals(0, it.getInt(1))
                }
            } }
        } finally {
            release.countDown(); executor.shutdown(); executor.awaitTermination(15, TimeUnit.SECONDS)
            database.write { connection -> connection.createStatement().use {
                it.executeUpdate("DELETE FROM users WHERE id='delete-race-user'")
                it.executeUpdate("DELETE FROM workspaces WHERE id IN ('delete-race-a','delete-race-b')")
            } }
        }
    }

    @Test fun administratorChangesAndInvitationConsumptionRemainAtomicUnderContention() {
        val database = database()
        val clock = Instant.parse("2026-10-28T14:00:00Z")
        var tokenNumber = 0
        val service = WorkspaceService(database, now = { clock }, newWorkspaceId = { "membership-race" },
            newInvitationToken = { "membership-invite-${tokenNumber++}" })
        val executor = Executors.newFixedThreadPool(2)
        try {
            service.create("synthetic-admin", "race")
            database.write { connection -> connection.prepareStatement("INSERT INTO workspace_memberships(workspace_id,user_id,role,created_at) VALUES('membership-race','viewer','admin',?)").use {
                it.instant(1, clock, database.sqlite); it.executeUpdate()
            } }
            service.select("synthetic-viewer", "membership-race")
            val start = CountDownLatch(1)
            val changes = listOf("admin", "viewer").map { user -> executor.submit<String> {
                start.await()
                try { service.updateMember("synthetic-$user", user, "editor"); "changed" }
                catch (failure: WorkspaceFailure) { failure.code }
            } }
            start.countDown()
            assertEquals(listOf("changed", "last_admin"), changes.map { it.get(10, TimeUnit.SECONDS) }.sorted())
            val owner = service.members("synthetic-admin").members.single { it.role == com.finance.tracker.business.contracts.Role.ADMIN }.userId
            val ownerToken = "synthetic-$owner"
            val invite = service.invite(ownerToken, "viewer")
            val acceptStart = CountDownLatch(1)
            val accepts = listOf("empty", "removed").map { user -> executor.submit<String> {
                acceptStart.await()
                try { service.accept("synthetic-$user", invite.token); "accepted" }
                catch (failure: WorkspaceFailure) { failure.code }
            } }
            acceptStart.countDown()
            assertEquals(listOf("accepted", "invalid_invitation"), accepts.map { it.get(10, TimeUnit.SECONDS) }.sorted())
            val oldRole = service.members(ownerToken).members.single { it.userId == owner }.role
            service.accept(ownerToken, service.invite(ownerToken, "viewer").token)
            assertEquals(oldRole, service.members(ownerToken).members.single { it.userId == owner }.role)
            val expired = service.invite(ownerToken, "editor")
            database.write { connection -> connection.prepareStatement("UPDATE workspace_invitations SET expires_at=? WHERE token_digest=?").use {
                it.instant(1, clock, database.sqlite); it.setString(2, tokenDigest(expired.token)); it.executeUpdate()
            } }
            assertEquals("invalid_invitation", assertFailsWith<WorkspaceFailure> { service.preview(expired.token) }.code)
            assertEquals("invalid_invitation", assertFailsWith<WorkspaceFailure> { service.accept("synthetic-empty", expired.token) }.code)
        } finally {
            executor.shutdownNow(); executor.awaitTermination(15, TimeUnit.SECONDS)
            cleanupWorkspace(database, "membership-race")
        }
    }

    @Test fun initializationAndLateDeletionFailuresRollBackWorkspacePointersAndDatasets() {
        val database = database()
        val service = WorkspaceService(database, newWorkspaceId = { "failure-workspace" })
        try {
            installFailure(database, "cash_projection_states", "INSERT", "NEW.workspace_id")
            assertFailsWith<StorageFailure> { service.create("synthetic-admin", "failure") }
            assertEquals("fixture-a", ReadServices(database).state("synthetic-admin").activeWorkspaceId)
            assertEquals(0, workspaceCount(database, "workspaces", "id"))
            assertEquals(0, workspaceCount(database, "cash_projection_datasets", "workspace_id"))
            removeFailure(database)
            service.create("synthetic-admin", "failure")
            installFailure(database, "workspaces", "DELETE", "OLD.id")
            assertFailsWith<StorageFailure> { service.delete("synthetic-admin", "failure") }
            assertEquals("failure-workspace", ReadServices(database).state("synthetic-admin").activeWorkspaceId)
            assertEquals(1, workspaceCount(database, "workspaces", "id"))
            assertEquals(1, workspaceCount(database, "cash_projection_datasets", "workspace_id"))
            assertEquals(1, workspaceCount(database, "cash_projection_states", "workspace_id"))
        } finally { removeFailure(database); cleanupWorkspace(database, "failure-workspace") }
    }

    private fun workspaceCount(database: Database, table: String, field: String): Int = database.read { connection ->
        connection.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM $table WHERE $field='failure-workspace'").use { it.next(); it.getInt(1) } }
    }

    private fun installFailure(database: Database, table: String, operation: String, field: String) = database.write { connection ->
        connection.createStatement().use {
            if (database.sqlite) it.execute("""CREATE TRIGGER kotlin_workspace_failure BEFORE $operation ON $table
                WHEN $field='failure-workspace' BEGIN SELECT RAISE(ABORT,'injected failure'); END""")
            else {
                it.execute("""CREATE FUNCTION kotlin_workspace_failure_fn() RETURNS trigger LANGUAGE plpgsql AS
                    'BEGIN IF $field = ''failure-workspace'' THEN RAISE EXCEPTION ''injected failure''; END IF; RETURN ${if (operation == "INSERT") "NEW" else "OLD"}; END;'""")
                it.execute("CREATE TRIGGER kotlin_workspace_failure BEFORE $operation ON $table FOR EACH ROW EXECUTE FUNCTION kotlin_workspace_failure_fn()")
            }
        }
    }

    private fun removeFailure(database: Database) = database.write { connection -> connection.createStatement().use {
        if (database.sqlite) it.execute("DROP TRIGGER IF EXISTS kotlin_workspace_failure")
        else {
            for (table in listOf("cash_projection_states", "workspaces")) it.execute("DROP TRIGGER IF EXISTS kotlin_workspace_failure ON $table")
            it.execute("DROP FUNCTION IF EXISTS kotlin_workspace_failure_fn()")
        }
    } }

    private fun cleanupWorkspace(database: Database, id: String) = database.write { connection ->
        for (table in listOf("cash_projection_states", "cash_projection_datasets", "workspaces")) connection.prepareStatement(
            "DELETE FROM $table WHERE ${if (table == "workspaces") "id" else "workspace_id"}=?").use { it.setString(1, id); it.executeUpdate() }
        connection.createStatement().use {
            it.executeUpdate("UPDATE user_sessions SET active_workspace_id='fixture-a' WHERE id IN ('session-admin','session-viewer','session-removed')")
            it.executeUpdate("UPDATE user_sessions SET active_workspace_id=NULL WHERE id='session-empty'")
        }
    }
}
