package com.finance.tracker.cli

import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.client.ClientAccessOperations
import com.finance.tracker.client.ClientHttp
import com.finance.tracker.client.ClientHttpBody
import com.finance.tracker.client.ClientHttpResponse
import com.finance.tracker.client.ClientNetworkUnavailable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise

@JsModule("./regular-host.mjs")
private external object BenchmarkRuntime {
    fun startRegularHost(): Promise<dynamic>
    fun nowMs(): Double
    fun beginOperationMemory()
    fun endOperationMemory(): Double
    fun recordSample(encoded: String)
}

@JsModule("./host.mjs")
private external object BenchmarkHttp {
    fun request(origin: String, method: String, path: String, token: String?, body: String?, headers: dynamic): Promise<dynamic>
}

internal val process: dynamic = js("globalThis.process")

internal class CliFailure(val code: String, val parameter: String? = null) : Exception(code)

internal suspend fun <T> Promise<T>.value(): T = suspendCoroutine { continuation ->
    then({ continuation.resume(it) }, { continuation.resumeWithException(it) })
}

internal val benchmarkJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun encodeComponent(input: String): String = js("encodeURIComponent(input)")

internal fun realHttp(origin: String): ClientHttp = ClientHttp { call ->
    val headers = js("Object.create(null)")
    for ((key, value) in call.headers) headers[key] = value
    val body = when (val input = call.body) {
        is ClientHttpBody.Json -> input.text.also { headers["Content-Type"] = "application/json" }
        is ClientHttpBody.Statement -> error("These benchmark transport operations use JSON")
        null -> null
    }
    val result = BenchmarkHttp.request(origin, call.method, call.path, call.token, body, headers).value()
    val status = result.status as Int
    if (status == 0) throw ClientNetworkUnavailable()
    check(status > 0) { "Unexpected benchmark transport status" }
    ClientHttpResponse(status, result.body as String)
}

internal class Sample(val index: Int) {
    internal var elapsed: Double? = null
        private set
    internal var peakRss: Double? = null
        private set
    internal var serverElapsed: Double? = null
        private set
    internal var fixtureSummary: JsonObject? = null
        private set

    fun recordFixture(summary: JsonObject) {
        check(fixtureSummary == null)
        fixtureSummary = summary
    }

    suspend fun <T> measure(block: suspend () -> T): T {
        check(elapsed == null) { "A single sample must measure exactly one operation" }
        BenchmarkRuntime.beginOperationMemory()
        val start = BenchmarkRuntime.nowMs()
        try {
            return block()
        } finally {
            elapsed = BenchmarkRuntime.nowMs() - start
            peakRss = BenchmarkRuntime.endOperationMemory()
        }
    }

    fun recordBackendDuration(ms: Double) {
        check(serverElapsed == null && elapsed != null && ms.isFinite() && ms > 0.0)
        serverElapsed = ms
    }
}

internal class Suite(
    internal val host: dynamic,
    val origin: String,
    val ownerId: String,
    internal val fixture: dynamic,
) {
    private val selection = (process.env.FT_PERF_SELECT as? String)?.split(",")?.toSet()
    val workspace: String = fixture.workspaces[0] as String
    val emptyWorkspace: String = fixture.workspaces[1] as String
    val categoryWorkspace: String = fixture.workspaces[2] as String
    val account: String get() = accountId(workspace)
    val http = realHttp(origin)
    val access = ClientAccessOperations(http, ::encodeComponent)
    val password = "perf-only synthetic password 832"
    val ownerEmail = "perf-owner@example.test"

    fun accountId(workspaceId: String, offset: Int = 0): String =
        when (workspaceId) {
            workspace -> fixture.accounts[offset]
            emptyWorkspace -> fixture.emptyAccounts[offset]
            categoryWorkspace -> fixture.categoryAccounts[offset]
            else -> error("No benchmark accounts for the workspace")
        } as String

    fun fixturePath(name: String): String = host.fixturePath(name) as String

    fun stateSlot(name: String = "") { host.stateSlot(name) }

    fun count(kind: String, workspaceId: String = workspace): Int = host.count(kind, workspaceId) as Int

    fun markRequests(): Int = host.requestMark() as Int

    fun requestCount(mark: Int, method: String, path: String): Int = host.requestCount(mark, method, path) as Int

    fun writeCount(mark: Int): Int = host.writeCount(mark) as Int

    suspend fun select(workspace: String): SessionDto = withClient(origin) { it.selectWorkspace(workspace) }

    fun includesGroup(group: String): Boolean = selection?.any { it.startsWith("$group.") } ?: true

    suspend fun scenario(id: String, run: suspend Sample.() -> Unit) {
        if (selection != null && id !in selection) return
        val diagnostic = process.env.FT_PERF_DIAGNOSTIC == "1"
        val warmups = if (diagnostic) 0 else 5
        val count = if (diagnostic) 1 else 30
        repeat(warmups + count) { iteration ->
            val sample = Sample(iteration)
            try {
                sample.run()
                val elapsed = checkNotNull(sample.elapsed) { "No operation was timed" }
                check(elapsed.isFinite() && elapsed > 0.0)
                BenchmarkRuntime.recordSample(buildJsonObject {
                    put("id", id)
                    put("asserted", true)
                    if (iteration < warmups) {
                        put("warmup", true)
                    } else {
                        put("ms", sample.serverElapsed ?: elapsed)
                        put("rssBytes", checkNotNull(sample.peakRss))
                        sample.serverElapsed?.let { put("clientMs", elapsed) }
                    }
                    sample.fixtureSummary?.let { put("fixture", it) }
                }.toString())
            } catch (failure: Throwable) {
                throw IllegalStateException("Benchmark $id failed on iteration $iteration", failure)
            } finally {
                stateSlot()
            }
        }
    }
}

suspend fun main() {
    val host = BenchmarkRuntime.startRegularHost().value()
    try {
        host.stateSlot("")
        val origin = host.origin as String
        val session = withClient(origin, createKey = true) {
            it.register("perf-owner@example.test", "perf-only synthetic password 832")
        }
        val initialWorkspace = session.activeWorkspaceId ?: withClient(origin) {
            checkNotNull(it.createWorkspace("Perf main workspace").activeWorkspaceId)
        }
        val fixture = host.seed(session.user.email, initialWorkspace)
        val suite = Suite(host, origin, fixture.owner as String, fixture)
        if (suite.includesGroup("session")) suite.sessionBenchmarks()
        if (suite.includesGroup("account")) suite.accountBenchmarks()
        if (suite.includesGroup("cash")) suite.cashBenchmarks()
        if (suite.includesGroup("category")) suite.categoryBenchmarks()
        if (suite.includesGroup("import")) suite.importBenchmarks()
    } finally {
        host.close().unsafeCast<Promise<Unit>>().value()
    }
}
