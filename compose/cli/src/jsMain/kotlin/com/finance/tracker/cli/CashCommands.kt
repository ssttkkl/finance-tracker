package com.finance.tracker.cli

import com.finance.tracker.business.contracts.AccountCommandViewDto
import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.domain.page
import com.finance.tracker.client.CashLedgerOperations
import com.finance.tracker.client.ClientCashWriteResult
import com.finance.tracker.client.ClientLedgerRead
import com.finance.tracker.client.ClientPendingWrite
import com.finance.tracker.client.NewCashRecord
import com.finance.tracker.client.NewCashTransfer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal val cashHelp = """
    Usage: ft cash list [--workspace ID] [--timezone ZONE] [--limit N] [--cursor CURSOR]
           ft cash show ID [--workspace ID]
           ft cash summary [--workspace ID] [--timezone ZONE]
    Filters: --date-from, --date-to, --account, --category, --uncategorized, --keyword, --currency,
             --amount-min, --amount-max, --type, --business-label, --composition, --state,
             --confirmation-status, --hide-zero-amount
    For position-based listing, use --start-index and optionally --ledger-seq.
    Add a record with ft cash add --account ID --amount AMOUNT --currency CCY --occurred-at TIME --type TYPE.
    Transfer with ft cash transfer --from-account ID --to-account ID --amount AMOUNT --from-currency CCY --to-currency CCY --date DATE --time TIME.
    Preview an edit with ft cash edit --input FILE and apply it with ft cash commit PLAN --review REV.
    Use ft cash delete ID... --ledger-seq SEQ to review deletion, then add --confirm.
    Use ft cash categorize ID... --ledger-seq SEQ (--category ID | --clear).
    Use ft cash candidates TRANSACTION_ID to search for related records.
    Use ft cash pending to find unfinished writes and ft cash resume OPERATION_ID to check the original result.
""".trimIndent()

internal suspend fun cashWrite(action: String?, args: List<String>): TerminalResult {
    val common = setOf("--workspace", "--server")
    val actionOptions = when (action) {
        "add" -> setOf("--account", "--amount", "--currency", "--occurred-at", "--type", "--subtype", "--counterparty", "--note", "--category")
        "transfer" -> setOf("--from-account", "--to-account", "--amount", "--from-currency", "--to-currency", "--date", "--time", "--to-amount", "--note")
        "pending", "resume" -> emptySet()
        "commit" -> setOf("--review")
        else -> throw CliFailure("unknown_command")
    }
    val id = when (action) {
        "resume" -> positional(args, "OPERATION_ID")
        "commit" -> positional(args, "PLAN")
        else -> null
    }
    val options = parseOptions(if (id == null) args else args.drop(1), common + actionOptions)
    val expected = options["--workspace"]
    return withCashWrites(options["--server"]) { operations ->
        if (action == "pending") {
            val items = operations.pending(expected)
            return@withCashWrites TerminalResult(buildJsonObject {
                put("operations", JsonArray(items.map { outputJson.encodeToJsonElement(ClientPendingWrite.serializer(), it) }))
            }, items.joinToString("\n") { "${it.id}  ${it.kind}" })
        }
        val result = when (action) {
            "add" -> operations.add(expected, NewCashRecord(
                options.required("--account"), options.required("--amount"), options.required("--currency"),
                options.required("--occurred-at"), options.required("--type"), options["--subtype"],
                options["--counterparty"], options["--note"], options["--category"],
            ))
            "transfer" -> operations.transfer(expected, NewCashTransfer(
                options.required("--from-account"), options.required("--to-account"), options.required("--amount"),
                options.required("--from-currency"), options.required("--to-currency"), options.required("--date"), options.required("--time"),
                options["--to-amount"], options["--note"],
            ))
            "commit" -> operations.commitMaintenance(expected, checkNotNull(id), options.revision("--review"))
            else -> operations.resume(expected, checkNotNull(id))
        }
        writeResult(result)
    }
}

internal fun writeResult(result: ClientCashWriteResult): TerminalResult = TerminalResult(
    buildJsonObject {
        put("operation_id", result.operationId)
        put("receipt", exactReadIdentifiers(outputJson.encodeToJsonElement(CashMutationReceiptDto.serializer(), result.receipt)))
        result.receipt.account?.let { account ->
            put("account", exactReadIdentifiers(outputJson.encodeToJsonElement(AccountCommandViewDto.serializer(), account)))
        }
    },
    "Receipt ${result.receipt.receiptId}",
)

private val cashFilters = setOf("--date-from", "--date-to", "--account", "--category", "--uncategorized", "--keyword",
    "--currency", "--amount-min", "--amount-max", "--type", "--business-label", "--composition", "--state",
    "--confirmation-status", "--hide-zero-amount", "--timezone")
