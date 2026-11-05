package com.finance.tracker.backend

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.time.Instant
import kotlin.test.*

class S3ParsedObjectClientTest {
    private data class Request(val method: String, val path: String, val query: String?, val headers: Map<String, List<String>>, val body: ByteArray) {
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
    }
    private class Server : AutoCloseable {
        val requests = java.util.Collections.synchronizedList(mutableListOf<Request>())
        var status = 200
        var response = ""
        var dropResponse = false
        var headers = mapOf<String, String>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requests += Request(exchange.requestMethod, exchange.requestURI.rawPath, exchange.requestURI.rawQuery,
                    exchange.requestHeaders.mapValues { it.value.toList() }, exchange.requestBody.use { it.readBytes() })
                if (dropResponse) { exchange.close(); return@createContext }
                headers.forEach { (key, value) -> exchange.responseHeaders.set(key, value) }
                val bytes = response.encodeToByteArray()
                exchange.sendResponseHeaders(status, if (exchange.requestMethod == "HEAD" || status == 204) -1 else bytes.size.toLong())
                exchange.responseBody.use { if (exchange.requestMethod != "HEAD" && status != 204) it.write(bytes) }
            }
            start()
        }
        val endpoint: URI get() = URI("http://127.0.0.1:${server.address.port}")
        override fun close() = server.stop(0)
    }

    @Test fun signedSdkRequestsPreserveConditionalHeadersMetadataAndStreamBytes() {
        Server().use { server ->
            S3ParsedObjectClient.connect(server.endpoint, "synthetic-access", "synthetic-secret").use { client ->
                val body = "{\"parsed\":true}".encodeToByteArray()
                server.headers = mapOf("ETag" to "\"one\"")
                client.put("private-imports", "cash-import/parsed/test/session", body, null, "*", "application/json",
                    mapOf("expires-at" to "2026-10-21T12:30:00+00:00"))
                val created = server.requests.single()
                assertEquals("PUT", created.method); assertEquals("/private-imports/cash-import/parsed/test/session", created.path)
                assertEquals("*", created.header("If-None-Match")); assertNull(created.header("If-Match"))
                assertEquals("2026-10-21T12:30:00+00:00", created.header("x-amz-meta-expires-at"))
                assertEquals("application/json", created.header("Content-Type"))
                assertTrue(created.header("Authorization")!!.startsWith("AWS4-HMAC-SHA256 Credential=synthetic-access/"))
                assertFalse(created.headers.toString().contains("synthetic-secret"))
                assertContentEquals(body, created.body)
                client.put("private-imports", "key", body, "\"one\"", null, "application/json", emptyMap())
                assertEquals("\"one\"", server.requests.last().header("If-Match"))
                assertNull(server.requests.last().header("If-None-Match"))
                server.response = body.decodeToString()
                val read = client.get("private-imports", "key")
                assertEquals("\"one\"", read.etag); assertContentEquals(body, read.body.use { it.readBytes() })
                server.headers = mapOf("x-amz-meta-expires-at" to "2026-10-21T12:30:00+00:00", "Last-Modified" to "Wed, 21 Oct 2026 12:00:00 GMT")
                val head = client.head("private-imports", "key")
                assertEquals("2026-10-21T12:30:00+00:00", head.metadata["expires-at"])
                assertEquals(Instant.parse("2026-10-21T12:00:00Z"), head.lastModified)
                server.headers = emptyMap()
                server.response = "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><IsTruncated>true</IsTruncated><NextContinuationToken>next+page</NextContinuationToken><Contents><Key>cash-import/parsed/a/session</Key></Contents></ListBucketResult>"
                assertEquals(ParsedObjectPage(listOf("cash-import/parsed/a/session"), true, "next+page"), client.list("private-imports", "cash-import/parsed/", 2, "previous+page"))
                val query = server.requests.last().query!!
                assertTrue("max-keys=2" in query); assertTrue("continuation-token=previous%2Bpage" in query)
                assertTrue("prefix=cash-import%2Fparsed%2F" in query)
                server.status = 204
                client.delete("private-imports", "key")
                assertEquals("DELETE", server.requests.last().method)
            }
        }
    }

    @Test fun providerErrorsAndUnknownWriteResultsAreNeverRetriedOrExposed() {
        Server().use { server ->
            S3ParsedObjectClient.connect(server.endpoint, "synthetic-access", "synthetic-secret").use { client ->
                for ((status, code) in listOf(412 to "PreconditionFailed", 503 to "ServiceUnavailable")) {
                    server.status = status
                    server.response = "<Error><Code>$code</Code><Message>private financial body and credentials</Message></Error>"
                    val before = server.requests.size
                    val error = assertFailsWith<ParsedObjectProviderError> {
                        client.put("bucket", "key", byteArrayOf(1), "\"one\"", null, "application/json", emptyMap())
                    }
                    assertEquals(code, error.providerCode); assertEquals(before + 1, server.requests.size)
                    assertEquals("object_provider_error", error.message); assertNull(error.cause)
                }
                server.dropResponse = true
                val before = server.requests.size
                val error = assertFailsWith<ParsedImportStorageUnavailable> {
                    client.put("bucket", "key", byteArrayOf(1), "\"one\"", null, "application/json", emptyMap())
                }
                assertEquals(before + 1, server.requests.size)
                assertEquals("import_session_storage_unavailable", error.message); assertNull(error.cause)
            }
        }
    }
}
