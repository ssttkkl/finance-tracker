package com.finance.tracker.business.domain

import java.text.BreakIterator
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalCashMerchantParityTest {
    private val remove = Regex("(?U)[\\W_]+")
    private fun original(value: String?): String = remove.replace(value.orEmpty(), "").lowercase()

    private fun compare(input: String) {
        assertEquals(original(input), normalizeLocalCashMerchant(input), "normalization")
        val text = remove.replace(input, "")
        val reference = BreakIterator.getWordInstance(Locale.ROOT).apply { setText(text) }
        val candidate = LocalCashMerchantBoundary(text)
        val random = Random(text.hashCode())
        val queries = (0..text.length).toList() + (text.length downTo 0).toList() +
            List(text.length + 1) { random.nextInt(text.length + 1) }
        for (query in queries) {
            assertEquals(reference.isBoundary(query), candidate.isBoundary(query), "boundary $query")
            assertEquals(reference.current(), candidate.current, "position $query")
        }
    }

    @Test fun frozenPublicCharacterPropertiesMatchEveryBaselineCodePoint() {
        for (point in 0..0x10ffff) {
            val type = Character.getType(point)
            val expected = when (type) {
                Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(), Character.TITLECASE_LETTER.toInt(),
                Character.MODIFIER_LETTER.toInt(), Character.OTHER_LETTER.toInt(), Character.COMBINING_SPACING_MARK.toInt() -> MerchantWordClass.LETTER
                Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> MerchantWordClass.NUMBER
                Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> MerchantWordClass.MARK
                Character.FORMAT.toInt() -> MerchantWordClass.FORMAT
                Character.CONNECTOR_PUNCTUATION.toInt() -> MerchantWordClass.CONNECTOR
                else -> MerchantWordClass.OTHER
            }
            assertEquals(expected, MerchantCharacterData.baseClass(point), "category $point")
            assertEquals(type == Character.LOWERCASE_LETTER.toInt() || type == Character.UPPERCASE_LETTER.toInt() ||
                type == Character.TITLECASE_LETTER.toInt(), MerchantCharacterData.isLetterCase(point), "letter case $point")
        }
    }

    @Test fun everyRetainedScalarMatchesOriginalContextsAndQueryStates() {
        val word = Regex("(?U)\\w")
        val contexts = listOf("AΣ%s", "AΣ%sA", "A%sΣ", "AΣ‿%sA", "AΣ%s‿A", "一%sΣ\u0301A", "AΣ\u0301%s\u200cA", "ぁ\u3099%sΣ𐀀A")
        for (point in 0..0x10ffff) {
            val atom = String(Character.toChars(point))
            if (point == '_'.code || !word.matches(atom)) continue
            for (context in contexts) compare(context.replace("%s", atom))
        }
    }

    @Test fun seededInputsCoverMultipleSigmasMarksAndSupplementaryCharacters() {
        val pool = intArrayOf(0x3a3, 0x41, 0x5d0, 0x903, 0x10400, 0x10000, 0x31, 0x2160,
            0x10140, 0x301, 0x345, 0x101fd, 0x200c, 0x203f, 0x4e00, 0x3041, 0x30a1, 0x30fc,
            0x3099, 0x24b6, 0x1f130, 0xd800, 0xdc00, 0x5f, 0x21)
        val random = Random(70193621)
        repeat(10000) {
            val text = buildString { repeat(random.nextInt(1, 97)) { appendCodePoint(pool[random.nextInt(pool.size)]) } }
            compare(text)
        }
    }
}
