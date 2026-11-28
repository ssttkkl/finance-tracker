package com.finance.tracker.cli

import com.finance.tracker.business.contracts.CashImportAccountInput
import com.finance.tracker.business.contracts.CashImportConfirmation
import com.finance.tracker.business.contracts.AccountCommandViewDto
import com.finance.tracker.business.contracts.CashImportArchivePage
import com.finance.tracker.business.contracts.CashImportPlanDto
import com.finance.tracker.business.contracts.CashImportPreviewInput
import com.finance.tracker.business.contracts.CashImportSessionDto
import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.contracts.CashPlanItemDetailDto
import com.finance.tracker.business.contracts.CashPlanItemPageDto
import com.finance.tracker.business.contracts.CashPlanProcessingPageDto
import com.finance.tracker.business.domain.CashImportResultContent
import com.finance.tracker.business.domain.page
import com.finance.tracker.client.CashAccountType
import com.finance.tracker.client.CashAccountCurrenciesResult
import com.finance.tracker.client.CashImportPreparedFile
import com.finance.tracker.client.CashImportReviewOperations
import com.finance.tracker.client.ClientCashImportListing
import com.finance.tracker.client.ClientCashImportResume
import com.finance.tracker.client.ClientImportConfirmationOutcome
import com.finance.tracker.client.ImportAccountChangeResult
import com.finance.tracker.client.PreparedCashImport
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal val importHelp = """
    Usage: ft import batches
           ft import prepare FILE... [--workspace ID] [--source SOURCE]
           ft import file <add|replace|remove> BATCH [FILE_REF] [FILE...] --revision REV
           ft import map BATCH --revision REV --source-account REF --account ID --account-revision REV
           ft import review BATCH [--workspace ID] [--timezone ZONE] [--choices FILE]
           ft import items BATCH [--status confirmed|pending|all] [--cursor CURSOR] [--limit N]
           ft import item BATCH --id ID
           ft import processing BATCH [--cursor CURSOR] [--limit N]
           ft import confirm BATCH --review REV [--no-wait]
           ft import <status|cancel-confirmation|result|resume> BATCH
           ft import account create BATCH --revision REV --name NAME --type TYPE --currency CNY[,USD]
           ft import account currencies BATCH --revision REV --account ID --account-revision REV --currency CNY[,USD]
           ft import account undo BATCH --revision REV
           ft import cancel BATCH --revision REV
""".trimIndent()

