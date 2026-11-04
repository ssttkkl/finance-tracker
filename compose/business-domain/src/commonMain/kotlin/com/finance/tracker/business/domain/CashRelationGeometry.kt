package com.finance.tracker.business.domain

/** 日期证据、卡尾、镜像代表与连通分量；时刻对象转换由调用平台适配。 */
class CashRelationGeometryRules<A>(private val core:CashRelationCoreRules<A>,
    private val utcDay:(Any?)->String, private val timeDelta:(Any?,Any?)->Long) {
    fun timeDeltaSeconds(a:Any?,b:Any?)=timeDelta(a,b)
    fun sameCalendarDay(a:Any?,b:Any?)=utcDay(a)==utcDay(b)
    private fun businessRawDateString(fact: RelationFact<A>): String {
        val payload = fact.rawPayload ?: emptyMap()
        for (key in listOf("date", "occurred_at", "交易时间", "交易日期", "记账日期")) {
            val value = payload[key]
            if (value != null && value.toString().isNotEmpty()) return value.toString().trim()
        }
        val occurred = fact.occurredAt
        return if (occurred != null && occurred.toString().isNotEmpty()) occurred.toString().trim() else ""
    }

    fun isDateOnlyBusinessString(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return false
        if (text.length == 10 && Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(text)) return true
        if (Regex("[0-9]{8}").matches(text)) return true
        return Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}[ T](?:00:00:00|00:00)(?:\\.0+)?(?:Z|[+-][0-9]{2}:?[0-9]{2})?")
            .matches(text)
    }

    fun businessDayUtc(fact: RelationFact<A>): String? {
        val raw = businessRawDateString(fact)
        if (raw.isEmpty()) return null
        Regex("^([0-9]{4})-([0-9]{2})-([0-9]{2})$").matchEntire(raw)?.let { match ->
            return runCatching { shiftCashCandidateDay(match.groupValues.drop(1).joinToString("-"),0) }.getOrNull()
        }
        Regex("^([0-9]{4})([0-9]{2})([0-9]{2})$").matchEntire(raw)?.let { match ->
            return runCatching { shiftCashCandidateDay(match.groupValues.drop(1).joinToString("-"),0) }.getOrNull()
        }
        var text = raw.replace("/", "-")
        if ('T' !in text && ' ' in text) text = text.replaceFirst(" ", "T")
        text = text.substringBefore('.')
        return try {
            val date = if (text.length == 10) {
                require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(text))
                shiftCashCandidateDay(text,0)
            } else
                utcDay(text)
            date
        } catch (_: Exception) {
            runCatching { utcDay(raw) }.getOrNull()
        }
    }

    fun factIsBankDateOnly(fact: RelationFact<A>): Boolean {
        val payload = fact.rawPayload ?: emptyMap()
        var raw = ""
        for (key in listOf("date", "交易日期", "记账日期", "occurred_at")) {
            val value = payload[key]
            if (value != null && value.toString().isNotEmpty()) { raw = value.toString().trim(); break }
        }
        if (raw.isNotEmpty()) {
            if (raw.length == 10 && Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(raw)) return true
            if (isDateOnlyBusinessString(raw)) return true
            return false
        }
        if (core.sourceGroup(fact) != "bank") return false
        val occurred = fact.occurredAt?.toString().orEmpty()
        return "16:00:00" in occurred || occurred.endsWith("00:00:00") || "T16:00:00" in occurred
    }

    fun sameBusinessDayUtc(a: RelationFact<A>, b: RelationFact<A>): Boolean {
        val left = businessDayUtc(a) ?: return false
        val right = businessDayUtc(b) ?: return false
        return left == right
    }

    fun extractCardTails(text: String?): Set<String> {
        val blob = text.orEmpty()
        val tails = linkedSetOf<String>()
        Regex("(?:尾号|卡号后四位|卡尾|end[iİı]ng)[ \t\n\u000B\u000C\r]*[:：]?[ \t\n\u000B\u000C\r]*([0-9]{4})", RegexOption.IGNORE_CASE)
            .findAll(blob).forEach { tails += it.groupValues[1] }
        Regex("[*＊]{2,}([0-9]{4})").findAll(blob).forEach { tails += it.groupValues[1] }
        Regex("(?<![0-9])([0-9]{4})(?![0-9])").findAll(blob).forEach { match ->
            val start = maxOf(0, match.range.first - 12)
            val window = blob.substring(start, minOf(blob.length, match.range.last + 5))
            if (listOf("尾号", "卡", "card", "支付", "储蓄", "信用").any { it in window }) tails += match.groupValues[1]
        }
        return tails
    }

    fun mainStyleCrossVerify(left: Any?, right: Any?): Boolean {
        fun parts(value: Any?): Pair<String, String> = when (value) {
            is Map<*, *> -> (value["counterparty"]?.toString().orEmpty()) to (value["note"]?.toString().orEmpty())
            else -> value?.toString().orEmpty() to ""
        }
        val (leftCounterparty, leftDescription) = parts(left)
        val (rightCounterparty, rightDescription) = parts(right)
        val a = leftCounterparty.trimEnd('.', '…')
        val b = rightCounterparty.trimEnd('.', '…')
        if (a.isNotEmpty() && b.isNotEmpty() && (a in b || b in a)) return true
        val da = leftDescription.trimEnd('.', '…')
        val db = rightDescription.trimEnd('.', '…')
        return da.isNotEmpty() && db.isNotEmpty() && (da in db || db in da)
    }

    fun crossKindCompatible(existingKinds: Collection<String>, newKind: String): Boolean {
        val kinds = existingKinds.toSet() + newKind
        return !setOf("transfer_pair", "refund_offset").all { it in kinds }
    }

    fun platformScore(fact: RelationFact<A>): Int {
        val source = cashRelationTextBlob(fact.billSource, fact.source)
        if (core.PAYMENT_PLATFORM_SOURCES.any { it in source }) return 2
        if (core.BANK_CHANNEL_SOURCES.any { it in source }) return 1
        return 0
    }

    fun canonicalMirrorFact(facts: List<RelationFact<A>>): RelationFact<A>? {
        if (facts.isEmpty()) return null
        val order = core.relationOrderKey(facts.first())
        val ranked = facts.sortedWith(Comparator { left, right ->
            val score = platformScore(left).compareTo(platformScore(right))
            if (score != 0) return@Comparator -score
            val text = cashRelationTextBlob(left.counterparty, left.note).length
                .compareTo(cashRelationTextBlob(right.counterparty, right.note).length)
            if (text != 0) return@Comparator -text
            -order.compare(left, right)
        })
        val top = ranked.first()
        if (ranked.size > 1) {
            val second = ranked[1]
            if (platformScore(top) == 2 && platformScore(second) == 2 &&
                cashRelationTextBlob(top.counterparty, top.note).length ==
                cashRelationTextBlob(second.counterparty, second.note).length) return null
        }
        return top
    }

    fun buildMirrorComponents(factIds: Collection<String>, acceptedPairs: List<Pair<String, String>>): List<Set<String>> {
        val parent = linkedMapOf<String, String>()
        fun find(value: String): String {
            if(value !in parent) parent[value]=value
            var current = value
            while (parent.getValue(current) != current) {
                parent[current] = parent.getValue(parent.getValue(current))
                current = parent.getValue(current)
            }
            return current
        }
        fun union(a: String, b: String) {
            val left = find(a); val right = find(b)
            if (left != right) parent[right] = left
        }
        for (id in factIds) if(id !in parent) parent[id]=id
        for ((a, b) in acceptedPairs) union(a, b)
        val groups = linkedMapOf<String, MutableSet<String>>()
        for (id in parent.keys.toList()) groups.getOrPut(find(id)) { linkedSetOf() } += id
        return groups.values.toList()
    }
}

val cashRelationGeometry=CashRelationGeometryRules(cashRelationCore,
    {value ->formatCashCandidateDay(parseRelationTimestampInstant(value).epochSeconds.floorDiv(86400L))},
    ::relationTimeDeltaSeconds)
