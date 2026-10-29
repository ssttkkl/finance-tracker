package com.finance.tracker.backend

import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*
import com.finance.tracker.backend.persistence.generated.tables.references.*
import org.jooq.exception.DataAccessException

class JooqPersistenceTest {
    private fun database(): Database {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")), "database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }

    @Test fun generatedFieldsPreserveExactAmountsTimestampsAndJson() {
        CurrentProjectionFixture().use { fixture ->
            val db = fixture.db
            for (text in listOf("99999999999999999999.999999999999999999", "-0.000000000000000001", "12.3400", "-0.0000")) {
                db.write { connection ->
                    val sql = connection.jooq(db.sqlite)
                    val instant = Instant.parse("2026-08-12T03:04:05.123456999Z")
                    val payload = buildJsonObject { put("value", text); put("null", JsonNull); put("source", "匿名来源") }
                    assertEquals(1, sql.update(CASH_TRANSACTIONS)
                        .set(CASH_TRANSACTIONS.AMOUNT, CashStoredDecimal(text))
                        .set(CASH_TRANSACTIONS.OCCURRED_AT, instant)
                        .set(CASH_TRANSACTIONS.SOURCE_PAYLOAD, payload)
                        .where(CASH_TRANSACTIONS.ID.eq(fixture.identifier)).execute())
                    val row = sql.select(CASH_TRANSACTIONS.AMOUNT, CASH_TRANSACTIONS.OCCURRED_AT, CASH_TRANSACTIONS.SOURCE_PAYLOAD)
                        .from(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.ID.eq(fixture.identifier)).fetchSingle()
                    assertEquals(storedCashDecimal(text, db.sqlite), row[CASH_TRANSACTIONS.AMOUNT])
                    assertEquals(Instant.parse("2026-08-12T03:04:05.123456Z"), row[CASH_TRANSACTIONS.OCCURRED_AT])
                    assertEquals(payload, row[CASH_TRANSACTIONS.SOURCE_PAYLOAD])
                }
            }
            db.write { connection ->
                val sql = connection.jooq(db.sqlite)
                sql.update(CASH_TRANSACTIONS).setNull(CASH_TRANSACTIONS.AMOUNT).setNull(CASH_TRANSACTIONS.OCCURRED_AT)
                    .set(CASH_TRANSACTIONS.SOURCE_PAYLOAD, JsonNull).set(CASH_TRANSACTIONS.STATE, "draft")
                    .where(CASH_TRANSACTIONS.ID.eq(fixture.identifier)).execute()
                val row = sql.selectFrom(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.ID.eq(fixture.identifier)).fetchSingle()
                assertNull(row.amount); assertNull(row.occurredAt); assertEquals(JsonNull, row.sourcePayload)
            }
        }
    }

    @Test fun suppliedConnectionKeepsSavepointAndOuterRollbackOwnership() {
        val db = database()
        val key = "jooq-${UUID.randomUUID()}"
        class Rollback : RuntimeException()
        assertFailsWith<Rollback> {
            db.write { connection ->
                val context = CashSqlContext(connection, "fixture-a", db.sqlite)
                val stamp = context.requireTransactionStamp()
                val sql = connection.jooq(db.sqlite)
                sql.insertInto(WORKSPACES).set(WORKSPACES.ID, key).set(WORKSPACES.NAME, "保存点前")
                    .set(WORKSPACES.CREATED_AT, Instant.parse("2026-08-12T03:04:05Z")).execute()
                connection.createStatement().use { it.execute("SAVEPOINT jooq_binding") }
                sql.update(WORKSPACES).set(WORKSPACES.NAME, "保存点内").where(WORKSPACES.ID.eq(key)).execute()
                connection.createStatement().use { it.execute("ROLLBACK TO SAVEPOINT jooq_binding"); it.execute("RELEASE SAVEPOINT jooq_binding") }
                assertTrue(stamp.isCurrent()); assertFalse(connection.isClosed)
                assertEquals("保存点前", sql.select(WORKSPACES.NAME).from(WORKSPACES).where(WORKSPACES.ID.eq(key)).fetchSingle(WORKSPACES.NAME))
                throw Rollback()
            }
        }
        db.read { connection -> assertFalse(connection.jooq(db.sqlite).fetchExists(WORKSPACES, WORKSPACES.ID.eq(key))) }
    }

    @Test fun databaseFailuresAreSafeAndProgrammerFailuresRemainDistinct() {
        val db = database()
        val failure = assertFailsWith<StorageFailure> {
            db.write { connection ->
                connection.jooq(db.sqlite).insertInto(WORKSPACES).set(WORKSPACES.ID, "fixture-a")
                    .set(WORKSPACES.NAME, "secret-bind-value").set(WORKSPACES.CREATED_AT, Instant.now()).execute()
            }
        }
        assertEquals("storage.connect", failure.code)
        assertEquals("storage.connect", failure.message)
        assertNull(failure.cause)
        val programmer = DataAccessException("programmer sentinel")
        assertSame(programmer, assertFailsWith<DataAccessException> { db.write<Unit> { throw programmer } })
    }
}
