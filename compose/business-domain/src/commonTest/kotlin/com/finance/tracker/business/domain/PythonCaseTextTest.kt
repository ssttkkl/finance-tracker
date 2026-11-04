package com.finance.tracker.business.domain

import kotlin.test.*
import kotlinx.serialization.json.*

class PythonCaseTextTest {
    @Test fun contextualSigmaAndSupplementaryCharactersKeepFrozenPythonSemantics() {
        for((input,expected) in listOf("ΟΣʰ" to "οςʰ","ΟΣʰA" to "οσʰa","Σ" to "σ","AΣ" to "aς",
            "A1Σ" to "a1σ","AΣ‿A" to "aς‿a","İ" to "i\u0307","𐐀𐐨" to "𐐨𐐨","\ud800Σ" to "\ud800σ"))
            assertEquals(expected,pythonCashLower(input),input)
        assertEquals("STRASSE FFI",pythonCashUpper("straße ﬃ"))
        assertEquals("strasse σσ",pythonCashCaseFold("Straße Σς"))
        assertEquals("\ud800\udc01\ud800",pythonCashUpper("\ud800\udc01\ud800"))
    }
    @Test fun completeFrozenMappingsAndSigmaRangeBoundariesMatchCPython() {
        val rows=Json.parseToJsonElement(PythonCaseTextOriginalVectors.json).jsonArray
        assertEquals(12625,rows.size)
        for((index,raw) in rows.withIndex()) {
            val row=raw.jsonArray.map {it.jsonPrimitive.content};val value=row[0]
            assertEquals(row[1],pythonCashLower(value),"lower case $index")
            assertEquals(row[2],pythonCashUpper(value),"upper case $index")
            assertEquals(row[3],pythonCashCaseFold(value),"casefold case $index")
        }
    }

}
