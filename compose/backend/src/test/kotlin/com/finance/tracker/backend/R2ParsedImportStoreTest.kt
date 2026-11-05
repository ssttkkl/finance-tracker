package com.finance.tracker.backend

import java.io.ByteArrayInputStream
import java.time.Instant
import kotlin.test.*

class R2ParsedImportStoreTest {
    internal class Client : ParsedObjectClient {
        val objects = linkedMapOf<String, ByteArray>()
        val metadata = mutableMapOf<String, Map<String, String>>()
        val modified = mutableMapOf<String, Instant>()
        val writes = mutableListOf<Pair<String?, String?>>()
        var failAfterPut = false
        var failRead = false
        var failClose = false
        var failHead = false
        var closed = 0
        var gets = 0
        val heads = mutableListOf<String>()
        val lists = mutableListOf<Triple<String, Int, String?>>()
        var now: Instant = Instant.parse("2026-10-21T12:00:00Z")
        var afterPut: () -> Unit = {}
        @Synchronized override fun get(bucket: String, key: String): ParsedObjectResponse {
            if (failRead) error("provider secret")
            val content = objects[key] ?: throw ParsedObjectProviderError("NoSuchKey")
            gets++
            return ParsedObjectResponse(object : ByteArrayInputStream(content) {
                override fun close() { closed++; if (failClose) error("close secret") }
            }, ParsedImportStore.digest(content), content.size.toLong())
        }
        @Synchronized override fun put(bucket: String, key: String, content: ByteArray, ifMatch: String?, ifNoneMatch: String?,
            contentType: String, metadata: Map<String, String>) {
            val previous = objects[key]
            if (ifNoneMatch == "*" && previous != null || ifMatch != null && (previous == null || ParsedImportStore.digest(previous) != ifMatch))
                throw ParsedObjectProviderError("PreconditionFailed")
            assertEquals("application/json", contentType)
            objects[key] = content.copyOf(); this.metadata[key] = metadata.toMap(); modified[key] = now
            writes += ifMatch to ifNoneMatch
            afterPut()
            if (failAfterPut) { failAfterPut = false; error("response lost after put") }
        }
        @Synchronized override fun delete(bucket: String, key: String) { objects.remove(key) }
        override fun list(bucket: String, prefix: String, limit: Int, cursor: String?): ParsedObjectPage {
            lists += Triple(prefix, limit, cursor)
            val keys = objects.keys.filter { it.startsWith(prefix) && (cursor == null || it > cursor) }.sorted()
            return ParsedObjectPage(keys.take(limit), keys.size > limit, if (keys.size > limit) keys[limit - 1] else null)
        }
        override fun head(bucket: String, key: String): ParsedObjectHead {
            heads += key
            if (failHead) error("provider credentials and object body")
            if (key !in objects) throw ParsedObjectProviderError("NoSuchKey")
            return ParsedObjectHead(metadata[key].orEmpty(), modified[key])
        }
    }
    private fun file() = ParsedImportFile("f1", "a".repeat(64), "failed", filename = "synthetic.csv", errorCode = "parse_error")

    @Test fun conditionalPublicationKeepsUnknownResultsAndOriginalKey() {
        val client = Client()
        val store = R2ParsedImportStore(client, "bucket", clock = { client.now })
        val first = store.create("w", "u", listOf(file()))
        assertEquals(null to "*", client.writes.single())
        client.failAfterPut = true
        val error = assertFailsWith<ParsedImportStorageUnavailable> {
            store.replace(first.sessionId, "w", "u", 1, first.files, emptyMap(), mapOf("plan_id" to "current"))
        }
        assertFalse(error.message!!.contains("provider"))
        assertEquals(java.math.BigInteger.TWO, store.get(first.sessionId, "w", "u").revision)
        assertFailsWith<ParsedImportRevisionConflict> {
            store.replace(first.sessionId, "w", "u", 1, first.files, emptyMap(), null)
        }
        assertEquals(2, client.writes.size)
        assertNotNull(client.writes.last().first); assertNull(client.writes.last().second)
        assertEquals(client.gets, client.closed)
    }

