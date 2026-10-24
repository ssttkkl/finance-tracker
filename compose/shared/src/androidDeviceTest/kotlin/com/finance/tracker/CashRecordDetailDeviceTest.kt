package com.finance.tracker

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finance.tracker.app.FinanceTrackerApp
import com.finance.tracker.core.AppPage
import com.finance.tracker.core.AppRoute
import com.finance.tracker.core.FinanceTheme
import com.finance.tracker.presentation.CashRecordSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CashRecordDetailDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun realDetailEntryUsesTheCurrentSummaryAndEditorInOnePanel() {
        composeRule.setContent {
            FinanceTrackerApp(demoMode = true, initialRoute = AppRoute(AppPage.CASH_LEDGER))
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag("ledger.open-record", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val compact = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp < 600
        if (compact) composeRule.onNodeWithTag("ledger.record-projection-demo-record-2").performClick()
        else composeRule.onAllNodesWithTag("ledger.open-record")[1].performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("cash.current.amount").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cash.current.amount").assertIsDisplayed()
        composeRule.onNodeWithText("流水信息").assertIsDisplayed()
        composeRule.onNodeWithTag("cash.current.edit").performClick()
        composeRule.onNodeWithTag("cash.entry.editor").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("ledger.record-panel").fetchSemanticsNodes().size == 1)
        composeRule.onNodeWithTag("cash.entry.cancel").performClick()
        composeRule.onNodeWithTag("cash.current.amount").assertIsDisplayed()
    }

    @Test
    fun enlargedLongSummaryValuesWrapWithinTheAvailableWidth() {
        val merchant = "长名称商户与跨地区业务服务中心".repeat(4)
        val note = "长备注需要完整阅读且不应横向裁切。".repeat(8)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                FinanceTheme {
                    Surface(Modifier.width(320.dp)) {
                        CashRecordSummary("12345678901234567890.123456789 CNY", false, merchant, "",
                            listOf("copy_e0361480e3" to note), Modifier.padding(16.dp))
                    }
                }
            }
        }
        for (value in listOf(merchant, note)) {
            val results = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(value, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertTrue("Long values wrap", results.single().lineCount > 1)
            assertFalse("Long values stay within the available width", results.single().didOverflowWidth)
        }
    }
}
