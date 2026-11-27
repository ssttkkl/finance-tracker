@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.finance.tracker.business.contracts

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

internal object ReceiptStoredIntegerSerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("ReceiptStoredInteger", PrimitiveKind.STRING)
    private fun checked(value: String): String {
        if (value.isEmpty() || value[0] !in '1'..'9' || value.length > maxIdentity.length ||
            (value.length == maxIdentity.length && value > maxIdentity) || value.any { it !in '0'..'9' })
            throw SerializationException("mutation_receipt_invalid")
        return value
    }
    override fun deserialize(decoder: Decoder): String = checked(receiptStoredString(decoder))
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(checked(value))

    private const val maxIdentity = "9223372036854775807"
}

internal object ReceiptEntryIdentitySerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("ReceiptEntryIdentity", PrimitiveKind.STRING)
    private fun checked(value: String): String {
        if (value.isEmpty()) throw SerializationException("mutation_receipt_invalid")
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char.isHighSurrogate()) {
                if (index == value.length || !value[index++].isLowSurrogate()) throw SerializationException("mutation_receipt_invalid")
            } else if (char.isLowSurrogate()) throw SerializationException("mutation_receipt_invalid")
        }
        return value
    }
    override fun deserialize(decoder: Decoder): String = checked(receiptStoredString(decoder))
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(checked(value))
}

private fun receiptStoredString(decoder: Decoder): String {
    if (decoder !is JsonDecoder || decoder.json === archiveReceiptJson) return decoder.decodeString()
    return (decoder.decodeJsonElement() as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw SerializationException("mutation_receipt_invalid")
}

@Serializable
data class CashWriteSetIdentityDto(
    @Serializable(with = ReceiptStoredIntegerSerializer::class) val id: String,
    @Serializable(with = ReceiptStoredIntegerSerializer::class) val revision: String,
)

@Serializable
data class CashWriteSetTransactionResultDto(
    @Serializable(with = ReceiptStoredIntegerSerializer::class) val id: String,
    @Serializable(with = ReceiptStoredIntegerSerializer::class) @SerialName("row_revision") val rowRevision: String,
    @SerialName("component_ids") val componentIds: List<@Serializable(with = ReceiptStoredIntegerSerializer::class) String>,
)

@Serializable
data class CashWriteSetEntryResultDto(
    @Serializable(with = ReceiptEntryIdentitySerializer::class) val id: String,
    @Serializable(with = ReceiptStoredIntegerSerializer::class) @SerialName("root_transaction_id") val rootTransactionId: String,
    @Serializable(with = ReceiptStoredIntegerSerializer::class) @SerialName("identity_transaction_id") val identityTransactionId: String,
    @Serializable(with = ReceiptStoredIntegerSerializer::class) val revision: String,
)

@Serializable
data class CashWriteSetResultDto(
    val transactions: List<CashWriteSetTransactionResultDto>,
    @SerialName("source_bindings") val sourceBindings: List<CashWriteSetIdentityDto>,
    val issues: List<CashWriteSetIdentityDto>,
    @SerialName("statement_account_mappings") val statementAccountMappings: List<CashWriteSetIdentityDto>,
    val relations: List<CashWriteSetIdentityDto>,
    val entries: List<CashWriteSetEntryResultDto>,
    @SerialName("resolved_issue_ids") val resolvedIssueIds: List<@Serializable(with = ReceiptStoredIntegerSerializer::class) String>,
    @SerialName("retired_entry_ids") val retiredEntryIds: List<@Serializable(with = ReceiptEntryIdentitySerializer::class) String>,
)

internal object ReceiptWriteSetResultSerializer : KSerializer<CashWriteSetResultDto?> {
    override val descriptor = CashWriteSetResultDto.serializer().descriptor.nullable
    override fun deserialize(decoder: Decoder): CashWriteSetResultDto {
        if (decoder !is JsonDecoder) throw SerializationException("mutation_receipt_invalid")
        val serializer = CashWriteSetResultDto.serializer()
        return if (decoder.json === archiveReceiptJson) decoder.decodeSerializableValue(serializer)
        else Json.decodeFromJsonElement(serializer, decoder.decodeJsonElement())
    }
    override fun serialize(encoder: Encoder, value: CashWriteSetResultDto?) =
        encoder.encodeSerializableValue(CashWriteSetResultDto.serializer(), value ?: throw SerializationException("mutation_receipt_invalid"))
}

private val archiveReceiptJson = Json { }

/** Archived receipts normally come from our serializer; retain the original decoder for historical envelopes. */
fun decodeArchivedCashMutationReceipt(body: String): CashMutationReceiptDto = try {
    archiveReceiptJson.decodeFromString(body)
} catch (_: SerializationException) {
    Json.decodeFromString(body)
}
