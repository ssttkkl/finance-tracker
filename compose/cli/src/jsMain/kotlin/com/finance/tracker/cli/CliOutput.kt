package com.finance.tracker.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

internal class TerminalResult private constructor(private val output: suspend (Boolean) -> Unit) {
    constructor(text: String, json: () -> String) : this({ machine -> println(if (machine) json() else text) })
    constructor(value: JsonElement, text: String) : this(text, { value.toString() })
    constructor(text: String, jsonParts: List<String>) : this({ machine ->
        if (machine) {
            for (part in jsonParts) writeTerminalPart(part)
            writeTerminalPart("\n")
        } else println(text)
    })

    suspend fun print(machine: Boolean) = output(machine)
}

private suspend fun writeTerminalPart(part: String) = suspendCoroutine<Unit> { continuation ->
    process.stdout.write(part) { error: dynamic ->
        if (error == null) continuation.resume(Unit)
        else continuation.resumeWithException(IllegalStateException("stdout write failed"))
    }
}

internal val outputJson = Json { encodeDefaults = true }

internal val exactReadIdentifierFields = setOf("id", "account_id", "revision", "row_revision", "account_revision", "projection_version",
    "plan_revision", "session_revision", "category_revision", "expected_account_revision")

internal fun exactReadIdentifiers(value: JsonElement): JsonElement = when (value) {
    is JsonArray -> {
        var changed: MutableList<JsonElement>? = null
        for ((index, item) in value.withIndex()) {
            val exact = exactReadIdentifiers(item)
            if (exact === item) continue
            if (changed == null) changed = value.toMutableList()
            changed[index] = exact
        }
        changed?.let(::JsonArray) ?: value
    }
    is JsonObject -> {
        var changed: MutableMap<String, JsonElement>? = null
        for ((key, field) in value) {
            val exact = when {
                key == "evidence" -> field
                key in exactReadIdentifierFields && field is JsonPrimitive && !field.isString && field.content.toLongOrNull() != null ->
                    JsonPrimitive(field.content)
                else -> exactReadIdentifiers(field)
            }
            if (exact === field) continue
            if (changed == null) changed = value.toMutableMap()
            changed[key] = exact
        }
        changed?.let(::JsonObject) ?: value
    }
    else -> value
}
