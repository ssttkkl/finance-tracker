package com.finance.tracker.business.contracts

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/** Snapshot facts keep stored JSON number tokens through both HTTP and local persistence. */
internal object LedgerSnapshotJsonElementSerializer : KSerializer<JsonElement> {
    override val descriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): JsonElement {
        require(decoder is JsonDecoder)
        return decoder.decodeJsonElement()
    }

    override fun serialize(encoder: Encoder, value: JsonElement) {
        require(encoder is JsonEncoder)
        encoder.encodeJsonElement(rawNumbers(value))
    }

    private val number = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    @OptIn(ExperimentalSerializationApi::class)
    private fun rawNumbers(value: JsonElement): JsonElement = when (value) {
        JsonNull -> JsonNull
        is JsonObject -> JsonObject(value.mapValues { rawNumbers(it.value) })
        is JsonArray -> JsonArray(value.map(::rawNumbers))
        is JsonPrimitive -> if (value.isString || value.booleanOrNull != null) value else {
            require(number.matches(value.content)) { "ledger_snapshot_invalid_json_number" }
            JsonUnquotedLiteral(value.content)
        }
    }
}