internal suspend fun cashImport(action: String?, args: List<String>): TerminalResult {
    val scopeOptions = setOf("--workspace", "--server")
    if (action == "batches") {
        val options = parseOptions(args, scopeOptions)
        return withCashImport(options["--server"]) { operations ->
            val batches = operations.batches(options["--workspace"])
            TerminalResult(buildJsonObject {
                put("batches", JsonArray(batches.map { outputJson.encodeToJsonElement(ClientCashImportListing.serializer(), it) }))
            }, batches.joinToString("\n") { "${it.batchId}  ${it.revision}  ${it.status}  ${it.fileCount}" })
        }
    }
    if (action == "prepare") {
        val paths = args.takeWhile { !it.startsWith("--") }
        if (paths.isEmpty()) throw CliFailure("missing_parameter", "FILE")
        if (paths.size > 20) throw CliFailure("import_payload_invalid")
        val options = parseOptions(args.drop(paths.size), scopeOptions + "--source", setOf("--password-stdin"))
        val password = passwordFromStdin(options)
        if (password != null && paths.size != 1) throw CliFailure("invalid_parameter", "--password-stdin")
        return withCashImport(options["--server"]) { operations ->
            preparedResult(operations.prepare(options["--workspace"],
                paths.map { statementSource(it, options["--source"], password = password) }))
        }
    }

    if (action == "file") return editImportFiles(args, scopeOptions)
    if (action == "account" || action == "cancel") return prepareImportAccounts(action, args, scopeOptions)

    val optionsAllowed = when (action) {
        "review" -> setOf("--timezone", "--choices")
        "items" -> setOf("--cursor", "--limit", "--status", "--timezone")
        "item" -> setOf("--id", "--timezone")
        "processing" -> setOf("--cursor", "--limit", "--timezone")
        "map" -> setOf("--timezone", "--revision", "--source-account", "--account", "--account-revision")
        "confirm" -> setOf("--review")
        "resume", "status", "cancel-confirmation" -> emptySet()
        "result" -> setOf("--kind", "--offset", "--limit")
        else -> throw CliFailure("unknown_command")
    }
    val batch = positional(args, "BATCH")
    val options = parseOptions(args.drop(1), scopeOptions + optionsAllowed, if (action == "confirm") setOf("--no-wait") else emptySet())
    val expected = options["--workspace"]
    val zone = options["--timezone"] ?: "UTC"
    if (action == "result") return withCashImportResult(options["--server"], expected, batch) {
        ImportResultRange(options["--kind"] ?: "row", options.integer("--offset") ?: 0, options.integer("--limit") ?: 50)
    }
    return withCashImport(options["--server"], reportProgress = action in setOf("review", "confirm", "resume")) { operations ->
        when (action) {
            "review" -> {
                val choices = options["--choices"]?.let { decodeInput(CashImportPreviewInput.serializer(), it) }
                operations.reviewForOutput(expected, batch, zone, choices, ::reviewResult)
            }
            "items" -> {
                val review = operations.reviewView(expected, batch, zone)
                val page = review.items(options["--cursor"], options.integer("--limit") ?: 50, options["--status"] ?: "all")
                terminal(CashPlanItemPageDto.serializer(), page, buildString {
                    page.items.forEach { appendLine("${it.previewItemId}  ${it.confirmationStatus}  ${it.display.amount.orEmpty()}  ${it.display.currency.orEmpty()}") }
                    page.nextCursor?.let { appendLine("Next cursor $it") }
                }.trimEnd())
            }
            "item" -> {
                val item = operations.reviewView(expected, batch, zone).item(options.required("--id"))
                terminal(CashPlanItemDetailDto.serializer(), item, item.issues.joinToString("\n") { "${it.code}  ${it.message}" })
            }
            "processing" -> {
                val page = operations.reviewView(expected, batch, zone).processing(options["--cursor"], options.integer("--limit") ?: 50)
                terminal(CashPlanProcessingPageDto.serializer(), page, buildString {
                    page.items.forEach { appendLine("${it.rowRef.orEmpty()}  ${it.plannedAction}  ${it.code.orEmpty()}") }
                    page.nextCursor?.let { appendLine("Next cursor $it") }
                }.trimEnd())
            }
            "map" -> {
                val revision = options.revision("--revision")
                val source = options.required("--source-account")
                val account = options.required("--account")
                reviewResult(operations.mapAccounts(expected, batch, revision,
                    listOf(CashImportAccountInput(source, account, options.revision("--account-revision"))), zone))
            }
            "confirm" -> {
                val revision = options.revision("--review")
                if ("--no-wait" !in options) importResult(operations.confirm(expected, batch, revision))
                else when (val confirmation = operations.acceptConfirmation(expected, batch, revision)) {
                    is ClientImportConfirmationOutcome.Complete -> importResult(confirmation.result)
                    is ClientImportConfirmationOutcome.Observed -> confirmationResult(batch, confirmation.operation)
                }
            }
            "status" -> confirmationResult(batch, operations.confirmationStatus(expected, batch))
            "cancel-confirmation" -> {
                val confirmation = operations.cancelConfirmation(expected, batch)
                confirmationResult(batch, confirmation.operation, confirmation.receipt)
            }
            "resume" -> when (val resumed = operations.resume(expected, batch)) {
                is ClientCashImportResume.Committed -> importResult(resumed.result)
                is ClientCashImportResume.Editable -> preparedResult(resumed.preparation)
            }
            else -> error("unreachable import command")
        }
    }
}

