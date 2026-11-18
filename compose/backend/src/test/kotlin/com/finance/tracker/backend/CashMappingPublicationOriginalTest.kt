package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import com.finance.tracker.business.domain.calculateLocalCashImportBusinessPlan
import com.finance.tracker.business.domain.localNormalizedCashDocument
import com.finance.tracker.business.domain.normalizedCashDocumentWire
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class CashMappingPublicationOriginalTest {
    private fun fixture(name: String) = CashImportOriginalDataFixture(name, "FT_KOTLIN_MAPPING_PUBLICATION_DIR")
    private fun queryOnly(connection: Connection) = Proxy.newProxyInstance(Connection::class.java.classLoader,
        arrayOf(Connection::class.java)) { _, method, args ->
        if (method.name == "prepareStatement") assertTrue((args!![0] as String).trimStart().startsWith("select", true))
        assertFalse(method.name in setOf("createStatement", "prepareCall"))
        try { method.invoke(connection, *(args ?: emptyArray())) }
        catch (error: InvocationTargetException) { throw error.targetException }
    } as Connection
    @Suppress("UNCHECKED_CAST") private fun choices(name: String): List<Map<String, Any?>> = cashSourceValue(Json.parseToJsonElement(
        Path.of(requireNotNull(System.getenv("FT_KOTLIN_MAPPING_PUBLICATION_DIR")), name, "api-input.json").readText()).jsonObject.getValue("row_choices")) as List<Map<String, Any?>>
    private data class Prepared(val plan: Map<String, Any?>, val request: JsonObject)
    private fun plan(f: CashImportOriginalDataFixture, files: List<ParsedImportFile>, choices: List<Map<String, Any?>> = emptyList(), account: String = "101"): Prepared {
        val before = f.snapshot()
        val source = LedgerReadService(f.database, now = { f.now }).snapshot(f.token)
        val normalized = files.map { file -> file.copy(document = file.document?.let { document ->
            localNormalizedCashDocument(normalizedCashDocumentWire(document) { sourceJson(it).jsonObject })
        }) }
        val input = sourceJson(mapOf("account_selections" to CashImportFunding.scan(files).groups.map {
            mapOf("source_account_ref" to it.sourceAccountRef, "account_id" to account, "expected_account_revision" to 1)
        }, "row_choices" to choices)).jsonObject
        val (plan, changes) = calculateLocalCashImportBusinessPlan(normalized, source, input,
            requireActive = {}, now = { kotlin.time.Instant.parse(f.now.toString()) })
        assertEquals(before, f.snapshot(), "Local calculation must not publish facts or defaults")
        assertEquals(true, plan["can_commit"])
        return Prepared(plan, Json.encodeToJsonElement(CashWriteSetRequestDto(source.ledgerSeq, CASH_RULES_VERSION, changes)).jsonObject)
    }
    private fun commit(f: CashImportOriginalDataFixture, plan: Prepared, key: String = "synthetic-confirm",
        service: CashImportWriteSetSubmissionService = CashImportWriteSetSubmissionService(f.database, now = { f.now })) =
        service.submit(f.token, f.workspace, plan.request, key)
    private fun summary(value: JsonObject, key: String) = value.getValue("summary").jsonObject.getValue(key).jsonPrimitive.int
    private fun assertSuccessfulWrite(value: JsonObject) {
        val outcome = value.getValue("commit_outcome").jsonObject
        assertEquals(JsonPrimitive(false), outcome.getValue("has_new_failures"))
        assertEquals(JsonPrimitive(true), outcome.getValue("has_business_writes"))
        assertEquals(JsonArray(emptyList()), outcome.getValue("reason_codes"))
    }
    private fun mappings(f: CashImportOriginalDataFixture) = f.database.read { connection ->
        val m = STATEMENT_ACCOUNT_MAPPINGS
        connection.jooq(f.database.sqlite).select(m.SOURCE_TYPE, m.IDENTITY_KIND, m.SOURCE_ACCOUNT_KEY, m.ACCOUNT_ID, m.REVISION)
            .from(m).where(m.WORKSPACE_ID.eq(f.workspace)).orderBy(m.ID).fetch().map { listOf(it.value1(), it.value2(), it.value3(), it.value4().toString(), it.value5()) }
    }
    private fun account(f: CashImportOriginalDataFixture, id: Long, change: String) = f.database.write { connection ->
        val a = ACCOUNTS; val sql = connection.jooq(f.database.sqlite)
        if (change == "inactive") sql.update(a).set(a.ACTIVE, false).set(a.REVISION, a.REVISION.plus(1L)).where(a.ID.eq(id).and(a.WORKSPACE_ID.eq(f.workspace))).execute()
        else sql.update(a).set(a.CURRENCIES, JsonArray(listOf(JsonPrimitive("USD")))).set(a.REVISION, a.REVISION.plus(1L)).where(a.ID.eq(id).and(a.WORKSPACE_ID.eq(f.workspace))).execute()
        connection.markLedgerChanged(f.workspace)
    }

    @Test fun onlyFinalConfirmationSavesUsedMappingAndReplayIsNoop() {
        for ((index, draft) in listOf(false, true).withIndex()) {
            val f = fixture("confirm-$index"); val file = f.files("default"); val prepared = plan(f, file)
            assertTrue(mappings(f).isEmpty())
            val receipt = commit(f, prepared)
            assertEquals(1, summary(receipt, if (draft) "saved_draft" else "created_posted"))
            val group = CashImportFunding.scan(file).groups.single()
            val expected = listOf(listOf("alipay", "funding_group_v1", group.sourceAccountRef, "101", 1L))
            assertEquals(expected, mappings(f))
            val saved = f.snapshot()
            assertEquals(receipt, commit(f, prepared)); assertEquals(expected, mappings(f)); assertEquals(saved, f.snapshot())
            commit(f, plan(f, f.files("second")), "synthetic-second-import"); assertEquals(expected, mappings(f))
            val beforeSuggestion = f.snapshot()
            val suggestion = f.database.read { CashImportAccountMappings(queryOnly(it), f.workspace, f.database.sqlite).suggest(listOf(group)).getValue(group.sourceAccountRef) }
            assertEquals(beforeSuggestion, f.snapshot())
            assertEquals("101", suggestion.accountId); assertEquals("confirmed_mapping", suggestion.basis)
            assertNotNull(suggestion.expectedAccountRevision); assertEquals(1L, suggestion.expectedAccountRevision)
        }
    }

    @Test fun sourceFailureRollsBackAllSharedAccountDefaults() {
        for (success in listOf("none", "one", "override_only")) {
            val name = "row-$success"; val f = fixture(name); val prepared = plan(f, f.files("default"), choices(name)); val before = f.snapshot()
            val operations = (prepared.plan.getValue("operations") as List<*>).map { it as Map<*, *> }
            assertEquals(2, operations.size); assertTrue(operations.all { it["planned_action"] == "create_posted" })
            assertEquals(setOf(listOf("file_a:r1"), listOf("file_b:r1")), operations.map { it["row_refs"] }.toSet())
            f.mode = "source"
            val failure = try { assertFailsWith<CashImportCommitFailure> { commit(f, prepared) } }
                finally { f.mode = null }
            assertEquals("not_committed", failure.commitState); assertEquals(1, f.faultCount)
            assertEquals("source_bindings", failure.diagnostic.stage)
            assertEquals(before, f.snapshot()); assertTrue(mappings(f).isEmpty())
        }
    }

    @Test fun notePatchAndSameAccountCoalescingKeepBothSourceProofs() {
        val f = fixture("coalesce"); val files = f.files("default")
        val receipt = commit(f, plan(f, files, choices("coalesce")))
        assertEquals(1, summary(receipt, "created_posted"))
        assertEquals(CashImportFunding.scan(files).groups.map { it.sourceAccountRef }.toSet(), mappings(f).map { it[2] }.toSet())
        assertTrue(mappings(f).all { it.drop(3) == listOf("101", 1L) })
    }

    @Test fun explicitlyDeferredDraftRemembersEligibleAccount() {
        val f = fixture("defer")
        assertEquals(1, summary(commit(f, plan(f, f.files("default"), choices("defer"))), "saved_draft"))
        assertEquals(listOf(listOf("101", 1L)), mappings(f).map { it.drop(3) })
    }

    @Test fun relationFailureRollsBackAllAccountDefaults() {
        val f = fixture("relation"); val prepared = plan(f, f.files("default")); val before = f.snapshot()
        f.mode = "relation"
        try { assertEquals("not_committed", assertFailsWith<CashImportCommitFailure> { commit(f, prepared) }.commitState) }
        finally { f.mode = null }
        assertTrue(f.faultCount > 0); assertEquals(before, f.snapshot()); assertTrue(mappings(f).isEmpty())
    }

    @Test fun currencyIneligibleDraftDoesNotCreateOrReplaceDefault() {
        for ((index, existing) in listOf(false, true).withIndex()) {
            val f = fixture("currency-$index"); var files = f.files("default")
            if (existing) { commit(f, plan(f, files)); files = f.files("second") }
            val before = mappings(f); account(f, 102, "currency")
            val receipt = commit(f, plan(f, files, account = "102"), "synthetic-currency-draft")
            assertEquals(1, summary(receipt, "saved_draft")); assertEquals(before, mappings(f))
        }
    }

    @Test fun changingDefaultKeepsTheRetainedAccountChoiceDuringExplicitRecalculation() {
        val f = fixture("frozen"); commit(f, plan(f, f.files("default")))
        val prepared = plan(f, f.files("second"))
        f.database.write { connection ->
            val sql = connection.jooq(f.database.sqlite); val m = STATEMENT_ACCOUNT_MAPPINGS; val a = ACCOUNTS
            sql.update(m).set(m.ACCOUNT_ID, 102L).set(m.REVISION, 2L).where(m.WORKSPACE_ID.eq(f.workspace).and(m.IDENTITY_KIND.eq("funding_group_v1"))).execute()
            sql.update(a).set(a.NAME, "账户展示名称已变化").set(a.REVISION, a.REVISION.plus(1L)).where(a.ID.eq(101L)).execute()
            connection.markLedgerChanged(f.workspace)
        }
        val changed = f.snapshot()
        assertFailsWith<LedgerBaseChanged> { commit(f, prepared, "synthetic-frozen-choice") }
        assertEquals(changed, f.snapshot())
        val receipt = commit(f, plan(f, f.files("second")), "synthetic-frozen-choice")
        assertEquals(1, summary(receipt, "created_posted")); assertSuccessfulWrite(receipt)
        assertEquals(listOf(listOf("101", 3L)), mappings(f).map { it.drop(3) })
    }

    @Test fun postedMutableUpdateDoesNotRequireRememberingAnIneligibleDefault() {
        for (change in listOf("inactive", "currency")) {
            val f = fixture("mutable-$change"); val prepared = plan(f, f.files("default"))
            assertEquals(1, (prepared.plan["summary"] as Map<*, *>)["update_posted"])
            account(f, 101, change)
            val changed = f.snapshot()
            assertFailsWith<LedgerBaseChanged> { commit(f, prepared) }
            assertEquals(changed, f.snapshot())
            val refreshed = plan(f, f.files("default"))
            assertEquals(1, (refreshed.plan["summary"] as Map<*, *>)["update_posted"])
            val receipt = commit(f, refreshed)
            assertEquals(1, summary(receipt, "updated_posted")); assertSuccessfulWrite(receipt); assertTrue(mappings(f).isEmpty())
            assertEquals("更新后的来源原文", f.database.read { it.jooq(f.database.sqlite).select(CASH_TRANSACTIONS.NOTE).from(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.ID.eq(1003L)).fetchSingle(CASH_TRANSACTIONS.NOTE) })
        }
    }

    @Test fun mappingAndRecordsRollbackTogetherOnPublicationFailure() {
        for (stage in listOf("mapping", "receipt")) {
            val f = fixture("failure-$stage"); val prepared = plan(f, f.files("default")); val before = f.snapshot(); var calls = 0
            val service = CashImportWriteSetSubmissionService(f.database, now = { f.now }, afterStep = {
                if (it == if (stage == "mapping") "account_mappings" else "receipt") { calls++; error("synthetic publication failure") }
            })
            assertEquals("not_committed", assertFailsWith<CashImportCommitFailure> { commit(f, prepared, service = service) }.commitState)
            assertEquals(1, calls); assertTrue(mappings(f).isEmpty()); assertEquals(before, f.snapshot())
            assertEquals(0, f.database.read { it.jooq(f.database.sqlite).fetchCount(CASH_TRANSACTIONS, CASH_TRANSACTIONS.SOURCE_TYPE.eq("alipay")) })
        }
    }
}
