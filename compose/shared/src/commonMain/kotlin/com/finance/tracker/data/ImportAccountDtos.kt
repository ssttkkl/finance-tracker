package com.finance.tracker.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

@Serializable
data class ImportAccountListItemDto(
    @Serializable(with = ImportAccountIdSerializer::class) val id: String,
    val name: String, val type: String, val active: Boolean,
    val currencies: List<String>? = null,
)
@Serializable
internal data class ImportAccountListDto(val items: List<ImportAccountListItemDto>)

// 旧列表以整数传输 ID，直接读取 JSON 词法值，不经过 Long 或 Double。
internal object ImportAccountIdSerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("ImportAccountId", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String {
        val value = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("invalid_account_id")
        if (value is JsonNull || (!value.isString && !value.content.matches(Regex("[0-9]+"))) || value.content.isEmpty()) {
            throw SerializationException("invalid_account_id")
        }
        return value.content
    }
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

typealias ImportAccountDto = com.finance.tracker.business.contracts.AccountCommandViewDto
