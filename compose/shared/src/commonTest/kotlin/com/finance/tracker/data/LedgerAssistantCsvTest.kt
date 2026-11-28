package com.finance.tracker.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals

class LedgerAssistantCsvTest {
    @Test
    fun preservesExactDecimalTextAndProtectsSpreadsheetCells() {
        val rows = Json.parseToJsonElement(
            """[["月份","币种","支出"],["2026-11","CNY","-88.0000"],["=1+1","a,b","引\"号\n换行"]]""",
        ) as JsonArray
        val csv = stringifyLedgerAssistantCsv(rows)
        assertEquals(
            "\"月份\",\"币种\",\"支出\"\r\n\"2026-11\",\"CNY\",\"-88.0000\"\r\n\"'=1+1\",\"a,b\",\"引\"\"号\n换行\"\r\n",
            csv,
        )
        val safeRows = Json.parseToJsonElement(
            """[["月份","币种","支出"],["2026-11","CNY","-88.0000"],["'=1+1","a,b","引\"号\n换行"]]""",
        )
        assertEquals(safeRows, parseLedgerAssistantCsv(csv))
    }
}
