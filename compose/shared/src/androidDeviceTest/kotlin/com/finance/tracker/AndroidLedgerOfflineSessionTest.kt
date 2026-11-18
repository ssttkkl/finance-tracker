package com.finance.tracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finance.tracker.business.contracts.Role
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.UserDto
import com.finance.tracker.business.contracts.WorkspaceDto
import com.finance.tracker.data.AndroidLedgerKeyVault
import com.finance.tracker.data.createAndroidLedgerFactCache
import com.finance.tracker.data.ledgerOfflinePrincipal
import com.finance.tracker.data.ledgerStorageId
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class AndroidLedgerOfflineSessionTest {
    @Test fun actualEncryptedSessionRecordReopensWithTheOriginalTokenAndClearsWithItsFacts() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val marker = UUID.randomUUID().toString()
        val session = SessionDto(UserDto("synthetic-$marker@example.test"), "workspace",
            listOf(WorkspaceDto("workspace", "Synthetic private workspace $marker", Role.EDITOR)))
        val scope = LedgerCacheScope("https://example.test", ledgerOfflinePrincipal(session.user.email), "workspace")
        val root = File(context.cacheDir, "ledger-session-$marker")
        val dir = File(root, "facts/${ledgerStorageId(scope)}")
        val vault = AndroidLedgerKeyVault(context, ledgerStorageId(scope))
        val token = "synthetic-private-token-$marker"
        try {
            val prepared = createAndroidLedgerFactCache(context, scope, root)
            try {
                prepared.replace(scope, localCashLedgerOwnerSnapshot())
                prepared.saveOfflineSession(scope, token, session)
                assertEquals(session, prepared.readOfflineSession(scope, token))
            } finally { prepared.close() }
            assertFails { prepared.clear(scope) }
            assertNotNull(vault.read()?.also { it.fill(0) })
            val encoded = File(dir, "ledger.db").readBytes()
            for (text in listOf(session.user.email, session.workspaces.single().name, token)) {
                assertFalse(encoded.asList().windowed(text.toByteArray().size).any { it == text.toByteArray().asList() })
            }
            val restored = createAndroidLedgerFactCache(context, scope, root, existingOnly = true)
            try {
                assertFails { prepared.clear(scope) }
                assertEquals(session, restored.readOfflineSession(scope, token))
                assertNull(restored.readOfflineSession(scope, token + "changed"))
            } finally { restored.clear(scope) }
            assertFalse(dir.exists())
            assertNull(vault.read())
            assertFails { restored.readOfflineSession(scope, token) }
        } finally { vault.erase(); root.deleteRecursively() }
    }
}
