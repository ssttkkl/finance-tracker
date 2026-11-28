package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.LedgerSnapshotReadPage
import com.finance.tracker.business.contracts.ReadPagePosition
import com.finance.tracker.business.contracts.Role
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.zip.GZIPInputStream
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SnapshotSourceBindingTimingTest {
    @Test fun splitOneSourceBindingReadWithoutChangingItsCursorWindow() = withCashWriteSetTestWorkspace("postgres") { db, workspace, account ->
        val user = UUID.randomUUID().toString()
        val email = "$user@example.invalid"
        val token = "fixture-only-$workspace"
        val inserted = 256
        db.write { connection ->
            val sql = connection.jooq(false)
            sql.insertInto(USERS).set(USERS.ID, user).set(USERS.EMAIL, email).set(USERS.PASSWORD_HASH, "unused")
                .set(USERS.CREATED_AT, deletionTime).execute()
            sql.insertInto(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID, workspace)
                .set(WORKSPACE_MEMBERSHIPS.USER_ID, user).set(WORKSPACE_MEMBERSHIPS.ROLE, "editor")
                .set(WORKSPACE_MEMBERSHIPS.CREATED_AT, deletionTime).execute()
            sql.insertInto(USER_SESSIONS).set(USER_SESSIONS.ID, UUID.randomUUID().toString())
                .set(USER_SESSIONS.USER_ID, user).set(USER_SESSIONS.TOKEN_DIGEST, tokenDigest(token))
                .set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, workspace).set(USER_SESSIONS.CREATED_AT, deletionTime)
                .set(USER_SESSIONS.EXPIRES_AT, Instant.parse("2099-01-01T00:00:00Z")).execute()
            val transaction = deletionFact(connection, db, workspace, account, null)
            repeat(inserted) { index ->
                val coverage = buildJsonObject { put("fixture_text", "账目-source-".repeat(180) + index) }
                deletionInsert(connection, db, "cash_source_bindings", linkedMapOf(
                    "workspace_id" to workspace, "transaction_id" to transaction, "source_type" to "alipay",
                    "source_scope_kind" to "account", "source_scope_key" to "fixture-account", "identity_kind" to "provider_id",
                    "identity_key" to "synthetic-$index", "identity_strength" to "provider", "strategy_version" to "fixture",
                    "coverage" to coverage, "revision" to 1L, "created_at" to deletionTime, "updated_at" to deletionTime))
            }
        }
        try {
            val service = LedgerReadService(db)
            val principal = Principal(user, email, workspace, Role.EDITOR)
            val request = ReadPageRequest(workspace, service.version(token, principal).ledgerSeq, ReadPagePosition("cash_source_bindings", 0))
            val encoding = ReadPageEncoding(1.0, 0.0)
            repeat(3) { service.snapshotPage(token, principal, request, encoding) }
            val trace = linkedMapOf<String, SnapshotReadTrace.Timing>()
            val samples = 6
            val file = Files.createTempFile("private-ft-source-read-", ".jfr")
            try {
                val thread = Thread.currentThread().id
                Recording().use { recording ->
                    recording.enable("jdk.SocketRead").withThreshold(Duration.ZERO).withStackTrace()
                    recording.start()
                    repeat(samples) {
                        val start = System.nanoTime()
                        val result = SnapshotReadTrace.capture(trace) { service.snapshotPage(token, principal, request, encoding) }
                        val wall = System.nanoTime() - start
                        assertTrue(result.gzip)
                        val receiveStart = System.nanoTime()
                        val text = GZIPInputStream(ByteArrayInputStream(result.body)).use { it.readBytes() }
                        val decoded = System.nanoTime()
                        val page = Json.decodeFromString<LedgerSnapshotReadPage>(text.decodeToString())
                        val parse = System.nanoTime() - decoded
                        assertEquals(inserted, page.chunk.items.size)
                        assertEquals(inserted, page.chunk.total)
                        println("cash-source-page $wall ${result.body.size} ${text.size} ${decoded - receiveStart} $parse")
                    }
                    recording.stop()
                    recording.dump(file)
                }
                var calls = 0
                var ns = 0L
                RecordingFile(file).use { recording ->
                    while (recording.hasMoreEvents()) {
                        val event = recording.readEvent()
                        if (event.eventType.name == "jdk.SocketRead" && event.thread?.javaThreadId == thread &&
                            event.stackTrace?.frames?.any { it.method.type.name.startsWith("org.postgresql") && it.method.name == "fetch" } == true) {
                            calls++
                            ns += event.duration.toNanos()
                        }
                    }
                }
                for ((stage, time) in trace) println("cash-source-stage $stage ${time.calls} ${time.elapsedNs}")
                println("cash-source-socket $calls $ns")
            } finally { Files.deleteIfExists(file) }
        } finally {
            db.write { connection ->
                val sql = connection.jooq(false)
                sql.deleteFrom(USER_SESSIONS).where(USER_SESSIONS.USER_ID.eq(user)).execute()
                sql.deleteFrom(WORKSPACE_MEMBERSHIPS).where(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute()
                sql.deleteFrom(USERS).where(USERS.ID.eq(user)).execute()
            }
        }
    }
}
