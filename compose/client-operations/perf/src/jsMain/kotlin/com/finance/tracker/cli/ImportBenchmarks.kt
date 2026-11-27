package com.finance.tracker.cli

import com.finance.tracker.business.contracts.CashImportAccountInput
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.CashImportResultContent
import com.finance.tracker.business.domain.ParsedImportFile
import com.finance.tracker.business.domain.captureCashStatement
import com.finance.tracker.business.domain.parseLocalCashCsvStatement
import com.finance.tracker.business.domain.sha256Hex
import com.finance.tracker.business.domain.toParsedImportFile
import com.finance.tracker.client.CashAccountCurrenciesResult
import com.finance.tracker.client.CashAccountType
import com.finance.tracker.client.CashImportReviewOperations
import com.finance.tracker.client.ClientCashImportResume
import com.finance.tracker.client.ClientOperationFailure
import com.finance.tracker.client.PreparedCashImport
import com.finance.tracker.client.rebaseCashImportReview
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@JsModule("./cli-fixture.mjs")
private external object ImportFixture {
    fun ordinaryImportSources(): Array<Array<String>>
    fun ordinaryLedgerCsv(): Array<String>
}

private const val writeSetPath = "/api/v1/cash-import/write-set"
private val importReceiptPaths =
    listOf("/api/v1/cash-import/write-set/receipt", "/api/v1/cash-mutations/receipts/by-key")

private class Statements(private val suite: Suite) {
    private val rows = ImportFixture.ordinaryImportSources()
    val names = rows.map { it[0] }
    val replacement = "preview-replacement.csv"
    val ledgerName: String
    val fixture: JsonObject

    init {
        rows.forEach { (name, content) -> suite.host.writeFixture(name, content) }
        suite.host.writeFixture(replacement, rows[0][1].replaceFirst("合成日用品", "合成替换日用品"))
        val (name, content) = ImportFixture.ordinaryLedgerCsv()
        suite.host.writeFixture(name, content)
        ledgerName = name
        fixture = buildJsonObject {
            put("ledgerRows", 3000)
            put("sourceFiles", 3)
            put("sourceRows", 1000)
            put("posted", 985)
            put("duplicates", 10)
            put("unpaidClosed", 5)
            put("sha256", sha256Hex((listOf(arrayOf(name, content)) + rows.toList())
                .joinToString("") { (filename, body) -> filename + "\u0000" + body }.encodeToByteArray()))
        }
    }

    fun source(name: String, ref: String = newReference()) = statementSource(suite.fixturePath(name), null, ref)

    suspend fun parsed(prepared: PreparedCashImport): List<ParsedImportFile> =
        names.mapIndexed { i, name ->
            val file = source(name, prepared.files[i].ref).read()
            captureCashStatement(file.ref, file.name, file.mediaType, file.modifiedAtMillis, file.source,
                file.bytes, parseLocalCashCsvStatement(file.bytes)).toParsedImportFile()
        }
}

private suspend fun Suite.prepare(
    workspace: String,
    statements: Statements,
    names: List<String> = statements.names,
): PreparedCashImport = withCashImport(origin) { imports ->
    imports.prepare(workspace, names.map { statements.source(it) })
}

private fun checkPrepared(batch: PreparedCashImport, statements: Statements) {
    check(batch.files.map { it.name } == statements.names && batch.files.all { it.status == "parsed" })
    check(batch.context.files.map { it.recognizedRowCount } == listOf(300, 350, 350))
    check(batch.context.sourceAccounts.size == 1 && batch.context.sessionId == batch.batchId)
}

private suspend fun Suite.mapping(workspace: String, batch: PreparedCashImport, accountId: String): List<CashImportAccountInput> {
    val account = withClient(origin) { it.account(workspace, accountId) }
    return batch.context.sourceAccounts.map { CashImportAccountInput(it.sourceAccountRef, accountId, account.accountRevision) }
}

private suspend fun Suite.openReview(
    workspace: String,
    statements: Statements,
    accountId: String,
): CashImportReviewOperations.Review {
    val batch = prepare(workspace, statements)
    val choices = mapping(workspace, batch, accountId)
    return withCashImport(origin) { it.mapAccounts(workspace, batch.batchId, batch.revision, choices, "UTC") }
}

private suspend fun Suite.cancel(workspace: String, batch: PreparedCashImport) {
    withCashImport(origin) { it.cancel(workspace, batch.batchId, batch.revision) }
}

