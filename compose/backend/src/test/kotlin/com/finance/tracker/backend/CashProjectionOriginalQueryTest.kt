package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.math.MathContext
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant
import java.time.ZoneId
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashProjectionOriginalQueryTest {
    private inner class Fixture(name: String) {
        val directory = Path.of(requireNotNull(System.getenv("FT_KOTLIN_PROJECTION_ORIGINAL_QUERY_CONTRACT_DIR"))).resolve(name)
        private val config = Json.parseToJsonElement(directory.resolve("database.json").readText()).jsonObject
        val workspace = config.apiText("workspace")
        val token = "original-import-owner"
        fun database(observe: (Connection, String) -> Unit = { _, _ -> }) = Database(config.apiText("jdbc_url"), System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")) { raw ->
            Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                if (method.name == "prepareStatement") observe(raw, args!![0] as String)
                try { method.invoke(raw, *(args ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
            } as Connection
        }
        val database = database()
        fun snapshot() = database.read { connection -> DefaultSchema.DEFAULT_SCHEMA.tables.sortedBy { it.name }.associate { table ->
            table.name to connection.createStatement().use { statement -> statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
                val metadata = rows.metaData
                buildList { while (rows.next()) add(JsonArray((1..metadata.columnCount).map { index -> rows.getString(index)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
            } }
        } }
        fun page(vararg values: Pair<String, String>, token: String = this.token): JsonObject {
            val before = snapshot()
            try { return CashProjectionListService(database).list(token, values.toMap()) }
            finally { assertEquals(before, snapshot()) }
        }
        fun rebuild() = CashProjectionService(database, workspace).rebuild()
    }
    private fun ids(page: JsonObject) = page.apiArray("items").map { it.jsonObject.apiText("projection_id") }
    private fun options(page: JsonObject) = page.getValue("filter_options").jsonObject
    private fun categories(page: JsonObject) = options(page).apiArray("categories").map { it.jsonObject.apiText("name") }
    private fun positions(page: JsonObject) = page.apiArray("month_positions").map {
        val row = it.jsonObject; Triple(row.apiText("month"), row.getValue("start_index").jsonPrimitive.int, row.getValue("end_index").jsonPrimitive.int)
    }
    @Test fun originalMonthSummariesAndPositionsFollowBrowserTimezone() {
        fun result(zone: String) = CashProjectionMonthlyAccumulator(ZoneId.of(zone)).apply {
            add(Instant.parse("2026-06-30T23:30:00Z"), "income", BigDecimal("10"), "USD")
        }.result()
        val utc = result("UTC"); val tokyo = result("Asia/Tokyo")
        assertEquals(listOf("2026-06"), utc.summaries.map { it.jsonObject.apiText("month") })
        assertEquals(listOf("2026-07"), tokyo.summaries.map { it.jsonObject.apiText("month") })
        for ((data, month) in listOf(utc to "2026-06", tokyo to "2026-07")) {
            assertEquals(listOf(Triple(month, 0, 1)), data.positions.map {
                val row = it.jsonObject; Triple(row.apiText("month"), row.getValue("start_index").jsonPrimitive.int, row.getValue("end_index").jsonPrimitive.int)
            })
        }
    }
    @Test fun originalMonthSummariesKeepFullPrecisionBesideSmallCallerContext() {
        val caller = MathContext(6)
        val first = BigDecimal("-12345678901234567890.123456789012345678"); val second = BigDecimal("-0.000000000000000001")
        val summary = CashProjectionMonthlyAccumulator(ZoneId.of("UTC")).apply {
            add(Instant.parse("2026-01-01T00:00:00Z"), "expense", first, "CNY")
            add(Instant.parse("2026-01-02T00:00:00Z"), "expense", second, "CNY")
        }.result().summaries.single().jsonObject.apiArray("currencies").single().jsonObject.apiText("expense")
        assertEquals(6, caller.precision)
        assertNotEquals(first.add(second, caller), summary.toBigDecimal())
        assertEquals("-12345678901234567890.123456789012345679", summary)
    }
    @Test fun originalIndexedMonthsComputeOnlyOnceForVersionedPages() {
        val f = Fixture("months"); var aggregates = 0
        val database = f.database { _, query ->
            val selected = query.lowercase().substringBefore(" from ")
            if (selected.contains("statistics_eligible") && !selected.contains("projection_id")) aggregates++
        }
        val before = f.snapshot(); val service = CashProjectionListService(database)
        val first = service.list(f.token, mapOf("limit" to "1", "start_index" to "0"))
        val following = service.list(f.token, mapOf("limit" to "1", "start_index" to "1", "projection_version" to first.apiText("projection_version")))
        assertEquals(5, first.getValue("total_count").jsonPrimitive.int)
        assertEquals(listOf(Triple("2026-07", 0, 3), Triple("2026-06", 3, 5)), positions(first))
        assertTrue(first.apiArray("monthly_summaries").isNotEmpty()); assertEquals(JsonArray(emptyList()), following["monthly_summaries"])
        assertEquals(JsonArray(emptyList()), following["month_positions"]); assertEquals(1, aggregates); assertEquals(before, f.snapshot())
    }
    @Test fun originalIndexCountsAndRowsRemainWorkspaceScoped() {
        val f = Fixture("scope"); val main = f.page("start_index" to "0", "limit" to "10")
        val other = f.page("start_index" to "0", "limit" to "10", token = "query-other")
        assertEquals(3, main.getValue("total_count").jsonPrimitive.int)
        assertEquals(listOf("咖啡店", "超市", "工资"), main.apiArray("items").map { it.jsonObject.apiText("counterparty") })
        assertEquals(1, other.getValue("total_count").jsonPrimitive.int)
        assertEquals(listOf("另一工作区流水"), other.apiArray("items").map { it.jsonObject.apiText("counterparty") })
    }
    @Test fun originalOldIndexVersionRequiresRefreshAfterActualRebuild() {
        val f = Fixture("index_version"); val first = f.page("limit" to "1", "start_index" to "0"); f.rebuild()
        assertEquals("read_snapshot_changed", assertFailsWith<CashProjectionListFailure> { f.page("limit" to "1", "start_index" to "1", "projection_version" to first.apiText("projection_version")) }.code)
    }
    @Test fun originalMonthlySummariesCoverAllFilteredRowsBeyondPage() {
        val page = Fixture("months").page("limit" to "1")
        val actual = page.apiArray("monthly_summaries").map { item ->
            val row = item.jsonObject; row.apiText("month") to row.apiArray("currencies").map { value ->
                val currency = value.jsonObject; Triple(currency.apiText("currency"), currency.apiText("income"), currency.apiText("expense"))
            }
        }
        assertEquals(listOf("2026-07" to listOf(Triple("CNY", "2000", "-112.5")), "2026-06" to listOf(Triple("USD", "10", "-3"))), actual)
    }
    @Test fun originalTextFilterMatchesCounterpartyAndNote() {
        assertEquals(listOf("cash:counterparty-match", "cash:note-match"), ids(Fixture("text").page("counterparty" to "星巴克")))
    }
    @Test fun originalOptionsExcludeHiddenValuesAndKeepCategoryCatalog() {
        val page = Fixture("options").page("limit" to "1")
        assertEquals(listOf("日用", "餐饮", "收入", "转账"), categories(page))
        assertEquals(listOf("CNY", "USD"), options(page).apiArray("currencies").map { it.jsonPrimitive.content })
    }
    @Test fun originalSubtypeOptionsAndFiltersUseActiveVisibleData() {
        val f = Fixture("types"); val page = f.page("limit" to "1"); val selected = f.page("transfer_subtype" to "bank_security_transfer", "limit" to "1")
        val economic = options(page).apiArray("economic_types").map { raw ->
            val row = raw.jsonObject; row.apiText("economic_type") to row.apiArray("transfer_subtypes").map { it.jsonPrimitive.content }
        }
        assertEquals(listOf("expense" to emptyList(), "income" to emptyList(), "internal_transfer" to listOf("bank_security_transfer", "ordinary_transfer")), economic)
        assertEquals(listOf("cash:bank-security-new"), ids(selected))
        val filters = selected.getValue("filters").jsonObject
        assertEquals("internal_transfer", filters.apiText("economic_type")); assertEquals("bank_security_transfer", filters.apiText("transfer_subtype"))
        assertEquals("invalid_filter", assertFailsWith<CashProjectionListFailure> { f.page("economic_type" to "expense", "transfer_subtype" to "bank_security_transfer") }.code)
        assertEquals("invalid_cursor", assertFailsWith<CashProjectionListFailure> { f.page("transfer_subtype" to "ordinary_transfer", "cursor" to selected.apiText("next_cursor")) }.code)
    }
    @Test fun originalVisibleTransferRemainsInPageAndGlobalOptions() {
        val f = Fixture("transfer"); val page = f.page(); val transfer = page.apiArray("items").map { it.jsonObject }.single { it.apiText("projection_id") == "cash:visible-transfer" }
        assertEquals("internal_transfer", transfer.apiText("economic_type")); assertEquals("0", transfer.apiText("amount"))
        assertEquals(listOf("日用", "餐饮", "收入", "转账"), categories(page))
        assertEquals(listOf("CNY", "HKD"), options(page).apiArray("currencies").map { it.jsonPrimitive.content })
        assertEquals(listOf("cash:visible-transfer"), ids(f.page("economic_type" to "internal_transfer")))
    }
    @Test fun originalEmptyVisiblePageKeepsCategoriesButNoCurrencies() {
        val page = Fixture("empty").page()
        assertEquals(emptyList(), ids(page)); assertEquals(listOf("日用", "餐饮", "收入", "转账"), categories(page))
        assertEquals(JsonArray(emptyList()), options(page)["currencies"])
    }
    @Test fun originalOldCursorVersionRequiresRefreshAfterActualRebuild() {
        val f = Fixture("cursor_version"); val cursor = f.page("limit" to "1").apiText("next_cursor"); f.rebuild()
        assertEquals("read_snapshot_changed", assertFailsWith<CashProjectionListFailure> { f.page("limit" to "1", "cursor" to cursor) }.code)
    }
    @Test fun originalRefundRebuildInterleavingKeepsOneDatasetAndVersionSnapshot() {
        val f = Fixture("snapshot"); val baseline = f.page("limit" to "2"); val version = baseline.getValue("projection_version").jsonPrimitive.long
        val input = Json.parseToJsonElement(f.directory.resolve("refund-input.json").readText()).jsonObject
        val observed = mutableListOf<Pair<Int, String>>(); var rebuilt = false
        val database = f.database { connection, query ->
            if (query.contains("cash_projection_states") || query.contains("cash_projections") || query.contains("cash_projection_relations")) observed += System.identityHashCode(connection) to query
        }
        val service = CashProjectionListService(database, afterStateRead = {
            assertFalse(rebuilt); rebuilt = true; stageRefund(f, input); f.rebuild()
        })
        val page = service.list(f.token, mapOf("limit" to "2"))
        assertTrue(rebuilt); assertEquals(version, page.getValue("projection_version").jsonPrimitive.long)
        assertEquals(listOf("cash:1003", "cash:1002"), ids(page))
        assertEquals(listOf("-12.5", "-100"), page.apiArray("items").map { it.jsonObject.apiText("amount") })
        page.apiArray("items").forEach { raw -> assertEquals(JsonArray(emptyList()), raw.jsonObject["composition"]); assertEquals(JsonArray(emptyList()), raw.jsonObject["accepted_relation_summary"]) }
        val states = observed.filter { it.second.contains("cash_projection_states") }
        assertEquals(1, states.size); assertTrue(states.single().second.contains("active_dataset_id")); assertTrue(states.single().second.contains("projection_version"))
        assertTrue(observed.any { it.second.contains("cash_projection_relations") }); assertEquals(1, observed.map { it.first }.toSet().size)
        assertEquals(version + 1, CashProjectionService(f.database, f.workspace).status().getValue("projection_version").jsonPrimitive.long)
        assertEquals("read_snapshot_changed", assertFailsWith<CashProjectionListFailure> { f.page("limit" to "2", "cursor" to page.apiText("next_cursor")) }.code)
    }
    private fun stageRefund(f: Fixture, input: JsonObject) = f.database.write { connection ->
        val sql = connection.jooq(f.database.sqlite); val fact = input.getValue("transaction").jsonObject; val primary = input.getValue("primary").jsonObject
        val part = input.getValue("component").jsonObject; val relation = input.getValue("relation").jsonObject
        fun JsonObject.long(name: String) = getValue(name).jsonPrimitive.long
        sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.RECORD_TYPE, primary.apiText("record_type"))
            .set(CASH_TRANSACTIONS.RECORD_SUBTYPE, primary.apiText("record_subtype")).set(CASH_TRANSACTIONS.ROW_REVISION, primary.long("row_revision"))
            .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(f.workspace)).and(CASH_TRANSACTIONS.ID.eq(primary.long("id"))).execute()
        sql.insertInto(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.ID, fact.long("id")).set(CASH_TRANSACTIONS.WORKSPACE_ID, f.workspace)
            .set(CASH_TRANSACTIONS.ACCOUNT_ID, fact.long("account_id")).set(CASH_TRANSACTIONS.STATE, fact.apiText("state"))
            .set(CASH_TRANSACTIONS.RECORD_ORIGIN, fact.apiText("record_origin")).set(CASH_TRANSACTIONS.ROW_REVISION, fact.long("row_revision"))
            .set(CASH_TRANSACTIONS.CASH_GRANULARITY, fact.apiText("cash_granularity")).set(CASH_TRANSACTIONS.SOURCE_TYPE, fact.apiText("source_type"))
            .set(CASH_TRANSACTIONS.RECORD_ID, fact.apiText("record_id")).set(CASH_TRANSACTIONS.MANUAL_OVERRIDES, fact["manual_overrides"])
            .set(CASH_TRANSACTIONS.OCCURRED_AT, Instant.parse(fact.apiText("occurred_at"))).set(CASH_TRANSACTIONS.AMOUNT, CashStoredDecimal(fact.apiText("amount")))
            .set(CASH_TRANSACTIONS.CURRENCY, fact.apiText("currency")).set(CASH_TRANSACTIONS.COUNTERPARTY, fact.apiText("counterparty"))
            .set(CASH_TRANSACTIONS.COUNTERPARTY_ACCOUNT, fact.apiText("counterparty_account")).set(CASH_TRANSACTIONS.COUNTERPARTY_ACCOUNT_ATTRS, fact["counterparty_account_attrs"])
            .set(CASH_TRANSACTIONS.NOTE, fact.apiText("note")).set(CASH_TRANSACTIONS.CATEGORY_ID, fact.apiText("category_id"))
            .set(CASH_TRANSACTIONS.RECORD_TYPE, fact.apiText("record_type")).set(CASH_TRANSACTIONS.RECORD_SUBTYPE, fact.apiText("record_subtype"))
            .set(CASH_TRANSACTIONS.CREATED_AT, Instant.parse(fact.apiText("created_at"))).execute()
        sql.insertInto(CASH_TRANSACTION_COMPONENTS).set(CASH_TRANSACTION_COMPONENTS.ID, part.long("id")).set(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID, f.workspace)
            .set(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID, part.long("cash_transaction_id")).set(CASH_TRANSACTION_COMPONENTS.ACCOUNT_ID, part.long("account_id"))
            .set(CASH_TRANSACTION_COMPONENTS.AMOUNT, CashStoredDecimal(part.apiText("amount"))).set(CASH_TRANSACTION_COMPONENTS.CURRENCY, part.apiText("currency"))
            .set(CASH_TRANSACTION_COMPONENTS.ORDINAL, part.long("ordinal")).set(CASH_TRANSACTION_COMPONENTS.LABEL, part.apiText("label"))
            .set(CASH_TRANSACTION_COMPONENTS.SOURCE_KEY, part.apiText("source_key")).set(CASH_TRANSACTION_COMPONENTS.METADATA_JSON, part["metadata_json"])
            .set(CASH_TRANSACTION_COMPONENTS.CREATED_AT, Instant.parse(part.apiText("created_at"))).execute()
        sql.insertInto(TRANSACTION_RELATIONS).set(TRANSACTION_RELATIONS.ID, relation.long("id")).set(TRANSACTION_RELATIONS.WORKSPACE_ID, f.workspace)
            .set(TRANSACTION_RELATIONS.KIND, relation.apiText("kind")).set(TRANSACTION_RELATIONS.SUBTYPE, relation.apiText("subtype"))
            .set(TRANSACTION_RELATIONS.PRIMARY_TRANSACTION_ID, relation.long("primary_transaction_id")).set(TRANSACTION_RELATIONS.SECONDARY_TRANSACTION_ID, relation.long("secondary_transaction_id"))
            .set(TRANSACTION_RELATIONS.PRIMARY_COMPONENT_ID, relation.long("primary_component_id")).set(TRANSACTION_RELATIONS.SECONDARY_COMPONENT_ID, relation.long("secondary_component_id"))
            .set(TRANSACTION_RELATIONS.ANCHOR_TRANSACTION_ID, relation.long("anchor_transaction_id")).set(TRANSACTION_RELATIONS.ANCHOR_COMPONENT_ID, relation.long("anchor_component_id"))
            .set(TRANSACTION_RELATIONS.PRIMARY_ROLE, relation.apiText("primary_role")).set(TRANSACTION_RELATIONS.SECONDARY_ROLE, relation.apiText("secondary_role"))
            .set(TRANSACTION_RELATIONS.STATE, relation.apiText("state")).set(TRANSACTION_RELATIONS.ACTIVE, relation.getValue("active").jsonPrimitive.boolean)
            .set(TRANSACTION_RELATIONS.ROW_REVISION, relation.long("row_revision")).set(TRANSACTION_RELATIONS.DECISION_SOURCE, relation.apiText("decision_source"))
            .set(TRANSACTION_RELATIONS.EVIDENCE, relation["evidence"]).set(TRANSACTION_RELATIONS.APPLIED_AMOUNT, CashStoredDecimal(relation.apiText("applied_amount")))
            .set(TRANSACTION_RELATIONS.RULE_ID, relation.apiText("rule_id")).set(TRANSACTION_RELATIONS.CREATED_BY, relation.apiText("created_by"))
            .set(TRANSACTION_RELATIONS.CREATED_AT, Instant.parse(relation.apiText("created_at"))).set(TRANSACTION_RELATIONS.DECIDED_BY, relation.apiText("decided_by"))
            .set(TRANSACTION_RELATIONS.DECISION_REASON, relation.apiText("decision_reason")).execute()
    }
}
