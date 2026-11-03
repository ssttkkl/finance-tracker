package com.finance.tracker.presentation

import com.finance.tracker.domain.ImportAccountListItem

/** 目标账户下拉框的建议排序；只派生展示顺序，不改变账户或选择。 */
internal data class ImportAccountSuggestion(val accountId: String, val rank: Int, val score: Double)

private val accountProductWords = listOf(
    "活期存折", "储蓄卡", "借记卡", "信用卡", "银行卡", "工资卡", "存折", "活期", "定期", "账户", "卡",
)
// 银行名称里的通用词不参与模糊相似度，避免“中国银行/中国建设银行”因共享“中国/银行”误配。
private val accountGenericWords = listOf(
    "股份有限公司", "有限责任公司", "有限公司", "中国", "银行", "分行", "支行", "营业部", "储蓄所",
)
private val accountParenthetical = Regex("\\(([^)]*)\\)")
private val accountDigitRun = Regex("[0-9]{4,}")

private data class AccountNameParts(val normalized: String, val base: String, val distinctive: String, val digits: String)

private fun normalizeAccountName(value: String): String = buildString {
    for (character in value) {
        val normalized = when {
            character == '\u3000' -> ' '
            character.code in 0xFF01..0xFF5E -> (character.code - 0xFEE0).toChar()
            else -> character
        }
        if (!normalized.isWhitespace()) append(normalized.lowercaseChar())
    }
}

private fun removeWords(value: String, words: List<String>): String {
    var text = value
    var changed = true
    while (changed) {
        changed = false
        for (word in words) {
            if (word in text) {
                text = text.replace(word, "")
                changed = true
            }
        }
    }
    return text
}

/** 卡号尾号只取名称末尾或括号内最后一段 4 位以上数字，避免把年份等中部数字当成卡号。 */
private fun accountCardTail(normalized: String): String {
    val trailing = accountDigitRun.findAll(normalized).lastOrNull()?.takeIf { normalized.endsWith(it.value) }
    val inside = accountParenthetical.findAll(normalized).lastOrNull()?.groupValues?.get(1)
        ?.let { content -> accountDigitRun.findAll(content).lastOrNull()?.value }
    return (trailing?.value ?: inside)?.takeLast(4).orEmpty()
}

private fun accountNameParts(value: String): AccountNameParts {
    val normalized = normalizeAccountName(value)
    val digits = accountCardTail(normalized)
    val withoutParenthetical = normalized.replace(accountParenthetical, "")
    val base = removeWords(withoutParenthetical, accountProductWords).filter { it.isLetter() }
    return AccountNameParts(normalized, base, removeWords(base, accountGenericWords), digits)
}

private fun containsBase(longer: String, shorter: String) =
    shorter.length >= 2 && longer.length > shorter.length && longer.contains(shorter)

internal fun importAccountSuggestions(
    sourceName: String,
    accounts: List<ImportAccountListItem>,
    confirmedAccountId: String? = null,
): List<String> {
    val source = accountNameParts(sourceName)
    val ranked = if (source.normalized.isEmpty()) emptyList() else accounts.mapNotNull { account ->
        if (account.id == confirmedAccountId) return@mapNotNull null
        val target = accountNameParts(account.name)
        if (source.digits.isNotEmpty() && target.digits.isNotEmpty() && source.digits != target.digits) return@mapNotNull null
        val rank = when {
            source.normalized == target.normalized -> 0
            source.base.isNotEmpty() && source.base == target.base ->
                if (source.digits.isNotEmpty() && source.digits == target.digits) 1 else 2
            source.base.isEmpty() && source.digits.isNotEmpty() && source.digits == target.digits -> 3
            containsBase(source.base, target.base) || containsBase(target.base, source.base) -> 3
            else -> null
        }
        val score = if (rank == null || rank == 3) jaroWinkler(source.distinctive, target.distinctive) else 1.0
        val fuzzy = source.distinctive.isNotEmpty() && target.distinctive.isNotEmpty() &&
            score >= 0.75 && minOf(source.base.length, target.base.length) >= 3
        if (rank == null && !fuzzy) return@mapNotNull null
        ImportAccountSuggestion(account.id, rank ?: 4, score)
    }
    val ordered = ranked.sortedWith(compareBy<ImportAccountSuggestion>({ it.rank }, { -it.score }, { it.accountId }))
        .map { it.accountId }
    return listOfNotNull(confirmedAccountId?.takeIf { id -> accounts.any { it.id == id } }) + ordered
}

/** 返回 0..1 的 Jaro-Winkler 相似度；共享实现避免为一次下拉引入依赖。 */
internal fun jaroWinkler(first: String, second: String): Double {
    if (first == second) return if (first.isEmpty()) 0.0 else 1.0
    if (first.isEmpty() || second.isEmpty()) return 0.0
    val matchDistance = maxOf(0, maxOf(first.length, second.length) / 2 - 1)
    val firstMatched = BooleanArray(first.length)
    val secondMatched = BooleanArray(second.length)
    var matches = 0
    for (index in first.indices) {
        val start = maxOf(0, index - matchDistance)
        val end = minOf(index + matchDistance + 1, second.length)
        for (candidate in start until end) {
            if (secondMatched[candidate] || first[index] != second[candidate]) continue
            firstMatched[index] = true
            secondMatched[candidate] = true
            matches += 1
            break
        }
    }
    if (matches == 0) return 0.0
    var transpositions = 0
    var candidate = 0
    for (index in first.indices) {
        if (!firstMatched[index]) continue
        while (!secondMatched[candidate]) candidate += 1
        if (first[index] != second[candidate]) transpositions += 1
        candidate += 1
    }
    val matched = matches.toDouble()
    val jaro = (matched / first.length + matched / second.length + (matched - transpositions / 2.0) / matched) / 3.0
    var prefix = 0
    while (prefix < minOf(4, first.length, second.length) && first[prefix] == second[prefix]) prefix += 1
    return jaro + prefix * 0.1 * (1.0 - jaro)
}
