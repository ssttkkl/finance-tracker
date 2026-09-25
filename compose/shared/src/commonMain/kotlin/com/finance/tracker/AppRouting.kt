package com.finance.tracker

enum class AppPage {
    CASH_LEDGER,
    CASH_IMPORT,
    CASH_CATEGORIES,
    INVESTMENT_HOLDINGS,
    INVESTMENT_EVENTS,
    WORKSPACE_MANAGEMENT,
    INVITATION,
    NOT_FOUND,
}

data class AppRoute(
    val page: AppPage,
    val workspaceId: String? = null,
    val invitationToken: String? = null,
    val unmatchedPath: String? = null,
)

data class WorkspaceLocation(
    val workspaceId: String,
    val childPath: String,
)

/** Parses the existing path and query contracts without converting them into hash routes. */
fun parseAppRoute(pathname: String, search: String = ""): AppRoute {
    val path = pathname.ifEmpty { "/" }
    val invitationToken = queryParameter(search, "invite")?.takeIf(String::isNotEmpty)
    if (invitationToken != null) {
        return AppRoute(
            page = AppPage.INVITATION,
            workspaceId = parseWorkspacePath(path)?.workspaceId,
            invitationToken = invitationToken,
        )
    }

    val workspace = parseWorkspacePath(path)
    if (path.startsWith(WORKSPACE_PREFIX) && workspace == null) {
        return AppRoute(page = AppPage.NOT_FOUND, unmatchedPath = path)
    }
    val childPath = workspace?.childPath ?: path
    val page = when (childPath) {
        "/" -> AppPage.CASH_LEDGER
        "/cash-import" -> AppPage.CASH_IMPORT
        "/cash-categories" -> AppPage.CASH_CATEGORIES
        "/investment-holdings" -> AppPage.INVESTMENT_HOLDINGS
        "/investment-events" -> AppPage.INVESTMENT_EVENTS
        "/workspace-management" -> AppPage.WORKSPACE_MANAGEMENT
        else -> AppPage.NOT_FOUND
    }
    return AppRoute(
        page = page,
        workspaceId = workspace?.workspaceId,
        unmatchedPath = path.takeIf { page == AppPage.NOT_FOUND },
    )
}

internal fun invitationReturnPage(pathname: String?): AppPage {
    val page = pathname?.let { parseAppRoute(it).page } ?: return AppPage.CASH_LEDGER
    return page.takeUnless { it == AppPage.INVITATION || it == AppPage.NOT_FOUND } ?: AppPage.CASH_LEDGER
}

fun invitationTokenFromUrl(url: String): String? {
    val customPrefix = "finance-tracker://invite/"
    if (url.startsWith(customPrefix)) {
        val encodedToken = url.removePrefix(customPrefix).substringBefore('?').substringBefore('#').substringBefore('/')
        return percentDecode(encodedToken, plusAsSpace = false)?.takeIf(String::isNotEmpty)
    }
    val queryStart = url.indexOf('?')
    if (queryStart < 0) return null
    val search = url.substring(queryStart + 1).substringBefore('#')
    return queryParameter(search, "invite")?.takeIf(String::isNotEmpty)
}

fun parseWorkspacePath(pathname: String): WorkspaceLocation? {
    if (!pathname.startsWith(WORKSPACE_PREFIX)) return null
    val remainder = pathname.removePrefix(WORKSPACE_PREFIX)
    val separator = remainder.indexOf('/')
    val encodedId = if (separator == -1) remainder else remainder.substring(0, separator)
    if (encodedId.isEmpty()) return null
    val workspaceId = percentDecode(encodedId, plusAsSpace = false)?.takeIf(String::isNotEmpty) ?: return null
    val childPath = if (separator == -1) "/" else remainder.substring(separator).ifEmpty { "/" }
    return WorkspaceLocation(workspaceId = workspaceId, childPath = childPath)
}