private fun checkReview(review: CashImportReviewOperations.Review, workspace: String, accountId: String) {
    check(review.workspaceId == workspace && review.plan.canCommit)
    check(review.plan.fileResults.map { it.recognizedRowCount } == listOf(300, 350, 350))
    check(review.plan.summary["create_posted"] == 985L)
    check(review.plan.summary["duplicate"] == 10L && review.plan.summary["skip"] == 5L)
    check(review.plan.itemCounts["confirmed"] == 985L)
    val transactions = review.calculation.request.changes.transactions
    check(transactions.size == 985 && transactions.all { it.values.accountId == accountId && it.values.currency == "CNY" })
}

private fun checkCommitted(result: CashImportResultContent, batchId: String) {
    val receipt = result.displayReceipt()
    check(result.batchId == batchId && result.hasServerItems && receipt.committed && receipt.receiptId.isNotEmpty())
    check(receipt.summary["created_posted"] == 985L && receipt.summary["duplicate"] == 10L)
}

private data class ImportWorkspace(val id: String, val account: String)

private suspend fun Suite.inNewWorkspace(
    block: suspend (ImportWorkspace) -> Unit,
) {
    val name = "Perf import " + newReference()
    val id = withClient(origin) { checkNotNull(it.createWorkspace(name).activeWorkspaceId) }
    try {
        val account = withCashWrites(origin) {
            checkNotNull(it.createAccount(id, "Synthetic settlement", CashAccountType.CASH, listOf("CNY")).receipt.account)
        }
        block(ImportWorkspace(id, account.accountId))
    } finally {
        withClient(origin) { it.deleteWorkspace(id, name) }
        select(workspace)
    }
}

private suspend fun Suite.seedLedger(workspace: ImportWorkspace, statements: Statements) {
    val batch = prepare(workspace.id, statements, listOf(statements.ledgerName))
    val choices = mapping(workspace.id, batch, workspace.account)
    val review = withCashImport(origin) { it.mapAccounts(workspace.id, batch.batchId, batch.revision, choices, "UTC") }
    check(review.plan.summary["create_posted"] == 3000L)
    val written = withCashImport(origin) { it.confirm(workspace.id, batch.batchId, review.plan.sessionRevision) }
    check(written.hasServerItems && written.displayReceipt().summary["created_posted"] == 3000L)
    check(count("rows", workspace.id) == 3000)
}

private suspend fun Suite.editingBenchmarks(ws: ImportWorkspace, files: Statements) {
    val work = ws.id
    scenario("import.batches") {
        val batch = prepare(work, files)
        val listed = measure { withCashImport(origin) { it.batches(work) } }
        val match = listed.single { it.batchId == batch.batchId }
        check(match.revision == batch.revision.toString() && match.fileCount == 3 && match.status == "open")
        cancel(work, batch)
    }
    scenario("import.prepare") {
        val batch = measure { prepare(work, files) }
        checkPrepared(batch, files)
        cancel(work, batch)
    }
    scenario("import.add-file") {
        val batch = prepare(work, files, files.names.take(2))
        val changed = measure { withCashImport(origin) {
            it.addFiles(work, batch.batchId, batch.revision, listOf(files.source(files.names.last())))
        } }
        check(changed.batchId == batch.batchId && changed.revision == batch.revision + 1)
        check(changed.files.take(2) == batch.files)
        checkPrepared(changed, files)
        cancel(work, changed)
    }
    scenario("import.replace-file") {
        val batch = prepare(work, files)
        val changed = measure { withCashImport(origin) {
            it.replaceFile(work, batch.batchId, batch.revision, files.source(files.replacement, batch.files.first().ref))
        } }
        check(changed.batchId == batch.batchId && changed.revision == batch.revision + 1)
        check(changed.files.first().ref == batch.files.first().ref && changed.files.first().name == files.replacement)
        check(changed.files.first().status == "parsed" && changed.files.drop(1) == batch.files.drop(1))
        check(changed.context.files.sumOf { checkNotNull(it.recognizedRowCount) } == 1000)
        cancel(work, changed)
    }
    scenario("import.remove-file") {
        val batch = prepare(work, files)
        val changed = measure { withCashImport(origin) { it.removeFile(work, batch.batchId, batch.revision, batch.files.first().ref) } }
        check(changed.batchId == batch.batchId && changed.revision == batch.revision + 1)
        check(changed.files == batch.files.drop(1))
        cancel(work, changed)
    }
    scenario("import.map-accounts") {
        val batch = prepare(work, files)
        val choices = mapping(work, batch, ws.account)
        val result = measure { withCashImport(origin) { it.mapAccounts(work, batch.batchId, batch.revision, choices, "UTC") } }
        checkReview(result, work, ws.account)
        cancel(work, batch.copy(revision = result.plan.sessionRevision))
    }
    scenario("import.cancel") {
        val batch = prepare(work, files)
        val mark = markRequests()
        measure { cancel(work, batch) }
        check(withCashImport(origin) { it.batches(work) }.none { it.batchId == batch.batchId })
        check(writeCount(mark) == 0)
    }
}

