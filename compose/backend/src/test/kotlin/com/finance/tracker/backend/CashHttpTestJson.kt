package com.finance.tracker.backend

import io.ktor.client.statement.*
import kotlinx.serialization.json.*

internal suspend fun HttpResponse.apiBody() = Json.parseToJsonElement(bodyAsText()).jsonObject
internal fun JsonObject.apiText(key: String) = getValue(key).jsonPrimitive.content
internal fun JsonObject.apiNumber(key: String) = getValue(key).jsonPrimitive.int
internal fun JsonObject.apiArray(key: String) = getValue(key).jsonArray
internal fun JsonObject.apiError() = getValue("error").jsonObject.apiText("code")
