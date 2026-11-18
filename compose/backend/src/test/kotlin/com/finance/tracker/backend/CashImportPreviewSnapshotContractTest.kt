package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

class CashImportPreviewSnapshotContractTest {
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_IMPORT_PREPARATION_CONTRACT_DIR")))
    private fun database() = Json.parseToJsonElement(root().resolve("database.json").readText()).jsonObject.let {
        Database(it.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    private fun text(row: JsonObject, key: String) = row[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
    private fun instant(value: Instant): String {
        val time = value.atOffset(ZoneOffset.UTC)
        val base = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").format(time)
        return base + (if (time.nano == 0) "" else "." + (time.nano / 1000).toString().padStart(6, '0')) + "+00:00"
    }
    private fun wire(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Instant -> JsonPrimitive(instant(value))
        is kotlin.time.Instant -> JsonPrimitive(com.finance.tracker.business.domain.formatPythonCashTimestamp(value))
        is com.finance.tracker.business.domain.CashImportPreviewInteger -> Json.parseToJsonElement(value.toString())
        is CashAmount -> JsonPrimitive(fixedCashDecimal(CashStoredDecimal(value.toString())))
        is CashStoredDecimal -> JsonPrimitive(fixedCashDecimal(value))
        is CashDecimalLiteral -> JsonPrimitive(value.text)
        is BigDecimal -> JsonPrimitive(value.toPlainString())
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to wire(it.value) })
        is Iterable<*> -> JsonArray(value.map(::wire))
        else -> sourceJson(value)
    }

    private fun normalizeAmounts(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, item) ->
            if ((key == "amount" || key.endsWith("_amount") || key.startsWith("amount")) && item is JsonPrimitive && item.isString)
                JsonPrimitive(item.content.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: item.content)
            else normalizeAmounts(item) })
        is JsonArray -> JsonArray(value.map(::normalizeAmounts))
        else -> value
    }

    private fun summary(snapshot: com.finance.tracker.business.domain.CashImportPreviewSnapshot<*, *>): JsonObject = wire(linkedMapOf(
        "values" to snapshot.values,
        "facts" to snapshot.facts.mapValues { (_, f) -> mapOf(
            "id" to f.id, "account_id" to f.accountId, "occurred_at" to f.occurredAt, "amount" to f.amount,
            "currency" to f.currency, "counterparty" to f.counterparty, "category_id" to f.categoryId, "note" to f.note,
            "source_type" to f.sourceType, "record_id" to f.recordId, "funding_relation_id" to f.fundingRelationId,
            "cash_granularity" to f.cashGranularity, "state" to f.state, "created_at" to f.createdAt,
            "needs_attention" to f.needsAttention, "record_type" to f.recordType, "record_subtype" to f.recordSubtype,
            "stable_source_key" to f.stableSourceKey, "record_origin" to f.recordOrigin,
        ) },
        "components" to snapshot.components.mapValues { (_, p) -> mapOf(
            "component_id" to p.componentId, "transaction_id" to p.transactionId, "amount" to p.amount,
            "currency" to p.currency, "account_id" to p.accountId, "record_type" to p.recordType,
            "record_subtype" to p.recordSubtype, "state" to p.state, "active" to p.active,
        ) },
        "edges" to snapshot.edges.mapKeys { it.key.toString() }.mapValues { (_, e) -> mapOf(
            "id" to e.id, "kind" to e.kind, "primary_transaction_id" to e.primaryTransactionId,
            "secondary_transaction_id" to e.secondaryTransactionId, "primary_component_id" to e.primaryComponentId,
            "secondary_component_id" to e.secondaryComponentId, "primary_role" to e.primaryRole,
            "secondary_role" to e.secondaryRole, "applied_amount" to e.appliedAmount,
            "state" to e.state, "active" to e.active,
        ) },
        "funding" to snapshot.funding.mapKeys { it.key.toString() }.mapValues { (_, f) ->
            listOf(f.id, f.componentId, f.investmentEventId, f.direction)
        },
        "parent_ops" to snapshot.parentOps.mapValues { it.value["operation_ref"] },
        "entry_public" to snapshot.entryPublic,
        "removed_relation_ids" to snapshot.removedRelations.sorted(),
        "removed_funding_ids" to snapshot.removedFunding.sorted(),
        "operations" to snapshot.operations,
        "rows" to snapshot.rows,
        "projections" to snapshot.build().projections.map { item -> mapOf(
            "projection_id" to item.projectionId, "net_amount" to item.netAmount,
            "economic_type" to item.economicType?.value, "member_ids" to item.memberIds,
            "relation_ids" to item.relations.map { it.id }, "compositions" to item.compositions,
            "funding_relation_id" to item.fundingRelationId, "state" to item.state,
            "attention_count" to item.attentionCount,
        ) },
    )).jsonObject

    private fun cases() = Json.parseToJsonElement(root().resolve("snapshot-cases.json").readText()).jsonArray.map { it.jsonObject }

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun localSnapshotFactoryMatchesAllOriginalNativeCaseStatesWithoutWrites() {
        val db = database()
        val before = detailDatabaseState(db)
        for (case in cases()) snapshot(db, case) { context, prepared, native ->
            val local = localSnapshot(context, prepared, case)
            assertEquals(summary(native), summary(local), case["name"].toString())
            assertEquals(native.facts.mapValues { it.value.amount?.representation },
                local.facts.mapValues { it.value.amount?.representation }, case["name"].toString())
            val nativeFacts = native.facts.values.map { listOf(it.id, it.amount, it.createdAt, it.occurredAt) }
            val localFacts = local.facts.values.map { listOf(it.id, it.amount, it.createdAt, it.occurredAt) }
            assertEquals(cashImportDigest(native.values, native.operations, native.rows, nativeFacts),
                com.finance.tracker.business.domain.localCashImportDigest(listOf(local.values, local.operations, local.rows, localFacts)),
                case["name"].toString())
        }
        assertEquals(before, detailDatabaseState(db))
    }

    private fun localValue(value: Any?): Any? = when (value) {
        is Instant -> kotlin.time.Instant.fromEpochSeconds(value.epochSecond, value.nano)
        is BigInteger -> Json.parseToJsonElement(value.toString())
        is Byte, is Short, is Int -> com.finance.tracker.business.domain.SourceJsonInteger((value as Number).toInt())
        is JsonElement -> localValue(cashSourceValue(value))
        is Map<*, *> -> value.entries.associate { it.key.toString() to localValue(it.value) }
        is List<*> -> value.map(::localValue)
        else -> value
    }

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun localPreparationReadsTheSameCompleteDependenciesAndBuildsTheSamePreviewWithoutWrites() {
        val db = database()
        val before = detailDatabaseState(db)
        for (case in cases()) snapshot(db, case, true) { context, prepared, native ->
            val source = com.finance.tracker.business.contracts.LedgerSnapshotDto(context.workspace, "0",
                com.finance.tracker.business.domain.CASH_RULES_VERSION,
                LedgerSnapshotRepository(context.connection, context.workspace, context.sqlite).facts())
            @Suppress("UNCHECKED_CAST") val operations = localValue(prepared.operations) as List<Map<String, Any?>>
            @Suppress("UNCHECKED_CAST") val choices = localValue(cashSourceValue(case.getValue("entry_choices"))) as List<Map<String, Any?>>
            @Suppress("UNCHECKED_CAST") val previous = case["previous_plan"]?.takeUnless { it == JsonNull }
                ?.let { localValue(cashSourceValue(it)) as Map<String, Any?> }
            val extra = case.getValue("additional_seed_ids").jsonArray.map { it.jsonPrimitive.long }.toSet()
            val local = com.finance.tracker.business.domain.LocalCashImportPreviewPreparation(source, context::requireActive)
                .prepareOperations(operations, entryChoices = choices, previousPlan = previous, additionalSeedIds = extra)
            assertEquals(wire(prepared.operations), wire(local.operations), case["name"].toString())
            assertEquals(wire(prepared.accounts), wire(local.accounts), case["name"].toString())
            assertEquals(prepared.candidateIds, local.candidateIds, case["name"].toString())
            assertEquals(prepared.retainedParentIds, local.retainedParentIds, case["name"].toString())
            assertEquals(prepared.context.token(), com.finance.tracker.business.domain.cashRelationReadContextToken(local.context))
            @Suppress("UNCHECKED_CAST") val rows = localValue(cashSourceValue(case.getValue("rows"))) as List<Map<String, Any?>>
            val value = com.finance.tracker.business.domain.buildLocalCashImportPreviewSnapshot(source, local.context, local.operations, rows) {
                kotlin.time.Instant.parse("2026-10-01T09:00:00Z")
            }
            assertEquals(summary(native), summary(value), case["name"].toString())
        }
        assertEquals(before, detailDatabaseState(db))
    }

    private fun localSnapshot(context: CashSqlContext, prepared: PreparedCashImportPreview, case: JsonObject):
        com.finance.tracker.business.domain.CashImportPreviewSnapshot<com.finance.tracker.business.domain.CashImportPreviewInteger, kotlin.time.Instant> {
        val source = com.finance.tracker.business.contracts.LedgerSnapshotDto(context.workspace, "0",
            com.finance.tracker.business.domain.CASH_RULES_VERSION,
            LedgerSnapshotRepository(context.connection, context.workspace, context.sqlite).facts())
        val localContext = com.finance.tracker.business.domain.readLocalCashRelationContext(
            source, prepared.context.seedIds, prepared.context.decisionGroupIds)
        @Suppress("UNCHECKED_CAST") val operations = localValue(prepared.operations) as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val rows = localValue(cashSourceValue(case.getValue("rows"))) as List<Map<String, Any?>>
        val local = com.finance.tracker.business.domain.buildLocalCashImportPreviewSnapshot(source, localContext, operations, rows) {
            kotlin.time.Instant.parse("2026-10-01T09:00:00Z")
        }
        return local
    }

    private fun snapshot(db: Database, case: JsonObject): CashImportPreviewSnapshot = snapshot(db, case) { _, _, value -> value }

    private fun sourceOccurrences(case: JsonObject): Map<String, SourceOccurrence> =
        case.getValue("source_rows").jsonObject.mapValues { (_, value) ->
            val row = value.jsonObject
            SourceOccurrence(row.getValue("source_type").jsonPrimitive.content,
                text(row, "source_account_key"), row.getValue("file_digest").jsonPrimitive.content,
                row.getValue("parser_version").jsonPrimitive.content, row.getValue("table_key").jsonPrimitive.content,
                row.getValue("position").jsonPrimitive.int, emptyMap(), providerId = text(row, "provider_id"))
        }

    private fun <T> snapshot(db: Database, case: JsonObject, normalizedOperations: Boolean = false,
        use: (CashSqlContext, PreparedCashImportPreview, CashImportPreviewSnapshot) -> T): T {
        @Suppress("UNCHECKED_CAST") val operations = cashSourceValue(case.getValue("operations")) as List<Map<String, Any?>>
        if (normalizedOperations) for (operation in operations) {
            @Suppress("UNCHECKED_CAST") val values = operation["values"] as MutableMap<String, Any?>
            for (field in financialCashFields) if (field in values) values[field] = CashRecordPolicy.normalize(field, values[field])
        }
        @Suppress("UNCHECKED_CAST") val rows = cashSourceValue(case.getValue("rows")) as List<Map<String, Any?>>
        val sourceRows = sourceOccurrences(case)
        @Suppress("UNCHECKED_CAST") val choices = cashSourceValue(case.getValue("entry_choices")) as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val previous = case["previous_plan"]?.takeUnless { it == JsonNull }
            ?.let { cashSourceValue(it) } as? Map<String, Any?>
        val extra = case.getValue("additional_seed_ids").jsonArray.map { it.jsonPrimitive.long }.toSet()
        val actual = db.read { connection ->
            val context = CashSqlContext(connection, "operation-workspace", db.sqlite)
            val prepared = CashImportPreviewPreparation(context).prepare(operations, sourceRows, choices, previous, extra)
            val value = CashImportPreviewSnapshotBuilder(context, now = { Instant.parse("2026-10-01T09:00:00Z") }).build(prepared, rows)
            use(context, prepared, value)
        }
        assertEquals(case.getValue("operations"), wire(operations))
        return actual
    }

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)

    @Test fun completeGraphRejectsConfirmedOrphanEdges() {
        val db = database()
        val normal = cases().single { it.getValue("name").jsonPrimitive.content == "normal" }
        snapshot(db, normal) { _, _, value ->
            value.edges[BigInteger.valueOf(999)] = CashRelationGraphEdge(999L, "payment_mirror",
                "missing-parent-a", "missing-parent-b", "missing-part-a", "missing-part-b", "expense", "expense")
            assertEquals("relation_endpoint_incomplete", assertFailsWith<CashRelationFailure> { value.build() }.code)
        }
    }

    private fun proposal(row: JsonObject): RelationProposal {
        val evidence = row.getValue("evidence").jsonObject
        fun list(key: String) = evidence.getValue(key).jsonArray.map { it.jsonPrimitive.content }
        @Suppress("UNCHECKED_CAST") val extras = cashSourceValue(evidence.getValue("extras")) as Map<String, Any?>
        val pair = list("source_pair")
        return RelationProposal(row.getValue("kind").jsonPrimitive.content,
            row.getValue("primary_fact_id").jsonPrimitive.content, text(row, "secondary_fact_id"),
            row.getValue("primary_fact_type").jsonPrimitive.content, text(row, "secondary_fact_type"),
            row.getValue("status").jsonPrimitive.content,
            row.getValue("rule_id").jsonPrimitive.content, row.getValue("confidence").jsonPrimitive.content,
            RelationEvidence(evidence.getValue("amount_delta").jsonPrimitive.content,
                text(evidence, "time_delta_seconds")?.toInt(), evidence.getValue("same_currency").jsonPrimitive.boolean,
                evidence.getValue("counterparty_similarity").jsonPrimitive.content, pair[0] to pair[1],
                evidence.getValue("rule_id").jsonPrimitive.content, evidence.getValue("candidate_count").jsonPrimitive.int,
                list("signals"), evidence.getValue("open_leg").jsonPrimitive.boolean,
                evidence.getValue("anchor_role").jsonPrimitive.content, list("candidate_fact_ids"), extras),
            row.getValue("created_by").jsonPrimitive.content, row.getValue("anchor_fact_id").jsonPrimitive.content,
            row.getValue("open_leg").jsonPrimitive.boolean)
    }

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)

    @Test fun relationGroupsAndCandidatesMatchPythonWithoutWrites() {
        val db = database()
        val before = detailDatabaseState(db)
        val snapshots = cases().associateBy { it.getValue("name").jsonPrimitive.content }
        val groups = Json.parseToJsonElement(root().resolve("relation-group-cases.json").readText()).jsonArray
        for (item in groups) {
            val case = item.jsonObject
            snapshot(db, snapshots.getValue(case.getValue("snapshot_case").jsonPrimitive.content), true) { _, _, value ->
                val unchanged = summary(value)
                val result = CashImportRelationGroups.build(value, case.getValue("proposals").jsonArray.map { proposal(it.jsonObject) })
                assertEquals(normalizeAmounts(case.getValue("groups")), normalizeAmounts(wire(result.groups)), case.getValue("name").jsonPrimitive.content)
                assertEquals(normalizeAmounts(case.getValue("failures")), normalizeAmounts(wire(result.failures)), case.getValue("name").jsonPrimitive.content)
                assertEquals(unchanged, summary(value))
                if (case.getValue("name").jsonPrimitive.content in setOf("weak_closed", "open_leg")) {
                    val group = result.groups.single()
                    assertEquals("defer", group["action"])
                    assertEquals(listOf("accept", "defer", "ignore"), group["allowed_actions"])
                    assertEquals(1, (group["candidates"] as List<*>).size)
                }
            }
        }
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun relationGroupDigestsKeepPythonDecimalAndDatetimeStrings() {
        for ((input, expected) in listOf("-5.00" to "-5.00", "0E+2147483648" to "0E+2147483648",
            "-0.0000000" to "-0E-7", "0.000001" to "0.000001", "0.0000001" to "1E-7", "123E2" to "1.23E+4"))
            assertEquals(sourceDigest(expected), cashImportDigest(CashAmount.parse(input)))
        assertEquals(sourceDigest("2026-10-02 10:00:00+00:00"), cashImportDigest(Instant.parse("2026-10-02T10:00:00Z")))
    }

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun localRelationGroupsMatchAllOriginalProposalCasesAndComputedDigests() {
        val db = database()
        val before = detailDatabaseState(db)
        val snapshots = cases().associateBy { it.getValue("name").jsonPrimitive.content }
        val groups = Json.parseToJsonElement(root().resolve("relation-group-cases.json").readText()).jsonArray
        for (item in groups) {
            val case = item.jsonObject
            val source = snapshots.getValue(case.getValue("snapshot_case").jsonPrimitive.content)
            snapshot(db, source, true) { context, prepared, native ->
                val local = localSnapshot(context, prepared, source)
                val proposals = case.getValue("proposals").jsonArray.map { proposal(it.jsonObject) }
                val unchanged = summary(local)
                val expected = CashImportRelationGroups.build(native, proposals)
                val actual = com.finance.tracker.business.domain.buildLocalCashImportRelationGroups(local, proposals)
                assertEquals(wire(expected.groups), wire(actual.groups), case["name"].toString())
                assertEquals(wire(expected.failures), wire(actual.failures), case["name"].toString())
                assertEquals(cashImportDigest(expected.groups, expected.failures),
                    com.finance.tracker.business.domain.localCashImportDigest(listOf(actual.groups, actual.failures)), case["name"].toString())
                assertEquals(unchanged, summary(local))
            }
        }
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun localPlanMoneyEncodingMatchesTheNativeFixedDecimalBoundary() {
        for (text in listOf("-0.00", "1.2300", "1E+2", "-0E2147483648", "-0E2147483649",
            "0E2147483649", "1e2147483649", "not-a-decimal", "１２.００")) {
            val expected = JsonPrimitive(fixedCashDecimal(CashStoredDecimal(text)))
            val literal = com.finance.tracker.business.domain.freezeLocalCashImportPreviewPlan(
                mapOf("amount" to CashDecimalLiteral(text)))
            assertEquals(expected, literal.getValue("amount"), text)
            CashAmount.parseOrNull(text)?.let {
                assertEquals(expected, com.finance.tracker.business.domain.freezeLocalCashImportPreviewPlan(
                    mapOf("amount" to it)).getValue("amount"), text)
            }
        }
    }

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun localPlanCompletionMatchesTheNativeBuilderForOriginalComputedProposalCases() {
        val db = database()
        val before = detailDatabaseState(db)
        val stored = Json.parseToJsonElement(root().resolve("input-session.json").readText()).jsonObject
        val files = ParsedImportCodec().decode(stored.getValue("session_id").jsonPrimitive.content,
            stored.getValue("content").toString().encodeToByteArray()).files
        val snapshots = cases().associateBy { it.getValue("name").jsonPrimitive.content }
        val groups = Json.parseToJsonElement(root().resolve("relation-group-cases.json").readText()).jsonArray
        var compared = 0
        for (item in groups) {
            val case = item.jsonObject
            val input = snapshots.getValue(case.getValue("snapshot_case").jsonPrimitive.content)
            // This fixture has a preparation-only edit reference without a final public-field patch.
            // Its original snapshot/group assertions remain in the existing complete replays above.
            if (input.getValue("entry_choices").jsonArray.isNotEmpty()) continue
            snapshot(db, input, true) { context, prepared, native ->
                val local = localSnapshot(context, prepared, input)
                val proposals = case.getValue("proposals").jsonArray.map { proposal(it.jsonObject) }
                val source = com.finance.tracker.business.contracts.LedgerSnapshotDto(context.workspace, "0",
                    com.finance.tracker.business.domain.CASH_RULES_VERSION,
                    LedgerSnapshotRepository(context.connection, context.workspace, context.sqlite).facts())
                @Suppress("UNCHECKED_CAST") val localOperations = localValue(prepared.operations) as List<Map<String, Any?>>
                @Suppress("UNCHECKED_CAST") val accounts = localValue(prepared.accounts) as Map<String, Map<String, Any?>>
                val dependencies = com.finance.tracker.business.domain.PreparedCashImportPreview(localOperations, accounts,
                    prepared.candidateIds, prepared.retainedParentIds,
                    com.finance.tracker.business.domain.readLocalCashRelationContext(source,
                        prepared.context.seedIds, prepared.context.decisionGroupIds))
                val originalPrepared = prepared
                val builder = object : CashImportBusinessPlanBuilder(context) {
                    override fun snapshot(prepared: PreparedCashImportPreview, rows: List<Map<String, Any?>>): CashImportPreviewSnapshot {
                        assertEquals(originalPrepared.accounts, prepared.accounts)
                        assertEquals(originalPrepared.candidateIds, prepared.candidateIds)
                        assertEquals(originalPrepared.retainedParentIds, prepared.retainedParentIds)
                        assertEquals(originalPrepared.context.token(), prepared.context.token())
                        return native
                    }
                    override fun discover(snapshot: CashImportPreviewSnapshot, seeds: Set<String>,
                        accounts: Map<String, Map<String, Any?>>): List<RelationProposal> = proposals
                }
                val expected = builder.buildOperations(prepared.operations, native.rows, emptyMap(), null,
                    files = files, sourceRows = sourceOccurrences(input))
                val actual = com.finance.tracker.business.domain.completeLocalCashImportPreviewPlan(source, dependencies,
                    local, proposals, files, emptyMap(), null)
                assertEquals(wire(expected), wire(actual), case["name"].toString())
                assertEquals(Json.parseToJsonElement(ParsedImportCodec().bytes(expected).decodeToString()),
                    com.finance.tracker.business.domain.freezeLocalCashImportPreviewPlan(actual), case["name"].toString())
                assertEquals(summary(native), summary(local), case["name"].toString())
                compared++
            }
        }
        assertEquals(15, compared)
        assertEquals(before, detailDatabaseState(db))
    }

    private fun candidateLimitGroups(value: CashImportPreviewSnapshot, allValid: Boolean): List<MutableMap<String, Any?>> {
        val parent = value.parentOps.entries.single { it.value["planned_action"] == "create_posted" }.key
        val root = value.values.getValue(parent)
        value.components.entries.removeIf { it.value.transactionId == parent }
        val parts = (0..1).map { ordinal ->
            linkedMapOf<String, Any?>("component_id" to cashImportVirtual("limit-part", ordinal).toString(),
                "account_id" to "9201", "amount" to CashAmount.parse("-5"), "currency" to "CNY", "ordinal" to ordinal)
        }
        root["components"] = parts
        root["amount"] = CashAmount.parse("-10")
        root["account_id"] = null
        root["cash_granularity"] = "aggregate"
        val fact = value.facts.getValue(parent)
        value.facts[parent] = CashProjectionFact.create(fact.id, null, fact.occurredAt, "-10", "CNY", fact.counterparty,
            fact.categoryId, fact.note, fact.sourceType, fact.recordId, cashGranularity = "aggregate", createdAt = fact.createdAt)
        val proposals = parts.mapIndexed { index, part ->
            val component = part.getValue("component_id") as String
            value.components[component] = CashRelationEndpoint(component, parent, part["amount"], "CNY", "9201", "consumption", "not_applicable")
            val candidates = (0 until 45).map { offset ->
                val identifier = (10000 + index * 100 + offset).toString()
                val componentId = (20000 + index * 100 + offset).toString()
                val amount = if (allValid || offset == 44) "5" else "1"
                val candidate = root.toMutableMap().apply {
                    put("amount", CashAmount.parse(amount)); put("account_id", "9201"); put("cash_granularity", "atomic")
                    put("record_type", "refund"); put("components", listOf(mapOf("component_id" to componentId)))
                }
                value.values[identifier] = candidate
                value.facts[identifier] = CashProjectionFact.create(identifier.toLong(), 9201, fact.occurredAt, amount, "CNY",
                    "候选退款", null, "", "fixture", identifier, createdAt = fact.createdAt)
                value.components[componentId] = CashRelationEndpoint(componentId, identifier, CashAmount.parse(amount),
                    "CNY", "9201", "refund", "not_applicable")
                componentId
            }
            RelationProposal("refund_offset", component, null, status = "pending_review",
                evidence = RelationEvidence(candidateCount = 45, candidateFactIds = candidates,
                    extras = mapOf("refund_amount" to "5")), anchorFactId = component)
        }
        return CashImportRelationGroups.build(value, proposals).groups
    }

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)

    @Test fun relationCandidatesStopAfterTwoThousandCombinationsAndTwentyValidChoices() {
        val db = database()
        val before = detailDatabaseState(db)
        val normal = cases().single { it.getValue("name").jsonPrimitive.content == "normal" }
        snapshot(db, normal) { _, _, value ->
            assertTrue(candidateLimitGroups(value, false).isEmpty())
            val parent = value.parentOps.entries.single { it.value["planned_action"] == "create_posted" }.key
            @Suppress("UNCHECKED_CAST") val parts = value.values.getValue(parent)["components"] as List<Map<String, Any?>>
            val edges = parts.mapIndexed { index, part -> CashRelationGraphEdge((30000 + index).toLong(), "refund_offset",
                parent, (10044 + index * 100).toString(), part["component_id"], (20044 + index * 100).toString(),
                "expense", "refund", appliedAmount = CashDecimalLiteral("5")) }
            assertEquals(3, value.build(setOf(parent, "10044", "10144"), additions = edges).projections.single().memberIds.size)
        }
        snapshot(db, normal) { _, _, value ->
            val group = candidateLimitGroups(value, true).single()
            assertEquals(20, (group["candidates"] as List<*>).size)
            assertEquals(listOf("accept", "defer", "ignore"), group["allowed_actions"])
        }
        assertEquals(before, detailDatabaseState(db))
    }

    private fun mapList(value: JsonElement): List<MutableMap<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        return cashSourceValue(value) as List<MutableMap<String, Any?>>
    }

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)

    @Test fun frozenRelationChoicesAndInvalidationMatchPython() {
        val cases = Json.parseToJsonElement(root().resolve("relation-choice-cases.json").readText()).jsonArray
        for (item in cases) {
            val case = item.jsonObject
            val groups = mapList(case.getValue("groups"))
            val choices = mapList(case.getValue("choices"))
            @Suppress("UNCHECKED_CAST") val previous = cashSourceValue(case.getValue("previous")) as? Map<String, Any?>
            if (case["error"] != null) {
                assertEquals(case.getValue("error").jsonPrimitive.content, assertFailsWith<CashImportPlanError> {
                    CashImportRelationDecisions.applyChoices(groups, choices, previous)
                }.code)
            } else {
                val result = CashImportRelationDecisions.applyChoices(groups, choices, previous)
                assertEquals(normalizeAmounts(case.getValue("expected")), normalizeAmounts(wire(mapOf("groups" to groups, "invalidated" to result.invalidated,
                    "sources" to result.sources, "generations" to result.generations))), case.getValue("name").jsonPrimitive.content)
                if (case.getValue("name").jsonPrimitive.content == "candidate_disappeared") {
                    assertEquals(1, result.invalidated.size)
                    assertEquals("defer", groups.single()["action"])
                    assertFalse(groups.single()["source_suggestion_id"] == groups.single()["suggestion_id"])
                }
                if (case.getValue("name").jsonPrimitive.content == "huge_generation")
                    assertEquals(BigInteger.ONE.shiftLeft(80), result.generations.values.single())
            }
            assertEquals(case.getValue("choices"), wire(choices))
            assertEquals(case.getValue("previous"), wire(previous))
        }
    }

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun computedProposalCandidatesCanBeSelectedWithoutErasingTheirDecimalTypes() {
        val db = database()
        val before = detailDatabaseState(db)
        val inputs = cases().associateBy { it.getValue("name").jsonPrimitive.content }
        val cases = Json.parseToJsonElement(root().resolve("relation-group-cases.json").readText()).jsonArray
        var selected = 0
        var decimal = false
        fun select(groups: List<MutableMap<String, Any?>>, local: List<MutableMap<String, Any?>>) {
            val previous = mapOf("relation_groups" to cashSourceValue(wire(groups)))
            assertEquals(wire(groups), wire(local))
            for ((index, group) in groups.withIndex()) {
                val candidate = (group["candidates"] as List<*>).firstOrNull() as? Map<*, *> ?: continue
                val identifier = group.getValue("suggestion_id")
                val choices = listOf(mapOf("suggestion_id" to identifier, "candidate_id" to candidate["candidate_id"], "action" to "accept"))
                val result = CashImportRelationDecisions.applyChoices(listOf(group), choices, previous)
                @Suppress("UNCHECKED_CAST") val old = localValue(previous) as Map<String, Any?>
                val localResult = com.finance.tracker.business.domain.applyLocalCashImportRelationChoices(listOf(local[index]), choices, old)
                assertEquals(wire(listOf(group, result.invalidated, result.sources, result.generations)),
                    wire(listOf(local[index], localResult.invalidated, localResult.sources, localResult.generations)))
                assertEquals("accept", group["action"])
                assertEquals(candidate["candidate_id"], group["candidate_id"])
                val edges = group["edges"] as List<*>
                decimal = decimal || edges.any { (it as Map<*, *>)["applied_amount"] is CashDecimalLiteral }
                selected++
            }
        }
        for (raw in cases) {
            val case = raw.jsonObject
            val input = inputs.getValue(case.getValue("snapshot_case").jsonPrimitive.content)
            snapshot(db, input, true) { context, prepared, value ->
                val groups = CashImportRelationGroups.build(value, case.getValue("proposals").jsonArray.map { proposal(it.jsonObject) }).groups
                val local = com.finance.tracker.business.domain.buildLocalCashImportRelationGroups(localSnapshot(context, prepared, input),
                    case.getValue("proposals").jsonArray.map { proposal(it.jsonObject) }).groups
                select(groups, local)
            }
        }
        snapshot(db, inputs.getValue("normal"), true) { context, prepared, _ ->
            @Suppress("UNCHECKED_CAST") val operations = CashRecordPolicy.copyValue(prepared.operations) as List<MutableMap<String, Any?>>
            val operation = operations.single { it["planned_action"] == "create_posted" }
            @Suppress("UNCHECKED_CAST") val values = operation["values"] as MutableMap<String, Any?>
            values["amount"] = CashAmount.parse("5.00")
            values["record_type"] = "refund"
            for (part in values["components"] as List<*>) {
                @Suppress("UNCHECKED_CAST") val component = part as MutableMap<String, Any?>
                component["amount"] = CashAmount.parse("5.00")
            }
            val value = CashImportPreviewSnapshotBuilder(context, now = { Instant.parse("2026-10-01T09:00:00Z") })
                .build(prepared.copy(operations = operations), emptyList())
            val parent = value.parentOps.entries.single { it.value["planned_action"] == "create_posted" }.key
            val refund = value.components.values.single { it.transactionId == parent }
            val expense = value.components.getValue("9508")
            val proposal = RelationProposal("refund_offset", expense.componentId as String, refund.componentId as String,
                evidence = RelationEvidence(extras = mapOf("refund_amount" to "5.00")))
            val groups = CashImportRelationGroups.build(value, listOf(proposal)).groups
            assertTrue(groups.isNotEmpty())
            assertTrue((groups.single()["candidates"] as List<*>).isNotEmpty())
            val local = com.finance.tracker.business.domain.buildLocalCashImportRelationGroups(
                localSnapshot(context, prepared.copy(operations = operations), inputs.getValue("normal")), listOf(proposal)).groups
            select(groups, local)
            val amount = ((groups.single()["edges"] as List<*>).single() as Map<*, *>)["applied_amount"]
            assertFailsWith<InvalidCashValue> { CashRecordPolicy.normalize("source_payload", mapOf("amount" to amount)) }
        }
        assertTrue(selected > 0)
        assertTrue(decimal)
        assertEquals(before, detailDatabaseState(db))
    }

    private fun graphState(value: com.finance.tracker.business.domain.CashImportPreviewSnapshot<*, *>) = JsonObject(summary(value).filterKeys {
        it in setOf("facts", "edges", "entry_public", "projections")
    })

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)

    @Test fun applyingRelationGroupsAndRestoringSplitValuesMatchesPythonWithoutWrites() {
        val db = database()
        val before = detailDatabaseState(db)
        val snapshots = cases().associateBy { it.getValue("name").jsonPrimitive.content }
        val cases = Json.parseToJsonElement(root().resolve("relation-application-cases.json").readText()).jsonArray
        for (item in cases) {
            val case = item.jsonObject
            val input = snapshots.getValue(case.getValue("snapshot_case").jsonPrimitive.content)
            snapshot(db, input, true) { context, prepared, value ->
                val groups = mapList(case.getValue("groups")).toMutableList()
                val local = localSnapshot(context, prepared, input)
                @Suppress("UNCHECKED_CAST") val localGroups = localValue(mapList(case.getValue("groups"))) as MutableList<MutableMap<String, Any?>>
                @Suppress("UNCHECKED_CAST") val overrides = cashSourceValue(case.getValue("entry_overrides")) as Map<String, Map<String, Any?>>
                value.entryPublic.putAll(overrides)
                @Suppress("UNCHECKED_CAST") val localOverrides = localValue(overrides) as Map<String, Map<String, Any?>>
                local.entryPublic.putAll(localOverrides)
                for ((parent, raw) in case.getValue("amount_overrides").jsonObject) {
                    val amount = raw.jsonPrimitive.content
                    val fact = value.facts.getValue(parent)
                    value.facts[parent] = CashProjectionFact.create(fact.id, fact.accountId, fact.occurredAt, amount,
                        fact.currency, fact.counterparty, fact.categoryId, fact.note, fact.sourceType, fact.recordId,
                        fact.fundingRelationId, fact.cashGranularity, fact.state, fact.createdAt, fact.needsAttention,
                        fact.recordType, fact.recordSubtype, fact.stableSourceKey, fact.recordOrigin)
                    val localFact = local.facts.getValue(parent)
                    val localValues = com.finance.tracker.business.domain.CashProjectionValues<com.finance.tracker.business.domain.CashImportPreviewInteger, kotlin.time.Instant>(
                        { it > com.finance.tracker.business.domain.CashImportPreviewInteger.parse("0") }, { true }, { com.finance.tracker.business.domain.pythonCashUpper(it) })
                    local.facts[parent] = com.finance.tracker.business.domain.CashProjectionFact.create(localValues,
                        localFact.id, localFact.accountId, localFact.occurredAt, amount, localFact.currency, localFact.counterparty,
                        localFact.categoryId, localFact.note, localFact.sourceType, localFact.recordId, localFact.fundingRelationId,
                        localFact.cashGranularity, localFact.state, localFact.createdAt, localFact.needsAttention,
                        localFact.recordType, localFact.recordSubtype, localFact.stableSourceKey, localFact.recordOrigin)
                    local.values.getValue(parent)["amount"] = CashAmount.parse(amount)
                    value.values.getValue(parent)["amount"] = CashAmount.parse(amount)
                    @Suppress("UNCHECKED_CAST") val parts = value.values.getValue(parent)["components"] as List<MutableMap<String, Any?>>
                    for (part in parts) {
                        part["amount"] = CashAmount.parse(amount)
                        val id = part["component_id"] as String
                        value.components[id] = value.components.getValue(id).copy(amount = CashAmount.parse(amount))
                    }
                    @Suppress("UNCHECKED_CAST") val localParts = local.values.getValue(parent)["components"] as List<MutableMap<String, Any?>>
                    for (part in localParts) {
                        part["amount"] = CashAmount.parse(amount)
                        val id = part["component_id"] as String
                        local.components[id] = local.components.getValue(id).copy(amount = CashAmount.parse(amount))
                    }
                }
                if (case["error"] != null) {
                    val failure = assertFailsWith<CashPolicyError> { CashImportRelationDecisions.applyGroups(value, groups) }
                    assertEquals(case.getValue("error").jsonPrimitive.content, failure.code)
                    assertEquals(case.getValue("error_fields"), wire(failure.fields))
                    assertEquals(case.getValue("state"), graphState(value))
                    val localFailure = assertFailsWith<CashPolicyError> { com.finance.tracker.business.domain.applyLocalCashImportRelationGroups(local, localGroups) }
                    assertEquals(failure.code, localFailure.code)
                    assertEquals(failure.fields, localFailure.fields)
                    assertEquals(graphState(value), graphState(local))
                } else {
                    if (case.getValue("restore_split").jsonPrimitive.boolean) {
                        CashImportRelationDecisions.restoreSplitValues(context, value, groups)
                        val source = com.finance.tracker.business.contracts.LedgerSnapshotDto(context.workspace, "0",
                            com.finance.tracker.business.domain.CASH_RULES_VERSION,
                            LedgerSnapshotRepository(context.connection, context.workspace, context.sqlite).facts())
                        com.finance.tracker.business.domain.restoreLocalCashImportSplitValues(source, local, localGroups)
                    }
                    val failures = CashImportRelationDecisions.applyGroups(value, groups)
                    val localFailures = com.finance.tracker.business.domain.applyLocalCashImportRelationGroups(local, localGroups)
                    assertEquals(wire(groups), wire(localGroups))
                    assertEquals(wire(failures), wire(localFailures))
                    assertEquals(graphState(value), graphState(local))
                    assertEquals(normalizeAmounts(case.getValue("expected")), normalizeAmounts(wire(mapOf("groups" to groups, "failures" to failures,
                        "state" to graphState(value)))), case.getValue("name").jsonPrimitive.content)
                    if (case.getValue("name").jsonPrimitive.content == "manual_field_conflict") {
                        assertEquals("defer", groups.single()["action"])
                        assertEquals("common_field_conflict", groups.single()["code"])
                    }
                    if (case.getValue("name").jsonPrimitive.content == "joint_decision_conflict")
                        assertTrue(groups.all { it["action"] == "defer" })
                    if (case.getValue("name").jsonPrimitive.content == "remove_restores_standalone") {
                        assertEquals(false, groups.single()["inherit_common_fields"])
                        assertEquals("独立备注 7501", value.facts.getValue("7501").note)
                    }
                }
            }
        }
        assertEquals(before, detailDatabaseState(db))
    }

    private fun matchingFact(fact: RelationFact): Map<String, Any?> = linkedMapOf(
        "id" to fact.id, "amount" to fact.amount, "currency" to fact.currency, "account_id" to fact.accountId,
        "account_name" to fact.accountName, "account_type" to fact.accountType, "occurred_at" to fact.occurredAt,
        "counterparty" to fact.counterparty, "counterparty_account" to fact.counterpartyAccount,
        "counterparty_account_attrs" to fact.counterpartyAccountAttrs, "payment_method" to fact.paymentMethod,
        "note" to fact.note, "record_type" to fact.recordType, "record_subtype" to fact.recordSubtype,
        "bill_source" to fact.billSource, "source" to fact.source, "fact_type" to fact.factType,
        "deleted" to fact.deleted, "raw_record_id" to fact.rawRecordId, "source_identity" to fact.sourceIdentity,
        "record_id" to fact.recordId, "raw_payload" to fact.rawPayload, "relation_metadata" to fact.relationMetadata,
        "parent_id" to fact.parentId, "component_ordinal" to fact.componentOrdinal, "cash_granularity" to fact.cashGranularity,
        "stable_source_key" to fact.stableSourceKey, "record_origin" to fact.recordOrigin,
    )

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)

    @Test fun finalPreviewPresentationMatchesPythonWithoutWrites() {
        val db = database()
        val before = detailDatabaseState(db)
        val inputs = cases().associateBy { it.getValue("name").jsonPrimitive.content }
        val presentations = Json.parseToJsonElement(root().resolve("presentation-cases.json").readText()).jsonArray
        for (raw in presentations) {
            val case = raw.jsonObject
            snapshot(db, inputs.getValue(case.getValue("snapshot_case").jsonPrimitive.content), true) { _, prepared, value ->
                @Suppress("UNCHECKED_CAST") val groups = (cashSourceValue(case.getValue("groups")) as List<MutableMap<String, Any?>>).toMutableList()
                CashImportRelationDecisions.applyGroups(value, groups)
                @Suppress("UNCHECKED_CAST") val failures = cashSourceValue(case.getValue("failures")) as List<Map<String, Any?>>
                for (failure in failures) for (parent in failure["parent_ids"] as List<*>) {
                    value.facts[parent]?.let { value.facts[parent.toString()] = it.withAttention(true) }
                }
                val snapshotBefore = summary(value)
                val actual = CashImportPreviewPresentation.present(emptyList(), value, value.build(), groups, failures,
                    prepared.accounts, case.getValue("include_unchanged").jsonPrimitive.boolean,
                    case.getValue("entry_parent_ids").jsonArray.map { it.jsonPrimitive.content }.toSet())
                assertEquals(normalizeAmounts(case.getValue("expected")), normalizeAmounts(wire(actual)), case.getValue("name").jsonPrimitive.content)
                assertEquals(snapshotBefore, summary(value))
            }
        }
        assertEquals(before, detailDatabaseState(db))
    }

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun localPresentationMatchesEveryOriginalNativeCaseWithoutWrites() {
        val db = database()
        val before = detailDatabaseState(db)
        val inputs = cases().associateBy { it.getValue("name").jsonPrimitive.content }
        val presentations = Json.parseToJsonElement(root().resolve("presentation-cases.json").readText()).jsonArray
        var editedTargets = 0
        for (raw in presentations) {
            val case = raw.jsonObject
            val input = inputs.getValue(case.getValue("snapshot_case").jsonPrimitive.content)
            snapshot(db, input, true) { context, prepared, native ->
                val local = localSnapshot(context, prepared, input)
                @Suppress("UNCHECKED_CAST") val groups = (cashSourceValue(case.getValue("groups")) as List<MutableMap<String, Any?>>).toMutableList()
                @Suppress("UNCHECKED_CAST") val localGroups = (localValue(groups) as List<MutableMap<String, Any?>>).toMutableList()
                assertEquals(wire(CashImportRelationDecisions.applyGroups(native, groups)),
                    wire(com.finance.tracker.business.domain.applyLocalCashImportRelationGroups(local, localGroups)))
                @Suppress("UNCHECKED_CAST") val failures = cashSourceValue(case.getValue("failures")) as List<Map<String, Any?>>
                for (failure in failures) for (parent in failure["parent_ids"] as List<*>) {
                    native.facts[parent]?.let { native.facts[parent.toString()] = it.withAttention(true) }
                    local.facts[parent]?.let { local.facts[parent.toString()] = it.withAttention(true) }
                }
                assertEquals(summary(native), summary(local), case["name"].toString())
                val localBefore = summary(local)
                val include = case.getValue("include_unchanged").jsonPrimitive.boolean
                val entryParents = case.getValue("entry_parent_ids").jsonArray.map { it.jsonPrimitive.content }.toSet()
                val files = listOf(ParsedImportFile("ready", "a".repeat(64), "parsed", filename = "statement.csv"),
                    ParsedImportFile("password", "b".repeat(64), "needs_password", errorCode = "password_required"))
                val expected = CashImportPreviewPresentation.present(files, native, native.build(), groups, failures,
                    prepared.accounts, include, entryParents)
                val actual = com.finance.tracker.business.domain.presentLocalCashImportPreview(files, local, localGroups,
                    failures, prepared.accounts, include, entryParents)
                assertEquals(wire(expected), wire(actual), case["name"].toString())
                assertEquals(localBefore, summary(local))
                @Suppress("UNCHECKED_CAST") val previousNative = CashRecordPolicy.copyValue(expected) as Map<String, Any?>
                @Suppress("UNCHECKED_CAST") val previousLocal = localValue(actual) as Map<String, Any?>
                val oldNative = wire(previousNative)
                val oldLocal = wire(previousLocal)
                val refs = (expected.getValue("item_details") as Map<*, *>).values.mapNotNull { detail ->
                    ((detail as Map<*, *>)["entry"] as? Map<*, *>)?.get("edit_ref") as? String
                }.distinct()
                val choices = refs.map { ref -> mapOf("edit_ref" to ref, "patch" to mapOf("note" to "checked-$ref")) }
                editedTargets += choices.size
                val choicesBefore = wire(choices)
                CashImportEntryChoices.apply(expected, choices, previousNative) { true }
                com.finance.tracker.business.domain.applyLocalCashImportEntryChoices(actual, choices, previousLocal, emptySet())
                assertEquals(wire(expected), wire(actual), "public choices: ${case["name"]}")
                assertEquals(oldNative, wire(previousNative))
                assertEquals(oldLocal, wire(previousLocal))
                assertEquals(choicesBefore, wire(choices))
                assertEquals(localBefore, summary(local))
            }
        }
        assertTrue(editedTargets > 0)
        assertEquals(before, detailDatabaseState(db))
    }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)

    @Test fun presentationKeepsFrozenMemberBoundariesAndFileResults() {
        val db = database()
        val normal = cases().single { it.getValue("name").jsonPrimitive.content == "normal" }
        snapshot(db, normal, true) { _, prepared, value ->
            val newParent = value.parentOps.entries.single { it.value["planned_action"] == "create_posted" }.key
            value.parentOps.remove(newParent)
            value.values.getValue(newParent)["record_origin"] = "manual"
            val files = listOf(ParsedImportFile("f_ready", "a".repeat(64), "parsed", filename = "账单.csv"),
                ParsedImportFile("f_password", "b".repeat(64), "needs_password", errorCode = "password_required"))
            val before = summary(value)
            val result = CashImportPreviewPresentation.present(files, value, value.build(), emptyList(), emptyList(),
                prepared.accounts, entryParentIds = setOf(newParent))
            val json = wire(result).jsonObject
            val fields = json.getValue("detail_values").jsonObject.getValue(newParent).jsonObject
            assertFalse("source_scope_key" in fields)
            assertTrue(fields.getValue("components").jsonArray.all { "component_id" !in it.jsonObject })
            val member = json.getValue("item_details").jsonObject.values.map { it.jsonObject }
                .flatMap { it.getValue("members").jsonArray }.map { it.jsonObject }
                .single { it["transaction_id"] == JsonPrimitive(newParent) }
            assertTrue("amount" in member.getValue("editable_fields").jsonArray.map { it.jsonPrimitive.content })
            assertFalse("source_payload" in member.getValue("editable_fields").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(JsonArray(emptyList()), member.getValue("allowed_actions"))
            assertTrue(json.getValue("can_commit").jsonPrimitive.boolean)
            assertEquals(listOf("ready", "password_required"), json.getValue("file_results").jsonArray
                .map { it.jsonObject.getValue("status").jsonPrimitive.content })
            assertTrue(json.getValue("file_results").jsonArray.all {
                "client_file_ref" !in it.jsonObject && "source_type" !in it.jsonObject
            })
            assertEquals(before, summary(value))
        }
    }

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)

    @Test fun matchingFactsAndDiscoveryMatchPythonWithoutWrites() {
        val db = database()
        val before = detailDatabaseState(db)
        for (case in cases()) snapshot(db, case) { context, prepared, value ->
            val discovery = CashImportRelationDiscovery(context)
            assertEquals(case.getValue("matching_facts"), wire(discovery.matchingFacts(value, prepared.accounts).map(::matchingFact)))
            val seeds = case.getValue("discovery_seeds").jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertEquals(case.getValue("proposals"), wire(discovery.discover(value, seeds, prepared.accounts).map { it.json() }))
            assertEquals(JsonArray(emptyList()), case.getValue("discovery_failures"))
        }
        assertEquals(before, detailDatabaseState(db))
    }

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)

    @Test fun discoveryUsesSourceHintsFiltersManualRecordsAndRespectsAcceptedRelations() {
        val db = database()
        val before = detailDatabaseState(db)
        val normal = cases().single { it.getValue("name").jsonPrimitive.content == "normal" }
        val seeds = normal.getValue("discovery_seeds").jsonArray.map { it.jsonPrimitive.content }.toSet()
        snapshot(db, normal) { context, prepared, value ->
            val discovery = CashImportRelationDiscovery(context)
            assertTrue(discovery.discover(value, emptySet(), prepared.accounts).isEmpty())
            val proposal = discovery.discover(value, seeds, prepared.accounts).first { it.kind == "payment_mirror" }
            assertTrue("9508" in setOf(proposal.primaryFactId, proposal.secondaryFactId))
            val first = value.components.getValue(proposal.primaryFactId)
            val second = value.components.getValue(proposal.secondaryFactId!!)
            value.edges[BigInteger.valueOf(999)] = CashRelationGraphEdge(999L, "payment_mirror",
                first.transactionId, second.transactionId, first.componentId, second.componentId,
                "expense", "expense", state = "confirmed")
            assertTrue(discovery.discover(value, seeds, prepared.accounts).none {
                it.primaryFactId == proposal.primaryFactId && it.secondaryFactId == proposal.secondaryFactId
            })
        }
        snapshot(db, normal) { context, prepared, value ->
            val discovery = CashImportRelationDiscovery(context)
            value.values.getValue("7507")["record_origin"] = "manual"
            assertTrue(discovery.discover(value, seeds, prepared.accounts).isEmpty())
            val wechat = value.values.getValue("7502")
            wechat["record_type"] = "refund"
            wechat["source_explanation"] = mapOf("_wechat_direction" to "支出", "platform_status" to "已全额退款")
            wechat["source_payload"] = mapOf("商品" to "账单原文商品")
            value.components.replaceAll { _, part -> if (part.transactionId == "7502") part.copy(recordType = "refund") else part }
            val fact = discovery.matchingFacts(value, prepared.accounts).single { it.parentId == "7502" }
            assertEquals("expense", fact.relationMetadata?.get("offset_role"))
            assertEquals("账单原文商品", fact.note)
            assertEquals("新备注", value.facts.getValue("7502").note)
            assertFalse("offset_role" in (wechat["source_explanation"] as Map<*, *>))
            wechat["source_explanation"] = mapOf("direction" to "支出", "status" to "已全额退款")
            assertEquals("expense", discovery.matchingFacts(value, prepared.accounts).single {
                it.parentId == "7502"
            }.relationMetadata?.get("offset_role"))
        }
        snapshot(db, normal) { context, prepared, value ->
            val newParent = value.parentOps.entries.single { it.value["planned_action"] == "create_posted" }.key
            value.values.getValue(newParent)["record_origin"] = "manual"
            assertTrue(CashImportRelationDiscovery(context).discover(value, seeds, prepared.accounts).isEmpty())
        }
        assertEquals(before, detailDatabaseState(db))
    }

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)

    @Test fun snapshotMatchesPythonOnBothBackendsWithoutWrites() {
        val db = database()
        val before = detailDatabaseState(db)
        for (case in cases()) {
            assertEquals(case.getValue("snapshot"), summary(snapshot(db, case)), case.getValue("name").jsonPrimitive.content)
        }
        assertEquals(before, detailDatabaseState(db))
    }

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)

    @Test fun typeUpdatesRemoveOnlyInvalidAssociationsAndRestoreTheWholeRejectedOperation() {
        val db = database()
        val before = detailDatabaseState(db)
        val cases = cases().associateBy { it.getValue("name").jsonPrimitive.content }
        val removed = snapshot(db, cases.getValue("type_change_remove_mirror"))
        assertEquals(1, removed.removedRelations.size)
        assertFalse(removed.edges.getValue(removed.removedRelations.single()).active as Boolean)
        assertTrue(removed.edges.values.single { it.kind == "refund_offset" }.active as Boolean)
        assertEquals("investment_out", removed.values.getValue("7501")["record_type"])

        val restored = snapshot(db, cases.getValue("type_change_restore_graph_error"))
        assertEquals("consumption", restored.values.getValue("7501")["record_type"])
        assertTrue(restored.removedRelations.isEmpty())
        assertTrue(restored.edges.values.all { it.active == true })
        assertFalse("7501" in restored.parentOps)
        assertTrue(restored.operations.none { it["operation_ref"] == "op_1" })
        val rejected = restored.rows.single { it["row_ref"] == "f_a" + "0".repeat(31) + ":r1" }
        assertEquals("skip", rejected["planned_action"])
        assertEquals("relation_endpoint_incompatible", rejected["code"])
        assertNull(rejected["operation_ref"])

        val funding = snapshot(db, cases.getValue("type_change_remove_funding"))
        assertEquals(1, funding.removedFunding.size)
        assertTrue(funding.funding.isEmpty())
        val expense = funding.build().projections.single { it.projectionId == "cash:7505" }
        assertEquals(0, BigDecimal("-7.00").compareTo(expense.netAmount!!.arithmeticValue()))
        assertEquals(CashEconomicType.EXPENSE, expense.economicType)
        assertNull(expense.fundingRelationId)

        val aggregate = snapshot(db, cases.getValue("type_change_aggregate"))
        assertNull(aggregate.facts.getValue("7506").accountId)
        assertEquals(2, aggregate.components.values.count { it.transactionId == "7506" })
        assertEquals(0, BigDecimal("-12.30").compareTo(aggregate.build().projections.single {
            it.projectionId == "cash:7506"
        }.netAmount!!.arithmeticValue()))
        assertEquals(before, detailDatabaseState(db))
    }
}
