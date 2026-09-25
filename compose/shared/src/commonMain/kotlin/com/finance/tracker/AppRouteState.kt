package com.finance.tracker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

internal data class AppNavigationState(
    val currentRoute: AppRoute,
    val backStack: List<AppRoute> = emptyList(),
) {
    fun navigate(nextRoute: AppRoute, recordBackStack: Boolean): AppNavigationState = copy(
        currentRoute = nextRoute,
        backStack = if (recordBackStack) pushNativeRouteHistory(backStack, currentRoute, nextRoute) else backStack,
    )

    fun back(): AppNavigationState? {
        val (remaining, previous) = popNativeRouteHistory(backStack)
        return previous?.let { copy(currentRoute = it, backStack = remaining) }
    }
}

internal fun saveAppRouteState(route: AppRoute): List<String> = listOf(
    route.page.name,
    route.workspaceId.orEmpty(),
    route.invitationToken.orEmpty(),
    route.unmatchedPath.orEmpty(),
)

internal fun restoreAppRouteState(values: List<String>): AppRoute? {
    if (values.size != 4) return null
    val page = runCatching { AppPage.valueOf(values[0]) }.getOrNull() ?: return null
    return AppRoute(
        page = page,
        workspaceId = values[1].ifEmpty { null },
        invitationToken = values[2].ifEmpty { null },
        unmatchedPath = values[3].ifEmpty { null },
    )
}

internal val appRouteSaver = listSaver<AppRoute, String>(
    save = { saveAppRouteState(it) },
    restore = { restoreAppRouteState(it) },
)

internal fun saveAppNavigationState(state: AppNavigationState): List<String> =
    saveAppRouteState(state.currentRoute) + saveAppRouteHistory(state.backStack)

internal fun restoreAppNavigationState(values: List<String>): AppNavigationState? {
    if (values.size < 5) return null
    val currentRoute = restoreAppRouteState(values.take(4)) ?: return null
    return AppNavigationState(
        currentRoute = currentRoute,
        backStack = restoreAppRouteHistory(values.drop(4)),
    )
}

internal val appNavigationStateSaver = listSaver<AppNavigationState, String>(
    save = { saveAppNavigationState(it) },
    restore = { restoreAppNavigationState(it) },
)

@Composable
internal fun rememberAppNavigationState(initialRoute: AppRoute): MutableState<AppNavigationState> =
    rememberSaveable(stateSaver = appNavigationStateSaver) {
        mutableStateOf(AppNavigationState(initialRoute))
    }

internal fun saveAppRouteHistory(history: List<AppRoute>): List<String> = buildList {
    add(history.size.toString())
    history.forEach { addAll(saveAppRouteState(it)) }
}

internal fun restoreAppRouteHistory(values: List<String>): List<AppRoute> {
    val routeCount = values.firstOrNull()?.toIntOrNull()?.coerceIn(0, 32) ?: return emptyList()
    if (values.size != 1 + routeCount * 4) return emptyList()
    return (0 until routeCount).mapNotNull { index ->
        restoreAppRouteState(values.subList(1 + index * 4, 1 + (index + 1) * 4))
    }
}

internal val appRouteHistorySaver = listSaver<List<AppRoute>, String>(
    save = { saveAppRouteHistory(it) },
    restore = { restoreAppRouteHistory(it) },
)

internal fun pushNativeRouteHistory(history: List<AppRoute>, current: AppRoute, next: AppRoute): List<AppRoute> =
    if (current == next) history else (history + current).takeLast(32)

internal fun popNativeRouteHistory(history: List<AppRoute>): Pair<List<AppRoute>, AppRoute?> =
    if (history.isEmpty()) history to null else history.dropLast(1) to history.last()