private suspend fun Suite.reviewBenchmarks(ws: ImportWorkspace, files: Statements) {
    val work = ws.id
    var opened: CashImportReviewOperations.Review? = null
    suspend fun plan() = opened ?: openReview(work, files, ws.account).also {
        checkReview(it, work, ws.account)
        opened = it
    }
    scenario("import.review") {
        recordFixture(files.fixture)
        val original = plan()
        val reviewed = measure { withCashImport(origin) { it.review(work, original.plan.sessionId, "UTC") } }
        checkReview(reviewed, work, ws.account)
        check(reviewed.plan.planId == original.plan.planId && reviewed.plan.sessionRevision == original.plan.sessionRevision)
    }
    scenario("import.items") {
        val original = plan()
        val first = original.items(null, 20)
        val next = measure { original.items(checkNotNull(first.nextCursor), 20) }
        check(next.items.size == 20 && next.items.map { it.previewItemId }.toSet().size == 20)
        check(next.items.none { item -> first.items.any { it.previewItemId == item.previewItemId } })
    }
    scenario("import.item") {
        val original = plan()
        val first = original.plan.items.first()
        val detail = measure { original.item(first.previewItemId) }
        check(detail.previewItemId == first.previewItemId && detail.members.single().rowRef == first.memberRefs.single().rowRef)
        check(detail.members.single().plannedAction == "create_posted")
    }
    scenario("import.processing") {
        val original = plan()
        val processing = measure { original.processing(null, 20) }
        check(processing.nextCursor == null && processing.items.size == 15)
        check(processing.items.count { it.plannedAction == "duplicate_in_batch" } == 10)
        check(processing.items.count { it.plannedAction == "skip" && it.code == "unpaid_closed" } == 5)
        check(processing.items.map { checkNotNull(it.rowRef) }.toSet().size == 15)
    }
    scenario("import.result-input") {
        val original = plan()
        val raw = measure { original.resultInput() }
        val value = raw.content()
        val processing = original.processing(null, 20)
        check(value.getValue("files").jsonArray.size == 3)
        val refs = value.getValue("rows").jsonArray.map { it.jsonObject.getValue("row_ref").jsonPrimitive.content }
        check(refs.size == 15 && refs.toSet() == processing.items.mapNotNull { it.rowRef }.toSet())
    }
    var rebaseInput: Triple<List<ParsedImportFile>, LedgerSnapshotDto, CashImportReviewOperations.Review>? = null
    scenario("import.rebase") {
        val (parsed, snapshot, initial) = rebaseInput ?: run {
            val batch = prepare(work, files)
            val choices = mapping(work, batch, ws.account)
            val review = withCashImport(origin) { it.mapAccounts(work, batch.batchId, batch.revision, choices, "UTC") }
            val parsedFiles = files.parsed(batch)
            withCashWrites(origin) { it.createAccount(work, "Unrelated synthetic account", CashAccountType.CASH, listOf("CNY")) }
            val fresh = withClient(origin) { it.ledger(work).ledger.snapshot }
            Triple(parsedFiles, fresh, review).also { rebaseInput = it }
        }
        val original = initial.calculation.request
        val rebased = measure { rebaseCashImportReview(parsed, snapshot, initial.calculation.review) {} }
        check(rebased.baseLedgerSeq == snapshot.ledgerSeq && rebased.baseLedgerSeq.toLong() > original.baseLedgerSeq.toLong())
        check(rebased.changes.transactions == original.changes.transactions)
    }
}

