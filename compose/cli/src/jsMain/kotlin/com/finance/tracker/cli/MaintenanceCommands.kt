package com.finance.tracker.cli

import com.finance.tracker.business.contracts.CashAssociationPageDto
import com.finance.tracker.business.contracts.CashCategoryDeleteImpactDto
import com.finance.tracker.business.contracts.CashCategoryDeleteResultDto
import com.finance.tracker.business.contracts.CashClassificationResultDto
import com.finance.tracker.business.contracts.CashMaintenanceInput
import com.finance.tracker.business.contracts.CashMaintenancePlanDto
import com.finance.tracker.business.contracts.CashProjectionDeleteImpactDto
import com.finance.tracker.business.contracts.CashProjectionDeleteResultDto
import com.finance.tracker.business.contracts.CategoryDirectoryDto
import com.finance.tracker.business.contracts.CategoryDirectoryItemDto
import com.finance.tracker.client.cashCategoryDirectory
import com.finance.tracker.client.CashAssociationQuery
import com.finance.tracker.client.CashCategoryPatch
import com.finance.tracker.client.CashCategoryParentChange
import com.finance.tracker.client.EditCashCategory
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal val categoryHelp = """
    Usage: ft category list
           ft category create --name NAME --revision REV [--parent ID] [--description TEXT]
           ft category edit ID --revision REV [--name NAME] [--parent ID | --root] [--description TEXT | --clear-description]
           ft category reorder ID --direction DIRECTION --revision REV [--target ID]
           ft category impact ID
           ft category delete ID --revision REV --category-revision REV --usage COUNT --confirm
    Use --workspace ID to bind any action to the active workspace.
""".trimIndent()

private val scopeOptions = setOf("--workspace", "--server")

internal suspend fun cashMaintenance(action: String, args: List<String>): TerminalResult {
    val positional = args.takeWhile { !it.startsWith("--") }
    val allowed = when (action) {
        "edit" -> setOf("--input")
        "plan" -> emptySet()
        "candidates" -> setOf("--keyword", "--timezone", "--kind", "--account", "--date-from", "--date-to", "--amount", "--cursor")
        "delete" -> setOf("--ledger-seq")
        "categorize" -> setOf("--ledger-seq", "--category")
        else -> throw CliFailure("unknown_command")
    }
    val flags = when (action) {
        "delete" -> setOf("--confirm")
        "categorize" -> setOf("--clear")
        else -> emptySet()
    }
    val options = parseOptions(args.drop(positional.size), scopeOptions + allowed, flags)
    val expected = options["--workspace"]
    return withMaintenance(options["--server"]) { operations ->
        when (action) {
            "edit" -> {
                if (positional.isNotEmpty()) throw CliFailure("invalid_parameter", "--input")
                val input = decodeInput(CashMaintenanceInput.serializer(), options.required("--input"))
                maintenanceResult(operations.preview(expected, input))
            }
            "plan" -> maintenanceResult(operations.plan(expected, requiredId(positional, "PLAN")))
            "candidates" -> {
                val search = CashAssociationQuery(options["--keyword"] ?: "", options["--timezone"] ?: "UTC", options["--kind"],
                    options["--amount"], options["--account"], options["--date-from"], options["--date-to"], options["--cursor"])
                val result = operations.associationTargets(expected, requiredId(positional, "TRANSACTION_ID"), search)
                terminal(CashAssociationPageDto.serializer(), result, buildString {
                    result.items.forEach { appendLine("${it.transactionId}  ${it.state}  ${it.confirmationStatus}") }
                    result.nextCursor?.let { appendLine("Next cursor $it") }
                }.trimEnd())
            }
            "delete" -> {
                val seq = sequence(options)
                if ("--confirm" in options) {
                    val result = operations.delete(expected, positional, seq)
                    terminal(CashProjectionDeleteResultDto.serializer(), result, "Deleted ${result.transactionCount} records.")
                } else {
                    val impact = operations.deletionImpact(expected, positional, seq)
                    terminal(CashProjectionDeleteImpactDto.serializer(), impact,
                        "${impact.transactionCount} records across ${impact.projectionCount} entries. Add --confirm to delete.")
                }
            }
            else -> {
                if (options.containsKey("--category") == options.containsKey("--clear"))
                    throw CliFailure("invalid_parameter", "--category")
                val result = operations.categorize(expected, positional, sequence(options), options["--category"])
                terminal(CashClassificationResultDto.serializer(), result, "Updated ${result.updatedTransactionCount} records.")
            }
        }
    }
}

