@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker

import com.finance.tracker.data.createWebLedgerFactCache
import com.finance.tracker.data.LedgerStorage
import com.finance.tracker.data.ledgerOfflinePrincipal
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.UserDto
import com.finance.tracker.business.contracts.WorkspaceDto
import com.finance.tracker.business.contracts.Role
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.await
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.test.*

class WebLedgerFactCacheTest {
    @Test fun existingOnlyRejectsAnEmptyMainFileWithoutReinitializingIt() = runTest {
        val scope = LedgerCacheScope("https://api.example.invalid", "empty-${Random.nextLong()}", "workspace")
        prepareEmptyDatabase(scope.apiOrigin, scope.principal, scope.workspaceId).await()
        var accidentallyOpened: LedgerStorage? = null
        try {
            assertFails { accidentallyOpened = createWebLedgerFactCache(scope, existingOnly = true) }
            assertUnchangedEmptyDatabase(scope.apiOrigin, scope.principal, scope.workspaceId).await()
            // A normal online owner can still explicitly initialize this synthetic empty file.
            val created = createWebLedgerFactCache(scope)
            try { assertNull(created.read(scope)) } finally { created.clear(scope) }
        } finally {
            clearEmptyDatabaseProbe()
            accidentallyOpened?.clear(scope)
            eraseSyntheticStorage(scope.apiOrigin, scope.principal, scope.workspaceId).await()
        }
    }

    @Test fun existingOnlyNeverCreatesMissingFactsOrKeyMaterial() = runTest {
        val scope = LedgerCacheScope("https://api.example.invalid", "existing-${Random.nextLong()}", "workspace")
        assertStorageMaterials(scope.apiOrigin, scope.principal, scope.workspaceId, false).await()
        repeat(2) { assertFails { createWebLedgerFactCache(scope, existingOnly = true) } }
        assertStorageMaterials(scope.apiOrigin, scope.principal, scope.workspaceId, false).await()
        val created = createWebLedgerFactCache(scope)
        val expected = localCashLedgerOwnerSnapshot()
        try { created.replace(scope, expected) } finally { created.close() }
        val restored = createWebLedgerFactCache(scope, existingOnly = true)
        try { assertEquals(expected, restored.read(scope)) } finally { restored.clear(scope) }
        assertStorageMaterials(scope.apiOrigin, scope.principal, scope.workspaceId, false).await()
        assertFails { createWebLedgerFactCache(scope, existingOnly = true) }
        assertStorageMaterials(scope.apiOrigin, scope.principal, scope.workspaceId, false).await()
    }

    @Test fun encryptedSessionRecordRequiresCommittedFactsAndItsOriginalToken() = runTest {
        val session = SessionDto(UserDto("synthetic-${Random.nextLong()}@example.test"), "workspace",
            listOf(WorkspaceDto("workspace", "Synthetic workspace", Role.EDITOR)))
        val scope = LedgerCacheScope("https://api.example.invalid", ledgerOfflinePrincipal(session.user.email), "workspace")
        val token = "synthetic-private-token"
        val prepared = createWebLedgerFactCache(scope)
        try {
            assertNull(prepared.readOfflineSession(scope, token))
            assertFails { prepared.saveOfflineSession(scope, token, session) }
            prepared.replace(scope, localCashLedgerOwnerSnapshot())
            prepared.saveOfflineSession(scope, token, session)
        } finally { prepared.close() }
        val cache = createWebLedgerFactCache(scope, existingOnly = true)
        try {
            assertFails { prepared.clear(scope) }
            assertEquals(session, cache.readOfflineSession(scope, token))
            assertNull(cache.readOfflineSession(scope, token + "changed"))
            assertFails { cache.saveOfflineSession(scope, token, session.copy(user = UserDto("other@example.test"))) }
            assertEquals(session, cache.readOfflineSession(scope, token))
        } finally { cache.clear(scope) }
        assertFails { cache.readOfflineSession(scope, token) }
        val fresh = createWebLedgerFactCache(scope)
        try { assertNull(fresh.readOfflineSession(scope, token)) } finally { fresh.clear(scope) }
    }

