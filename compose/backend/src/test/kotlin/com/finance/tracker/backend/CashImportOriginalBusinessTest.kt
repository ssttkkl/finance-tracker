package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.math.BigInteger
import java.sql.Connection
import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportOriginalBusinessTest {
    private fun fixture(name: String = "base") = CashImportOriginalApiFixture(name, "FT_KOTLIN_IMPORT_ORIGINAL_BUSINESS_CONTRACT_DIR")
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun maps(value: Any?) = value as List<Map<String, Any?>>
    private fun items(plan: Map<String, Any?>) = maps(plan["items"])
    private fun groups(plan: Map<String, Any?>) = maps(plan["relation_groups"])
    private fun summary(plan: Map<String, Any?>, key: String) = map(plan["summary"])[key].toString().toInt()
    private fun detail(plan: Map<String, Any?>, item: Map<String, Any?> = items(plan).single()) =
        CashImportPlanViews.resolveDetail(plan, map(plan["item_details"]).getValue(item.getValue("preview_item_id") as String))
    private fun member(plan: Map<String, Any?>) = maps(detail(plan)["members"]).single()
    private fun selection(group: Map<String, Any?>, action: String = "defer", candidate: Any? = null) =
        linkedMapOf<String, Any?>("suggestion_id" to group.getValue("suggestion_id"), "action" to action).apply { if (candidate != null) put("candidate_id", candidate) }
    private fun row(patch: Map<String, Any?>) = mapOf("row_ref" to "file_a:r1", "action" to "apply", "patch" to patch)
    private fun reference(id: String) = mapOf("transaction_id" to id)
    private fun amount(item: Map<String, Any?>) = BigDecimal(map(item["display"])["amount"].toString())
    private fun invalid(block: () -> Unit) = assertEquals("plan_input_invalid", assertFailsWith<CashImportPlanError>(block = block).code)
    private fun queryOnly(connection: Connection): Connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
        if (method.name == "prepareStatement") {
            val query = (args!![0] as String).replace(Regex("""'(?:''|[^'])*'|"(?:""|[^"])*"|`(?:``|[^`])*`"""), " ")
            val words = Regex("[A-Za-z_][A-Za-z_0-9]*").findAll(query).map { it.value.lowercase() }.toList()
            assertTrue(words.first() in setOf("select", "with") && words.none {
                it in setOf("insert", "update", "delete", "merge", "create", "drop", "alter", "truncate", "call", "copy")
            } && !Regex("(?i)\\breplace\\s+into\\b").containsMatchIn(query), "Read-only builder must issue queries without mutations")
        }
        check(method.name != "createStatement" && method.name != "prepareCall") { "Unobserved SQL execution path" }
        try { method.invoke(connection, *(args ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
    } as Connection

    private inner class Builder(private val sqlContext: CashSqlContext, private val mode: String,
        private val counts: MutableList<Int>, private val complete: Boolean) : CashImportBusinessPlanBuilder(sqlContext) {
        var discoveries = 0
        override fun discover(snapshot: CashImportPreviewSnapshot, seeds: Set<String>, accounts: Map<String, Map<String, Any?>>): List<RelationProposal> {
            discoveries++
            if (mode == "forbid") fail("ordinary update started relation discovery")
            if (mode == "broken") error("SQL private bill body")
            val facts = CashImportRelationDiscovery(sqlContext).matchingFacts(snapshot, accounts)
            if (mode == "open") return facts.filter { it.recordType == "transfer_out" }.map {
                RelationProposal("transfer_pair", it.id, null, subtype = "ordinary_transfer",
                    evidence = RelationEvidence(openLeg = true, anchorRole = "outgoing", candidateFactIds = listOf("1001", "1002")))
            }
            if (mode == "invalid") {
                fun pair(amount: String) = facts.filter { it.amount.compareTo(BigDecimal(amount)) == 0 && it.parentId!!.toBigInteger() >= BigInteger.ONE.shiftLeft(63) }.sortedBy { it.billSource }
                val good = pair("-12.5"); val bad = pair("-7")
                return listOf(RelationProposal("payment_mirror", good[0].id, good[1].id, status = "accepted", confidence = "strong"),
                    RelationProposal("transfer_pair", bad[0].id, bad[1].id, subtype = "ordinary_transfer", status = "accepted", confidence = "strong"))
            }
            return super.discover(snapshot, seeds, accounts)
        }
        override fun preparePreview(operations: PreparedCashImportOperations, input: Map<String, Any?>, previous: Map<String, Any?>?): PreparedCashImportPreview {
            if (!complete) return super.preparePreview(operations, input, previous)
            return CashImportPreviewPreparation(sqlContext).prepare(operations.operations, operations.source.rows.rows,
                maps(input["entry_choices"] ?: emptyList<Map<String, Any?>>()), previous, additionalSeedIds = setOf(1003L))
        }
        override fun snapshot(prepared: PreparedCashImportPreview, rows: List<Map<String, Any?>>): CashImportPreviewSnapshot =
            super.snapshot(prepared, rows).copy(graphBuilder = { facts, parts, edges, funding ->
                counts += (facts as Collection<*>).size
                buildCashRelationGraph(facts, parts, edges, funding)
            })
    }
    private fun build(f: CashImportOriginalApiFixture, variant: String = "default", mapped: Boolean = true,
        rows: List<Map<String, Any?>> = emptyList(), relations: List<Map<String, Any?>> = emptyList(),
        previous: Map<String, Any?>? = null, mode: String = "real", counts: MutableList<Int> = mutableListOf(),
        complete: Boolean = false, observed: (Builder) -> Unit = {}): Map<String, Any?> {
        val before = f.snapshot()
        try {
            return f.database.read {
                val context = CashSqlContext(queryOnly(it), f.workspace, f.database.sqlite, now = { f.now })
                val files = f.files(variant)
                val input = mapOf("account_selections" to if (mapped) CashImportFunding.scan(files).groups.map { group ->
                    mapOf("source_account_ref" to group.sourceAccountRef, "account_id" to "101", "expected_account_revision" to 1) } else emptyList(),
                    "row_choices" to rows, "relation_choices" to relations)
                val builder = Builder(context, mode, counts, complete)
                try { builder.build(files, input, previous) } finally { observed(builder) }
            }
        } finally { assertEquals(before, f.snapshot()) }
    }
    private fun changeNote(f: CashImportOriginalApiFixture, text: String) = f.database.write { connection ->
        connection.jooq(f.database.sqlite).update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.NOTE, text)
            .set(CASH_TRANSACTIONS.ROW_REVISION, CASH_TRANSACTIONS.ROW_REVISION.plus(1L))
            .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(f.workspace).and(CASH_TRANSACTIONS.ID.eq(1001L))).execute()
    }
    private fun noRelations(f: CashImportOriginalApiFixture) = assertEquals(0, f.database.read {
        it.jooq(f.database.sqlite).selectCount().from(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(f.workspace)).fetchSingle().value1()
    })

    @Test fun missingAlipayFundingUsesWalletInActualBusinessPlan() {
        val plan = build(fixture(), "wallet"); val operation = maps(plan["operations"]).single(); val item = items(plan).single()
        assertEquals("create_posted", operation["planned_action"]); assertEquals("101", map(operation["values"])["account_id"])
        assertFalse(maps(operation["issues"]).any { it["code"] == "funding_unrecognized" })
        assertEquals("posted", item["anticipated_state"]); assertEquals(true, item["statistics_eligible"])
    }
    @Test fun actualNewAndDraftItemsAreReadOnlyResults() {
        val f = fixture(); val posted = build(f); val draft = build(f, mapped = false)
        assertEquals(true, posted["can_commit"]); assertEquals(1, items(posted).size)
        assertEquals("-12.5", map(items(posted).single()["display"])["amount"])
        assertEquals("confirmed", items(posted).single()["confirmation_status"]); assertEquals(true, items(posted).single()["statistics_eligible"])
        val item = items(draft).single(); assertEquals("pending", item["confirmation_status"])
        assertEquals("draft", item["anticipated_state"]); assertEquals(false, item["statistics_eligible"])
        assertEquals(1, summary(posted, "create_posted")); assertEquals(1, summary(draft, "create_draft"))
    }
    @Test fun keepExistingIsOfferedOnlyForSavedRecord() {
        assertFalse("keep_existing" in (member(build(fixture()))["allowed_actions"] as List<*>))
        assertTrue("keep_existing" in (member(build(fixture("bound"), "changed"))["allowed_actions"] as List<*>))
    }
    @Test fun frozenPreviewStoresMemberValuesOnceAndResolvesIndependentDetail() {
        val plan = build(fixture(), "evidence"); val evidence = "synthetic-unique-evidence-" + "x".repeat(10000)
        assertEquals(1, sourceJson(plan).toString().split(evidence).size - 1)
        val first = detail(plan); val payload = map(map(maps(first["members"]).single()["fields"])["source_payload"])
        assertEquals(evidence, payload["synthetic"])
        @Suppress("UNCHECKED_CAST") val mutablePayload = payload as MutableMap<String, Any?>
        mutablePayload["synthetic"] = "local change"
        assertEquals(evidence, map(map(maps(detail(plan)["members"]).single()["fields"])["source_payload"])["synthetic"])
    }
    @Test fun wechatRefundNumberEvidenceKeepsScopeForAllFourOriginalCases() {
        for (stored in listOf(false, true)) for (same in listOf(false, true)) {
            val plan = build(fixture(if (stored) "wechat" else "base"), "wechat_${if (stored) "refund_" else ""}$same")
            assertEquals(same, groups(plan).any { it["action"] == "accept" })
            if (same) { assertEquals(1, items(plan).size); assertEquals(0, amount(items(plan).single()).compareTo(BigDecimal("-7.5"))) }
            else assertTrue(items(plan).all { (it["member_refs"] as List<*>).size == 1 })
        }
    }
    @Test fun equalSizedMirrorsWithoutUniqueEvidenceDeferBothOriginalCases() {
        for (same in listOf(false, true)) {
            val plan = build(fixture(), "ambiguous_$same")
            assertEquals(4, maps(plan["operations"]).size); assertEquals(4, items(plan).size)
            assertTrue(maps(plan["operations"]).all { it["planned_action"] == "create_posted" })
            assertEquals(emptyList<Any?>(), plan["relation_failures"]); assertTrue(groups(plan).isNotEmpty())
            assertTrue(groups(plan).all { it["action"] == "defer" }); assertTrue(items(plan).all { it["confirmation_status"] == "pending" })
        }
    }
    @Test fun truncatedMirrorCandidatesKeepAnchorAndRemainSelectable() {
        val plan = build(fixture("truncated"), "special")
        assertEquals("create_posted", maps(plan["operations"]).first()["planned_action"])
        val group = groups(plan).single(); assertEquals("defer", group["action"])
        assertTrue(maps(group["candidates"]).any { reference("2001") in (it["member_refs"] as List<*>) })
    }
    @Test fun confirmedMirrorProjectsOneItemBeforePersistence() {
        val f = fixture(); val plan = build(f, "mirror"); val item = items(plan).single()
        assertEquals("-12.5", map(item["display"])["amount"]); assertEquals("confirmed", item["confirmation_status"])
        assertEquals(setOf(mapOf("row_ref" to "file_a:r1"), mapOf("row_ref" to "file_b:r1")), maps(item["member_refs"]).toSet())
        assertEquals(2, summary(plan, "create_posted")); assertEquals(1, summary(plan, "relations_accepted"))
        assertEquals(1, groups(plan).size); noRelations(f)
    }
    @Test fun deferredMirrorKeepsTwoPostedItemsAndIndependentStatistics() {
        val f = fixture(); val first = build(f, "mirror"); val plan = build(f, "mirror", previous = first, relations = listOf(selection(groups(first).first())))
        assertEquals(2, items(plan).size); assertEquals(1, summary(plan, "relations_deferred"))
        assertTrue(items(plan).all { it["confirmation_status"] == "pending" && it["statistics_eligible"] == true && it["anticipated_state"] == "posted" })
        assertEquals(0, items(plan).map(::amount).fold(BigDecimal.ZERO, BigDecimal::add).compareTo(BigDecimal("-25")))
        assertTrue(items(plan).all { maps(it["attention_reasons"]).any { reason -> reason["code"] == "relation_pending" } })
    }
    @Test fun ordinarySourceUpdateUsesExistingProjectionWithoutRediscovery() {
        val plan = build(fixture("existing_mirror"), "ordinary", mapped = false, mode = "forbid")
        val item = items(plan).single(); assertEquals("来源新名称", map(item["display"])["counterparty"])
        assertEquals(0, amount(item).compareTo(BigDecimal("-12.5")))
        assertEquals(setOf(mapOf("row_ref" to "file_c:r1"), reference("1001")), maps(item["member_refs"]).toSet())
        assertEquals(1, summary(plan, "update_posted")); assertEquals(0, map(plan["summary"]).getOrDefault("create_posted", 0))
    }
    @Test fun typeEditRejectsWithoutRemovingExistingRelation() {
        val f = fixture("existing_mirror")
        invalid { build(f, mapped = false, rows = listOf(row(mapOf("record_type" to "transfer_out", "record_subtype" to "ordinary_transfer")))) }
        f.database.read { connection ->
            val sql = connection.jooq(f.database.sqlite)
            assertEquals(true, sql.select(TRANSACTION_RELATIONS.ACTIVE).from(TRANSACTION_RELATIONS)
                .where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(f.workspace)).fetchSingle().value1())
            assertEquals("consumption", sql.select(CASH_TRANSACTIONS.RECORD_TYPE).from(CASH_TRANSACTIONS)
                .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(f.workspace).and(CASH_TRANSACTIONS.ID.eq(1003L))).fetchSingle().value1())
        }
    }
    @Test fun conflictsAndUnreadableRowsHaveDetailsWithoutFakeItems() {
        val plan = build(fixture("bound"), "conflicts")
        assertEquals(1, items(plan).size); assertEquals(2, summary(plan, "skip"))
        assertEquals(setOf("fixed_field_conflict", "row_unreadable"), maps(plan["processing_details"]).mapNotNull { it["code"] }.toSet())
    }
    @Test fun discoveryFailureStopsWithoutRecursiveRetry() {
        var calls = 0
        val failure = assertFailsWith<IllegalStateException> { build(fixture(), "mirror", mode = "broken", observed = { calls = it.discoveries }) }
        assertEquals("SQL private bill body", failure.message); assertEquals(1, calls)
    }
    @Test fun unidentifiedRecordIdIsReusedWhenOnlyRelationChoiceChanges() {
        val f = fixture(); val first = build(f, "unidentified")
        val second = build(f, "unidentified", previous = first, relations = listOf(selection(groups(first).first())))
        assertEquals(maps(first["operations"]).map { map(it["values"])["record_id"] }, maps(second["operations"]).map { map(it["values"])["record_id"] })
    }
    @Test fun duplicateAndAllConflictsCountRowsOnceAndCanFinish() {
        val f = fixture("bound"); val duplicate = build(f, mapped = false)
        assertEquals(1, summary(duplicate, "duplicate")); assertEquals(true, duplicate["can_commit"])
        assertEquals(emptyList<Any?>(), duplicate["items"]); assertEquals(1, maps(duplicate["processing_details"]).size)
        val conflict = build(f, "conflict_only", mapped = false)
        assertEquals(true, conflict["can_commit"]); assertEquals(1, summary(conflict, "skip")); assertEquals(emptyList<Any?>(), conflict["items"])
    }
    @Test fun newAndFinalizingRecordsOfferFinancialFieldsBeforeCommit() {
        val fields = member(build(fixture()))["editable_fields"] as List<*>
        assertTrue(fields.containsAll(listOf("account_id", "amount", "currency", "components", "occurred_at")))
    }
    @Test fun actualBuilderPublishesReadsAndRepreviewsThroughSessionService() {
        val f = fixture(); val before = f.snapshot(); val files = f.files("mirror")
        val service = CashImportHttpService(f.database, f.store) { upload, _ -> files[if (upload.clientFileRef == "first") 0 else 1].document!! }
        val created = service.create(f.token, listOf(CashImportUpload("first", "one.csv", "first".encodeToByteArray()), CashImportUpload("second", "two.csv", "second".encodeToByteArray())))
        val sessionId = created["session_id"] as String
        val stored = f.store.get(sessionId, f.workspace, f.user)
        val selections = CashImportFunding.scan(stored.files).groups.map { mapOf("source_account_ref" to it.sourceAccountRef, "account_id" to "101", "expected_account_revision" to 1) }
        val plan = service.preview(f.token, sessionId, 1, null, mapOf("account_selections" to selections))
        assertEquals(1, items(plan).size); assertFalse("operations" in plan); assertFalse("relation_groups" in plan)
        val detail = service.planItem(f.token, plan["plan_id"] as String, items(plan).single()["preview_item_id"] as String)
        assertEquals(2, maps(detail["members"]).size); assertEquals(1, maps(detail["relation_choices"]).size)
        assertTrue(maps(detail["members"]).all { member -> maps(map(member["fields"])["components"]).all { it["component_id"] == null || it["component_id"] == "" } })
        val second = service.preview(f.token, sessionId, 2, plan["plan_id"] as String,
            mapOf("account_selections" to selections, "relation_choices" to listOf(selection(maps(detail["relation_choices"]).single()))))
        assertEquals(2, items(second).size); assertTrue(items(second).all { it["confirmation_status"] == "pending" })
        assertEquals(before, f.snapshot())
    }
    @Test fun refundAndMirrorBundleProjectsAndDefersAsOneDecisionForBothAmounts() {
        val f = fixture()
        for ((variant, expected) in listOf("bundle_partial" to "-7.5", "bundle_full" to "0")) {
            val first = build(f, variant); val item = items(first).single()
            assertEquals(0, amount(item).compareTo(BigDecimal(expected))); assertEquals("confirmed", item["confirmation_status"])
            val group = groups(first).single(); assertTrue(maps(group["edges"]).size >= 3); assertEquals(4, maps(group["member_refs"]).size)
            val deferred = build(f, variant, previous = first, relations = listOf(selection(group)))
            assertEquals(4, items(deferred).size); assertTrue(items(deferred).all { it["confirmation_status"] == "pending" && it["statistics_eligible"] == true })
            assertEquals(1, summary(deferred, "relations_deferred"))
        }
    }
    @Test fun independentGroupsValidateOwnClosureAndOnlyFinalGraphUsesWholeImport() {
        val counts = mutableListOf<Int>(); val plan = build(fixture(), "many", counts = counts)
        assertEquals(20, items(plan).size); assertEquals(20, groups(plan).size)
        assertTrue(counts.last() >= 40); assertEquals(1, counts.count { it > 2 })
        assertTrue(counts.dropLast(1).all { it <= 2 })
    }
    @Test fun openCandidateFromPreviousPlanConfirmsWholeDecision() {
        val f = fixture("open"); val first = build(f, "special", mode = "open"); val group = groups(first).single()
        assertEquals(2, maps(group["candidates"]).size); assertEquals("defer", group["action"])
        val candidate = maps(group["candidates"]).single { reference("1002") in maps(it["member_refs"]) }
        val second = build(f, "special", mode = "open", previous = first, relations = listOf(selection(group, "accept", candidate["candidate_id"])))
        val item = items(second).single(); assertEquals("confirmed", item["confirmation_status"])
        assertEquals(0, amount(item).compareTo(BigDecimal.ZERO)); assertTrue(reference("1002") in maps(item["member_refs"])); noRelations(f)
    }
    @Test fun candidateChangeInvalidatesOldChoiceWithoutSilentlySelectingAnother() {
        val f = fixture("open_change"); val first = build(f, "special", mode = "open"); val group = groups(first).single()
        val choice = selection(group, "accept", maps(group["candidates"]).first()["candidate_id"])
        changeNote(f, "用户尚未看过的新状态")
        val second = build(f, "special", mode = "open", previous = first, relations = listOf(choice))
        assertEquals(listOf(mapOf("kind" to "relation", "reference" to choice["suggestion_id"], "code" to "relation_context_changed")), second["invalidated_choices"])
        assertTrue(groups(second).none { it["action"] == "accept" })
    }
    @Test fun choiceCannotInjectForeignCandidateOutsidePreviousPlan() {
        val f = fixture("open"); val first = build(f, "special", mode = "open")
        invalid { build(f, "special", mode = "open", previous = first, relations = listOf(selection(groups(first).first(), "accept", "foreign"))) }
    }
    @Test fun reliableRefundKeysFindOlderRecordsForBothOriginalSources() {
        for (source in listOf("alipay", "wechat")) {
            val plan = build(fixture("old_$source"), "special"); val item = items(plan).single()
            assertEquals(0, amount(item).compareTo(BigDecimal("-7.5"))); assertEquals("confirmed", item["confirmation_status"])
            assertTrue(reference("1003") in maps(item["member_refs"]))
        }
    }
    @Test fun competingEndpointChoicesFailTogetherWithoutKeepingFirstPair() {
        val f = fixture("open"); val first = build(f, "competing", mode = "open"); assertEquals(2, groups(first).size)
        val choices = groups(first).map { group -> selection(group, "accept", maps(group["candidates"]).single {
            reference("1001") in maps(it["member_refs"]) }["candidate_id"]) }
        val conflicted = build(f, "competing", mode = "open", previous = first, relations = choices)
        assertTrue(groups(conflicted).all { it["action"] == "skip" })
        assertTrue(items(conflicted).all { maps(it["member_refs"]).size == 1 })
        assertEquals(1, groups(conflicted).map { it["commit_group_id"] }.toSet().size)
        assertEquals(2, maps(conflicted["operations"]).size)
    }
    @Test fun oneInvalidRelationReportsReasonWhileIndependentPairRemains() {
        val plan = build(fixture(), "independent", mode = "invalid")
        assertEquals(4, maps(plan["operations"]).size); assertEquals(3, items(plan).size)
        assertEquals(1, summary(plan, "relations_accepted")); assertEquals(1, summary(plan, "relations_failed"))
        assertEquals(2, items(plan).count { it["confirmation_status"] == "pending" })
    }
    @Test fun explicitCurrentChoiceWinsRetainedInvalidatedChoiceInAllFourOrders() {
        for (action in listOf("accept", "ignore")) for (oldFirst in listOf(true, false)) {
            val f = fixture("open_${action}_$oldFirst"); val first = build(f, "special", mode = "open")
            val initial = groups(first).single(); val stale = selection(initial, "accept", maps(initial["candidates"]).first()["candidate_id"])
            changeNote(f, "候选已变化，需要重新确认")
            val changed = build(f, "special", mode = "open", previous = first, relations = listOf(stale)); val current = groups(changed).single()
            assertEquals("defer", current["action"]); assertNotEquals(stale["suggestion_id"], current["suggestion_id"])
            val choice = selection(current, action, maps(current["candidates"]).first()["candidate_id"])
            val selected = build(f, "special", mode = "open", previous = changed, relations = if (oldFirst) listOf(stale, choice) else listOf(choice, stale))
            assertEquals(action, groups(selected).single()["action"]); assertTrue(maps(selected["invalidated_choices"]).isNotEmpty())
            if (action == "accept") { assertEquals(1, items(selected).size); assertEquals("confirmed", items(selected).single()["confirmation_status"]) }
            noRelations(f)
        }
    }
    @Test fun nonzeroGenerationSurvivesFileInvalidationAndBlocksFingerprintRevival() {
        val f = fixture("open"); val first = build(f, "special", mode = "open"); val initial = groups(first).first()
        val choice0 = selection(initial, "accept", maps(initial["candidates"]).first()["candidate_id"])
        val changed = build(f, "special", mode = "open", previous = first, relations = listOf(choice0),
            rows = listOf(row(mapOf("source_payload" to mapOf("备注" to "第一次来源变化")))))
        val returned = build(f, "special", mode = "open", previous = changed, relations = listOf(choice0)); val current = groups(returned).first()
        assertEquals(1, current["choice_generation"].toString().toInt()); assertEquals("defer", current["action"])
        val choice1 = selection(current, "accept", maps(current["candidates"]).first()["candidate_id"])
        val selected = build(f, "special", mode = "open", previous = returned, relations = listOf(choice1))
        val session = f.store.create(f.workspace, f.user, f.files("special")).copy(plan = selected)
        val proof = map(CashImportChoiceContext.retained(session).getValue(CashImportChoiceContext.CONTEXT_KEY))
        val invalidated = build(f, "special", mode = "open", previous = proof, relations = listOf(choice1),
            rows = listOf(row(mapOf("source_payload" to mapOf("备注" to "文件变化后再次来源变化")))))
        val final = build(f, "special", mode = "open", previous = invalidated, relations = listOf(choice1)); val group = groups(final).first()
        assertEquals("defer", group["action"]); assertEquals(2, group["choice_generation"].toString().toInt())
        assertNotEquals(choice1["suggestion_id"], group["suggestion_id"])
    }
    @Test fun candidateMembersResolveReadOnlyFromSameFrozenPlan() {
        val plan = build(fixture(), "candidate_members"); val pending = items(plan).first { it["confirmation_status"] == "pending" }
        val detail = CashImportPlanViews.publicDetail(detail(plan, pending))
        fun key(member: Map<String, Any?>) = member["row_ref"] to member["transaction_id"]
        val expected = maps(detail["relation_choices"]).flatMap { group -> (listOf(group) + maps(group["candidates"])).flatMap { maps(it["member_refs"]).map(::key) } }.toSet()
        val members = maps(detail["relation_members"]).associateBy(::key)
        assertTrue(expected.isNotEmpty()); assertTrue(members.keys.containsAll(expected))
        for (member in members.values) {
            assertEquals("-12.5", map(member["fields"])["amount"]); assertEquals("合成商户", map(member["fields"])["counterparty"])
            assertEquals(emptyList<Any?>(), member["editable_fields"]); assertEquals(emptyList<Any?>(), member["allowed_actions"])
            assertFalse("fields_ref" in member)
        }
    }
    @Test fun negativeConsumptionCandidateSourceGatePreservesAllFourOriginalCases() {
        for ((name, expected) in listOf("other" to false, "bank" to true, "platform" to true, "blank" to true)) {
            val f = fixture("negative_$name"); val before = f.snapshot()
            f.database.read { connection ->
                val context = CashSqlContext(queryOnly(connection), f.workspace, f.database.sqlite)
                val files = f.files("default"); val selections = CashImportFunding.scan(files).groups.map {
                    mapOf("source_account_ref" to it.sourceAccountRef, "account_id" to "101", "expected_account_revision" to 1) }
                val original = CashImportOperationPlanner(context).prepare(files, selections).operations.first()
                val value = map(original["values"]) + mapOf("record_type" to "consumption", "amount" to "-12.5")
                val operation = original + ("values" to value)
                assertEquals(expected, 1003L in CashImportCandidateRepository(context).candidateIds(listOf(operation)))
                val refund = operation + ("values" to (value + mapOf("record_type" to "refund", "amount" to "12.5")))
                assertTrue(1003L in CashImportCandidateRepository(context).candidateIds(listOf(refund)))
            }
            assertEquals(before, f.snapshot())
        }
    }
    @Test fun candidatePruningMatchesForcedCompleteDiscoveryWithOtherSourceRefund() {
        val f = fixture("pruning"); val pruned = build(f, "special"); val complete = build(f, "special", previous = pruned, complete = true)
        for (key in listOf("items", "item_details", "summary", "relation_groups", "relation_failures", "type_effects")) assertEquals(sourceJson(pruned[key]), sourceJson(complete[key]), key)
        assertEquals(emptyList<Any?>(), pruned["relation_groups"])
    }
}
