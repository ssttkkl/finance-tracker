package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashRelationRepositoryTest {
    private val workspace = "relation-workspace"
    private val now = Instant.parse("2026-10-01T09:00:00Z")
    private val labels = linkedMapOf<Long, String>()
    private val expected: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-relation-repository.json")!!
            .bufferedReader().use { it.readText() }).jsonObject.getValue("cases").jsonObject
    }
    private val jdbcUrl: String by lazy {
        Json.parseToJsonElement(Path.of(requireNotNull(
            System.getenv("FT_KOTLIN_RELATION_REPOSITORY_CONTRACT_DIR"))).resolve("database.json").readText())
            .jsonObject.getValue("jdbc_url").jsonPrimitive.content
    }
    private fun database() = Database(jdbcUrl, System.getenv("FT_KOTLIN_TEST_DB_USER"),
        System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))

    private fun norm(value: Any?): Any? = when (value) {
        null -> null
        is BigDecimal -> value.stripTrailingZeros().toPlainString()
        is Instant -> if (value == now) ParsedImportCodec.timeText(value) else "<now>"
        is Map<*, *> -> linkedMapOf<String, Any?>().apply {
            for ((key, item) in value) put(key.toString(), norm(item))
            labels[(value["id"] as? Number)?.toLong()]?.let { put("id", it) }
        }
        is List<*> -> value.map { norm(it) }
        else -> value
    }

    private fun seed(database: Database) {
        val sqlite = database.sqlite
        val url = if (sqlite) "jdbc:sqlite:file:${Path.of(jdbcUrl.removePrefix("jdbc:sqlite:"))
            .toUri().rawPath}?mode=rw" else jdbcUrl
        java.sql.DriverManager.getConnection(url, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")).use { connection ->
            if (sqlite) connection.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
            val sql = connection.jooq(sqlite)
            sql.deleteFrom(CASH_RELATION_CANDIDATES).where(CASH_RELATION_CANDIDATES.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(CASH_TRANSACTION_COMPONENTS).where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(ACCOUNTS).where(ACCOUNTS.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(WORKSPACES).where(WORKSPACES.ID.eq(workspace)).execute()
            sql.insertInto(WORKSPACES).set(WORKSPACES.ID, workspace).set(WORKSPACES.NAME, "关系仓库工作区")
                .set(WORKSPACES.CREATED_AT, now).execute()
            for ((id, name) in listOf(9301L to "现金", 9302L to "工资卡")) {
                sql.insertInto(ACCOUNTS).set(ACCOUNTS.ID, id).set(ACCOUNTS.WORKSPACE_ID, workspace)
                    .set(ACCOUNTS.NAME, name).set(ACCOUNTS.TYPE, "cash").set(ACCOUNTS.ACTIVE, true)
                    .set(ACCOUNTS.CURRENCIES, JsonArray(listOf(JsonPrimitive("CNY"))))
                    .set(ACCOUNTS.METADATA_JSON, JsonObject(emptyMap())).set(ACCOUNTS.REVISION, 1L)
                    .set(ACCOUNTS.CREATED_AT, now).set(ACCOUNTS.UPDATED_AT, now).execute()
            }
            val rows = listOf(
                listOf(93001L, 9301L, "alipay", "REL-1", "consumption", "-100.00", "atomic", 94001L, "-100.00"),
                listOf(93002L, 9301L, "wechat", "REL-2", "refund", "30.00", "atomic", 94002L, "30.00"),
                listOf(93003L, 9302L, "ccb_debit", "REL-3", "transfer_out", "-50.00", "atomic", 94003L, "-50.00"),
                listOf(93004L, 9302L, "icbc_debit", "REL-4", "transfer_in", "50.00", "atomic", 94004L, "50.00"),
                listOf(93005L, 9301L, "alipay", "REL-5", "consumption", "-20.00", "atomic", 94005L, "-20.00"),
                listOf(93006L, 9302L, "alipay", "REL-6", "consumption", "-20.00", "aggregate", 94006L, "-12.00"),
                listOf(93007L, 9302L, "icbc_debit", "REL-7", "consumption", "-30.00", "atomic", 94008L, "-30.00"))
            for (row in rows) {
                val recordType = row[4] as String
                sql.insertInto(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.ID, row[0] as Long)
                    .set(CASH_TRANSACTIONS.WORKSPACE_ID, workspace)
                    .set(CASH_TRANSACTIONS.ACCOUNT_ID,
                        if (row[6] == "aggregate") null else row[1] as Long)
                    .set(CASH_TRANSACTIONS.STATE, "posted").set(CASH_TRANSACTIONS.RECORD_ORIGIN, "import")
                    .set(CASH_TRANSACTIONS.ROW_REVISION, 1L).set(CASH_TRANSACTIONS.CASH_GRANULARITY, row[6] as String)
                    .set(CASH_TRANSACTIONS.SOURCE_TYPE, row[2] as String).set(CASH_TRANSACTIONS.RECORD_ID, row[3] as String)
                    .set(CASH_TRANSACTIONS.SOURCE_PAYLOAD, JsonObject(emptyMap()))
                    .set(CASH_TRANSACTIONS.OCCURRED_AT, now).set(CASH_TRANSACTIONS.AMOUNT, CashStoredDecimal(row[5] as String))
                    .set(CASH_TRANSACTIONS.CURRENCY, "CNY").set(CASH_TRANSACTIONS.COUNTERPARTY, "商户")
                    .set(CASH_TRANSACTIONS.COUNTERPARTY_ACCOUNT, "")
                    .set(CASH_TRANSACTIONS.COUNTERPARTY_ACCOUNT_ATTRS, JsonArray(emptyList()))
                    .set(CASH_TRANSACTIONS.NOTE, "").setNull(CASH_TRANSACTIONS.CATEGORY_ID)
                    .set(CASH_TRANSACTIONS.RECORD_TYPE, recordType)
                    .set(CASH_TRANSACTIONS.RECORD_SUBTYPE,
                        if (recordType.startsWith("transfer_")) "ordinary_transfer" else "not_applicable")
                    .set(CASH_TRANSACTIONS.CREATED_AT, now).execute()
            }
            for (row in rows) {
                sql.insertInto(CASH_TRANSACTION_COMPONENTS).set(CASH_TRANSACTION_COMPONENTS.ID, row[7] as Long)
                    .set(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID, workspace)
                    .set(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID, row[0] as Long)
                    .set(CASH_TRANSACTION_COMPONENTS.ACCOUNT_ID, 9301L)
                    .set(CASH_TRANSACTION_COMPONENTS.AMOUNT, CashStoredDecimal(row[8] as String))
                    .set(CASH_TRANSACTION_COMPONENTS.CURRENCY, "CNY").set(CASH_TRANSACTION_COMPONENTS.ORDINAL, 0L)
                    .set(CASH_TRANSACTION_COMPONENTS.LABEL, "").set(CASH_TRANSACTION_COMPONENTS.SOURCE_KEY, "")
                    .set(CASH_TRANSACTION_COMPONENTS.METADATA_JSON, JsonObject(emptyMap()))
                    .set(CASH_TRANSACTION_COMPONENTS.CREATED_AT, now).execute()
            }
            sql.insertInto(CASH_TRANSACTION_COMPONENTS).set(CASH_TRANSACTION_COMPONENTS.ID, 94007L)
                .set(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID, workspace)
                .set(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID, 93006L)
                .set(CASH_TRANSACTION_COMPONENTS.ACCOUNT_ID, 9301L)
                .set(CASH_TRANSACTION_COMPONENTS.AMOUNT, CashStoredDecimal("-8.00"))
                .set(CASH_TRANSACTION_COMPONENTS.CURRENCY, "CNY").set(CASH_TRANSACTION_COMPONENTS.ORDINAL, 1L)
                .set(CASH_TRANSACTION_COMPONENTS.LABEL, "").set(CASH_TRANSACTION_COMPONENTS.SOURCE_KEY, "")
                .set(CASH_TRANSACTION_COMPONENTS.METADATA_JSON, JsonObject(emptyMap()))
                .set(CASH_TRANSACTION_COMPONENTS.CREATED_AT, now).execute()
            sql.insertInto(TRANSACTION_RELATIONS).set(TRANSACTION_RELATIONS.ID, 9001L)
                .set(TRANSACTION_RELATIONS.WORKSPACE_ID, workspace).set(TRANSACTION_RELATIONS.KIND, "payment_mirror")
                .set(TRANSACTION_RELATIONS.SUBTYPE, "")
                .set(TRANSACTION_RELATIONS.PRIMARY_TRANSACTION_ID, 93001L)
                .set(TRANSACTION_RELATIONS.SECONDARY_TRANSACTION_ID, 93005L)
                .set(TRANSACTION_RELATIONS.PRIMARY_COMPONENT_ID, 94001L)
                .set(TRANSACTION_RELATIONS.SECONDARY_COMPONENT_ID, 94005L)
                .set(TRANSACTION_RELATIONS.ANCHOR_TRANSACTION_ID, 93001L)
                .set(TRANSACTION_RELATIONS.ANCHOR_COMPONENT_ID, 94001L)
                .set(TRANSACTION_RELATIONS.PRIMARY_ROLE, "expense").set(TRANSACTION_RELATIONS.SECONDARY_ROLE, "expense")
                .set(TRANSACTION_RELATIONS.STATE, "confirmed").set(TRANSACTION_RELATIONS.ACTIVE, true)
                .set(TRANSACTION_RELATIONS.ROW_REVISION, 1L).setNull(TRANSACTION_RELATIONS.DECISION_GROUP_ID)
                .set(TRANSACTION_RELATIONS.DECISION_SOURCE, "rule")
                .set(TRANSACTION_RELATIONS.EVIDENCE, JsonObject(emptyMap()))
                .set(TRANSACTION_RELATIONS.APPLIED_AMOUNT, CashStoredDecimal("20.00"))
                .set(TRANSACTION_RELATIONS.RULE_ID, "mirror.seed.v1")
                .set(TRANSACTION_RELATIONS.CREATED_BY, "system").set(TRANSACTION_RELATIONS.CREATED_AT, now)
                .set(TRANSACTION_RELATIONS.DECIDED_BY, "").setNull(TRANSACTION_RELATIONS.DECIDED_AT)
                .set(TRANSACTION_RELATIONS.DECISION_REASON, "").execute()
            sql.insertInto(CASH_RELATION_CANDIDATES)
                .set(CASH_RELATION_CANDIDATES.WORKSPACE_ID, workspace)
                .set(CASH_RELATION_CANDIDATES.RELATION_ID, 9001L)
                .set(CASH_RELATION_CANDIDATES.CANDIDATE_TRANSACTION_ID, 93007L)
                .set(CASH_RELATION_CANDIDATES.CANDIDATE_COMPONENT_ID, 94008L)
                .set(CASH_RELATION_CANDIDATES.EXPECTED_ROW_REVISION, 1L)
                .set(CASH_RELATION_CANDIDATES.EVIDENCE, JsonObject(emptyMap()))
                .set(CASH_RELATION_CANDIDATES.VALIDITY, "valid").execute()
        }
    }

    @Test fun relationRepositoryMatchesPython() {
        val database = database()
        seed(database)
        fun <T> write(block: CashRelationRepository.() -> T): T = database.write { connection ->
            CashRelationRepository(CashSqlContext(connection, workspace, database.sqlite)).block()
        }
        fun capture(block: CashRelationRepository.() -> Any?): Any? = try {
            val value = write(block)
            norm(if (value is Unit) null else value)
        } catch (failure: IllegalArgumentException) {
            linkedMapOf<String, Any?>("error" to "ValueError: ${failure.message}")
        }

        assertEquals(expected.getValue("list_all"), sourceJson(norm(write { listActive() })))
        assertEquals(expected.getValue("list_kind"), sourceJson(norm(write { listActive(kind = "payment_mirror") })))
        assertEquals(expected.getValue("list_accepted"), sourceJson(norm(write { listActive(status = "accepted") })))
        assertEquals(expected.getValue("list_superseded"), sourceJson(norm(write { listActive(status = "superseded") })))
        assertEquals(expected.getValue("get"), sourceJson(norm(write { get(9001) })))
        assertEquals(expected.getValue("get_missing"), sourceJson(norm(write { get(999999) })))
        assertEquals(expected.getValue("find"), sourceJson(norm(write {
            findByBusinessKey("payment_mirror", 93001, 93005) })))
        assertEquals(expected.getValue("find_reversed"), sourceJson(norm(write {
            findByBusinessKey("payment_mirror", 94005, 94001, componentA = 94005, componentB = 94001) })))
        assertEquals(expected.getValue("find_missing"), sourceJson(norm(write {
            findByBusinessKey("transfer_pair", 93003, 93004) })))
        assertEquals(expected.getValue("for_facts"), sourceJson(norm(write { listForFacts(listOf(93001)) })))
        assertEquals(expected.getValue("for_components"),
            sourceJson(norm(write { listForComponents(listOf(94002, 94001)) })))

        val refund = write {
            add(mapOf("kind" to "refund_offset", "primary_fact_id" to "93001", "secondary_fact_id" to "93002",
                "status" to "accepted", "rule_id" to "refund.seed.v1", "applied_amount" to "10.00",
                "created_by" to "system", "anchor_fact_id" to "93001"))
        }
        labels[refund] = "added_1"
        val mirror = write {
            add(mapOf("kind" to "payment_mirror", "primary_fact_id" to "93002", "secondary_fact_id" to "93004",
                "status" to "accepted", "rule_id" to "mirror.seed.v1"))
        }
        labels[mirror] = "added_2"
        val open = write {
            add(mapOf("kind" to "transfer_pair", "primary_fact_id" to "93003", "status" to "pending_review",
                "rule_id" to "transfer.open.v1", "anchor_fact_id" to "93003",
                "candidate_fact_ids" to listOf(94004, 94008), "subtype" to "ordinary_transfer"))
        }
        labels[open] = "added_3"
        assertEquals(expected.getValue("added"), sourceJson(norm(listOf(
            write { get(refund) }, write { get(mirror) }, write { get(open) }))))

        val errors = listOf(
            capture { add(mapOf("kind" to "refund_offset", "primary_fact_id" to "93001",
                "secondary_fact_id" to "93002", "status" to "accepted", "applied_amount" to "-1.00")) },
            capture { add(mapOf("kind" to "refund_offset", "primary_fact_id" to "93005",
                "secondary_fact_id" to "93002", "status" to "accepted", "applied_amount" to "999.00")) },
            capture { add(mapOf("kind" to "refund_offset", "primary_fact_id" to "93006",
                "secondary_fact_id" to "93002", "status" to "accepted")) },
            capture { add(mapOf("kind" to "refund_offset", "primary_fact_id" to "93001",
                "secondary_fact_id" to "93002", "secondary_component_id" to 999999, "status" to "accepted")) })
        assertEquals(expected.getValue("add_errors"), sourceJson(errors))
        assertEquals(expected.getValue("open_leg"),
            sourceJson(norm(write { findOpenLeg("transfer_pair", 93003, "ordinary_transfer") })))
        assertEquals(expected.getValue("bind"), sourceJson(norm(write {
            bindOtherLeg(open, 93004, status = "accepted", decidedBy = "user", decisionReason = "人工配对") })))
        assertEquals(expected.getValue("bind_candidates"),
            sourceJson(capture { updateOpenLegCandidates(open, listOf(94008)) }))
        assertEquals(expected.getValue("reject"), sourceJson(norm(write {
            updateStatus(refund, status = "rejected", decidedBy = "user", decisionReason = "人工拒绝") })))
        assertEquals(expected.getValue("supersede"), sourceJson(norm(write {
            updateStatus(9001, status = "superseded") })))
        assertEquals(expected.getValue("batch"),
            sourceJson(capture { updateStatusBatch(listOf(mirror), status = "accepted", decidedBy = "system") }))
        assertEquals(expected.getValue("batch_superseded"),
            sourceJson(capture { updateStatusBatch(listOf(mirror), status = "superseded") }))
        assertEquals(expected.getValue("update_kind"), sourceJson(norm(write {
            updateKind(mirror, "refund_offset", "p2p_return", "user", "改类") })))
        assertEquals(expected.getValue("final_active"), sourceJson(norm(write { listActive() })))

        val finalRows = database.read { connection ->
            val sql = connection.jooq(database.sqlite)
            sql.selectFrom(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(workspace))
                .orderBy(TRANSACTION_RELATIONS.ID).fetch().map { row ->
                    linkedMapOf<String, Any?>("id" to row.id, "kind" to row.kind, "subtype" to row.subtype,
                        "primary_transaction_id" to row.primaryTransactionId,
                        "secondary_transaction_id" to row.secondaryTransactionId,
                        "primary_component_id" to row.primaryComponentId,
                        "secondary_component_id" to row.secondaryComponentId,
                        "anchor_transaction_id" to row.anchorTransactionId,
                        "anchor_component_id" to row.anchorComponentId, "primary_role" to row.primaryRole,
                        "secondary_role" to row.secondaryRole, "state" to row.state, "active" to row.active,
                        "row_revision" to row.rowRevision, "decision_source" to row.decisionSource,
                        "applied_amount" to row.appliedAmount?.let { BigDecimal(fixedCashDecimal(it)) },
                        "rule_id" to row.ruleId, "created_by" to row.createdBy, "decided_by" to row.decidedBy,
                        "decision_reason" to row.decisionReason)
                }
        }
        @Suppress("UNCHECKED_CAST")
        val sortedFinalRows = (norm(finalRows) as List<Map<String, Any?>>).sortedBy { it["id"].toString() }
        assertEquals(expected.getValue("final_rows"), sourceJson(sortedFinalRows))
        val finalCandidates = database.read { connection ->
            val sql = connection.jooq(database.sqlite)
            sql.select(CASH_RELATION_CANDIDATES.RELATION_ID, CASH_RELATION_CANDIDATES.CANDIDATE_COMPONENT_ID)
                .from(CASH_RELATION_CANDIDATES).where(CASH_RELATION_CANDIDATES.WORKSPACE_ID.eq(workspace))
                .fetch().map { listOf(labels[it.value1()] ?: it.value1(), it.value2()) }
                .sortedBy { (it[1] as? Number)?.toLong() ?: 0 }
        }
        assertEquals(expected.getValue("final_candidates"), sourceJson(finalCandidates))
    }
}
