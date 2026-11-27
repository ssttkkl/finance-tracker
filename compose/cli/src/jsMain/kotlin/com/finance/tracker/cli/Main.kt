package com.finance.tracker.cli

import com.finance.tracker.business.domain.CashImportPlanError
import com.finance.tracker.business.domain.CashProjectionDomainFailure
import com.finance.tracker.client.ClientNetworkUnavailable
import com.finance.tracker.client.ClientOperationFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal val process: dynamic = js("globalThis.process")

internal class CliFailure(val code: String, val parameter: String? = null, val exitCode: Int = 2) : Exception(code)

internal data class TerminalResult(val value: JsonElement, val text: String)

internal val outputJson = Json { encodeDefaults = true }

private val rootHelp = """
    Usage: ft [--json] <command>
      ft auth
      ft workspace
      ft cash
      ft account
      ft category
      ft import
    Options: --help, --version
    Set FT_CLI_SERVER or append --server URL to a command.
""".trimIndent()

suspend fun main() {
    val args = process.argv.unsafeCast<Array<String>>().drop(2)
    val machine = "--json" in args
    try {
        val result = dispatch(args.filterNot { it == "--json" })
        println(if (machine) result.value.toString() else result.text)
    } catch (failure: CliFailure) {
        report(machine, failure.code, failure.exitCode, failure.parameter)
    } catch (failure: ClientOperationFailure) {
        report(machine, failure.code, 1, status = failure.status, commitState = failure.commitState, operation = failure.operationId)
    } catch (_: ClientNetworkUnavailable) {
        report(machine, "network_unavailable", 1)
    } catch (failure: CashProjectionDomainFailure) {
        report(machine, failure.code, 1)
    } catch (failure: CashImportPlanError) {
        report(machine, failure.code, 1, commitState = failure.commitState)
    } catch (failure: Throwable) {
        process.stderr.write("ft: system_error (${failure::class.simpleName})\n")
        report(machine, "system_error", 1)
    }
}

private fun report(
    machine: Boolean, code: String, exitCode: Int, parameter: String? = null, status: Int? = null, commitState: String? = null,
    operation: String? = null,
) {
    process.exitCode = exitCode
    if (machine) {
        println(buildJsonObject {
            put("error", buildJsonObject {
                put("code", code)
                parameter?.let { put("parameter", it) }
                status?.let { put("status", it) }
                commitState?.let { put("commit_state", it) }
                operation?.let { put("operation_id", it) }
            })
        })
    } else {
        process.stderr.write("ft: $code${(parameter ?: operation)?.let { " ($it)" }.orEmpty()}\n")
    }
}

private suspend fun dispatch(args: List<String>): TerminalResult {
    if (args == listOf("--version")) {
        val version = process.env.FT_PACKAGE_VERSION as String
        return TerminalResult(buildJsonObject { put("version", version) }, version)
    }
    val group = args.firstOrNull()
    val action = args.getOrNull(1)
    if (args.isEmpty() || args.last() == "--help" || args.size == 1 && group in setOf("auth", "workspace", "cash", "import", "account", "category")) {
        val help = when (group) {
            "auth" -> authHelp
            "workspace" -> workspaceHelp
            "cash" -> cashHelp
            "account" -> accountHelp
            "category" -> categoryHelp
            "import" -> importHelp
            else -> rootHelp
        }
        return TerminalResult(buildJsonObject { put("help", help) }, help)
    }
    val options = args.drop(2)
    return when (group) {
        "auth" -> auth(action, options)
        "workspace" -> workspace(action, options)
        "cash" -> when (action) {
            "add", "transfer", "pending", "resume", "commit" -> cashWrite(action, options)
            "edit", "plan", "candidates", "delete", "categorize" -> cashMaintenance(action, options)
            else -> cash(action, options)
        }
        "category" -> category(action, options)
        "account" -> account(action, options)
        "import" -> cashImport(action, options)
        else -> throw CliFailure("unknown_command")
    }
}

internal fun parseOptions(args: List<String>, values: Set<String>, flags: Set<String> = emptySet()): Map<String, String> {
    val options = mutableMapOf<String, String>()
    val tokens = args.iterator()
    while (tokens.hasNext()) {
        val option = tokens.next()
        val value = when (option) {
            in values -> {
                if (!tokens.hasNext()) throw CliFailure("missing_parameter", option)
                tokens.next().also { if (it.startsWith("--")) throw CliFailure("missing_parameter", option) }
            }
            in flags -> ""
            else -> throw CliFailure("unknown_option")
        }
        if (options.put(option, value) != null) throw CliFailure("duplicate_option", option)
    }
    return options
}

internal fun Map<String, String>.required(option: String): String =
    get(option)?.takeIf { it.isNotBlank() } ?: throw CliFailure("missing_parameter", option)


internal fun positional(args: List<String>, name: String): String =
    args.firstOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("--") } ?: throw CliFailure("missing_parameter", name)

internal fun Map<String, String>.integer(option: String): Int? =
    get(option)?.let { it.toIntOrNull() ?: throw CliFailure("invalid_parameter", option) }
internal fun passwordFromStdin(options: Map<String, String>, required: Boolean = false): String? {
    if (!options.containsKey("--password-stdin") && !required) return null
    if (!options.containsKey("--password-stdin") || process.stdin.isTTY == true) throw CliFailure("missing_parameter", "--password-stdin")
    val fs = process.getBuiltinModule("node:fs")
    val buffer = process.getBuiltinModule("node:buffer").Buffer.alloc(8193)
    var total = 0
    while (total < 8193) {
        val read = fs.readSync(0, buffer, total, 8193 - total, null) as Int
        if (read == 0) break
        total += read
    }
    try {
        if (total > 8192) throw CliFailure("invalid_parameter", "--password-stdin")
        return (buffer.toString("utf8", 0, total) as String).removeSuffix("\n").removeSuffix("\r")
            .takeUnless(String::isEmpty) ?: throw CliFailure("missing_parameter", "--password-stdin")
    } finally {
        buffer.fill(0)
    }
}

internal fun Map<String, String>.revision(flag: String): Long =
    required(flag).toLongOrNull()?.takeIf { it > 0 } ?: throw CliFailure("invalid_parameter", flag)


private val exactReadIdentifiers = setOf("id", "account_id", "revision", "row_revision", "account_revision", "projection_version",
    "plan_revision", "session_revision", "category_revision", "expected_account_revision")

internal fun exactReadIdentifiers(value: JsonElement): JsonElement = when (value) {
    is JsonArray -> JsonArray(value.map(::exactReadIdentifiers))
    is JsonObject -> buildJsonObject {
        for ((key, field) in value) {
            put(key, when {
                key == "evidence" -> field
                key in exactReadIdentifiers && field is JsonPrimitive && !field.isString && field.content.toLongOrNull() != null ->
                    JsonPrimitive(field.content)
                else -> exactReadIdentifiers(field)
            })
        }
    }
    else -> value
}
