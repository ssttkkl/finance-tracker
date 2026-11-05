package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class CashEntryReconciliationRulesTest {
    private val time = Instant.parse("2026-01-01T00:00:00Z")
    private val values = CashProjectionValues<Long, Instant>({ it > 0 }, { true }, { it })
    private fun publicValues(note: String) = buildJsonObject {
        put("category_id", JsonNull); put("note", note); put("counterparty", "merchant")
        put("counterparty_account", ""); put("counterparty_account_attrs", JsonArray(emptyList()))
    }
    private fun fact(id: Long) = CashProjectionFact.create(values, id, 1L, time, "-5", "CNY", "merchant", null,
        "source", "fixture", "record-$id", recordType = "consumption", recordSubtype = "not_applicable")
    private fun projections(vararg ids: Long, mirror: Boolean = false): List<CashProjection<Long, Instant>> {
        val relations = if (mirror) listOf(CashProjectionRelation.create(values, 10L, "payment_mirror", ids[0], ids[1])) else emptyList()
        return CashProjectionRules(values, Instant::toString).build(ids.map(::fact), relations).projections
    }

    @Test fun singletonRetainsExplicitEmptySourceValueAndRegistersTheSameFreshMember() {
        val registered = mutableListOf<CashEntryMember<Long>>()
        val rules = CashEntryReconciliationRules<Long, Long>({ it }, { it }, { "entry-new" }, { registered += it })
        val result = rules.reconcile(projections(1), emptyMap(), emptyMap(),
            mapOf(1L to CashEntrySource(publicValues(""), setOf("note"))))
        val entry = result.entriesByProjection.values.single()
        assertEquals("entry-new", entry.id)
        assertEquals(1L, entry.rootTransactionId)
        assertEquals(1L, entry.identityTransactionId)
        assertEquals(1L, entry.revision)
        assertEquals(publicValues(""), entry.values)
        assertEquals(listOf("note"), entry.manualFields)
        assertSame(entry, result.newEntries.single())
        assertSame(registered.single(), result.newMembers.single())
        assertEquals(publicValues(""), registered.single().standaloneValues)
        assertEquals(listOf("note"), registered.single().standaloneManualFields)
    }

    @Test fun unchangedGroupPreservesObjectIdentityManualPublicAndIndependentValues() {
        val entry = CashEntry("old", 1L, 1L, 7L, publicValues("kept"), listOf("note"), null)
        val member = CashEntryMember(1L, "old", publicValues("independent"), listOf("note"))
        val rules = CashEntryReconciliationRules<Long, Long>({ it }, { it }, { error("unexpected entry allocation") },
            { error("unexpected member registration") })
        val result = rules.reconcile(projections(1), mapOf(1L to member), mapOf("old" to entry),
            mapOf(1L to CashEntrySource(publicValues("automatic"), emptySet())))
        assertSame(entry, result.entriesByProjection.values.single())
        assertEquals(7L, entry.revision)
        assertEquals(publicValues("kept"), entry.values)
        assertEquals(publicValues("independent"), member.standaloneValues)
        assertEquals(listOf("note"), member.standaloneManualFields)
        assertTrue(result.newEntries.isEmpty())
        assertTrue(result.newMembers.isEmpty())
    }

    @Test fun incompatibleManualValuesRejectTheGroupBeforeChangingItsExistingObjects() {
        val first = CashEntry("first", 1L, 1L, 7L, publicValues("one"), listOf("note"), null)
        val second = CashEntry("second", 2L, 2L, 9L, publicValues("two"), listOf("note"), null)
        val before = listOf(first.copy(), second.copy())
        val members = listOf(1L to "first", 2L to "second").associate { (id, entry) ->
            id to CashEntryMember(id, entry, publicValues("standalone"), emptyList())
        }
        val rules = CashEntryReconciliationRules<Long, Long>({ it }, { it }, { error("unexpected allocation") },
            { error("unexpected registration") })
        val failure = assertFailsWith<CashPolicyError> {
            rules.reconcile(projections(1, 2, mirror = true), members, mapOf("first" to first, "second" to second),
                listOf(1L, 2L).associateWith { CashEntrySource(publicValues("source"), emptySet()) })
        }
        assertEquals("common_field_conflict", failure.code)
        assertEquals(listOf("note"), failure.fields)
        assertEquals(before, listOf(first, second))
        assertEquals(listOf("first", "second"), members.values.map { it.entryId })
    }

    @Test fun splitKeepsIdentityAnchorAndPreservesAllThreeInheritanceStates() {
        for (inherit in listOf(false, true, null)) {
            val entry = CashEntry("old", 1L, 2L, 4L, publicValues("public"), listOf("note"), inherit)
            val members = (1L..2L).associateWith { CashEntryMember(it, "old", publicValues("independent-$it"), listOf("note")) }
            val sources = (1L..2L).associateWith { CashEntrySource(publicValues("automatic"), emptySet()) }
            val registered = mutableListOf<CashEntryMember<Long>>()
            val result = CashEntryReconciliationRules<Long, Long>({ it }, { it }, { "fresh" }, { registered += it })
                .reconcile(projections(1, 2).reversed(), members, mapOf("old" to entry), sources)
            assertSame(entry, result.entriesByProjection.getValue("cash:2"))
            assertEquals(2L, entry.identityTransactionId)
            assertEquals("fresh", result.entriesByProjection.getValue("cash:1").id)
            for (id in 1L..2L) {
                val expectedPublic = if (inherit == false) "independent-$id" else "public"
                assertEquals(publicValues(expectedPublic), result.entriesByProjection.getValue("cash:$id").values)
                assertEquals(publicValues(if (inherit == true) "public" else "independent-$id"), members.getValue(id).standaloneValues)
            }
            assertEquals(inherit, entry.splitInherit)
            assertTrue(registered.isEmpty())
        }
    }

    @Test fun reconciliationOnlyUsesTheSelectedMembersOfAnEntry() {
        val entry = CashEntry("old", 1L, 2L, 4L, publicValues("public"), listOf("note"), false)
        val member = CashEntryMember(1L, "old", publicValues("independent"), listOf("note"))
        val result = CashEntryReconciliationRules<Long, Long>({ it }, { it }, { error("unexpected allocation") },
            { error("unexpected registration") }).reconcile(projections(1), mapOf(1L to member), mapOf("old" to entry),
            mapOf(1L to CashEntrySource(publicValues("automatic"), emptySet())))
        assertSame(entry, result.entriesByProjection.values.single())
        assertEquals(1L, entry.identityTransactionId)
        assertEquals(4L, entry.revision)
        assertEquals(publicValues("public"), entry.values)
        assertEquals(publicValues("independent"), member.standaloneValues)
    }

    @Test fun membershipAndPublicValuesEachAdvanceTheExistingRevision() {
        val entry = CashEntry("old", 1L, 1L, 7L, publicValues("old"), emptyList(), null)
        val member = CashEntryMember(1L, "old", publicValues("old"), emptyList())
        val result = CashEntryReconciliationRules<Long, Long>({ it }, { it }, { error("unexpected allocation") }, {})
            .reconcile(projections(1, 2, mirror = true), mapOf(1L to member), mapOf("old" to entry),
                (1L..2L).associateWith { CashEntrySource(publicValues("automatic"), emptySet()) })
        assertSame(entry, result.entriesByProjection.values.single())
        assertEquals(9L, entry.revision)
        assertEquals(publicValues("automatic"), entry.values)
        assertEquals(2L, result.newMembers.single().transactionId)
    }

    @Test fun revisionOverflowPropagatesAtBothOriginalIncrementPoints() {
        for (changeMembership in listOf(false, true)) {
            val entry = CashEntry("old", 1L, 1L, Long.MAX_VALUE, publicValues("old"), emptyList(), null)
            val member = CashEntryMember(1L, "old", publicValues("old"), emptyList())
            assertFailsWith<ArithmeticException> {
                CashEntryReconciliationRules<Long, Long>({ it }, { it }, { error("unexpected allocation") },
                    { error("unexpected registration") }).reconcile(
                    if (changeMembership) projections(1, 2, mirror = true) else projections(1),
                    mapOf(1L to member), mapOf("old" to entry),
                    (1L..2L).associateWith { CashEntrySource(publicValues("automatic"), emptySet()) })
            }
            assertEquals(Long.MAX_VALUE, entry.revision)
            assertEquals(publicValues("old"), entry.values)
            assertEquals(publicValues(if (changeMembership) "old" else "automatic"), member.standaloneValues)
        }
    }

    @Test fun freshMemberRegistrationSurvivesALaterGroupConflictWithoutExpandingTheOldIndex() {
        val first = CashEntry("first", 2L, 2L, 7L, publicValues("one"), listOf("note"), null)
        val second = CashEntry("second", 3L, 3L, 9L, publicValues("two"), listOf("note"), null)
        val members = (2L..3L).associateWith { CashEntryMember(it, if (it == 2L) "first" else "second", publicValues("old"), emptyList()) }
        val registered = linkedMapOf<Long, CashEntryMember<Long>>()
        val built = projections(1) + projections(2, 3, mirror = true)
        // Existing anchors are processed first, so use an unrelated retired anchor for the conflict group.
        first.identityTransactionId = 20L; second.identityTransactionId = 30L
        val failure = assertFailsWith<CashPolicyError> {
            CashEntryReconciliationRules<Long, Long>({ it }, { it }, { "fresh" }, { registered[it.transactionId] = it })
                .reconcile(built.reversed(), members, mapOf("first" to first, "second" to second),
                    (1L..3L).associateWith { CashEntrySource(publicValues("source"), emptySet()) })
        }
        assertEquals("common_field_conflict", failure.code)
        assertEquals(setOf(1L), registered.keys)
        assertEquals("fresh", registered.getValue(1L).entryId)
        assertEquals(setOf(2L, 3L), members.keys)
        assertEquals(listOf("first", "second"), members.values.map { it.entryId })
        assertEquals(7L, first.revision); assertEquals(9L, second.revision)
    }

    @Test fun candidateTieUsesOriginalUtf16EntryOrderingAndTheAnchorUsesNumericIdOrdering() {
        val supplementary = CashEntry("𐀀", 10L, 10L, 1L, publicValues("same"), emptyList(), null)
        val bmp = CashEntry("\uE000", 2L, 2L, 1L, publicValues("same"), emptyList(), null)
        val members = mapOf(10L to CashEntryMember(10L, supplementary.id, publicValues("same"), emptyList()),
            2L to CashEntryMember(2L, bmp.id, publicValues("same"), emptyList()))
        val result = CashEntryReconciliationRules<Long, Long>({ it }, { it }, { error("unexpected allocation") }, {})
            .reconcile(projections(10, 2, mirror = true), members, mapOf(supplementary.id to supplementary, bmp.id to bmp),
                listOf(10L, 2L).associateWith { CashEntrySource(publicValues("same"), emptySet()) })
        assertSame(supplementary, result.entriesByProjection.values.single())
        val fresh = CashEntryReconciliationRules<Long, Long>({ it }, { it }, { "fresh" }, {})
            .reconcile(projections(10, 2, mirror = true), emptyMap(), emptyMap(),
                listOf(10L, 2L).associateWith { CashEntrySource(publicValues("same"), emptySet()) })
        assertEquals(2L, fresh.newEntries.single().identityTransactionId)
    }

    @Test fun virtualIdentifiersStayExactBeyondLongAndJavascriptNumberRanges() {
        val id = CashImportPreviewInteger.parse("92233720368547758081234567890")
        val interpretation = CashProjectionValues<CashImportPreviewInteger, Instant>({ it.positive }, { true }, { it })
        val fact = CashProjectionFact.create(interpretation, id, 1L, time, "-5", "CNY", "merchant", null,
            "source", "fixture", "record", recordType = "consumption", recordSubtype = "not_applicable")
        val built = CashProjectionRules(interpretation, Instant::toString).build(listOf(fact), emptyList()).projections
        val result = CashEntryReconciliationRules<CashImportPreviewInteger, CashImportPreviewInteger>({ it }, { it }, { "virtual" }, {})
            .reconcile(built, emptyMap(), emptyMap(), mapOf(id to CashEntrySource(publicValues(""), emptySet())))
        assertEquals(id, result.newEntries.single().rootTransactionId)
        assertEquals(id, result.newEntries.single().identityTransactionId)
        assertEquals(id, result.newMembers.single().transactionId)
        assertEquals("92233720368547758081234567890", result.newMembers.single().transactionId.toString())
    }

}
