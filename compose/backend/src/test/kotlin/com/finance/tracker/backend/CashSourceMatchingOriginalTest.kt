package com.finance.tracker.backend

import kotlin.test.*

class CashSourceMatchingOriginalTest {
    private fun coverage() = CashSourceCoverage("2026-08-01T00:00:00Z", "2026-09-01T00:00:00Z", true,
        "statement-declared-count-and-range", emptyMap(), "source-coverage-v1")
    private fun unknownCoverage() = CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1")
    private fun row(position: Int = 1, name: String = "甲", providerId: String? = null,
        stableEvidence: String? = null, fields: Map<String, Any?> = emptyMap()) = SourceOccurrence(
        "fixture-bank", "owner-full-account", "a".repeat(64), "fixture-v1", "sheet-1", position,
        mapOf("amount" to "-20.00", "currency" to "CNY", "account_id" to "101",
            "occurred_at" to "2026-08-15T00:00:00Z", "counterparty" to name, "note" to "",
            "category_id" to null, "record_type" to "consumption", "record_subtype" to "not_applicable",
            "source_payload" to mapOf("名称" to name)) + fields, coverage(), providerId, stableEvidence)
    private fun old(id: String = "1", name: String = "甲", providerId: String? = null,
        stableEvidence: String? = null, fields: Map<String, Any?> = emptyMap(),
        bindingKeys: Set<String> = emptySet(), protectedFields: Set<String> = emptySet(), deleted: Boolean = false) =
        row(name = name).let { SourceRecord(id, it.sourceType, it.sourceAccountKey,
            mapOf("state" to "posted") + it.fields + fields, listOf(it.coverage), bindingKeys,
            protectedFields, stableEvidence, providerId, deleted = deleted) }
    private fun resolve(rows: List<SourceOccurrence>, records: List<SourceRecord> = emptyList()) =
        CashSourceResolver.resolveSourceFile(rows, records)

    @Test fun test_first_import_preserves_identical_real_occurrences() {
        val results = resolve(listOf(row(1), row(2)))
        assertEquals(listOf("new", "new"), results.map { it.action })
        assertNotEquals(results[0].occurrenceKey, results[1].occurrenceKey)
    }

    @Test fun test_unchanged_complete_multiset_is_duplicate_without_arbitrary_bindings() {
        val results = resolve(listOf(row(1, "乙"), row(2, "甲")), listOf(old("1", "甲"), old("2", "乙")))
        assertEquals(listOf("duplicate", "duplicate"), results.map { it.action })
        assertTrue(results.all { it.transactionId == null && !it.bind })
    }

    @Test fun test_unique_stable_group_updates_name_without_identity_question() {
        val result = resolve(listOf(row(name = "新名称")), listOf(old())).single()
        assertTrue(result.action == "match" && result.transactionId == "1" && result.bind)
    }

    @Test fun test_mutable_crossed_names_cannot_manufacture_a_unique_remainder() {
        val results = resolve(listOf(row(1, "乙"), row(2, "丙")), listOf(old("1", "甲"), old("2", "乙")))
        assertTrue(results.all { it.action == "pending" && it.candidateIds == listOf("1", "2") })
    }

    @Test fun test_uniform_true_multiplicity_increase_adds_only_the_excess() {
        val results = resolve(listOf(row(1), row(2), row(3)), listOf(old("1"), old("2")))
        assertEquals(listOf("duplicate", "duplicate", "new"), results.map { it.action })
        assertTrue(results.take(2).all { !it.bind })
    }

    @Test fun test_nonuniform_multiplicity_increase_does_not_guess_which_is_new() {
        val results = resolve(listOf(row(1, "甲"), row(2, "乙"), row(3, "丙")), listOf(old("1", "甲"), old("2", "乙")))
        assertTrue(results.all { it.action == "pending" })
    }

    @Test fun test_unique_candidate_is_insufficient_without_comparable_coverage() {
        val variants = mapOf("unknown" to unknownCoverage(), "filter" to coverage().copy(filters = mapOf("merchant" to "乙")),
            "bounds" to coverage().copy(startInclusive = false, start = "2026-08-15T00:00:00Z"),
            "precision" to coverage().copy(timePrecision = "day"))
        for ((difference, value) in variants) {
            val result = resolve(listOf(row(name = "乙").copy(coverage = value)), listOf(old())).single()
            assertTrue(result.action == "pending" && !result.bind, difference)
        }
    }

    @Test fun test_unknown_range_without_old_collision_is_normally_new() {
        assertEquals("new", resolve(listOf(row().copy(coverage = unknownCoverage())))[0].action)
    }

