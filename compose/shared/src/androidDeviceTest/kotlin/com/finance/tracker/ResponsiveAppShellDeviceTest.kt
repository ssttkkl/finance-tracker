package com.finance.tracker

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
    fun regularRailKeepsPageContentVisible() {
        composeRule.setContent {
            MaterialTheme {
                RegularAppShell(AppRoute(AppPage.CASH_LEDGER), onNavigate = {}) {
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
                WideAppShell(AppRoute(AppPage.CASH_LEDGER), onNavigate = {}) {
                    Text("页面内容检查点")
                }
            }
        }

        composeRule.onNodeWithText("页面内容检查点").assertIsDisplayed()
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
