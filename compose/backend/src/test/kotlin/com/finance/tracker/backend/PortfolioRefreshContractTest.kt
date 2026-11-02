package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class PortfolioRefreshContractTest {
    @Test fun originalCompleteMergeOutputsMatch() {
        val cases = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_PORTFOLIO_REFRESH_CONTRACT"))).readText()).jsonArray
        for (case in cases) {
            val row = case.jsonObject
            assertEquals(row.getValue("equal").jsonPrimitive.boolean, portfolioSnapshotsEqual(row["previous"], row["incoming"]), row.getValue("name").jsonPrimitive.content + " equality")
            assertEquals(row["expected"], portfolioMergeLastSuccess(row["previous"]?.takeUnless { it == JsonNull }?.jsonObject, row.getValue("incoming").jsonObject), row.getValue("name").jsonPrimitive.content)
        }
    }
}