    @Test fun actualEncryptedSqlDelightFactoryPreservesFactsAndClearsItsScope() = runTest {
        val scope = LedgerCacheScope("https://api.example.invalid", "synthetic-${Random.nextLong()}", "workspace")
        val cache = createWebLedgerFactCache(scope)
        println(browserInfo())
        try {
            assertNull(cache.read(scope))
            val base = localCashLedgerOwnerSnapshot(sequence = "9223372036854775807")
            val tokens = Json.parseToJsonElement("""[9007199254740993,18446744073709551616,-0,1.2300,1e400]""")
            val expected = base.copy(facts = base.facts.copy(cashTransactions = base.facts.cashTransactions.map {
                it.copy(amount = "-12345678901234567890.123456789012345678", sourcePayload = tokens)
            }))
            cache.replace(scope, expected)
            assertEquals(expected, cache.read(scope))
            assertFailsWith<IllegalArgumentException> { cache.read(scope.copy(principal = "other")) }
            assertFailsWith<IllegalArgumentException> { cache.replace(scope, expected.copy(workspaceId = "other")) }
            assertEquals(expected, cache.read(scope))
        } finally { cache.clear(scope) }
        cache.clear(scope)
        assertFails { cache.read(scope) }
        val fresh = createWebLedgerFactCache(scope)
        try { assertNull(fresh.read(scope)) } finally { fresh.clear(scope) }
    }

    @Test fun cancellingWhileTheRealWorkerOpensDoesNotLeakItsScopeLease() = runTest {
        val scope = LedgerCacheScope("https://api.example.invalid", "cancelled-${Random.nextLong()}", "workspace")
        val held = holdNextOpen()
        val opening = launch { createWebLedgerFactCache(scope) }
        try {
            held.await()
            opening.cancel()
            releaseHeldOpen()
            opening.join()
            assertTrue(opening.isCancelled)
            val recovered = createWebLedgerFactCache(scope)
            try { assertNull(recovered.read(scope)) } finally { recovered.clear(scope) }
        } finally { releaseHeldOpen(); opening.cancelAndJoin() }
    }

    @Test fun completedSqlRequestsDoNotResumeAgainAndFutureReadsFailAfterARealWorkerCrash() = runTest {
        val scope = LedgerCacheScope("https://api.example.invalid", "crashed-${Random.nextLong()}", "workspace")
        installFaultWorker()
        val cache = try { createWebLedgerFactCache(scope) }
        catch (failure: Throwable) { restoreFaultWorker(); throw failure }
        var reading: Job? = null
        try {
            cache.replace(scope, localCashLedgerOwnerSnapshot())
            assertNotNull(cache.read(scope))
            val held = holdNextSqlRequest()
            val active = async { runCatching { cache.read(scope) } }
            reading = active
            held.await()
            crashActualWorker().await()
            actualBrowserBudget().await()
            assertTrue(active.isCompleted, "A Worker crash must reject the active read")
            assertTrue(active.await().isFailure)
            val future = async { runCatching { cache.read(scope) } }
            reading = future
            actualBrowserBudget().await()
            assertEquals(0, uncaughtWorkerErrors(), workerErrorSummary())
            assertTrue(future.isCompleted, "A dead Worker must reject the next read instead of leaving it pending")
            assertTrue(future.await().isFailure)
        } finally {
            reading?.cancelAndJoin()
            restoreFaultWorker()
            withContext(NonCancellable) {
                assertFails { cache.clear(scope) }
                eraseSyntheticStorage(scope.apiOrigin, scope.principal, scope.workspaceId).await()
            }
        }
    }
}

private fun browserInfo(): String = js("navigator.userAgent + '; ' + location.href + '; ' + innerWidth + 'x' + innerHeight")

private fun clearEmptyDatabaseProbe(): Unit = js("(function() { delete globalThis.__ftEmptyLedgerKeyDigest; })()")

private fun prepareEmptyDatabase(apiOrigin: String, principal: String, workspaceId: String): Promise<JsAny?> = js("""(function() {
    const scope = [apiOrigin, principal, workspaceId];
    return import('/ledger-key-vault.mjs').then(vault =>
        vault.createWebLedgerKey(scope).then(key => crypto.subtle.digest('SHA-256', key)
            .then(digest => { globalThis.__ftEmptyLedgerKeyDigest = Array.from(new Uint8Array(digest)).join(','); })
            .finally(() => key.fill(0))).then(() => vault.webLedgerKeyId(scope)))
        .then(id => navigator.storage.getDirectory()
            .then(root => root.getDirectoryHandle('ft-ledger-cache-v1', { create: true }))
            .then(parent => parent.getDirectoryHandle(id, { create: true }))
            .then(folder => folder.getFileHandle('ledger.db', { create: true })))
        .then(file => file.getFile()).then(file => {
            if (file.size !== 0) throw new Error('Synthetic file must be empty');
            return null;
        });
})()""")

