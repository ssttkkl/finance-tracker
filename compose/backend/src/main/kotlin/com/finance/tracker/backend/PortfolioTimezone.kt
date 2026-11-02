package com.finance.tracker.backend

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.serialization.json.*

internal class PortfolioTimezone(name: String) {
    private val rule = rules[names[name]?.jsonPrimitive?.content]?.jsonObject ?: throw IllegalArgumentException("invalid investment timezone")
    private val initial = rule.getValue("initial").jsonPrimitive.int
    private val transitions = rule.getValue("transitions").jsonArray.map { it.jsonArray[0].jsonPrimitive.long to it.jsonArray[1].jsonPrimitive.int }
    private val future = rule.getValue("future").jsonObject
    private val fixed = future["fixed"]?.jsonPrimitive?.int
    private val standard = future["standard"]?.jsonPrimitive?.int ?: fixed!!
    private val daylight = future["daylight"]?.jsonPrimitive?.int ?: standard
    private val offsets = (transitions.map { it.second } + initial + standard + daylight).distinct()
    fun local(instant: Instant) = LocalDateTime.ofInstant(instant, ZoneOffset.ofTotalSeconds(offset(instant.epochSecond)))
    fun fold(instant: Instant): Int {
        val seconds = local(instant).toEpochSecond(ZoneOffset.UTC)
        val valid = validOffsets(seconds)
        return if (valid.size > 1 && seconds - valid.last() == instant.epochSecond) 1 else 0
    }
    fun time(local: LocalDateTime, fold: Int = 0): QuoteTime {
        val seconds = local.toEpochSecond(ZoneOffset.UTC)
        val valid = validOffsets(seconds)
        val chosen = valid.getOrElse(fold) { valid.firstOrNull() ?: gapOffset(seconds, local.year, fold) }
        return QuoteTime(local, ZoneOffset.ofTotalSeconds(chosen))
    }
    private fun validOffsets(seconds: Long) = offsets.filter { offset(seconds - it) == it }.sortedByDescending { it }
    private fun gapOffset(seconds: Long, year: Int, fold: Int): Int {
        val extra = if (fixed != null) emptyList() else (year - 1..year + 1).flatMap(::futureTransitions).filter { transitions.isEmpty() || it.first > transitions.last().first }
        return (transitions + extra).firstNotNullOfOrNull { (at, after) ->
            val before = offset(at - 1)
            if (after > before && seconds >= at + before && seconds < at + after) (if (fold == 0) before else after) else null
        } ?: error("unresolved portfolio timezone")
    }
    private fun offset(seconds: Long): Int {
        if (transitions.isNotEmpty() && seconds <= transitions.last().first) {
            val index = transitions.binarySearchBy(seconds) { it.first }
            return transitions.getOrNull(if (index >= 0) index else -index - 2)?.second ?: initial
        }
        if (fixed != null) return fixed
        val year = LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC).year
        val changes = futureTransitions(year)
        val start = changes[0].first; val end = changes[1].first
        return if (if (start < end) seconds >= start && seconds < end else seconds >= start || seconds < end) daylight else standard
    }
    private fun futureTransitions(year: Int) = listOf(calendar(year, "start") - standard to daylight, calendar(year, "end") - daylight to standard)
    private fun calendar(year: Int, key: String): Long {
        val fields = future.getValue(key).jsonArray.map { it.jsonPrimitive.int }
        val first = LocalDate.of(year, fields[0], 1)
        var day = Math.floorMod(fields[2] - first.dayOfWeek.value % 7, 7) + 1 + (fields[1] - 1) * 7
        if (day > first.lengthOfMonth()) day -= 7
        return first.withDayOfMonth(day).atStartOfDay().toEpochSecond(ZoneOffset.UTC) + fields[3]
    }
    companion object {
        private val data by lazy { Json.parseToJsonElement(requireNotNull(PortfolioTimezone::class.java.getResourceAsStream("/portfolio-timezones.json")).bufferedReader().use { it.readText() }).jsonObject }
        private val names by lazy { data.getValue("names").jsonObject }
        private val rules by lazy { data.getValue("rules").jsonObject }
    }
}