    @Test fun test_overlapping_declared_ranges_can_match_in_their_shared_interval() {
        val previous = old().copy(coverages = listOf(coverage().copy(start = "2026-08-10T00:00:00Z", end = "2026-10-01T00:00:00Z")))
        assertEquals("duplicate", resolve(listOf(row()), listOf(previous))[0].action)
    }

    @Test fun test_full_account_identity_isolation_ignores_same_tail_and_internal_mapping() {
        assertEquals("new", resolve(listOf(row()), listOf(old().copy(sourceAccountKey = "different-owner-same-tail")))[0].action)
    }

    @Test fun test_unknown_source_owner_never_claims_cross_file_identity() {
        val result = resolve(listOf(row().copy(sourceAccountKey = null)), listOf(old().copy(sourceAccountKey = null))).single()
        assertTrue(result.action == "pending" && !result.bind)
    }

    @Test fun test_unknown_source_owner_colliding_with_known_owner_is_pending_not_another_formal_record() {
        val result = resolve(listOf(row().copy(sourceAccountKey = null)), listOf(old())).single()
        assertTrue(result.action == "pending" && result.candidateIds == listOf("1") && !result.bind)
    }

    @Test fun test_same_file_binding_reuses_unknown_owner_draft() {
        val value = row().copy(sourceAccountKey = null)
        val previous = old().copy(sourceAccountKey = null, fields = mapOf("state" to "draft") + value.fields,
            bindingKeys = value.bindingKeys.toSet())
        val result = resolve(listOf(value), listOf(previous)).single()
        assertTrue(result.action == "duplicate" && result.transactionId == "1")
    }

    @Test fun test_parser_namespace_change_does_not_reuse_old_file_position() {
        val value = row().copy(sourceAccountKey = null, coverage = unknownCoverage())
        val previous = old().copy(sourceAccountKey = null, bindingKeys = value.bindingKeys.toSet())
        val result = resolve(listOf(value.copy(parserVersion = "fixture-v2")), listOf(previous)).single()
        assertTrue(result.action == "pending" && result.transactionId == null && !result.bind)
    }

    @Test fun test_verified_provider_id_matches_even_without_coverage_and_rejects_amount_change() {
        val value = row(providerId = "provider-123").copy(coverage = unknownCoverage())
        val previous = old().copy(bindingKeys = value.bindingKeys.toSet())
        assertEquals("1", resolve(listOf(value), listOf(previous))[0].transactionId)
        val changed = value.copy(fields = value.fields + mapOf("amount" to "-21", "counterparty" to "新"))
        val result = resolve(listOf(changed), listOf(previous)).single()
        assertTrue(result.action == "conflict" && result.reason == "fixed_field_conflict")
        assertFalse(result.bind)
    }

    @Test fun test_same_source_fingerprint_does_not_ignore_manual_mutable_changes() {
        assertEquals("match", resolve(listOf(row()), listOf(old(fields = mapOf("counterparty" to "用户曾修改"))))[0].action)
    }

    @Test fun test_manual_note_and_category_protection_in_identity_comparison() {
        val previous = old(fields = mapOf("note" to "", "category_id" to "user-cat"), protectedFields = setOf("note", "category_id"))
        val value = row().copy(fields = row().fields + ("note" to "来源备注"))
        assertEquals("duplicate", resolve(listOf(value), listOf(previous))[0].action)
    }

    @Test fun test_entire_set_is_not_duplicate_when_manual_mutable_fields_need_updates() {
        val results = resolve(listOf(row(1, "甲"), row(2, "乙")), listOf(
            old("1", "甲", fields = mapOf("counterparty" to "用户修改")), old("2", "乙")))
        assertTrue(results.all { it.action == "pending" })
    }

    @Test fun test_deleted_target_is_ignored_and_live_replacement_wins() {
        val value = row(providerId = "123")
        val deleted = old("1", deleted = true, bindingKeys = value.bindingKeys.toSet())
        assertEquals("new", resolve(listOf(value), listOf(deleted))[0].action)
        val replacement = old("2", bindingKeys = value.bindingKeys.toSet())
        assertEquals("2", resolve(listOf(value), listOf(deleted, replacement))[0].transactionId)
    }

    @Test fun test_conflicting_provider_occurrences_all_fail_instead_of_first_wins() {
        val results = resolve(listOf(row(1, "甲", providerId = "123"), row(2, "乙", providerId = "123")))
        assertTrue(results.all { it.action == "conflict" && it.reason == "batch_identity_conflict" })
    }

