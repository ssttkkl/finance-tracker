package com.finance.tracker.backend

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*
import org.apache.hc.client5.http.auth.AuthScope
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.auth.BasicAuthCache
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider
import org.apache.hc.client5.http.impl.auth.BasicScheme
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.protocol.HttpClientContext
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.HttpHost
import org.apache.hc.core5.http.io.entity.ByteArrayEntity
import org.apache.hc.core5.util.Timeout

internal class PolymarketHttpStatus(val status: Int) : IOException("HTTP $status")
internal class PolymarketHttp(private val api: String = "https://data-api.polymarket.com",
    private val profileBase: String = "https://polymarket.com/profile", private val endpoints: List<String> = RPC_ENDPOINTS,
    private val environment: Map<String, String> = System.getenv()) {
    fun activityUrl(wallet: String, limit: Int, offset: Long) = "$api/activity?user=${URLEncoder.encode(wallet, Charsets.UTF_8).replace("%7E", "~")}&limit=$limit&offset=$offset"
    fun profile(wallet: String) = request("$profileBase/$wallet", null).toString(Charsets.UTF_8)
    fun json(url: String) = decode(request(url, null))
    private fun decode(bytes: ByteArray): JsonElement {
        val content = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
            catch (_: java.nio.charset.CharacterCodingException) { throw IOException("invalid response encoding") }
        return try { categoryDeleteRequest(PythonJsonText(content, emptySet())).value } catch (_: CategoryFailure) { throw IOException("invalid JSON response") }
    }
    fun rpc(method: String, params: JsonArray): JsonElement {
        val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", method); put("params", params) }.toString().toByteArray(Charsets.UTF_8)
        for (endpoint in endpoints) {
            try {
                val response = decode(request(endpoint, body)) as? JsonObject ?: throw IOException("invalid RPC response")
                if (pythonTruthy(response["error"]) || response["result"] == null || response["result"] == JsonNull) throw IOException("RPC result unavailable")
                return response.getValue("result")
            } catch (_: IOException) { /* Try the next configured read-only endpoint. */ }
        }
        throw ConnectorFailure("Polygon RPC $method failed: request failed")
    }
    private fun request(url: String, body: ByteArray?): ByteArray {
        val uri = URI.create(url)
        require(uri.scheme in setOf("http", "https"))
        val noProxy = environment["no_proxy"] ?: environment["NO_PROXY"] ?: ""
        val host = uri.host.orEmpty().lowercase(); val authority = host + if (uri.port >= 0) ":${uri.port}" else ""
        val bypass = noProxy.split(',').any { raw -> val item = raw.trim().lowercase().removePrefix("."); item == "*" || item.isNotEmpty() && (host == item || authority == item || host.endsWith(".$item") || authority.endsWith(".$item")) }
        val configured = environment[uri.scheme + "_proxy"] ?: environment[uri.scheme.uppercase() + "_PROXY"]
        val proxy = configured?.takeIf { it.isNotEmpty() && !bypass }?.let { URI.create(if ("://" in it) it else "http://$it") }
        val context = HttpClientContext.create()
        val builder = HttpClients.custom().disableAutomaticRetries().disableCookieManagement().disableContentCompression()
            .setUserAgent(USER_AGENT).setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(Timeout.ofSeconds(30)).setConnectionRequestTimeout(Timeout.ofSeconds(30)).setResponseTimeout(Timeout.ofSeconds(30)).build())
        proxy?.let {
            val proxyHost = HttpHost(it.scheme, it.host, if (it.port >= 0) it.port else 80)
            builder.setProxy(proxyHost)
            it.rawUserInfo?.let { raw ->
                val user = raw.substringBefore(':'); val password = raw.substringAfter(':', "")
                fun decode(value: String) = java.net.URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8)
                val credentials = UsernamePasswordCredentials(decode(user), decode(password).toCharArray())
                val provider = BasicCredentialsProvider(); provider.setCredentials(AuthScope(proxyHost), credentials)
                context.credentialsProvider = provider
                val scheme = BasicScheme(); scheme.initPreemptive(credentials)
                val cache = BasicAuthCache(); cache.put(proxyHost, scheme); context.authCache = cache
            }
        }
        val request = HttpUriRequestBase(if (body == null) "GET" else "POST", uri)
        if (body != null) { request.entity = ByteArrayEntity(body, ContentType.APPLICATION_JSON); request.setHeader("Content-Type", "application/json") }
        return builder.build().use { client ->
            client.execute(request, context) { response ->
                if (response.code !in 200..299) throw PolymarketHttpStatus(response.code)
                response.entity?.content?.use { it.readBytes() } ?: ByteArray(0)
            }
        }
    }
    companion object {
        val RPC_ENDPOINTS = listOf("https://polygon.api.onfinality.io/public", "https://polygon.drpc.org", "https://1rpc.io/matic")
        const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    }
}
