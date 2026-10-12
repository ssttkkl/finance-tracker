package com.finance.tracker

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finance.tracker.core.SemanticIds
import com.finance.tracker.core.FinanceTheme
import com.finance.tracker.domain.Session
import com.finance.tracker.domain.User
import com.finance.tracker.domain.Workspace
import com.finance.tracker.domain.WorkspaceRole
import com.finance.tracker.presentation.WorkspaceAccessScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class WorkspaceAccessScreenDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun firstUseHasOneCreateHeadingAndAuthStyleAccountHeader() {
        showScreen(session = emptySession())

        composeRule.onNodeWithText(expectedText("财务账本", "Finance Tracker")).assertIsDisplayed()
        composeRule.onNodeWithText("ss@example.com").assertIsDisplayed()
        composeRule.onNodeWithText(expectedText("退出登录", "Sign out")).assertIsDisplayed()
        composeRule.onAllNodes(hasTestTag(SemanticIds.workspaceCreateTitle)).assertCountEquals(1)
        composeRule.onAllNodesWithText(expectedText("新工作区", "New workspace")).assertCountEquals(0)
        composeRule.onNodeWithTag(SemanticIds.workspaceCreateName).assertIsDisplayed()
        composeRule.onNodeWithTag(SemanticIds.workspaceCreate).assertIsDisplayed()
        assertFirstUseCardGeometry()
    }

    @Test
    fun blankNameShowsValidationAndReportsInvalidInput() {
        val invalidCalls = AtomicInteger()
        showScreen(session = emptySession(), onInvalidWorkspaceName = { invalidCalls.incrementAndGet() })

        composeRule.onNodeWithTag(SemanticIds.workspaceCreate).performClick()

        composeRule.onNodeWithText(
            expectedText("请输入不超过 255 个字符的名称。", "Enter a name with no more than 255 characters."),
            substring = true,
        ).assertIsDisplayed()
        composeRule.runOnIdle { assert(invalidCalls.get() == 1) }
    }

    @Test
    fun nameFieldCanReceiveFocusAndSubmitsNormalizedName() {
        val createdName = AtomicReference<String?>()
        showScreen(session = emptySession(), onCreate = { createdName.set(it) })

        composeRule.onNode(hasSetTextAction()).performClick().assertIsFocused()
        composeRule.onNode(hasSetTextAction()).performTextInput("  Tax ledger  ")
        composeRule.onNodeWithTag(SemanticIds.workspaceCreate).performClick()

        composeRule.runOnIdle { assert(createdName.get() == "Tax ledger") }
    }

    @Test
    fun requestErrorKeepsNameEditableAndRetryAvailable() {
        val retryCalls = AtomicInteger()
        showScreen(
            session = emptySession(),
            requestError = "The network is temporarily unavailable.",
            onRetry = { retryCalls.incrementAndGet() },
        )

        composeRule.onNodeWithText("The network is temporarily unavailable.").assertIsDisplayed()
        composeRule.onNodeWithTag(SemanticIds.workspaceCreateName).assertIsDisplayed()
        composeRule.onNodeWithTag(SemanticIds.workspaceRetry).performClick()

        composeRule.runOnIdle { assert(retryCalls.get() == 1) }
    }

    @Test
    fun busyStateDisablesNameCreateAndLogoutActions() {
        showScreen(session = emptySession(), busy = true)

        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        composeRule.onNodeWithTag(SemanticIds.workspaceCreate).assertIsNotEnabled()
        composeRule.onNodeWithTag(SemanticIds.workspaceLogout).assertIsNotEnabled()
        composeRule.onNodeWithText(expectedText("正在创建…", "Creating…")).assertIsDisplayed()
    }

    @Test
    fun existingWorkspaceSelectionAndCreateAnotherRemainAvailable() {
        showScreen(
            session = emptySession().copy(
                workspaces = listOf(Workspace("w-1", "Family ledger", WorkspaceRole.ADMIN)),
            ),
        )

        composeRule.onNodeWithText("Family ledger").assertIsDisplayed()
        composeRule.onNodeWithTag(SemanticIds.workspaceList).assertIsDisplayed()
        composeRule.onNodeWithText(expectedText("新工作区", "New workspace")).assertIsDisplayed()
        composeRule.onNodeWithTag(SemanticIds.workspaceCreate).assertIsDisplayed()
    }

    private fun assertFirstUseCardGeometry() {
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val root = composeRule.onNodeWithTag(SemanticIds.workspaceScreen).fetchSemanticsNode().boundsInRoot
        val card = composeRule.onNodeWithTag(SemanticIds.workspaceCreateCard).fetchSemanticsNode().boundsInRoot
        val widthDp = (root.width / density).roundToInt()
        val cardWidthDp = card.width / density
        val cardLeftDp = card.left / density
        val expectedCardWidthDp = if (widthDp < 600) widthDp - 32f else 440f
        val expectedCardLeftDp = if (widthDp < 600) 16f else (widthDp - 440f) / 2f
        assertTrue(abs(cardWidthDp - expectedCardWidthDp) <= 1f, "Card width was $cardWidthDp dp at $widthDp dp")
        assertTrue(abs(cardLeftDp - expectedCardLeftDp) <= 1f, "Card left was $cardLeftDp dp at $widthDp dp")
        val expectedTopDp = when (widthDp) {
            390 -> 136f
            768 -> 392f
            1440 -> 348f
            else -> null
        }
        expectedTopDp?.let {
            assertTrue(abs(card.top / density - it) <= 2f, "Card top was ${card.top / density} dp at $widthDp dp")
        }
    }

    private fun expectedText(chinese: String, english: String): String {
        val locales = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.locales
        return if (locales[0].language == "zh") chinese else english
    }

    private fun showScreen(
        session: Session,
        busy: Boolean = false,
        requestError: String? = null,
        onCreate: (String) -> Unit = {},
        onRetry: () -> Unit = {},
        onInvalidWorkspaceName: () -> Unit = {},
    ) {
        composeRule.setContent {
            FinanceTheme {
                WorkspaceAccessScreen(
                    session = session,
                    busy = busy,
                    requestError = requestError,
                    onSelect = {},
                    onCreate = onCreate,
                    onLogout = {},
                    onRetry = onRetry,
                    onInvalidWorkspaceName = onInvalidWorkspaceName,
                )
            }
        }
    }

    private fun emptySession() = Session(user = User("ss@example.com"))
}
