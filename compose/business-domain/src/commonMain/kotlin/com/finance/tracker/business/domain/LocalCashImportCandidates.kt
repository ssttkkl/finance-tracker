package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import kotlin.time.Instant

/** Read discovery candidates from one complete workspace snapshot, with no IO, mutation or matching. */
fun readLocalCashImportCandidateIds(snapshot: LedgerSnapshotDto, query: CashImportCandidateQuery<Instant>): Set<Long> {
    if (query.windows.isEmpty() && query.hardKeys.isEmpty()) return emptySet()
    val data = snapshot.facts
    val eligible = data.cashTransactions.filter {
        it.recordOrigin in setOf("import", "legacy") && it.state == "posted" && it.deletedAt == null
    }.associateBy { it.id }
    val result = linkedSetOf<Long>()
    val zero = CashAmount.parse("0")
    if (query.recordTypes.isNotEmpty() && query.windows.isNotEmpty()) for (row in eligible.values) {
        if (row.recordType !in query.recordTypes) continue
        val occurredAt = row.occurredAt ?: continue
        val time = parsePythonCashTimestamp(occurredAt)
        if (query.windows.none { (start, end) -> time in start..end }) continue
        val source = row.sourceType
        if (!query.expenseOnly || source.isNullOrBlank() || cashRelationCore.sourceGroup(
                RelationFact("candidate", zero, "", "", billSource = source, source = source)) != "other") result += row.id.toLong()
    }
    val accountParents = data.cashTransactionComponents.filter { it.accountId != null }
        .groupBy({ it.accountId!!.toLong() }, { it.cashTransactionId })
    val bindings = data.cashSourceBindings.groupBy { it.transactionId }
    val platformKeys = mutableMapOf<String, CashPlatformTransactionKeys?>()
    for (request in query.hardKeys) {
        if (cashPlatformSourceFields(request.source) == null) continue
        val transactions = request.expandedTransactionKeys
        val scope = request.scope
        if (scope != null) {
            val identities = transactions.map(::providerIdentityKey).toSet()
            for (binding in data.cashSourceBindings) if (binding.sourceType == request.source &&
                binding.sourceScopeKind == "account" && binding.sourceScopeKey == scope &&
                binding.identityKind == "provider_id" && binding.identityKey in identities && binding.transactionId in eligible)
                result += binding.transactionId.toLong()
        }
        val keys = request.allKeys
        if (request.accounts.isEmpty() || keys.isEmpty()) continue
        val parents = request.accounts.flatMap { accountParents[it].orEmpty() }.toSet()
        for (parent in parents) {
            val row = eligible[parent]?.takeIf { it.sourceType == request.source } ?: continue
            if (scope != null) {
                val accountBindings = bindings[parent].orEmpty().filter { it.sourceScopeKind == "account" }
                if (accountBindings.any { it.sourceScopeKey != scope }) continue
                if (request.source == "wechat" && scope != CashSourceScope.WECHAT_DEFAULT_SCOPE &&
                    accountBindings.none { it.sourceScopeKey == scope }) continue
            }
            val mapped = platformKeys.getOrPut(parent) {
                readCashPlatformTransactionKeys(request.source, decodeLocalCashValue(row.relationMetadata)) {
                    decodeLocalCashValue(row.sourcePayload)
                }
            } ?: continue
            if (mapped.transactionId in keys || mapped.merchantOrderId in keys || request.source == "alipay" &&
                request.transactionKeys.any { key -> mapped.transactionId?.let { it.startsWith(key + "_") || it.startsWith(key + "*") } == true })
                result += parent.toLong()
        }
    }
    return result
}
