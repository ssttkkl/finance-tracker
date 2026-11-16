package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import com.finance.tracker.business.domain.calculateLocalCashImportBusinessPlan
import com.finance.tracker.business.domain.localNormalizedCashDocument
import com.finance.tracker.business.domain.normalizedCashDocumentWire
import com.finance.tracker.business.domain.suggestLocalCashFundingAccounts
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class CashMappingSuggestionOriginalTest {
    private fun fixture(name: String) = CashImportOriginalDataFixture(name, "FT_KOTLIN_MAPPING_SUGGESTION_DIR")
    private fun group(files: List<ParsedImportFile>) = CashImportFunding.scan(files).groups.single()
    private fun queryOnly(connection: Connection) = Proxy.newProxyInstance(Connection::class.java.classLoader,
        arrayOf(Connection::class.java)) { _, method, args ->
        if (method.name == "prepareStatement") assertTrue((args!![0] as String).trimStart().startsWith("select", true))
        assertFalse(method.name in setOf("createStatement", "prepareCall"))
        try { method.invoke(connection, *(args ?: emptyArray())) }
        catch (error: InvocationTargetException) { throw error.targetException }
    } as Connection
    private fun suggest(f: CashImportOriginalDataFixture, files: List<ParsedImportFile>): CashFundingSuggestion {
        val before = f.snapshot(); val groups = CashImportFunding.scan(files).groups
        val result = f.database.read { CashImportAccountMappings(queryOnly(it), f.workspace, f.database.sqlite).suggest(groups) }
        val source = LedgerReadService(f.database, now = { f.now }).snapshot(f.token)
        val local = suggestLocalCashFundingAccounts(groups, source)
        assertEquals(result, local)
        assertEquals(before, f.snapshot()); return local.getValue(groups.single().sourceAccountRef)
    }
    private fun mappings(f: CashImportOriginalDataFixture) = f.database.read { connection ->
        val m = STATEMENT_ACCOUNT_MAPPINGS
        connection.jooq(f.database.sqlite).select(m.SOURCE_TYPE, m.IDENTITY_KIND, m.SOURCE_ACCOUNT_KEY, m.ACCOUNT_ID, m.REVISION)
            .from(m).where(m.WORKSPACE_ID.eq(f.workspace)).orderBy(m.ID).fetch().map { it.intoList() }
    }
    private fun mapping(f: CashImportOriginalDataFixture, source: String, kind: String, key: String, account: Long = 101L,
        workspace: String = f.workspace) = f.database.write { connection ->
        val m = STATEMENT_ACCOUNT_MAPPINGS
        connection.jooq(f.database.sqlite).insertInto(m).set(m.WORKSPACE_ID, workspace).set(m.SOURCE_TYPE, source)
            .set(m.IDENTITY_KIND, kind).set(m.SOURCE_ACCOUNT_KEY, key).set(m.ACCOUNT_ID, account).set(m.CONFIRMED_BY, f.user)
            .set(m.REVISION, 1L).set(m.CREATED_AT, f.now).set(m.UPDATED_AT, f.now).execute()
    }

    @Test fun onlyExactUniqueEligibleBankAliasIsSuggested() {
        for ((index, ambiguous) in listOf(false, true).withIndex()) {
            val f = fixture("alias-$index"); val files = f.files("default"); val owner = "1234567890123456"
            f.database.write { connection ->
                val a = ACCOUNT_ALIASES; val sql = connection.jooq(f.database.sqlite)
                for ((kind, value, account) in listOf(Triple("account_identifier", owner, 101L), Triple("card_tail", owner.takeLast(4), 102L)) +
                    if (ambiguous) listOf(Triple("account_identifier", owner, 102L)) else emptyList()) {
                    sql.insertInto(a).set(a.WORKSPACE_ID, f.workspace).set(a.ALIAS_TYPE, kind).set(a.ALIAS_VALUE, value)
                        .set(a.ACCOUNT_ID, account).set(a.CREATED_AT, f.now).set(a.UPDATED_AT, f.now).execute()
                }
            }
            val value = suggest(f, files)
            assertEquals(if (ambiguous) null else "101", value.accountId)
            assertEquals(if (ambiguous) "none" else "account_alias", value.basis)
            assertTrue(mappings(f).isEmpty())
        }
    }

    @Test fun mappingDoesNotCrossOwnerOrFileLocalScope() {
        repeat(2) { index ->
            val f = fixture("scope-$index"); val first = f.files("default"); val other = f.files("other")
            val own = group(first); val different = group(other)
            val source = LedgerReadService(f.database, now = { f.now }).snapshot(f.token)
            val before = f.snapshot()
            val files = first.map { file -> file.copy(document = localNormalizedCashDocument(
                normalizedCashDocumentWire(requireNotNull(file.document)) { sourceJson(it).jsonObject })) }
            val review = buildJsonObject { put("account_selections", buildJsonArray { add(buildJsonObject {
                put("source_account_ref", own.sourceAccountRef); put("account_id", "101"); put("expected_account_revision", 1)
            }) }) }
            val (plan, changes) = calculateLocalCashImportBusinessPlan(files, source, review,
                requireActive = {}, now = { kotlin.time.Instant.parse(f.now.toString()) })
            assertEquals(true, plan["can_commit"])
            assertEquals(before, f.snapshot(), "Local calculation must not publish facts or defaults")
            val service = CashImportWriteSetSubmissionService(f.database, now = { f.now })
            val input = Json.encodeToJsonElement(CashWriteSetRequestDto(source.ledgerSeq, CASH_RULES_VERSION, changes)).jsonObject
            val receipt = service.submit(f.token, f.workspace, input, "synthetic-confirm")
            assertEquals(JsonPrimitive(1), receipt.getValue("summary").jsonObject["created_posted"])
            val saved = f.snapshot()
            assertEquals(receipt, service.submit(f.token, f.workspace, input, "synthetic-confirm"))
            assertEquals(saved, f.snapshot(), "Original-key replay must not publish any additional change")
            assertNotEquals(own.sourceAccountRef, different.sourceAccountRef)
            assertEquals("101", suggest(f, first).accountId)
            assertNull(suggest(f, other).accountId)
        }
    }

    @Test fun suggestionsRecheckAccountEligibilityWithoutWrites() {
        for ((index, change) in listOf("inactive", "type", "currency", "workspace").withIndex()) {
            val f = fixture("eligibility-$index"); val files = f.files("default"); val group = group(files)
            if (change == "workspace") {
                val workspace = f.database.read { it.jooq(f.database.sqlite).select(ACCOUNTS.WORKSPACE_ID).from(ACCOUNTS).where(ACCOUNTS.ID.eq(104L)).fetchSingle(ACCOUNTS.WORKSPACE_ID)!! }
                mapping(f, "alipay", "funding_group_v1", group.sourceAccountRef, 104L, workspace)
            } else {
                mapping(f, "alipay", "funding_group_v1", group.sourceAccountRef)
                f.database.write { connection ->
                    val sql = connection.jooq(f.database.sqlite); val a = ACCOUNTS
                    when (change) {
                        "inactive" -> sql.update(a).set(a.ACTIVE, false).set(a.REVISION, a.REVISION.plus(1L)).where(a.ID.eq(101L)).execute()
                        "type" -> sql.update(a).set(a.TYPE, "security").set(a.REVISION, a.REVISION.plus(1L)).where(a.ID.eq(101L)).execute()
                        else -> sql.update(a).set(a.CURRENCIES, JsonArray(listOf(JsonPrimitive("USD")))).set(a.REVISION, a.REVISION.plus(1L)).where(a.ID.eq(101L)).execute()
                    }
                }
            }
            val before = mappings(f)
            assertEquals(if (change == "currency") "101" else null, suggest(f, files).accountId)
            assertEquals(before, mappings(f))
        }
    }

    @Test fun legacyMappingIsOnlyANarrowReadonlyHint() {
        for ((index, expected) in listOf(false, true, false).withIndex()) {
            val kind = listOf("payment_method", "file_account", "card_tail")[index]
            val f = fixture("legacy-$index"); val files = f.files("default"); val group = group(files)
            val owner = "1234567890123456"
            val key = when (kind) { "card_tail" -> owner.takeLast(4); "file_account" -> owner; else -> group.identityKey }
            mapping(f, group.sourceType, kind, key)
            val before = mappings(f)
            assertEquals(if (expected) "101" else null, suggest(f, files).accountId)
            assertEquals(before, mappings(f))
        }
    }
}
