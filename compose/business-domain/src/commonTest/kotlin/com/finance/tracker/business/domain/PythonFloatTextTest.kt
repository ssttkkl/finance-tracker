package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class PythonFloatTextTest {
    @Test
    fun pythonReprVectorsCoverNotationBoundariesAndBinaryNeighbours() {
        // Expected literals from CPython repr(float), keyed by exact IEEE 754 bits.
        val vectors = listOf(
            "0000000000000000" to "0.0",
            "8000000000000000" to "-0.0",
            "7ff0000000000000" to "inf",
            "fff0000000000000" to "-inf",
            "7ff8000000000000" to "nan",
            "3ff0000000000000" to "1.0",
            "3fb999999999999a" to "0.1",
            "3f1a36e2eb1c432d" to "0.0001",
            "3ee4f8b588e368f1" to "1e-05",
            "430c6bf526340000" to "1000000000000000.0",
            "4341c37937e08000" to "1e+16",
            "44b52d02c7e14af6" to "1e+23",
            "3ff3c0ca428c59fb" to "1.2345678901234567",
            "4005666666666666" to "2.675",
            "3ff0000000000001" to "1.0000000000000002",
            "433fffffffffffff" to "9007199254740991.0",
            "4340000000000000" to "9007199254740992.0",
            "0000000000000001" to "5e-324",
            "0000000000000002" to "1e-323",
            "0000000000000003" to "1.5e-323",
            "000fffffffffffff" to "2.225073858507201e-308",
            "0010000000000000" to "2.2250738585072014e-308",
            "0010000000000001" to "2.225073858507202e-308",
            "7fefffffffffffff" to "1.7976931348623157e+308",
            "7feffffffffffffe" to "1.7976931348623155e+308",
            "001fffffffffffff" to "4.4501477170144023e-308",
            "0020000000000000" to "4.450147717014403e-308",
            "0020000000000001" to "4.450147717014404e-308",
            "016fffffffffffff" to "9.332636185032188e-302",
            "0170000000000000" to "9.332636185032189e-302",
            "0170000000000001" to "9.33263618503219e-302",
            "39afffffffffffff" to "7.888609052210117e-31",
            "39b0000000000000" to "7.888609052210118e-31",
            "39b0000000000001" to "7.88860905221012e-31",
            "3c9fffffffffffff" to "1.1102230246251564e-16",
            "3ca0000000000000" to "1.1102230246251565e-16",
            "3ca0000000000001" to "1.1102230246251568e-16",
            "3fdfffffffffffff" to "0.49999999999999994",
            "3fe0000000000000" to "0.5",
            "3fe0000000000001" to "0.5000000000000001",
            "3fefffffffffffff" to "0.9999999999999999",
            "3fffffffffffffff" to "1.9999999999999998",
            "4000000000000000" to "2.0",
            "4000000000000001" to "2.0000000000000004",
            "432fffffffffffff" to "4503599627370495.5",
            "4330000000000000" to "4503599627370496.0",
            "4330000000000001" to "4503599627370497.0",
            "4340000000000001" to "9007199254740994.0",
            "462fffffffffffff" to "1.2676506002282293e+30",
            "4630000000000000" to "1.2676506002282294e+30",
            "4630000000000001" to "1.2676506002282297e+30",
            "7e6fffffffffffff" to "1.0715086071862672e+301",
            "7e70000000000000" to "1.0715086071862673e+301",
            "7e70000000000001" to "1.0715086071862676e+301",
            "7fdfffffffffffff" to "8.988465674311579e+307",
            "7fe0000000000000" to "8.98846567431158e+307",
            "7fe0000000000001" to "8.988465674311582e+307",
        )
        for ((bits, expected) in vectors) {
            val value = Double.fromBits(bits.toULong(16).toLong())
            assertEquals(expected, pythonFloatText(value), bits)
            if (value.isFinite() && value > 0.0) {
                assertEquals("-$expected", pythonFloatText(-value), "negative $bits")
            }
        }
    }

    @Test
    fun signedNanHasTheSamePythonText() {
        assertEquals("nan", pythonFloatText(Double.fromBits(-1L)))
    }
}
