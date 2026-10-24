package com.finance.tracker

import com.finance.tracker.app.*
import com.finance.tracker.core.*
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResponsiveAppShellDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun routeTitleCanBeRenderedInTheTopBar() {
        composeRule.setContent {
            MaterialTheme {
                DestinationTopBarTitle(AppPage.CASH_LEDGER)
            }
        }

        composeRule.onNodeWithTag(SemanticIds.destinationTopBarTitle).assertIsDisplayed()
    }

    @Test
    fun featurePageCanHideRouteTitleWithoutHidingContent() {
        composeRule.setContent {
            MaterialTheme {
                FeaturePage("Route title", "feature-page-test", showTitle = false) {
                    Text("Main content")
                }
            }
        }

        composeRule.onNodeWithText("Main content").assertIsDisplayed()
        composeRule.onAllNodesWithTag(SemanticIds.destinationBodyTitle).assertCountEquals(0)
    }

    @Test
    fun regularNavigationKeepsPageContentVisible() {
        composeRule.setContent {
            MaterialTheme {
                RegularAppShell(shellState()) {
                    Text("页面内容检查点")
                }
            }
        }

        composeRule.onNodeWithText("页面内容检查点").assertIsDisplayed()
    }

    @Test
    fun wideDrawerKeepsPageContentVisible() {
        composeRule.setContent {
            MaterialTheme {
                WideAppShell(shellState()) {
                    Text("页面内容检查点")
                }
            }
        }

        composeRule.onNodeWithText("页面内容检查点").assertIsDisplayed()
    }

    @Test
    fun desktopRailExpandsOnActivationAndAccountMenuKeepsWorkspaceActions() {
        val selectedPages = mutableListOf<AppPage>()
        val selectedWorkspaces = mutableListOf<String>()
        val logoutCount = mutableStateOf(0)
        val state = shellState(
            onNavigate = { selectedPages += it },
            onWorkspaceSelected = { selectedWorkspaces += it },
            onLogout = { logoutCount.value += 1 },
        )
        composeRule.setContent {
            MaterialTheme {
                RegularAppShell(state) { Text("页面内容检查点") }
            }
        }

        composeRule.onNodeWithText("页面内容检查点").assertIsDisplayed()
        composeRule.onNodeWithTag(SemanticIds.navigationRailToggle).performClick()
        composeRule.onNodeWithText("收支账本").assertIsDisplayed()
        composeRule.onNodeWithTag("navigation-item-cash-import").performClick()
        assert(selectedPages.last() == AppPage.CASH_IMPORT)

        composeRule.onNodeWithTag(SemanticIds.workspaceSwitcher).performClick()
        composeRule.onNodeWithTag(SemanticIds.accountMenu).assertIsDisplayed()
        composeRule.onNodeWithText("个人工作区").assertIsDisplayed()
        composeRule.onNodeWithText("管理员 · 当前").assertIsDisplayed()
        composeRule.onNodeWithText("家庭工作区").performClick()
        assert(selectedWorkspaces == listOf("workspace-family"))

        composeRule.onNodeWithTag(SemanticIds.workspaceSwitcher).performClick()
        composeRule.onNodeWithTag(SemanticIds.accountManageWorkspaces).performClick()
        assert(selectedPages.last() == AppPage.WORKSPACE_MANAGEMENT)
        composeRule.onNodeWithTag(SemanticIds.workspaceSwitcher).performClick()
        composeRule.onNodeWithTag(SemanticIds.workspaceLogout).performClick()
        assert(logoutCount.value == 1)
    }

    @Test
    fun workspaceOptionsAreDisabledWhileASelectionIsBusy() {
        val state = shellState().copy(accessBusy = true)
        composeRule.setContent {
            MaterialTheme {
                RegularAppShell(state) { Text("页面内容检查点") }
            }
        }

        composeRule.onNodeWithTag(SemanticIds.workspaceSwitcher).performClick()
        composeRule.onNodeWithTag(SemanticIds.accountMenu).assertIsDisplayed()
        composeRule.onNodeWithText("家庭工作区").assertIsNotEnabled()
    }

    @Test
    fun compactDrawerKeepsSixFlatDestinationsAndBottomAccountEntry() {
        val state = shellState(sizeClass = WindowSizeClass.COMPACT)
        composeRule.setContent {
            MaterialTheme {
                CompactAppShell(state) { navigationIcon ->
                    Column {
                        navigationIcon()
                        Text("页面内容检查点")
                    }
                }
            }
        }

        composeRule.onNodeWithTag("navigation-open").performClick()
        composeRule.onNodeWithText("收支账本").assertIsDisplayed()
        composeRule.onNodeWithText("账单导入").assertIsDisplayed()
        composeRule.onNodeWithText("收支分类").assertIsDisplayed()
        composeRule.onNodeWithText("当前持仓").assertIsDisplayed()
        composeRule.onNodeWithText("投资事件").assertIsDisplayed()
        composeRule.onNodeWithText("工作区管理").assertIsDisplayed()
        composeRule.onNodeWithTag(SemanticIds.workspaceSwitcher).performClick()
        composeRule.onNodeWithTag(SemanticIds.accountMenu).assertIsDisplayed()
        composeRule.onAllNodesWithText("ssttkkl@qq.com").assertCountEquals(2)
    }

    @Test
    fun materialButtonExposesDisabledState() {
        composeRule.setContent {
            MaterialTheme {
                Button(onClick = {}, enabled = false) { Text("保存") }
            }
        }

        composeRule.onNodeWithText("保存").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun nativeCurrentRouteAndBackStackSurviveSavedStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        val ledger = AppRoute(AppPage.CASH_LEDGER, workspaceId = "workspace-1")
        val holdings = AppRoute(AppPage.INVESTMENT_HOLDINGS, workspaceId = "workspace-1")
        val events = AppRoute(AppPage.INVESTMENT_EVENTS, workspaceId = "workspace-1")

        restorationTester.setContent {
            val navigation = rememberAppNavigationState(ledger)
            Column {
                Text(navigation.value.currentRoute.page.name)
                Button(onClick = {
                    navigation.value = navigation.value.navigate(holdings, recordBackStack = true)
                }) { Text("打开持仓") }
                Button(onClick = {
                    navigation.value = navigation.value.navigate(events, recordBackStack = true)
                }) { Text("打开事件") }
                Button(onClick = {
                    navigation.value = navigation.value.back() ?: navigation.value
                }) { Text("返回") }
            }
        }

        composeRule.onNodeWithText("打开持仓").performClick()
        composeRule.onNodeWithText("打开事件").performClick()
        composeRule.onNodeWithText(AppPage.INVESTMENT_EVENTS.name).assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText(AppPage.INVESTMENT_EVENTS.name).assertIsDisplayed()
        composeRule.onNodeWithText("返回").performClick()
        composeRule.onNodeWithText(AppPage.INVESTMENT_HOLDINGS.name).assertIsDisplayed()
        composeRule.onNodeWithText("返回").performClick()
        composeRule.onNodeWithText(AppPage.CASH_LEDGER.name).assertIsDisplayed()
    }
}

