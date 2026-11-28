package com.finance.tracker.data

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
}

internal class SqlLedgerAssistantConversations private constructor(private val driver: SqlDriver) : LedgerAssistantConversations {
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

    override suspend fun delete(previous: LedgerAssistantConversation): Boolean {
        driver.execute(null, "DELETE FROM local_ledger_assistant_conversations WHERE id = ? AND revision = ?", 2) {
            bindString(0, previous.id)
            bindLong(1, previous.revision)
        }.await()
        return readText(previous.id) == null
    }

    private suspend fun readText(id: String): String? = driver.executeQuery(
        null,
        "SELECT data FROM local_ledger_assistant_conversations WHERE id = ?",
        { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
        1,
    ) { bindString(0, id) }.await()

    companion object {
        suspend fun open(driver: SqlDriver): SqlLedgerAssistantConversations {
            driver.execute(null, """
                CREATE TABLE IF NOT EXISTS local_ledger_assistant_conversations (
                    id TEXT PRIMARY KEY NOT NULL,
                    updated_at TEXT NOT NULL,
                    revision INTEGER NOT NULL,
                    data TEXT NOT NULL
                )
            """.trimIndent(), 0).await()
            return SqlLedgerAssistantConversations(driver)
        }
    }
}