fun workspacePath(workspaceId: String, path: String = "/"): String {
    require(workspaceId.isNotEmpty()) { "workspaceId must not be empty" }
    val childPath = when {
        path.isEmpty() -> "/"
        path.startsWith('/') -> path
        else -> "/$path"
    }
    return "$WORKSPACE_PREFIX${encodePathSegment(workspaceId)}$childPath"
}

fun appPathForRoute(route: AppRoute): String? {
    val childPath = when (route.page) {
        AppPage.CASH_LEDGER -> "/"
        AppPage.CASH_IMPORT -> "/cash-import"
        AppPage.CASH_CATEGORIES -> "/cash-categories"
        AppPage.INVESTMENT_HOLDINGS -> "/investment-holdings"
        AppPage.INVESTMENT_EVENTS -> "/investment-events"
        AppPage.WORKSPACE_MANAGEMENT -> "/workspace-management"
        AppPage.INVITATION -> {
            val token = route.invitationToken?.takeIf(String::isNotEmpty) ?: return null
            return "/?invite=${encodePathSegment(token)}"
        }
        AppPage.NOT_FOUND -> return null
    }
    return route.workspaceId?.let { workspacePath(it, childPath) } ?: childPath
}

internal fun invitationLinkFor(webOrigin: String, currentPath: String?, token: String): String {
    val encodedToken = encodePathSegment(token)
    val origin = webOrigin.trimEnd('/')
    if (origin.isEmpty()) return "finance-tracker://invite/$encodedToken"
    val path = currentPath?.takeIf { it.startsWith('/') && !it.startsWith("//") } ?: "/"
    return "$origin$path?invite=$encodedToken"
}

private const val WORKSPACE_PREFIX = "/w/"

private fun queryParameter(search: String, requestedName: String): String? {
    val query = search.removePrefix("?")
    if (query.isEmpty()) return null
    for (part in query.split('&')) {
        val separator = part.indexOf('=')
        val encodedName = if (separator == -1) part else part.substring(0, separator)
        val encodedValue = if (separator == -1) "" else part.substring(separator + 1)
        val name = percentDecode(encodedName, plusAsSpace = true) ?: continue
        if (name == requestedName) {
            return percentDecode(encodedValue, plusAsSpace = true)
        }
    }
    return null
}

private fun percentDecode(value: String, plusAsSpace: Boolean): String? {
    val decoded = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character != '%' && !(plusAsSpace && character == '+')) {
            decoded.append(character)
            index++
            continue
        }

        val bytes = mutableListOf<Byte>()
        while (index < value.length) {
            when (val encoded = value[index]) {
                '%' -> {
                    if (index + 2 >= value.length) return null
                    val high = value[index + 1].hexValue() ?: return null
                    val low = value[index + 2].hexValue() ?: return null
                    bytes += ((high shl 4) or low).toByte()
                    index += 3
                }
                '+' -> if (plusAsSpace) {
                    bytes += ' '.code.toByte()
                    index++
                } else {
                    break
                }
                else -> break
            }
        }
        try {
            decoded.append(bytes.toByteArray().decodeToString(throwOnInvalidSequence = true))
        } catch (_: IllegalArgumentException) {
            return null
        }
    }
    return decoded.toString()
}

private fun Char.hexValue(): Int? = when (this) {
    in '0'..'9' -> code - '0'.code
    in 'a'..'f' -> code - 'a'.code + 10
    in 'A'..'F' -> code - 'A'.code + 10
    else -> null
}

private fun encodePathSegment(value: String): String {
    val result = StringBuilder(value.length)
    for (byte in value.encodeToByteArray()) {
        val unsigned = byte.toInt() and 0xff
        val character = unsigned.toChar()
        if (character.isPathSegmentSafe()) {
            result.append(character)
        } else {
            result.append('%')
            result.append(HEX_DIGITS[unsigned shr 4])
            result.append(HEX_DIGITS[unsigned and 0x0f])
        }
    }
    return result.toString()
}

private fun Char.isPathSegmentSafe(): Boolean =
    this in 'a'..'z' ||
        this in 'A'..'Z' ||
        this in '0'..'9' ||
        this in "-_.!~*'()"

private const val HEX_DIGITS = "0123456789ABCDEF"
