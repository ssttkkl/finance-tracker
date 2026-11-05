package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class PortfolioHoldingsContractTest {
    @Test fun actualLocalRepositoryAndHoldingsMatchCompleteOriginalResponses() {
        val dir = Path.of(requireNotNull(System.getenv("FT_KOTLIN_HOLDINGS_ORACLE_DIR")))
        val config = Json.parseToJsonElement(dir.resolve("database.json").readText()).jsonObject
        val db = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"))
        val cases = Json.parseToJsonElement(dir.resolve("holdings.json").readText()).jsonArray
        for ((index, caseValue) in cases.withIndex()) {
            val case = caseValue.jsonObject
            db.write { c ->
                val table = com.finance.tracker.backend.persistence.generated.tables.references.LEDGER_SNAPSHOTS
                c.jooq(db.sqlite).update(table).set(table.PAYLOAD, case.getValue("snapshot")).where(table.WORKSPACE_ID.eq("fixture-a")).execute()
            }
            db.read { c ->
                val repository = RelationalPortfolioRepository(CashSqlContext(c, "fixture-a", db.sqlite))
                assertEquals(case["holdings_raw"], repository.loadHoldings(), "holdings raw $index")
                assertEquals(case["portfolio_raw"], repository.loadPortfolio(), "portfolio raw $index")
                assertEquals(case["holdings"], PortfolioQueryService(repository).getHoldings(), "holdings $index")
            }
        }
    }
}