    @Test fun test_identical_provider_occurrences_are_batch_duplicates() {
        val rows = listOf(row(1, providerId = "123"), row(2, providerId = "123"))
        val results = resolve(rows)
        assertEquals(listOf("new", "batch_duplicate"), results.map { it.action })
        assertEquals(rows[0].occurrenceKey, results[1].duplicateOf)
    }

    @Test fun test_extra_occurrence_cannot_consume_an_already_file_bound_transaction() {
        val first = row(1)
        val results = resolve(listOf(first, row(2)), listOf(old(bindingKeys = first.bindingKeys.toSet())))
        assertEquals(listOf("duplicate", "new"), results.map { it.action })
    }

    @Test fun test_unknown_owner_extra_occurrence_stays_pending_after_known_match_consumed_candidate() {
        val first = row(1)
        val results = resolve(listOf(first, row(2).copy(sourceAccountKey = null)), listOf(old(bindingKeys = first.bindingKeys.toSet())))
        assertEquals(listOf("duplicate", "pending"), results.map { it.action })
        assertFalse(results[1].bind)
    }

    @Test fun test_unknown_old_owner_cannot_prove_extra_occurrences_after_a_file_alias_match() {
        val first = row(1)
        val previous = old(bindingKeys = first.bindingKeys.toSet()).copy(sourceAccountKey = null)
        val results = resolve(listOf(first, row(2)), listOf(previous))
        assertEquals(listOf("duplicate", "pending"), results.map { it.action })
    }

    @Test fun test_verified_row_evidence_allows_unknown_range_but_names_never_do() {
        val value = row(name = "新名称", stableEvidence = "certified-bank-sequence:123").copy(coverage = unknownCoverage())
        assertEquals("match", resolve(listOf(value), listOf(old(stableEvidence = "certified-bank-sequence:123")))[0].action)
    }

    @Test fun test_verified_evidence_disambiguates_colliding_financial_indexes_as_a_group() {
        val rows = listOf(1 to "新甲", 2 to "新乙").map { (i, name) ->
            row(i, name, stableEvidence = "verified:$i").copy(coverage = unknownCoverage()) }
        val records = listOf(1 to "甲", 2 to "乙").map { (i, name) -> old(i.toString(), name, stableEvidence = "verified:$i") }
        val results = resolve(rows, records)
        assertEquals(listOf("1", "2"), results.map { it.transactionId })
        assertTrue(results.all { it.action == "match" })
    }

    @Test fun test_verified_row_evidence_detects_a_fixed_field_conflict() {
        val result = resolve(listOf(row(stableEvidence = "verified:1", fields = mapOf("amount" to "-21"))),
            listOf(old(stableEvidence = "verified:1"))).single()
        assertEquals("conflict", result.action)
    }

    @Test fun test_distinct_reliable_provider_ids_are_distinct_despite_financial_collision() {
        assertEquals("new", resolve(listOf(row(providerId = "new-id")), listOf(old(providerId = "old-id")))[0].action)
    }

    @Test fun test_known_provider_mismatch_cannot_be_hidden_by_anonymous_candidates() {
        val result = resolve(listOf(row(providerId = "B")), listOf(old("1", "甲", providerId = "A"), old("2", "乙"))).single()
        assertTrue(result.action == "match" && result.transactionId == "2")
    }

    @Test fun test_different_verified_row_evidence_excludes_a_weak_candidate() {
        assertEquals("new", resolve(listOf(row(stableEvidence = "verified:B")), listOf(old(stableEvidence = "verified:A")))[0].action)
    }

    @Test fun test_file_alias_does_not_allow_changing_a_known_source_account() {
        val value = row()
        val previous = old(bindingKeys = value.bindingKeys.toSet())
        val result = resolve(listOf(value.copy(sourceAccountKey = "another-known-owner")), listOf(previous)).single()
        assertTrue(result.action == "conflict" && result.reason == "source_identity_conflict")
    }

    @Test fun test_one_file_is_required_so_occurrences_are_consumed_per_export() {
        assertContains(assertFailsWith<IllegalArgumentException> {
            resolve(listOf(row(1), row(2).copy(fileDigest = "b".repeat(64))))
        }.message.orEmpty(), "one_source_file_required")
    }

    @Test fun test_scope_and_identity_keys_are_canonical_and_do_not_expose_accounts() {
        val value = row(providerId = "provider-secret-id")
        for (key in value.bindingKeys) assertTrue("owner-full-account" !in key && "provider-secret-id" !in key)
        val changed = value.copy(sourceAccountKey = "other-owner")
        assertNotEquals(value.providerBindingKey, changed.providerBindingKey)
        assertEquals(value.fileBindingKey, changed.fileBindingKey)
    }

