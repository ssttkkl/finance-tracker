package com.finance.tracker.backend

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** 关系匹配的时间、日期、卡尾和镜像图几何规则。 */
internal object CashRelationGeometry {
    fun asDecimal(value: Any?): BigDecimal = when (value) {
        is BigDecimal -> value
        is CashAmount -> BigDecimal(value.toString())
        else -> BigDecimal(value.toString())
    }
    fun absDecimal(value: Any?): BigDecimal = asDecimal(value).abs()
    fun parseDt(value: Any?): Instant = when (value) {
        is Instant -> value
        is java.time.OffsetDateTime -> value.toInstant()
        is java.time.ZonedDateTime -> value.toInstant()
        else -> {
            var text = value?.toString()?.trim().orEmpty()
            if (text.isEmpty()) throw IllegalArgumentException("occurred_at is required")
            if ('T' !in text && ' ' in text) text = text.replaceFirst(" ", "T")
            pythonCashTimestamp(text, allowNaive = true)
        }
    }
    fun timeDeltaSeconds(a: Any?, b: Any?): Long =
        kotlin.math.abs(ChronoUnit.SECONDS.between(parseDt(a), parseDt(b)))
    fun sameCalendarDay(a: Any?, b: Any?): Boolean =
        parseDt(a).atOffset(ZoneOffset.UTC).toLocalDate() == parseDt(b).atOffset(ZoneOffset.UTC).toLocalDate()

    private fun businessRawDateString(fact: RelationFact): String {
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

    fun businessDayUtc(fact: RelationFact): LocalDate? {
        val raw = businessRawDateString(fact)
        if (raw.isEmpty()) return null
        Regex("^([0-9]{4})-([0-9]{2})-([0-9]{2})$").matchEntire(raw)?.let { match ->
            return runCatching { LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(),
                match.groupValues[3].toInt()) }.getOrNull()
        }
        Regex("^([0-9]{4})([0-9]{2})([0-9]{2})$").matchEntire(raw)?.let { match ->
            return runCatching { LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(),
                match.groupValues[3].toInt()) }.getOrNull()
        }
        var text = raw.replace("/", "-")
        if ('T' !in text && ' ' in text) text = text.replaceFirst(" ", "T")
        text = text.substringBefore('.')
        return try {
            val date = if (text.length == 10) LocalDate.parse(text) else
                pythonCashTimestamp(text, allowNaive = true).atOffset(ZoneOffset.UTC).toLocalDate()
            date
        } catch (_: Exception) {
            runCatching { parseDt(raw).atOffset(ZoneOffset.UTC).toLocalDate() }.getOrNull()
        }
    }

    fun factIsBankDateOnly(fact: RelationFact): Boolean {
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
        if (CashRelationCore.sourceGroup(fact) != "bank") return false
        val occurred = fact.occurredAt?.toString().orEmpty()
        return "16:00:00" in occurred || occurred.endsWith("00:00:00") || "T16:00:00" in occurred
    }

    fun sameBusinessDayUtc(a: RelationFact, b: RelationFact): Boolean {
        val left = businessDayUtc(a) ?: return false
        val right = businessDayUtc(b) ?: return false
        return left == right
    }

    fun extractCardTails(text: String?): Set<String> {
        val blob = text.orEmpty()
        val tails = linkedSetOf<String>()
        Regex("(?:尾号|卡号后四位|卡尾|ending)\\s*[:：]?\\s*([0-9]{4})", RegexOption.IGNORE_CASE)
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
            is RelationFact -> value.counterparty to value.note
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

    fun platformScore(fact: RelationFact): Int {
        val source = cashRelationTextBlob(fact.billSource, fact.source)
        if (CashRelationCore.PAYMENT_PLATFORM_SOURCES.any { it in source }) return 2
        if (CashRelationCore.BANK_CHANNEL_SOURCES.any { it in source }) return 1
        return 0
    }

    fun canonicalMirrorFact(facts: List<RelationFact>): RelationFact? {
        if (facts.isEmpty()) return null
        val order = CashRelationCore.relationOrderKey(facts.first())
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
            parent.putIfAbsent(value, value)
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
        for (id in factIds) parent.putIfAbsent(id, id)
        for ((a, b) in acceptedPairs) union(a, b)
        val groups = linkedMapOf<String, MutableSet<String>>()
        for (id in parent.keys.toList()) groups.getOrPut(find(id)) { linkedSetOf() } += id
        return groups.values.toList()
    }
}
