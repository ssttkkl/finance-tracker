package com.finance.tracker.backend

import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MutationPlanStoreTest {
    @Test fun contextIsOwnerScopedAndRevisionFenced() {
        var now = Instant.parse("2026-10-29T12:00:00Z")
        val store = MutationPlanStore(clock = { now })
        val context = store.create("workspace-a", "admin", "42")
        assertEquals(java.math.BigInteger.ONE, context.revision)
        assertEquals(now.plusSeconds(1800), context.expiresAt)
        assertTrue(context.id.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertEquals(context.id, store.get(context.id, "workspace-a", "admin").id)
        assertFailsWith<MutationPlanStore.Forbidden> { store.get(context.id, "workspace-a", "viewer") }
        assertFailsWith<MutationPlanStore.Forbidden> { store.get(context.id, "workspace-b", "admin") }
        assertFailsWith<MutationPlanStore.NotFound> { store.get("invalid", "workspace-a", "admin") }

        val plan = buildJsonObject { put("plan_id", store.planId(context.id)) }
        val input = buildJsonObject { put("intent", "save_draft"); put("transaction_id", "42") }
        val replaced = store.replace(context.id, "workspace-a", "admin", 1, plan, input)
        assertEquals(java.math.BigInteger.TWO, replaced.revision)
        assertEquals(plan, replaced.plan)
        assertFailsWith<MutationPlanStore.RevisionConflict> {
            store.replace(context.id, "workspace-a", "admin", 1, plan, input)
        }
        val cancelled = store.cancel(context.id, "workspace-a", "admin", 2)
        assertEquals(java.math.BigInteger.valueOf(3), cancelled.revision)
        assertTrue(cancelled.cancelled)
        assertNull(cancelled.plan)
        assertEquals(cancelled, store.cancel(context.id, "workspace-a", "admin", 3))
        assertFailsWith<MutationPlanStore.Closed> {
            store.replace(context.id, "workspace-a", "admin", 3, plan, input)
        }
        now = now.plusSeconds(1800)
        assertFailsWith<MutationPlanStore.Expired> { store.get(context.id, "workspace-a", "admin") }
        assertFailsWith<MutationPlanStore.NotFound> { store.get(context.id, "workspace-a", "admin") }
    }

    @Test fun planIdentifiersAndTtlMatchTheOriginalProtocol() {
        assertEquals(43, MutationPlanStore.secureUrlToken(32).length)
        assertEquals(16, MutationPlanStore.secureUrlToken(12).length)
        val context = "a".repeat(43)
        val plan = "b".repeat(16)
        assertEquals("mp_$context.$plan", MutationPlanStore(ttlSeconds = 1800).planId(context, plan))
        assertEquals(context, MutationPlanStore.contextId("mp_$context.$plan"))
        for (invalid in listOf(null, "", "ip_$context.$plan", "mp_${"a".repeat(42)}.$plan", "mp_$context.${"b".repeat(15)}")) {
            assertFailsWith<MutationPlanFailure>(invalid.toString()) { MutationPlanStore.contextId(invalid) }
        }
        assertFailsWith<IllegalArgumentException> { MutationPlanStore(ttlSeconds = 0) }
        assertFailsWith<IllegalArgumentException> { MutationPlanStore(ttlSeconds = 1801) }
        assertEquals(1800, MutationPlanStore.ttlFromEnvironment(null))
        assertEquals(90, MutationPlanStore.ttlFromEnvironment("90"))
        assertFailsWith<IllegalArgumentException> { MutationPlanStore.ttlFromEnvironment("1801") }
        assertFailsWith<IllegalArgumentException> { MutationPlanStore.ttlFromEnvironment("not-a-number") }
    }

    @Test fun ownerAndTransactionInputsAreBounded() {
        val store = MutationPlanStore()
        assertFailsWith<IllegalArgumentException> { store.create("", "admin", "42") }
        assertFailsWith<IllegalArgumentException> { store.create("workspace", "x".repeat(129), "42") }
        for (invalid in listOf("", "0", "-1", "abc", "9223372036854775808")) {
            assertFailsWith<IllegalArgumentException>(invalid) { store.create("workspace", "admin", invalid) }
        }
    }

    @Test fun adaptersShareStoredPlansAndReturnDetachedJson() {
        for (staging in listOf(InMemoryParsedImportStore(), R2ParsedImportStore(R2ParsedImportStoreTest.Client(), "bucket"))) {
            val first = MutationPlanStore(staging = staging)
            val second = MutationPlanStore(staging = staging)
            val context = first.create("workspace", "admin", "42")
            val mutable = mutableMapOf("note" to JsonPrimitive("original"))
            val plan = JsonObject(mapOf("detail" to JsonObject(mutable)))
            first.replace(context.id, "workspace", "admin", 1, plan, buildJsonObject { put("intent", "save_draft") })
            mutable["note"] = JsonPrimitive("changed")
            assertEquals("original", second.get(context.id, "workspace", "admin").plan!!.getValue("detail").jsonObject.getValue("note").jsonPrimitive.content)
            val read = second.get(context.id, "workspace", "admin").plan!!
            val reread = second.get(context.id, "workspace", "admin").plan!!
            assertNotSame(read, reread)
            assertNotSame(read.getValue("detail"), reread.getValue("detail"))
            val private = staging.get(context.id, "workspace", "admin")
            assertEquals("mutation", private.workflowKind); assertTrue(private.files.isEmpty())
            assertEquals(mapOf("transaction_id" to "42", "version" to 1), private.contextData)
        }
    }

    @Test fun foreignReaderCannotRemoveExpiredContextAndWrongWorkflowCannotBecomeAPlan() {
        var now = Instant.parse("2026-10-29T12:00:00Z")
        val staging = InMemoryParsedImportStore(ttlSeconds = 1, clock = { now })
        val store = MutationPlanStore(staging = staging)
        val context = store.create("workspace", "admin", "42")
        now = now.plusSeconds(1)
        assertFailsWith<MutationPlanStore.Forbidden> { store.get(context.id, "workspace", "other") }
        assertFailsWith<MutationPlanStore.Expired> { store.get(context.id, "workspace", "admin") }
        val invalid = staging.create("workspace", "admin", emptyList(), "mutation", mapOf("transaction_id" to "42", "version" to true))
        assertFailsWith<MutationPlanFailure> { store.get(invalid.sessionId, "workspace", "admin") }
        val wrong = staging.create("workspace", "admin", listOf(ParsedImportFile("f", "a".repeat(64), "failed")))
        assertFailsWith<MutationPlanStore.NotFound> { store.get(wrong.sessionId, "workspace", "admin") }
    }
}
