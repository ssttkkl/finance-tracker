package com.finance.tracker.backend

import java.net.URI

fun validateWebOrigin(origin: String): String {
    val uri = try { URI(origin) } catch (_: java.net.URISyntaxException) { throw IllegalArgumentException("invalid Web origin") }
    val port = uri.port
    val host = uri.host
    val local = uri.scheme == "http" && host?.lowercase() in setOf("127.0.0.1", "localhost") && port >= 0
    val hosted = uri.scheme == "https" && host != null
    require((local || hosted) && port <= 65535 && uri.rawUserInfo == null && uri.path in listOf("", "/") &&
        uri.rawQuery == null && uri.rawFragment == null) { "invalid Web origin" }
    return origin.trimEnd('/')
}
