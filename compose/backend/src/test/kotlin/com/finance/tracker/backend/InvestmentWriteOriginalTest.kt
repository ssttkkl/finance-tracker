package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class InvestmentWriteOriginalTest {
    private fun fixture(block: (Database, String) -> Unit) {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")), "database.json").readText()).jsonObject
        val db = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val spaces = WorkspaceService(db)
        val name = "投资命令原断言"
        val workspace = spaces.create("synthetic-admin", name).activeWorkspaceId!!
        try {
            db.write { c ->
                for ((label, type) in listOf("IBKR" to "security", "broker" to "security", "crypto" to "crypto", "cash" to "cash"))
                    deletionInsert(c, db, "accounts", linkedMapOf("workspace_id" to workspace, "name" to label, "type" to type, "active" to false,
                        "revision" to 1L, "currencies" to JsonArray(if (label == "IBKR") listOf(JsonPrimitive("USD")) else emptyList()),
                        "metadata_json" to buildJsonObject {}, "created_at" to deletionTime, "updated_at" to deletionTime))
            }
            block(db, workspace)
        } finally { spaces.delete("synthetic-admin", name); spaces.select("synthetic-admin", "fixture-a") }
    }
    private fun application(db: Database) = InvestmentCommandApplication { InvestmentWriteService(db).execute("synthetic-admin", it) }
    private fun positions(db: Database, workspace: String, account: String) = db.read {
        CashSqlContext(it, workspace, db.sqlite).snapshot.load().value.getValue("accounts").jsonObject.getValue("security").jsonObject.getValue(account).jsonObject.getValue("positions").jsonObject
    }
    private fun count(db: Database, workspace: String) = db.read { c -> c.prepareStatement("SELECT COUNT(*) FROM investment_events WHERE workspace_id=?").use {
        it.setString(1, workspace); it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
    } }
    private fun ok(result: JsonObject) { assertEquals(JsonPrimitive(true), result["ok"], result.toString()) }
    private fun decimal(expected: String, value: JsonElement?) { assertEquals(0, expected.toBigDecimal().compareTo(value!!.jsonPrimitive.content.toBigDecimal())) }

    @Test fun postgresInvestmentCommandsWriteEventsAndProjectionAtomically() = fixture { db, workspace ->
        val s = application(db)
        ok(s.deposit("100", "USD", "IBKR", "seed", "2026-07-17"))
        ok(s.buy("AAPL.US", "2", "10", "1", "USD", "IBKR", "buy", "2026-07-17"))
        ok(s.sell("AAPL.US", "1", "12", "1", "USD", "IBKR", "sell", "2026-07-17"))
        ok(s.dividend("AAPL.US", "2", "USD", "IBKR", "dividend", "2026-07-17"))
        ok(s.checkinTicker("AAPL.US", "3", "8", "USD", "IBKR", "check", "2026-07-17"))
        ok(s.swap("IBKR", "AAPL.US", "1", "MSFT.US", "2", "USD", "swap", "2026-07-17"))
        ok(s.withdraw("2", "USD", "IBKR", "withdraw", "2026-07-17"))
        ok(s.checkinCash("8.75", "USD", "IBKR", "cash", "2026-07-17"))
        assertEquals(8, count(db, workspace))
        val p = positions(db, workspace, "IBKR")
        assertEquals(JsonPrimitive("8.75"), p.getValue("usd").jsonObject["shares"])
        for ((ticker, shares, cost) in listOf(Triple("aapl.us", "2", "16"), Triple("msft.us", "2", "8"))) {
            val position = p.getValue(ticker).jsonObject
            assertEquals(JsonPrimitive(shares), position["shares"]); decimal(cost, position["total_cost"])
            assertEquals(JsonPrimitive("USD"), position["cost_currency"])
        }
    }
    @Test fun computedProjectionReleasedCostIsQuantizedTo18Dp() = fixture { db, workspace ->
        for (action in listOf("sell", "swap")) {
            db.write { c ->
                c.prepareStatement("DELETE FROM investment_events WHERE workspace_id=?").use { it.setString(1, workspace); it.executeUpdate() }
                CashSqlContext(c, workspace, db.sqlite).snapshot.save(CashSnapshotDocument(Json.parseToJsonElement("""{"accounts":{"security":{"IBKR":{"currency":"USD","positions":{"aapl.us":{"shares":"3","total_cost":"1","cost_currency":"USD"}}}}}}""").jsonObject))
            }
            val s = application(db)
            ok(if (action == "sell") s.sell("AAPL.US", "1", "1", "0", "USD", "IBKR") else s.swap("IBKR", "AAPL.US", "1", "MSFT.US", "1", "USD"))
            assertEquals(1, count(db, workspace))
            val cost = positions(db, workspace, "IBKR").getValue("aapl.us").jsonObject.getValue("total_cost").jsonPrimitive.content.toBigDecimal()
            assertTrue(cost.scale() <= 18)
            assertEquals(0, cost.compareTo("0.666666666666666667".toBigDecimal()))
        }
    }
    @Test fun stockCommandsSmokeBuySellSwapDepositWithdrawDividendCheckin() = fixture { db, workspace ->
        val s = application(db)
        ok(s.deposit("10000", "CNY", "broker", date = "2026-01-01 00:00:00"))
        ok(s.buy("600000.sh", "100", "12.5", "1", "CNY", "broker", date = "2026-01-02 00:00:00"))
        ok(s.dividend("600000.sh", "50", "CNY", "broker", date = "2026-01-03 00:00:00"))
        ok(s.sell("600000.sh", "50", "13", "1", "CNY", "broker", date = "2026-01-04 00:00:00"))
        ok(s.withdraw("100", "CNY", "broker", date = "2026-01-05 00:00:00"))
        ok(s.swap("broker", "cny", "100", "usd", "14", "CNY", date = "2026-01-06 00:00:00", commission = "0"))
        ok(s.checkinCash("9000", "CNY", "broker", date = "2026-01-07 00:00:00"))
        ok(s.checkinTicker("600000.sh", "50", "12.5", "CNY", "broker", date = "2026-01-08 00:00:00"))
        val p = positions(db, workspace, "broker")
        decimal("9000", p.getValue("cny").jsonObject["shares"]); decimal("50", p.getValue("600000.sh").jsonObject["shares"])
        db.read { validateInvestmentSnapshot(CashSqlContext(it, workspace, db.sqlite).snapshot.load().value) }
    }
    @Test fun swapCommissionDefaultsToFromTicker() = fixture { db, workspace ->
        val s = application(db)
        ok(s.deposit("1000", "USD", "broker", date = "2026-01-01 00:00:00"))
        val result = s.swap("broker", "usd", "500", "btc", "0.01", "USD", date = "2026-01-02 00:00:00", commission = "1")
        ok(result)
        val row = result.getValue("details").jsonObject.getValue("row").jsonObject
        assertEquals(JsonPrimitive("1"), row["commission"]); assertEquals(JsonPrimitive("usd"), row["commission_asset"])
        val p = positions(db, workspace, "broker")
        decimal("499", p.getValue("usd").jsonObject["shares"]); decimal("0.01", p.getValue("btc").jsonObject["shares"])
    }
    @Test fun swapThirdPartyCommissionAsset() = fixture { db, workspace ->
        val s = application(db)
        ok(s.deposit("5000", "USD", "broker", date = "2026-01-01 00:00:00"))
        ok(s.checkinTicker("bnb", "10", "300", "USD", "broker", date = "2026-01-01 01:00:00"))
        val result = s.swap("broker", "usd", "5000", "btc", "0.1", "USD", date = "2026-01-02 00:00:00", commission = "0.01", commissionAsset = "bnb")
        ok(result)
        assertEquals(JsonPrimitive("bnb"), result.getValue("details").jsonObject.getValue("row").jsonObject["commission_asset"])
        val p = positions(db, workspace, "broker")
        decimal("9.99", p.getValue("bnb").jsonObject["shares"]); decimal("0.1", p.getValue("btc").jsonObject["shares"])
    }
    private fun state(db: Database) = db.read { c ->
        com.finance.tracker.backend.persistence.generated.DefaultSchema.DEFAULT_SCHEMA.tables.associate { table -> table.name to
            c.createStatement().use { statement -> statement.executeQuery("SELECT * FROM \"" + table.name + "\"").use { rows ->
                buildList { while (rows.next()) add((1..rows.metaData.columnCount).map { rows.getString(it) }) }.sortedBy { it.toString() }
            } }
        }
    }
    private fun observed(db: Database, stage: String, reached: java.util.concurrent.atomic.AtomicInteger): Database {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")), "database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")) { connection ->
            fun execute(sql: String, action: () -> Any?): Any? {
                val normalized = sql.replace("\"", "").replace(96.toChar().toString(), "").trim().lowercase()
                if (stage == "before-commit" && normalized == "commit") { reached.incrementAndGet(); throw java.sql.SQLException("injected commit", "XX000") }
                val result = action()
                if (stage == "after-insert" && normalized.startsWith("insert into investment_events") ||
                    stage == "after-snapshot" && normalized.startsWith("insert into ledger_snapshots")) {
                    reached.incrementAndGet(); throw java.sql.SQLException("injected write", "XX000")
                }
                return result
            }
            java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection::class.java.classLoader, arrayOf(java.sql.Connection::class.java)) { _, method, args ->
                fun call(): Any? = try { method.invoke(connection, *(args ?: emptyArray())) } catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
                if (method.name == "commit") execute("COMMIT", ::call) else {
                    val result = call()
                    if (method.name in setOf("prepareStatement", "createStatement") && result is java.sql.Statement) {
                        val sql = if (method.name == "prepareStatement") args!![0] as String else null
                        val type = if (result is java.sql.PreparedStatement) java.sql.PreparedStatement::class.java else java.sql.Statement::class.java
                        java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, operation, values ->
                            fun invoke(): Any? = try { operation.invoke(result, *(values ?: emptyArray())) } catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
                            if (operation.name in setOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch")) execute(sql ?: values?.firstOrNull() as? String ?: "", ::invoke) else invoke()
                        }
                    } else result
                }
            } as java.sql.Connection
        }
    }
    @Test fun actualEventSnapshotAndCommitFaultsRollbackEveryTable() = fixture { db, _ ->
        ok(application(db).deposit("5.2500", "USD", "IBKR", date = "2026-08-04"))
        val before = state(db)
        for (stage in listOf("after-insert", "after-snapshot", "before-commit")) {
            val reached = java.util.concurrent.atomic.AtomicInteger()
            val instrumented = observed(db, stage, reached)
            assertFailsWith<StorageFailure> { InvestmentWriteService(instrumented).execute("synthetic-admin", InvestmentCommand("deposit", "IBKR", "USD", amount = exactCashAmount("10"), date = "2026-08-04")) }
            assertEquals(1, reached.get(), stage); assertEquals(before, state(db), stage)
        }
    }
    @Test fun returnTimePermissionChangeRollsBackActualEventAndSnapshot() = fixture { db, workspace ->
        val before = state(db)
        val changed = java.util.concurrent.atomic.AtomicInteger()
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")), "database.json").readText()).jsonObject
        val instrumented = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")) { connection ->
            java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection::class.java.classLoader, arrayOf(java.sql.Connection::class.java)) { _, method, args ->
                fun call(): Any? = try { method.invoke(connection, *(args ?: emptyArray())) } catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
                if (method.name == "prepareStatement" && (args!![0] as String).filter { it.code != 34 && it.code != 96 }.lowercase().startsWith("insert into ledger_snapshots")) {
                    val statement = call() as java.sql.PreparedStatement
                    java.lang.reflect.Proxy.newProxyInstance(java.sql.PreparedStatement::class.java.classLoader, arrayOf(java.sql.PreparedStatement::class.java)) { _, operation, values ->
                        val result = try { operation.invoke(statement, *(values ?: emptyArray())) } catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
                        if (operation.name in setOf("execute", "executeQuery", "executeUpdate") && changed.compareAndSet(0, 1)) connection.prepareStatement("UPDATE workspace_memberships SET role='viewer' WHERE workspace_id=? AND user_id='admin'").use { it.setString(1, workspace); assertEquals(1, it.executeUpdate()) }
                        result
                    }
                } else call()
            } as java.sql.Connection
        }
        assertFailsWith<AccessFailure> { InvestmentWriteService(instrumented).execute("synthetic-admin", InvestmentCommand("deposit", "IBKR", "USD", amount = exactCashAmount("10"), date = "2026-08-04")) }
        assertEquals(1, changed.get()); assertEquals(before, state(db))
    }

}
