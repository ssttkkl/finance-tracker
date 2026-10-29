package com.finance.tracker.backend

import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

internal class CurrentProjectionFixture : AutoCloseable {
    private val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")), "database.json").readText()).jsonObject
    private val url = config.getValue("jdbc_url").jsonPrimitive.content
    private val user = System.getenv("FT_KOTLIN_TEST_DB_USER")
    private val password = System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")
    val db = Database(url, user, password)
    val workspaces = WorkspaceService(db)
    private val name = "当前收支读取-${UUID.randomUUID()}"
    val workspace = workspaces.create("synthetic-admin", name).activeWorkspaceId!!
    val identifier: Long
    val projectionId: String

    init {
        deletionAccount(db, workspace)
        LegacyCashflowService(db).addTransaction("synthetic-admin",
            LegacyCashInput("账户", "-12.340000000000000001", "CNY", "2026-08-12T03:04:05.123456Z", counterparty = "当前商家"))
        identifier = db.read { c -> c.prepareStatement("SELECT id FROM cash_transactions WHERE workspace_id=?").use {
            it.setString(1, workspace); it.executeQuery().use { rows -> assertTrue(rows.next()); rows.getLong(1) }
        } }
        projectionId = CurrentProjectionReadService(db).location("synthetic-admin", identifier).getValue("projection_id").jsonPrimitive.content
    }

    fun execute(sql: String, vararg values: Any?) = db.write { c -> c.prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
    } }

    fun useEditor() {
        assertEquals(1, execute("UPDATE workspace_memberships SET role='editor' WHERE workspace_id=? AND user_id='admin'", workspace))
        assertEquals(com.finance.tracker.business.contracts.Role.EDITOR, ReadServices(db).requireContext("synthetic-admin").role)
    }

    fun addIssue() = db.write { c ->
        deletionInsert(c, db, "cash_transaction_issues", linkedMapOf(
            "workspace_id" to workspace, "transaction_id" to identifier, "issue_key" to "pending:source",
            "code" to "source_update_pending", "field_path" to null, "revision" to 1L,
            "pending_fields" to buildJsonObject { put("note", "私有待采用正文") },
            "pending_source_payload" to buildJsonObject { put("备注", "私有账单原文") },
            "origin" to JsonObject(emptyMap()), "created_at" to deletionTime, "updated_at" to deletionTime,
        ))
    }

    fun withWal(block: () -> Unit) {
        if (!db.sqlite) { block(); return }
        fun journal(mode: String? = null) = DriverManager.getConnection(url).use { c -> c.createStatement().use {
            it.executeQuery("PRAGMA journal_mode" + (mode?.let { value -> "=$value" } ?: "")).use { rows -> assertTrue(rows.next()); rows.getString(1) }
        } }
        val original = journal()
        assertEquals("wal", journal("WAL").lowercase())
        try { block() } finally { assertEquals(original.lowercase(), journal(original).lowercase()) }
    }

    override fun close() {
        assertEquals(1, execute("UPDATE workspace_memberships SET role='admin' WHERE workspace_id=? AND user_id='admin'", workspace))
        workspaces.select("synthetic-admin", workspace)
        workspaces.delete("synthetic-admin", name)
        workspaces.select("synthetic-admin", "fixture-a")
    }
}
