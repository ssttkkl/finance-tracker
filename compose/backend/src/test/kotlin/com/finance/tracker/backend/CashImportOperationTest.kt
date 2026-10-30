package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportOperationTest {
    private val workspace = "operation-workspace"
    private val now = Instant.parse("2026-10-01T09:00:00Z")
    private val expected: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-import-operations.json")!!
            .bufferedReader().use { it.readText() }).jsonObject
    }
    private fun databaseFile() = Json.parseToJsonElement(Path.of(requireNotNull(
        System.getenv("FT_KOTLIN_PLAN_BUILD_CONTRACT_DIR"))).resolve("database.json").readText()).jsonObject
    private fun database() = databaseFile().let {
        Database(it.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }

    private fun coverage() = CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1", endInclusive = false)
    private fun coverageJson(value: CashSourceCoverage): JsonObject = buildJsonObject {
        put("start", value.start?.let(::JsonPrimitive) ?: JsonNull); put("end", value.end?.let(::JsonPrimitive) ?: JsonNull)
        put("complete", value.complete); put("evidence", value.evidence); put("filters", sourceJson(value.filters))
        put("start_inclusive", value.startInclusive); put("end_inclusive", value.endInclusive)
        put("time_precision", value.timePrecision); put("strategy_version", value.strategyVersion)
        put("document_key", value.documentKey?.let(::JsonPrimitive) ?: JsonNull)
        put("page_total", value.pageTotal?.let(::JsonPrimitive) ?: JsonNull)
        put("page_fingerprints", sourceJson(value.pageFingerprints))
    }

    private fun seed(jdbcUrl: String, sqlite: Boolean) {
        java.sql.DriverManager.getConnection(jdbcUrl.replace("jdbc:sqlite:", "jdbc:sqlite:file:") +
            (if (sqlite) "?mode=rw" else ""), System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")).use { connection ->
            if (sqlite) connection.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
            val sql = connection.jooq(sqlite)
            sql.deleteFrom(CASH_ENTRY_MEMBERS).where(CASH_ENTRY_MEMBERS.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(CASH_ENTRIES).where(CASH_ENTRIES.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(CASH_TRANSACTION_ISSUES).where(CASH_TRANSACTION_ISSUES.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(CASH_SOURCE_BINDINGS).where(CASH_SOURCE_BINDINGS.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(CASH_TRANSACTION_COMPONENTS).where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(CASH_CATEGORIES).where(CASH_CATEGORIES.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(ACCOUNTS).where(ACCOUNTS.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(WORKSPACES).where(WORKSPACES.ID.eq(workspace)).execute()
            val w = WORKSPACES
            sql.insertInto(w).set(w.ID, workspace).set(w.NAME, "操作规划工作区").set(w.CREATED_AT, now).execute()
            val a = ACCOUNTS
            for ((identifier, name, currency, revision) in listOf(
                listOf(9201L, "合成现金", "CNY", 1L), listOf(9202L, "合成美元", "USD", 2L))) {
                sql.insertInto(a).set(a.ID, identifier as Long).set(a.WORKSPACE_ID, workspace).set(a.NAME, name as String)
                    .set(a.TYPE, "cash").set(a.ACTIVE, true).set(a.CURRENCIES, JsonArray(listOf(JsonPrimitive(currency as String))))
                    .set(a.METADATA_JSON, JsonObject(emptyMap())).set(a.REVISION, revision as Long)
                    .set(a.CREATED_AT, now).set(a.UPDATED_AT, now).execute()
            }
            val category = CASH_CATEGORIES
            sql.insertInto(category).set(category.ID, "op-food").set(category.WORKSPACE_ID, workspace)
                .set(category.PARENT_SCOPE_KEY, "__root__").set(category.NAME, "餐饮").set(category.NORMALIZED_NAME, "餐饮")
                .set(category.CATEGORY_PATH, "/op-food/").set(category.DEPTH, 1).set(category.SORT_ORDER, 1)
                .set(category.REVISION, 1L).set(category.CREATED_AT, now).set(category.UPDATED_AT, now).execute()
            data class Tx(val id: Long, val source: String, val record: String, val state: String, val amount: String,
                val note: String, val occurred: Instant, val payload: Map<String, Any?>)
            val tx = CASH_TRANSACTIONS
            for (item in listOf(
                Tx(7501L, "alipay", "ALI-1", "posted", "-12.30", "", Instant.parse("2026-10-01T10:00:00Z"),
                    mapOf("订单号" to "ALI-1", "付款方式" to "支付宝余额")),
                Tx(7502L, "wechat", "wechat_W1", "draft", "-9.00", "旧备注", Instant.parse("2026-10-03T10:00:00Z"),
                    mapOf("交易单号" to "W1", "付款方式" to "零钱")),
            )) {
                sql.insertInto(tx).set(tx.ID, item.id).set(tx.WORKSPACE_ID, workspace).set(tx.ACCOUNT_ID, 9201L)
                    .set(tx.STATE, item.state).set(tx.RECORD_ORIGIN, "import").set(tx.CASH_GRANULARITY, "atomic")
                    .set(tx.SOURCE_TYPE, item.source).set(tx.RECORD_ID, item.record).set(tx.SOURCE_PAYLOAD, sourceJson(item.payload))
                    .set(tx.OCCURRED_AT, item.occurred).set(tx.AMOUNT, storedCashDecimal(item.amount, sqlite))
                    .set(tx.CURRENCY, "CNY").set(tx.COUNTERPARTY, "商户").set(tx.COUNTERPARTY_ACCOUNT, "")
                    .set(tx.COUNTERPARTY_ACCOUNT_ATTRS, JsonArray(emptyList())).set(tx.NOTE, item.note)
                    .set(tx.RECORD_TYPE, "consumption").set(tx.RECORD_SUBTYPE, "not_applicable")
                    .set(tx.ROW_REVISION, 1L).set(tx.CREATED_AT, now).execute()
            }
            val component = CASH_TRANSACTION_COMPONENTS
            sql.insertInto(component).set(component.ID, 9501L).set(component.WORKSPACE_ID, workspace)
                .set(component.CASH_TRANSACTION_ID, 7501L).set(component.ACCOUNT_ID, 9201L)
                .set(component.AMOUNT, storedCashDecimal("-12.30", sqlite)).set(component.CURRENCY, "CNY")
                .set(component.ORDINAL, 0L).set(component.LABEL, "").set(component.SOURCE_KEY, "")
                .set(component.METADATA_JSON, JsonObject(emptyMap())).set(component.CREATED_AT, now).execute()
            val binding = CASH_SOURCE_BINDINGS
            for ((transaction, source, scope, provider) in listOf(
                listOf(7501L, "alipay", "owner", "ALI-1"), listOf(7502L, "wechat", CashSourceScope.WECHAT_DEFAULT_SCOPE, "W1"))) {
                sql.insertInto(binding).set(binding.WORKSPACE_ID, workspace).set(binding.TRANSACTION_ID, transaction as Long)
                    .set(binding.SOURCE_TYPE, source as String).set(binding.SOURCE_SCOPE_KIND, "account")
                    .set(binding.SOURCE_SCOPE_KEY, scope as String).set(binding.IDENTITY_KIND, "provider_id")
                    .set(binding.IDENTITY_KEY, providerIdentityKey(provider as String)).set(binding.IDENTITY_STRENGTH, "provider")
                    .set(binding.STABLE_EVIDENCE_KEY, null as String?).set(binding.STRATEGY_VERSION, "source-coverage-v1")
                    .set(binding.COVERAGE, coverageJson(coverage())).set(binding.REVISION, 1L)
                    .set(binding.CREATED_AT, now).set(binding.UPDATED_AT, now).execute()
            }
            val issue = CASH_TRANSACTION_ISSUES
            sql.insertInto(issue).set(issue.ID, 5501L).set(issue.WORKSPACE_ID, workspace).set(issue.TRANSACTION_ID, 7501L)
                .set(issue.ISSUE_KEY, "relation:sg1").set(issue.CODE, "relation_pending").set(issue.FIELD_PATH, null as String?)
                .set(issue.PENDING_SOURCE_PAYLOAD, null as JsonElement?).set(issue.PENDING_FIELDS, null as JsonElement?)
                .set(issue.ORIGIN, JsonObject(emptyMap())).set(issue.REVISION, 1L)
                .set(issue.CREATED_AT, now).set(issue.UPDATED_AT, now).execute()
            val entry = CASH_ENTRIES
            sql.insertInto(entry).set(entry.ID, "entry-7501").set(entry.WORKSPACE_ID, workspace)
                .set(entry.ROOT_TRANSACTION_ID, 7501L).set(entry.REVISION, 1L).set(entry.CATEGORY_ID, null as String?)
                .set(entry.NOTE, "").set(entry.COUNTERPARTY, "商户").set(entry.COUNTERPARTY_ACCOUNT, "")
                .set(entry.COUNTERPARTY_ACCOUNT_ATTRS, JsonArray(emptyList())).set(entry.MANUAL_FIELDS, JsonArray(emptyList()))
                .set(entry.SPLIT_INHERIT, null as Boolean?).execute()
            sql.insertInto(entry).set(entry.ID, "entry-7502").set(entry.WORKSPACE_ID, workspace)
                .set(entry.ROOT_TRANSACTION_ID, 7502L).set(entry.REVISION, 1L).set(entry.CATEGORY_ID, null as String?)
                .set(entry.NOTE, "旧备注").set(entry.COUNTERPARTY, "商户").set(entry.COUNTERPARTY_ACCOUNT, "")
                .set(entry.COUNTERPARTY_ACCOUNT_ATTRS, JsonArray(emptyList())).set(entry.MANUAL_FIELDS, JsonArray(emptyList()))
                .set(entry.SPLIT_INHERIT, null as Boolean?).execute()
            val member = CASH_ENTRY_MEMBERS
            sql.insertInto(member).set(member.WORKSPACE_ID, workspace).set(member.TRANSACTION_ID, 7501L)
                .set(member.ENTRY_ID, "entry-7501").set(member.STANDALONE_VALUES, JsonObject(emptyMap()))
                .set(member.STANDALONE_MANUAL_FIELDS, JsonArray(emptyList())).execute()
            sql.insertInto(member).set(member.WORKSPACE_ID, workspace).set(member.TRANSACTION_ID, 7502L)
                .set(member.ENTRY_ID, "entry-7502").set(member.STANDALONE_VALUES, JsonObject(emptyMap()))
                .set(member.STANDALONE_MANUAL_FIELDS, JsonArray(emptyList())).execute()
        }
    }

    private fun row(position: Int, provider: String, occurred: String, amount: String, method: String,
        error: String? = null) = ParsedCashRow(position, linkedMapOf(
            "amount" to amount, "currency" to "CNY", "occurred_at" to occurred, "record_type" to "consumption",
            "record_subtype" to "not_applicable", "counterparty" to "商户", "counterparty_account" to "",
            "counterparty_account_attrs" to emptyList<String>(), "note" to "",
            "source_payload" to linkedMapOf("订单号" to provider, "付款方式" to method)),
        paymentMethod = method, providerId = provider, errorCode = error)

    private fun files(): List<ParsedImportFile> {
        val alipay = ParsedCashDocument("alipay", "fixture:0", "fixture-v1", listOf(
            row(1, "ALI-1", "2026-10-01T10:00:00+00:00", "-12.30", "支付宝余额"),
            row(2, "NEW-1", "2026-10-02T10:00:00+00:00", "-5.00", "支付宝余额"),
            row(3, "BAD-1", "2026-10-05T10:00:00+00:00", "bad", "支付宝余额", error = "amount_unreadable")),
            "owner", "合成支付宝", coverage(), 3, 3)
        val wechat = ParsedCashDocument("wechat", "fixture:0", "fixture-v1", listOf(
            row(1, "W1", "2026-10-03T10:00:00+00:00", "-9.00", "零钱")),
            CashSourceScope.WECHAT_DEFAULT_SCOPE, "合成微信", coverage(), 1, 1)
        return listOf(
            ParsedImportFile("f_a" + "0".repeat(31), "a".repeat(64), "parsed", alipay,
                clientFileRef = "client-a", filename = "支付宝账单.csv", size = 100, sourceType = "alipay"),
            ParsedImportFile("f_w" + "0".repeat(31), "w".repeat(64), "parsed", wechat,
                clientFileRef = "client-w", filename = "微信账单.csv", size = 100, sourceType = "wechat"))
    }

    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        is JsonPrimitive -> if (value.isString && Regex("-?(?:0|[1-9][0-9]*)\\.[0-9]+").matches(value.content))
            JsonPrimitive(BigDecimal(value.content).stripTrailingZeros().toPlainString()) else value
        else -> value
    }

    @Test fun operationsMatchPythonContracts() {
        val db = database()
        seed(databaseFile().getValue("jdbc_url").jsonPrimitive.content, db.sqlite)
        val selections = expected.getValue("groups").jsonArray.map { group ->
            mapOf("source_account_ref" to group.jsonObject.getValue("source_account_ref").jsonPrimitive.content,
                "account_id" to "9201", "expected_account_revision" to 1)
        }
        @Suppress("UNCHECKED_CAST")
        val choices = cashSourceValue(expected.getValue("choices")) as List<Any?>
        db.read { connection ->
            val prepared = CashImportOperationPlanner(CashSqlContext(connection, workspace, db.sqlite))
                .prepare(files(), selections, choices)
            assertEquals(canonical(expected.getValue("rows")), canonical(sourceJson(prepared.rows)))
            assertEquals(canonical(expected.getValue("operations")), canonical(sourceJson(prepared.operations)))
            assertEquals(expected.getValue("candidate_sets"), sourceJson(prepared.candidateSets))
            val snapshotContext = CashSqlContext(connection, workspace, db.sqlite)
            val preview = CashImportPreviewPreparation(snapshotContext).prepare(prepared.operations, prepared.source.rows.rows)
            val snapshot = CashImportPreviewSnapshotBuilder(snapshotContext, now = { now }).build(preview, prepared.rows)
            assertEquals(3, snapshot.values.size)
            assertEquals(3, snapshot.parentOps.size)
            assertEquals(3, snapshot.entryPublic.size)
            @Suppress("UNCHECKED_CAST")
            val deferredChoices = cashSourceValue(expected.getValue("deferred").jsonObject.getValue("choices")) as List<Any?>
            val deferred = CashImportOperationPlanner(CashSqlContext(connection, workspace, db.sqlite))
                .prepare(files(), selections, deferredChoices)
            val deferredExpected = expected.getValue("deferred").jsonObject
            assertEquals(canonical(deferredExpected.getValue("rows")), canonical(sourceJson(deferred.rows)))
            assertEquals(canonical(deferredExpected.getValue("operations")), canonical(sourceJson(deferred.operations)))
            assertEquals(deferredExpected.getValue("candidate_sets"), sourceJson(deferred.candidateSets))
        }
    }
}
