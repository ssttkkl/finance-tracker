package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashRelationServiceTest {
    private val workspace = RelationContractFixture.workspace
    private val jdbcUrl: String by lazy {
        Json.parseToJsonElement(Path.of(requireNotNull(
            System.getenv("FT_KOTLIN_RELATION_REPOSITORY_CONTRACT_DIR"))).resolve("database.json").readText())
            .jsonObject.getValue("jdbc_url").jsonPrimitive.content
    }
    private fun database() = Database(jdbcUrl, System.getenv("FT_KOTLIN_TEST_DB_USER"),
        System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    private fun golden(database: Database): JsonObject {
        val suffix = if (database.sqlite) "sqlite" else "pg"
        return Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-relation-service-$suffix.json")!!
            .bufferedReader().use { it.readText() }).jsonObject.getValue("cases").jsonObject
    }
    private fun factCase(fact: RelationFact): Map<String, Any?> = linkedMapOf(
        "id" to fact.id, "amount" to fact.signedAmount.toPlainString(), "currency" to fact.currency,
        "account_id" to fact.accountId, "account_name" to fact.accountName, "account_type" to fact.accountType,
        "occurred_at" to fact.occurredAt, "counterparty" to fact.counterparty,
        "counterparty_account" to fact.counterpartyAccount,
        "counterparty_account_attrs" to fact.counterpartyAccountAttrs,
        "payment_method" to fact.paymentMethod, "note" to fact.note, "record_type" to fact.recordType,
        "record_subtype" to fact.recordSubtype, "bill_source" to fact.billSource, "source" to fact.source,
        "record_id" to fact.recordId, "source_identity" to fact.sourceIdentity, "parent_id" to fact.parentId,
        "component_ordinal" to fact.componentOrdinal, "cash_granularity" to fact.cashGranularity,
        "raw_payload" to fact.rawPayload, "relation_metadata" to fact.relationMetadata)
    private fun evidenceJson(evidence: RelationEvidence): Map<String, Any?> = linkedMapOf(
        "amount_delta" to evidence.amountDelta, "time_delta_seconds" to evidence.timeDeltaSeconds,
        "same_currency" to evidence.sameCurrency, "counterparty_similarity" to evidence.counterpartySimilarity,
        "source_pair" to evidence.sourcePair.toList(), "rule_id" to evidence.ruleId,
        "candidate_count" to evidence.candidateCount, "signals" to evidence.signals,
        "open_leg" to evidence.openLeg, "anchor_role" to evidence.anchorRole,
        "candidate_fact_ids" to evidence.candidateFactIds, "extras" to evidence.extras)
    private fun planCase(plan: RelationPlan): Map<String, Any?> = linkedMapOf(
        "facts" to plan.facts.map(::factCase),
        "proposals" to plan.proposals.map { proposal ->
            linkedMapOf<String, Any?>("key" to CashRelationSerialization.proposalKey(proposal, plan.facts),
                "body" to linkedMapOf<String, Any?>("kind" to proposal.kind,
                    "primary_fact_id" to proposal.primaryFactId, "secondary_fact_id" to proposal.secondaryFactId,
                    "primary_fact_type" to proposal.primaryFactType,
                    "secondary_fact_type" to proposal.secondaryFactType, "subtype" to proposal.subtype,
                    "status" to proposal.status, "rule_id" to proposal.ruleId,
                    "confidence" to proposal.confidence, "evidence" to evidenceJson(proposal.evidence),
                    "created_by" to proposal.createdBy, "anchor_fact_id" to proposal.effectiveAnchorFactId,
                    "open_leg" to proposal.isOpenLeg))
        },
        "context_digest" to plan.contextDigest,
        "external_context_digest" to plan.externalContextDigest)

    @Test fun relationServiceMatchesPython() {
        val database = database()
        RelationContractFixture.seed(database, jdbcUrl)
        database.write { connection ->
            val sql = connection.jooq(database.sqlite)
            sql.deleteFrom(CASH_RELATION_CANDIDATES).where(CASH_RELATION_CANDIDATES.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(workspace)).execute()
        }
        val previewRow = linkedMapOf<String, Any?>("record_id" to "PREVIEW-1", "amount" to "-15.00",
            "currency" to "CNY", "occurred_at" to "2026-10-05T10:00:00+00:00", "record_type" to "consumption",
            "record_subtype" to "not_applicable", "counterparty" to "预览商户", "note" to "预览",
            "bill_source" to "alipay", "source_type" to "alipay", "account_id" to 9301,
            "account_name" to "现金", "account_type" to "cash", "components" to emptyList<Any?>())
        val (plan, preview, external) = database.read { connection ->
            val service = CashRelationService(CashSqlContext(connection, workspace, database.sqlite))
            Triple(service.plan(), service.plan(listOf(previewRow), listOf("93001")),
                service.externalContextDigest())
        }
        val expected = golden(database)
        assertEquals(expected.getValue("plan"), sourceJson(planCase(plan)))
        assertEquals(expected.getValue("preview"), sourceJson(planCase(preview)))
        assertEquals(expected.getValue("external_digest").jsonPrimitive.content, external)

        val cached = CashRelationSerialization.serialize(plan)
        val results = listOf(
            runCatching { database.read { connection -> CashRelationService(CashSqlContext(connection,
                workspace, database.sqlite)).validateCachedPlanContext(cached) } }.exceptionOrNull()?.message,
            runCatching { database.read { connection -> CashRelationService(CashSqlContext(connection,
                workspace, database.sqlite)).validateCachedPlanContext(cached, plan.contextDigest) } }
                .exceptionOrNull()?.message,
            runCatching { database.read { connection -> CashRelationService(CashSqlContext(connection,
                workspace, database.sqlite)).validateCachedPlanContext(
                cached + ("external_context_digest" to "0".repeat(64))) } }.exceptionOrNull()?.message)
        assertEquals(expected.getValue("validation"), sourceJson(results))
    }

    private val labels = linkedMapOf<Long, String>()
    private fun normDecision(value: Any?): Any? = when (value) {
        null -> null
        is java.math.BigDecimal -> value.stripTrailingZeros().toPlainString()
        is java.time.Instant -> if (value == RelationContractFixture.now) ParsedImportCodec.timeText(value) else "<now>"
        is Map<*, *> -> linkedMapOf<String, Any?>().apply {
            for ((key, item) in value) put(key.toString(), normDecision(item))
            labels[(value["id"] as? Number)?.toLong()]?.let { put("id", it) }
        }
        is List<*> -> value.map { normDecision(it) }
        else -> value
    }

    @Test fun relationDecisionsMatchPython() {
        val database = database()
        RelationContractFixture.seed(database, jdbcUrl)
        fun sqliteContext(connection: java.sql.Connection) = CashSqlContext(connection, workspace, database.sqlite)
        fun clearRelations() = database.write { connection ->
            val sql = connection.jooq(database.sqlite)
            sql.deleteFrom(CASH_RELATION_CANDIDATES).where(CASH_RELATION_CANDIDATES.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(workspace)).execute()
        }
        fun addRelation(label: String, payload: Map<String, Any?>): Long =
            database.write { connection -> CashRelationRepository(sqliteContext(connection)).add(payload) }
                .also { labels[it] = label }
        fun <T> withService(block: CashRelationService.() -> T): T =
            database.write { connection -> CashRelationService(sqliteContext(connection)).block() }
        fun capture(block: CashRelationService.() -> Any?): Any? = try {
            val value = withService(block)
            normDecision(if (value is RelationDecision) linkedMapOf("ok" to value.ok, "count" to value.count,
                "message" to value.message, "details" to value.details) else if (value is Unit) null else value)
        } catch (failure: IllegalArgumentException) {
            linkedMapOf("error" to "ValueError: ${failure.message}")
        }

        clearRelations()
        val ids = linkedMapOf<String, Long>()
        ids["r1"] = addRelation("r1", mapOf("kind" to "refund_offset", "primary_fact_id" to "93001",
            "secondary_fact_id" to "93002", "status" to "pending_review", "rule_id" to "refund.pending.v1",
            "applied_amount" to "10.00"))
        ids["r2"] = addRelation("r2", mapOf("kind" to "transfer_pair", "primary_fact_id" to "93003",
            "status" to "pending_review", "subtype" to "ordinary_transfer", "rule_id" to "transfer.open.v1",
            "anchor_fact_id" to "93003", "candidate_fact_ids" to listOf(94004)))
        ids["r3"] = addRelation("r3", mapOf("kind" to "refund_offset", "primary_fact_id" to "93002",
            "status" to "pending_review", "rule_id" to "refund.open.v1", "anchor_fact_id" to "93002",
            "candidate_fact_ids" to listOf(94001)))
        ids["r4"] = addRelation("r4", mapOf("kind" to "refund_offset", "primary_fact_id" to "93007",
            "secondary_fact_id" to "93002", "status" to "pending_review", "rule_id" to "refund.reject.v1",
            "applied_amount" to "5.00"))
        ids["r6"] = addRelation("r6", mapOf("kind" to "refund_offset", "primary_fact_id" to "93007",
            "secondary_fact_id" to "93002", "status" to "pending_review", "subtype" to "ordinary",
            "rule_id" to "refund.supersede.v1"))
        ids["r8"] = addRelation("r8", mapOf("kind" to "transfer_pair", "primary_fact_id" to "93007",
            "status" to "pending_review", "subtype" to "ordinary_transfer", "rule_id" to "transfer.open2.v1",
            "anchor_fact_id" to "93007", "candidate_fact_ids" to listOf(94003)))
        ids["r9"] = addRelation("r9", mapOf("kind" to "refund_offset", "primary_fact_id" to "93002",
            "status" to "pending_review", "subtype" to "alternate", "rule_id" to "refund.open2.v1",
            "anchor_fact_id" to "93002", "candidate_fact_ids" to listOf(94004, 94001)))
        ids["r10"] = addRelation("r10", mapOf("kind" to "refund_offset", "primary_fact_id" to "93005",
            "secondary_fact_id" to "93002", "status" to "pending_review", "subtype" to "delete",
            "rule_id" to "refund.delete.v1"))

        val cases = linkedMapOf<String, Any?>()
        cases["pending_before"] = capture { listPending() }
        cases["accept"] = listOf(
            capture { accept(ids.getValue("r1"), actor = "user", reason = "确认") },
            capture { accept(ids.getValue("r2"), actor = "user", otherFactId = 94004) })
        cases["accept_errors"] = listOf(
            capture { accept(ids.getValue("r1"), actor = "user") },
            capture { accept(999999, actor = "user") },
            capture { accept(ids.getValue("r8"), actor = "user") },
            capture { accept(ids.getValue("r8"), actor = "user", otherFactId = 94006) },
            capture { accept(ids.getValue("r9"), actor = "user", otherFactId = 94004) })
        cases["reject"] = capture { reject(ids.getValue("r4"), actor = "user", reason = "不要") }
        cases["reject_error"] = capture { reject(ids.getValue("r4"), actor = "user") }
        @Suppress("UNCHECKED_CAST")
        val superseded = capture {
            supersede(ids.getValue("r6"), linkedMapOf("kind" to "refund_offset", "subtype" to "p2p_return",
                "primary_fact_id" to "93005", "secondary_fact_id" to "93002", "status" to "pending_review",
                "rule_id" to "refund.replacement.v1"))
        } as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val supersedeDetails = superseded.getValue("details") as MutableMap<String, Any?>
        val newId = (supersedeDetails["new_id"] as Number).toLong()
        supersedeDetails["old_id"] = labels[ids.getValue("r6")]
        supersedeDetails["new_id"] = "replacement"
        labels[newId] = "replacement"
        cases["supersede"] = superseded
        cases["delete"] = capture { logicalDeleteCash(93005, actor = "user", reason = "误记") }
        cases["delete_error"] = capture { logicalDeleteCash(93005, actor = "user", reason = "again") }
        val persisted = listOf(
            capture { persistRejectedProposal(RelationProposal(kind = "refund_offset",
                primaryFactId = "94008", secondaryFactId = "94002", status = "accepted",
                ruleId = "refund.reject.v1",
                evidence = RelationEvidence(extras = linkedMapOf("refund_amount" to "5.00")))) },
            capture { persistRejectedProposal(RelationProposal(kind = "refund_offset",
                primaryFactId = "94007", secondaryFactId = "94002", subtype = "p2p_return", status = "accepted",
                ruleId = "refund.new.v1",
                evidence = RelationEvidence(extras = linkedMapOf("refund_amount" to "30.00")))) })
        for ((index, item) in persisted.withIndex()) {
            @Suppress("UNCHECKED_CAST")
            val map = item as Map<String, Any?>
            val identifier = (map["id"] as? Number)?.toLong()
            if (identifier != null && identifier !in labels) {
                labels[identifier] = "persisted_${index + 1}"
                (map as MutableMap)["id"] = labels.getValue(identifier)
            }
        }
        cases["persist_rejected"] = persisted
        cases["pending_after"] = capture { listPending() }
        cases["project"] = capture { project() }

        val finalRows = database.read { connection ->
            connection.jooq(database.sqlite).selectFrom(TRANSACTION_RELATIONS)
                .where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(workspace))
                .orderBy(TRANSACTION_RELATIONS.ID).fetch().map { row ->
                    linkedMapOf<String, Any?>("id" to row.id, "kind" to row.kind, "subtype" to row.subtype,
                        "primary_component_id" to row.primaryComponentId,
                        "secondary_component_id" to row.secondaryComponentId, "state" to row.state,
                        "active" to row.active, "row_revision" to row.rowRevision,
                        "decision_source" to row.decisionSource,
                        "applied_amount" to row.appliedAmount?.let { java.math.BigDecimal(fixedCashDecimal(it)) },
                        "rule_id" to row.ruleId, "created_by" to row.createdBy, "decided_by" to row.decidedBy,
                        "decision_reason" to row.decisionReason)
                }
        }
        @Suppress("UNCHECKED_CAST")
        cases["final_rows"] = (normDecision(finalRows) as List<Map<String, Any?>>)
            .sortedBy { it["id"].toString() }
        val finalCandidates = database.read { connection ->
            connection.jooq(database.sqlite).select(CASH_RELATION_CANDIDATES.RELATION_ID,
                CASH_RELATION_CANDIDATES.CANDIDATE_COMPONENT_ID).from(CASH_RELATION_CANDIDATES)
                .where(CASH_RELATION_CANDIDATES.WORKSPACE_ID.eq(workspace)).fetch()
                .map { listOf<Any?>(labels[it.value1()] ?: it.value1(), it.value2()) }
                .sortedWith(compareBy({ it[0].toString() }, { it[1].toString().toLong() }))
        }
        cases["final_candidates"] = finalCandidates
        assertEquals(golden(database).getValue("decisions"), sourceJson(cases))
    }

    private fun relationProposal(kind: String = "refund_offset", primary: String, secondary: String? = null,
        subtype: String = "", status: String = "accepted", ruleId: String = "rule.refund.v1",
        anchor: String = primary, amount: String = "30.00", candidates: List<String> = emptyList(),
        refundExtras: Boolean = true) =
        RelationProposal(kind = kind, primaryFactId = primary, secondaryFactId = secondary, subtype = subtype,
            status = status, ruleId = ruleId, anchorFactId = anchor,
            evidence = RelationEvidence(extras = if (kind == "refund_offset" && refundExtras)
                linkedMapOf("refund_amount" to amount) else emptyMap(), candidateFactIds = candidates))
    private fun proposalBody(proposal: RelationProposal): Map<String, Any?> = linkedMapOf(
        "key" to CashRelationSerialization.proposalKey(proposal),
        "body" to linkedMapOf<String, Any?>("kind" to proposal.kind,
            "primary_fact_id" to proposal.primaryFactId, "secondary_fact_id" to proposal.secondaryFactId,
            "primary_fact_type" to proposal.primaryFactType,
            "secondary_fact_type" to proposal.secondaryFactType, "subtype" to proposal.subtype,
            "status" to proposal.status, "rule_id" to proposal.ruleId,
            "confidence" to proposal.confidence, "evidence" to evidenceJson(proposal.evidence),
            "created_by" to proposal.createdBy, "anchor_fact_id" to proposal.effectiveAnchorFactId,
            "open_leg" to proposal.isOpenLeg))

    @Test fun relationPersistenceMatchesPython() {
        val database = database()
        RelationContractFixture.seed(database, jdbcUrl)
        fun sqliteContext(connection: java.sql.Connection) = CashSqlContext(connection, workspace, database.sqlite)
        fun clearRelations() = database.write { connection ->
            val sql = connection.jooq(database.sqlite)
            sql.deleteFrom(CASH_RELATION_CANDIDATES).where(CASH_RELATION_CANDIDATES.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(workspace)).execute()
        }
        val remaining = mapOf("94001" to java.math.BigDecimal("100.00"),
            "94005" to java.math.BigDecimal("5.00"), "94008" to java.math.BigDecimal("5.00"))
        fun call(proposal: RelationProposal, automatic: Boolean = false,
            label: String? = null): Map<String, Any?>? {
            val result = database.write { connection ->
                val service = CashRelationService(sqliteContext(connection))
                if (automatic) service.persistAutomaticProposal(proposal, remaining, service.activeCashFacts())
                else service.persistProposal(proposal, remaining)
            }
            if (result != null && label != null) {
                val identifier = (result["id"] as Number).toLong()
                if (identifier !in labels) labels[identifier] = label
            }
            @Suppress("UNCHECKED_CAST")
            return normDecision(result) as? Map<String, Any?>
        }

        clearRelations()
        val cases = linkedMapOf<String, Any?>()
        val invalid = relationProposal(primary = "94001", secondary = "94002", amount = "130.00")
        cases["automatic_invalid"] = call(invalid, automatic = true)
        val accepted = relationProposal(primary = "94001", secondary = "94002", amount = "10.00")
        cases["new_accepted"] = call(accepted, automatic = true, label = "new_accepted")
        cases["repeat_accepted"] = call(accepted)
        val openProposal = relationProposal(kind = "transfer_pair", primary = "94003", subtype = "ordinary_transfer",
            status = "pending_review", ruleId = "open.rule", anchor = "94003", candidates = listOf("94004"))
        cases["new_open"] = call(openProposal, label = "new_open")
        val openUpdated = openProposal.copy(evidence = RelationEvidence(candidateFactIds = listOf("94001", "94004")))
        cases["update_candidates"] = call(openUpdated)
        val bindProposal = relationProposal(kind = "transfer_pair", primary = "94003", secondary = "94004",
            subtype = "ordinary_transfer", ruleId = "bind.rule", anchor = "94003")
        cases["bind_open"] = call(bindProposal)
        val pendingProposal = relationProposal(primary = "94005", secondary = "94002", subtype = "pending",
            status = "pending_review", amount = "5.00")
        cases["new_pending"] = call(pendingProposal, label = "new_pending")
        cases["auto_upgrade"] = call(pendingProposal.copy(status = "accepted"))
        val conflictProposal = relationProposal(primary = "94003", secondary = "94002", subtype = "conflict",
            amount = "10.00")
        cases["kind_conflict"] = call(conflictProposal, label = "new_conflict")
        database.write { connection ->
            val repository = CashRelationRepository(sqliteContext(connection))
            val identifier = repository.add(mapOf("kind" to "refund_offset", "primary_fact_id" to "94005",
                "secondary_fact_id" to "94002", "primary_component_id" to 94005,
                "secondary_component_id" to 94002, "anchor_fact_id" to "94005", "anchor_component_id" to 94005,
                "subtype" to "human", "status" to "accepted", "rule_id" to "human.rule",
                "created_by" to "user", "decided_by" to "user", "applied_amount" to "5.00"))
            labels[identifier] = "seed_human"
        }
        cases["human_preserved"] = call(relationProposal(primary = "94005", secondary = "94002",
            subtype = "human", amount = "5.00"))
        cases["over_remaining"] = call(relationProposal(primary = "94008", secondary = "94002",
            subtype = "over", amount = "10.00"), label = "new_over")
        database.write { connection ->
            val repository = CashRelationRepository(sqliteContext(connection))
            val identifier = repository.add(mapOf("kind" to "refund_offset", "primary_fact_id" to "94002",
                "status" to "rejected", "primary_component_id" to 94002, "anchor_component_id" to 94002,
                "subtype" to "occ", "rule_id" to "occ.rule", "anchor_fact_id" to "94002",
                "candidate_fact_ids" to listOf(94001)))
            labels[identifier] = "seed_occ"
        }
        cases["rejected_occupancy"] = call(relationProposal(primary = "94002", subtype = "occ",
            status = "pending_review", ruleId = "occ.rule2", anchor = "94002", candidates = listOf("94004"),
            refundExtras = false))
        database.write { connection ->
            val repository = CashRelationRepository(sqliteContext(connection))
            val identifier = repository.add(mapOf("kind" to "refund_offset", "primary_fact_id" to "94002",
                "status" to "pending_review", "primary_component_id" to 94002, "anchor_component_id" to 94002,
                "subtype" to "humanopen", "rule_id" to "human.open", "anchor_fact_id" to "94002",
                "created_by" to "user", "decided_by" to "user", "candidate_fact_ids" to listOf(94001)))
            labels[identifier] = "seed_humanopen"
        }
        cases["human_open_preserved"] = call(relationProposal(primary = "94002", subtype = "humanopen",
            status = "pending_review", ruleId = "human.open2", anchor = "94002", candidates = listOf("94004"),
            refundExtras = false))
        cases["proposals"] = linkedMapOf("accepted" to proposalBody(accepted), "open" to proposalBody(openUpdated),
            "bind" to proposalBody(bindProposal), "pending" to proposalBody(pendingProposal),
            "conflict" to proposalBody(conflictProposal),
            "human" to proposalBody(relationProposal(primary = "94005", secondary = "94002", subtype = "human",
                amount = "5.00")),
            "over" to proposalBody(relationProposal(primary = "94008", secondary = "94002", subtype = "over",
                amount = "10.00")),
            "occupancy" to proposalBody(relationProposal(primary = "94002", subtype = "occ",
                status = "pending_review", ruleId = "occ.rule2", anchor = "94002", candidates = listOf("94004"),
                refundExtras = false)),
            "human_open" to proposalBody(relationProposal(primary = "94002", subtype = "humanopen",
                status = "pending_review", ruleId = "human.open2", anchor = "94002", candidates = listOf("94004"),
                refundExtras = false)))
        val finalRows = database.read { connection ->
            connection.jooq(database.sqlite).selectFrom(TRANSACTION_RELATIONS)
                .where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(workspace))
                .orderBy(TRANSACTION_RELATIONS.ID).fetch().map { row ->
                    linkedMapOf<String, Any?>("id" to row.id, "kind" to row.kind, "subtype" to row.subtype,
                        "primary_component_id" to row.primaryComponentId,
                        "secondary_component_id" to row.secondaryComponentId, "state" to row.state,
                        "active" to row.active, "row_revision" to row.rowRevision,
                        "applied_amount" to row.appliedAmount?.let { java.math.BigDecimal(fixedCashDecimal(it)) },
                        "rule_id" to row.ruleId, "created_by" to row.createdBy, "decided_by" to row.decidedBy,
                        "decision_reason" to row.decisionReason)
                }
        }
        @Suppress("UNCHECKED_CAST")
        cases["final_rows"] = (normDecision(finalRows) as List<Map<String, Any?>>).sortedBy { it["id"].toString() }
        cases["final_candidates"] = database.read { connection ->
            connection.jooq(database.sqlite).select(CASH_RELATION_CANDIDATES.RELATION_ID,
                CASH_RELATION_CANDIDATES.CANDIDATE_COMPONENT_ID).from(CASH_RELATION_CANDIDATES)
                .where(CASH_RELATION_CANDIDATES.WORKSPACE_ID.eq(workspace)).fetch()
                .map { listOf<Any?>(labels[it.value1()] ?: it.value1(), it.value2()) }
                .sortedWith(compareBy({ it[0].toString() }, { it[1].toString().toLong() }))
        }
        assertEquals(golden(database).getValue("persistence"), sourceJson(cases))
    }

    @Test fun relationReconciliationMatchesPython() {
        val database = database()
        RelationContractFixture.seed(database, jdbcUrl)
        fun sqliteContext(connection: java.sql.Connection) = CashSqlContext(connection, workspace, database.sqlite)
        fun run(seed: List<Pair<String, Map<String, Any?>>>, protected: Set<String> = emptySet(),
            blocked: Set<Pair<String, String>> = emptySet()): Pair<List<Long>, List<Map<String, Any?>>> {
            val localLabels = linkedMapOf<Long, String>()
            database.write { connection ->
                val sql = connection.jooq(database.sqlite)
                sql.deleteFrom(CASH_RELATION_CANDIDATES).where(CASH_RELATION_CANDIDATES.WORKSPACE_ID.eq(workspace)).execute()
                sql.deleteFrom(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(workspace)).execute()
                val repository = CashRelationRepository(sqliteContext(connection))
                for ((label, payload) in seed) localLabels[repository.add(payload)] = label
            }
            labels.putAll(localLabels)
            val affected = database.write { connection ->
                val service = CashRelationService(sqliteContext(connection))
                service.reconcileSystemPaymentMirrors(service.activeCashFacts(), protected, blocked).sorted()
            }
            val rows = database.read { connection ->
                connection.jooq(database.sqlite).selectFrom(TRANSACTION_RELATIONS)
                    .where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(workspace))
                    .orderBy(TRANSACTION_RELATIONS.ID).fetch().map { row ->
                        linkedMapOf<String, Any?>("id" to row.id, "kind" to row.kind, "subtype" to row.subtype,
                            "primary_component_id" to row.primaryComponentId,
                            "secondary_component_id" to row.secondaryComponentId, "state" to row.state,
                            "active" to row.active, "row_revision" to row.rowRevision,
                            "decision_source" to row.decisionSource, "rule_id" to row.ruleId,
                            "created_by" to row.createdBy, "decided_by" to row.decidedBy,
                            "decision_reason" to row.decisionReason)
                    }
            }
            @Suppress("UNCHECKED_CAST")
            val normalized = (normDecision(rows) as List<Map<String, Any?>>)
                .sortedBy { it["id"].toString() }
            return affected to normalized
        }
        fun mirror(label: String, primary: Int, secondary: Int, createdBy: String = "system",
            status: String = "accepted", decidedBy: String = "") = label to mapOf<String, Any?>(
            "kind" to "payment_mirror", "primary_fact_id" to primary, "secondary_fact_id" to secondary,
            "primary_component_id" to primary, "secondary_component_id" to secondary,
            "anchor_fact_id" to primary, "anchor_component_id" to primary, "status" to status,
            "rule_id" to "mirror.rule", "applied_amount" to "0", "created_by" to createdBy,
            "decided_by" to decidedBy)
        val cases = linkedMapOf<String, Any?>()
        val obsolete = run(listOf(mirror("auto", 94001, 94008)))
        cases["obsolete_automatic"] = listOf(obsolete.first, obsolete.second)
        val protected = run(listOf(mirror("auto", 94001, 94008),
            mirror("human", 94005, 94008, createdBy = "user", decidedBy = "user")),
            protected = setOf("94005", "94008"))
        cases["human_protected"] = listOf(protected.first, protected.second)
        val humanOnly = run(listOf(mirror("human", 94001, 94008, createdBy = "user", decidedBy = "user")))
        cases["human_only"] = listOf(humanOnly.first, humanOnly.second)
        val blocked = run(listOf(mirror("human_rejected", 94001, 94008, createdBy = "user",
            status = "rejected", decidedBy = "user")), blocked = setOf("94001" to "94008"))
        cases["blocked_pair"] = listOf(blocked.first, blocked.second)
        assertEquals(golden(database).getValue("reconciliation"), sourceJson(cases))
    }
}
