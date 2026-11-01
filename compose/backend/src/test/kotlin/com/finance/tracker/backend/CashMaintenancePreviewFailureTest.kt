package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashMaintenancePreviewFailureTest {
    private val at = Instant.parse("2026-10-01T09:00:00Z")
    private class Store(at: Instant) : ParsedImportStore(clock = { at }) {
        val entries = linkedMapOf<String, ParsedImportObject>()
        var writes = 0
        override fun readRaw(id: String) = entries[id] ?: throw ParsedImportNotFound()
        override fun writeRaw(id: String, content: ByteArray, expected: String?, expiresAt: Instant) {
            if (entries[id]?.etag != expected) throw ParsedImportRevisionConflict()
            entries[id] = ParsedImportObject(content.copyOf(), digest(content)); writes++
        }
        override fun deleteRaw(id: String) { entries.remove(id) }
        override fun sweepExpired(cursor: String?, limit: Int, shouldStop: () -> Boolean) = ParsedImportSweep(0, 0, null)
    }
    private class DiscoveryFault {
        var armed = false
        var attempts = 0
        val failure = IllegalStateException("synthetic private discovery failure")
        private fun invoke(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? =
            try { method.invoke(target, *(args ?: emptyArray())) } catch (error: InvocationTargetException) { throw error.targetException }
        fun decorate(connection: Connection): Connection = Proxy.newProxyInstance(Connection::class.java.classLoader,
            arrayOf(Connection::class.java)) { _, method, args ->
            val result = invoke(connection, method, args)
            val query = args?.firstOrNull() as? String
            if (result is PreparedStatement && query?.lowercase()?.contains("account_aliases") == true) {
                Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java)) { _, action, values ->
                    val value = invoke(result, action, values)
                    if (armed && action.name in setOf("executeQuery", "execute") && Thread.currentThread().stackTrace.any {
                            it.className == "com.finance.tracker.backend.CashImportRelationDiscovery" && it.methodName == "discover"
                        }) { attempts++; throw failure }
                    value
                } as Statement
            } else result
        } as Connection
    }
    private inner class Fixture(name: String) {
        val directory = Path.of(requireNotNull(System.getenv("FT_KOTLIN_MAINTENANCE_PREVIEW_FAILURE_DIR"))).resolve(name)
        private val config = Json.parseToJsonElement(directory.resolve("database.json").readText()).jsonObject
        val case = Json.parseToJsonElement(directory.resolve("case.json").readText()).jsonObject
        val workspace = config.getValue("workspace").jsonPrimitive.content
        val user = config.getValue("user").jsonPrimitive.content
        val token = "original-import-owner"
        val fault = DiscoveryFault()
        val database = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"), fault::decorate)
        val store = Store(at)
        val service = MutationPlanService(database, now = { at }, staging = store)
        val input get() = case.getValue("input").jsonObject
        fun detail() = CashRecordDetailService(database, now = { at }).detail(token, input.getValue("transaction_id").jsonPrimitive.content)
        fun state() = database.read { connection ->
            val tables = DefaultSchema.DEFAULT_SCHEMA.tables; assertEquals(31, tables.size)
            tables.associate { table -> table.name to connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
                    buildList { while (rows.next()) add(JsonArray((1..rows.metaData.columnCount).map { rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
                }
            } }
        }
    }

    @Test fun sourceTextCannotBeEditedAndTheFullRefundStillAppearsOnceAtZero() {
        val f = Fixture("source_text"); val before = f.state(); val detail = f.detail()
        val failure = assertFailsWith<MutationPlanFieldFailure> { f.service.preview(f.token, f.input) }
        assertEquals("field_not_editable", failure.code); assertEquals(listOf("source_payload"), failure.fields)
        assertEquals(detail, f.detail()); assertEquals(before, f.state()); assertEquals(0, f.store.writes)
        val unchanged = f.service.preview(f.token, JsonObject(f.input + ("patch" to JsonObject(emptyMap()))))
        val item = unchanged.getValue("items").jsonArray.single()
        assertEquals(0, item.jsonObject.getValue("display").jsonObject.getValue("amount").jsonPrimitive.content.toBigDecimal().signum())
        assertEquals(before, f.state())
    }

    private fun finalizationFailure(name: String, expectedParts: Int) {
        val f = Fixture(name); val before = f.state(); val request = MutationPlanRequest.parse(f.input)
        f.database.read { connection ->
            val prepared = MutationPlanBuilder(CashSqlContext(connection, f.workspace, f.database.sqlite, now = { at }))
                .prepareMaintenanceOperation(request)
            val values = prepared.getValue("values") as Map<*, *>
            assertEquals("posted", values["state"])
            val parts = values["components"] as List<*>; assertEquals(expectedParts, parts.size)
            if (expectedParts == 2) assertEquals(listOf("-5", "-7.5"), parts.map { (it as Map<*, *>)["amount"] })
        }
        f.fault.armed = true
        try { assertSame(f.fault.failure, assertFailsWith<IllegalStateException> { f.service.preview(f.token, f.input) }) }
        finally { f.fault.armed = false }
        assertEquals(1, f.fault.attempts); assertEquals(before, f.state())
        assertEquals(0, f.store.writes); assertTrue(f.store.entries.isEmpty())
    }
    @Test fun discoveryFailureStopsSinglePartFinalizationBeforePublishingAnyProblemOrPlan() =
        finalizationFailure("finalize_discovery", 1)
    @Test fun discoveryFailureStopsExplicitMultiplePartsBeforePublishingAnyProblemOrPlan() =
        finalizationFailure("component_discovery", 2)

    @Test fun importDiscoveryFailureKeepsTheOriginalSessionAndPublishesNoPlanOrPermanentResult() {
        val f = Fixture("import_discovery"); val before = f.state()
        val original = ParsedImportCodec().decode(f.case.getValue("session_id").jsonPrimitive.content,
            f.case.getValue("session").toString().encodeToByteArray())
        val staged = f.store.create(f.workspace, f.user, original.files)
        val raw = f.store.entries.getValue(staged.sessionId).content.copyOf(); val writes = f.store.writes
        val service = CashImportHttpService(f.database, f.store)
        @Suppress("UNCHECKED_CAST") val input = cashSourceValue(f.input) as Map<String, Any?>
        f.fault.armed = true
        try { assertSame(f.fault.failure, assertFailsWith<IllegalStateException> { service.preview(f.token, staged.sessionId, 1, null, input) }) }
        finally { f.fault.armed = false }
        assertEquals(1, f.fault.attempts); assertEquals(before, f.state()); assertEquals(writes, f.store.writes)
        assertEquals(1, f.store.entries.size); assertContentEquals(raw, f.store.entries.getValue(staged.sessionId).content)
        val current = f.store.get(staged.sessionId, f.workspace, f.user)
        assertEquals(1.toBigInteger(), current.revision); assertNull(current.plan)
    }
}
