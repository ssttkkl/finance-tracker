package com.finance.tracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppNavigationTest {
    @Test
    fun mainNavigationKeepsAllExistingAppRoutes() {
        assertEquals(
            listOf(
                AppPage.CASH_LEDGER,
                AppPage.CASH_IMPORT,
                AppPage.CASH_CATEGORIES,
                AppPage.INVESTMENT_HOLDINGS,
                AppPage.INVESTMENT_EVENTS,
                AppPage.WORKSPACE_MANAGEMENT,
            ),
            appDestinations.map(AppDestination::page),
        )
    }

    @Test
    fun invitationAndUnknownRoutesHaveTitlesButAreNotPrimaryNavigation() {
        assertEquals("接受邀请", titleFor(AppPage.INVITATION))
        assertEquals("页面不存在", titleFor(AppPage.NOT_FOUND))
        assertFalse(appDestinations.any { it.page == AppPage.INVITATION })
        assertFalse(appDestinations.any { it.page == AppPage.NOT_FOUND })
    }

    @Test
    fun invitationLinksKeepWebQueryAndRegisteredNativeSchemeTokens() {
        assertEquals("token/one", invitationTokenFromUrl("https://finance.example/?invite=token%2Fone"))
        assertEquals("token one", invitationTokenFromUrl("https://finance.example/?invite=token+one"))
        assertEquals("token/one", invitationTokenFromUrl("finance-tracker://invite/token%2Fone"))
        assertEquals(null, invitationTokenFromUrl("finance-tracker://other/token"))
    }

    @Test
    fun everyPrimaryDestinationHasAStableSemanticId() {
        assertTrue(appDestinations.all { it.semanticId.startsWith("navigation-item-") })
        assertEquals(appDestinations.size, appDestinations.map(AppDestination::semanticId).toSet().size)
    }

    @Test
    fun nativeRouteStateRoundTripsPageWorkspaceAndInvitationContext() {
        val routes = listOf(
            AppRoute(AppPage.INVESTMENT_EVENTS, workspaceId = "family space"),
            AppRoute(AppPage.INVITATION, workspaceId = "family space", invitationToken = "token/one"),
        )

        routes.forEach { route ->
            assertEquals(route, restoreAppRouteState(saveAppRouteState(route)))
        }
    }

    @Test
    fun nativeBackStackReturnsToPreviousRouteInOrder() {
        val ledger = AppRoute(AppPage.CASH_LEDGER, workspaceId = "workspace-1")
        val holdings = AppRoute(AppPage.INVESTMENT_HOLDINGS, workspaceId = "workspace-1")
        val events = AppRoute(AppPage.INVESTMENT_EVENTS, workspaceId = "workspace-1")
        val stack = pushNativeRouteHistory(
            pushNativeRouteHistory(emptyList(), ledger, holdings),
            holdings,
            events,
        )

        val (remaining, previous) = popNativeRouteHistory(stack)
        assertEquals(holdings, previous)
        assertEquals(listOf(ledger), remaining)
        val (empty, first) = popNativeRouteHistory(remaining)
        assertEquals(ledger, first)
        assertTrue(empty.isEmpty())
    }

    @Test
    fun nativeNavigationRestoresCurrentRouteAndBackStackAfterConfigurationChange() {
        val ledger = AppRoute(AppPage.CASH_LEDGER, workspaceId = "workspace-1")
        val holdings = AppRoute(AppPage.INVESTMENT_HOLDINGS, workspaceId = "workspace-1")
        val events = AppRoute(AppPage.INVESTMENT_EVENTS, workspaceId = "workspace-1")
        val beforeRecreation = AppNavigationState(currentRoute = events, backStack = listOf(ledger, holdings))

        val restored = restoreAppNavigationState(saveAppNavigationState(beforeRecreation))

        assertEquals(beforeRecreation, restored)
        val afterBack = restored!!.back()
        assertEquals(holdings, afterBack?.currentRoute)
        assertEquals(listOf(ledger), afterBack?.backStack)
    }
}
