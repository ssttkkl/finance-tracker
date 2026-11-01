package com.finance.tracker.backend

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Existing public protocol metadata, selected from the application's actual route tree. */
internal object LegacyOpenApi {
    private val methods = setOf("get", "post", "put", "patch", "delete", "head", "options", "trace")
    private val contract by lazy {
        Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/openapi-contract.json"))
            .bufferedReader().use { it.readText() }).jsonObject
    }
    private fun signature(method: String, path: String) = method to path.replace(Regex("\\{[^}]+}"), "{}")

    private fun operations(root: RoutingNode): Set<Pair<String, String>> {
        val result = linkedSetOf<Pair<String, String>>()
        fun visit(node: RoutingNode, parts: List<String>, method: String?) {
            val selector = node.selector
            val path = when (selector) {
                is RootRouteSelector -> parts
                is PathSegmentConstantRouteSelector -> parts + selector.value
                is PathSegmentParameterRouteSelector -> parts + (selector.prefix.orEmpty() + "{" + selector.name + "}" + selector.suffix.orEmpty())
                is PathSegmentTailcardRouteSelector -> parts + (selector.prefix + "{" + selector.name + "}")
                else -> {
                    require(selector !is RoutePathComponent) { "Unsupported API documentation path selector" }
                    parts
                }
            }
            val verb = if (selector is HttpMethodRouteSelector) selector.method.value.lowercase() else method
            val full = "/" + path.joinToString("/")
            if (node.hasHandler() && verb != null && full.startsWith("/api/v1/")) result += signature(verb, full)
            node.children.forEach { visit(it, path, verb) }
        }
        visit(root, emptyList(), null)
        return result
    }

    fun install(root: RoutingRoot) {
        val active = operations(root)
        val original = contract.getValue("paths").jsonObject
        val available = original.flatMap { (path, values) -> values.jsonObject.keys.filter { it in methods }.map { signature(it, path) } }.toSet()
        require(active.all { it in available }) { "Registered API operation lacks an OpenAPI contract" }
        val paths = buildJsonObject {
            for ((path, raw) in original) {
                val item = raw.jsonObject
                val included = item.keys.filter { it in methods && signature(it, path) in active }.toSet()
                if (included.isNotEmpty()) put(path, JsonObject(item.filterKeys { it !in methods || it in included }))
            }
        }
        val response = JsonObject(contract + ("paths" to paths)).toString()
        root.get("/openapi.json") { call.respondText(response, ContentType.Application.Json) }
    }
}
