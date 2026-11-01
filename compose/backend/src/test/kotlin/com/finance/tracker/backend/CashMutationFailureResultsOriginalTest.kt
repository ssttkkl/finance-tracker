package com.finance.tracker.backend

import java.lang.reflect.Proxy
import java.math.BigInteger
import java.sql.Connection
import kotlin.test.*

class CashMutationFailureResultsOriginalTest {
    private fun writer(): CashPlanRelationWriter {
        val connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            when (method.name) {
                "isClosed" -> false
                "getAutoCommit" -> false
                "toString" -> "NoSqlContractConnection"
                else -> error("Unexpected database access: ${method.name}")
            }
        } as Connection
        return CashPlanRelationWriter(CashSqlContext(connection, "synthetic-workspace", true), "synthetic-user")
    }
    @Test fun duplicateDiscoveryFailuresHaveOneStableSafeResultAndSkipExistingGroups() {
        val failures = listOf(mapOf("code" to "relation_discovery_failed", "parent_ids" to listOf("20", "10")),
            mapOf("code" to "relation_discovery_failed", "parent_ids" to listOf("10", "20", "20")),
            mapOf("code" to "relation_discovery_failed", "parent_ids" to listOf("10", "20"), "suggestion_id" to "sg_1"))
        val service = writer(); val identity = mapOf("10" to "100", "20" to "200")
        val result = service.discoveryResults(mapOf("relation_failures" to failures), identity).single()
        assertEquals("relation", result["result_kind"]); assertEquals("failed", result["outcome"]); assertEquals("relation_discovery_failed", result["code"])
        assertEquals("100", result["transaction_id"])
        assertEquals(result, service.discoveryResults(mapOf("relation_failures" to failures.take(1)), identity).single())
    }
    @Test fun unresolvedVirtualMembersNeverExposeAVirtualTransactionReference() {
        val virtual = BigInteger.ONE.shiftLeft(63).add(BigInteger.valueOf(100L)).toString()
        val result = writer().discoveryResults(mapOf("relation_failures" to listOf(
            mapOf("code" to "relation_discovery_failed", "parent_ids" to listOf(virtual)))), emptyMap()).single()
        assertNull(result["transaction_id"]); assertFalse(virtual in result.toString())
    }
}
