package com.finance.tracker

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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
import com.finance.tracker.presentation.CashRecordFact
import com.finance.tracker.presentation.CashRelatedRecordSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class CashRecordDetailDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun realDetailEntrySeparatesSummaryFromFlatFacts() {
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
            composeRule.onAllNodesWithTag("record.detail.fact.time").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("record.detail.fact.time.value", useUnmergedTree = true).assertIsDisplayed()
        val amount = textLayout(composeRule.onNodeWithTag("record.detail.amount", useUnmergedTree = true))
        val counterparty = textLayout(composeRule.onNodeWithTag("record.detail.counterparty", useUnmergedTree = true))
        assertTrue("Amount provides the summary's visual focus", amount.layoutInput.style.fontSize.value > counterparty.layoutInput.style.fontSize.value * 1.5f)
        val panel = composeRule.onNodeWithTag("ledger.record-panel").getUnclippedBoundsInRoot()
        val summary = composeRule.onNodeWithTag("record.detail.summary").getUnclippedBoundsInRoot()
        val header = composeRule.onNodeWithTag("ledger.record-panel.header").getUnclippedBoundsInRoot()
        assertTrue("Summary keeps 24 dp below the header divider", abs((summary.top - header.bottom).value - 25f) < 1f)
        val facts = composeRule.onNodeWithTag("record.detail.facts").getUnclippedBoundsInRoot()
        assertTrue("Flat facts use the panel content edge", abs((facts.left - panel.left).value - 24f) < 1f)
        composeRule.onNodeWithTag("record.detail.edit").performClick()
        composeRule.onNodeWithTag("record.cancel").performClick()
        composeRule.onNodeWithTag("record.detail.summary").assertIsDisplayed()
    }

    @Test
    fun mixedSizeFactTextSharesItsFirstBaseline() {
        composeRule.setContent {
            FinanceTheme {
                Surface(Modifier.width(360.dp)) {
                    CashRecordFact("收支分类", "日常生活 / 餐饮 / 早餐与咖啡", "sample.fact")
                }
            }
        }
        val label = composeRule.onNodeWithTag("sample.fact.label", useUnmergedTree = true)
        val value = composeRule.onNodeWithTag("sample.fact.value", useUnmergedTree = true)
        val labelBaseline = label.fetchSemanticsNode().boundsInRoot.top + textLayout(label).firstBaseline
        val valueBaseline = value.fetchSemanticsNode().boundsInRoot.top + textLayout(value).firstBaseline
        assertTrue("Mixed font sizes align on the first baseline", abs(labelBaseline - valueBaseline) <= 1f)
        assertFalse(textLayout(value).didOverflowWidth)
    }

    @Test
    fun enlargedLongValuesStackWithoutClipping() {
        val merchant = "长名称商户与跨地区业务服务中心"
        val amount = "12345678901234567890.123456789 CNY"
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                FinanceTheme {
                    Surface(Modifier.width(320.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            CashRecordFact("对方账号", "BANK-123456789012345678901234567890", "sample.fact")
                            CashRelatedRecordSummary(merchant, amount, "2026-10-22T10:00:00+08:00", "日常账户", "消费", "餐饮", "", "")
                        }
                    }
                }
            }
        }
        val label = composeRule.onNodeWithTag("sample.fact.label", useUnmergedTree = true)
        val value = composeRule.onNodeWithTag("sample.fact.value", useUnmergedTree = true)
        assertTrue("Large text uses label above value", value.fetchSemanticsNode().boundsInRoot.top >= label.fetchSemanticsNode().boundsInRoot.bottom)
        assertTrue("Long account wraps", textLayout(value).lineCount > 1)
        assertFalse(textLayout(value).didOverflowWidth)
        val name = composeRule.onNodeWithText(merchant, useUnmergedTree = true)
        val number = composeRule.onNodeWithText(amount, useUnmergedTree = true)
        assertTrue("Long amount follows the merchant instead of squeezing it", number.fetchSemanticsNode().boundsInRoot.top >= name.fetchSemanticsNode().boundsInRoot.bottom)
        assertFalse(textLayout(number).didOverflowWidth)
    }

    private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
}
