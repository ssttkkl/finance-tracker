package com.finance.tracker.data

import app.cash.sqldelight.SuspendingTransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.finance.tracker.client.ClientPendingCommand
import com.finance.tracker.client.LedgerAssistantDelivery
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class LedgerAssistantConversation(
    val id: String,
    val updatedAt: String,
    val revision: Long = 0,
    val messages: List<LedgerAssistantMessage> = emptyList(),
    val checkpoint: String? = null,
    val draft: LedgerAssistantDraft? = null,
    val incompleteReply: String? = null,
    val titleOverride: String? = null,
    val pinned: Boolean = false,
) {
    val originalTitle: String? get() = messages.firstNotNullOfOrNull { it.delivery?.input }?.trim()
    val historyTitle: String? get() = titleOverride ?: originalTitle
    val hasUnresolvedWrite: Boolean get() = draft?.command?.let { it.receipt == null } == true

    override fun toString(): String = "LedgerAssistantConversation(id=$id, revision=$revision)"
}

@Serializable
internal data class LedgerAssistantMessage(
    val delivery: LedgerAssistantDelivery? = null,
    val reply: String? = null,
    val result: LedgerAssistantToolResult? = null,
    val toolTrace: LedgerAssistantToolTrace? = null,
    val attachment: LedgerAssistantFile? = null,
) {
    override fun toString(): String = "LedgerAssistantMessage"
}

@Serializable
internal data class LedgerAssistantToolResult(val tool: String, val payload: JsonObject) {
    override fun toString(): String = "LedgerAssistantToolResult(tool=$tool)"
}

@Serializable
internal data class LedgerAssistantDraft(
    val localId: String,
    val toolCallId: String,
    val fields: JsonObject,
    val cancelled: Boolean = false,
    val command: ClientPendingCommand? = null,
) {
    override fun toString(): String = "LedgerAssistantDraft(cancelled=$cancelled)"
}

internal interface LedgerAssistantConversations {
    suspend fun list(): List<LedgerAssistantConversation>
    suspend fun delete(previous: LedgerAssistantConversation): Boolean
    suspend fun swap(previous: LedgerAssistantConversation?, next: LedgerAssistantConversation): Boolean
    suspend fun purgeExpiredSkillFiles(now: Long)
    suspend fun skillFiles(conversationId: String, now: Long): List<LedgerAssistantFile>
    suspend fun readSkillFile(conversationId: String, fileId: String, now: Long): LedgerAssistantFileContent?
    suspend fun skillReceipt(conversationId: String, callId: String): LedgerAssistantFileReceipt?
    suspend fun commitSkillRun(
        previous: LedgerAssistantConversation,
        next: LedgerAssistantConversation,
        removed: Set<String>,
        files: List<LedgerAssistantFileContent>,
        receipt: LedgerAssistantFileReceipt,
        now: Long,
        requiredSource: String? = null,
        expectedLedgerVersion: Long? = null,
    ): Boolean
}

