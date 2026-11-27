package com.finance.tracker.cli

import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.CashMaintenanceInput
import com.finance.tracker.business.contracts.CashMonthlySummaryDto
import com.finance.tracker.business.contracts.CashPageDto
import com.finance.tracker.business.domain.CashAmount
import com.finance.tracker.client.CashAssociationQuery
import com.finance.tracker.client.CashLedgerOperations
import com.finance.tracker.client.ClientOperationFailure
import com.finance.tracker.client.NewCashRecord
import com.finance.tracker.client.NewCashTransfer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal suspend fun Suite.cashBenchmarks() {
    select(workspace)
    val snapshot = withClient(origin) { it.ledger(workspace).ledger.snapshot }
    val operations = CashLedgerOperations(snapshot, nodeCalendar)
    val expectedRows = (0 until 3000).map { index ->
        val row = fixture.rows[index]
        val date = (row.date as String).substringBefore('.').replace(' ', 'T') + "+00:00"
        ExpectedCash(row.id as String, row.entryId as String, date, row.amount as String, row.account as String, row.note as String,
            row.source as String?, row.sourceRef as String)
    }.sortedWith(compareByDescending<ExpectedCash> { it.date }.thenByDescending { it.projection })
    val monthly = expectedRows.groupBy { it.date.take(7) }.entries.sortedByDescending { it.key }.associate { (month, rows) ->
        val values = rows.map { integerCents(it.amount) }
        month to (values.filter { it > 0 }.sum() to values.filter { it < 0 }.sum())
    }
    val primary = fixture.rows[0]
    val transactionId = primary.id as String
    val projectionId = primary.entryId as String
    val end = fixture.rows[2999].id as String
    val fixtureSummary = buildJsonObject {
        put("sha256", fixture.cashDigest as String)
        put("rows", 3000)
        put("accounts", 50)
        put("months", 12)
        put("pageSize", 20)
    }
    scenario("cash.first-page") {
        val value = measure { CashLedgerOperations(snapshot, nodeCalendar).page(timeZoneId = "UTC", startIndex = 0) }
        checkPage(value, expectedRows, 0, snapshot.ledgerSeq.toLong())
        checkMonthly(value.monthlySummaries, monthly)
        recordFixture(fixtureSummary)
    }
    scenario("cash.next-page") {
        val bounds = nodeCalendar.bounds(null, null, "UTC")
        val value = measure { operations.page(CashFiltersDto(), bounds, cursor = null, startIndex = 20,
            expectedLedgerSeq = snapshot.ledgerSeq.toLong(), limit = 20) }
        checkPage(value, expectedRows, 20, snapshot.ledgerSeq.toLong())
    }
    scenario("cash.filter") {
        val value = measure { operations.page(CashFiltersDto(accountId = account), timeZoneId = "UTC", startIndex = 0) }
        val filtered = expectedRows.filter { it.account == account }
        check(filtered.size == 60)
        checkPage(value, filtered, 0, snapshot.ledgerSeq.toLong())
    }
    scenario("cash.summary") {
        val value = measure { CashLedgerOperations(snapshot, nodeCalendar).summary(timeZoneId = "UTC") }
        check(value.months.size == 12 && value.workspaceId == workspace && value.ledgerSeq == snapshot.ledgerSeq)
        check(value.pendingItemCount == 0L)
        checkMonthly(value.months, monthly)
        recordFixture(fixtureSummary)
    }
    scenario("cash.detail") {
        val value = measure { operations.record(transactionId, true) }
        check(value.getValue("transaction_id").jsonPrimitive.content == transactionId)
        val fields = value.getValue("fields").jsonObject
        check(cashEquals(fields.getValue("amount").jsonPrimitive.content, primary.amount as String))
        check(fields.getValue("currency").jsonPrimitive.content == "CNY")
    }
    scenario("cash.current-group") {
        val value = measure { operations.currentGroup(projectionId) }
        check(value.projectionVersion.toString() == snapshot.ledgerSeq &&
            value.members.single().transactionId == transactionId && value.members.single().rowRevision == 1L)
    }
    scenario("cash.evidence") {
        val value = measure { operations.evidence(projectionId) }
        check(value.getValue("projection").jsonObject.getValue("projection_id").jsonPrimitive.content == projectionId)
        check(value.getValue("members").jsonArray.size == 1 && value.getValue("accepted_relations").jsonArray.isEmpty())
    }
    val indexed = (0 until 100).map { (fixture.rows[it].id as String) to (fixture.rows[it].entryId as String) }
    scenario("cash.locate") {
        val value = measure { operations.projectionRefs(indexed.map { it.first }) }
        check(value.associate { it.transactionId to it.projectionId } == indexed.toMap())
    }

    val manualPath = "/api/v1/cash-records/commands"
    val transferPath = "/api/v1/cashflow/transfer/commands"
    val manual = NewCashRecord(account, "-19.35", "CNY", "2025-07-17T08:00:00Z", "consumption", note = "Perf new cash")
    val transfer = NewCashTransfer(account, accountId(workspace, 1), "123.45", "CNY", "CNY", "2025-07-17", "08:00")
    scenario("cash.add") {
        warmLedger()
        val before = count("receipts")
        val mark = markRequests()
        val value = withCashWrites(origin) { measure { it.add(workspace, manual) } }
        val inserted = host.newCash(workspace, end)[0]
        check(value.receipt.committed && count("rows") == 3001 && count("receipts") == before + 1)
        check(requestCount(mark, "POST", manualPath) == 1 && inserted.note == manual.note && inserted.account_id == account)
        check(cashEquals(inserted.amount as String, manual.amount) && inserted.currency == "CNY")
        host.discardNewCash(workspace, end)
    }
    scenario("cash.transfer") {
        warmLedger()
        val before = count("receipts")
        val mark = markRequests()
        val value = withCashWrites(origin) { measure { it.transfer(workspace, transfer) } }
        check(value.receipt.committed && count("rows") == 3002 && count("receipts") == before + 1)
        check(requestCount(mark, "POST", transferPath) == 1)
        val inserted = host.newCash(workspace, end)
        val amounts = (0 until 2).associate { i -> (inserted[i].account_id as String) to (inserted[i].amount as String) }
        check(cashEquals(amounts.getValue(transfer.fromAccountId), "-123.45") &&
            cashEquals(amounts.getValue(transfer.toAccountId), "123.45"))
        val links = host.newCashLinks(workspace, end)
        check(links.length == 1 && links[0].kind == "transfer_pair" && links[0].state == "confirmed")
        check(setOf(links[0].a as String, links[0].b as String) == setOf(inserted[0].id as String, inserted[1].id as String))
        host.discardNewCash(workspace, end)
    }
    scenario("cash.pending") {
        warmLedger()
        host.dropReply("POST", manualPath)
        val failure = catchUnknown { withCashWrites(origin) { it.add(workspace, manual) } }
        val command = checkNotNull(failure.operationId)
        val receipt = checkNotNull(host.receiptId(workspace, command) as String?)
        val value = withCashWrites(origin) { measure { it.pending(workspace) } }
        check(value.single().id == command && value.single().kind == "cash_create_manual")
        val mark = markRequests()
        val verified = withCashWrites(origin) { it.resume(workspace, command) }
        check(verified.receipt.receiptId == receipt && requestCount(mark, "POST", manualPath) == 0)
        host.discardNewCash(workspace, end)
    }
    scenario("cash.resume") {
        warmLedger()
        host.dropReply("POST", manualPath)
        val failure = catchUnknown { withCashWrites(origin) { it.add(workspace, manual) } }
        val operation = checkNotNull(failure.operationId)
        val receipt = checkNotNull(host.receiptId(workspace, operation) as String?)
        val before = count("receipts")
        val mark = markRequests()
        val verified = withCashWrites(origin) { measure { it.resume(workspace, operation) } }
        check(verified.receipt.committed && verified.receipt.receiptId == receipt && verified.operationId == operation)
        check(count("receipts") == before && count("rows") == 3001 && requestCount(mark, "POST", manualPath) == 0)
        host.discardNewCash(workspace, end)
    }
    val originalNote = primary.note as String
    scenario("cash.maintenance-preview") {
        val command = editNote(transactionId, "Perf preview $index")
        val value = withMaintenance(origin) { measure { it.preview(workspace, command) } }
        check(value.canCommit && value.planId.isNotBlank() && (host.cashNote(transactionId) as String) == originalNote)
    }
    scenario("cash.maintenance-plan") {
        val expected = withMaintenance(origin) { it.preview(workspace, editNote(transactionId, "Perf plan $index")) }
        val value = withMaintenance(origin) { measure { it.plan(workspace, expected.planId) } }
        check(value.planRevision == expected.planRevision && value.planId == expected.planId && value.canCommit)
    }
    scenario("cash.maintenance-confirm") {
        warmLedger()
        val note = "Perf committed maintenance $index"
        val plan = withMaintenance(origin) { it.preview(workspace, editNote(transactionId, note)) }
        val before = count("receipts")
        val value = withCashWrites(origin) { measure { it.commitMaintenance(workspace, plan.planId, plan.planRevision) } }
        check(value.receipt.committed && value.receipt.planId == plan.planId && count("receipts") == before + 1)
        check((host.cashNote(transactionId) as String) == note)
        restoreNote(transactionId, originalNote)
    }
    scenario("cash.maintenance-resume") {
        warmLedger()
        val note = "Perf resumed maintenance $index"
        val plan = withMaintenance(origin) { it.preview(workspace, editNote(transactionId, note)) }
        val path = "/api/v1/cash-mutations/plans/${encodeComponent(plan.planId)}/commit"
        host.dropReply("POST", path)
        val failure = catchUnknown { withCashWrites(origin) { it.commitMaintenance(workspace, plan.planId, plan.planRevision) } }
        val operation = checkNotNull(failure.operationId)
        val originalReceipt = checkNotNull(host.receiptId(workspace, operation) as String?)
        val before = count("receipts")
        val mark = markRequests()
        val value = withCashWrites(origin) { measure { it.resume(workspace, operation) } }
        check(value.receipt.committed && value.receipt.receiptId == originalReceipt && value.receipt.planId == plan.planId)
        check(count("receipts") == before && requestCount(mark, "POST", path) == 0)
        check((host.cashNote(transactionId) as String) == note)
        restoreNote(transactionId, originalNote)
    }
    scenario("cash.association-candidates") {
        val anchor = fixture.rows[1].id as String
        val expected = fixture.rows[3].id as String
        val lookup = CashAssociationQuery(query = fixture.rows[3].sourceRef as String, amount = fixture.rows[3].amount as String)
        val value = withMaintenance(origin) { measure { it.associationTargets(workspace, anchor, lookup) } }
        check(value.items.single().transactionId == expected && value.items.single().recordOrigin == "import")
    }
}

