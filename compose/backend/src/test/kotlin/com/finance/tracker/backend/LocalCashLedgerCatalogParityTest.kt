package com.finance.tracker.backend

import com.finance.tracker.business.domain.buildLocalCashCategoryDirectory
import com.finance.tracker.business.domain.buildLocalCashAccountReferences
import com.finance.tracker.business.contracts.AccountReferenceDto
import com.finance.tracker.backend.persistence.generated.tables.references.ACCOUNTS
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

/** Explicitly configured, existing synthetic fixture database; no discovery or database startup. */
@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class LocalCashLedgerCatalogParityTest {
    private fun database(): Database {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),
            "database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }

    @Test fun aCompleteSnapshotProducesTheSameCategoryDirectoryAsTheAuthenticatedRead() {
        val database = database()
        for (token in listOf("synthetic-admin", "synthetic-viewer", "synthetic-new-directory")) {
            val snapshot = LedgerReadService(database).snapshot(token)
            assertEquals(ReadServices(database).categories(token),
                buildLocalCashCategoryDirectory(snapshot.facts.cashCategories, snapshot.facts.categoryDirectoryRevision))
        }
    }

    @Test fun snapshotCashAccountReferencesKeepTheOriginalListAndStoredCurrencyShapes() {
        val database = database(); val workspaces = WorkspaceService(database); val accounts = AccountService(database)
        val name = "local-cash-account-parity"
        val workspace = requireNotNull(workspaces.create("synthetic-admin", name).activeWorkspaceId)
        try {
            val ids = listOf("cash", "loan", "lend", "security", "crypto").associateWith { type ->
                accounts.create("synthetic-admin", buildJsonObject { put("name", type); put("type", type) },
                    "catalog:$type").getValue("account_id").jsonPrimitive.content
            }
            accounts.update("synthetic-admin", ids.getValue("loan"), buildJsonObject { put("active", false) }, 1, "catalog:inactive")
            val shapes = listOf("null", "false", "0", "-0.0", "1e-999", "[]", "{}", "\"\"", "\"cß𐐨\"",
                "{\"usd\":null,\"hkd\":false}", "[\"usd\",\" usd \",\"usd\",null,true,false,-0,1.0,1e-6,[\"ß\"],{\"a\":null}]")
            for (shape in shapes) {
                database.write { connection ->
                    assertEquals(1, connection.jooq(database.sqlite).update(ACCOUNTS)
                        .set(ACCOUNTS.CURRENCIES, Json.parseToJsonElement(shape))
                        .where(ACCOUNTS.WORKSPACE_ID.eq(workspace)).and(ACCOUNTS.ID.eq(ids.getValue("cash").toLong())).execute())
                }
                val expected = Json.decodeFromJsonElement<List<AccountReferenceDto>>(accounts.list("synthetic-admin", "cash").getValue("items"))
                val snapshot = LedgerReadService(database).snapshot("synthetic-admin")
                assertEquals(expected, buildLocalCashAccountReferences(snapshot.facts.accounts), shape)
                assertEquals(listOf("cash", "loan", "lend"), expected.map { it.type })
                assertFalse(expected.single { it.type == "loan" }.active)
            }
        } finally {
            workspaces.delete("synthetic-admin", name)
            workspaces.select("synthetic-admin", "fixture-a")
        }
    }
}
