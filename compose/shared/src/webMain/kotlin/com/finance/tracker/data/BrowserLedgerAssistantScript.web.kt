@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.data

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.js.JsAny

internal actual suspend fun runLedgerAssistantScript(
    code: String,
    input: JsonObject,
    host: LedgerAssistantScriptHost,
): JsonElement {
    val program = ledgerAssistantScriptProgram(code, input)
    return withTimeoutOrNull(LEDGER_SCRIPT_TIMEOUT_MS) {
        val events = Channel<ScriptEvent>(capacity = 128)
        val worker = openScriptWorker(program, LEDGER_SCRIPT_TIMEOUT_MS.toInt(), LEDGER_SCRIPT_HEAP_BYTES.toInt()) {
                kind, id, body ->
            if (events.trySend(ScriptEvent(kind, id, body)).isFailure) {
                events.close(LedgerAssistantScriptFailure("too_many_calls"))
            }
        }
        try {
            var result: String? = null
            while (result == null) {
                val event = events.receive()
                when (event.kind) {
                    "hostRequest" -> answerScriptWorker(worker, event.id, ledgerAssistantScriptHostResponse(event.body, host))
                    "result" -> result = event.body
                    "timeout" -> throw LedgerAssistantScriptFailure("timeout")
                    else -> throw LedgerAssistantScriptFailure("script_failed")
                }
            }
            ledgerAssistantScriptResult(result)
        } finally {
            stopScriptWorker(worker)
            events.cancel()
        }
    } ?: throw LedgerAssistantScriptFailure("timeout")
}

private data class ScriptEvent(val kind: String, val id: Int, val body: String)

@JsFun("""(program, timeoutMs, heapBytes, accept) => {
    const worker = new Worker('/assistant-script-worker/worker.mjs', { type: 'module' });
    worker.onmessage = ({ data }) => accept(data.kind, data.id ?? 0, data.body ?? '');
    worker.onerror = () => accept('failed', 0, '');
    worker.onmessageerror = () => accept('failed', 0, '');
    worker.postMessage({ kind: 'run', program, timeoutMs, heapBytes });
    return worker;
}""")
private external fun openScriptWorker(
    program: String,
    timeoutMs: Int,
    heapBytes: Int,
    accept: (String, Int, String) -> Unit,
): JsAny

@JsFun("(worker, id, body) => worker.postMessage({ kind: 'hostResult', id, body })")
private external fun answerScriptWorker(worker: JsAny, id: Int, body: String)

@JsFun("(worker) => worker.terminate()")
private external fun stopScriptWorker(worker: JsAny)
