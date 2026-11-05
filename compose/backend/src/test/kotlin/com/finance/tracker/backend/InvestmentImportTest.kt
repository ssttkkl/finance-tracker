package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class InvestmentImportTest {
    private val fixtures get() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_INVESTMENT_FIXTURES")))
    private fun fixture(block: (Database, String) -> Unit) {
        val config = Json.parseToJsonElement(Files.readString(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")), "database.json"))).jsonObject
        val db = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val spaces = WorkspaceService(db); val name = "投资文件导入验证"
        val workspace = spaces.create("synthetic-admin", name).activeWorkspaceId!!
        try {
            db.write { c ->
                for ((label, type) in listOf("东方证券" to "security", "IBKR" to "security", "嘉信" to "security", "盈立证券" to "security", "crypto" to "crypto", "cash" to "cash", "other" to "security"))
                    deletionInsert(c, db, "accounts", linkedMapOf("workspace_id" to workspace, "name" to label, "type" to type, "active" to true,
                        "revision" to 1L, "currencies" to JsonArray(emptyList()), "metadata_json" to JsonObject(emptyMap()), "created_at" to deletionTime, "updated_at" to deletionTime))
            }
            block(db, workspace)
        } finally { spaces.delete("synthetic-admin", name); spaces.select("synthetic-admin", "fixture-a") }
    }
    private fun events(db: Database, workspace: String) = db.read { c ->
        val e = INVESTMENT_EVENTS; val a = ACCOUNTS
        c.jooq(db.sqlite).select(e.fields().toList() + a.NAME).from(e).join(a).on(a.ID.eq(e.ACCOUNT_ID)).and(a.WORKSPACE_ID.eq(e.WORKSPACE_ID))
            .where(e.WORKSPACE_ID.eq(workspace)).orderBy(e.OCCURRED_AT, e.ID).fetch { row -> buildJsonObject {
                put("account_name", row[a.NAME]!!); put("date", manualInstantText(row[e.OCCURRED_AT]!!))
                for ((key, value) in listOf("record_type" to row[e.RECORD_TYPE], "record_subtype" to row[e.RECORD_SUBTYPE], "source_type" to row[e.SOURCE_TYPE], "record_id" to row[e.RECORD_ID], "note" to row[e.NOTE], "currency" to row[e.CURRENCY], "from_ticker" to row[e.FROM_TICKER], "to_ticker" to row[e.TO_TICKER], "commission_asset" to row[e.COMMISSION_ASSET])) put(key, value?.let(::JsonPrimitive) ?: JsonNull)
                for ((key, value) in listOf("from_amount" to row[e.FROM_AMOUNT], "to_amount" to row[e.TO_AMOUNT], "commission" to row[e.COMMISSION])) put(key, value?.let { JsonPrimitive(it.text) } ?: JsonNull)
                put("source_payload", row[e.SOURCE_PAYLOAD] ?: JsonNull)
            } }
    }
    private fun snapshot(db: Database, workspace: String) = db.read { CashSqlContext(it, workspace, db.sqlite).snapshot.load().value }
    private fun positions(db: Database, workspace: String, account: String) = snapshot(db, workspace).getValue("accounts").jsonObject.getValue("security").jsonObject.getValue(account).jsonObject.getValue("positions").jsonObject
    private fun ok(value: JsonObject, count: Int? = null) { assertEquals(JsonPrimitive(true), value["ok"], value.toString()); if (count != null) assertEquals(JsonPrimitive(count), value["count"]) }
    private fun amount(expected: String, actual: JsonElement?) = assertEquals(0, expected.toBigDecimal().compareTo(actual!!.jsonPrimitive.content.toBigDecimal()))
    private fun import(db: Database, source: String, file: String, account: String) = InvestmentImportService(db).importStatement("synthetic-admin", source, fixtures.resolve(file), account)

    @Test fun dfzqFullFlowInlineProvenanceAndSnapshot() = fixture { db, workspace ->
        ok(import(db, "dfzq", "dfzq/sample_statement.txt", "东方证券"), 6)
        val e = events(db, workspace); assertEquals(6, e.size)
        assertTrue(e.all { it["source_type"] == JsonPrimitive("dfzq_pdf") && it.getValue("record_id").jsonPrimitive.content.startsWith("dfzq:") })
        val counts = e.groupingBy { it.getValue("record_type").jsonPrimitive.content }.eachCount()
        assertEquals(mapOf("funding" to 1, "trade" to 2, "income" to 2, "snapshot" to 1), counts)
        val funding = e.single { it["record_type"] == JsonPrimitive("funding") }
        assertEquals(JsonArray(listOf("20260610", "银行转证券", "CNY", "1.0000", "10000.00", "0.00", "10000.00").map(::JsonPrimitive)), funding.getValue("source_payload").jsonObject["原始文本单元"])
        val check = e.single { it["record_type"] == JsonPrimitive("snapshot") && it["record_subtype"] == JsonPrimitive("cash") }
        assertEquals(JsonArray(listOf("20260620", "红利入账", "600000", "浦发银行", "0.0000", "0.0000", "50.00", "0.00", "0.00", "0.00", "9447.30").map(::JsonPrimitive)), check.getValue("source_payload").jsonObject["原始文本单元"])
        assertTrue(e.all { it.getValue("source_payload").jsonObject.keys == setOf("原始文本单元") })
        assertEquals(JsonPrimitive(""), check["note"])
        assertEquals(setOf(JsonPrimitive("证券买入"), JsonPrimitive("证券卖出")), e.filter { it["record_type"] == JsonPrimitive("trade") }.map { it["note"] }.toSet())
        val p = positions(db, workspace, "东方证券"); amount("9447.30", p.getValue("cny").jsonObject["shares"]); amount("60", p.getValue("600000.sh").jsonObject["shares"])
        validateInvestmentSnapshot(snapshot(db, workspace))
    }
    @Test fun everyBrokerImportsOnceAndKeepsOriginalRowsAndNativeBalances() = fixture { db, workspace ->
        for ((source, file, account, count) in listOf(listOf("ibkr", "ibkr/transactions_1y_sample.csv", "IBKR", "39"), listOf("schwab", "schwab/transaction_history_sample.csv", "嘉信", "37"), listOf("usmart-hk", "usmart_hk/monthly_sample.txt", "盈立证券", "46"))) {
            ok(import(db, source, file, account), count.toInt()); val before = snapshot(db, workspace); val old = events(db, workspace)
            val second = import(db, if (source == "usmart-hk") "usmart_hk" else source, file, account)
            ok(second, 0); assertEquals(JsonPrimitive(true), second.getValue("details").jsonObject["duplicate"]); assertEquals(JsonPrimitive(0), second.getValue("details").jsonObject["new_rows"])
            assertEquals(before, snapshot(db, workspace)); assertEquals(old, events(db, workspace))
            val rows = InvestmentDocument.read(source, file, Files.readAllBytes(fixtures.resolve(file)))
            val expected = rows.associate { InvestmentDocument.identity(source, it) to InvestmentDocument.sourcePayload(source, it) }
            val stored = old.filter { it["account_name"] == JsonPrimitive(account) }; assertEquals(count.toInt(), stored.size)
            assertEquals(expected, stored.associate { it.getValue("record_id").jsonPrimitive.content to it.getValue("source_payload") })
            assertTrue(stored.filter { it["record_type"] == JsonPrimitive("snapshot") }.all { it["note"] == JsonPrimitive("") })
        }
        amount("5044.938780328453", positions(db, workspace, "IBKR").getValue("usd").jsonObject["shares"])
        val schwab = positions(db, workspace, "嘉信"); for ((ticker, quantity) in listOf("usd" to "2865.36", "avgo.us" to "7", "msft.us" to "5")) amount(quantity, schwab.getValue(ticker).jsonObject["shares"])
        val usmart = positions(db, workspace, "盈立证券"); for ((ticker, quantity) in listOf("usd" to "4750.17", "hkd" to "2021.09", "00700.hk" to "100", "mrvl.us" to "3", "spcx.us" to "5")) amount(quantity, usmart.getValue(ticker).jsonObject["shares"])
    }
    @Test fun brokerOriginalEvidenceAndNotesRemainExact() = fixture { db, workspace ->
        ok(import(db, "ibkr", "ibkr/transactions_1y_sample.csv", "IBKR"), 39)
        ok(import(db, "usmart-hk", "usmart_hk/monthly_sample.txt", "盈立证券"), 46)
        val all = events(db, workspace)
        val ibkr = all.filter { it["account_name"] == JsonPrimitive("IBKR") }
        val repositoryEvents = db.read { RelationalPortfolioRepository(CashSqlContext(it, workspace, db.sqlite)).loadPortfolio().getValue("investment_events").jsonArray }
        val originalFunding = repositoryEvents.single { it.jsonObject["account"] == JsonPrimitive("IBKR") && it.jsonObject["record_type"] == JsonPrimitive("funding") && it.jsonObject["to_amount"] == JsonPrimitive("4757") }.jsonObject
        assertEquals(JsonPrimitive("4757"), originalFunding["to_amount"])
        val funding = ibkr.single { it["record_type"] == JsonPrimitive("funding") && it["to_amount"]!!.jsonPrimitive.content.toBigDecimal().compareTo("4757".toBigDecimal()) == 0 }
        assertEquals(JsonArray(listOf("Transaction History", "Data", "2026-07-08", "U***67228", "电子资金转账", "存款", "-", "-", "-", "-", "4757.0", "-", "4757.0").map(::JsonPrimitive)), funding.getValue("source_payload").jsonObject["原始文本单元"])
        assertEquals(JsonPrimitive("电子资金转账"), funding["note"])
        assertTrue(ibkr.all { it.getValue("source_payload").jsonObject.keys == setOf("原始文本单元") })
        val ibkrSnapshot = ibkr.single { it["record_type"] == JsonPrimitive("snapshot") && it["record_subtype"] == JsonPrimitive("cash") }
        assertEquals(JsonArray(listOf("总结", "Data", "期末现金", "5044.938780328453").map(::JsonPrimitive)), ibkrSnapshot.getValue("source_payload").jsonObject["原始文本单元"])
        val usmart = all.filter { it["account_name"] == JsonPrimitive("盈立证券") }
        fun units(row: JsonObject) = row.getValue("source_payload").jsonObject.getValue("原始文本单元").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(usmart.all { it.getValue("source_payload").jsonObject.keys == setOf("原始文本单元") })
        val interest = usmart.first { units(it).any { line -> "融资利息" in line } }
        assertEquals(listOf("  融资利息                     USD                       -0.78                      2026-06-29"), units(interest))
        assertTrue(units(usmart.first { it["record_type"] == JsonPrimitive("snapshot") && it["record_subtype"] == JsonPrimitive("cash") && it["to_ticker"] == JsonPrimitive("usd") }).any { "期末账⼾结余" in it })
        assertTrue(units(usmart.first { it["record_type"] == JsonPrimitive("snapshot") && it["record_subtype"] == JsonPrimitive("position") && it["to_ticker"] == JsonPrimitive("00700.hk") }).any { "00700 (腾讯控股)" in it })
        assertEquals(JsonPrimitive(""), usmart.first { it["record_type"] == JsonPrimitive("trade") }["note"])
    }
    @Test fun schwabSnapshotAndFundingKeepExactOriginalColumnNamesAndValues() = fixture { db, workspace ->
        ok(import(db, "schwab", "schwab/transaction_history_sample.csv", "嘉信"), 37)
        val records = BrokerText.csv(Files.readString(fixtures.resolve("schwab/transaction_history_sample.csv")).removePrefix("\uFEFF"))
        val sourceRows = records.drop(1).filter { it.isNotEmpty() }.map { values -> JsonObject(records.first().mapIndexed { i, header -> header to JsonPrimitive(values[i]) }.toMap()) }
        val fundingSource = sourceRows.first { it[" 类型"]?.jsonPrimitive?.content?.trim() == "WIN" }
        val stored = events(db, workspace)
        val funding = stored.first { it["record_type"] == JsonPrimitive("funding") }
        val check = stored.first { it["record_type"] == JsonPrimitive("snapshot") && it["record_subtype"] == JsonPrimitive("cash") }
        assertEquals(fundingSource, funding["source_payload"]); assertTrue(funding["note"] in fundingSource.values)
        assertEquals(sourceRows.first(), check["source_payload"])
        assertTrue(stored.all { it.getValue("source_payload").jsonObject.keys.intersect(setOf("date", "type", "ticker", "amount", "record_type", "record_subtype")).isEmpty() })
    }
    @Test fun copiedAndWhitespaceModifiedFilesRemainRowIdempotent() = fixture { db, workspace ->
        val service = InvestmentImportService(db); val original = fixtures.resolve("dfzq/sample_statement.txt")
        val first = service.importStatement("synthetic-admin", "dfzq", original, "东方证券"); ok(first, 6)
        val before = events(db, workspace); assertEquals(6, before.size); val positions = positions(db, workspace, "东方证券")
        val folder = Files.createTempDirectory("ft-investment-idempotency-")
        try {
            val copy = Files.copy(original, folder.resolve("copied_statement.txt"))
            val changed = Files.writeString(folder.resolve("modified_statement.txt"), Files.readString(original) + "\n\n")
            for (path in listOf(original, copy, changed)) {
                val result = service.importStatement("synthetic-admin", "dfzq", path, "东方证券"); ok(result, 0)
                val details = result.getValue("details").jsonObject
                assertEquals(JsonPrimitive(true), details["duplicate"]); assertEquals(JsonPrimitive(0), details["new_rows"])
                assertEquals(JsonNull, details["batch_id"]); assertEquals(first.getValue("details").jsonObject["batch_id"], details["batch_id"])
                assertEquals(before, events(db, workspace)); assertEquals(positions, positions(db, workspace, "东方证券"))
            }
        } finally { Files.list(folder).use { paths -> paths.forEach { Files.delete(it) } }; Files.delete(folder) }
    }
    @Test fun feeBreakdownAndPreCheckinCashReplay() = fixture { db, workspace ->
        ok(import(db, "dfzq", "dfzq/fee_breakdown_statement.txt", "东方证券"), 4)
        ok(import(db, "dfzq", "dfzq/fee_breakdown_statement.txt", "东方证券"), 0)
        val e = events(db, workspace); val buy = e.single { it["note"] == JsonPrimitive("证券买入") }; val sell = e.single { it["note"] == JsonPrimitive("证券卖出") }
        amount("11.53", buy["commission"]); amount("1000.00", buy["from_amount"]); amount("11.53", sell["commission"]); amount("1000.00", sell["to_amount"])
        amount("1011.53", JsonPrimitive(buy.getValue("from_amount").jsonPrimitive.content.toBigDecimal().add(buy.getValue("commission").jsonPrimitive.content.toBigDecimal()).toPlainString()))
        amount("988.47", JsonPrimitive(sell.getValue("to_amount").jsonPrimitive.content.toBigDecimal().subtract(sell.getValue("commission").jsonPrimitive.content.toBigDecimal()).toPlainString()))
        val replay = CashSnapshotDocument(JsonObject(emptyMap()))
        e.filter { it["record_type"] != JsonPrimitive("snapshot") }.forEach { InvestmentProjection.applyEvent(replay, it, "CNY") }
        amount("9976.94", replay.value.getValue("accounts").jsonObject.getValue("security").jsonObject.getValue("东方证券").jsonObject.getValue("positions").jsonObject.getValue("cny").jsonObject["shares"])
        amount("9976.94", positions(db, workspace, "东方证券").getValue("cny").jsonObject["shares"])
    }
    @Test fun overlapBothOrdersDeduplicatesAcrossAccountsAndDigests() = fixture { db, workspace ->
        for ((account, files, counts) in listOf(Triple("东方证券", listOf("a", "b"), listOf(3, 2)), Triple("other", listOf("b", "a"), listOf(4, 1)))) {
            db.write { c -> c.jooq(db.sqlite).deleteFrom(INVESTMENT_EVENTS).where(INVESTMENT_EVENTS.WORKSPACE_ID.eq(workspace)).execute(); c.jooq(db.sqlite).deleteFrom(LEDGER_SNAPSHOTS).where(LEDGER_SNAPSHOTS.WORKSPACE_ID.eq(workspace)).execute() }
            for (i in files.indices) {
                val result = import(db, "dfzq", "import_idempotency/dfzq_overlap_${files[i]}.txt", account)
                ok(result, counts[i]); assertEquals(JsonPrimitive(counts[i]), result.getValue("details").jsonObject["new_rows"])
            }
            assertEquals(5, events(db, workspace).size)
            ok(import(db, "dfzq", "import_idempotency/dfzq_overlap_a.txt", if (account == "other") "东方证券" else "other"), 0)
        }
    }
    @Test fun missingFileAccountWrongTypeAndMalformedPayloadLeaveNoFinancialRows() = fixture { db, workspace ->
        val service = InvestmentImportService(db); val before = state(db)
        for ((file, account, message) in listOf(Triple("missing.txt", "东方证券", "找不到文件"), Triple("dfzq/sample_statement.txt", "missing", "找不到账户"), Triple("dfzq/sample_statement.txt", "cash", "security"))) {
            val result = service.importStatement("synthetic-admin", "dfzq", fixtures.resolve(file), account)
            assertEquals(JsonPrimitive(false), result["ok"]); assertTrue(result.getValue("message").jsonPrimitive.content.contains(message))
            assertTrue(events(db, workspace).isEmpty()); assertEquals(before, state(db))
        }
        val rows = listOf(DfzqRow(buildJsonObject {
            put("date", "2026-06-10 00:00:00"); put("action", "DEPOSIT"); put("ticker", ""); put("note", "")
            put("amount", "100"); put("balance", "100"); put("shares", "0"); put("price", "0"); put("fee", "0")
        }))
        val result = InvestmentImportService(db, parse = { _, _, _, _ -> rows }).importStatement("synthetic-admin", "dfzq", fixtures.resolve("dfzq/sample_statement.txt"), "东方证券")
        assertEquals(JsonPrimitive(false), result["ok"]); assertTrue(result.getValue("message").jsonPrimitive.content.contains("来源行快照")); assertTrue(events(db, workspace).isEmpty()); assertEquals(before, state(db))
    }
    @Test fun truncatedDfzqDocumentIsRejectedWithoutFinancialWrites() = fixture { db, _ ->
        val before = state(db)
        val content = "资金流水明细\n20260610\n银行转证券\nCNY".toByteArray()
        val result = InvestmentImportService(db).importBytes("synthetic-admin", "dfzq", "statement.txt", content, "东方证券")
        assertEquals(JsonPrimitive(false), result["ok"])
        assertEquals(JsonPrimitive("解析账单失败：list index out of range"), result["message"])
        assertEquals(before, state(db))
    }
    @Test fun decodedHttpUploadLimitRejectsBeforeParsingOrWriting() = fixture { db, _ ->
        val before = state(db); var parses = 0
        val service = InvestmentImportService(db, parse = { _, _, _, _ -> parses++; emptyList() })
        val content = java.util.Base64.getEncoder().encodeToString(ByteArray(25 * 1024 * 1024 + 1))
        val input = buildJsonObject { put("source", "dfzq"); put("account", "东方证券"); put("filename", "statement.pdf"); put("content_base64", content) }
        val failure = assertFailsWith<CashflowOperationFailure> { InvestmentImportHttp.execute(service, "synthetic-admin", input, null, null) }
        assertEquals("investment_import_content_too_large", failure.code)
        assertEquals(0, parses); assertEquals(before, state(db))
    }
    @Test fun actualPlainAndEncryptedPdfsImportAndDeduplicateWithText() = fixture { db, workspace ->
        val contract = Path.of(requireNotNull(System.getenv("FT_KOTLIN_PDF_TOOL_CONTRACT")))
        val cases = Json.parseToJsonElement(Files.readString(contract)).jsonObject.getValue("cases").jsonArray
        assertEquals(4, cases.size)
        for (raw in cases) {
            val case = raw.jsonObject; val source = case.getValue("broker").jsonPrimitive.content
            val account = if (source == "dfzq") "东方证券" else "盈立证券"
            val password = case["password"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
            val content = Files.readAllBytes(contract.parent.resolve(case.getValue("file").jsonPrimitive.content))
            val service = InvestmentImportService(db); val before = state(db)
            if (password != null) {
                val rejected = service.importBytes("synthetic-admin", source, "statement.pdf", content, account, password = "wrong")
                assertEquals(JsonPrimitive(false), rejected["ok"]); assertEquals(before, state(db))
            }
            val expected = case.getValue("expected").jsonArray
            val result = service.importBytes("synthetic-admin", source, "statement.pdf", content, account, password = password)
            ok(result, if (password == null) expected.size else 0)
            val stored = events(db, workspace).filter { it["account_name"] == JsonPrimitive(account) }
            assertEquals(expected.size, stored.size)
            assertEquals(expected.associate { it.jsonObject.getValue("identity").jsonPrimitive.content to it.jsonObject.getValue("row").jsonObject.getValue("_source_payload") },
                stored.associate { it.getValue("record_id").jsonPrimitive.content to it.getValue("source_payload") })
            val after = events(db, workspace); val positions = positions(db, workspace, account)
            val repeated = service.importBytes("synthetic-admin", source, "statement.txt", case.getValue("source_text").jsonPrimitive.content.toByteArray(), account)
            ok(repeated, 0); assertEquals(after, events(db, workspace)); assertEquals(positions, positions(db, workspace, account))
        }
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
        val before = state(db)
        for (stage in listOf("after-insert", "after-snapshot", "before-commit")) {
            val reached = java.util.concurrent.atomic.AtomicInteger()
            val instrumented = observed(db, stage, reached)
            assertFailsWith<StorageFailure> { import(instrumented, "dfzq", "dfzq/sample_statement.txt", "东方证券") }
            assertEquals(1, reached.get(), stage); assertEquals(before, state(db), stage)
        }
    }
    @Test fun permissionsAreCheckedBeforeParsingAndAfterUnlockedParsing() = fixture { db, workspace ->
        val path = fixtures.resolve("dfzq/sample_statement.txt")
        val before = state(db); var parsed = 0
        val rejecting = InvestmentImportService(db, parse = { _, _, _, _ -> parsed++; error("should not parse") })
        for (token in listOf(null, "synthetic-viewer", "synthetic-removed", "synthetic-empty")) {
            assertFailsWith<AccessFailure> { rejecting.importStatement(token, "dfzq", path, "东方证券") }
            assertEquals(0, parsed); assertEquals(before, state(db))
        }
        var reached = 0
        val service = InvestmentImportService(db, afterParse = {
            reached++
            db.write { c -> c.jooq(db.sqlite).update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "viewer")
                .where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(workspace)).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq("admin")).execute() }
        })
        try {
            assertFailsWith<AccessFailure> { service.importStatement("synthetic-admin", "dfzq", path, "东方证券") }
            assertEquals(1, reached); assertTrue(events(db, workspace).isEmpty())
            val after = state(db); assertEquals(before.filterKeys { it != "workspace_memberships" }, after.filterKeys { it != "workspace_memberships" })
        } finally {
            db.write { c -> c.jooq(db.sqlite).update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "admin")
                .where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(workspace)).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq("admin")).execute() }
        }
    }
    @Test fun lateInternalFailureStopsAndRollsBackAllEventsAndSnapshots() = fixture { db, _ ->
        val before = state(db); var reached = 0
        val service = InvestmentImportService(db, beforeReturn = { reached++; error("synthetic late failure") })
        assertFailsWith<IllegalStateException> { service.importStatement("synthetic-admin", "ibkr", fixtures.resolve("ibkr/transactions_1y_sample.csv"), "IBKR") }
        assertEquals(1, reached); assertEquals(before, state(db))
    }
    @Test fun simultaneousImportsSerializeAndInsertOnlyOneCopy() = fixture { db, workspace ->
        val ready = java.util.concurrent.CountDownLatch(2); val release = java.util.concurrent.CountDownLatch(1)
        val service = InvestmentImportService(db, afterParse = { ready.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) })
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val futures = (1..2).map { executor.submit<JsonObject> { service.importStatement("synthetic-admin", "dfzq", fixtures.resolve("dfzq/sample_statement.txt"), "东方证券") } }
        try {
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)); release.countDown()
            val results = futures.map { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }
            results.forEach { ok(it) }; assertEquals(listOf(0, 6), results.map { it.getValue("count").jsonPrimitive.int }.sorted())
            assertEquals(6, events(db, workspace).size)
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)) }
    }
}
