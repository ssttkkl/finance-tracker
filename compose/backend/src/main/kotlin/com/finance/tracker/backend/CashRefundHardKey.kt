package com.finance.tracker.backend

/** Phase A：平台硬键退款提议；纯匹配，不持久化。 */
internal object CashRefundHardKey {
    private fun normalizeRuleId(ruleId: String?): String {
        var value = if (ruleId.isNullOrEmpty()) "scan.platform.refund.v1" else ruleId
        if (value.startsWith("import.")) value = "scan." + value.removePrefix("import.")
        if (!value.startsWith("scan.")) value = "scan.$value"
        return value
    }
    private fun rowSourceBlob(row: Map<String, Any?>): String =
        "${row["bill_source"].toString().orEmpty()} ${row["source"].toString().orEmpty()}".lowercase()
    private fun isAlipay(row: Map<String, Any?>) = "alipay" in rowSourceBlob(row) || "支付宝" in rowSourceBlob(row)
    private fun isWechat(row: Map<String, Any?>) = "wechat" in rowSourceBlob(row) || "微信" in rowSourceBlob(row)
    private fun value(row: Map<String, Any?>, key: String): Any? = row[key]

    private fun proposalPair(expId: String?, refId: String?, exp: RelationFact?, ref: RelationFact?,
        ruleId: String, extras: Map<String, Any?>? = null, allowInvestmentOut: Boolean = false,
        allowTransferReversal: Boolean = false, subtype: String = ""): RelationProposal? {
        if (expId.isNullOrEmpty() || refId.isNullOrEmpty() || expId == refId) return null
        if (exp == null || ref == null) return null
        if (exp.accountId != ref.accountId) return null
        if (exp.currency.uppercase() != ref.currency.uppercase()) return null
        var expenseValid = CashRelationCore.isRefundExpenseCandidate(exp)
        if (!expenseValid && allowInvestmentOut && exp.signedAmount.signum() < 0 && exp.recordType == "investment_out")
            expenseValid = true
        if (!expenseValid && allowTransferReversal && exp.recordType == "transfer_reversal" && exp.signedAmount.signum() < 0)
            expenseValid = true
        var refundValid = CashRelationCore.isRefundIn(ref)
        if (!refundValid && allowTransferReversal && ref.recordType == "transfer_reversal" && ref.signedAmount.signum() > 0)
            refundValid = true
        if (!expenseValid || !refundValid || (!allowTransferReversal && exp.recordType == "transfer_reversal")) return null
        val rule = normalizeRuleId(ruleId)
        val refundAmount = ref.signedAmount.abs()
        val (primary, secondary) = if (exp.signedAmount.signum() < 0 ||
            (exp.signedAmount.signum() <= 0 && ref.signedAmount.signum() >= 0)) expId to refId else refId to expId
        val evidence = RelationEvidence(amountDelta = "0", timeDeltaSeconds = 0, sameCurrency = true, ruleId = rule,
            signals = listOf("phase_a", "platform_hard_key"),
            extras = linkedMapOf<String, Any?>("phase" to "A", "refund_amount" to refundAmount.toPlainString())
                .apply { extras?.let { putAll(it) } })
        return RelationProposal(kind = "refund_offset", primaryFactId = primary, secondaryFactId = secondary,
            primaryFactType = if (primary == expId) exp.factType else ref.factType,
            secondaryFactType = if (secondary == refId) ref.factType else exp.factType,
            subtype = subtype, status = "accepted", ruleId = rule, confidence = RELATION_CONFIDENCE_STRONG,
            evidence = evidence, createdBy = "system", anchorFactId = primary, openLeg = false)
    }

    fun match(detailedRows: List<Map<String, Any?>>, factsById: Map<String, RelationFact>,
        linkedPairs: Set<Pair<String, String>>? = null, remainingByExpense: Map<String, java.math.BigDecimal>? = null): List<RelationProposal> {
        val grouped = linkedMapOf<String, MutableList<Map<String, Any?>>>()
        for (row in detailedRows) {
            val fact = factsById[row["id"]?.toString()] ?: continue
            grouped.getOrPut(fact.sourceIdentity) { mutableListOf() } += row
        }
        val result = mutableListOf<RelationProposal>()
        for (scope in grouped.keys.sortedWith(cashRelationStringOrder)) {
            val rows = grouped.getValue(scope)
            val localFacts = linkedMapOf<String, RelationFact>()
            for (row in rows) factsById[row["id"]?.toString()]?.let { localFacts[row["id"].toString()] = it }
            val remaining = remainingByExpense?.filterKeys { it in localFacts }
            result += matchInScope(rows, localFacts, linkedPairs, remaining)
        }
        return result
    }

