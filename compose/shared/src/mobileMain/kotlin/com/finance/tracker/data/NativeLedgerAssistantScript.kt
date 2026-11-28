package com.finance.tracker.data

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal actual suspend fun runLedgerAssistantScript(
    code: String,
    input: JsonObject,
    host: LedgerAssistantScriptHost,
): JsonElement {
    val program = ledgerAssistantScriptProgram(code, input)
    return withTimeoutOrNull(LEDGER_SCRIPT_TIMEOUT_MS) {
        withContext(Dispatchers.Default) {
            try {
                val engine = QuickJs.create(Dispatchers.Default)
                try {
                    engine.memoryLimit = LEDGER_SCRIPT_HEAP_BYTES
                    engine.evaluationTimeoutMillis = LEDGER_SCRIPT_TIMEOUT_MS
                    val hostLock = Mutex()
                    engine.defineBinding("__ledgerInvoke", AsyncFunctionBinding<String> { args ->
                        hostLock.withLock { ledgerAssistantScriptHostResponse(args.single() as String, host) }
                    })
                    val published = CompletableDeferred<String>()
                    engine.defineBinding("__ledgerPublish", FunctionBinding<String> { args ->
                        published.complete(args.single() as String)
                        ""
                    })
                    engine.evaluate<Any?>(program)
                    ledgerAssistantScriptResult(published.await())
                } finally {
                    engine.close()
                }
            } catch (_: QuickJsInterruptedException) {
                throw LedgerAssistantScriptFailure("timeout")
            } catch (_: QuickJsException) {
                throw LedgerAssistantScriptFailure("script_failed")
            }
        }
    } ?: throw LedgerAssistantScriptFailure("timeout")
}
