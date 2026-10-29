package com.finance.tracker.backend

import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeFalse

class WorkspaceLockReauthorizationTest {
    @Test fun lockWaitRechecksCurrentMembershipSessionAndWorkspace() {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),
            "database.json").readText()).jsonObject
        val db = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        assumeFalse("real concurrent lock wait is PostgreSQL-specific", db.sqlite)
        val workspaces = WorkspaceService(db)
        val userId = db.read { connection -> ReadServices(db).session(connection, "synthetic-admin").userId }
        val executor = Executors.newFixedThreadPool(2)
        try {
            for (mode in listOf("switch", "revoke", "logout")) {
                val name = "锁等待-${mode}-${java.util.UUID.randomUUID()}"
                val created = workspaces.create("synthetic-admin", name)
                val workspace = created.activeWorkspaceId!!
                val locked = CountDownLatch(1)
                val release = CountDownLatch(1)
                try {
                    val holder = executor.submit {
                        db.write { connection ->
                            assertTrue(connection.lockWorkspace(false, workspace))
                            locked.countDown()
                            release.await(10, TimeUnit.SECONDS)
                        }
                    }
                    assertTrue(locked.await(5, TimeUnit.SECONDS))
                    val deleter = executor.submit<Result<Any?>> {
                        runCatching { workspaces.delete("synthetic-admin", name) }
                    }
                    Thread.sleep(500)
                    db.write { connection ->
                        when (mode) {
                            "switch" -> connection.prepareStatement(
                                "UPDATE user_sessions SET active_workspace_id='fixture-a' WHERE token_digest=?").use {
                                it.setString(1, tokenDigest("synthetic-admin")); it.executeUpdate()
                            }
                            "revoke" -> connection.prepareStatement(
                                "DELETE FROM workspace_memberships WHERE workspace_id=? AND user_id=?").use {
                                it.setString(1, workspace); it.setString(2, userId); it.executeUpdate()
                            }
                            else -> connection.prepareStatement(
                                "DELETE FROM user_sessions WHERE token_digest=?").use {
                                it.setString(1, tokenDigest("synthetic-admin")); it.executeUpdate()
                            }
                        }
                    }
                    release.countDown()
                    holder.get(10, TimeUnit.SECONDS)
                    val result = deleter.get(10, TimeUnit.SECONDS)
                    assertTrue(result.isFailure, mode)
                    assertIs<AccessFailure>(result.exceptionOrNull(), mode)
                    assertTrue(db.read { connection ->
                        connection.createStatement().use { statement ->
                            statement.executeQuery("SELECT COUNT(*) FROM workspaces WHERE id='$workspace'").use {
                                it.next(); it.getLong(1) > 0
                            }
                        }
                    }, mode)
                } finally {
                    release.countDown()
                    db.write { connection ->
                        val updated = connection.prepareStatement(
                            "UPDATE workspace_memberships SET role='admin' WHERE workspace_id=? AND user_id=?").use {
                            it.setString(1, workspace); it.setString(2, userId); it.executeUpdate()
                        }
                        if (updated == 0) connection.prepareStatement(
                            "INSERT INTO workspace_memberships(workspace_id,user_id,role,created_at) VALUES(?, ?, 'admin', '2026-01-01 00:00:00')").use {
                            it.setString(1, workspace); it.setString(2, userId); it.executeUpdate()
                        }
                        val sessionUpdated = connection.prepareStatement(
                            "UPDATE user_sessions SET active_workspace_id=? WHERE token_digest=?").use {
                            it.setString(1, workspace); it.setString(2, tokenDigest("synthetic-admin")); it.executeUpdate()
                        }
                        if (sessionUpdated == 0) connection.prepareStatement(
                            "INSERT INTO user_sessions(id,user_id,token_digest,active_workspace_id,expires_at,created_at) VALUES('session-admin',?,?,?,'2099-01-01 00:00:00','2026-01-01 00:00:00')").use {
                            it.setString(1, userId); it.setString(2, tokenDigest("synthetic-admin")); it.setString(3, workspace); it.executeUpdate()
                        }
                    }
                    if (db.read { connection -> connection.createStatement().use { statement ->
                            statement.executeQuery("SELECT COUNT(*) FROM workspaces WHERE id='$workspace'").use { it.next(); it.getLong(1) }
                        } } > 0) workspaces.delete("synthetic-admin", name)
                    workspaces.select("synthetic-admin", "fixture-a")
                }
            }
        } finally { executor.shutdownNow() }
    }
}
