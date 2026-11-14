package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class LocalCashImportSourceWritesTest {
    private val empty = JsonObject(emptyMap())
    private val array = JsonArray(emptyList())
    private val time = "2026-08-15T02:00:00Z"
    private fun snapshot() = LedgerSnapshotDto("w", "9007199254740993", CASH_RULES_VERSION, LedgerFactsDto(
        listOf(LedgerAccountFactDto("10", "Cash", "cash", true, JsonArray(listOf(JsonPrimitive("CNY"))), "9007199254740993")),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0"))
    private fun files(source: String = "alipay") = listOf(ParsedImportFile("f", "f".repeat(64), "parsed", ParsedCashDocument(source, "csv", "p",
        listOf(ParsedCashRow(1, mapOf("amount" to "-12.50", "currency" to "CNY", "occurred_at" to time,
            "record_type" to "consumption", "record_subtype" to "not_applicable", "counterparty" to "Party", "note" to "Source",
            "source_payload" to mapOf("order" to "0001")), paymentMethod = if (source == "wechat") "零钱" else "支付宝余额",
            providerId = if (source == "ccb_debit") null else "0001",
            sourceExplanation = if (source == "ccb_debit") mapOf("source_display_name" to "Bank") else emptyMap())), "6222", "Account",
        CashSourceCoverage(null, null, false, "", emptyMap(), "v"), 1, 1, "Nickname")))
    private fun choice(action: String, ref: String = "f:r1", patch: JsonObject = empty) = buildJsonArray {
        add(buildJsonObject { put("row_ref", ref); put("action", action); put("patch", patch) })
    }
    private fun calculate(source: LedgerSnapshotDto, files: List<ParsedImportFile>, choices: JsonArray = array): Pair<PreparedCashImportOperations, JsonObject> {
        val selections = JsonArray(scanLocalCashFunding(files).groups.map { group -> buildJsonObject {
            put("source_account_ref", group.sourceAccountRef); put("account_id", "10"); put("expected_account_revision", 9007199254740993L)
        } })
        val operations = prepareLocalCashImportOperations(files, source, selections, choices, wireValues = false)
        val prepared = LocalCashImportPreviewPreparation(source) {}.prepareOperations(operations.operations)
        val state = buildLocalCashImportPreviewSnapshot(source, prepared.context, prepared.operations, operations.rows) { Instant.parse(time) }
        val plan = completeLocalCashImportPreviewPlan(source, prepared, state, emptyList(), files,
            prepareLocalCashImportInput(selections, choices, array), null, operations.candidateSets)
        return operations to freezeLocalCashImportPreviewPlan(plan)
    }
    private fun writes(source: LedgerSnapshotDto = snapshot(), files: List<ParsedImportFile> = files(), choices: JsonArray = array): CashWriteSetDto {
        val (prepared, plan) = calculate(source, files, choices)
        return buildLocalCashImportSourceWrites(source, files, prepared.source, plan, choices)
    }
    private fun coverage(value: CashSourceCoverage) = encodeLocalCashValue(cashSourceCoverageFields(value) +
        ("page_total" to value.pageTotal?.let(::SourceJsonInteger)))
    private fun saved(state: String = "posted", issues: List<LedgerCashIssueFactDto> = emptyList(), inputFiles: List<ParsedImportFile> = files()): LedgerSnapshotDto {
        val source = snapshot(); val (prepared, plan) = calculate(source, inputFiles)
        val row = buildLocalCashImportTransactionWrites(source, plan).single(); val v = row.values
        val tx = LedgerCashTransactionFactDto("1", v.accountId, state, v.recordOrigin, v.manualTransferId, "9007199254740995", v.cashGranularity,
            v.sourceType, v.recordId, v.sourcePayload, v.relationMetadata, v.sourceFingerprint, v.manualOverrides, v.occurredAt, v.amount, v.currency,
            v.counterparty, v.counterpartyAccount, v.counterpartyAccountAttrs, v.note, v.categoryId, v.recordType, v.recordSubtype, time, null, "", "")
        val parts = row.components.mapIndexed { i, p -> LedgerCashComponentFactDto("${i + 11}", "1", p.accountId, p.amount, p.currency,
            p.ordinal, p.label, p.sourceKey, p.metadataJson, time) }
        val occurrence = prepared.source.rows.rows.values.single()
        val bindings = occurrence.bindingSpecs.mapIndexed { i, key -> LedgerSourceBindingFactDto("${i + 20}", "1", key.sourceType,
            key.scopeKind, key.scopeKey, key.identityKind, key.identityKey, key.identityStrength, null, occurrence.parserVersion,
            coverage(occurrence.coverage), "9007199254740997", time, time) }
        return source.copy(facts = source.facts.copy(cashTransactions = listOf(tx), cashTransactionComponents = parts,
            cashSourceBindings = bindings, cashTransactionIssues = issues))
    }
    private fun oldIssue(code: String, key: String = "import:old") = LedgerCashIssueFactDto("40", "1", key, code, null,
        JsonNull, empty, empty, "9007199254740999", time, time)

    @Test fun realPreviewCombinesTypedSourceWritesWithoutAddingInputProofs() {
        val source = snapshot(); val files = files(); val (prepared, plan) = calculate(source, files)
        val before = plan.toString(); val result = buildLocalCashImportSourceWrites(source, files, prepared.source, plan)
        val row = result.transactions.single(); val occurrence = prepared.source.rows.rows.values.single()
        assertEquals(occurrence.bindingSpecs.size, result.sourceBindings.size)
        for ((binding, key) in result.sourceBindings.zip(occurrence.bindingSpecs)) {
            assertEquals(row.target.reference, binding.transaction)
            assertEquals(key.identityKey, binding.identityKey); assertEquals(key.identityStrength, binding.identityStrength)
            assertTrue(binding.target.reference is NewCashWriteReferenceDto); assertNull(binding.target.expectedRevision)
        }
        val mapping = result.statementAccountMappings.single()
        assertEquals(prepared.scan.groups.single().sourceAccountRef, mapping.sourceAccountKey)
        assertEquals("funding_group_v1", mapping.identityKind); assertEquals("10", mapping.accountId)
        assertTrue(result.issues.isEmpty()); assertTrue(result.entries.isEmpty()); assertTrue(result.relations.isEmpty())
        prepareCashImportWriteSetSourceMetadata(result, source.facts); prepareCashImportWriteSetMappings(result, source.facts)
        assertEquals(before, plan.toString()); assertTrue(source.facts.cashTransactions.isEmpty())
        val text = Json { encodeDefaults = true; explicitNulls = true }.encodeToString(CashWriteSetDto.serializer(), result)
        assertFalse("account_mapping_rows" in text); assertFalse("account_mapping_groups" in text)
    }

    @Test fun exactKeepReimportPreservesTheWholeOldIssueAndDoesNotRepublishDefaults() {
        val issue = oldIssue("posting_incomplete").copy(origin = buildJsonObject { put("table_key", "kept") }, pendingFields = buildJsonObject { put("kept", true) })
        val source = saved("draft", listOf(issue)); val result = writes(source, choices = choice("keep_existing"))
        assertEquals(1, result.transactions.size)
        assertTrue(result.sourceBindings.isEmpty()); assertTrue(result.issues.isEmpty()); assertTrue(result.resolvedIssues.isEmpty())
        assertTrue(result.statementAccountMappings.isEmpty()); assertEquals(issue, source.facts.cashTransactionIssues.single())
    }

    @Test fun missingAliasUsesKnownNaturalIdentityAndRetainsNonemptyStableEvidence() {
        val source = saved().let { it.copy(facts = it.facts.copy(cashSourceBindings = it.facts.cashSourceBindings.map { binding ->
            if (binding.sourceScopeKind == "account") binding.copy(stableEvidenceKey = "e".repeat(64)) else binding
        })) }
        val files = files().map { it.copy(fileRef = "alias", digest = "a".repeat(64)) }
        val result = writes(source, files, choice("keep_existing", "alias:r1"))
        val provider = result.sourceBindings.single { it.identityKind == "provider_id" }
        val old = source.facts.cashSourceBindings.single { it.identityKind == "provider_id" }
        assertEquals(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(old.id), old.revision), provider.target)
        assertEquals("e".repeat(64), provider.stableEvidenceKey)
        assertEquals(2, result.sourceBindings.count { it.target.reference is NewCashWriteReferenceDto })
        prepareCashImportWriteSetSourceMetadata(result, source.facts)
    }

    @Test fun draftIssueIsCreatedAndExplicitlyRetiredOnFinalization() {
        val created = writes(choices = choice("defer"))
        val issue = created.issues.single()
        assertEquals("posting_incomplete", issue.code)
        assertEquals("import:3973751b7a0b94281518d4bcca60aee52c7d2a5498db1dad964fae3da5137e35", issue.issueKey)
        assertEquals(created.transactions.single().target, issue.transaction)
        assertEquals(1, created.statementAccountMappings.size)
        prepareCashImportWriteSetSourceMetadata(created, snapshot().facts)
        val old = oldIssue("posting_incomplete"); val source = saved("draft", listOf(old))
        val finalized = writes(source)
        assertEquals("posted", finalized.transactions.single().values.state)
        assertTrue(finalized.issues.isEmpty())
        assertEquals(listOf(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("40"), old.revision)), finalized.resolvedIssues)
        prepareCashImportWriteSetSourceMetadata(finalized, source.facts)
    }

    @Test fun postedDeferKeepsExplicitNullPendingPayloadAndOriginalExplanationPresence() {
        val source = saved(); val file = files().single(); val doc = file.document!!
        val changed = listOf(file.copy(digest = "a".repeat(64), document = doc.copy(rows = doc.rows.map { row ->
            row.copy(fields = row.fields!! + mapOf("source_payload" to null, "note" to "Incoming"))
        })))
        val result = writes(source, changed, choice("defer"))
        val issue = result.issues.single()
        assertEquals("source_update_pending", issue.code)
        assertEquals("import:ee7e77c6f75cbd3600475611e5a4f7efe480b1dd3902fd5485967c0b04ff37ee", issue.issueKey)
        assertEquals(JsonNull, issue.pendingSourcePayload)
        assertEquals(JsonPrimitive(true), issue.pendingFields.jsonObject["_source_payload_present"])
        assertEquals(empty, issue.pendingFields.jsonObject["_source_explanation"])
        assertEquals(source.facts.cashTransactions.single().sourcePayload, result.transactions.single().values.sourcePayload)
        assertTrue(result.statementAccountMappings.isEmpty())
        prepareCashImportWriteSetSourceMetadata(result, source.facts)
    }

    @Test fun deferredExplanationOnlyChangeKeepsTheExactOldMetadata() {
        val source = saved(); val original = files().single(); val document = original.document!!
        val incoming = listOf(original.copy(document = document.copy(rows = document.rows.map {
            it.copy(sourceExplanation = mapOf("summary" to "new explanation"))
        })))
        val (prepared, plan) = calculate(source, incoming, choice("defer"))
        assertEquals(JsonPrimitive("duplicate"), plan.getValue("operations").jsonArray.single().jsonObject["planned_action"])
        val result = buildLocalCashImportSourceWrites(source, incoming, prepared.source, plan, choice("defer"))
        assertEquals(source.facts.cashTransactions.single().relationMetadata, result.transactions.single().values.relationMetadata)
        assertTrue(result.sourceBindings.isEmpty()); assertTrue(result.issues.isEmpty()); assertTrue(result.statementAccountMappings.isEmpty())
    }

    @Test fun storedJsonScalarsDoNotCreateAPendingSourceChange() {
        val original = files().single(); val document = original.document!!
        val input = listOf(original.copy(document = document.copy(rows = document.rows.map { it.copy(fields = it.fields!! +
            ("source_payload" to Json.parseToJsonElement("""{"n":9007199254740993,"d":1.2300,"e":1E+003,"s":"0001","b":true,"a":["text",null]}"""))) })))
        val source = saved(inputFiles = input)
        val (prepared, plan) = calculate(source, input, choice("defer"))
        val operation = plan.getValue("operations").jsonArray.single().jsonObject
        assertEquals(empty, operation["pending_fields"])
        assertEquals(JsonPrimitive("duplicate"), operation["planned_action"])
        val result = buildLocalCashImportSourceWrites(source, input, prepared.source, plan, choice("defer"))
        assertTrue(result.issues.isEmpty()); assertTrue(result.sourceBindings.isEmpty())
    }

    @Test fun existingAmbiguityAndRelationIssuesAreRetainedWithoutRefreshingCandidates() {
        val ambiguity = oldIssue("source_identity_ambiguous")
        val relation = oldIssue("relation_endpoint_incompatible", "relation:kept").copy(id = "41")
        val base = saved("draft", listOf(ambiguity, relation))
        val candidates = listOf(LedgerIssueCandidateFactDto("60", ambiguity.id, "2", "1"))
        val source = base.copy(facts = base.facts.copy(cashTransactions = base.facts.cashTransactions +
            base.facts.cashTransactions.single().copy(id = "2", sourceType = "other"), cashIssueCandidates = candidates))
        val result = writes(source)
        assertTrue(result.issues.isEmpty()); assertTrue(result.resolvedIssues.isEmpty())
        assertEquals("draft", result.transactions.single().values.state)
        assertEquals(candidates, source.facts.cashIssueCandidates)
        prepareCashImportWriteSetSourceMetadata(result, source.facts)
    }

    @Test fun onlyTheExistingUsedSelectionOutputPublishesFutureDefaults() {
        val source = saved()
        assertEquals(1, writes(source).statementAccountMappings.size)
        assertTrue(writes(source, choices = choice("defer")).statementAccountMappings.isEmpty())
        assertTrue(writes(source, choices = choice("keep_existing")).statementAccountMappings.isEmpty())
        assertTrue(writes(choices = choice("apply", patch = buildJsonObject { put("account_id", "10") })).statementAccountMappings.isEmpty())
        assertTrue(writes(files = files("wechat")).statementAccountMappings.isEmpty())
        val file = files().single(); val document = file.document!!
        val unsupported = listOf(file.copy(document = document.copy(rows = document.rows.map { it.copy(fields = it.fields!! + ("currency" to "USD")) })))
        assertTrue(writes(files = unsupported).statementAccountMappings.isEmpty())
    }

    @Test fun existingMappingUsesItsActualIdentityAndRevision() {
        val group = scanLocalCashFunding(files()).groups.single().sourceAccountRef
        val old = LedgerStatementMappingFactDto("50", "alipay", "funding_group_v1", group, "10", "confirmed", "9007199254740999", time, time)
        val source = snapshot().let { it.copy(facts = it.facts.copy(statementAccountMappings = listOf(old))) }
        val mapping = writes(source).statementAccountMappings.single()
        assertEquals(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("50"), old.revision), mapping.target)
        assertEquals(old.accountId, mapping.accountId)
    }

    @Test fun actualAmbiguousPreparationKeepsEveryCandidateRevisionWithoutNarrowing() {
        val old = saved(); val parent = old.facts.cashTransactions.single()
        val parents = listOf(parent.copy(sourceType = "ccb_debit", recordId = "legacy-1"),
            parent.copy(id = "2", sourceType = "ccb_debit", recordId = "legacy-2", rowRevision = "9223372036854775807"))
        val parts = old.facts.cashTransactionComponents + old.facts.cashTransactionComponents.map { it.copy(id = "12", cashTransactionId = "2") }
        val source = old.copy(facts = old.facts.copy(cashTransactions = parents, cashTransactionComponents = parts, cashSourceBindings = emptyList()))
        val result = writes(source, files("ccb_debit"))
        val issue = result.issues.single()
        assertEquals("source_identity_ambiguous", issue.code)
        assertEquals("import:bebfd30b43411e18d27e4f9720ffeed74b80e72f464ec3bd093cf56b12c1727f", issue.issueKey)
        assertEquals(parents.map { CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(it.id), it.rowRevision) }, issue.candidates)
        assertTrue(issue.transaction.reference is NewCashWriteReferenceDto)
        assertEquals("draft", result.transactions.single().values.state)
        prepareCashImportWriteSetSourceMetadata(result, source.facts)
    }

    @Test fun duplicateOccurrencesShareOneNaturalBindingWithTheLastParserMetadata() {
        val first = files().single(); val last = first.copy(fileRef = "last", digest = "b".repeat(64),
            document = first.document!!.copy(parserVersion = "p2"))
        val result = writes(files = listOf(first, last))
        assertEquals(1, result.transactions.size)
        val provider = result.sourceBindings.single { it.identityKind == "provider_id" }
        assertEquals("p2", provider.strategyVersion)
        assertEquals(1, result.statementAccountMappings.size)
        assertEquals(result.sourceBindings.size, result.sourceBindings.map {
            listOf(it.sourceType, it.sourceScopeKind, it.sourceScopeKey, it.identityKind, it.identityKey)
        }.toSet().size)
    }

    private fun documentFile(): ParsedImportFile {
        val file = files().single(); val document = file.document!!
        return file.copy(document = document.copy(coverage = CashSourceCoverage("2026-08-01T00:00Z", "2026-09-01T00:00Z", true,
            "icbc-pdf-export-pages-count-v1", emptyMap(), "v", documentKey = "d".repeat(64), pageTotal = 2,
            pageFingerprints = mapOf("1" to "1".repeat(64), "2" to "2".repeat(64))),
            rows = document.rows.map { it.copy(documentPosition = "p1:t0:r1") }))
    }

    @Test fun emptyDocumentPageAndPerRowCoverageUseTheOriginalPageSelection() {
        val files = listOf(documentFile()); val result = writes(files = files)
        val page = result.sourceEmptyPages.single()
        assertEquals(2, page.pageNumber)
        val proof = decodeStoredCashSourceCoverage(page.coverage)
        assertFalse(proof.complete); assertEquals("", proof.evidence); assertEquals(setOf("2"), proof.pageFingerprints.keys)
        for (binding in result.sourceBindings) assertEquals(setOf("1"), decodeStoredCashSourceCoverage(binding.coverage).pageFingerprints.keys)
        prepareCashImportWriteSetSourceMetadata(result, snapshot().facts)
        val source = snapshot().let { it.copy(facts = it.facts.copy(cashSourceEmptyPages = listOf(page))) }
        assertTrue(writes(source, files).sourceEmptyPages.isEmpty())
    }

    @Test fun subsetOrIncompleteRowLocationsDoNotCreateEmptyPageRecords() {
        val plain = files().single(); val doc = documentFile().let { file -> file.copy(fileRef = "doc", digest = "b".repeat(64),
            document = file.document!!.copy(rows = file.document!!.rows.map { it.copy(providerId = "0002") })) }
        for (file in listOf(doc.copy(isSubset = true), doc.copy(document = doc.document!!.copy(observedCount = 2)),
            doc.copy(document = doc.document!!.copy(rows = doc.document!!.rows.map { it.copy(documentPosition = null) })))) {
            assertTrue(writes(files = listOf(plain, file)).sourceEmptyPages.isEmpty())
        }
    }

    @Test fun skippedConflictingDocumentNamespaceDoesNotCreateEmptyPages() {
        val first = documentFile().copy(fileRef = "doc1")
        val doc = first.document!!
        val second = first.copy(fileRef = "doc2", digest = "b".repeat(64), document = doc.copy(
            coverage = doc.coverage.copy(pageFingerprints = doc.coverage.pageFingerprints + ("1" to "a".repeat(64)))))
        val plain = files().single().let { it.copy(digest = "c".repeat(64), document = it.document!!.copy(rows = it.document!!.rows.map { row -> row.copy(providerId = "other") })) }
        val all = listOf(first, second, plain)
        val source = snapshot(); val (prepared, plan) = calculate(source, all)
        assertEquals(2, plan.getValue("row_decisions").jsonArray.count { it.jsonObject["planned_action"] == JsonPrimitive("skip") })
        assertTrue(cashImportSourceEvidenceGroups(all, prepared.source, decodeLocalCashValue(plan) as Map<String, Any?>).isEmpty())
        val result = buildLocalCashImportSourceWrites(source, all, prepared.source, plan)
        assertEquals(1, result.transactions.size)
        assertTrue(result.sourceEmptyPages.isEmpty())
    }
}
