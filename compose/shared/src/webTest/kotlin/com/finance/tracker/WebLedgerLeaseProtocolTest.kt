@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class WebLedgerLeaseProtocolTest {
    @Test fun aReservedLeaseSurvivesDatabaseCloseAndMaterialErasureUntilFinalClose() = runTest {
        assertEquals("lease-retained-through-erasure", probeLease("lease-${Random.nextLong()}").await().toString())
    }
    @Test fun aReservedWorkerCannotOpenADifferentScopeOrCreateItsMaterials() = runTest {
        assertEquals("foreign-scope-rejected", probeBinding("binding-${Random.nextLong()}").await().toString())
    }
}
private fun probeLease(principal: String): Promise<JsString> =
    js("import('/ledger-storage/ledger-lease-probe.mjs').then(module => module.leaseLifecycle(['https://synthetic.example.test', principal, 'workspace']))")
private fun probeBinding(principal: String): Promise<JsString> =
    js("import('/ledger-storage/ledger-lease-probe.mjs').then(module => module.scopeBinding(['https://synthetic.example.test', principal, 'workspace']))")
