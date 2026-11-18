package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class CashImportRelationDiscoveryTest {
    private fun preview(): Pair<CashImportPreviewSnapshot<CashImportPreviewInteger, Instant>, Map<String, Map<String, Any?>>> {
        val fixture = LocalCashImportBusinessPlanTest(); val source = fixture.source(); val files = fixture.files()
        val operations = prepareLocalCashImportOperations(files, source, fixture.input(files).getValue("account_selections"), wireValues = false)
        val prepared = LocalCashImportPreviewPreparation(source) {}.prepare(operations.operations, operations.source.rows.rows)
        return buildLocalCashImportPreviewSnapshot(source, prepared.context, prepared.operations, operations.rows) {
            Instant.parse("2026-10-01T00:00:00Z")
        } to prepared.accounts
    }

    private fun rules(active: () -> Unit = {}, needed: (List<RelationFact<String>>, Set<String>) -> Boolean = { _, _ -> true },
        plan: (List<RelationFact<String>>, List<String>, List<Map<String, Any?>>, Map<String, List<String>>,
            Map<String, List<String>>, String) -> List<RelationProposal> = { _, _, _, _, _, _ -> error("unexpected planner") }) =
        CashImportRelationDiscoveryRules(active, ::copyLocalCashValue, ::localCashImportOriginalExpense,
            { if (it is CashDecimalLiteral) it.text else it.toString() }, { localCashIntegerText(it)!!.toInt() }, needed, plan)

    @Test fun matchingUsesSourceNoteAndIndependentMetadataWithoutChangingThePreview() {
        val (preview, accounts) = preview()
        val before = localCashImportDigest(listOf(preview.values))
        val facts = rules().matchingFacts(preview, accounts)
        assertEquals(listOf("原始商品", "原始商品"), facts.map { it.note })
        assertTrue(facts.none { it.note == "edited-note" })
        val expense = facts.single { it.amount.startsWith('-') }
        assertNull(expense.rawPayload!!["offset_role"])
        assertEquals("ORDER", expense.rawPayload!!["txn_id"])
        assertEquals("现金", expense.accountName)
        assertEquals(before, localCashImportDigest(listOf(preview.values)))
        (expense.rawPayload as MutableMap)["txn_id"] = "changed"
        assertEquals(before, localCashImportDigest(listOf(preview.values)))
        val value = preview.values.getValue(expense.parentId!!)
        value["source_type"] = "wechat"; value["record_type"] = "refund"
        value["source_explanation"] = linkedMapOf("_wechat_direction" to "支出", "platform_status" to WECHAT_FULL_REFUND_STATUS)
        preview.components[expense.id] = preview.components.getValue(expense.id).copy(recordType = "refund")
        val wechat = rules().matchingFacts(preview, accounts).single { it.id == expense.id }
        assertEquals("expense", wechat.rawPayload!!["offset_role"])
        assertFalse("offset_role" in (value["source_explanation"] as Map<*, *>))
    }

    @Test fun noEligibleSeedsAndPredicateFalseNeverReadAliasesOrPlan() {
        val (preview, accounts) = preview()
        val seeds = preview.components.keys.toSet()
        fun aliases(): List<Map<String, Any?>> = error("unexpected alias read")
        assertEquals(emptyList(), rules().discover(preview, emptySet(), accounts, "w", ::aliases))
        assertEquals(emptyList(), rules(needed = { _, _ -> false }).discover(preview, seeds, accounts, "w", ::aliases))
        preview.values.values.forEach { it["record_origin"] = null }
        assertEquals(emptyList(), rules().discover(preview, seeds, accounts, "w", ::aliases))
        preview.values.values.forEach { it["record_origin"] = "manual" }
        assertEquals(emptyList(), rules().discover(preview, seeds, accounts, "w", ::aliases))
    }

    @Test fun missingOriginRemainsEligibleAndConfirmedEdgesAliasesAndFirstProposalArePreserved() {
        val (preview, accounts) = preview()
        preview.values.values.first().remove("record_origin")
        val seeds = preview.components.keys.toSet()
        val endpoints = seeds.toList()
        val accepted = CashRelationGraphEdge(CashImportPreviewInteger.parse("7"), "refund_offset", "p", "r",
            endpoints[0], endpoints[1], "expense", "refund", CashDecimalLiteral("2.00"))
        for (edge in listOf(accepted, accepted.copy(id = CashImportPreviewInteger.parse("8"), active = false),
            accepted.copy(id = CashImportPreviewInteger.parse("9"), state = "draft"),
            accepted.copy(id = CashImportPreviewInteger.parse("10"), secondaryComponentId = "excluded"))) {
            preview.edges[edge.id as CashImportPreviewInteger] = edge
        }
        val first = RelationProposal("refund_offset", endpoints[0], endpoints[1], ruleId = "first")
        var calls = 0
        val result = rules { facts, actualSeeds, edges, tails, identifiers, workspace ->
            calls++
            assertEquals(2, facts.size)
            assertEquals(seeds.sortedWith(Comparator(::pythonStringOrder)), actualSeeds)
            assertEquals(listOf("7"), edges.map { it["id"] })
            assertEquals("2.00", edges.single()["applied_amount"])
            assertEquals(mapOf("1234" to listOf("10", "20", "10")), tails)
            assertEquals(mapOf("6222-raw" to listOf("10")), identifiers)
            assertEquals("workspace", workspace)
            listOf(first, first.copy(ruleId = "later"))
        }.discover(preview, seeds + "missing", accounts, "workspace") {
            listOf("card_tail" to "10", "card_tail" to "20", "card_tail" to "10", "ignored" to "10").map { (type, id) ->
                mapOf("alias_type" to type, "alias_value" to "1234", "account_id" to id)
            } + mapOf("alias_type" to "account_identifier", "alias_value" to "6222-raw", "account_id" to "10")
        }
        assertEquals(1, calls)
        assertEquals(listOf(first), result)
    }

    @Test fun activeGuardRunsBeforeAnyPreviewReadAndProgramErrorsPropagate() {
        val (preview, _) = preview()
        val error = IllegalStateException("inactive")
        assertSame(error, assertFailsWith<IllegalStateException> {
            rules(active = { throw error }).discover(preview, preview.components.keys, emptyMap(), "w") { error("aliases") }
        })
        assertFailsWith<NoSuchElementException> {
            rules().discover(preview, preview.components.keys, emptyMap(), "w") { emptyList() }
        }
    }
}
