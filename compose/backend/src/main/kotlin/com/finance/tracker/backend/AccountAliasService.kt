package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.*

internal class AccountAliasFailure(message: String) : RuntimeException(message)

internal fun normalizedAccountAliasValue(type: String, value: String): String {
    var normalized = value.trim { it.code in pythonWhitespace }
    if (type == "card_tail" && (normalized.length != 4 || normalized.any { it !in '0'..'9' }))
        throw AccountAliasFailure("card_tail must contain exactly four ASCII digits")
    if (type == "account_identifier") {
        normalized = normalized.filter { it.code !in pythonWhitespace && it !in "-()\uff08\uff09" }
        if (normalized.length <= 4 || normalized.any { it !in '0'..'9' })
            throw AccountAliasFailure("account_identifier must contain more than four ASCII digits")
    }
    return normalized
}

private fun aliasStorageText(value: JsonElement?, original: DecodedPythonJson? = null, normalized: String? = null) {
    try { manualOriginalUtf8(value, original, normalized = normalized) }
    catch (failure: ManualCashBusinessFailure) { throw AccountAliasFailure(failure.message.orEmpty()) }
}

internal class AccountAliasRepository(private val context: CashSqlContext) {
    private val aliases = ACCOUNT_ALIASES
    fun add(type: String, value: String, accountId: Long): Long {
        context.requireActive()
        val normalized = normalizedAccountAliasValue(type, value)
        aliasStorageText(JsonPrimitive(type))
        aliasStorageText(JsonPrimitive(normalized))
        return context.sql.insertInto(aliases).set(aliases.WORKSPACE_ID, context.workspace)
            .set(aliases.ALIAS_TYPE, type).set(aliases.ALIAS_VALUE, normalized).set(aliases.ACCOUNT_ID, accountId)
            .set(aliases.CREATED_AT, context.now().truncatedTo(ChronoUnit.MICROS))
            .set(aliases.UPDATED_AT, context.now().truncatedTo(ChronoUnit.MICROS))
            .returningResult(aliases.ID).fetchSingle().value1()!!
    }
    fun list(): List<Map<String, Any?>> {
        context.requireActive()
        return context.sql.selectFrom(aliases).where(aliases.WORKSPACE_ID.eq(context.workspace))
            .orderBy(aliases.CREATED_AT, aliases.ID).fetch().map { row ->
                mapOf("id" to row[aliases.ID], "alias_type" to row[aliases.ALIAS_TYPE],
                    "alias_value" to row[aliases.ALIAS_VALUE], "account_id" to row[aliases.ACCOUNT_ID],
                    "created_at" to row[aliases.CREATED_AT], "updated_at" to row[aliases.UPDATED_AT])
            }
    }
}

class AccountAliasService(private val database: Database, private val now: () -> Instant = Instant::now) {
    private val reads = ReadServices(database, now)
    private fun text(body: JsonObject, key: String, required: Boolean = true): String? {
        val value = body[key]
        if (!required && (value == null || value == JsonNull)) return null
        if (value !is JsonPrimitive || !value.isString || required && value.content.all { it.code in pythonWhitespace })
            throw AccountAliasFailure(key + "_required")
        return value.content.trim { it.code in pythonWhitespace }
    }

    internal fun add(token: String?, value: JsonElement, original: DecodedPythonJson? = null,
        requestWorkspace: String? = null): JsonObject {
        val body = value as? JsonObject ?: throw AccountAliasFailure("alias_payload_invalid")
        val type = text(body, "type", required = false).orEmpty().ifEmpty { "card_tail" }
        val alias = text(body, "value")!!
        val account = text(body, "account")!!
        return database.write { connection ->
            val workspace = requestWorkspace ?: reads.session(connection, token).workspaceId ?: throw AccessFailure(true)
            if (!connection.lockWorkspace(database.sqlite, workspace)) throw AccessFailure(true)
            reads.lockedContext(connection, token, workspace, write = true)
            aliasStorageText(body["account"], original, account)
            val accountId = connection.jooq(database.sqlite).select(ACCOUNTS.ID).from(ACCOUNTS)
                .where(ACCOUNTS.WORKSPACE_ID.eq(workspace)).and(ACCOUNTS.NAME.eq(account)).fetchOne(ACCOUNTS.ID)
                ?: throw AccountAliasFailure("account.not_found")
            val normalized = normalizedAccountAliasValue(type, alias)
            aliasStorageText(body["type"], original, type)
            aliasStorageText(body["value"], original, normalized)
            val id = AccountAliasRepository(CashSqlContext(connection, workspace, database.sqlite, now)).add(type, normalized, accountId)
            reads.lockedContext(connection, token, workspace, write = true)
            buildJsonObject { put("id", id) }
        }
    }
}

