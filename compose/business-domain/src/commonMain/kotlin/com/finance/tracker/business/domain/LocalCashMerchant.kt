package com.finance.tracker.business.domain

internal enum class MerchantWordClass {
    LETTER, NUMBER, MARK, FORMAT, CONNECTOR, HAN, HIRAGANA, KATAKANA, KANA_EXTENSION, VOICING_MARK, OTHER,
}

private fun merchantClass(point: Int): MerchantWordClass = when {
    point == 0x3005 || point in 0x4e00..0x9fa5 || point in 0xf900..0xfa2d -> MerchantWordClass.HAN
    point in 0x3041..0x3094 || point in 0x309d..0x309e -> MerchantWordClass.HIRAGANA
    point in 0x30a1..0x30fa || point in 0x30fd..0x30fe -> MerchantWordClass.KATAKANA
    point == 0x3099 || point == 0x309a -> MerchantWordClass.VOICING_MARK
    point == 0x309b || point == 0x309c || point == 0x30fc -> MerchantWordClass.KANA_EXTENSION
    point == 0x30fb -> MerchantWordClass.OTHER
    else -> MerchantCharacterData.baseClass(point)
}

private fun merchantCodePointAt(text: String, index: Int): Int {
    val first = text[index].code
    if (first in 0xd800..0xdbff && index + 1 < text.length) {
        val second = text[index + 1].code
        if (second in 0xdc00..0xdfff) return 0x10000 + ((first - 0xd800) shl 10) + second - 0xdc00
    }
    return first
}

private fun merchantCodePointBefore(text: String, index: Int): Int {
    val last = text[index - 1].code
    if (last in 0xdc00..0xdfff && index >= 2) {
        val first = text[index - 2].code
        if (first in 0xd800..0xdbff) return 0x10000 + ((first - 0xd800) shl 10) + last - 0xdc00
    }
    return last
}

private fun merchantPointWidth(point: Int): Int = if (point > 0xffff) 2 else 1

// This is the reachable legacy Sigma predicate, not a general Unicode Cased API.
private fun merchantContextCased(point: Int): Boolean = MerchantCharacterData.isLetterCase(point) ||
    point in 0x2b0..0x2b8 || point in 0x2c0..0x2c1 || point in 0x2e0..0x2e4 ||
    point == 0x345 || point == 0x37a || point in 0x1d2c..0x1d61 || point in 0x2160..0x217f

/** Original Java word filtering and scalar ROOT mappings; Sigma queries keep original UTF-16 coordinates. */
internal fun normalizeLocalCashMerchant(value: String?): String {
    if (value.isNullOrEmpty()) return ""
    val filtered = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val point = merchantCodePointAt(value, index)
            index += merchantPointWidth(point)
            if (point != '_'.code && SourceAccountCharacterData.wordBefore(value, index)) appendPythonCodePoint(point)
        }
    }
    val cursors = if ('Σ' in filtered) LocalCashMerchantBoundary.prepare(filtered) else null
    return buildString(filtered.length) {
        var index = 0
        while (index < filtered.length) {
            val point = merchantCodePointAt(filtered, index)
            if (point == 0x3a3) append(if (merchantFinalSigma(filtered, index, checkNotNull(cursors).invoke())) '\u03c2' else '\u03c3')
            else {
                val mapping = pythonLowerCase[point]
                if (mapping != null) append(mapping) else appendPythonCodePoint(point)
            }
            index += merchantPointWidth(point)
        }
    }
}

private fun merchantFinalSigma(text: String, index: Int, cursor: LocalCashMerchantBoundary): Boolean {
    var before = index
    while (before > 0) {
        if (cursor.isBoundary(before)) break
        val point = merchantCodePointBefore(text, before)
        before -= merchantPointWidth(point)
        if (merchantContextCased(point)) {
            var after = index + 1
            while (after < text.length) {
                if (cursor.isBoundary(after)) return true
                val next = merchantCodePointAt(text, after)
                if (merchantContextCased(next)) return false
                after += merchantPointWidth(next)
            }
            return true
        }
    }
    return false
}

/** A per-Sigma cursor; only immutable scan results are shared between cursors. */
internal class LocalCashMerchantBoundary private constructor(private val scan: Scan) {
    constructor(text: String) : this(Scan(text))

    var current: Int = 0
        private set
    private var cached = -1

    fun isBoundary(query: Int): Boolean {
        if (query == 0) return true
        val offset = query - 1
        var result = if (cached >= 0 && cached < offset) cached else scan.relocate(offset)
        while (result >= 0 && result <= offset) result = scan.nextBoundary(result)
        current = result
        cached = result
        return result == query
    }

    companion object {
        fun prepare(text: String): () -> LocalCashMerchantBoundary {
            val scan = Scan(text)
            return { LocalCashMerchantBoundary(scan) }
        }
    }

    private class ForwardRow(end: Int) {
        var formats = end
        var marks = end
        var wordLetter = end
        var wordNumber = end
        var han = end
        var hiragana = end
        var katakana = end
        var sharedKana = end
    }

    private class Scan(private val text: String) {
        private val ends = forwardEnds()
        private val relocations = reverseStarts()

        fun nextBoundary(start: Int): Int = if (start >= text.length) -1 else ends[start]
        fun relocate(offset: Int): Int = relocations[offset]

        private fun advance(index: Int): Int = if (index >= text.length) text.length
        else index + merchantPointWidth(merchantCodePointAt(text, index))

        private fun rewind(index: Int): Int = if (index <= 0) -1
        else index - merchantPointWidth(merchantCodePointBefore(text, index))