    @Test fun corruptedOversizedAndCloseFailuresNeverReturnPartialState() {
        val client = Client()
        val store = R2ParsedImportStore(client, "bucket", codec = ParsedImportCodec(maxBytes = 2500))
        val first = store.create("w", "u", listOf(file()))
        val key = client.objects.keys.single()
        val valid = client.objects.getValue(key)
        for (body in listOf("{\"corrupt\":true}".encodeToByteArray(), ByteArray(4000))) {
            client.objects[key] = body
            assertFailsWith<ParsedImportStorageUnavailable> { store.get(first.sessionId, "w", "u") }
            assertEquals(client.gets, client.closed)
        }
        client.objects[key] = valid; client.failClose = true
        val error = assertFailsWith<ParsedImportStorageUnavailable> { store.get(first.sessionId, "w", "u") }
        assertFalse(error.message.orEmpty().contains("secret"))
    }

    @Test fun expiryDuringPublicationDeletesBodyAndNeverReturnsSuccess() {
        val client = Client()
        val store = R2ParsedImportStore(client, "bucket", ttlSeconds = 1, clock = { client.now })
        client.afterPut = { client.now = client.now.plusSeconds(1) }
        assertFailsWith<ParsedImportExpired> { store.create("w", "u", listOf(file())) }
        assertTrue(client.objects.isEmpty())
    }

    @Test fun sweepUsesOnlyMetadataAndLeavesLiveAndUnrelatedObjects() {
        val client = Client()
        val store = R2ParsedImportStore(client, "bucket", ttlSeconds = 60, clock = { client.now })
        val expired = (1..3).map { store.create("w", "u", listOf(file())) }
        expired.forEach { session -> assertEquals(ParsedImportCodec.timeText(session.expiresAt),
            client.metadata.getValue("cash-import/parsed/${tokenDigest(session.sessionId)}/session")["expires-at"]) }
        client.now = client.now.plusSeconds(30)
        val live = store.create("w", "u", listOf(file()))
        client.now = client.now.plusSeconds(30)
        var scanned = 0; var deleted = 0; var cursor: String? = null
        do {
            val result = store.sweepExpired(cursor, 2)
            assertTrue(result.scanned <= 2)
            scanned += result.scanned; deleted += result.deleted; cursor = result.nextCursor
        } while (cursor != null)
        assertEquals(4, scanned); assertEquals(3, deleted); assertEquals(0, client.gets); assertEquals(4, client.heads.size)
        assertTrue(client.lists.all { it.first == "cash-import/parsed/" && it.second == 2 })
        assertEquals(live, store.get(live.sessionId, "w", "u"))
        client.objects["cash-import/parsed/unrelated"] = "private".encodeToByteArray()
        client.objects["cash-import/files/original"] = "private".encodeToByteArray()
        client.objects["elsewhere/parsed/${"a".repeat(64)}/session"] = "private".encodeToByteArray()
        val legacy = "cash-import/parsed/${"b".repeat(64)}/session"
        client.objects[legacy] = "never download this".encodeToByteArray()
        client.modified[legacy] = client.now.minusSeconds(1800)
        assertEquals(1, store.sweepExpired().deleted)
        assertTrue("cash-import/parsed/unrelated" in client.objects)
        assertTrue("cash-import/files/original" in client.objects)
        assertTrue("elsewhere/parsed/${"a".repeat(64)}/session" in client.objects)
        assertEquals(1, client.gets)
    }

    @Test fun failedCleanupDoesNotExposeProviderDetailsOrDeleteLiveObjects() {
        val client = Client()
        val store = R2ParsedImportStore(client, "bucket", clock = { client.now })
        val live = store.create("w", "u", listOf(file()))
        client.failHead = true
        val error = assertFailsWith<ParsedImportStorageUnavailable> { store.sweepExpired() }
        assertFalse(error.message.orEmpty().contains("credentials")); assertEquals(1, client.objects.size)
        assertEquals(live, store.get(live.sessionId, "w", "u"))
    }

    @Test fun cachedPagesNeverHideCurrentStorageErrors() {
        val client = Client()
        val store = R2ParsedImportStore(client, "bucket", clock = { client.now })
        val first = store.create("w", "u", listOf(file()))
        store.replace(first.sessionId, "w", "u", 1, first.files, emptyMap(), mapOf("plan_id" to "one"))
        var calls = 0
        fun read() = store.readPlanProjection(first.sessionId, "w", "u", listOf("one", "items")) {
            calls++; mapOf("value" to "public")
        }
        assertEquals(read(), read()); assertEquals(1, calls)
        client.failRead = true
        assertFailsWith<ParsedImportStorageUnavailable> { read() }
        assertEquals(1, calls)
    }
}
