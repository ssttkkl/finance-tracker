package com.finance.tracker

import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import com.finance.tracker.domain.FileReadLimitExceeded
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.writeString
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class CashImportContextApiTest {
    @Test fun partialSessionRetainsExistingSelectionAndOpaqueIds() {
        val value = apiJson.decodeFromString<CashImportSessionDto>(SESSION)
        assertTrue(value.canPreview)
        assertEquals(listOf("ready", "password_required"), value.files.map { it.status })
        assertNull(value.files.last().recognizedRowCount)
        val group = value.sourceAccounts.single()
        assertNull(group.suggestion.accountId)
        assertNull(group.suggestion.expectedAccountRevision)
        assertEquals("none", group.suggestion.basis)
        assertEquals("昵称甲", group.sessionLabel)
        assertEquals("account-group-one", group.sessionGroupRef)
        assertEquals("102", group.selection?.accountId)
        assertEquals("人工备注", value.lastPreviewInput!!["row_choices"]!!.jsonArray.single().jsonObject["patch"]!!.jsonObject["note"]!!.jsonPrimitive.content)
    }

    @Test fun consumedSessionHasNullableRevisionAndPermanentResultReference() {
        val value = apiJson.decodeFromString<CashImportSessionDto>("""{"session_id":"s1","session_revision":null,"expires_at":null,"state":"committed","files":[],"source_accounts":[],"last_preview_input":null,"last_plan_id":null,"can_preview":false,"receipt_id":"receipt-one"}""")
        assertNull(value.sessionRevision)
        assertNull(value.expiresAt)
        assertEquals("receipt-one", value.receiptId)
        assertFalse(value.canPreview)
    }

    @Test fun alreadyCommittedErrorKeepsSafeResultReferenceWithoutPrivateText() = runTest {
        val client = FinanceApiClient({ "https://api.example.test" }, ContextTokenStore(), MockEngine {
            respond("""{"error":{"code":"session_committed","message":"private financial text"},"receipt_id":"receipt-one"}""", HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val failure = assertFailsWith<ApiFailure> { client.readCashMutationReceipt("receipt-one") }
        assertEquals("session_committed", failure.code)
        assertEquals(409, failure.status)
        assertEquals("receipt-one", failure.receiptId)
        assertFalse("private" in failure.toString())
        client.close()
    }

    @Test fun malformedRecoveryReferencesAreNotRetained() = runTest {
        for (reference in listOf("123", "null", "\"private/path\"", "\"" + "a".repeat(37) + "\"")) {
            val client = FinanceApiClient({ "https://api.example.test" }, ContextTokenStore(), MockEngine {
                respond("{\"error\":{\"code\":\"session_committed\"},\"receipt_id\":" + reference + "}",
                    HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json"))
            })
            val failure = assertFailsWith<ApiFailure> { client.readCashMutationReceipt("receipt-one") }
            assertNull(failure.receiptId)
            client.close()
        }
    }

    @Test fun boundedSourceReadsOnlyLimitAndOneProbeByteAndAlwaysCloses() {
        val input = Buffer().apply { writeString("abcdefgh") }
        var consumed = 0L
        var closed = false
        val source = object : RawSource {
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = input.readAtMostTo(sink, byteCount).also { if (it > 0) consumed += it }
            override fun close() { closed = true }
        }
        assertFailsWith<FileReadLimitExceeded> { readBoundedImportSource(source, 4) }
        assertEquals(5L, consumed)
        assertEquals(3L, input.size)
        assertTrue(closed)
        closed = false
        val throwing = object : RawSource {
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = error("private read failure")
            override fun close() { closed = true }
        }
        assertFailsWith<IllegalStateException> { readBoundedImportSource(throwing, 4) }
        assertTrue(closed)
        assertContentEquals("1234".encodeToByteArray(), readBoundedImportSource(Buffer().apply { writeString("1234") }, 4))
    }

    @Test fun invalidReadLimitStillClosesAnAlreadyOpenedSource() {
        var closed = false
        val source = object : RawSource {
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = fail("Invalid limit must not read")
            override fun close() { closed = true }
        }
        assertFailsWith<IllegalArgumentException> { readBoundedImportSource(source, -1) }
        assertTrue(closed)
    }

    companion object {
        private val SESSION = """{"session_id":"s1","session_revision":3,"expires_at":"2026-10-23T10:00:00Z","state":"open","files":[
          {"file_ref":"f1","client_file_ref":"a","display_name":"one.csv","source_type":"wechat","status":"ready","recognized_row_count":1,"issues":[],"can_retry":false},
          {"file_ref":"f2","client_file_ref":"b","display_name":"two.pdf","source_type":null,"status":"password_required","recognized_row_count":null,"issues":[{"code":"statement_password_required","message":"请补充密码","field_path":null,"blocking_scope":"file","next_action":"reselect_file"}],"can_retry":true}],
          "source_accounts":[{"source_account_ref":"source-one","source_type":"wechat","session_group_ref":"account-group-one","session_label":"昵称甲","display_name":"微信零钱","currencies":["CNY"],"row_count":1,"suggestion":{"account_id":null,"expected_account_revision":null,"basis":"none"},"selection":{"account_id":"102","expected_account_revision":5}}],
          "last_preview_input":{"account_selections":[],"row_choices":[{"row_ref":"f1:r1","action":"apply","patch":{"note":"人工备注"}}],"relation_choices":[]},"last_plan_id":"ip_latest","can_preview":true,"receipt_id":null}""".trimIndent()
    }
}
private class ContextTokenStore : TokenStore {
    override suspend fun get(): String? = "synthetic-token"
    override suspend fun set(value: String) = Unit
    override suspend fun clear() = Unit
}