    @Test fun test_missing_financial_index_uses_binding_only_not_empty_candidate_collision() {
        val value = row().copy(fields = row().fields + ("amount" to null))
        val previous = old(fields = mapOf("amount" to null, "state" to "draft"))
        assertEquals("new", resolve(listOf(value), listOf(previous))[0].action)
    }

    @Test fun test_invalid_business_field_isolated_from_other_occurrences() {
        val results = resolve(listOf(row(1, fields = mapOf("currency" to emptyList<String>())), row(2)))
        assertEquals(listOf("conflict", "new"), results.map { it.action })
        assertTrue(results[0].reason == "invalid_field" && !results[0].bind)
    }

    @Test fun test_legacy_posted_record_without_provable_owner_blocks_automatic_duplicate_creation() {
        val result = resolve(listOf(row()), listOf(old().copy(sourceAccountKey = null))).single()
        assertTrue(result.action == "pending" && result.candidateIds == listOf("1"))
    }

    @Test fun test_conflicting_verified_evidence_occurrences_do_not_partially_update_a_target() {
        val rows = listOf(row(1, "甲", stableEvidence = "verified:1"), row(2, "乙", stableEvidence = "verified:1"))
        assertTrue(resolve(rows, listOf(old(stableEvidence = "verified:1"))).all { it.action == "conflict" })
    }

    @Test fun test_result_equivalent_uniform_updates_are_automatic() {
        for (count in listOf(2, 3)) {
            val rows = (0 until count).map { row(it, "共同新名称") }
            val results = resolve(rows, listOf(old("1", "甲"), old("2", "乙")))
            assertEquals(listOf("match", "match") + if (count == 3) listOf("new") else emptyList(), results.map { it.action })
            assertEquals(setOf("1", "2"), results.filter { it.action == "match" }.map { it.transactionId }.toSet())
        }
    }

    @Test fun test_synthetic_identity_is_allocated_once_and_never_a_mutable_content_hash() {
        val value = row()
        fun allocate(value: SourceOccurrence) = CashSourceResolver.allocateSourceRecordId(value)
        assertNotEquals(allocate(value), allocate(value))
        val provider = value.copy(providerId = "provider-1")
        assertEquals(allocate(provider), allocate(provider.copy(fileDigest = "b".repeat(64),
            fields = provider.fields + ("counterparty" to "新名称"))))
        assertNotEquals(allocate(provider), allocate(provider.copy(sourceAccountKey = "other-owner")))
    }

    @Test fun test_all_certified_identities_participate_in_batch_conflict_group() {
        for (secondProvider in listOf(null, "provider-B")) {
            val rows = listOf(row(1, "甲", providerId = "provider-A", stableEvidence = "verified:S"),
                row(2, "乙", providerId = secondProvider, stableEvidence = "verified:S"))
            assertTrue(resolve(rows, listOf(old(stableEvidence = "verified:S"))).all { it.action == "conflict" })
        }
    }

    @Test fun test_batch_identity_conflict_propagates_through_shared_certified_keys() {
        val rows = listOf(row(1, "甲", providerId = "provider-A"),
            row(2, "甲", providerId = "provider-A", stableEvidence = "verified:S"),
            row(3, "乙", providerId = "provider-B", stableEvidence = "verified:S"))
        assertTrue(resolve(rows).all { it.action == "conflict" })
    }

    @Test fun test_known_provider_and_evidence_aliases_join_batch_conflict_scope() {
        val provider = row(1, "甲", providerId = "provider-A")
        val evidence = row(2, "乙", stableEvidence = "verified:S")
        val previous = old(providerId = "provider-A", stableEvidence = "verified:S")
        assertTrue(resolve(listOf(provider, evidence), listOf(previous)).all { it.action == "conflict" })
    }

    @Test fun test_unchanged_assignment_respects_different_manual_protection_without_greedy_binding() {
        val rows = listOf(row(1, fields = mapOf("note" to "甲")), row(2, fields = mapOf("note" to "乙")))
        val records = listOf(old("1", protectedFields = setOf("note")), old("2", fields = mapOf("note" to "甲")))
        assertTrue(resolve(rows, records).all { it.action == "duplicate" && !it.bind })
    }

    @Test fun test_failed_direct_update_does_not_consume_a_live_candidate_for_other_rows() {
        val first = row(1, providerId = "provider-1", fields = mapOf("amount" to "-21"))
        val results = resolve(listOf(first, row(2)), listOf(old(providerId = "provider-1")))
        assertEquals(listOf("conflict", "duplicate"), results.map { it.action })
        assertEquals("1", results[1].transactionId)
    }
}
