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
}
