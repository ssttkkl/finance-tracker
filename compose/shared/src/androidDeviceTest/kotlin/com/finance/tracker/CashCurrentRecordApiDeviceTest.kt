package com.finance.tracker

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finance.tracker.app.FinanceTrackerApp
import com.finance.tracker.core.AndroidTokenStoreContext
import com.finance.tracker.core.createPlatformTokenStore
import com.finance.tracker.data.FinanceApiClient
import com.finance.tracker.data.KtorCashMaintenanceRepository
import com.finance.tracker.data.fetchCashPage
import com.finance.tracker.data.CashFiltersDto
import com.finance.tracker.domain.StructuredText
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 仅在显式指定仓库外的合成 API 及会话文件时运行，不创建服务或探测真实账本。 */
@RunWith(AndroidJUnit4::class)
class CashCurrentRecordApiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actualAppRouteSavesDraftAndReturnsToLedger() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val origin = arguments.getString("cashQaApiOrigin")
        val statePath = arguments.getString("cashQaStateFile")
        assumeTrue("Explicit synthetic cash API QA parameters are required", origin != null && statePath != null)
        val state = JSONObject(File(requireNotNull(statePath)).readText())
        val context = instrumentation.targetContext
        AndroidTokenStoreContext.install(context)
        val tokens = createPlatformTokenStore()
        runBlocking { tokens.set(state.getString("token")) }
        val api = FinanceApiClient({ requireNotNull(origin) }, tokens)
        try {
            val repository = KtorCashMaintenanceRepository(api)
            val id = state.getString("record_id")
            val before = runBlocking { repository.record(id) }
            val row = runBlocking { api.fetchCashPage(CashFiltersDto()).items.single { it.transactionId == id } }
            compose.setContent { FinanceTrackerApp(apiOrigin = requireNotNull(origin)) }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("ledger.record-${row.projectionId}").fetchSemanticsNodes().isNotEmpty() }
            val width = context.resources.configuration.screenWidthDp
            if (width < 600) compose.onNodeWithTag("ledger.record-${row.projectionId}").performScrollTo().performClick()
            else compose.onAllNodesWithTag("ledger.open-record").onFirst().performScrollTo().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("cash.current.detail").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("cash.current.pending-draft").assertCountEquals(1)
            val sources = compose.onNodeWithTag("cash.current.sources-toggle")
            val foldedLabel = sources.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
            sources.performScrollTo().performClick()
            compose.onNodeWithTag("cash.current.process-draft").performScrollTo().performClick()
            compose.onNodeWithTag("cash.draft.save").performScrollTo().assertIsNotEnabled()
            val note = "设备保存 $width ${System.currentTimeMillis()}"
            compose.onNodeWithTag("cash.draft.note").performScrollTo().performTextReplacement(note)
            compose.onNodeWithTag("cash.draft.save").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(20_000) { runBlocking { (repository.record(id).entry?.fields?.fields?.get("note") as? StructuredText)?.value == note } }
            val after = runBlocking { repository.record(id) }
            check(after.state == "draft")
            check(after.fields.fields["amount"] == before.fields.fields["amount"])
            check(after.fields.fields["components"] == before.fields.fields["components"])
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("cash.draft.editor").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("cash.current.sources-toggle").performScrollTo().assertTextEquals(foldedLabel).performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText(note).fetchSemanticsNodes().size >= 2 }
            screenshot("cash-current-$width-detail.png")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("cash.current.detail").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("ledger.record-${row.projectionId}").assertIsDisplayed()
            screenshot("cash-current-$width-return.png")
        } finally { api.close() }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), name)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
