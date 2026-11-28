package com.finance.tracker.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val LEDGER_SCRIPT_TIMEOUT_MS = 10_000L
internal const val LEDGER_SCRIPT_HEAP_BYTES = 32L * 1024 * 1024
private const val SCRIPT_INPUT_LIMIT = 64 * 1024
private const val SCRIPT_RESULT_LIMIT = 4 * 1024
private const val SCRIPT_BRIDGE_LIMIT = 7 * 1024 * 1024

internal class LedgerAssistantScriptFailure(val reason: String) : Exception(reason)
internal typealias LedgerAssistantScriptHost = suspend (String, JsonArray) -> JsonElement

internal fun ledgerAssistantScriptProgram(code: String, input: JsonObject): String {
    val source = JsonPrimitive(code).toString()
    val inputText = input.toString()
    if (source.encodeToByteArray().size > SCRIPT_INPUT_LIMIT ||
        inputText.encodeToByteArray().size > SCRIPT_INPUT_LIMIT
    ) throw LedgerAssistantScriptFailure("input_too_large")
    val encodedInput = JsonPrimitive(inputText)
    return """
        (async () => {
            "use strict";
            const invoke = globalThis.__ledgerInvoke;
            const publish = globalThis.__ledgerPublish;
            delete globalThis.__ledgerInvoke;
            delete globalThis.__ledgerPublish;
            const { parse, stringify } = JSON;
            const errors = new WeakMap();
            const setReason = errors.set.bind(errors);
            const getReason = errors.get.bind(errors);
            const ScriptError = Error;
            const codePointAt = Function.prototype.call.bind(String.prototype.codePointAt);
            const exceedsUtf8 = (text, limit) => {
                if (text.length > limit) return true;
                let bytes = 0;
                for (let i = 0; i < text.length; i++) {
                    const point = codePointAt(text, i);
                    bytes += point < 0x80 ? 1 : point < 0x800 ? 2 : point < 0x10000 ? 3 : 4;
                    if (bytes > limit) return true;
                    if (point >= 0x10000) i++;
                }
                return false;
            };
            const then = Function.prototype.call.bind(Promise.prototype.then);
            let queue = Promise.resolve();
            let count = 0;
            let pending = 0;
            let accepting = true;
            const fail = reason => {
                const failure = new ScriptError("Ledger host call failed");
                setReason(failure, reason);
                throw failure;
            };
            const call = async (method, ...args) => {
                if (!accepting) fail("run_finished");
                if (++count > 1024) fail("too_many_calls");
                pending++;
                try {
                    const request = stringify({ __proto__: null, method, args });
                    if (exceedsUtf8(request, $SCRIPT_BRIDGE_LIMIT)) fail("input_too_large");
                    const reply = then(queue, () => invoke(request));
                    queue = then(reply, () => undefined, () => undefined);
                    const response = parse(await reply);
                    if (response.error) fail(response.error);
                    return response.value;
                } finally {
                    pending--;
                }
            };
            const ft = Object.freeze({
                accounts: Object.freeze({ list: () => call("ft.accounts.list") }),
                categories: Object.freeze({ list: () => call("ft.categories.list") }),
                transactions: Object.freeze({
                    summarize: filter => call("ft.transactions.summarize", filter ?? {}),
                    get: id => call("ft.transactions.get", id),
                    list: async function* (filter) {
                        let cursor = null;
                        do {
                            const page = await call("ft.transactions.list", filter ?? {}, cursor);
                            yield* page.items;
                            cursor = page.next_cursor;
                        } while (cursor !== null);
                    }
                })
            });
            const fs = Object.freeze({
                list: () => call("fs.list"),
                readText: ref => call("fs.readText", ref),
                writeText: (path, text) => call("fs.writeText", path, text),
                remove: path => call("fs.remove", path)
            });
            const csv = Object.freeze({
                stringify: rows => call("csv.stringify", rows),
                parse: text => call("csv.parse", text)
            });
            const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
            try {
                const run = new AsyncFunction("input", "ft", "fs", "csv", '"use strict";\n' + $source);
                const value = await run(parse($encodedInput), ft, fs, csv);
                const result = stringify({ __proto__: null, value: value ?? null });
                if (exceedsUtf8(result, $SCRIPT_RESULT_LIMIT + 10)) fail("result_too_large");
                if (pending !== 0) fail("unawaited_host_calls");
                accepting = false;
                publish(result);
            } catch (error) {
                accepting = false;
                publish(stringify({ __proto__: null, error: getReason(error) ?? "script_failed" }));
            }
        })()
    """.trimIndent()
}

internal suspend fun ledgerAssistantScriptHostResponse(request: String, host: LedgerAssistantScriptHost): String {
    if (request.length > SCRIPT_BRIDGE_LIMIT || request.encodeToByteArray().size > SCRIPT_BRIDGE_LIMIT) {
        return """{"error":"input_too_large"}"""
    }
    val call = try {
        Json.parseToJsonElement(request) as? JsonObject
    } catch (_: SerializationException) {
        null
    }
    val method = (call?.get("method") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val args = call?.get("args") as? JsonArray
    if (method == null || args == null || call.keys != setOf("method", "args")) {
        return """{"error":"invalid_request"}"""
    }
    val response = try {
        JsonObject(mapOf("value" to host(method, args))).toString()
    } catch (failure: LedgerAssistantScriptFailure) {
        JsonObject(mapOf("error" to JsonPrimitive(failure.reason))).toString()
    }
    return if (response.encodeToByteArray().size <= SCRIPT_BRIDGE_LIMIT) response else """{"error":"result_too_large"}"""
}

internal fun ledgerAssistantScriptResult(response: String): JsonElement {
    if (response.length > SCRIPT_RESULT_LIMIT + 10 || response.encodeToByteArray().size > SCRIPT_RESULT_LIMIT + 10) {
        throw LedgerAssistantScriptFailure("result_too_large")
    }
    val result = Json.parseToJsonElement(response) as JsonObject
    (result["error"] as? JsonPrimitive)?.let { throw LedgerAssistantScriptFailure(it.content) }
    return result.getValue("value")
}

internal expect suspend fun runLedgerAssistantScript(
    code: String,
    input: JsonObject,
    host: LedgerAssistantScriptHost,
): JsonElement
