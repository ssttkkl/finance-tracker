package com.finance.tracker

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteAndWindowSizeTest {
    @Test
    fun windowSizeClassUsesTheSharedBreakpoints() {
        assertEquals(WindowSizeClass.COMPACT, WindowSizeClass.fromWidthDp(0))
        assertEquals(WindowSizeClass.COMPACT, WindowSizeClass.fromWidthDp(599))
        assertEquals(WindowSizeClass.REGULAR, WindowSizeClass.fromWidthDp(600))
        assertEquals(WindowSizeClass.REGULAR, WindowSizeClass.fromWidthDp(1023))
        assertEquals(WindowSizeClass.WIDE, WindowSizeClass.fromWidthDp(1024))
    }

    @Test
    fun parsesAllExistingRootPagePaths() {
        val cases = listOf(
            "/" to AppPage.CASH_LEDGER,
            "/cash-import" to AppPage.CASH_IMPORT,
            "/cash-categories" to AppPage.CASH_CATEGORIES,
            "/investment-holdings" to AppPage.INVESTMENT_HOLDINGS,
            "/investment-events" to AppPage.INVESTMENT_EVENTS,
            "/workspace-management" to AppPage.WORKSPACE_MANAGEMENT,
        )

        cases.forEach { (path, page) ->
            assertEquals(AppRoute(page = page), parseAppRoute(path))
        }
    }

    @Test
    fun parsesWorkspacePathsAndRetainsDecodedWorkspaceId() {
        assertEquals(
            AppRoute(page = AppPage.CASH_LEDGER, workspaceId = "team one"),
            parseAppRoute("/w/team%20one"),
        )
        assertEquals(
            AppRoute(page = AppPage.INVESTMENT_EVENTS, workspaceId = "team/one"),
            parseAppRoute("/w/team%2Fone/investment-events"),
        )
    }

    @Test
    fun invitationQueryTakesPrecedenceAndDecodesTheToken() {
        assertEquals(
            AppRoute(page = AppPage.INVITATION, invitationToken = "invite/one two"),
            parseAppRoute("/", "?source=email&invite=invite%2Fone+two"),
        )
    }

    @Test
    fun invitationRouteRetainsTheWorkspacePathForReturnNavigation() {
        assertEquals(
            AppRoute(AppPage.INVITATION, workspaceId = "team one", invitationToken = "token"),
            parseAppRoute("/w/team%20one/workspace-management", "?invite=token"),
        )
    }

    @Test
    fun invitationReturnPageRestoresTheExistingRouteOrFallsBackToLedger() {
        assertEquals(AppPage.WORKSPACE_MANAGEMENT, invitationReturnPage("/w/team%20one/workspace-management"))
        assertEquals(AppPage.INVESTMENT_EVENTS, invitationReturnPage("/investment-events"))
        assertEquals(AppPage.CASH_LEDGER, invitationReturnPage(null))
        assertEquals(AppPage.CASH_LEDGER, invitationReturnPage("/unknown"))
    }

    @Test
    fun unknownAndMalformedWorkspacePathsAreNotTreatedAsTheLedger() {
        assertEquals(
            AppRoute(page = AppPage.NOT_FOUND, unmatchedPath = "/cash-unknown"),
            parseAppRoute("/cash-unknown"),
        )
        assertEquals(
            AppRoute(page = AppPage.NOT_FOUND, unmatchedPath = "/w/%ZZ/investment-events"),
            parseAppRoute("/w/%ZZ/investment-events"),
        )
    }

    @Test
    fun workspacePathEncodesTheIdAndPreservesTheExistingChildPath() {
        assertEquals(
            "/w/team%201%2F%E6%9D%B1%E4%BA%AC/cash-import",
            workspacePath("team 1/東京", "/cash-import"),
        )
        assertEquals("/w/team/", workspacePath("team"))
    }

    @Test
    fun routeFormattingPreservesExistingRootAndWorkspacePaths() {
        assertEquals("/", appPathForRoute(AppRoute(AppPage.CASH_LEDGER)))
        assertEquals("/cash-import", appPathForRoute(AppRoute(AppPage.CASH_IMPORT)))
        assertEquals("/cash-categories", appPathForRoute(AppRoute(AppPage.CASH_CATEGORIES)))
        assertEquals("/investment-holdings", appPathForRoute(AppRoute(AppPage.INVESTMENT_HOLDINGS)))
        assertEquals("/investment-events", appPathForRoute(AppRoute(AppPage.INVESTMENT_EVENTS)))
        assertEquals("/workspace-management", appPathForRoute(AppRoute(AppPage.WORKSPACE_MANAGEMENT)))
        assertEquals(
            "/w/team%20one/cash-import",
            appPathForRoute(AppRoute(AppPage.CASH_IMPORT, workspaceId = "team one")),
        )
    }

    @Test
    fun invitationUrlIsEncodedAndUnknownRouteDoesNotNavigate() {
        assertEquals(
            "/?invite=one%20two%2F%E4%BD%A0%E5%A5%BD",
            appPathForRoute(AppRoute(AppPage.INVITATION, invitationToken = "one two/你好")),
        )
        assertEquals(null, appPathForRoute(AppRoute(AppPage.NOT_FOUND)))
    }

    @Test
    fun invitationLinkPreservesCurrentWorkspacePathAndEncodesToken() {
        assertEquals(
            "https://finance.example/w/team%20one/workspace-management?invite=one%20two%2F%E4%BD%A0%E5%A5%BD",
            invitationLinkFor(
                webOrigin = "https://finance.example/",
                currentPath = "/w/team%20one/workspace-management",
                token = "one two/你好",
            ),
        )
    }

    @Test
    fun invitationLinkFallsBackToApplicationSchemeWithoutWebOrigin() {
        assertEquals(
            "finance-tracker://invite/one%20two",
            invitationLinkFor(webOrigin = "", currentPath = null, token = "one two"),
        )
    }

    @Test
    fun clearingAcceptedOrCancelledInvitationRemovesOnlyThatPendingToken() {
        IncomingInvitationLinks.clear()
        IncomingInvitationLinks.receive("finance-tracker://invite/one")
        assertEquals("one", IncomingInvitationLinks.currentToken())

        IncomingInvitationLinks.clear("other")
        assertEquals("one", IncomingInvitationLinks.currentToken())

        IncomingInvitationLinks.clear("one")
        assertEquals(null, IncomingInvitationLinks.currentToken())
    }
}
