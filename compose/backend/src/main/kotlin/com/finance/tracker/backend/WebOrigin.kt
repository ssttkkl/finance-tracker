package com.finance.tracker.backend

import java.net.URI

private val canonicalIpv4 = Regex("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")

fun validateWebOrigin(origin: String): String {
    val uri = try { URI(origin) } catch (_: java.net.URISyntaxException) { throw IllegalArgumentException("invalid Web origin") }
    val port = uri.port
    val host = uri.host
    val local = uri.scheme == "http" && host?.lowercase() in setOf("127.0.0.1", "localhost") && port in 1..65535
    val privateNetwork = uri.scheme == "http" && isPrivateIpv4(host) && port in 1..65535
    val hosted = uri.scheme == "https" && host != null && (port == -1 || port in 1..65535)
    require((local || privateNetwork || hosted) && uri.rawUserInfo == null && uri.path in listOf("", "/") &&
        uri.rawQuery == null && uri.rawFragment == null && !hasLeadingZeroPort(uri)) { "invalid Web origin" }
    return origin.trimEnd('/')
}

private fun hasLeadingZeroPort(uri: URI): Boolean {
    val authority = uri.rawAuthority ?: return false
    val separator = authority.lastIndexOf(':')
    if (separator < 0) return false
    val port = authority.substring(separator + 1)
    return port.length > 1 && port.startsWith('0')
}

private fun isPrivateIpv4(host: String?): Boolean {
    if (host == null || !canonicalIpv4.matches(host)) return false
    val octets = host.split('.').map(String::toInt)
    if (octets.any { it !in 0..255 }) return false
    return octets[0] == 10 ||
        (octets[0] == 172 && octets[1] in 16..31) ||
        (octets[0] == 192 && octets[1] == 168)
}
