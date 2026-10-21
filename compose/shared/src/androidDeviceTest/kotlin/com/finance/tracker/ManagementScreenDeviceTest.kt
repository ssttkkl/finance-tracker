package com.finance.tracker

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finance.tracker.app.FinanceTrackerApp
import com.finance.tracker.core.AppPage
import com.finance.tracker.core.AppRoute
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class ManagementScreenDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun managementDirectoryAndCategoryEditorShareTheRealNativeEntry() {
        lateinit var inputMode: InputModeManager
        composeRule.setContent {
            inputMode = LocalInputModeManager.current
            FinanceTrackerApp(demoMode = true, initialRoute = AppRoute(AppPage.WORKSPACE_MANAGEMENT))
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag("workspace-management.name").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        screenshot("workspace")
        val compact = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp < 600
        if (compact) {
            composeRule.onNodeWithTag("management.directory.open").performClick()
            composeRule.onNodeWithTag("management.directory.dialog").assertIsDisplayed()
            screenshot("directory")
        } else composeRule.onNodeWithTag("management.directory").assertIsDisplayed()
        composeRule.onNodeWithTag("management.directory.group.workspace").assertIsDisplayed()
        composeRule.onNodeWithTag("management.directory.group.access").assertIsDisplayed()
        composeRule.onNodeWithTag("management.directory.group.cash-ledger").assertIsDisplayed()
        composeRule.onNodeWithTag("management.directory.group.access").performClick()
        composeRule.onNodeWithTag("management.section.members").assertIsFocused()
        composeRule.onNodeWithTag("management.group.access").assertIsDisplayed()
        screenshot("access")
        if (compact) composeRule.onNodeWithTag("management.directory.open").performClick()
        composeRule.onNodeWithTag("management.directory.item.danger").performClick()
        composeRule.onNodeWithTag("management.section.danger").assertIsFocused()
        composeRule.onNodeWithTag("workspace.management.delete").assertIsDisplayed()
        screenshot("danger")
        if (compact) composeRule.onNodeWithTag("management.directory.open").performClick()
        composeRule.onNodeWithTag("management.directory.item.categories").performClick()
        composeRule.onNodeWithTag("management.section.categories").assertIsFocused()
        if (!compact) composeRule.onNodeWithTag("management.directory.item.categories").assertIsSelected()
        screenshot("categories")
        composeRule.onNodeWithTag("cash-category.edit.food").performClick()
        composeRule.onNodeWithTag("cash-category.editor").assertIsDisplayed()
        screenshot("editor")
        composeRule.onNodeWithTag("cash-category.name").performTextReplacement("Native category")
        composeRule.onNodeWithTag("cash-category.save").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("cash-category.editor").fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitUntil(5_000) {
            composeRule.onNodeWithTag("cash-category.edit.food").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true
        }
        composeRule.onNodeWithTag("cash-category.edit.food").assertIsFocused()
        if (compact) composeRule.onNodeWithTag("management.directory.open").performClick()
        composeRule.onNodeWithTag("management.directory.item.workspace").performClick()
        composeRule.onNodeWithTag("workspace-management.name").assertIsDisplayed()
    }

    private fun screenshot(state: String) {
        composeRule.waitForIdle()
        android.os.SystemClock.sleep(350)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val width = context.resources.configuration.screenWidthDp
        File(context.externalCacheDir, "management-$width-$state.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun managementLocalActionsFollowTheFormLayout() {
        composeRule.setContent {
            FinanceTrackerApp(demoMode = true, initialRoute = AppRoute(AppPage.WORKSPACE_MANAGEMENT))
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag("workspace-management.name").fetchSemanticsNodes().isNotEmpty()
        }
        val invite = composeRule.onNodeWithTag("workspace.management.invite").getUnclippedBoundsInRoot()
        assertTrue("A local invitation action should fit its label", (invite.right - invite.left).value < 300)
        val compact = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp < 600
        val name = composeRule.onNodeWithTag("workspace-management.name").getUnclippedBoundsInRoot()
        val save = composeRule.onNodeWithTag("management.save-name").getUnclippedBoundsInRoot()
        if (compact) assertTrue("Phone save follows its field", save.top >= name.bottom)
        else {
            assertTrue("Desktop save follows its field horizontally", save.left >= name.right)
            assertTrue("Desktop save stays in the field row", (save.top + save.bottom) / 2 >= name.top && (save.top + save.bottom) / 2 <= name.bottom)
        }
    }

    @Test
    fun legacyCategoryRouteWaitsForWorkspaceLayoutBeforePositioning() {
        composeRule.setContent {
            FinanceTrackerApp(demoMode = true, initialRoute = AppRoute(AppPage.CASH_CATEGORIES))
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag("cash-category.edit.food").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        val compact = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp < 600
        if (compact) composeRule.onNodeWithTag("management.directory.open").performClick()
        composeRule.onNodeWithTag("management.directory.item.categories").assertIsSelected()
    }
}