private fun shellState(
    sizeClass: WindowSizeClass = WindowSizeClass.REGULAR,
    onNavigate: (AppPage) -> Unit = {},
    onWorkspaceSelected: (String) -> Unit = {},
    onLogout: () -> Unit = {},
): DestinationState {
    val session = Session(
        user = User("ssttkkl@qq.com"),
        activeWorkspaceId = "workspace-personal",
        workspaces = listOf(
            Workspace("workspace-personal", "个人工作区", WorkspaceRole.ADMIN),
            Workspace("workspace-family", "家庭工作区", WorkspaceRole.EDITOR),
        ),
    )
    return DestinationState(
        route = AppRoute(AppPage.CASH_LEDGER),
        financeRepository = testProxy(FinanceRepository::class.java),
        cashMaintenanceRepository = testProxy(com.finance.tracker.domain.CashMaintenanceRepository::class.java),
        cashMaintenanceRecovery = com.finance.tracker.core.InMemoryCashMaintenanceRecoveryStore(),
        sessionRepository = testProxy(SessionRepository::class.java),
        diagnosticLogger = NoOpDiagnosticLogger,
        session = session,
        accessBusy = false,
        accessError = null,
        sizeClass = sizeClass,
        cashLedgerReloadRevision = 0,
        onNavigate = onNavigate,
        canNavigateBack = false,
        onNavigateBack = {},
        onNavigateBackOr = {},
        onCloseSecondaryPage = {},
        onSessionUpdated = {},
        onWorkspaceDeleted = {},
        onInvitationAccepted = {},
        onWorkspaceSelected = onWorkspaceSelected,
        onLogout = onLogout,
    )
}

@Suppress("UNCHECKED_CAST")
private fun <T> testProxy(type: Class<T>): T = java.lang.reflect.Proxy.newProxyInstance(
    type.classLoader,
    arrayOf(type),
) { proxy, method, arguments ->
    when (method.name) {
        "toString" -> "${type.simpleName} test proxy"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === arguments?.firstOrNull()
        else -> null
    }
} as T
