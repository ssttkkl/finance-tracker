package com.finance.tracker.business.domain

data class CashRelationFundingVersion(val id: Long, val componentId: Long, val investmentEventId: Long, val direction: String)
data class CashRelationIssueVersion(val id: Long, val transactionId: Long, val revision: Long)
data class CashRelationComponentVersion(val id: Long, val transactionId: Long, val accountId: Long?,
    val amount: String?, val currency: String?, val ordinal: Long)
data class CashRelationEntrySnapshot(val transactionId: Long, val entryId: String, val revision: Long, val digest: String)

/** One read context's facts and exact dependency metadata; IO and workspace selection belong to the reader. */
data class CashRelationReadContext<I : Comparable<I>, T : Comparable<T>>(
    val seedIds: Set<Long>, val decisionGroupIds: List<String>, val transactionIds: Set<Long>,
    val facts: List<CashProjectionFact<I, T>>, val components: List<CashRelationEndpoint>, val relations: List<CashRelationGraphEdge>,
    val jointMembers: List<CashJointRelationMember>, val transactionRevisions: List<Pair<Long, Long>>, val activeTransactionIds: Set<Long>,
    val componentVersions: List<CashRelationComponentVersion>, val relationRevisions: List<Pair<Long, Long>>,
    val issueVersions: List<CashRelationIssueVersion>, val entryVersions: List<CashRelationEntrySnapshot>,
    val relationGroups: List<Pair<Long, String>>, val fundingVersions: List<CashRelationFundingVersion>, val fundingComponentIds: Set<String>,
    val sourceOrderKeys: List<Pair<Long, List<String>>> = emptyList(),
)

/** Preserve the original ordered dependency token, including ASCII escaping of each UTF-16 code unit. */
fun cashRelationReadContextToken(context: CashRelationReadContext<*, *>): String {
    val payload = with(context) { listOf(
        transactionRevisions.map { listOf(it.first, it.second) },
        componentVersions.map { listOf(it.id, it.transactionId, it.accountId, it.amount, it.currency, it.ordinal) },
        relationRevisions.map { listOf(it.first, it.second) }, relationGroups.map { listOf(it.first, it.second) },
        fundingVersions.map { listOf(it.id, it.componentId, it.investmentEventId, it.direction) },
        issueVersions.map { listOf(it.id, it.transactionId, it.revision) },
        entryVersions.map { listOf(it.transactionId, it.entryId, it.revision, it.digest) },
        sourceOrderKeys.map { listOf(it.first, it.second) },
        jointMembers.map { listOf(it.id, it.decisionGroupId, it.rowRevision, it.state, it.active) },
    ) }
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> contextQuoted(value)
        is Long, is Int, is Boolean -> value.toString()
        is List<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> error("Unsupported relation context token value")
    }
    return sha256Hex(encode(payload).encodeToByteArray(throwOnInvalidSequence = true))
}

private fun contextQuoted(text: String): String = buildString {
    append('"')
    for (char in text) when (char) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\b' -> append("\\b")
        '\u000C' -> append("\\f")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (char.code < 32 || char.code >= 127) {
            append("\\u"); append(char.code.toString(16).padStart(4, '0'))
        } else append(char)
    }
    append('"')
}
