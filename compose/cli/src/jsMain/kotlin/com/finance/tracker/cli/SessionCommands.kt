package com.finance.tracker.cli

import com.finance.tracker.business.contracts.AccountCommandViewDto
import com.finance.tracker.business.contracts.AccountReferenceDto
import com.finance.tracker.business.contracts.InvitationPreviewDto
import com.finance.tracker.business.contracts.Role
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.WorkspaceDto
import com.finance.tracker.business.contracts.WorkspaceMembersDto
import com.finance.tracker.client.CashAccountBalance
import com.finance.tracker.client.CashAccountType
import com.finance.tracker.client.ClientInvitation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal val authHelp = """
    Usage: ft auth <login|register|status|logout>
    For login or registration, supply --email EMAIL --password-stdin.
""".trimIndent()

internal val workspaceHelp = """
    Usage: ft workspace <list|current|use ID|create --name NAME>
           ft workspace rename --name NAME [--workspace ID]
           ft workspace delete --confirm-name NAME [--workspace ID]
           ft workspace member <list|set-role USER_ID --role ROLE|remove USER_ID>
           ft workspace invite <create --role ROLE|preview TOKEN|accept TOKEN>
""".trimIndent()

internal val accountHelp = """
    Usage: ft account list [--workspace ID]
           ft account show ACCOUNT_ID [--workspace ID]
           ft account create --name NAME --type TYPE --currency CNY[,USD] [--workspace ID]
""".trimIndent()

internal suspend fun auth(action: String?, args: List<String>): TerminalResult = when (action) {
    "login", "register" -> {
        val options = parseOptions(args, values = setOf("--email", "--server"), flags = setOf("--password-stdin"))
        val email = options.required("--email")
        val password = passwordFromStdin(options, required = true)
        withClient(options["--server"], createKey = true) { client ->
            sessionResult(if (action == "login") client.login(email, checkNotNull(password)) else client.register(email, checkNotNull(password)))
        }
    }
    "status" -> withClient(parseOptions(args, setOf("--server"))["--server"]) { sessionResult(it.status()) }
    "logout" -> withClient(parseOptions(args, setOf("--server"))["--server"]) {
        it.logout()
        TerminalResult(buildJsonObject { put("ok", true) }, "Signed out.")
    }
    else -> throw CliFailure("unknown_command")
}

private fun sessionResult(session: SessionDto): TerminalResult =
    TerminalResult(outputJson.encodeToJsonElement(SessionDto.serializer(), session), buildString {
        appendLine(session.user.email)
        for (workspace in session.workspaces) {
            val indicator = if (workspace.id == session.activeWorkspaceId) "* " else "  "
            appendLine("$indicator${workspace.id}  ${workspace.name}  ${workspace.role.name.lowercase()}")
        }
    }.trimEnd())

