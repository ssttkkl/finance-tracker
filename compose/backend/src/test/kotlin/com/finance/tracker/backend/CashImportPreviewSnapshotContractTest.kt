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
        is CashAmount -> JsonPrimitive(fixedCashDecimal(CashStoredDecimal(value.toString())))
        is CashStoredDecimal -> JsonPrimitive(fixedCashDecimal(value))
        is CashDecimalLiteral -> JsonPrimitive(value.text)
        is BigDecimal -> JsonPrimitive(value.toPlainString())
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to wire(it.value) })
        is Iterable<*> -> JsonArray(value.map(::wire))
        else -> sourceJson(value)
    }
    private fun summary(snapshot: CashImportPreviewSnapshot): JsonObject = wire(linkedMapOf(
        "values" to snapshot.values,
        "facts" to snapshot.facts.mapValues { (_, f) -> mapOf(
            "id" to f.id, "account_id" to f.accountId, "occurred_at" to f.occurredAt, "amount" to f.amount,
            "currency" to f.currency, "counterparty" to f.counterparty, "category_id" to f.categoryId, "note" to f.note,
            "source_type" to f.sourceType, "record_id" to f.recordId, "funding_relation_id" to f.fundingRelationId,
            "cash_granularity" to f.cashGranularity, "state" to f.state, "created_at" to f.createdAt,
            "needs_attention" to f.needsAttention,
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
            "secondary_role" to e.secondaryRole, "subtype" to e.subtype, "applied_amount" to e.appliedAmount,
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

    private fun snapshot(db: Database, case: JsonObject): CashImportPreviewSnapshot = snapshot(db, case) { _, _, value -> value }

    private fun <T> snapshot(db: Database, case: JsonObject, normalizedOperations: Boolean = false,
        use: (CashSqlContext, PreparedCashImportPreview, CashImportPreviewSnapshot) -> T): T {
        @Suppress("UNCHECKED_CAST") val operations = cashSourceValue(case.getValue("operations")) as List<Map<String, Any?>>
        if (normalizedOperations) for (operation in operations) {
            @Suppress("UNCHECKED_CAST") val values = operation["values"] as MutableMap<String, Any?>
            for (field in financialCashFields) if (field in values) values[field] = CashRecordPolicy.normalize(field, values[field])
        }
        @Suppress("UNCHECKED_CAST") val rows = cashSourceValue(case.getValue("rows")) as List<Map<String, Any?>>
        val sourceRows = case.getValue("source_rows").jsonObject.mapValues { (_, value) ->
            val row = value.jsonObject
            SourceOccurrence(row.getValue("source_type").jsonPrimitive.content,
                text(row, "source_account_key"), row.getValue("file_digest").jsonPrimitive.content,
                row.getValue("parser_version").jsonPrimitive.content, row.getValue("table_key").jsonPrimitive.content,
                row.getValue("position").jsonPrimitive.int, emptyMap(), providerId = text(row, "provider_id"))
        }
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
            row.getValue("subtype").jsonPrimitive.content, row.getValue("status").jsonPrimitive.content,
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
                assertEquals(case.getValue("groups"), wire(result.groups), case.getValue("name").jsonPrimitive.content)
                assertEquals(case.getValue("failures"), wire(result.failures), case.getValue("name").jsonPrimitive.content)
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

    private fun candidateLimitGroup(value: CashImportPreviewSnapshot, allValid: Boolean): MutableMap<String, Any?> {
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
        return CashImportRelationGroups.build(value, proposals).groups.single()
    }

    @Test fun relationCandidatesStopAfterTwoThousandCombinationsAndTwentyValidChoices() {
        val db = database()
        val before = detailDatabaseState(db)
        val normal = cases().single { it.getValue("name").jsonPrimitive.content == "normal" }
        snapshot(db, normal) { _, _, value ->
            val group = candidateLimitGroup(value, false)
            assertTrue((group["candidates"] as List<*>).isEmpty())
            assertEquals(listOf("defer", "ignore"), group["allowed_actions"])
            val parent = value.parentOps.entries.single { it.value["planned_action"] == "create_posted" }.key
            @Suppress("UNCHECKED_CAST") val parts = value.values.getValue(parent)["components"] as List<Map<String, Any?>>
            val edges = parts.mapIndexed { index, part -> CashRelationGraphEdge((30000 + index).toLong(), "refund_offset",
                parent, (10044 + index * 100).toString(), part["component_id"], (20044 + index * 100).toString(),
                "expense", "refund", appliedAmount = CashDecimalLiteral("5")) }
            assertEquals(3, value.build(setOf(parent, "10044", "10144"), additions = edges).projections.single().memberIds.size)
        }
        snapshot(db, normal) { _, _, value ->
            val group = candidateLimitGroup(value, true)
            assertEquals(20, (group["candidates"] as List<*>).size)
            assertEquals(listOf("accept", "defer", "ignore"), group["allowed_actions"])
        }
        assertEquals(before, detailDatabaseState(db))
    }

    private fun mapList(value: JsonElement): List<MutableMap<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        return cashSourceValue(value) as List<MutableMap<String, Any?>>
    }

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
                assertEquals(case.getValue("expected"), wire(mapOf("groups" to groups, "invalidated" to result.invalidated,
                    "sources" to result.sources, "generations" to result.generations)), case.getValue("name").jsonPrimitive.content)
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

    private fun graphState(value: CashImportPreviewSnapshot) = JsonObject(summary(value).filterKeys {
        it in setOf("facts", "edges", "entry_public", "projections")
    })

    @Test fun applyingRelationGroupsAndRestoringSplitValuesMatchesPythonWithoutWrites() {
        val db = database()
        val before = detailDatabaseState(db)
        val snapshots = cases().associateBy { it.getValue("name").jsonPrimitive.content }
        val cases = Json.parseToJsonElement(root().resolve("relation-application-cases.json").readText()).jsonArray
        for (item in cases) {
            val case = item.jsonObject
            snapshot(db, snapshots.getValue(case.getValue("snapshot_case").jsonPrimitive.content), true) { context, _, value ->
                val groups = mapList(case.getValue("groups"))
                @Suppress("UNCHECKED_CAST") val overrides = cashSourceValue(case.getValue("entry_overrides")) as Map<String, Map<String, Any?>>
                value.entryPublic.putAll(overrides)
                for ((parent, raw) in case.getValue("amount_overrides").jsonObject) {
                    val amount = raw.jsonPrimitive.content
                    val fact = value.facts.getValue(parent)
                    value.facts[parent] = CashProjectionFact.create(fact.id, fact.accountId, fact.occurredAt, amount,
                        fact.currency, fact.counterparty, fact.categoryId, fact.note, fact.sourceType, fact.recordId,
                        fact.fundingRelationId, fact.cashGranularity, fact.state, fact.createdAt, fact.needsAttention)
                    value.values.getValue(parent)["amount"] = CashAmount.parse(amount)
                    @Suppress("UNCHECKED_CAST") val parts = value.values.getValue(parent)["components"] as List<MutableMap<String, Any?>>
                    for (part in parts) {
                        part["amount"] = CashAmount.parse(amount)
                        val id = part["component_id"] as String
                        value.components[id] = value.components.getValue(id).copy(amount = CashAmount.parse(amount))
                    }
                }
                if (case["error"] != null) {
                    val failure = assertFailsWith<CashPolicyError> { CashImportRelationDecisions.applyGroups(value, groups) }
                    assertEquals(case.getValue("error").jsonPrimitive.content, failure.code)
                    assertEquals(case.getValue("error_fields"), wire(failure.fields))
                    assertEquals(case.getValue("state"), graphState(value))
                } else {
                    if (case.getValue("restore_split").jsonPrimitive.boolean)
                        CashImportRelationDecisions.restoreSplitValues(context, value, groups)
                    val failures = CashImportRelationDecisions.applyGroups(value, groups)
                    assertEquals(case.getValue("expected"), wire(mapOf("groups" to groups, "failures" to failures,
                        "state" to graphState(value))), case.getValue("name").jsonPrimitive.content)
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
    )

    @Test fun finalPreviewPresentationMatchesPythonWithoutWrites() {
        val db = database()
        val before = detailDatabaseState(db)
        val inputs = cases().associateBy { it.getValue("name").jsonPrimitive.content }
        val presentations = Json.parseToJsonElement(root().resolve("presentation-cases.json").readText()).jsonArray
        for (raw in presentations) {
            val case = raw.jsonObject
            snapshot(db, inputs.getValue(case.getValue("snapshot_case").jsonPrimitive.content), true) { _, prepared, value ->
                @Suppress("UNCHECKED_CAST") val groups = cashSourceValue(case.getValue("groups")) as List<MutableMap<String, Any?>>
                CashImportRelationDecisions.applyGroups(value, groups)
                @Suppress("UNCHECKED_CAST") val failures = cashSourceValue(case.getValue("failures")) as List<Map<String, Any?>>
                for (failure in failures) for (parent in failure["parent_ids"] as List<*>) {
                    value.facts[parent]?.let { value.facts[parent.toString()] = it.withAttention(true) }
                }
                val snapshotBefore = summary(value)
                val actual = CashImportPreviewPresentation.present(emptyList(), value, value.build(), groups, failures,
                    prepared.accounts, case.getValue("include_unchanged").jsonPrimitive.boolean,
                    case.getValue("entry_parent_ids").jsonArray.map { it.jsonPrimitive.content }.toSet())
                assertEquals(case.getValue("expected"), wire(actual), case.getValue("name").jsonPrimitive.content)
                assertEquals(snapshotBefore, summary(value))
            }
        }
        assertEquals(before, detailDatabaseState(db))
    }

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

    @Test fun snapshotMatchesPythonOnBothBackendsWithoutWrites() {
        val db = database()
        val before = detailDatabaseState(db)
        for (case in cases()) {
            assertEquals(case.getValue("snapshot"), summary(snapshot(db, case)), case.getValue("name").jsonPrimitive.content)
        }
        assertEquals(before, detailDatabaseState(db))
    }

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