    private fun matchInScope(detailedRows: List<Map<String, Any?>>, factsById: Map<String, RelationFact>,
        linkedPairs: Set<Pair<String, String>>?, remainingByExpense: Map<String, java.math.BigDecimal>?): List<RelationProposal> {
        val linked = linkedPairs.orEmpty().map { setOf(it.first, it.second) }.toSet()
        fun already(a: String, b: String) = setOf(a, b) in linked
        val proposals = mutableListOf<RelationProposal>()
        val detailed = detailedRows.filter { it["id"]?.toString().orEmpty().isNotEmpty() }.map { it.toMutableMap() }
        val remaining = remainingByExpense?.toMutableMap() ?: linkedMapOf<String, java.math.BigDecimal>().apply {
            for (fact in factsById.values) if (fact.signedAmount.signum() < 0) put(fact.id, fact.signedAmount.abs())
        }
        val alipayRows = detailed.filter(::isAlipay)
        val origins = mutableListOf<Map<String, Any?>>()
        val refunds = mutableListOf<Map<String, Any?>>()
        for (row in alipayRows) {
            val payload = row["raw_payload"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val status = listOf(row["platform_status"], row["status"], payload["status"], payload["platform_status"])
                .firstOrNull { it != null }?.toString().orEmpty()
            val fact = factsById[row["id"]?.toString()]
            val isRef = fact != null && CashRelationCore.isRefundIn(fact)
            val isExp = fact != null && (CashRelationCore.isRefundExpenseCandidate(fact) ||
                fact.recordType == "investment_out" && fact.signedAmount.signum() < 0)
            if (CashPlatformRefund.alipayIsAuthHold(status)) { origins += row; continue }
            if (CashPlatformRefund.alipayIsUnfreeze(status)) { refunds += row; continue }
            if (isRef) refunds += row else if (isExp) origins += row
        }
        val originTxns = origins.map { it["txn_id"]?.toString() ?: it["record_id"]?.toString().orEmpty() }
        fun candidateOrigins(ref: Map<String, Any?>): List<Int> {
            val refundTxn = ref["txn_id"]?.toString() ?: ref["record_id"]?.toString().orEmpty()
            var hits = originTxns.indices.filter { CashPlatformRefund.alipayOrderMatch(refundTxn, originTxns[it]) }
            val merchantOrder = ref["merchant_order_id"]?.toString().orEmpty().trim()
            if (merchantOrder.isNotEmpty()) {
                val exact = hits.filter { origins[it]["merchant_order_id"]?.toString().orEmpty().trim() == merchantOrder }
                if (exact.isNotEmpty()) hits = exact
            }
            return hits
        }
        for (ref in refunds.sortedWith(compareBy({ it["txn_id"]?.toString() ?: it["record_id"]?.toString().orEmpty() },
            { it["id"].toString() }))) {
            val payload = ref["raw_payload"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val status = listOf(ref["platform_status"], ref["status"], payload["status"]).firstOrNull { it != null }?.toString().orEmpty()
            if (CashPlatformRefund.alipayIsUnfreeze(status)) continue
            val refundAmount = CashPlatformRefund.toDecimal(ref["amount"])?.abs() ?: java.math.BigDecimal.ZERO
            val hits = candidateOrigins(ref).filter { index ->
                refundAmount.signum() > 0 && refundAmount <= (remaining[origins[index]["id"].toString()]
                    ?: factsById.getValue(origins[index]["id"].toString()).signedAmount.abs())
            }
            if (hits.size != 1) continue
            val expenseId = origins[hits.single()]["id"].toString()
            val refundId = ref["id"].toString()
            if (already(expenseId, refundId)) continue
            proposalPair(expenseId, refundId, factsById[expenseId], factsById[refundId],
                "scan.alipay.order_prefix.v1", allowInvestmentOut = true)?.let {
                proposals += it
                remaining[expenseId] = (remaining[expenseId] ?: factsById.getValue(expenseId).signedAmount.abs()) - refundAmount
            }
        }
        val holds = alipayRows.filter { row ->
            val payload = row["raw_payload"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            CashPlatformRefund.alipayIsAuthHold(listOf(row["platform_status"], row["status"], payload["status"])
                .firstOrNull { it != null }?.toString().orEmpty())
        }
        val unfreezes = alipayRows.filter { row ->
            val payload = row["raw_payload"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            CashPlatformRefund.alipayIsUnfreeze(listOf(row["platform_status"], row["status"], payload["status"])
                .firstOrNull { it != null }?.toString().orEmpty())
        }
        fun day(row: Map<String, Any?>) = (row["date"]?.toString() ?: row["occurred_at"]?.toString().orEmpty()).take(10)
        val holdsByDay = holds.groupBy(::day)
        val unfreezesByDay = unfreezes.groupBy(::day)
        for ((dayValue, dayHolds) in holdsByDay) {
            val dayUnfreezes = unfreezesByDay[dayValue].orEmpty()
            if (dayHolds.size == 1 && dayUnfreezes.size == 1) {
                val expenseId = dayHolds.single()["id"].toString(); val refundId = dayUnfreezes.single()["id"].toString()
                if (already(expenseId, refundId)) continue
                proposalPair(expenseId, refundId, factsById[expenseId], factsById[refundId],
                    "scan.alipay.auth_unfreeze.v1")?.let { proposals += it }
            }
        }
        val wechatRows = detailed.filter(::isWechat).map { row ->
            val payload = row["raw_payload"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            row["status"] = row["status"] ?: payload["status"] ?: row["platform_status"] ?: ""
            row["txn_type"] = row["txn_type"] ?: payload["txn_type"] ?: payload["type"] ?: ""
            row["type"] = row["type"] ?: row["txn_type"]
            row["payment_method"] = row["payment_method"] ?: payload["payment_method"] ?: payload["pay"] ?: ""
            row["pay"] = row["payment_method"]
            row["txn_id"] = row["txn_id"] ?: payload["txn_id"] ?: row["record_id"] ?: ""
            row["txn"] = row["txn_id"]
            row["merchant_order_id"] = row["merchant_order_id"] ?: payload["merchant_order_id"] ?: ""
            row["mer"] = row["merchant_order_id"]
            row["record_type"] = row["record_type"]?.toString().orEmpty()
            val fact = factsById[row["id"]?.toString()]
            if (fact != null) {
                if (row["account_id"] == null) row["account_id"] = fact.accountId
                if (row["currency"] == null) row["currency"] = fact.currency
                if (row["record_type"].toString().isEmpty()) row["record_type"] = fact.recordType
                if (row["occurred_at"] == null && row["date"] == null) row["occurred_at"] = fact.occurredAt
            }
            row["direction"] = row["direction"] ?: payload["direction"] ?:
                if ((CashPlatformRefund.toDecimal(row["amount"]) ?: java.math.BigDecimal.ZERO).signum() < 0) "支出" else "收入"
            row["date"] = row["date"] ?: row["occurred_at"]?.toString().orEmpty()
            row
        }
        if (wechatRows.isNotEmpty()) {
            val pairs = CashPlatformRefund.pairWechatTransferReturns(wechatRows) +
                CashPlatformRefund.pairWechatRefunds(wechatRows)
            for ((expenseIndex, incomeIndex, rule) in pairs) {
                val expenseId = wechatRows[expenseIndex]["id"].toString()
                val refundId = wechatRows[incomeIndex]["id"].toString()
                if (already(expenseId, refundId)) continue
                proposalPair(expenseId, refundId, factsById[expenseId], factsById[refundId], rule,
                    allowTransferReversal = rule.endsWith("transfer_return.v1"),
                    subtype = if (rule.endsWith("transfer_return.v1")) "p2p_return" else "")?.let { proposals += it }
            }
        }
        return proposals
    }
}
