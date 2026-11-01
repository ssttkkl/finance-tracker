package com.finance.tracker.backend

import java.lang.reflect.InvocationTargetException
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.*
import kotlinx.serialization.json.*

class ParsedImportStagingOriginalTest {
    private class ObservedStore(private val delegate: ParsedImportStore, clock: () -> Instant, codec: ParsedImportCodec) :
        ParsedImportStore(60, clock, codec) {
        var failRead = false
        private fun invoke(name: String, vararg arguments: Any?): Any? {
            val method = delegate.javaClass.declaredMethods.single { it.name == name }
            method.isAccessible = true
            try { return method.invoke(delegate, *arguments) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        }
        override fun readRaw(id: String): ParsedImportObject {
            if (failRead) throw ParsedImportStorageUnavailable()
            return invoke("readRaw", id) as ParsedImportObject
        }
        override fun writeRaw(id: String, content: ByteArray, expected: String?, expiresAt: Instant) {
            invoke("writeRaw", id, content, expected, expiresAt)
        }
        override fun deleteRaw(id: String) { invoke("deleteRaw", id) }
        override fun sweepExpired(cursor: String?, limit: Int, shouldStop: () -> Boolean) =
            delegate.sweepExpired(cursor, limit, shouldStop)
    }

    private class Fixture(val r2: Boolean, maxBytes: Int = 64 * 1024 * 1024, maxParsedBytes: Int = 25 * 1024 * 1024) {
        val client = R2ParsedImportStoreTest.Client()
        var now: Instant
            get() = client.now
            set(value) { client.now = value }
        val codec = ParsedImportCodec(maxBytes, maxParsedBytes)
        val delegate: ParsedImportStore = if (r2) R2ParsedImportStore(client, "private-imports", ttlSeconds = 60,
            clock = { now }, codec = codec) else InMemoryParsedImportStore(60, { now }, codec)
        val store = ObservedStore(delegate, { now }, codec)
        @Suppress("UNCHECKED_CAST")
        val objects: MutableMap<String, ByteArray>
            get() = if (r2) client.objects else delegate.javaClass.getDeclaredField("entries").let {
                it.isAccessible = true; it.get(delegate) as MutableMap<String, ByteArray>
            }
        fun key(id: String) = if (r2) "cash-import/parsed/" + tokenDigest(id) + "/session" else id
        fun create(files: List<ParsedImportFile> = listOf(file()), workspace: String = "workspace-a", user: String = "user-a") =
            store.create(workspace, user, files)
        fun get(value: ParsedImportSession, workspace: String = value.workspaceId, user: String = value.userId) =
            store.get(value.sessionId, workspace, user)
        fun replace(value: ParsedImportSession, files: List<ParsedImportFile> = value.files,
            preview: Map<String, Any?> = value.previewInput, plan: Map<String, Any?>? = value.plan) =
            store.replace(value.sessionId, value.workspaceId, value.userId, value.revision, files, preview, plan)!!
        fun damage(value: ParsedImportSession, transform: (JsonObject) -> JsonObject) {
            val key = key(value.sessionId)
            val json = Json.parseToJsonElement(objects.getValue(key).decodeToString()).jsonObject
            objects[key] = transform(json).toString().encodeToByteArray()
        }
        fun damageRow(value: ParsedImportSession, transform: (JsonObject) -> JsonElement) = damage(value) { root ->
            val file = root.getValue("files").jsonArray.single().jsonObject
            val document = file.getValue("document").jsonObject
            val row = document.getValue("rows").jsonArray.single().jsonObject
            val changedDocument = JsonObject(document + ("rows" to JsonArray(listOf(transform(row)))))
            val changedFile = JsonObject(file + ("document" to changedDocument))
            JsonObject(root + ("files" to JsonArray(listOf(changedFile))))
        }
    }

    companion object {
        private fun coverage() = CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1")
        private fun file(ref: String = "f1", clientRef: String = "client-f1") = ParsedImportFile(ref, "a".repeat(64), "parsed",
            ParsedCashDocument("alipay", "csv:0", "native-v1", listOf(ParsedCashRow(8,
                mapOf("amount" to "-20.00", "note" to "\u539f\u59cb\u8bf4\u660e", "source_payload" to mapOf("\u91d1\u989d" to "20.00")),
                "fixture-bank", "001")), "fixture-owner", "fixture-owner", coverage(), 1, 1),
            clientFileRef = clientRef, filename = "synthetic.csv", size = 100, sourceType = "alipay")
        private fun changedRow(file: ParsedImportFile, transform: (ParsedCashRow) -> ParsedCashRow) =
            file.copy(document = file.document!!.copy(rows = listOf(transform(file.document.rows.single()))))
    }
    private fun each(maxBytes: Int = 64 * 1024 * 1024, maxParsedBytes: Int = 25 * 1024 * 1024,
        block: (Fixture) -> Unit) {
        for (r2 in listOf(false, true)) block(Fixture(r2, maxBytes, maxParsedBytes))
    }
    private fun invalid(code: String? = null, block: () -> Unit) {
        val failure = assertFailsWith<ParsedImportDataError>(block = block)
        if (code != null) assertEquals(code, failure.code)
    }

    @Test fun documentPageProofAndLocatorRoundtripWithoutOriginalFile() = each { f ->
        val coverage = coverage().copy(documentKey = "f".repeat(64), pageTotal = 3,
            pageFingerprints = mapOf("2" to "e".repeat(64)))
        val file = changedRow(file()) { it.copy(documentPosition = "p2:t0:r1") }
        val value = f.create(listOf(file.copy(document = file.document!!.copy(coverage = coverage))))
        val restored = f.get(value).files.single().document!!
        assertEquals(coverage, restored.coverage)
        assertEquals("p2:t0:r1", restored.rows.single().documentPosition)
    }

    @Test fun editContextInheritsOriginalLifetimeAndClearsPrivateDataOnCancel() = each { f ->
        val original = f.create(); f.now = f.now.plusSeconds(30)
        val child = f.store.create(original.workspaceId, original.userId, emptyList(), "mutation",
            mapOf("synthetic_proof" to "a".repeat(64)), original.createdAt, original.expiresAt)
        assertEquals(original.createdAt, child.createdAt); assertEquals(original.expiresAt, child.expiresAt)
        assertEquals("mutation", f.get(child).workflowKind)
        @Suppress("UNCHECKED_CAST") val privateData = child.contextData as MutableMap<String, Any?>
        privateData["synthetic_proof"] = "caller mutation"
        assertEquals(mapOf("synthetic_proof" to "a".repeat(64)), f.get(child).contextData)
        val changed = f.replace(child, preview = mapOf("synthetic" to true))
        assertEquals(original.expiresAt, changed.expiresAt); assertEquals("mutation", changed.workflowKind)
        val closed = f.store.cancel(child.sessionId, child.workspaceId, child.userId, changed.revision)
        assertTrue(closed.contextData.isEmpty()); assertTrue(closed.files.isEmpty()); assertTrue(closed.cancelled)
        f.now = original.expiresAt
        assertFailsWith<ParsedImportExpired> { f.get(closed) }
    }

    @Test fun mutationContextHasNoFilesAndKeepsOwnerCasAndOriginalTtl() = each { f ->
        val current = f.store.create("workspace-a", "user-a", emptyList(), "mutation",
            mapOf("transaction_id" to "1001", "version" to 1))
        assertTrue(current.files.isEmpty()); assertEquals("mutation", f.get(current).workflowKind)
        assertFailsWith<ParsedImportForbidden> { f.get(current, user = "user-b") }
        f.now = f.now.plusSeconds(30)
        val changed = f.replace(current, preview = mapOf("patch" to mapOf("note" to "\u5408\u6210\u7ef4\u62a4\u6b63\u6587")),
            plan = mapOf("plan_id" to "synthetic-plan"))
        assertEquals(current.expiresAt, changed.expiresAt); assertTrue(changed.files.isEmpty())
        assertFailsWith<ParsedImportRevisionConflict> { f.replace(current, plan = emptyMap()) }
        val closed = f.store.cancel(current.sessionId, current.workspaceId, current.userId, changed.revision)
        assertTrue(closed.files.isEmpty()); assertTrue(closed.contextData.isEmpty())
        assertTrue(closed.previewInput.isEmpty()); assertNull(closed.plan)
    }

    @Test fun invalidContextKindOrLifetimeCannotBeWritten() = each { f ->
        for ((kind, start, end) in listOf(Triple("arbitrary", 0L, 60L), Triple("mutation", 0L, 1900L),
            Triple("mutation", 10L, 60L), Triple("mutation", -60L, -1L))) {
            invalid { f.store.create("workspace-a", "user-a", listOf(file()), kind,
                createdAt = f.now.plusSeconds(start), expiresAt = f.now.plusSeconds(end)) }
        }
    }

    @Test fun legacyContextWithoutKindIsPlainImport() = each { f ->
        val value = f.create()
        f.damage(value) { root ->
            val file = root.getValue("files").jsonArray.single().jsonObject
            JsonObject(root - "workflow_kind" - "context_data" + ("files" to JsonArray(listOf(JsonObject(file - "is_subset")))))
        }
        val restored = f.get(value)
        assertEquals("import", restored.workflowKind); assertTrue(restored.contextData.isEmpty())
        assertFalse(restored.files.single().isSubset)
    }

    @Test fun escapedJsonOverflowKeepsSizeReasonAndPreviousPlan() = each(12000, 3000) { f ->
        val first = f.create()
        invalid("import_session_draft_too_large") { f.replace(first, plan = mapOf("synthetic" to "\"".repeat(7000))) }
        assertEquals(BigInteger.ONE, f.get(first).revision); assertNull(f.get(first).plan)
    }

    @Test fun datetimeInSourceFieldsKeepsSafeInvalidJsonFailure() = each { f ->
        val file = changedRow(file()) { it.copy(fields = mapOf("occurred_at" to f.now)) }
        invalid("import_session_json_invalid") { f.create(listOf(file)) }
    }

    @Test fun parsedSessionRoundtripIsDetachedAndNeverContainsUploadBytes() = each { f ->
        val value = f.create()
        assertEquals(BigInteger.ONE, value.revision); assertEquals(43, value.sessionId.length)
        assertEquals(value, f.get(value))
        @Suppress("UNCHECKED_CAST") val fields = value.files.single().document!!.rows.single().fields as MutableMap<String, Any?>
        fields["note"] = "caller mutation"
        assertEquals("\u539f\u59cb\u8bf4\u660e", f.get(value).files.single().document!!.rows.single().fields!!["note"])
        if (f.r2) for (body in f.client.objects.values) {
            val json = Json.parseToJsonElement(body.decodeToString()).jsonObject
            val file = json.getValue("files").jsonArray.single().jsonObject
            assertEquals(JsonPrimitive("-20.00"), file.getValue("document").jsonObject.getValue("rows")
                .jsonArray.single().jsonObject.getValue("fields").jsonObject["amount"])
            assertFalse("content" in file); assertFalse("password" in file)
            assertFalse(body.decodeToString().contains(value.sessionId))
        }
        assertEquals(f.client.gets, f.client.closed)
    }

    @Test fun rowLevelSourceScopeSurvivesBothParsedStores() = each { f ->
        val file = changedRow(file()) { it.copy(sourceAccountIdentifier = "6222000000001234") }
        val value = f.create(listOf(file.copy(sourceType = "icbc_credit",
            document = file.document!!.copy(sourceType = "icbc_credit", sourceAccountIdentifier = null))))
        assertEquals("6222000000001234", f.get(value).files.single().document!!.rows.single().sourceAccountIdentifier)
    }

    @Test fun sourceExplanationRoundtripIsDetached() = each { f ->
        val explanation = mutableMapOf<String, Any?>("txn_id" to "001", "_is_refund" to false)
        val file = changedRow(file()) { it.copy(sourceExplanation = explanation) }
        val value = f.create(listOf(file)); explanation["txn_id"] = "mutated"
        assertEquals(mapOf("txn_id" to "001", "_is_refund" to false), f.get(value).files.single().document!!.rows.single().sourceExplanation)
    }

    @Test fun oldParsedObjectsWithoutSourceExplanationRemainReadable() = each { f ->
        val value = f.create()
        f.damageRow(value) { JsonObject(it - "source_explanation") }
        assertTrue(f.get(value).files.single().document!!.rows.single().sourceExplanation.isEmpty())
    }

    @Test fun typedSourceExplanationRejectsRepresentableInvalidValuesAndCorruptNull() = each { f ->
        val value = f.create()
        for (bad in listOf(mapOf("password" to "secret"), mapOf("note" to "generated"),
            mapOf("txn_id" to emptyList<Any>()), mapOf("_is_refund" to 1))) {
            invalid { f.replace(value, files = listOf(changedRow(value.files.single()) { it.copy(sourceExplanation = bad) })) }
            assertEquals(value, f.get(value))
        }
        val damaged = f.create()
        f.damageRow(damaged) { JsonObject(it + ("source_explanation" to JsonNull)) }
        assertFailsWith<ParsedImportStorageUnavailable> { f.get(damaged) }
    }

    @Test fun typedRowScopeRejectsInvalidStringsAndCorruptNonStrings() = each { f ->
        val value = f.create()
        for (owner in listOf("", "x".repeat(257))) {
            invalid { f.replace(value, files = listOf(changedRow(value.files.single()) { it.copy(sourceAccountIdentifier = owner) })) }
            assertEquals(value, f.get(value))
        }
        for (owner in listOf(JsonPrimitive(123), JsonArray(emptyList()))) {
            val damaged = f.create()
            f.damageRow(damaged) { JsonObject(it + ("source_account_identifier" to owner)) }
            assertFailsWith<ParsedImportStorageUnavailable> { f.get(damaged) }
        }
    }

    @Test fun replacementAtomicallyChangesFilesInputAndPlanAndRejectsStaleRevision() = each { f ->
        val first = f.create()
        val second = f.replace(first, listOf(file("replacement")), mapOf("rows" to emptyList<Any>()),
            mapOf("plan_id" to "plan-1", "items" to emptyList<Any>()))
        assertEquals(BigInteger.TWO, second.revision); assertEquals("replacement", second.files.single().fileRef)
        assertEquals("plan-1", second.plan!!["plan_id"])
        assertFailsWith<ParsedImportRevisionConflict> { f.replace(first) }
        assertEquals(second, f.get(first))
        if (f.r2) { assertEquals("*", f.client.writes.first().second); assertNotNull(f.client.writes.last().first) }
    }

    @Test fun cancelClearsFinancialBodyAndCannotBeReopenedByStalePublish() = each { f ->
        val first = f.create()
        val closed = f.store.cancel(first.sessionId, "workspace-a", "user-a", 1)
        assertTrue(closed.cancelled); assertEquals(BigInteger.TWO, closed.revision)
        assertTrue(closed.files.isEmpty()); assertNull(closed.plan); assertTrue(closed.previewInput.isEmpty())
        assertFailsWith<ParsedImportRevisionConflict> { f.replace(first) }
        assertFailsWith<ParsedImportClosed> { f.replace(closed, listOf(file())) }
        assertTrue(f.get(first).cancelled)
    }

    @Test fun revisionChangesNeverExtendTtlAndExpiryDeletesBody() = each { f ->
        val first = f.create(); f.now = f.now.plusSeconds(59)
        val second = f.replace(first); assertEquals(first.expiresAt, second.expiresAt)
        f.now = f.now.plusSeconds(1)
        assertFailsWith<ParsedImportExpired> { f.get(first) }
        assertFailsWith<ParsedImportNotFound> { f.get(first) }
        assertTrue(f.client.objects.isEmpty())
    }

    @Test fun payloadOverflowLeavesPreviousStateUntouched() = each(maxBytes = 2500) { f ->
        val first = f.create()
        invalid("import_session_draft_too_large") { f.replace(first, plan = mapOf("large" to "x".repeat(3000))) }
        assertEquals(first, f.get(first))
    }

    @Test fun onlyJsonBusinessValuesCanBeStaged() = each { f ->
        val first = f.create()
        for (bad in listOf("original upload bytes".encodeToByteArray(), mapOf("amount" to Double.NaN),
            mapOf("nested" to "password".encodeToByteArray()))) {
            invalid { f.replace(first, plan = mapOf("payload" to bad)) }
            assertEquals(first, f.get(first))
        }
    }

    @Test fun fileLimitsAndUniqueReferencesAreCheckedBeforeWrite() = each { f ->
        for (files in listOf(emptyList(), (0..20).map { file("f-$it", "c-$it") }, listOf(file(), file()))) {
            invalid { f.create(files) }; assertTrue(f.client.objects.isEmpty()); assertTrue(f.objects.isEmpty())
        }
    }

    @Test fun nestedInvalidJsonIsRejectedWithoutLeakingARecursionError() = each { f ->
        val first = f.create(); val cycle = mutableMapOf<String, Any?>(); cycle["cycle"] = cycle
        invalid("import_session_json_invalid") { f.replace(first, plan = cycle) }
        assertEquals(first, f.get(first))
    }

    @Test fun backgroundSweepRemovesUnvisitedExpiredSessionsInBoundedPages() = each { f ->
        val expired = List(3) { f.create() }; f.now = f.now.plusSeconds(30)
        val live = f.create(); f.now = f.now.plusSeconds(30)
        var scanned = 0; var deleted = 0; var cursor: String? = null
        do {
            val result = f.store.sweepExpired(cursor, 2)
            assertTrue(result.scanned <= 2); scanned += result.scanned; deleted += result.deleted
            cursor = result.nextCursor
        } while (cursor != null)
        assertEquals(4, scanned); assertEquals(3, deleted)
        if (f.r2) {
            assertEquals(0, f.client.gets); assertEquals(4, f.client.heads.size)
            assertTrue(f.client.lists.all { it.first == "cash-import/parsed/" && it.second == 2 })
            val formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx").withZone(ZoneOffset.UTC)
            for (item in expired) assertEquals(formatter.format(item.expiresAt), f.client.metadata.getValue(f.key(item.sessionId))["expires-at"])
        } else assertEquals(setOf(live.sessionId), f.objects.keys)
        assertEquals(live, f.get(live))
    }

    @Test fun sweepDoesNotExtendExpiryAfterALateEdit() = each { f ->
        val first = f.create(); f.now = f.now.plusSeconds(50)
        f.replace(first, preview = mapOf("changed" to true)); f.now = f.now.plusSeconds(10)
        assertEquals(1, f.store.sweepExpired(limit = 10).deleted)
    }

    @Test fun wechatScopeRoundtripsAndInvalidScopeNeverReplacesSession() = each { f ->
        val file = file().let { it.copy(sourceType = "wechat", document = it.document!!.copy(sourceType = "wechat"),
            sourceScopeLabel = "\u5de5\u4f5c\u5fae\u4fe1") }
        val current = f.create(listOf(file))
        assertEquals("\u5de5\u4f5c\u5fae\u4fe1", f.get(current).files.single().sourceScopeLabel)
        for (bad in listOf(file.copy(sourceScopeLabel = "  \u5de5\u4f5c\u5fae\u4fe1  "),
            file.copy(sourceType = "alipay", document = file.document!!.copy(sourceType = "alipay")))) {
            invalid("import_session_file_invalid") { f.replace(current, listOf(bad)) }
        }
        assertEquals(current, f.get(current))
    }

    @Test fun r2SweepIgnoresUnrelatedKeysAndCanCleanPreMetadataObjects() {
        val f = Fixture(true)
        for (key in listOf("cash-import/files/original", "cash-import/parsed/unrelated",
            "elsewhere/parsed/" + "a".repeat(64) + "/session")) f.client.objects[key] = "must not read or delete".encodeToByteArray()
        val old = "cash-import/parsed/" + "b".repeat(64) + "/session"
        f.client.objects[old] = "expired financial body must not be downloaded".encodeToByteArray()
        f.client.modified[old] = f.now.minusSeconds(1800)
        assertEquals(1, f.store.sweepExpired(limit = 100).deleted); assertEquals(3, f.client.objects.size)
        assertEquals(0, f.client.gets); assertEquals(listOf(old), f.client.heads)
    }

    @Test fun typedSweepLimitRejectsInvalidIntegersAndDoesNotAcceptOtherTypes() = each { f ->
        for (limit in listOf(0, 201)) invalid("import_session_sweep_invalid") { f.store.sweepExpired(limit = limit) }
        val method = ParsedImportStore::class.java.declaredMethods.single { it.name == "sweepExpired" }
        assertEquals(Int::class.javaPrimitiveType, method.parameterTypes[1])
    }

    @Test fun typedNestedDocumentAndCorruptWireFailClosed() = each { f ->
        val first = f.create()
        invalid { f.replace(first, listOf(changedRow(first.files.single()) { it.copy(fields = null) })) }
        assertEquals(first, f.get(first))
        assertEquals(Map::class.java, ParsedCashRow::class.java.getMethod("getFields").returnType)
        assertEquals(List::class.java, ParsedCashDocument::class.java.getMethod("getRows").returnType)
        for (wrongRow in listOf(true, false)) {
            val damaged = f.create()
            f.damageRow(damaged) {
                if (wrongRow) buildJsonObject { put("wrong", "row structure") }
                else JsonObject(it + ("fields" to JsonPrimitive("invalid fields")))
            }
            assertFailsWith<ParsedImportStorageUnavailable> { f.get(damaged) }
        }
    }

    @Test fun nonFinancialJsonNumbersCanRoundtripInSourceEvidence() = each { f ->
        val first = f.create()
        val second = f.replace(first, plan = mapOf("evidence" to mapOf("score" to 0.95)))
        assertEquals(second, f.get(first))
    }

    @Test fun unpairedUnicodeSurrogateHasSafeErrorAndPreservesCurrentRevision() = each { f ->
        val first = f.create(); val surrogate = 0xd800.toChar().toString()
        for (plan in listOf(mapOf("note" to surrogate), mapOf(surrogate to "value"))) {
            invalid("import_session_json_invalid") { f.replace(first, plan = plan) }
            assertEquals(first, f.get(first))
        }
    }

    @Test fun jsonScalarDepthBoundaryIsPreserved() = each { f ->
        for (container in listOf("list", "dict")) {
            val first = f.create(); var value: Any? = "\u6b63\u5e38\u5b57\u6bb5"
            repeat(62) { value = if (container == "list") listOf(value) else mapOf("value" to value) }
            val accepted = f.replace(first, plan = mapOf("nested" to value))
            assertEquals(mapOf("nested" to value), f.get(accepted).plan)
            value = if (container == "list") listOf(value) else mapOf("value" to value)
            invalid("import_session_json_invalid") { f.replace(accepted, plan = mapOf("nested" to value)) }
            assertEquals(accepted, f.get(accepted))
        }
    }

    @Test fun planSnapshotReadsDoNotRestoreUnrelatedFilesAndRemainIsolated() = each { f ->
        val first = f.create(workspace = "w", user = "u")
        val plan = mapOf("plan_id" to "synthetic-plan", "items" to listOf(mapOf("value" to "original")))
        f.replace(first, preview = emptyMap(), plan = plan)
        f.damageRow(first) { JsonObject(it + ("fields" to JsonPrimitive("fail if unrelated row restored"))) }
        assertFailsWith<ParsedImportStorageUnavailable> { f.get(first) }
        val snapshot = f.store.getPlanSnapshot(first.sessionId, "w", "u")
        assertTrue(snapshot.files.isEmpty()); assertEquals(plan, snapshot.plan); assertEquals(BigInteger.TWO, snapshot.revision)
        @Suppress("UNCHECKED_CAST") val items = snapshot.plan!!.getValue("items") as List<MutableMap<String, Any?>>
        items.single()["value"] = "changed"
        assertEquals(plan, f.store.getPlanSnapshot(first.sessionId, "w", "u").plan)
        assertFailsWith<ParsedImportForbidden> { f.store.getPlanSnapshot(first.sessionId, "w", "other") }
        assertFailsWith<ParsedImportForbidden> { f.store.getPlanSnapshot(first.sessionId, "other", "u") }
    }

    @Test fun planSnapshotRejectsCorruptMetadata() = each { f ->
        val changes = listOf(
            buildJsonObject { put("session_digest", "wrong") },
            buildJsonObject { put("revision", true) },
            buildJsonObject { put("workspace_id", JsonNull) },
            buildJsonObject { put("created_at", "2026-10-21T12:00:00") },
            buildJsonObject { put("plan", JsonArray(emptyList())) },
            buildJsonObject { put("files", JsonObject(emptyMap())) },
        )
        for (change in changes) {
            val value = f.create(workspace = "w", user = "u")
            f.damage(value) { JsonObject(it + change) }
            assertFailsWith<ParsedImportStorageUnavailable> { f.store.getPlanSnapshot(value.sessionId, "w", "u") }
        }
    }

    @Test fun publicPlanPageCacheIsBoundedByCurrentObjectOwnerAndLifetime() = each { f ->
        val value = f.create(workspace = "w", user = "u")
        f.replace(value, preview = emptyMap(), plan = mapOf("plan_id" to "one", "items" to listOf(mapOf("value" to "original"))))
        val calls = mutableListOf<BigInteger>()
        fun read(owner: String = "u") = f.store.readPlanProjection(value.sessionId, "w", owner,
            listOf("one", "items", null, 50, "all")) { snapshot ->
            calls += snapshot.revision
            mapOf("items" to snapshot.plan!!.getValue("items"))
        }
        val first = read()
        @Suppress("UNCHECKED_CAST") val items = first.getValue("items") as List<MutableMap<String, Any?>>
        items.single()["value"] = "changed"
        assertEquals(mapOf("items" to listOf(mapOf("value" to "original"))), read())
        assertEquals(listOf(BigInteger.TWO), calls)
        assertFailsWith<ParsedImportForbidden> { read("other") }
        f.replace(f.get(value), preview = emptyMap(), plan = mapOf("plan_id" to "one", "items" to listOf(mapOf("value" to "current"))))
        assertEquals(mapOf("items" to listOf(mapOf("value" to "current"))), read())
        assertEquals(listOf(BigInteger.TWO, BigInteger.valueOf(3)), calls)
        f.now = f.now.plusSeconds(61)
        assertFailsWith<ParsedImportExpired> { read() }
    }

    @Test fun publicPlanCacheNeverHidesStorageFailuresAndBoundsPayloads() = each { f ->
        val value = f.create(workspace = "w", user = "u")
        f.replace(value, preview = emptyMap(), plan = mapOf("plan_id" to "one", "operations" to listOf(mapOf("private" to "source"))))
        fun read(index: Any, payload: String = "public") = f.store.readPlanProjection(value.sessionId, "w", "u",
            listOf("one", index)) { mapOf("value" to payload) }
        repeat(20) { assertEquals(mapOf("value" to "public"), read(it)) }
        fun cache(): Map<*, *> = ParsedImportStore::class.java.getDeclaredField("planCache").let {
            it.isAccessible = true; it.get(f.store) as Map<*, *>
        }
        fun response(item: Any) = item.javaClass.getDeclaredField("response").let {
            it.isAccessible = true; it.get(item) as ByteArray
        }
        assertEquals(16, cache().size)
        assertTrue(cache().values.sumOf { response(it!!).size.toLong() } <= 4 * 1024 * 1024)
        assertTrue(cache().values.none { response(it!!).decodeToString().contains("private") })
        read("too-large", "x".repeat(4 * 1024 * 1024))
        assertEquals(16, cache().size)
        f.store.failRead = true
        assertFailsWith<ParsedImportStorageUnavailable> { read(19) }
    }

    @Test fun retiredFailedSubsetKindsCannotBeCreated() = each { f ->
        for (kind in listOf("retry", "retry_capsule")) invalid {
            f.store.create("w", "u", listOf(file()), workflowKind = kind)
        }
    }

    @Test fun emptyJsonContainersPreserveBudgetAndDepthBoundary() {
        for (kind in listOf("dict", "list", "tuple")) {
            val value: Any = if (kind == "dict") emptyMap<String, Any?>() else emptyList<Any?>()
            val codec = ParsedImportCodec()
            assertEquals(0L, codec.budget(value, 2, 64), kind)
            invalid("import_session_draft_too_large") { codec.budget(value, 1) }
            invalid("import_session_json_invalid") { codec.budget(listOf(value), 100, 64) }
            assertEquals(0L, codec.budget(listOf(value), 5, 63), kind)
        }
    }

    @Test fun jsonBudgetStillVisitsEmptyLookingContainerSubclass() {
        val value = object : ArrayList<String>(listOf("not empty")) {
            override val size: Int get() = 0
        }
        assertEquals(0, value.size)
        assertEquals((100 - 3 - 2 - "not empty".length).toLong(), ParsedImportCodec().budget(listOf(value), 100))
    }

    @Test fun planSnapshotPublicationReusesOriginalFilesAndCas() = each { f ->
        val original = f.create()
        val snapshot = f.store.planUpdateSnapshot(original.sessionId, "workspace-a", "user-a")
        val current = snapshot.session()
        @Suppress("UNCHECKED_CAST") val fields = current.files.single().document!!.rows.single().fields as MutableMap<String, Any?>
        fields["amount"] = "not a financial value"
        f.store.replacePlan(snapshot, "workspace-a", "user-a", 1, emptyMap(), mapOf("plan_id" to "first"))
        assertEquals(mapOf("plan_id" to "first"), f.get(current).plan)
        assertFailsWith<ParsedImportRevisionConflict> {
            f.store.replacePlan(snapshot, "workspace-a", "user-a", 1, emptyMap(), mapOf("plan_id" to "stale"))
        }
        assertEquals(mapOf("plan_id" to "first"), f.get(current).plan)
        assertEquals(original.files, f.get(current).files)
    }

}