        // Each residual depends only on the next UTF-16 position (one or two units).
        // Connector lookahead uses an already completed token, so three rows suffice.
        private fun forwardEnds(): IntArray {
            val result = IntArray(text.length)
            val rows = Array(3) { ForwardRow(text.length) }
            for (position in text.indices.reversed()) {
                val kind = merchantClass(merchantCodePointAt(text, position))
                val next = advance(position)
                val following = rows[next % 3]
                val row = rows[position % 3]
                row.formats = if (kind == MerchantWordClass.FORMAT) following.formats else position
                row.marks = if (kind == MerchantWordClass.FORMAT || kind == MerchantWordClass.MARK || kind == MerchantWordClass.VOICING_MARK)
                    following.marks else position
                when (kind) {
                    MerchantWordClass.FORMAT, MerchantWordClass.MARK, MerchantWordClass.VOICING_MARK -> {
                        row.wordLetter = following.wordLetter
                        row.wordNumber = following.wordNumber
                    }
                    MerchantWordClass.LETTER -> {
                        row.wordLetter = following.wordLetter
                        row.wordNumber = following.wordLetter
                    }
                    MerchantWordClass.NUMBER -> {
                        row.wordLetter = following.wordNumber
                        row.wordNumber = following.wordNumber
                    }
                    MerchantWordClass.CONNECTOR -> {
                        val letter = following.formats
                        row.wordLetter = if (letter < text.length && merchantClass(merchantCodePointAt(text, letter)) == MerchantWordClass.LETTER)
                            result[letter] else position
                        row.wordNumber = position
                    }
                    else -> {
                        row.wordLetter = position
                        row.wordNumber = position
                    }
                }
                row.han = if (kind == MerchantWordClass.FORMAT || kind == MerchantWordClass.HAN) following.han else position
                val commonKana = kind == MerchantWordClass.FORMAT || kind == MerchantWordClass.KANA_EXTENSION || kind == MerchantWordClass.VOICING_MARK
                row.hiragana = if (commonKana || kind == MerchantWordClass.HIRAGANA) following.hiragana else position
                row.katakana = if (commonKana || kind == MerchantWordClass.KATAKANA) following.katakana else position
                row.sharedKana = when {
                    commonKana -> following.sharedKana
                    kind == MerchantWordClass.HIRAGANA -> following.hiragana
                    kind == MerchantWordClass.KATAKANA -> following.katakana
                    else -> position
                }
                result[position] = when (kind) {
                    MerchantWordClass.FORMAT -> if (next == text.length) next else result[next]
                    MerchantWordClass.LETTER -> following.wordLetter
                    MerchantWordClass.NUMBER -> following.wordNumber
                    MerchantWordClass.MARK -> following.formats
                    MerchantWordClass.HAN -> maxOf(following.marks, following.han)
                    MerchantWordClass.HIRAGANA -> maxOf(following.marks, following.hiragana)
                    MerchantWordClass.KATAKANA -> maxOf(following.marks, following.katakana)
                    MerchantWordClass.KANA_EXTENSION -> maxOf(following.marks, following.sharedKana)
                    MerchantWordClass.VOICING_MARK -> maxOf(following.formats, following.sharedKana)
                    else -> following.marks
                }
            }
            return result
        }

        private fun suffix(kind: MerchantWordClass): Boolean = kind == MerchantWordClass.MARK ||
            kind == MerchantWordClass.FORMAT || kind == MerchantWordClass.VOICING_MARK

        private fun reverseJoin(left: MerchantWordClass, right: MerchantWordClass): Boolean = when (left) {
            MerchantWordClass.MARK -> suffix(right) || right == MerchantWordClass.LETTER || right == MerchantWordClass.NUMBER || right == MerchantWordClass.CONNECTOR
            MerchantWordClass.VOICING_MARK -> suffix(right) || right != MerchantWordClass.HAN && right != MerchantWordClass.OTHER
            else -> suffix(right) || when (left) {
                MerchantWordClass.LETTER -> right == MerchantWordClass.LETTER || right == MerchantWordClass.NUMBER || right == MerchantWordClass.CONNECTOR
                MerchantWordClass.NUMBER -> right == MerchantWordClass.LETTER || right == MerchantWordClass.NUMBER
                MerchantWordClass.CONNECTOR -> right == MerchantWordClass.LETTER
                MerchantWordClass.KANA_EXTENSION -> right == MerchantWordClass.KANA_EXTENSION || right == MerchantWordClass.HIRAGANA || right == MerchantWordClass.KATAKANA
                else -> if (right == MerchantWordClass.KANA_EXTENSION) left == MerchantWordClass.HIRAGANA || left == MerchantWordClass.KATAKANA
                else left == right && (left == MerchantWordClass.HAN || left == MerchantWordClass.HIRAGANA || left == MerchantWordClass.KATAKANA)
            }
        }

        // A backward residual reads only the previous one or two UTF-16 units.
        // Keep three rows for all right classes, publishing only the initial-class result.
        private fun reverseStarts(): IntArray {
            val result = IntArray(text.length)
            val rows = Array(3) { IntArray(MerchantWordClass.entries.size) }
            for (position in text.indices) {
                val row = rows[position % 3]
                val before = rewind(position)
                if (before < 0) row.fill(0)
                else {
                    // Do not recombine an unpaired high surrogate returned by a backward read.
                    val left = merchantClass(merchantCodePointBefore(text, position))
                    val previous = rows[before % 3]
                    val extra = rewind(before)
                    val stop = if (extra < 0) 0 else advance(advance(extra))
                    for (right in MerchantWordClass.entries) {
                        row[right.ordinal] = when {
                            left == MerchantWordClass.FORMAT -> previous[right.ordinal]
                            reverseJoin(left, right) -> previous[left.ordinal]
                            else -> stop
                        }
                    }
                }
                result[position] = row[merchantClass(merchantCodePointAt(text, position)).ordinal]
            }
            return result
        }
    }
}
