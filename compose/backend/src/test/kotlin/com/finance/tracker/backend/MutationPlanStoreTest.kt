package com.finance.tracker.backend

import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MutationPlanStoreTest {
    @Test fun contextIsOwnerScopedAndRevisionFenced() {
        var now = Instant.parse("2026-10-29T12:00:00Z")
        val store = MutationPlanStore(clock = { now })
        val context = store.create("workspace-a", "admin", "42")
        assertEquals(1, context.revision)
        assertEquals(now.plusSeconds(1800), context.expiresAt)
        assertTrue(context.id.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertEquals(context.id, store.get(context.id, "workspace-a", "admin").id)
        assertFailsWith<MutationPlanStore.Forbidden> { store.get(context.id, "workspace-a", "viewer") }
        assertFailsWith<MutationPlanStore.Forbidden> { store.get(context.id, "workspace-b", "admin") }
        assertFailsWith<MutationPlanStore.NotFound> { store.get("invalid", "workspace-a", "admin") }

        val plan = buildJsonObject { put("plan_id", store.planId(context.id)) }
        val input = buildJsonObject { put("intent", "save_draft"); put("transaction_id", "42") }
        val replaced = store.replace(context.id, "workspace-a", "admin", 1, plan, input)
        assertEquals(2, replaced.revision)
        assertEquals(plan, replaced.plan)
        assertFailsWith<MutationPlanStore.RevisionConflict> {
            store.replace(context.id, "workspace-a", "admin", 1, plan, input)
        }
        val cancelled = store.cancel(context.id, "workspace-a", "admin", 2)
        assertEquals(3, cancelled.revision)
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
            assertFailsWith<MutationPlanStore.NotFound>(invalid.toString()) { MutationPlanStore.contextId(invalid) }
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
}