internal class SqlLedgerAssistantConversations private constructor(
    driver: SqlDriver,
    private val readLedgerVersion: suspend () -> Long?,
) : LedgerAssistantConversations, SuspendingTransacterImpl(driver) {
    override suspend fun list(): List<LedgerAssistantConversation> = driver.executeQuery(
        null,
        "SELECT data FROM local_ledger_assistant_conversations ORDER BY updated_at DESC, id DESC",
        { cursor ->
            QueryResult.Value(buildList {
                while (cursor.next().value) add(Json.decodeFromString<LedgerAssistantConversation>(checkNotNull(cursor.getString(0))))
            })
        },
        0,
    ).await()

    override suspend fun swap(previous: LedgerAssistantConversation?, next: LedgerAssistantConversation): Boolean {
        val text = Json.encodeToString(next)
        if (previous == null) {
            check(next.revision == 0L)
            driver.execute(null, """
                INSERT OR IGNORE INTO local_ledger_assistant_conversations (id, updated_at, revision, data)
                VALUES (?, ?, ?, ?)
            """.trimIndent(), 4) {
                bindString(0, next.id)
                bindString(1, next.updatedAt)
                bindLong(2, next.revision)
                bindString(3, text)
            }.await()
            return readText(next.id) == text
        }
        check(previous.id == next.id && previous.revision < Long.MAX_VALUE && next.revision == previous.revision + 1)
        driver.execute(null, """
            UPDATE local_ledger_assistant_conversations SET updated_at = ?, revision = ?, data = ?
            WHERE id = ? AND revision = ?
        """.trimIndent(), 5) {
            bindString(0, next.updatedAt)
            bindLong(1, next.revision)
            bindString(2, text)
            bindString(3, next.id)
            bindLong(4, previous.revision)
        }.await()
        return readText(next.id) == text
    }

    override suspend fun delete(previous: LedgerAssistantConversation): Boolean = transactionWithResult {
        if (!hasRevision(previous)) return@transactionWithResult readText(previous.id) == null
        driver.execute(null, "DELETE FROM local_ledger_assistant_conversations WHERE id = ? AND revision = ?", 2) {
            bindString(0, previous.id)
            bindLong(1, previous.revision)
        }.await()
        if (readText(previous.id) != null) rollback(false)
        driver.execute(null, "DELETE FROM local_ledger_assistant_files WHERE conversation_id = ?", 1) {
            bindString(0, previous.id)
        }.await()
        driver.execute(null, "DELETE FROM local_ledger_assistant_file_receipts WHERE conversation_id = ?", 1) {
            bindString(0, previous.id)
        }.await()
        true
    }

    override suspend fun skillFiles(conversationId: String, now: Long): List<LedgerAssistantFile> = transactionWithResult {
        purgeExpiredSkillFiles(now)
        driver.execute(null, """
            UPDATE local_ledger_assistant_files SET expires_at = ? WHERE conversation_id = ? AND expires_at IS NOT NULL
        """.trimIndent(), 2) {
            bindLong(0, now + LEDGER_ASSISTANT_FILE_TTL_MS)
            bindString(1, conversationId)
        }.await()
        driver.executeQuery(null, """
            SELECT id, path, mime_type, byte_size, expires_at FROM local_ledger_assistant_files
            WHERE conversation_id = ? ORDER BY path, id
        """.trimIndent(), { cursor -> QueryResult.Value(buildList {
            while (cursor.next().value) add(cursor.assistantFile())
        }) }, 1) { bindString(0, conversationId) }.await()
    }

    override suspend fun readSkillFile(conversationId: String, fileId: String, now: Long): LedgerAssistantFileContent? =
        driver.executeQuery(null, """
            SELECT id, path, mime_type, byte_size, expires_at, content FROM local_ledger_assistant_files
            WHERE conversation_id = ? AND id = ? AND (expires_at IS NULL OR expires_at > ?)
        """.trimIndent(), { cursor ->
            QueryResult.Value(if (cursor.next().value) {
                LedgerAssistantFileContent(cursor.assistantFile(), checkNotNull(cursor.getString(5)))
            } else null)
        }, 3) {
            bindString(0, conversationId)
            bindString(1, fileId)
            bindLong(2, now)
        }.await()

    override suspend fun skillReceipt(conversationId: String, callId: String): LedgerAssistantFileReceipt? =
        driver.executeQuery(null, """
            SELECT receipt FROM local_ledger_assistant_file_receipts WHERE conversation_id = ? AND call_id = ?
        """.trimIndent(), { cursor ->
            QueryResult.Value(if (cursor.next().value) {
                Json.decodeFromString<LedgerAssistantFileReceipt>(checkNotNull(cursor.getString(0)))
            } else null)
        }, 2) {
            bindString(0, conversationId)
            bindString(1, callId)
        }.await()

    override suspend fun commitSkillRun(
        previous: LedgerAssistantConversation,
        next: LedgerAssistantConversation,
        removed: Set<String>,
        files: List<LedgerAssistantFileContent>,
        receipt: LedgerAssistantFileReceipt,
        now: Long,
        requiredSource: String?,
        expectedLedgerVersion: Long?,
    ): Boolean = transactionWithResult {
        if (!hasRevision(previous) || skillReceipt(previous.id, receipt.callId) != null) return@transactionWithResult false
        if (requiredSource != null && readSkillFile(previous.id, requiredSource, now)?.metadata?.attached != false) {
            throw LedgerAssistantScriptFailure("file_expired")
        }
        if (expectedLedgerVersion != null && readLedgerVersion() != expectedLedgerVersion) {
            throw LedgerAssistantScriptFailure("ledger_changed")
        }
        if (!swap(previous, next)) rollback(false)
        purgeExpiredSkillFiles(now)
        for (id in removed) {
            driver.execute(null, """
                DELETE FROM local_ledger_assistant_files WHERE conversation_id = ? AND id = ? AND expires_at IS NOT NULL
            """.trimIndent(), 2) {
                bindString(0, previous.id)
                bindString(1, id)
            }.await()
        }
        for (content in files) {
            val file = content.metadata
            driver.execute(null, """
                INSERT INTO local_ledger_assistant_files (conversation_id, id, path, mime_type, byte_size, expires_at, content)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(), 7) {
                bindString(0, previous.id)
                bindString(1, file.id)
                bindString(2, file.path)
                bindString(3, file.mimeType)
                bindLong(4, file.size)
                bindLong(5, if (file.attached) null else now + LEDGER_ASSISTANT_FILE_TTL_MS)
                bindString(6, content.text)
            }.await()
        }
        driver.execute(null, """
            INSERT INTO local_ledger_assistant_file_receipts (conversation_id, call_id, receipt) VALUES (?, ?, ?)
        """.trimIndent(), 3) {
            bindString(0, previous.id)
            bindString(1, receipt.callId)
            bindString(2, Json.encodeToString(receipt))
        }.await()
        true
    }

    override suspend fun purgeExpiredSkillFiles(now: Long) {
        driver.execute(null, "DELETE FROM local_ledger_assistant_files WHERE expires_at <= ?", 1) {
            bindLong(0, now)
        }.await()
    }

    private suspend fun hasRevision(item: LedgerAssistantConversation): Boolean = driver.executeQuery(
        null, "SELECT 1 FROM local_ledger_assistant_conversations WHERE id = ? AND revision = ?",
        { cursor -> QueryResult.Value(cursor.next().value) }, 2,
    ) {
        bindString(0, item.id)
        bindLong(1, item.revision)
    }.await()

    private suspend fun readText(id: String): String? = driver.executeQuery(
        null,
        "SELECT data FROM local_ledger_assistant_conversations WHERE id = ?",
        { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
        1,
    ) { bindString(0, id) }.await()

    companion object {
        suspend fun open(
            driver: SqlDriver,
            readLedgerVersion: suspend () -> Long? = { null },
        ): SqlLedgerAssistantConversations {
            driver.execute(null, """
                CREATE TABLE IF NOT EXISTS local_ledger_assistant_conversations (
                    id TEXT PRIMARY KEY NOT NULL,
                    updated_at TEXT NOT NULL,
                    revision INTEGER NOT NULL,
                    data TEXT NOT NULL
                )
            """.trimIndent(), 0).await()
            driver.execute(null, """
                CREATE TABLE IF NOT EXISTS local_ledger_assistant_files (
                    conversation_id TEXT NOT NULL, id TEXT NOT NULL, path TEXT NOT NULL,
                    mime_type TEXT NOT NULL, byte_size INTEGER NOT NULL, expires_at INTEGER, content TEXT NOT NULL,
                    PRIMARY KEY (conversation_id, id)
                )
            """.trimIndent(), 0).await()
            driver.execute(null, """
                CREATE UNIQUE INDEX IF NOT EXISTS local_ledger_assistant_file_path
                ON local_ledger_assistant_files (conversation_id, path) WHERE expires_at IS NOT NULL
            """.trimIndent(), 0).await()
            driver.execute(null, """
                CREATE INDEX IF NOT EXISTS local_ledger_assistant_file_expiry
                ON local_ledger_assistant_files (expires_at) WHERE expires_at IS NOT NULL
            """.trimIndent(), 0).await()
            driver.execute(null, """
                CREATE TABLE IF NOT EXISTS local_ledger_assistant_file_receipts (
                    conversation_id TEXT NOT NULL, call_id TEXT NOT NULL, receipt TEXT NOT NULL,
                    PRIMARY KEY (conversation_id, call_id)
                )
            """.trimIndent(), 0).await()
            return SqlLedgerAssistantConversations(driver, readLedgerVersion)
        }
    }
}

private fun app.cash.sqldelight.db.SqlCursor.assistantFile() = LedgerAssistantFile(
    checkNotNull(getString(0)), checkNotNull(getString(1)), checkNotNull(getString(2)),
    checkNotNull(getLong(3)), attached = getLong(4) == null,
)
