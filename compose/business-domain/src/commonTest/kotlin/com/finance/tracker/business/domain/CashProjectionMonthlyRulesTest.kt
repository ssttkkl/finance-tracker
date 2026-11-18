package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class CashProjectionMonthlyRulesTest {
    @Test fun fullPrecisionSumsExceedSingleFactBoundsWithoutRounding() {
        val totals=CashProjectionMonthlyRules<String>{it}.apply {
            add("2026-01","expense","-12345678901234567890.123456789012345678","CNY")
            add("2026-01","expense","-0.000000000000000001","CNY")
            repeat(2){add("2026-01","income","99999999999999999999.999999999999999999","CNY")}
        }.result()
        val money=totals.summaries.single().jsonObject.getValue("currencies").jsonArray.single().jsonObject
        assertEquals("-12345678901234567890.123456789012345679",money.getValue("expense").jsonPrimitive.content)
        assertEquals("199999999999999999999.999999999999999998",money.getValue("income").jsonPrimitive.content)
        assertEquals(4,totals.total)
    }
    @Test fun unknownAndIneligibleRowsOccupyPositionsWithoutStatistics() {
        val totals=CashProjectionMonthlyRules<String>{it}.apply {
            add(null,null,null,null,false,1)
            add("2026-07","income","1","CNY",false,2)
            add("2026-07","internal_transfer","0","CNY")
            add(null,null,null,null,false)
            add("2026-07","expense","-2.00","USD")
            add("2026-06","income","3.000","USD")
        }.result()
        assertEquals(Json.parseToJsonElement("""[{"month":"unknown","start_index":0,"end_index":1},{"month":"2026-07","start_index":1,"end_index":3},{"month":"unknown","start_index":3,"end_index":4},{"month":"2026-07","start_index":4,"end_index":5},{"month":"2026-06","start_index":5,"end_index":6}]"""),totals.positions)
        assertEquals(Json.parseToJsonElement("""[{"month":"2026-07","currencies":[{"currency":"USD","income":"0","expense":"-2"}]},{"month":"2026-06","currencies":[{"currency":"USD","income":"3","expense":"0"}]}]"""),totals.summaries)
        assertEquals(6,totals.total);assertEquals(2,totals.pending)
    }
    @Test fun transferOnlyMonthsAndCurrencyOrderArePreserved() {
        val totals=CashProjectionMonthlyRules<String>{it}.apply {
            add("2026-01","income","1.000"," usd\u001c")
            add("2026-01","income","-1.0","USD")
            add("2026-01","expense","-0.000","CNY")
            add("2026-02","internal_transfer",null,null)
        }.result()
        assertEquals(Json.parseToJsonElement("""[{"month":"2026-02","currencies":[]},{"month":"2026-01","currencies":[{"currency":"CNY","income":"0","expense":"0"},{"currency":"USD","income":"0","expense":"0"}]}]"""),totals.summaries)
    }
    @Test fun monthMappingIsExplicitAndEmptyResultIsStable() {
        val empty=CashProjectionMonthlyRules<String>{error("no input")}.result()
        assertEquals(JsonArray(emptyList()),empty.summaries);assertEquals(JsonArray(emptyList()),empty.positions)
        assertEquals(0,empty.total);assertEquals(0,empty.pending)
        val mapped=CashProjectionMonthlyRules<Int>{if(it==1)"2026-06" else "2026-07"}.apply {
            add(1,"income","1","CNY");add(2,"income","1","CNY")
        }.result()
        assertEquals(listOf("2026-07","2026-06"),mapped.summaries.map{it.jsonObject.getValue("month").jsonPrimitive.content})
        assertFailsWith<IllegalArgumentException>{CashProjectionMonthlyRules<String>{it}.add("2026-01","income",null,"CNY")}
    }
}
