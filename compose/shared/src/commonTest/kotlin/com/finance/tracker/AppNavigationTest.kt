package com.finance.tracker

import com.finance.tracker.app.*
import com.finance.tracker.core.*
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*

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
        assertEquals("navigation_invitation", titleKeyFor(AppPage.INVITATION))
        assertEquals("navigation_not_found", titleKeyFor(AppPage.NOT_FOUND))
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
        assertTrue(appDestinations.all { it.titleKey.startsWith("navigation_") })
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