private suspend fun prepareImportAccounts(action: String, args: List<String>, scopeOptions: Set<String>): TerminalResult {
    val command = if (action == "cancel") action else args.firstOrNull()
    val values = if (action == "cancel") args else args.drop(1)
    val extra = when (command) {
        "cancel", "undo" -> emptySet()
        "create" -> setOf("--name", "--type", "--currency")
        "currencies" -> setOf("--account", "--account-revision", "--currency")
        else -> throw CliFailure("unknown_command")
    }
    val batch = positional(values, "BATCH")
    val options = parseOptions(values.drop(1), scopeOptions + extra + "--revision")
    val revision = options.revision("--revision")
    val expected = options["--workspace"]
    return withImportAccounts(options["--server"]) { operations ->
        when (command) {
            "create" -> {
                val type = options.required("--type")
                val kind = CashAccountType.entries.firstOrNull { it.name.lowercase() == type } ?: throw CliFailure("invalid_parameter", "--type")
                writeResult(operations.create(expected, batch, revision, options.required("--name"), kind, options.required("--currency").split(',')))
            }
            "currencies" -> when (val result = operations.ensureCurrencies(expected, batch, revision,
                options.required("--account"), options.revision("--account-revision"), options.required("--currency").split(','))) {
                is CashAccountCurrenciesResult.Updated -> writeResult(result.write)
                is CashAccountCurrenciesResult.Unchanged -> TerminalResult(buildJsonObject {
                    put("changed", false)
                    put("account", exactReadIdentifiers(outputJson.encodeToJsonElement(AccountCommandViewDto.serializer(), result.account)))
                }, "The account already supports these currencies.")
            }
            else -> {
                val changes = if (command == "cancel") operations.cancel(expected, batch, revision) else operations.undo(expected, batch, revision)
                TerminalResult(buildJsonObject {
                    put("batch_id", batch)
                    if (command == "cancel") put("cancelled", true)
                    put("account_changes", JsonArray(changes.map { outputJson.encodeToJsonElement(ImportAccountChangeResult.serializer(), it) }))
                }, buildString {
                    if (command == "cancel") appendLine("Batch $batch cancelled.")
                    changes.forEach { appendLine("${it.accountId.orEmpty()}  ${it.status}  ${it.code.orEmpty()}") }
                }.trimEnd())
            }
        }
    }
}

private suspend fun editImportFiles(args: List<String>, scopeOptions: Set<String>): TerminalResult {
    val action = args.firstOrNull()
    if (action !in setOf("add", "replace", "remove")) throw CliFailure("unknown_command")
    val batch = positional(args.drop(1), "BATCH")
    val fileRef = if (action == "add") null else positional(args.drop(2), "FILE_REF")
    val rest = args.drop(if (fileRef == null) 2 else 3)
    val paths = rest.takeWhile { !it.startsWith("--") }
    val options = parseOptions(rest.drop(paths.size), scopeOptions + setOf("--revision", "--source"), setOf("--password-stdin"))
    val revision = options.revision("--revision")
    val password = passwordFromStdin(options)
    if (password != null && paths.size != 1) throw CliFailure("invalid_parameter", "--password-stdin")
    val expected = options["--workspace"]
    return withCashImport(options["--server"]) { operations ->
        val result = when (action) {
            "add" -> {
                if (paths.isEmpty()) throw CliFailure("missing_parameter", "FILE")
                if (paths.size > 20) throw CliFailure("import_payload_invalid")
                operations.addFiles(expected, batch, revision, paths.map { statementSource(it, options["--source"], password = password) })
            }
            "replace" -> operations.replaceFile(expected, batch, revision,
                statementSource(paths.singleOrNull() ?: throw CliFailure("invalid_parameter", "FILE"), options["--source"], checkNotNull(fileRef), password))
            else -> {
                if (paths.isNotEmpty()) throw CliFailure("invalid_parameter", "FILE")
                operations.removeFile(expected, batch, revision, checkNotNull(fileRef))
            }
        }
        preparedResult(result)
    }
}

private fun preparedResult(result: PreparedCashImport): TerminalResult {
    val files = JsonArray(result.files.map { outputJson.encodeToJsonElement(CashImportPreparedFile.serializer(), it) })
    val context = outputJson.encodeToJsonElement(CashImportSessionDto.serializer(), result.context).jsonObject
    return TerminalResult(buildJsonObject {
        put("batch_id", result.batchId)
        put("revision", result.revision.toString())
        put("files", files)
        put("source_accounts", exactReadIdentifiers(context.getValue("source_accounts")))
        result.interruption?.let { put("interruption", it) }
        if (result.remainingFiles.isNotEmpty()) put("remaining_files", JsonArray(result.remainingFiles.map(::JsonPrimitive)))
    }, buildString {
        appendLine("Batch ${result.batchId}  Revision ${result.revision}")
        result.files.forEach { appendLine("${it.ref}  ${it.name}  ${it.status}${it.code?.let { code -> " ($code)" }.orEmpty()}") }
        result.interruption?.let { appendLine("Preparation paused ($it). Remaining files: ${result.remainingFiles.joinToString(", ")}") }
    }.trimEnd())
}

