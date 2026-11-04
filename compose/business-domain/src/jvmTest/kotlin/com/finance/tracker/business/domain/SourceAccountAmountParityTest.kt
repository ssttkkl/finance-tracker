package com.finance.tracker.business.domain

import java.math.BigDecimal
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SourceAccountAmountParityTest {
    private fun check(text: String) {
        val native = runCatching { BigDecimal(text) }
        val shared = runCatching { parseSourceAccountAmount(text) }
        if (native.isSuccess) assertEquals(native.getOrThrow().toString(), shared.getOrThrow().toString(), text)
        else assertIs<NumberFormatException>(shared.exceptionOrNull(), text)
    }
    @Test fun sourceCurrencyUppercaseMatchesOriginalJvmOnEveryCodePoint() {
        for (code in 0..0x10ffff) {
            val text = String(Character.toChars(code))
            assertEquals(text.uppercase(), pythonCashUpper(text), "U+${code.toString(16)}")
        }
    }
    @Test fun allBmpCharactersAndExponentBoundariesMatchOriginalAmountConstructor() {
        for (code in 0..0xffff) {
            check("1${code.toChar()}.20")
            check("1e${code.toChar()}1")
        }
        for (fraction in 0..5) for (exponent in listOf(-2147483649L, -2147483648, -2147483647, -1, 0, 1,
            2147483647, 2147483648, 2147483649, 2147483654, 9999999999, 10000000000))
            for (zero in listOf("0", "1", "-0", "-1"))
                check(zero + if (fraction == 0) "e$exponent" else ".${"0".repeat(fraction)}e$exponent")
        val random = Random(92371)
        repeat(5_000) {
            val value = buildString {
                if (random.nextBoolean()) append('-')
                repeat(random.nextInt(1, 60)) { append(('0'.code + random.nextInt(10)).toChar()) }
                if (random.nextBoolean()) { append('.'); repeat(random.nextInt(0, 60)) { append(('0'.code + random.nextInt(10)).toChar()) } }
                if (random.nextBoolean()) { append('e'); append(random.nextInt(-200, 201)) }
            }
            check(value)
        }
    }
}
