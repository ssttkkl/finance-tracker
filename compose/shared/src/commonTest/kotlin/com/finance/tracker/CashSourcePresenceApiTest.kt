package com.finance.tracker

import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class CashSourcePresenceApiTest {
    @Test fun sourceBodyPresenceKeepsUnknownSeparateFromExplicitClear() = runTest {
        for (presence in listOf<Boolean?>(null, false, true)) {
            val issue = buildJsonObject {
                put("issue_id", "31"); put("revision", 1); put("code", "source_update_pending"); put("message", "账单有更新")
                put("blocking_scope", "none"); put("next_action", "resolve_issue")
                put("allowed_resolutions", buildJsonArray { add("keep_existing") })
                put("pending_fields", buildJsonObject {}); put("pending_source_payload", JsonNull)
                if (presence != null) put("pending_source_payload_present", presence)
            }
            val response = buildJsonObject {
                put("transaction_id", "11"); put("record_origin", "import"); put("state", "posted"); put("row_revision", 2)
                put("confirmation_status", "pending"); put("fields", buildJsonObject {}); put("editable_fields", JsonArray(emptyList()))
                put("issues", JsonArray(listOf(issue))); put("relations", JsonArray(emptyList()))
            }
            val api = FinanceApiClient({ "https://api.example.test" }, object : TokenStore {
                override suspend fun get() = "test-token"
                override suspend fun set(value: String) = Unit
                override suspend fun clear() = Unit
            }, MockEngine { respond(response.toString(), headers = headersOf(HttpHeaders.ContentType, "application/json")) })
            val record = KtorCashMaintenanceRepository(api).record("11")
            assertEquals(presence, record.issues.single().pendingSourcePayloadPresent)
            assertNull(record.issues.single().pendingSourcePayload)
            api.close()
        }
    }
}
