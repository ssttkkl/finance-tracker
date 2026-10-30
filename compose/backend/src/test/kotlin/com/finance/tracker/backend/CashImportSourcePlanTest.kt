package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportSourcePlanTest {
    private val workspace = "source-plan-workspace"
    private val documentKey = "d".repeat(64)
    private val pageFingerprint = "e".repeat(64)
    private val emptyFingerprint = "f".repeat(64)
    private val now = Instant.parse("2026-10-01T09:00:00Z")

    private fun databaseFile() = Json.parseToJsonElement(Path.of(requireNotNull(
        System.getenv("FT_KOTLIN_PLAN_BUILD_CONTRACT_DIR"))).resolve("database.json").readText()).jsonObject
    private fun database() = databaseFile().let {
        Database(it.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    private fun golden(): JsonObject = Json.parseToJsonElement(javaClass.getResourceAsStream(
        "/python-cash-import-source-plan.json")!!.bufferedReader().use { it.readText() }).jsonObject

    private fun coverage(document: Boolean = false): CashSourceCoverage = CashSourceCoverage(
        "2026-10-01", "2026-10-04", document, "fixture", mapOf("account" to "fixture"), "fixture-v1",
        documentKey = if (document) documentKey else null, pageTotal = if (document) 2 else null,
        pageFingerprints = if (document) linkedMapOf("1" to pageFingerprint, "2" to emptyFingerprint) else emptyMap())

    private fun coverageJson(value: CashSourceCoverage): JsonObject = buildJsonObject {
        put("start", value.start?.let(::JsonPrimitive) ?: JsonNull)
        put("end", value.end?.let(::JsonPrimitive) ?: JsonNull)
        put("complete", value.complete)
        put("evidence", value.evidence)
        put("filters", sourceJson(value.filters))
        put("start_inclusive", value.startInclusive)
        put("end_inclusive", value.endInclusive)
        put("time_precision", value.timePrecision)
        put("strategy_version", value.strategyVersion)
        put("document_key", value.documentKey?.let(::JsonPrimitive) ?: JsonNull)
        put("page_total", value.pageTotal?.let(::JsonPrimitive) ?: JsonNull)
        put("page_fingerprints", sourceJson(value.pageFingerprints))
    }

    private fun wire(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is BigDecimal -> JsonPrimitive(value.stripTrailingZeros().toPlainString())
        is Instant -> JsonPrimitive(ParsedImportCodec.timeText(value))
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to wire(it.value) })
        is List<*> -> JsonArray(value.map(::wire))
        is Number, is Boolean, is String -> sourceJson(value)
        else -> sourceJson(value)
    }

    private fun recordJson(record: SourceRecord): JsonObject = buildJsonObject {
        put("transaction_id", record.transactionId); put("source_type", record.sourceType)
        put("source_account_key", record.sourceAccountKey?.let(::JsonPrimitive) ?: JsonNull)
        put("fields", wire(record.fields))
        put("provider_id", record.providerId?.let(::JsonPrimitive) ?: JsonNull)
        put("provider_binding_key", record.providerBindingKey?.let(::JsonPrimitive) ?: JsonNull)
        put("stable_evidence_binding_key", record.stableEvidenceBindingKey?.let(::JsonPrimitive) ?: JsonNull)
        put("binding_keys", JsonArray(record.bindingKeys.sorted().map(::JsonPrimitive)))
        put("row_revision", record.rowRevision)
        put("coverages", JsonArray(record.coverages.map(::coverageJson)))
        put("file_occurrences", JsonObject(record.fileOccurrences.mapValues { JsonArray(it.value.sorted().map(::JsonPrimitive)) }))
        put("document_evidence", JsonArray(record.documentEvidence.map { evidence ->
            buildJsonObject { put("source_type", evidence.sourceType); put("parser_version", evidence.parserVersion)
                put("coverage", coverageJson(evidence.coverage)) }
        }))
    }

    private fun evidenceJson(evidence: SourceDocumentEvidence): JsonObject = buildJsonObject {
        put("source_type", evidence.sourceType); put("parser_version", evidence.parserVersion)
        put("coverage", coverageJson(evidence.coverage))
    }

    private fun sortRecords(value: JsonElement): JsonElement = JsonArray(value.jsonArray.sortedBy {
        it.jsonObject.getValue("transaction_id").jsonPrimitive.content
    })
    private fun sortEvidence(value: JsonElement): JsonElement = JsonArray(value.jsonArray.sortedBy {
        val item = it.jsonObject
        listOf(item.getValue("source_type").jsonPrimitive.content, item.getValue("parser_version").jsonPrimitive.content,
            item.getValue("coverage").jsonObject["document_key"]?.jsonPrimitive?.contentOrNull.orEmpty()).joinToString("\u0000")
    })

    private fun resolutionJson(value: SourceBatchResolution): JsonObject = buildJsonObject {
        put("row_ref", value.rowRef); put("action", value.action)
        put("transaction_id", value.transactionId?.let(::JsonPrimitive) ?: JsonNull)
        put("candidate_ids", sourceJson(value.candidateIds)); put("reason", value.reason); put("bind", value.bind)
        put("duplicate_of", value.duplicateOf?.let(::JsonPrimitive) ?: JsonNull)
        put("group_ref", value.groupRef?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun seed(jdbcUrl: String, sqlite: Boolean) {
        java.sql.DriverManager.getConnection(jdbcUrl.replace("jdbc:sqlite:", "jdbc:sqlite:file:") +
            (if (sqlite) "?mode=rw" else ""), System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")).use { connection ->
            if (sqlite) connection.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
            val sql = connection.jooq(sqlite)
            sql.deleteFrom(CASH_SOURCE_EMPTY_PAGES).where(CASH_SOURCE_EMPTY_PAGES.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(CASH_SOURCE_BINDINGS).where(CASH_SOURCE_BINDINGS.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(CASH_TRANSACTION_COMPONENTS).where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(ACCOUNTS).where(ACCOUNTS.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(WORKSPACES).where(WORKSPACES.ID.eq(workspace)).execute()
            val w = WORKSPACES
            sql.insertInto(w).set(w.ID, workspace).set(w.NAME, "来源规划工作区").set(w.CREATED_AT, now).execute()
            val a = ACCOUNTS
            for (identifier in listOf(9101L, 9102L)) sql.insertInto(a).set(a.ID, identifier).set(a.WORKSPACE_ID, workspace)
                .set(a.NAME, "合成账户$identifier").set(a.TYPE, "cash").set(a.ACTIVE, true)
                .set(a.CURRENCIES, JsonArray(listOf(JsonPrimitive("CNY")))).set(a.METADATA_JSON, JsonObject(emptyMap()))
                .set(a.REVISION, 1L).set(a.CREATED_AT, now).set(a.UPDATED_AT, now).execute()
            val tx = CASH_TRANSACTIONS
            data class TxSeed(val id: Long, val source: String, val record: String, val origin: String,
                val occurred: Instant, val amount: String)
            for (seed in listOf(
                TxSeed(7001L, "wechat", "wechat_ORDER-1", "import", Instant.parse("2026-10-01T10:00:00Z"), "12.30"),
                TxSeed(7002L, "alipay", "ALI-1", "import", Instant.parse("2026-10-02T10:00:00Z"), "-3.00"),
                TxSeed(7003L, "wechat", "wechat_ ORDER-2 ", "legacy", Instant.parse("2026-10-03T10:00:00Z"), "9.00"),
            )) {
                sql.insertInto(tx).set(tx.ID, seed.id).set(tx.WORKSPACE_ID, workspace).set(tx.ACCOUNT_ID, 9101L)
                    .set(tx.STATE, "posted").set(tx.RECORD_ORIGIN, seed.origin).set(tx.CASH_GRANULARITY, "atomic")
                    .set(tx.SOURCE_TYPE, seed.source).set(tx.RECORD_ID, seed.record)
                    .set(tx.SOURCE_PAYLOAD, if (seed.source == "wechat") sourceJson(mapOf(
                        "交易单号" to seed.record.removePrefix("wechat_"))) else sourceJson(mapOf("订单号" to "ALI-1")))
                    .set(tx.OCCURRED_AT, seed.occurred).set(tx.AMOUNT, storedCashDecimal(seed.amount, sqlite))
                    .set(tx.CURRENCY, "CNY").set(tx.COUNTERPARTY, "商户").set(tx.COUNTERPARTY_ACCOUNT, "")
                    .set(tx.COUNTERPARTY_ACCOUNT_ATTRS, JsonArray(emptyList()))
                    .set(tx.NOTE, "").set(tx.RECORD_TYPE, "consumption").set(tx.RECORD_SUBTYPE, "not_applicable")
                    .set(tx.ROW_REVISION, 1L).set(tx.CREATED_AT, now).execute()
            }
            val component = CASH_TRANSACTION_COMPONENTS
            sql.insertInto(component).set(component.ID, 9001L).set(component.WORKSPACE_ID, workspace)
                .set(component.CASH_TRANSACTION_ID, 7001L).set(component.ACCOUNT_ID, 9101L)
                .set(component.AMOUNT, storedCashDecimal("12.30", sqlite)).set(component.CURRENCY, "CNY")
                .set(component.ORDINAL, 0L).set(component.LABEL, "").set(component.SOURCE_KEY, "")
                .set(component.METADATA_JSON, JsonObject(emptyMap())).set(component.CREATED_AT, now).execute()
            val occurrence = SourceOccurrence("wechat", CashSourceScope.WECHAT_DEFAULT_SCOPE, "a".repeat(64),
                "fixture-v1", "fixture-table", 1, emptyMap(), coverage(), providerId = "ORDER-1")
            val documentRow = occurrence.copy(coverage = coverage(true), documentPosition = "p1:t1:r1")
            val documentScope = sourceDigest("source-document-v1", "wechat", "fixture-v1", documentKey)
            val bindings = listOf(
                listOf(7001L, "wechat", "account", CashSourceScope.WECHAT_DEFAULT_SCOPE, "provider_id",
                    providerIdentityKey("ORDER-1"), null, coverage()),
                listOf(7001L, "wechat", "file_local", occurrence.fileScopeKey, "file_occurrence",
                    occurrence.occurrenceKey, null, coverage()),
                listOf(7001L, "wechat", "file_local", documentScope, "file_occurrence",
                    documentRow.documentOccurrenceKey!!, null, coverage(true)),
            )
            val b = CASH_SOURCE_BINDINGS
            for (binding in bindings) sql.insertInto(b).set(b.WORKSPACE_ID, workspace)
                .set(b.TRANSACTION_ID, binding[0] as Long).set(b.SOURCE_TYPE, binding[1] as String)
                .set(b.SOURCE_SCOPE_KIND, binding[2] as String).set(b.SOURCE_SCOPE_KEY, binding[3] as String)
                .set(b.IDENTITY_KIND, binding[4] as String).set(b.IDENTITY_KEY, binding[5] as String)
                .set(b.IDENTITY_STRENGTH, "validated").set(b.STABLE_EVIDENCE_KEY, binding[6] as String?)
                .set(b.STRATEGY_VERSION, "fixture-v1").set(b.COVERAGE, coverageJson(binding[7] as CashSourceCoverage))
                .set(b.REVISION, 1L).set(b.CREATED_AT, now).set(b.UPDATED_AT, now).execute()
            val empty = CASH_SOURCE_EMPTY_PAGES
            sql.insertInto(empty).set(empty.WORKSPACE_ID, workspace).set(empty.SOURCE_TYPE, "wechat")
                .set(empty.PARSER_VERSION, "fixture-v1").set(empty.DOCUMENT_KEY, documentKey).set(empty.PAGE_NUMBER, 2)
                .set(empty.COVERAGE, coverageJson(coverage(true).copy(complete = false, evidence = "",
                    pageFingerprints = linkedMapOf("2" to emptyFingerprint)))).execute()
        }
    }

    private fun rows(position: Int, provider: String, occurred: String, amount: String, method: String) = ParsedCashRow(
        position, linkedMapOf("amount" to amount, "currency" to "CNY", "occurred_at" to occurred,
            "record_type" to "consumption", "record_subtype" to "not_applicable", "counterparty" to "商户",
            "counterparty_account" to "", "counterparty_account_attrs" to emptyList<String>(), "note" to "",
            "source_payload" to mapOf("交易单号" to provider, "付款方式" to method)),
        paymentMethod = method, providerId = provider)

    private fun files(): List<ParsedImportFile> {
        val documentA = ParsedCashDocument("wechat", "fixture:0", "fixture-v1", listOf(
            rows(1, "ORDER-1", "2026-10-01T10:00:00+00:00", "12.30", "零钱"),
            rows(2, "ORDER-2", "2026-10-03T10:00:00+00:00", "9.00", "零钱")),
            CashSourceScope.WECHAT_DEFAULT_SCOPE, "合成微信", coverage(true), 2, 2)
        val documentB = ParsedCashDocument("alipay", "fixture:0", "fixture-v1", listOf(
            rows(1, "NEW-1", "2026-10-04T10:00:00+00:00", "-3.00", "支付宝余额")),
            "owner", "合成支付宝", CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1"), 1, 1)
        return listOf(
            ParsedImportFile("f_a" + "0".repeat(31), "a".repeat(64), "parsed", documentA,
                clientFileRef = "client-a", filename = "微信账单.csv", size = 100, sourceType = "wechat"),
            ParsedImportFile("f_b" + "0".repeat(31), "b".repeat(64), "parsed", documentB,
                clientFileRef = "client-b", filename = "支付宝账单.csv", size = 100, sourceType = "alipay"))
    }

    @Test fun sourcePlanReadsAndViewMatchPythonContracts() {
        val db = database()
        seed(databaseFile().getValue("jdbc_url").jsonPrimitive.content, db.sqlite)
        val expected = golden()
        val preparedFiles = files()
        db.read { connection ->
            val context = CashSqlContext(connection, workspace, db.sqlite)
            val repository = CashSourceReadRepository(context)
            val batch = CashSourceRows.sourceBatchRows(preparedFiles)
            val records = repository.recordsForFile(batch.rows.values)
            assertEquals(sortRecords(expected.getValue("records")), sortRecords(JsonArray(records.map(::recordJson))))
            val docs = CashSourceDocumentReadRepository(context).read(preparedFiles,
                candidateEvidence = records.flatMap { it.documentEvidence })
            assertEquals(sortEvidence(expected.getValue("documents")), sortEvidence(JsonArray(docs.map(::evidenceJson))))
            val prepared = CashImportSourcePlanner(context).prepare(preparedFiles)
            assertEquals(sortRecords(expected.getValue("prepared").jsonObject.getValue("records")),
                sortRecords(JsonArray(prepared.records.map(::recordJson))))
            assertEquals(expected.getValue("prepared").jsonObject.getValue("rows"),
                JsonArray(prepared.plan.rows.map(::resolutionJson)))
            assertEquals(sortEvidence(expected.getValue("prepared").jsonObject.getValue("evidence")),
                sortEvidence(JsonArray(prepared.evidence.map(::evidenceJson))))
            assertTrue(prepared.plan.groups.isEmpty())
            val scan = CashImportFunding.scan(preparedFiles)
            val suggestions = CashImportAccountMappings(connection, workspace, db.sqlite).suggest(scan.groups)
            val session = ParsedImportSession("s".repeat(43), workspace, "u", now, now.plusSeconds(1800),
                java.math.BigInteger.valueOf(3), preparedFiles,
                mapOf("account_selections" to listOf(mapOf("source_account_ref" to scan.groups[0].sourceAccountRef,
                    "account_id" to "9101", "expected_account_revision" to 1, "ignored" to true)),
                    "row_choices" to listOf(mapOf("row_ref" to "f_a:r1")),
                    "entry_choices" to listOf(mapOf("x" to 1)), "unrelated" to mapOf("drop" to true)),
                mapOf("plan_id" to "ip_previous"))
            val view = CashImportSessionView.view(session, scan.groups, suggestions)
            assertEquals(expected.getValue("view"), wire(view))
        }
    }
}