private val cashPagination = setOf("--limit", "--cursor", "--start-index", "--ledger-seq")

internal suspend fun cash(action: String?, args: List<String>): TerminalResult {
    val common = setOf("--workspace", "--server")
    val id = when (action) {
        "show" -> positional(args, "PROJECTION_ID")
        "record" -> positional(args, "TRANSACTION_ID")
        else -> null
    }
    val options = when (action) {
        "show", "record" -> parseOptions(args.drop(1), common)
        "list" -> parseOptions(args, common + cashFilters + cashPagination)
        "summary" -> parseOptions(args, common + cashFilters)
        else -> throw CliFailure("unknown_command")
    }
    val startIndex = options.integer("--start-index")
    val expectedSeq = options["--ledger-seq"]?.let { it.toLongOrNull() ?: throw CliFailure("invalid_parameter", "--ledger-seq") }
    val limit = options.integer("--limit") ?: 20
    return withClient(options["--server"]) { client ->
        val read = client.ledger(options["--workspace"])
        traceCashPhase("ledger_graph_start")
        val cash = CashLedgerOperations(read.ledger.snapshot, nodeCalendar, ::traceCashPhase)
        val filter = filters(options)
        val zone = options["--timezone"]
        when (action) {
            "list" -> {
                val page = cash.page(filter, zone, options["--cursor"], startIndex, expectedSeq, limit)
                traceCashPhase("terminal_output_start")
                val lines = page.items.map { item ->
                    listOfNotNull(item.projectionId, item.occurredAt, item.amount, item.currency, item.counterparty).joinToString("  ")
                }
                val payload = buildJsonObject { put("page", exactReadIdentifiers(outputJson.encodeToJsonElement(com.finance.tracker.business.contracts.CashPageDto.serializer(), page))) }
                read.result(payload, lines.joinToString("\n").ifEmpty { "No cash records." }).also { traceCashPhase("terminal_output_ready") }
            }
            "record" -> {
                val record = cash.record(checkNotNull(id), read.canWrite)
                val detail = listOfNotNull(record["id"]?.jsonPrimitive?.content, record["amount"]?.jsonPrimitive?.content,
                    record["currency"]?.jsonPrimitive?.content, record["source_type"]?.jsonPrimitive?.content).joinToString("  ")
                read.result(buildJsonObject { put("record", exactReadIdentifiers(record)) }, detail)
            }
            "show" -> {
                val evidence = cash.evidence(checkNotNull(id))
                val root = evidence.getValue("root_record").jsonObject
                val view = evidence.getValue("projection").jsonObject
                val detail = listOfNotNull(
                    id, view["occurred_at"]?.jsonPrimitive?.content, view["amount"]?.jsonPrimitive?.content,
                    view["currency"]?.jsonPrimitive?.content, view["counterparty"]?.jsonPrimitive?.content,
                ).joinToString("  ")
                val source = listOfNotNull(root["source_type"]?.jsonPrimitive?.content, view["record_id"]?.jsonPrimitive?.content)
                    .joinToString("  ")
                read.result(buildJsonObject { put("evidence", exactReadIdentifiers(evidence)) }, "$detail\nSource  $source")
            }
            else -> {
                val summary = cash.summary(filter, zone)
                val lines = summary.months.flatMap { month -> month.currencies.map {
                    "${month.month}  ${it.currency}  Income ${it.income}  Expense ${it.expense}"
                } }
                read.result(outputJson.encodeToJsonElement(com.finance.tracker.client.CashLedgerSummary.serializer(), summary).jsonObject,
                    lines.joinToString("\n").ifEmpty { "No monthly totals." })
            }
        }
    }
}

private fun ClientLedgerRead.result(payload: JsonObject, detail: String) = TerminalResult(
    buildJsonObject {
        payload.forEach { (key, value) -> put(key, value) }
        put("workspace_id", ledger.snapshot.workspaceId)
        put("ledger_seq", ledger.snapshot.ledgerSeq)
        put("synced_at", ledger.syncedAt)
        put("offline", offline)
    },
    "$detail\n${if (offline) "Offline. " else ""}Last synced ${ledger.syncedAt}",
)

private fun filters(options: Map<String, String>) = CashFiltersDto(
    dateFrom = options["--date-from"], dateTo = options["--date-to"], accountId = options["--account"],
    counterparty = options["--keyword"], categoryId = options["--category"], uncategorized = options["--uncategorized"],
    currency = options["--currency"], amountMin = options["--amount-min"], amountMax = options["--amount-max"],
    economicType = options["--type"], businessLabel = options["--business-label"], composition = options["--composition"],
    state = options["--state"], confirmationStatus = options["--confirmation-status"], hideZeroAmount = options["--hide-zero-amount"],
)
