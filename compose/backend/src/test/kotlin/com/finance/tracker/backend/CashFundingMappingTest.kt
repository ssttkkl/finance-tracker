package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashFundingMappingTest {
    private data class SqlCall(val sql: String, val parameters: Map<Int, Any?>)
    private val calls = mutableListOf<SqlCall>()
    private fun observe(connection: java.sql.Connection): java.sql.Connection {
        fun invoke(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? =
            try { method.invoke(target, *(args ?: emptyArray())) }
            catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
        return java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection::class.java.classLoader,
            arrayOf(java.sql.Connection::class.java)) { _, method, args ->
            val result = invoke(connection, method, args)
            val sql = args?.firstOrNull() as? String
            if (result !is java.sql.PreparedStatement || sql == null) result else {
                val bound = linkedMapOf<Int, Any?>()
                java.lang.reflect.Proxy.newProxyInstance(java.sql.PreparedStatement::class.java.classLoader,
                    arrayOf(java.sql.PreparedStatement::class.java)) { _, operation, values ->
                    if (operation.name.startsWith("set") && values?.size == 2 && values[0] is Int)
                        bound[values[0] as Int] = if (operation.name == "setNull") null else values[1]
                    if (operation.name in setOf("execute", "executeQuery", "executeUpdate"))
                        calls += SqlCall(sql.lowercase(), bound.toMap())
                    invoke(result, operation, values)
                }
            }
        } as java.sql.Connection
    }
    private val now = Instant.parse("2026-10-30T04:00:00.123456Z")
    private fun database() = Json.parseToJsonElement(Path.of(requireNotNull(
        System.getenv("FT_KOTLIN_PLAN_BUILD_CONTRACT_DIR"))).resolve("database.json").readText()).jsonObject.let {
        Database(it.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"), ::observe)
    }
    private fun group(ref: String = "ref", source: String = "alipay", kind: String = "payment_method",
        origin: String = "owner", identity: String = "余额") = CashFundingGroup(ref, source, kind, origin, identity,
            "合成账户", listOf("CNY"), listOf("a:r1"))
    private fun choice(ref: String, account: String, source: String = "alipay") =
        mapOf("source_type" to source, "source_account_ref" to ref, "account_id" to account)

    @Test fun publicationIsAtomicAndSameAccountDoesNotChangeConfirmation() {
        val db = database()
        db.read { connection ->
            val repo = CashFundingMappingRepository(connection, "plan-a", db.sqlite) { now }
            val key = CashFundingMappingKey("alipay", "funding_group_v1", "ref")
            repo.publish(listOf(choice("ref", "101")), "admin")
            val first = repo.lookup(listOf(key)).getValue(key)
            assertEquals(1L, first.revision); assertEquals(now, first.createdAt); assertEquals(now, first.updatedAt)
            repo.publish(listOf(choice("ref", "101"), choice("ref", "101")), "other")
            assertEquals(first, repo.lookup(listOf(key)).getValue(key))
            val sql = connection.jooq(db.sqlite)
            val a = ACCOUNTS
            sql.insertInto(a).set(a.ID, 102L).set(a.WORKSPACE_ID, "plan-a").set(a.NAME, "合成账户乙")
                .set(a.TYPE, "cash").set(a.ACTIVE, true).set(a.CURRENCIES, JsonArray(listOf(JsonPrimitive("CNY"))))
                .set(a.METADATA_JSON, JsonObject(emptyMap())).set(a.REVISION, 4L)
                .set(a.CREATED_AT, now).set(a.UPDATED_AT, now).execute()
            CashFundingMappingRepository(connection, "plan-a", db.sqlite) { now.plusSeconds(1) }
                .publish(listOf(choice("ref", "102")), "other")
            val changed = repo.lookup(listOf(key)).getValue(key)
            assertEquals(2L, changed.revision); assertEquals("other", changed.confirmedBy)
            assertEquals(102L, changed.accountId); assertEquals(now, changed.createdAt)
            assertEquals(now.plusSeconds(1), changed.updatedAt)
            val failure = assertFailsWith<IllegalArgumentException> {
                repo.publish(listOf(choice("new", "101"), choice("ref", "101"), choice("ref", "102")), "admin")
            }
            assertEquals("funding_mapping_conflict", failure.message)
            assertEquals(mapOf(key to changed), repo.lookup(listOf(key, CashFundingMappingKey("alipay", "funding_group_v1", "new"))))
        }
        db.read { connection ->
            assertTrue(CashFundingMappingRepository(connection, "plan-a", db.sqlite).lookup(
                listOf(CashFundingMappingKey("alipay", "funding_group_v1", "ref"))).isEmpty())
        }
    }

    @Test fun confirmedMappingWinsAndEligibilityIsRecheckedWithoutFallback() {
        val db = database()
        db.read { connection ->
            val repo = CashFundingMappingRepository(connection, "plan-a", db.sqlite) { now }
            repo.publish(listOf(choice("ref", "101")), "admin")
            val service = CashImportAccountMappings(connection, "plan-a", db.sqlite)
            val expected = CashFundingSuggestion("101", 1, "confirmed_mapping")
            assertEquals(expected, service.suggest(listOf(group())).getValue("ref"))
            connection.jooq(db.sqlite).update(ACCOUNTS).set(ACCOUNTS.CURRENCIES, JsonArray(listOf(JsonPrimitive("USD"))))
                .where(ACCOUNTS.ID.eq(101L)).execute()
            assertEquals(expected, service.suggest(listOf(group())).getValue("ref"))
            for (type in listOf("security", "cash")) {
                connection.jooq(db.sqlite).update(ACCOUNTS).set(ACCOUNTS.TYPE, type).set(ACCOUNTS.ACTIVE, type != "cash")
                    .where(ACCOUNTS.ID.eq(101L)).execute()
                assertEquals(CashFundingSuggestion(null, null, "none"), service.suggest(listOf(group())).getValue("ref"))
            }
            assertEquals(1L, repo.lookup(listOf(CashFundingMappingKey("alipay", "funding_group_v1", "ref"))).values.single().revision)
            assertEquals(CashFundingSuggestion(null, null, "none"),
                CashImportAccountMappings(connection, "other", db.sqlite).suggest(listOf(group())).getValue("ref"))
        }
    }

    @Test fun onlyCompleteUniqueEligibleAliasCanSuggestAnAccount() {
        val db = database()
        db.read { connection ->
            val sql = connection.jooq(db.sqlite)
            val aliases = ACCOUNT_ALIASES
            sql.insertInto(aliases).set(aliases.WORKSPACE_ID, "plan-a").set(aliases.ACCOUNT_ID, 101L)
                .set(aliases.ALIAS_TYPE, "account_identifier").set(aliases.ALIAS_VALUE, "1234567890123456")
                .set(aliases.CREATED_AT, now).set(aliases.UPDATED_AT, now).execute()
            val service = CashImportAccountMappings(connection, "plan-a", db.sqlite)
            val bank = group(source = "ccb_debit", kind = "file_account", identity = "1234567890123456")
            assertEquals(CashFundingSuggestion("101", 1, "account_alias"), service.suggest(listOf(bank)).getValue("ref"))
            for (invalid in listOf(bank.copy(identityKind = "card_tail", identityKey = "3456"),
                    bank.copy(identityKey = "3456"), bank.copy(identityKey = "１２３４５６")))
                assertEquals(CashFundingSuggestion(null, null, "none"), service.suggest(listOf(invalid)).getValue("ref"))
            val a = ACCOUNTS
            sql.insertInto(a).set(a.ID, 102L).set(a.WORKSPACE_ID, "plan-a").set(a.NAME, "别名账户乙")
                .set(a.TYPE, "loan").set(a.ACTIVE, true).set(a.METADATA_JSON, JsonObject(emptyMap()))
                .set(a.CURRENCIES, JsonArray(listOf(JsonPrimitive("CNY")))).set(a.REVISION, 3L)
                .set(a.CREATED_AT, now).set(a.UPDATED_AT, now).execute()
            sql.insertInto(aliases).set(aliases.WORKSPACE_ID, "plan-a").set(aliases.ACCOUNT_ID, 102L)
                .set(aliases.ALIAS_TYPE, "account_identifier").set(aliases.ALIAS_VALUE, bank.identityKey)
                .set(aliases.CREATED_AT, now).set(aliases.UPDATED_AT, now).execute()
            assertEquals(CashFundingSuggestion(null, null, "none"), service.suggest(listOf(bank)).getValue("ref"))
        }
    }

    @Test fun legacyHintsAreNarrowAndLookupHandlesMoreThanOneBatch() {
        val db = database()
        db.read { connection ->
            val repo = CashFundingMappingRepository(connection, "plan-a", db.sqlite) { now }
            repo.publish((0..404).map { choice("ref-$it", "101") }, "admin")
            val keys = (0..404).map { CashFundingMappingKey("alipay", "funding_group_v1", "ref-$it") }
            calls.clear()
            val found = repo.lookup(keys + keys)
            assertEquals(keys.toSet(), found.keys)
            assertTrue(found.values.all { it.accountId == 101L && it.revision == 1L && it.confirmedBy == "admin" })
            val lookups = calls.filter { it.sql.startsWith("select") && "statement_account_mappings" in it.sql }
            assertEquals(listOf(601, 601, 16), lookups.map { it.parameters.size })
            val m = STATEMENT_ACCOUNT_MAPPINGS
            connection.jooq(db.sqlite).insertInto(m).set(m.WORKSPACE_ID, "plan-a").set(m.SOURCE_TYPE, "wechat")
                .set(m.IDENTITY_KIND, "payment_method").set(m.SOURCE_ACCOUNT_KEY, "微信零钱").set(m.ACCOUNT_ID, 101L)
                .set(m.CONFIRMED_BY, "admin").set(m.REVISION, 1L).set(m.CREATED_AT, now).set(m.UPDATED_AT, now).execute()
            val service = CashImportAccountMappings(connection, "plan-a", db.sqlite)
            val wechat = group(source = "wechat", origin = CashSourceScope.WECHAT_DEFAULT_SCOPE, identity = "微信零钱")
            assertEquals(CashFundingSuggestion("101", 1, "legacy_mapping"), service.suggest(listOf(wechat)).getValue("ref"))
            for (invalid in listOf(wechat.copy(originKey = "wechat:named:v1:工作"), wechat.copy(sourceType = "alipay")))
                assertEquals(CashFundingSuggestion(null, null, "none"), service.suggest(listOf(invalid)).getValue("ref"))
        }
    }

    @Test fun confirmedAndLegacyMappingsSuppressAliasFallbackEvenWhenIneligible() {
        val db = database()
        db.read { connection ->
            val sql = connection.jooq(db.sqlite)
            val a = ACCOUNTS
            sql.insertInto(a).set(a.ID, 102L).set(a.WORKSPACE_ID, "plan-a").set(a.NAME, "失效对应账户")
                .set(a.TYPE, "cash").set(a.ACTIVE, false).set(a.METADATA_JSON, JsonObject(emptyMap()))
                .set(a.CURRENCIES, JsonArray(listOf(JsonPrimitive("CNY")))).set(a.REVISION, 3L)
                .set(a.CREATED_AT, now).set(a.UPDATED_AT, now).execute()
            val aliases = ACCOUNT_ALIASES
            sql.insertInto(aliases).set(aliases.WORKSPACE_ID, "plan-a").set(aliases.ACCOUNT_ID, 101L)
                .set(aliases.ALIAS_TYPE, "account_identifier").set(aliases.ALIAS_VALUE, "1234567890")
                .set(aliases.CREATED_AT, now).set(aliases.UPDATED_AT, now).execute()
            val m = STATEMENT_ACCOUNT_MAPPINGS
            for ((source, kind) in listOf("icbc_credit" to "file_account", "icbc_debit" to "file_account",
                    "icbc_asia" to "account_identifier")) {
                sql.insertInto(m).set(m.WORKSPACE_ID, "plan-a").set(m.SOURCE_TYPE, source).set(m.IDENTITY_KIND, kind)
                    .set(m.SOURCE_ACCOUNT_KEY, "1234567890").set(m.ACCOUNT_ID, 101L).set(m.CONFIRMED_BY, "legacy")
                    .set(m.REVISION, 1L).set(m.CREATED_AT, now).set(m.UPDATED_AT, now).execute()
                val bank = group(source = source, kind = "file_account", identity = "1234567890")
                val service = CashImportAccountMappings(connection, "plan-a", db.sqlite)
                assertEquals(CashFundingSuggestion("101", 1, "legacy_mapping"), service.suggest(listOf(bank)).getValue("ref"))
                for (invalid in listOf(bank.copy(identityKind = "account_identifier"), bank.copy(identityKind = "card_tail")))
                    assertEquals(CashFundingSuggestion(null, null, "none"), service.suggest(listOf(invalid)).getValue("ref"))
                sql.update(m).set(m.ACCOUNT_ID, 102L).where(m.WORKSPACE_ID.eq("plan-a"))
                    .and(m.SOURCE_TYPE.eq(source)).and(m.IDENTITY_KIND.eq(kind)).execute()
                assertEquals(CashFundingSuggestion(null, null, "none"), service.suggest(listOf(bank)).getValue("ref"))
                sql.update(m).set(m.ACCOUNT_ID, 101L).where(m.WORKSPACE_ID.eq("plan-a"))
                    .and(m.SOURCE_TYPE.eq(source)).and(m.IDENTITY_KIND.eq(kind)).execute()
                CashFundingMappingRepository(connection, "plan-a", db.sqlite) { now }
                    .publish(listOf(choice("ref", "102", source)), "admin")
                assertEquals(CashFundingSuggestion(null, null, "none"), service.suggest(listOf(bank)).getValue("ref"))
            }
        }
    }

    @Test fun latePublicationErrorRollsBackEarlierMappingWrites() {
        val db = database()
        calls.clear()
        assertFailsWith<StorageFailure> {
            db.write { connection ->
                CashFundingMappingRepository(connection, "plan-a", db.sqlite) { now }
                    .publish(listOf(choice("a-valid", "101"), choice("z-invalid", "999999")), "admin")
            }
        }
        val inserts = calls.filter { it.sql.startsWith("insert into") && "statement_account_mappings" in it.sql }
        assertEquals(2, inserts.size)
        assertTrue("a-valid" in inserts[0].parameters.values)
        assertTrue("z-invalid" in inserts[1].parameters.values)
        db.read { connection ->
            assertTrue(CashFundingMappingRepository(connection, "plan-a", db.sqlite).lookup(listOf(
                CashFundingMappingKey("alipay", "funding_group_v1", "a-valid"),
                CashFundingMappingKey("alipay", "funding_group_v1", "z-invalid"))).isEmpty())
        }
    }

    @Test fun aliasSuggestionsCoverEveryKeyAcrossBatches() {
        val db = database()
        db.read { connection ->
            val sql = connection.jooq(db.sqlite)
            val a = ACCOUNT_ALIASES
            val groups = (0..404).map { group(ref = "ref-$it", source = "ccb_debit", kind = "file_account",
                identity = "123456${it.toString().padStart(4, '0')}") }
            sql.batch(groups.map { bank ->
                sql.insertInto(a).set(a.WORKSPACE_ID, "plan-a").set(a.ACCOUNT_ID, 101L)
                    .set(a.ALIAS_TYPE, "account_identifier").set(a.ALIAS_VALUE, bank.identityKey)
                    .set(a.CREATED_AT, now).set(a.UPDATED_AT, now)
            }).execute()
            calls.clear()
            val suggestions = CashImportAccountMappings(connection, "plan-a", db.sqlite).suggest(groups)
            assertEquals(groups.map { it.sourceAccountRef }.toSet(), suggestions.keys)
            assertTrue(suggestions.values.all { it == CashFundingSuggestion("101", 1, "account_alias") })
            val lookups = calls.filter { it.sql.startsWith("select") && "account_aliases" in it.sql }
            assertEquals(listOf(401, 401, 11), lookups.map { it.parameters.size })
            assertTrue(calls.all { it.sql.startsWith("select") })
        }
    }

    @Test fun mappingRevisionOverflowDoesNotChangeTheSavedAccount() {
        val db = database()
        db.read { connection ->
            val repo = CashFundingMappingRepository(connection, "plan-a", db.sqlite) { now }
            val key = CashFundingMappingKey("alipay", "funding_group_v1", "ref")
            repo.publish(listOf(choice("ref", "101")), "admin")
            val m = STATEMENT_ACCOUNT_MAPPINGS
            connection.jooq(db.sqlite).update(m).set(m.REVISION, Long.MAX_VALUE)
                .where(m.WORKSPACE_ID.eq("plan-a")).and(m.SOURCE_ACCOUNT_KEY.eq("ref")).execute()
            val before = repo.lookup(listOf(key)).getValue(key)
            assertFailsWith<ArithmeticException> { repo.publish(listOf(choice("ref", "102")), "other") }
            assertEquals(before, repo.lookup(listOf(key)).getValue(key))
        }
    }
}
