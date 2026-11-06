package com.finance.tracker.backend

import com.finance.tracker.business.domain.buildLocalCashCategoryDirectory
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Explicitly configured, existing synthetic fixture database; no discovery or database startup. */
@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class LocalCashLedgerCatalogParityTest {
    @Test fun aCompleteSnapshotProducesTheSameCategoryDirectoryAsTheAuthenticatedRead() {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),
            "database.json").readText()).jsonObject
        val database = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        for (token in listOf("synthetic-admin", "synthetic-viewer", "synthetic-new-directory")) {
            val snapshot = LedgerReadService(database).snapshot(token)
            assertEquals(ReadServices(database).categories(token),
                buildLocalCashCategoryDirectory(snapshot.facts.cashCategories, snapshot.facts.categoryDirectoryRevision))
        }
    }
}