private fun reviewResult(review: CashImportReviewOperations.Review): TerminalResult =
    reviewResult(review.workspaceId, review.plan, review.calculation.request.baseLedgerSeq)

private fun reviewResult(workspaceId: String, plan: CashImportPlanDto, ledgerSeq: String): TerminalResult {
    val document = exactReadIdentifiers(outputJson.encodeToJsonElement(CashImportPlanDto.serializer(), plan)).jsonObject
    return TerminalResult(buildJsonObject {
        for ((key, value) in document) put(key, value)
        put("workspace_id", workspaceId)
        put("ledger_seq", ledgerSeq)
        put("review_revision", plan.sessionRevision.toString())
    }, buildString {
        appendLine("Batch ${plan.sessionId}  Review ${plan.sessionRevision}")
        for ((key, count) in plan.itemCounts) appendLine("$key  $count")
        for (file in plan.fileResults) appendLine("${file.displayName}  ${file.status}")
    }.trimEnd())
}

private fun confirmationResult(batch: String, value: CashImportConfirmation, receipt: CashMutationReceiptDto? = null): TerminalResult {
    val encoded = outputJson.encodeToJsonElement(CashImportConfirmation.serializer(), value).jsonObject
    val status = encoded.getValue("status")
    return TerminalResult(buildJsonObject {
        put("batch_id", batch)
        encoded.forEach { (key, item) -> put(key, item) }
        receipt?.let { put("receipt", exactReadIdentifiers(outputJson.encodeToJsonElement(CashMutationReceiptDto.serializer(), it))) }
    }, buildString {
        append("Batch $batch  Operation ${value.operationId}  ${status.toString().trim('"')}")
        if (receipt != null) append("  Receipt ${receipt.receiptId}")
    })
}

internal fun validateImportResultRange(kind: String, offset: Int, limit: Int) {
    if (kind != "row" && kind != "relation" || offset < 0 || limit !in 1..200) throw CliFailure("invalid_parameter")
}

internal fun importResult(result: CashImportResultContent, kind: String = "row", offset: Int = 0, limit: Int = 50): TerminalResult {
    validateImportResultRange(kind, offset, limit)
    val page = if (result.hasServerItems) try {
        result.page(kind, offset, limit)
    } catch (_: IllegalArgumentException) {
        throw CliFailure("invalid_parameter", "--offset")
    } else null
    val receipt = result.displayReceipt()
    val output = ImportResultOutput(result.batchId, receipt, result.hasServerItems, page)
    return TerminalResult(importResultText(result.batchId, receipt, result.hasServerItems), output::encode)
}

internal fun importResultText(batchId: String, receipt: CashMutationReceiptDto, complete: Boolean): String = buildString {
    appendLine("Batch $batchId  Receipt ${receipt.receiptId}")
    for ((key, count) in receipt.summary) appendLine("$key  $count")
    if (!complete) appendLine("Saved. Use ft import resume $batchId to finish retrieving results.")
}.trimEnd()

private fun CashMutationReceiptDto.needsExactResultIdentifiers(): Boolean =
    projectionVersion != null || accountRevision != null || account != null ||
        summary.keys.any { it in exactReadIdentifierFields } || legacyByAccount.keys.any { it in exactReadIdentifierFields }

private fun CashMutationReceiptDto.receiptWithExactIdentifiers() =
    exactReadIdentifiers(outputJson.encodeToJsonElement(CashMutationReceiptDto.serializer(), this))

internal fun archivedReceiptJson(receipt: CashMutationReceiptDto): String =
    if (receipt.needsExactResultIdentifiers()) receipt.receiptWithExactIdentifiers().toString()
    else outputJson.encodeToString(CashMutationReceiptDto.serializer(), receipt)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class ImportResultOutput(
    @SerialName("batch_id") val batchId: String,
    val receipt: CashMutationReceiptDto,
    @SerialName("items_complete") val itemsComplete: Boolean,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val page: CashImportArchivePage? = null,
) {
    fun encode(): String {
        if (!receipt.needsExactResultIdentifiers())
            return outputJson.encodeToString(this)
        // Permitted historical receipt metadata still uses the same exact numeric identifier conversion.
        return buildJsonObject {
            put("batch_id", batchId)
            put("receipt", receipt.receiptWithExactIdentifiers())
            put("items_complete", itemsComplete)
            page?.let { put("page", outputJson.encodeToJsonElement(CashImportArchivePage.serializer(), it)) }
        }.toString()
    }
}
