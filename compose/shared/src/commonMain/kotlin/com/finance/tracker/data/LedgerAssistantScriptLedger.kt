package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.domain.CashProjectionDomainFailure
import com.finance.tracker.client.CashLedgerSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class LedgerAssistantScriptLedger(
    private val reader: LedgerLocalReader,
    private val workspaceId: String,
    private val checkScope: suspend () -> Unit,
) {
    var version: Long? = null
        private set

    suspend fun checkVersion() {
        checkScope()
        val pinned = version ?: return
        if (reader.ledgerVersion() != pinned) throw LedgerAssistantScriptFailure("ledger_changed")
        checkScope()
    }

    suspend fun read(method: String, args: JsonArray): JsonElement {
        checkScope()
        val pinned = version ?: reader.ledgerVersion()?.also { version = it }
            ?: throw LedgerAssistantScriptFailure("ledger_read_unavailable")
        checkVersion()
        val result = try {
            when (method) {
                "ft.accounts.list" -> {
                    requireLedgerArguments(args.isEmpty())
                    Json.encodeToJsonElement(reader.accounts() ?: unavailableLedger())
                }
                "ft.categories.list" -> {
                    requireLedgerArguments(args.isEmpty())
                    Json.encodeToJsonElement((reader.categories() ?: unavailableLedger()).items)
                }
                "ft.transactions.summarize", "ft.transactions.list" -> {
                    val summarize = method == "ft.transactions.summarize"
                    requireLedgerArguments(args.size == if (summarize) 1 else 2)
                    val filter = args.first() as? JsonObject ?: throw LedgerAssistantScriptFailure("invalid_arguments")
                    requireLedgerArguments(filter.all { (key, value) -> key in scriptFilterKeys && value.scriptString() != null })
                    val position = args.getOrNull(1)?.takeUnless { it == JsonNull }?.let {
                        scriptArgument(it).toIntOrNull()?.takeIf { offset -> offset >= 0 }
                            ?: throw LedgerAssistantScriptFailure("invalid_arguments")
                    } ?: 0
                    val page = reader.page(
                        assistantCashFilters(filter), filter["time_zone_id"]?.scriptString(),
                        startIndex = if (summarize) null else position,
                        expectedLedgerSeq = if (summarize) null else pinned,
                        limit = if (summarize) 1 else 50,
                    ) ?: unavailableLedger()
                    if (page.projectionVersion != pinned) throw LedgerAssistantScriptFailure("ledger_changed")
                    if (summarize) {
                        Json.encodeToJsonElement(CashLedgerSummary(
                            workspaceId, pinned.toString(), page.filters, page.monthlySummaries, page.pendingItemCount,
                        ))
                    } else buildJsonObject {
                        put("items", Json.encodeToJsonElement(page.items))
                        val next = if ((page.itemsAfter ?: 0) > 0) (position + page.items.size).toString() else null
                        put("next_cursor", next)
                    }
                }
                "ft.transactions.get" -> {
                    requireLedgerArguments(args.size == 1)
                    val id = scriptArgument(args.single())
                    val result = readLedgerAssistantTransaction(reader, id, pinned)
                    (result["error"] as? JsonPrimitive)?.let { throw LedgerAssistantScriptFailure(it.content) }
                    result
                }
                else -> throw LedgerAssistantScriptFailure("unknown_method")
            }
        } catch (_: CashProjectionDomainFailure) {
            checkVersion()
            throw LedgerAssistantScriptFailure("invalid_arguments")
        }
        checkVersion()
        return result.withScriptIdentifiers()
    }
}

private val scriptFilterKeys = setOf(
    "date_from", "date_to", "account_id", "counterparty", "category_id", "currency",
    "amount_min", "amount_max", "economic_type", "time_zone_id",
)

internal fun assistantCashFilters(fields: JsonObject): CashFiltersDto = CashFiltersDto(
    dateFrom = fields["date_from"]?.scriptString(), dateTo = fields["date_to"]?.scriptString(),
    accountId = fields["account_id"]?.scriptString(), counterparty = fields["counterparty"]?.scriptString(),
    categoryId = fields["category_id"]?.scriptString(), currency = fields["currency"]?.scriptString(),
    amountMin = fields["amount_min"]?.scriptString(), amountMax = fields["amount_max"]?.scriptString(),
    economicType = fields["economic_type"]?.scriptString(),
)

internal suspend fun readLedgerAssistantTransaction(
    reader: LedgerLocalReader,
    projectionId: String,
    pinnedVersion: Long? = null,
): JsonObject {
    val group = reader.currentGroup(projectionId) ?: return ledgerDetailError("transaction_not_found")
    val records = reader.sourceRecords(projectionId) ?: return ledgerDetailError("transaction_not_found")
    if (pinnedVersion != null && group.projectionVersion != pinnedVersion) return ledgerDetailError("ledger_changed")
    if (records.evidence.projectionVersion != group.projectionVersion) {
        return ledgerDetailError(if (pinnedVersion == null) "transaction_changed" else "ledger_changed")
    }
    return buildJsonObject {
        put("group", Json.encodeToJsonElement(group))
        put("entry", Json.encodeToJsonElement(records.evidence.projection))
        put("records", JsonArray(records.evidence.members.map { member ->
            Json.encodeToJsonElement(member.copy(counterpartyAccount = null))
        }))
    }
}

private fun ledgerDetailError(reason: String) = buildJsonObject { put("error", reason) }

private fun JsonElement.scriptString() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun scriptArgument(value: JsonElement) =
    value.scriptString()?.takeIf { it.length in 1..4096 } ?: throw LedgerAssistantScriptFailure("invalid_arguments")

private fun requireLedgerArguments(valid: Boolean) {
    if (!valid) throw LedgerAssistantScriptFailure("invalid_arguments")
}

private fun unavailableLedger(): Nothing = throw LedgerAssistantScriptFailure("ledger_read_unavailable")

private val scriptLongFields = setOf("id", "projection_version", "row_revision")

private fun JsonElement.withScriptIdentifiers(): JsonElement = when (this) {
    is JsonObject -> JsonObject(mapValues { (key, value) ->
        if (key in scriptLongFields && value is JsonPrimitive && !value.isString && value.longOrNull != null) {
            JsonPrimitive(value.content)
        } else value.withScriptIdentifiers()
    })
    is JsonArray -> JsonArray(map { it.withScriptIdentifiers() })
    else -> this
}