internal suspend fun category(action: String?, args: List<String>): TerminalResult {
    val id = when (action) {
        "edit", "reorder", "impact", "delete" -> positional(args, "CATEGORY_ID")
        else -> null
    }
    val allowed = when (action) {
        "list", "impact" -> emptySet()
        "create", "edit" -> setOf("--name", "--revision", "--parent", "--description")
        "reorder" -> setOf("--direction", "--revision", "--target")
        "delete" -> setOf("--revision", "--category-revision", "--usage")
        else -> throw CliFailure("unknown_command")
    }
    val flags = when (action) {
        "delete" -> setOf("--confirm")
        "edit" -> setOf("--root", "--clear-description")
        else -> emptySet()
    }
    val options = parseOptions(if (id == null) args else args.drop(1), scopeOptions + allowed, flags)
    val workspace = options["--workspace"]
    if (action == "list") return withClient(options["--server"]) { session ->
        val directory = cashCategoryDirectory(session.ledger(workspace).ledger.snapshot)
        terminal(CategoryDirectoryDto.serializer(), directory, buildString {
            appendLine("Revision ${directory.revision}")
            directory.items.forEach { appendLine("${it.id}  ${it.path.joinToString(" / ")}") }
        }.trimEnd())
    }
    return withMaintenance(options["--server"]) { operations ->
        when (action) {
            "create" -> {
                val saved = operations.createCategory(workspace, EditCashCategory(options.required("--name"), options["--parent"],
                    options["--description"].orEmpty(), nonnegative(options, "--revision")))
                terminal(CategoryDirectoryItemDto.serializer(), saved, "${saved.id}  ${saved.name}")
            }
            "edit" -> {
                if ("--root" in options && "--parent" in options || "--clear-description" in options && "--description" in options)
                    throw CliFailure("invalid_parameter")
                val parent = if ("--root" in options) CashCategoryParentChange(null) else options["--parent"]?.let(::CashCategoryParentChange)
                val description = if ("--clear-description" in options) "" else options["--description"]
                val patch = CashCategoryPatch(nonnegative(options, "--revision"), options["--name"], parent, description)
                if (patch.name == null && patch.parent == null && patch.description == null) throw CliFailure("missing_parameter", "--name")
                val saved = operations.updateCategory(workspace, checkNotNull(id), patch)
                terminal(CategoryDirectoryItemDto.serializer(), saved, "${saved.id}  ${saved.name}")
            }
            "reorder" -> {
                val result = operations.reorderCategory(workspace, checkNotNull(id), options.required("--direction"),
                    options.revision("--revision"), options["--target"])
                terminal(CategoryDirectoryItemDto.serializer(), result, "${result.id}  ${result.name}")
            }
            "impact" -> {
                val impact = operations.categoryDeletionImpact(workspace, checkNotNull(id))
                terminal(CashCategoryDeleteImpactDto.serializer(), impact,
                    "${impact.directUsageCount} records, ${impact.childCount} child categories. Revision ${impact.revision}, category revision ${impact.categoryRevision}.")
            }
            else -> {
                if ("--confirm" !in options) throw CliFailure("missing_parameter", "--confirm")
                val expectedUsage = options.integer("--usage")?.takeIf { it >= 0 } ?: throw CliFailure("invalid_parameter", "--usage")
                val result = operations.deleteCategory(workspace, checkNotNull(id), nonnegative(options, "--revision"),
                    options.revision("--category-revision"), expectedUsage)
                terminal(CashCategoryDeleteResultDto.serializer(), result, "Deleted ${result.categoryId}.")
            }
        }
    }
}

private fun sequence(options: Map<String, String>): Long = nonnegative(options, "--ledger-seq")

private fun nonnegative(options: Map<String, String>, flag: String): Long =
    options.required(flag).toLongOrNull()?.takeIf { it >= 0 } ?: throw CliFailure("invalid_parameter", flag)

private fun requiredId(values: List<String>, parameter: String): String =
    values.singleOrNull()?.takeIf(String::isNotBlank) ?: throw CliFailure("invalid_parameter", parameter)

private fun maintenanceResult(plan: CashMaintenancePlanDto) = terminal(CashMaintenancePlanDto.serializer(), plan,
    "Plan ${plan.planId}  Revision ${plan.planRevision}\n" + plan.summary.entries.joinToString("\n") { "${it.key}  ${it.value}" })

internal fun <T> terminal(serializer: KSerializer<T>, value: T, text: String) =
    TerminalResult(exactReadIdentifiers(outputJson.encodeToJsonElement(serializer, value)), text)

internal fun <T> decodeInput(serializer: KSerializer<T>, path: String): T = try {
    Json.decodeFromString(serializer, readInputText(path))
} catch (_: SerializationException) {
    throw CliFailure("invalid_parameter", "--input")
}