private suspend fun Suite.accountImportBenchmarks(files: Statements) {
    scenario("import.create-account") {
        inNewWorkspace { ws ->
            val batch = prepare(ws.id, files)
            val result = measure { withImportAccounts(origin) {
                it.create(ws.id, batch.batchId, batch.revision, "Extra synthetic account", CashAccountType.CASH, listOf("USD"))
            } }
            val account = checkNotNull(result.receipt.account)
            check(result.receipt.committed && account.currencies == listOf("USD"))
            check(count("accounts", ws.id) == 2)
        }
    }
    scenario("import.extend-account") {
        inNewWorkspace { ws ->
            val batch = prepare(ws.id, files)
            val account = withClient(origin) { it.account(ws.id, ws.account) }
            val result = measure { withImportAccounts(origin) {
                it.ensureCurrencies(ws.id, batch.batchId, batch.revision, ws.account, account.accountRevision, listOf("CNY", "USD"))
            } } as CashAccountCurrenciesResult.Updated
            val updated = checkNotNull(result.write.receipt.account)
            check(updated.accountId == ws.account && updated.currencies.toSet() == setOf("CNY", "USD"))
            check(updated.accountRevision == account.accountRevision + 1 && count("accounts", ws.id) == 1)
        }
    }
    scenario("import.undo-account") {
        inNewWorkspace { ws ->
            val batch = prepare(ws.id, files)
            val initial = withClient(origin) { it.account(ws.id, ws.account) }
            withImportAccounts(origin) {
                it.ensureCurrencies(ws.id, batch.batchId, batch.revision, ws.account, initial.accountRevision, listOf("USD"))
            }
            val undone = measure { withImportAccounts(origin) { it.undo(ws.id, batch.batchId, batch.revision) } }.single()
            check(undone.accountId == ws.account && undone.status == "restored")
            val account = withClient(origin) { it.account(ws.id, ws.account) }
            check(account.currencies == initial.currencies && account.active == initial.active)
        }
    }
    scenario("import.cancel-keep-accounts") {
        inNewWorkspace { ws ->
            val batch = prepare(ws.id, files)
            val created = withImportAccounts(origin) {
                it.create(ws.id, batch.batchId, batch.revision, "Retained synthetic account", CashAccountType.CASH, listOf("USD"))
            }
            val kept = measure { withImportAccounts(origin) { it.cancel(ws.id, batch.batchId, batch.revision) } }.single()
            check(kept.accountId == created.receipt.account?.accountId && kept.status == "kept")
            check(count("accounts", ws.id) == 2 && withCashImport(origin) { it.batches(ws.id) }.none { it.batchId == batch.batchId })
        }
    }
}

private suspend fun Suite.writeImportBenchmarks(files: Statements) {
    scenario("import.confirm") {
        inNewWorkspace { ws ->
            val review = openReview(ws.id, files, ws.account)
            checkReview(review, ws.id, ws.account)
            val batch = review.plan.sessionId
            val before = count("receipts", ws.id)
            val mark = markRequests()
            val result = measure { withCashImport(origin) { it.confirm(ws.id, batch, review.plan.sessionRevision) } }
            checkCommitted(result, batch)
            check(requestCount(mark, "POST", writeSetPath) == 1)
            check(count("receipts", ws.id) == before + 1 && count("rows", ws.id) == 985)
        }
    }
    scenario("import.resume") {
        inNewWorkspace { ws ->
            val review = openReview(ws.id, files, ws.account)
            val batch = review.plan.sessionId
            val before = count("receipts", ws.id)
            host.dropReply("POST", writeSetPath)
            try {
                withCashImport(origin) { it.confirm(ws.id, batch, review.plan.sessionRevision) }
                error("Missing simulated lost response")
            } catch (failure: ClientOperationFailure) {
                check(failure.code == "result_unknown")
            }
            val original = host.waitForReceipt(ws.id, batch).unsafeCast<kotlin.js.Promise<String>>().value()
            val mark = markRequests()
            val result = measure { withCashImport(origin) { it.resume(ws.id, batch) } } as ClientCashImportResume.Committed
            checkCommitted(result.result, batch)
            check(result.result.displayReceipt().receiptId == original)
            check(requestCount(mark, "POST", writeSetPath) == 0 && importReceiptPaths.sumOf { requestCount(mark, "GET", it) } == 1)
            check(count("receipts", ws.id) == before + 1 && count("rows", ws.id) == 985)
        }
    }
}

internal suspend fun Suite.importBenchmarks() {
    val selection = process.env.FT_PERF_SELECT as? String
    if (selection != null && selection.split(",").none { it.startsWith("import.") }) return
    val files = Statements(this)
    val readScenarios = setOf("batches", "prepare", "add-file", "replace-file", "remove-file", "map-accounts",
        "cancel", "review", "items", "item", "processing", "result-input", "rebase")
    if (selection == null || selection.split(",").any { it.removePrefix("import.") in readScenarios }) {
        inNewWorkspace { ws ->
            seedLedger(ws, files)
            editingBenchmarks(ws, files)
            reviewBenchmarks(ws, files)
        }
    }
    accountImportBenchmarks(files)
    writeImportBenchmarks(files)
    if (selection == null || "import.result" in selection.split(",")) {
        inNewWorkspace { ws ->
            val review = openReview(ws.id, files, ws.account)
            val batch = review.plan.sessionId
            val saved = withCashImport(origin) { it.confirm(ws.id, batch, review.plan.sessionRevision) }
            checkCommitted(saved, batch)
            scenario("import.result") {
                val mark = markRequests()
                val result = measure { withCashImport(origin) { it.result(ws.id, batch) } }
                checkCommitted(result, batch)
                check(result.displayReceipt() == saved.displayReceipt() && writeCount(mark) == 0)
            }
        }
    }
}
