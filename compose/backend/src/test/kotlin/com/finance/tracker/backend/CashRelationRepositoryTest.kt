package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashRelationRepositoryTest {
    private val workspace = RelationContractFixture.workspace
    private val now = RelationContractFixture.now
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


    @Test fun relationRepositoryMatchesPython() {
        val database = database()
        RelationContractFixture.seed(database, jdbcUrl)
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
