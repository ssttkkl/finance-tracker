package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class CashImportOriginalSessionApiTest {
    @Test fun uploadIsolatesUnreadableFileAndOnlyExposesSafeSessionView() = testApplication {
        val f = CashImportOriginalSessionFixture("safe_view")
        f.parser = { upload -> if (upload.clientFileRef == "client-b") throw StatementDocumentFailure("statement_unreadable") else f.document }
        application { financeModule(f.base.database, cashImports = f.service); f.observe(this) }
        val metadata = buildJsonObject { put("files", buildJsonArray {
            add(buildJsonObject { put("client_file_ref", "client-a"); put("password", "private-password") })
            add(buildJsonObject { put("client_file_ref", "client-b") })
        }) }
        val response = f.upload(client, metadata.toString(), listOf(
            Triple("client-a", "synthetic-pwd=private-password.csv", "synthetic-original".encodeToByteArray()),
            Triple("client-b", "synthetic-b.csv", "bad-original".encodeToByteArray())))
        assertEquals(201, response.status.value, response.bodyAsText()); val view = response.apiBody()
        assertEquals(1, view.apiNumber("session_revision")); assertEquals("open", view.apiText("state")); assertEquals(JsonPrimitive(true), view["can_preview"])
        assertEquals(listOf("client-a", "client-b"), f.calls)
        assertEquals(listOf("ready", "failed"), view.apiArray("files").map { it.jsonObject.apiText("status") })
        assertEquals(listOf(JsonPrimitive(1), JsonNull), view.apiArray("files").map { it.jsonObject["recognized_row_count"] })
        assertEquals("statement_unreadable", view.apiArray("files")[1].jsonObject.apiArray("issues")[0].jsonObject.apiText("code"))
        assertEquals(2, f.opened); f.assertClosed()
        val read = f.base.get(client, f.sessionPath(view)); assertEquals(200, read.status.value); assertEquals(view, read.apiBody())
        assertEquals(listOf("client-a", "client-b"), f.calls)
        for (secret in listOf("private-password", "synthetic-original", "bad-original", "fixture-owner", "source_payload", "digest", "SQL", "parser_version")) assertFalse(secret in read.bodyAsText())
        val raw = f.base.store.raw(view.apiText("session_id")).decodeToString()
        assertFalse("private-password" in raw); assertFalse("synthetic-original" in raw)
        System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let { path ->
            val directory = Path.of(path); require(directory.isAbsolute); Files.createDirectories(directory)
            Files.writeString(directory.resolve("cash-import-session-view.json"), buildJsonObject {
                put("backend", if (f.base.database.sqlite) "sqlite" else "postgresql")
                put("status", read.status.value); put("body", read.apiBody())
            }.toString())
        }
    }

    @Test fun fileModificationTimeIsStagedButNotPublicBusinessData() = testApplication {
        val f = CashImportOriginalSessionFixture("timestamp")
        application { financeModule(f.base.database, cashImports = f.service); f.observe(this) }
        val response = f.upload(client, buildJsonObject { put("files", buildJsonArray { add(buildJsonObject {
            put("client_file_ref", "client-a"); put("last_modified_at_ms", 1725000000123L)
        }) }) }.toString())
        assertEquals(201, response.status.value); val view = response.apiBody()
        assertFalse("last_modified_at_ms" in view.apiArray("files")[0].jsonObject)
        assertEquals(1725000000123L, f.base.store.get(view.apiText("session_id"), f.base.workspace, f.base.user).files.single().lastModifiedAtMs)
    }

    @Test fun missingAlipayFundingExposesWalletFallback() = testApplication {
        val f = CashImportOriginalSessionFixture("wallet")
        f.parser = { f.document.copy(rows = f.document.rows.map { row -> row.copy(paymentMethod = "", fields = row.fields!! +
            ("source_payload" to ((row.fields["source_payload"] as Map<*, *>).entries.associate { it.key.toString() to it.value } + ("商品说明" to "")))) }) }
        application { financeModule(f.base.database, cashImports = f.service); f.observe(this) }
        val view = f.upload(client).apiBody(); assertEquals(JsonPrimitive(true), view["can_preview"])
        assertEquals("支付宝余额", view.apiArray("source_accounts").single().jsonObject.apiText("display_name"))
    }

    @Test fun knownFileFormatFailuresOfferSafeReselectAction() = testApplication {
        val f = CashImportOriginalSessionFixture("format")
        application { financeModule(f.base.database, cashImports = f.service); f.observe(this) }
        for (code in listOf("statement_header_not_found", "statement_header_invalid", "statement_encoding_unsupported")) {
            f.parser = { throw StatementDocumentFailure(code) }
            val response = f.upload(client); assertEquals(201, response.status.value)
            val issue = response.apiBody().apiArray("files").single().jsonObject.apiArray("issues").single().jsonObject
            assertEquals(code, issue.apiText("code")); assertEquals("reselect_file", issue.apiText("next_action"))
            assertTrue("重新导出" in issue.apiText("message")); assertFalse("表头" in issue.apiText("message")); assertFalse("编码" in issue.apiText("message"))
        }
    }

    @Test fun passwordRetryOnlyParsesReplacementAndKeepsClientSlot() = testApplication {
        val f = CashImportOriginalSessionFixture("password")
        f.parser = { upload -> if (upload.clientFileRef == "client-a" && upload.password != "synthetic-correct") throw StatementPdfPasswordFailure(true) else f.document }
        application { financeModule(f.base.database, cashImports = f.service); f.observe(this) }
        val view = f.upload(client, f.metadata("client-a", "client-b").toString(), listOf(
            Triple("client-a", "synthetic.pdf", "encrypted".encodeToByteArray()), Triple("client-b", "synthetic.csv", "okay".encodeToByteArray()))).apiBody()
        assertEquals("password_required", view.apiArray("files")[0].jsonObject.apiText("status"))
        val path = f.sessionPath(view) + "/files"
        val body = buildJsonObject { put("expected_session_revision", 1); put("file_ref", view.apiArray("files")[0].jsonObject.getValue("file_ref"))
            put("files", buildJsonArray { add(buildJsonObject { put("client_file_ref", "new-picker-ref"); put("password", "synthetic-correct") }) }) }
        val parts = listOf(Triple("new-picker-ref", "synthetic.pdf", "encrypted".encodeToByteArray()))
        val response = f.upload(client, body.toString(), parts, path); assertEquals(200, response.status.value)
        val updated = response.apiBody(); assertEquals(listOf("client-a", "client-b", "client-a"), f.calls)
        assertEquals(2, updated.apiNumber("session_revision")); assertEquals("client-a", updated.apiArray("files")[0].jsonObject.apiText("client_file_ref"))
        assertNotEquals(view.apiArray("files")[0].jsonObject["file_ref"], updated.apiArray("files")[0].jsonObject["file_ref"])
        assertEquals(view.apiArray("files")[1], updated.apiArray("files")[1])
        assertEquals(409, f.upload(client, body.toString(), parts, path).status.value)
        assertEquals(listOf("client-a", "client-b", "client-a"), f.calls)
    }

    @Test fun badUploadProtocolIsSafeAndDisposesEveryDeliveredFilePart() = testApplication {
        val f = CashImportOriginalSessionFixture("protocol")
        application { financeModule(f.base.database, cashImports = f.service); f.observe(this) }
        val inputs = listOf("{\"files\":[],\"files\":[]}", "{\"files\":[{\"client_file_ref\":\"client-a\",\"password\":123}]}",
            "{\"files\":[{\"client_file_ref\":\"client-a\"}],\"workspace_id\":\"private-workspace\"}",
            "{\"files\":[{\"client_file_ref\":\"client-a\"}],\"expected_session_revision\":1.0}",
            "{\"files\":[{\"client_file_ref\":\"client-a\"}],\"expected_session_revision\":true}",
            "{\"files\":[{\"client_file_ref\":\"client-a\"}],\"anything\":NaN}", "[]", "not json",
            "{\"files\":[{\"client_file_ref\":\"client-a\",\"last_modified_at_ms\":-1}]}",
            "{\"files\":[{\"client_file_ref\":\"client-a\",\"last_modified_at_ms\":1.0}]}",
            "{\"files\":[{\"client_file_ref\":\"client-a\",\"last_modified_at_ms\":true}]}",
            "{\"files\":[{\"client_file_ref\":\"client-a\"}],\"files\":[{\"client_file_ref\":\"client-a\"}]}")
        for (input in inputs) {
            f.resetParts(); val response = f.upload(client, input); assertEquals(422, response.status.value, input)
            assertEquals("import_payload_invalid", response.apiBody().apiError())
            assertFalse("private-workspace" in response.bodyAsText()); assertFalse("synthetic-original" in response.bodyAsText()); f.assertClosed()
        }
    }

    @Test fun alipayAccountGroupReferenceIsSessionScopedAndLabelIsMasked() = testApplication {
        val f = CashImportOriginalSessionFixture("alipay_group")
        val original = f.document
        fun document(owner: String, method: String) = original.copy(sourceAccountIdentifier = owner, sourceAccountLabel = owner,
            rows = original.rows.map { it.copy(paymentMethod = method) })
        val docs = mapOf("client-a" to document("alice@example.test", "支付宝余额"),
            "client-b" to document("alice@example.test", "余额宝"),
            "client-c" to document("bob@example.test", "支付宝余额"))
        f.parser = { upload -> docs.getValue(upload.clientFileRef) }
        application { financeModule(f.base.database, cashImports = f.service); f.observe(this) }
        val metadata = f.metadata("client-a", "client-b", "client-c").toString()
        val files = docs.keys.map { Triple(it, "$it.csv", it.encodeToByteArray()) }
        val first = f.upload(client, metadata, files)
        assertEquals(201, first.status.value, first.bodyAsText())
        val view = first.apiBody()
        val accounts = view.apiArray("source_accounts").map { it.jsonObject }
        assertEquals(3, accounts.size)
        fun account(label: String, display: String) = accounts.single {
            it.apiText("session_label") == label && it.apiText("display_name") == display
        }
        assertNotEquals(account("a***@example.test", "支付宝余额").apiText("session_group_ref"),
            account("b***@example.test", "支付宝余额").apiText("session_group_ref"))
        assertEquals(account("a***@example.test", "支付宝余额").apiText("session_group_ref"),
            account("a***@example.test", "余额宝").apiText("session_group_ref"))
        assertFalse("alice@example.test" in first.bodyAsText()); assertFalse("bob@example.test" in first.bodyAsText())

        val second = f.upload(client, metadata, files)
        assertEquals(201, second.status.value, second.bodyAsText())
        val secondWallet = second.apiBody().apiArray("source_accounts").map { it.jsonObject }.single {
            it.apiText("session_label") == "a***@example.test" && it.apiText("display_name") == "支付宝余额" }
        assertNotEquals(account("a***@example.test", "支付宝余额").apiText("session_group_ref"),
            secondWallet.apiText("session_group_ref"))
    }

    @Test fun alipayMissingIdentityKeepsEachFileGroupSeparate() = testApplication {
        val f = CashImportOriginalSessionFixture("alipay_missing_identity")
        val original = f.document
        f.parser = { original.copy(sourceAccountIdentifier = null, sourceAccountLabel = null,
            rows = original.rows.map { it.copy(paymentMethod = "支付宝余额") }) }
        application { financeModule(f.base.database, cashImports = f.service); f.observe(this) }
        val response = f.upload(client, f.metadata("client-a", "client-b").toString(), listOf(
            Triple("client-a", "one.csv", "one".encodeToByteArray()), Triple("client-b", "two.csv", "two".encodeToByteArray())))
        assertEquals(201, response.status.value, response.bodyAsText())
        val accounts = response.apiBody().apiArray("source_accounts").map { it.jsonObject }
        assertEquals(setOf("one.csv", "two.csv"), accounts.map { it.apiText("session_label") }.toSet())
        assertEquals(2, accounts.map { it.apiText("session_group_ref") }.toSet().size)
    }

    @Test fun wechatNicknameIsSessionOnlyAndFileScopeRouteIsRemoved() = testApplication {
        val f = CashImportOriginalSessionFixture("wechat_nickname")
        f.parser = { f.document.copy(sourceType = "wechat", sourceAccountIdentifier = null,
            sourceAccountLabel = "昵称甲", sourceNickname = "昵称甲") }
        application { financeModule(f.base.database, cashImports = f.service); f.observe(this) }
        val view = f.upload(client).apiBody(); val path = f.sessionPath(view)
        val filePath = path + "/files/" + view.apiArray("files")[0].jsonObject.apiText("file_ref")
        assertFalse("source_scope_label" in view.apiArray("files")[0].jsonObject)
        assertEquals("昵称甲", view.apiArray("source_accounts")[0].jsonObject.apiText("session_label"))
        val patched = client.patch(filePath) {
            header(HttpHeaders.Authorization, "Bearer ${f.base.token}"); contentType(ContentType.Application.Json)
            setBody("{\"expected_session_revision\":1,\"source_scope_label\":\"另一来源\"}")
        }
        // 路由未注册；Ktor 对无匹配方法返回 404，原 Python 保留路径方法返回 405。
        assertEquals(404, patched.status.value)
        assertEquals(listOf("client-a"), f.calls)
        val removed = client.delete(filePath) { header(HttpHeaders.Authorization, "Bearer ${f.base.token}"); header("If-Match", "\"1\"") }
        assertEquals(200, removed.status.value, removed.bodyAsText())
        val empty = f.base.get(client, path).apiBody()
        assertEquals(JsonArray(emptyList()), empty["files"]); assertEquals(JsonArray(emptyList()), empty["source_accounts"])
        assertEquals(JsonPrimitive(false), empty["can_preview"])
        assertEquals(200, client.delete(path) { header(HttpHeaders.Authorization, "Bearer ${f.base.token}") }.status.value)
        assertEquals("cancelled", f.base.get(client, path).apiBody().apiText("state"))
    }

    @Test fun uploadRejectsRemovedSourceScopeMetadata() = testApplication {
        val f = CashImportOriginalSessionFixture("scope_metadata")
        f.parser = { f.document.copy(sourceType = "wechat", sourceAccountIdentifier = null) }
        application { financeModule(f.base.database, cashImports = f.service); f.observe(this) }
        val result = f.upload(client, buildJsonObject { put("files", buildJsonArray { add(buildJsonObject {
            put("client_file_ref", "client-a"); put("source_scope_label", "另一来源")
        }) }) }.toString())
        assertEquals(422, result.status.value)
        assertEquals("import_payload_invalid", result.apiBody().apiError())
    }

    @Test fun viewerIsRejectedBeforeAnyFilePartOrParserIsOpened() = testApplication {
        val f = CashImportOriginalSessionFixture("viewer"); f.base.role("viewer")
        application { financeModule(f.base.database, cashImports = f.service); f.observe(this) }
        assertEquals(403, f.upload(client).status.value); assertEquals(emptyList<String>(), f.calls); assertEquals(0, f.opened)
    }

    @Test fun oversizedFileSkipsOnlyThatFileAndDisposesAllParts() = testApplication {
        val f = CashImportOriginalSessionFixture("oversize", limit = 8)
        application { financeModule(f.base.database, cashImports = f.service); f.observe(this) }
        val response = f.upload(client, f.metadata("client-a", "client-b").toString(), listOf(
            Triple("client-a", "synthetic-large.csv", ByteArray(20) { 'x'.code.toByte() }), Triple("client-b", "synthetic-small.csv", "okay".encodeToByteArray())))
        assertEquals(201, response.status.value); val view = response.apiBody(); val files = view.apiArray("files")
        assertEquals(listOf("failed", "ready"), files.map { it.jsonObject.apiText("status") })
        assertEquals("statement_file_too_large", files[0].jsonObject.apiArray("issues")[0].jsonObject.apiText("code"))
        assertEquals(listOf("client-b"), f.calls); assertEquals(JsonPrimitive(true), view["can_preview"]); f.assertClosed()
    }

    @Test fun otherOperatorCannotReadOrModifySession() = testApplication {
        val f = CashImportOriginalSessionFixture("foreign")
        application { financeModule(f.base.database, cashImports = f.service); f.observe(this) }
        val view = f.upload(client).apiBody(); f.base.role("editor", "original-import-other"); val path = f.sessionPath(view)
        assertEquals(404, f.base.get(client, path, "original-import-other").status.value)
        assertEquals(404, client.delete(path) { header(HttpHeaders.Authorization, "Bearer original-import-other") }.status.value)
        assertEquals(200, f.base.get(client, path).status.value)
    }

    @Test fun currentSuggestionIsDistinctFromSavedUserSelection() = testApplication {
        val f = CashImportOriginalSessionFixture("suggestion")
        application { financeModule(f.base.database, cashImports = f.service); f.observe(this) }
        val view = f.upload(client).apiBody(); val group = view.apiArray("source_accounts").single().jsonObject
        assertEquals(JsonNull, group["selection"]); assertEquals(JsonNull, group.getValue("suggestion").jsonObject["account_id"])
        f.base.database.write { connection ->
            val sql = connection.jooq(f.base.database.sqlite)
            sql.insertInto(STATEMENT_ACCOUNT_MAPPINGS).set(STATEMENT_ACCOUNT_MAPPINGS.WORKSPACE_ID, f.base.workspace)
                .set(STATEMENT_ACCOUNT_MAPPINGS.SOURCE_TYPE, "alipay").set(STATEMENT_ACCOUNT_MAPPINGS.IDENTITY_KIND, "funding_group_v1")
                .set(STATEMENT_ACCOUNT_MAPPINGS.SOURCE_ACCOUNT_KEY, group.apiText("source_account_ref")).set(STATEMENT_ACCOUNT_MAPPINGS.ACCOUNT_ID, 101L)
                .set(STATEMENT_ACCOUNT_MAPPINGS.REVISION, 1L).set(STATEMENT_ACCOUNT_MAPPINGS.CREATED_AT, f.base.now)
                .set(STATEMENT_ACCOUNT_MAPPINGS.UPDATED_AT, f.base.now).execute()
        }
        val selected = mapOf("source_account_ref" to group.apiText("source_account_ref"), "account_id" to "102", "expected_account_revision" to 1)
        val old = f.base.store.get(view.apiText("session_id"), f.base.workspace, f.base.user)
        f.base.store.replace(old.sessionId, old.workspaceId, old.userId, 1, old.files,
            mapOf("account_selections" to listOf(selected), "row_choices" to emptyList<Any?>(), "relation_choices" to emptyList<Any?>()), null)
        val response = f.base.get(client, f.sessionPath(view)); assertEquals(200, response.status.value)
        val account = response.apiBody().apiArray("source_accounts").single().jsonObject
        assertEquals("101", account.getValue("suggestion").jsonObject.apiText("account_id"))
        assertEquals(buildJsonObject { put("account_id", "102"); put("expected_account_revision", 1) }, account["selection"])
    }

    private fun expiry(consumed: Boolean) = testApplication {
        val f = CashImportOriginalSessionFixture(if (consumed) "consumed" else "expired")
        application { financeModule(f.base.database, cashImports = f.service); f.observe(this) }
        val view = f.upload(client).apiBody(); val session = view.apiText("session_id")
        val receipt = if (consumed) f.base.database.write { connection -> ReceiptRepository(connection, f.base.workspace, f.base.database.sqlite).create(
            user = f.base.user, key = "committed", requestDigest = "a".repeat(64), operation = "import", planId = "plan-1",
            sessionDigest = ParsedImportStore.digest(session.encodeToByteArray()), resultSummary = mapOf("status" to "no_changes"), results = emptyList<Any?>()) } else null
        f.base.now = f.base.now.plusSeconds(2 * 24 * 3600)
        val response = f.base.get(client, f.sessionPath(view))
        if (!consumed) assertEquals(410, response.status.value) else {
            assertEquals(200, response.status.value); val recovered = response.apiBody()
            assertEquals("committed", recovered.apiText("state")); assertEquals(receipt!!["receipt_id"], recovered["receipt_id"])
            assertEquals(JsonNull, recovered["session_revision"]); assertEquals(JsonNull, recovered["expires_at"])
            assertEquals(JsonArray(emptyList()), recovered["files"]); assertEquals(JsonArray(emptyList()), recovered["source_accounts"])
            assertEquals(JsonPrimitive(false), recovered["can_preview"])
        }
    }
    @Test fun expiredUnconsumedSessionReturnsGone() = expiry(false)
    @Test fun consumedSessionIsRecoveredBeforeExpiredTemporaryStorage() = expiry(true)

    @Test fun readFailuresAndCorsAreSafe() = testApplication {
        val f = CashImportOriginalSessionFixture("read_failure")
        application { financeModule(f.base.database, cashImports = f.service); f.observe(this) }
        f.base.mode = "session_read"
        val response = f.base.get(client, "/api/v1/cash-import/sessions/" + "a".repeat(43))
        assertEquals(503, response.status.value); assertFalse("SQL" in response.bodyAsText()); assertFalse("provider" in response.bodyAsText())
        assertTrue(f.base.faultCount > 0)
        val cors = client.options("/api/v1/cash-import/sessions/" + "a".repeat(43)) {
            header("Origin", "http://127.0.0.1:5173"); header("Access-Control-Request-Method", "DELETE"); header("Access-Control-Request-Headers", "If-Match")
        }
        assertEquals(200, cors.status.value)
    }
}
