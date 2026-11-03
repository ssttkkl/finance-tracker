package com.finance.tracker.backend

import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class CcxtDependencyRuntimeTest {
    @Test fun ccxtDependenciesCoexistWithActualNettyServer() {
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            routing { get("/health") { call.respondText("ready") } }
        }.start(wait = false)
        try {
            CcxtSdkClient("kraken", mapOf("api_key" to "synthetic-key", "api_secret" to "c3ludGhldGljLXNlY3JldA==")).use {
                val port = runBlocking { server.engine.resolvedConnectors().single().port }
                HttpClient.newHttpClient().use { client ->
                    val result = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/health")).build(), HttpResponse.BodyHandlers.ofString())
                    assertEquals(200, result.statusCode()); assertEquals("ready", result.body())
                }
                assertEquals("4.2.17.Final", io.netty.util.Version.identify()["netty-common"]!!.artifactVersion())
                assertEquals("1.86", org.bouncycastle.jce.provider.BouncyCastleProvider().versionStr)
            }
        } finally { server.stop(100, 1000) }
    }
}