private fun assertUnchangedEmptyDatabase(apiOrigin: String, principal: String, workspaceId: String): Promise<JsAny?> = js("""(function() {
    const scope = [apiOrigin, principal, workspaceId];
    return import('/ledger-key-vault.mjs').then(vault =>
        vault.readWebLedgerKey(scope).then(key => {
            if (key === null) throw new Error('Original key must remain');
            return crypto.subtle.digest('SHA-256', key).then(bytes => {
                const digest = Array.from(new Uint8Array(bytes)).join(',');
                if (digest !== globalThis.__ftEmptyLedgerKeyDigest) throw new Error('Original key changed');
            }).finally(() => key.fill(0));
        }).then(() => vault.webLedgerKeyId(scope)))
        .then(id => navigator.storage.getDirectory()
            .then(root => root.getDirectoryHandle('ft-ledger-cache-v1'))
            .then(parent => parent.getDirectoryHandle(id))
            .then(folder => folder.getFileHandle('ledger.db')))
        .then(file => file.getFile()).then(file => {
            if (file.size !== 0) throw new Error('Empty file was reinitialized');
            return null;
        });
})()""")

private fun holdNextOpen(): Promise<JsAny?> = js("""(function() {
    const original = Worker.prototype.postMessage;
    const barrier = { restore: () => { Worker.prototype.postMessage = original; } };
    const ready = new Promise(resolve => { barrier.resolve = resolve; });
    Worker.prototype.postMessage = function(message, ...extra) {
        if (message.action === 'open') {
            barrier.release = () => { barrier.restore(); original.call(this, message, ...extra); };
            barrier.resolve(null);
        } else original.call(this, message, ...extra);
    };
    globalThis.__ftLedgerOpenBarrier = barrier;
    return ready;
})()""")

private fun releaseHeldOpen(): Unit = js("""(function() {
    const barrier = globalThis.__ftLedgerOpenBarrier;
    if (barrier) { barrier.restore(); if (barrier.release) barrier.release(); delete globalThis.__ftLedgerOpenBarrier; }
})()""")

private fun installFaultWorker(): Unit = js("""(function() {
    const original = globalThis.Worker;
    const probe = { original, errors: [] };
    probe.capture = event => { probe.errors.push(event.message); event.preventDefault(); event.stopImmediatePropagation(); };
    window.addEventListener('error', probe.capture, true);
    globalThis.Worker = function(url, options) {
        const target = new URL(url, location.href);
        if (target.pathname === '/ledger-storage/worker.js') {
            target.pathname = '/ledger-storage/ledger-worker-fault.js';
            probe.worker = new original(target, options);
            globalThis.Worker = original;
            return probe.worker;
        }
        return new original(url, options);
    };
    globalThis.Worker.prototype = original.prototype;
    globalThis.__ftSdkWorkerProbe = probe;
})()""")

private fun holdNextSqlRequest(): Promise<JsAny?> = js("""new Promise(resolve => {
    const worker = globalThis.__ftSdkWorkerProbe.worker;
    const held = event => {
        if (event.data.qaHeld) { worker.removeEventListener('message', held); resolve(null); }
    };
    worker.addEventListener('message', held);
    worker.postMessage({ qaHoldNext: true });
})""")

private fun crashActualWorker(): Promise<JsAny?> = js("""new Promise(resolve => {
    const worker = globalThis.__ftSdkWorkerProbe.worker;
    worker.addEventListener('error', event => { event.preventDefault(); resolve(null); }, { once: true });
    worker.postMessage({ qaCrash: true });
})""")

private fun actualBrowserBudget(): Promise<JsAny?> = js("new Promise(resolve => setTimeout(() => resolve(null), 400))")
private fun uncaughtWorkerErrors(): Int = js("globalThis.__ftSdkWorkerProbe.errors.length")
private fun workerErrorSummary(): String = js("JSON.stringify(globalThis.__ftSdkWorkerProbe.errors)")
private fun restoreFaultWorker(): Unit = js("""(function() {
    const probe = globalThis.__ftSdkWorkerProbe;
    if (probe) { globalThis.Worker = probe.original; window.removeEventListener('error', probe.capture, true); delete globalThis.__ftSdkWorkerProbe; }
})()""")

private fun eraseSyntheticStorage(apiOrigin: String, principal: String, workspaceId: String): Promise<JsAny?> =
    js("import('/ledger-storage/connection.mjs').then(module => module.eraseWebLedgerStorage([apiOrigin, principal, workspaceId]))")