private data class ExpectedCash(
    val transaction: String, val projection: String, val date: String, val amount: String,
    val account: String, val note: String, val source: String?, val sourceRef: String,
)

private fun checkPage(value: CashPageDto, expected: List<ExpectedCash>, index: Int, version: Long) {
    check(value.totalCount == expected.size && value.startIndex == index && value.projectionVersion == version)
    check(value.items.size == 20)
    for ((actual, row) in value.items.zip(expected.drop(index).take(20))) {
        check(actual.transactionId == row.transaction && actual.projectionId == row.projection && actual.occurredAt == row.date)
        check(integerCents(checkNotNull(actual.amount)) == integerCents(row.amount) && actual.currency == "CNY")
        check(actual.account?.id.toString() == row.account && actual.note == row.note && actual.sourceType == row.source)
        check(actual.recordId == row.sourceRef && actual.memberCount == 1 && actual.state == "posted")
    }
}

private fun checkMonthly(actual: List<CashMonthlySummaryDto>, expected: Map<String, Pair<Int, Int>>) {
    check(actual.map { it.month } == expected.keys.toList()) { "Monthly ordering or range changed" }
    for (month in actual) {
        val totals = month.currencies.single()
        val (income, expense) = expected.getValue(month.month)
        check(totals.currency == "CNY" && integerCents(totals.income) == income && integerCents(totals.expense) == expense) {
            "Wrong monthly totals for ${month.month}: ${totals.income}, ${totals.expense}"
        }
    }
}

