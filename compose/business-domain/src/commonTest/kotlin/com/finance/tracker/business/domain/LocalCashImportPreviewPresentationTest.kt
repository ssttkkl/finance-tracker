package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class LocalCashImportPreviewPresentationTest {
    private fun preview(): CashImportPreviewSnapshot<CashImportPreviewInteger, Instant> {
        val source = LocalCashRelationContextTest().snapshot()
        return buildLocalCashImportPreviewSnapshot(source, readLocalCashRelationContext(source, emptySet()),
            LocalCashImportPreviewSnapshotTest().operation, JsonArray(emptyList())) { Instant.parse("2026-10-01T09:00:00Z") }
    }

    @Test fun realFactoryRendersComputedValuesAndStableTargetsWithoutMutatingInputs() {
        val snapshot = preview()
        val before = localCashImportDigest(listOf(snapshot.values, snapshot.operations, snapshot.rows))
        val diagnostic = linkedMapOf<String, Any?>("messages" to mutableListOf("cause"))
        val files = listOf(ParsedImportFile("ready", "a".repeat(64), "parsed", filename = "statement.csv"),
            ParsedImportFile("password", "b".repeat(64), "needs_password", errorCode = "password_required", diagnostic = diagnostic))
        val result = presentLocalCashImportPreview(files, snapshot, emptyList(), emptyList(), mapOf("10" to mapOf("name" to "cash")))
        val items = result["items"] as List<*>
        val display = (items.single() as Map<*, *>)["display"] as Map<*, *>
        assertEquals("confirmed-note", display["note"])
        assertTrue(display["occurred_at"] is Instant)
        assertTrue(display["amount"] is CashAmount)
        assertEquals(listOf("cash"), display["accounts"])
        val targets = result["entry_targets"] as Map<*, *>
        assertEquals(1, targets.size)
        assertEquals(targets, presentLocalCashImportPreview(files, snapshot, emptyList(), emptyList(), emptyMap())["entry_targets"])
        assertEquals(true, result["can_commit"])
        @Suppress("UNCHECKED_CAST") val publicFiles = result["file_results"] as List<Map<String, Any?>>
        assertEquals(listOf("ready", "password_required"), publicFiles.map { it["status"] })
        assertTrue(publicFiles.all { "source_type" !in it && "client_file_ref" !in it })
        @Suppress("UNCHECKED_CAST") val copied = (publicFiles.last()["diagnostic"] as Map<*, *>)["messages"] as MutableList<String>
        copied += "changed"
        assertEquals(listOf("cause"), diagnostic["messages"])
        assertEquals(before, localCashImportDigest(listOf(snapshot.values, snapshot.operations, snapshot.rows)))
    }

    @Test fun memberDetailsHideOnlyVirtualComponentIdsUsingExactIntegers() {
        val identifiers = listOf("9223372036854775807", "9223372036854775808", "18446744073709551616", "not-an-id")
        for ((index, identifier) in identifiers.withIndex()) {
            val snapshot = preview()
            val parent = snapshot.parentOps.keys.single()
            snapshot.parentOps.clear()
            val values = snapshot.values.getValue(parent)
            values["record_origin"] = "manual"
            @Suppress("UNCHECKED_CAST") val part = (values["components"] as List<Map<String, Any?>>).single().toMutableMap()
            part["component_id"] = identifier
            values["components"] = listOf(part)
            val endpoint = snapshot.components.values.single()
            snapshot.components.clear()
            snapshot.components[identifier] = endpoint.copy(componentId = identifier)
            val result = presentLocalCashImportPreview(emptyList(), snapshot, emptyList(), emptyList(), emptyMap(), entryParentIds = setOf(parent))
            val fields = (result["detail_values"] as Map<*, *>)[parent] as Map<*, *>
            val rendered = (fields["components"] as List<*>).single() as Map<*, *>
            if (index in 1..2) assertFalse("component_id" in rendered) else assertEquals(identifier, rendered["component_id"])
            assertFalse("source_scope_key" in fields)
            assertEquals(identifier, part["component_id"])
        }
    }

    @Test fun entryEdgeOrderUsesExactNumericOrderWithTheOriginalStringFallback() {
        val order = localCashImportEntryEdgeOrder
        assertTrue(order.compare("2", "10") < 0)
        assertTrue(order.compare("-10", "-2") < 0)
        assertEquals(0, order.compare("+００１０", "10"))
        assertTrue(order.compare("9223372036854775808", "34038115944072999195166050254573576354") < 0)
        assertEquals(cashRelationStringOrder.compare("x10", "2"), order.compare("x10", "2"))
        assertEquals(cashRelationStringOrder.compare("1.0", "2"), order.compare("1.0", "2"))
    }
}