internal suspend fun workspace(action: String?, args: List<String>): TerminalResult {
    val nested = if (action in setOf("member", "invite")) args.firstOrNull() else null
    val cmd = if (nested == null) action else "$action $nested"
    val values = if (nested == null) args else args.drop(1)
    val id = when (cmd) {
        "use" -> positional(values, "WORKSPACE_ID")
        "member set-role", "member remove" -> positional(values, "USER_ID")
        "invite preview", "invite accept" -> positional(values, "TOKEN")
        else -> null
    }
    val allowed = when (cmd) {
        "create", "rename" -> setOf("--name")
        "delete" -> setOf("--confirm-name")
        "member set-role", "invite create" -> setOf("--role")
        "list", "current", "use", "member list", "member remove", "invite preview", "invite accept" -> emptySet()
        else -> throw CliFailure("unknown_command")
    }
    val options = parseOptions(if (id == null) values else values.drop(1), allowed + setOf("--server", "--workspace"))
    val expected = options["--workspace"]
    if (expected != null && cmd !in setOf("rename", "delete", "member list", "member set-role", "member remove", "invite create"))
        throw CliFailure("unknown_option", "--workspace")
    val role = if ("--role" in allowed) {
        val raw = options.required("--role")
        Role.entries.firstOrNull { it.name.lowercase() == raw } ?: throw CliFailure("invalid_parameter", "--role")
    } else null
    if (cmd == "invite preview") {
        val result = previewInvitation(options["--server"], checkNotNull(id))
        return TerminalResult(outputJson.encodeToJsonElement(InvitationPreviewDto.serializer(), result),
            "${result.workspace.name}  ${result.role.name.lowercase()}")
    }
    return withClient(options["--server"]) { client ->
        when (cmd) {
            "use" -> sessionResult(client.selectWorkspace(checkNotNull(id)))
            "list" -> sessionResult(client.status())
            "current" -> {
                val current = client.currentWorkspace()
                TerminalResult(outputJson.encodeToJsonElement(WorkspaceDto.serializer(), current),
                    "${current.id}  ${current.name}  ${current.role.name.lowercase()}")
            }
            "create" -> sessionResult(client.createWorkspace(options.required("--name")))
            "rename" -> sessionResult(client.renameWorkspace(expected, options.required("--name")))
            "delete" -> sessionResult(client.deleteWorkspace(expected, options.required("--confirm-name")))
            "member list" -> memberResult(client.members(expected))
            "member set-role" -> memberResult(client.setRole(expected, checkNotNull(id), checkNotNull(role)))
            "member remove" -> {
                val removed = client.removeMember(expected, checkNotNull(id))
                TerminalResult(buildJsonObject { put("ok", removed) }, if (removed) "Member removed." else "Member removal rejected.")
            }
            "invite create" -> {
                val invitation = client.createInvitation(expected, checkNotNull(role))
                TerminalResult(outputJson.encodeToJsonElement(ClientInvitation.serializer(), invitation), invitation.token)
            }
            "invite accept" -> sessionResult(client.acceptInvitation(checkNotNull(id)))
            else -> error("unreachable workspace command")
        }
    }
}

private fun memberResult(value: WorkspaceMembersDto) = TerminalResult(
    outputJson.encodeToJsonElement(WorkspaceMembersDto.serializer(), value),
    value.members.joinToString("\n") { "${it.userId}  ${it.email}  ${it.role.name.lowercase()}" },
)

internal suspend fun account(action: String?, args: List<String>): TerminalResult {
    val id = if (action == "show") positional(args, "ACCOUNT_ID") else null
    val argsAllowed = setOf("--server", "--workspace")
    val allowed = when (action) {
        "list", "show" -> argsAllowed
        "create" -> argsAllowed + setOf("--name", "--type", "--currency")
        else -> throw CliFailure("unknown_command")
    }
    val options = parseOptions(if (id == null) args else args.drop(1), allowed)
    val expected = options["--workspace"]
    if (action == "create") {
        val name = options.required("--name")
        val type = options.required("--type")
        val kind = CashAccountType.entries.firstOrNull { it.name.lowercase() == type } ?: throw CliFailure("invalid_parameter", "--type")
        val currencies = options.required("--currency").split(',')
        return withCashWrites(options["--server"]) { writeResult(it.createAccount(expected, name, kind, currencies)) }
    }
    return withClient(options["--server"]) { client ->
        if (action == "list") {
            val accounts = client.accounts(expected)
            TerminalResult(buildJsonObject {
                put("accounts", JsonArray(accounts.map { exactReadIdentifiers(outputJson.encodeToJsonElement(AccountReferenceDto.serializer(), it)) }))
            }, accounts.joinToString("\n") { "${it.id}  ${it.name}  ${it.currencies.orEmpty().joinToString(",")}" })
        } else {
            val details = client.accountDetails(expected, checkNotNull(id))
            val account = details.account
            TerminalResult(buildJsonObject {
                put("account", exactReadIdentifiers(outputJson.encodeToJsonElement(AccountCommandViewDto.serializer(), account)))
                put("balances", JsonArray(details.balances.map { outputJson.encodeToJsonElement(CashAccountBalance.serializer(), it) }))
            }, buildString {
                appendLine("${account.accountId}  ${account.name}  ${account.currencies.joinToString(",")}")
                details.balances.forEach { appendLine("${it.currency}  ${it.balance}") }
            }.trimEnd())
        }
    }
}
