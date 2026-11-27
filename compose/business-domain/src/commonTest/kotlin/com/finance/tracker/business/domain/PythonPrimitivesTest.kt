package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PythonPrimitivesTest {
    @Test
    fun pythonWhitespaceMatchesPythonSet() {
        val expected = intArrayOf(
            0x9, 0xa, 0xb, 0xc, 0xd, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0x85, 0xa0, 0x1680,
            0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009,
            0x200a, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000,
        )
        assertEquals(expected.toList(), pythonWhitespace.toList())
    }

    @Test
    fun codePointCountCountsAstralCharactersOnce() {
        assertEquals(0, "".pythonCodePointCount())
        assertEquals(1, "\uD83D\uDE00".pythonCodePointCount())
        assertEquals(3, "a\uD83D\uDE00b".pythonCodePointCount())
        assertEquals(2, "中文".pythonCodePointCount())
    }

    @Test
    fun stringOrderComparesByCodePoint() {
        assertTrue(pythonStringOrder("a", "b") < 0)
        assertTrue(pythonStringOrder("b", "a") > 0)
        assertEquals(0, pythonStringOrder("同", "同"))
        assertTrue(pythonStringOrder("\uFFFF", "\uD800\uDC00") < 0)
        assertTrue(pythonStringOrder("\uD800\uDC00", "\uFFFF") > 0)
        assertTrue(pythonStringOrder("x\uD800", "x\uD800\uDC00") < 0)
        assertTrue(pythonStringOrder("x\uD800\uDC00", "x\uD800\uDC00a") < 0)
        assertTrue(pythonStringOrder("x\uD83D\uDE00a", "x\uD83D\uDE00b") < 0)
        assertEquals(0, pythonStringOrder("x\uD800\uDC00", "x\uD800\uDC00"))
    }

    @Test
    fun sha256HexMatchesKnownVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(""),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc"),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test fun sha256PaddingMatchesTheByteAndStreamingInputs() {
        for ((count, expected) in listOf(
            55 to "d5e285683cd4efc02d021a5c62014694958901005d6f71e89e0989fac77e4072",
            56 to "04c26261370ee7541549d16dee320c723e3fd14671e66a099afe0a377c16888e",
            63 to "75220b47218278e656f2013bb8f0c455a25eaf01e86c64924e9d48d89776d6f2",
            64 to "7ce100971f64e7001e8fe5a51973ecdfe1ced42befe7ee8d5fd6219506b5393c",
            65 to "9537c5fdf120482f7d58d25e9ed583f52c02b4e304ea814db1633ad565aed7e9",
            129 to "0ec9eb33e74510bcdd1f2ea55206e82f21649c5c2becbf2b433eb475b34c01bd",
        )) {
            assertEquals(expected, sha256Hex("x".repeat(count)))
            assertEquals(expected, sha256HexAscii { append -> repeat(count) { append('x'.code) } })
        }
    }
}
