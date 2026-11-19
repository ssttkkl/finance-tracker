package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.WORKSPACES
import com.finance.tracker.backend.persistence.generated.tables.references.ACCOUNTS
import com.finance.tracker.business.domain.sha256Hex
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.time.Instant

internal class CashCreationCommandInputFailure(val code: String = "cash_creation_command_invalid") : RuntimeException(code)
private val creationCommandKey = Regex("[A-Za-z0-9_:.~-]{1,255}")

internal class CashCreationCommand(val key: String, val accountIds: List<Long>, requestBody: ByteArray) {
    private val digest = sha256Hex((accountIds.joinToString(",") + "\n").encodeToByteArray() + requestBody)

    fun replay(connection: Connection, sqlite: Boolean, principal: Principal, kind: String): JsonObject? =
        ReceiptRepository(connection, requireNotNull(principal.workspaceId), sqlite)
            .replay(principal.userId, key, digest, kind)?.let(::publicResult)

    fun rejection(connection: Connection, sqlite: Boolean, principal: Principal, kind: String,
        currencies: List<String>, now: () -> Instant): JsonObject? {
        val workspace = requireNotNull(principal.workspaceId)
        val accounts = connection.jooq(sqlite).select(ACCOUNTS.ID, ACCOUNTS.ACTIVE, ACCOUNTS.CURRENCIES).from(ACCOUNTS)
            .where(ACCOUNTS.WORKSPACE_ID.eq(workspace)).and(ACCOUNTS.ID.`in`(accountIds)).fetch().associateBy { it[ACCOUNTS.ID] }
        val valid = accountIds.zip(currencies).all { (id, currency) ->
            val account = accounts[id]
            account?.get(ACCOUNTS.ACTIVE) == true && account[ACCOUNTS.CURRENCIES]?.jsonArray
                ?.any { it.jsonPrimitive.content.equals(currency.trim(), ignoreCase = true) } == true
        }
        return if (valid) null else record(connection, sqlite, principal, kind, now, rejected = "account_changed")
    }

    fun apply(connection: Connection, sqlite: Boolean, principal: Principal, kind: String,
        now: () -> Instant, write: () -> Unit): JsonObject {
        connection.createStatement().use { it.execute("SAVEPOINT cash_creation") }
        val rejected = try {
            write()
            null
        } catch (_: ManualCashBusinessFailure) {
            "row_save_failed"
        } catch (_: CashflowOperationFailure) {
            "row_save_failed"
        }
        connection.createStatement().use {
            if (rejected != null) it.execute("ROLLBACK TO SAVEPOINT cash_creation")
            it.execute("RELEASE SAVEPOINT cash_creation")
        }
        return record(connection, sqlite, principal, kind, now, rejected)
    }

    private fun record(connection: Connection, sqlite: Boolean, principal: Principal, kind: String,
        now: () -> Instant, rejected: String?): JsonObject {
        val workspace = requireNotNull(principal.workspaceId)
        val sequence = connection.jooq(sqlite).select(WORKSPACES.LEDGER_SEQ).from(WORKSPACES)
            .where(WORKSPACES.ID.eq(workspace)).fetchSingle(WORKSPACES.LEDGER_SEQ)!!
        val summary = buildMap<String, Any> {
            put("ledger_seq", sequence.toString())
            if (rejected != null) {
                put("status", "failed")
                put("commit_outcome", mapOf("has_new_failures" to true, "has_business_writes" to false, "reason_codes" to listOf(rejected)))
            }
        }
        return publicResult(ReceiptRepository(connection, workspace, sqlite, now).create(principal.userId, key, digest, kind,
            summary, emptyList<Any>()))
    }

    private fun publicResult(result: JsonObject): JsonObject =
        JsonObject(result.filterKeys { it in setOf("receipt_id", "operation_kind", "committed", "created_at", "ledger_seq", "status", "commit_outcome") })
}

internal suspend fun ApplicationCall.cashCreationCommand(principal: Principal, accountCount: Int): Pair<ByteArray, CashCreationCommand> {
    val scope = request.headers.getAll("X-FT-Workspace-ID")?.singleOrNull()
    val key = request.headers.getAll("Idempotency-Key")?.singleOrNull()
    if (scope.isNullOrBlank() || scope != principal.workspaceId || key == null || !creationCommandKey.matches(key)) {
        throw CashCreationCommandInputFailure()
    }
    val accountIds = request.headers.getAll("X-FT-Account-IDs")?.singleOrNull()?.split(',').orEmpty()
    if (accountIds.size != accountCount || accountIds.any { !it.matches(Regex("[1-9][0-9]{0,18}")) || it.toLongOrNull() == null }) {
        throw CashCreationCommandInputFailure()
    }
    val bytes = receiveChannel().readRemaining(65537).readByteArray()
    if (bytes.size > 65536) throw CashCreationCommandInputFailure()
    return bytes to CashCreationCommand(key, accountIds.map(String::toLong), bytes)
}

internal suspend fun ApplicationCall.respondCashCreation(result: JsonObject) {
    if (result["status"]?.jsonPrimitive?.content == "failed") {
        val accountChanged = result.getValue("commit_outcome").jsonObject.getValue("reason_codes").jsonArray
            .single().jsonPrimitive.content == "account_changed"
        respond(HttpStatusCode.Conflict, buildJsonObject {
            put("commit_state", "not_committed")
            put("error", buildJsonObject {
                put("code", if (accountChanged) "account_changed" else "invalid_record")
                put("message", if (accountChanged) "请重新选择账户和币种。" else "请检查填写内容后重试。")
            })
        })
    } else respond(HttpStatusCode.Created, result)
}