private fun integerCents(text: String): Int {
    val absolute = if (text.startsWith('-')) text.drop(1) else text
    val fraction = absolute.substringAfter('.', "").trimEnd('0')
    check(fraction.length <= 2)
    val cents = absolute.substringBefore('.').toInt() * 100 + fraction.padEnd(2, '0').toInt()
    return if (text.startsWith('-')) -cents else cents
}

private suspend fun Suite.warmLedger() = withClient(origin) { it.ledger(workspace) }

private fun Suite.editNote(transaction: String, note: String): CashMaintenanceInput {
    val current = host.cashRevision(transaction)
    return CashMaintenanceInput("edit_entry", transaction, (current.row_revision as Int).toLong(),
        patch = buildJsonObject { put("note", note) }, entryId = current.id as String,
        expectedEntryRevision = (current.revision as Int).toLong())
}

private suspend fun Suite.restoreNote(transaction: String, note: String) {
    val plan = withMaintenance(origin) { it.preview(workspace, editNote(transaction, note)) }
    val value = withCashWrites(origin) { it.commitMaintenance(workspace, plan.planId, plan.planRevision) }
    check(value.receipt.committed && (host.cashNote(transaction) as String) == note)
}

private suspend fun catchUnknown(block: suspend () -> Unit): ClientOperationFailure {
    val failure = runCatching { block() }.exceptionOrNull()
    check(failure is ClientOperationFailure && failure.code == "result_unknown" && failure.commitState == "unknown")
    return failure
}

internal fun cashEquals(actual: String, expected: String): Boolean {
    val left = checkNotNull(CashAmount.parseOrNull(actual))
    val right = checkNotNull(CashAmount.parseOrNull(expected))
    return left.compareTo(right) == 0
}
