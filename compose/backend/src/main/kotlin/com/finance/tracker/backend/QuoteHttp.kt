package com.finance.tracker.backend

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlinx.serialization.json.JsonElement

internal fun interface QuoteJsonFetcher { fun fetch(url: String, timeout: Double?): JsonElement }
internal class QuoteHttpFailure : RuntimeException("market.request_failed")

internal fun marketUrlQuote(value: String): String {
    val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
    return buildString {
        while (bytes.hasRemaining()) {
            val number = bytes.get().toInt() and 255
            val char = number.toChar()
            if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-._~/") append(char)
            else { append('%'); append("0123456789ABCDEF"[number shr 4]); append("0123456789ABCDEF"[number and 15]) }
        }
    }
}

internal class JvmQuoteHttpFetcher(private val environment: Map<String, String> = System.getenv(),
    private val userAgent: String = "Mozilla/5.0") : QuoteJsonFetcher {
    private fun bypass(uri: URI): Boolean {
        val configured = environment["no_proxy"] ?: environment["NO_PROXY"] ?: ""
        val host = uri.host.orEmpty().lowercase()
        val authority = host + (if (uri.port >= 0) ":" + uri.port else "")
        return configured.split(',').any { raw ->
            val item = raw.trim().lowercase().removePrefix(".")
            item == "*" || item.isNotEmpty() && (host == item || authority == item ||
                host.endsWith("." + item) || authority.endsWith("." + item))
        }
    }
    override fun fetch(url: String, timeout: Double?): JsonElement {
        val uri = URI.create(url)
        require(uri.scheme in setOf("http", "https"))
        val seconds = timeout ?: 4.0
        require(seconds > 0 && seconds.isFinite())
        val millis = (seconds * 1000).coerceAtLeast(1.0).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
        val override = (environment["HTTPS_PROXY"]?.takeIf { it.isNotEmpty() } ?: environment["HTTP_PROXY"]).orEmpty()
        val configured = override.takeIf { it.isNotEmpty() } ?: environment[uri.scheme + "_proxy"].orEmpty()
        val proxy = configured.takeIf { it.isNotEmpty() && !bypass(uri) }?.let {
            URI.create(if ("://" in it) it else "http://" + it)
        }
        val connection = (if (proxy == null) uri.toURL().openConnection() else uri.toURL().openConnection(
            Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.host, if (proxy.port >= 0) proxy.port else 80)))) as HttpURLConnection
        connection.connectTimeout = millis; connection.readTimeout = millis
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", "application/json")
        proxy?.rawUserInfo?.let { userInfo ->
            val decoded = java.net.URLDecoder.decode(userInfo.replace("+", "%2B"), Charsets.UTF_8)
            connection.setRequestProperty("Proxy-Authorization", "Basic " + Base64.getEncoder().encodeToString(decoded.toByteArray(Charsets.UTF_8)))
        }
        try {
            if (connection.responseCode !in 200..299) throw QuoteHttpFailure()
            val content = connection.inputStream.use { it.readBytes() }
            return categoryDeleteRequest(pythonJsonText(content)).value
        } finally { connection.disconnect() }
    }
}

