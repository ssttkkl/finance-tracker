package com.finance.tracker.backend.persistence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jooq.Converter
import org.jooq.JSON

internal class JsonElementConverter : Converter<JSON, JsonElement> {
    override fun from(databaseObject: JSON?): JsonElement? = databaseObject?.data()?.let(Json::parseToJsonElement)
    override fun to(userObject: JsonElement?): JSON? = userObject?.let { JSON.valueOf(it.toString()) }
    override fun fromType(): Class<JSON> = JSON::class.java
    override fun toType(): Class<JsonElement> = JsonElement::class.java
}
