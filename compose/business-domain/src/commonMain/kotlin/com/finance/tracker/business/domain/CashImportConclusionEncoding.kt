package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashWriteSetDto
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun hashCashImportConclusion(workspace: String, writes: CashWriteSetDto, review: JsonObject): String =
    hashCashImportConclusion(workspace, { writes }) { append -> writeCanonicalConclusion(review, append) }

internal fun hashCashImportConclusion(
    workspace: String, writes: () -> CashWriteSetDto, review: ((Int) -> Unit) -> Unit,
): String =
    sha256HexAscii { append ->
        val fields = linkedMapOf<String, () -> Unit>(
            "review" to { review(append) },
            "rules" to { writeCanonicalConclusion(JsonPrimitive(CASH_RULES_VERSION), append) },
            "version" to { writeCanonicalConclusion(JsonPrimitive("cash-import-conclusion-v1"), append) },
            "workspace" to { writeCanonicalConclusion(JsonPrimitive(workspace), append) },
            "writes" to { writeConclusionWrites(writes(), append) },
        )
        writeConclusionFields(fields, append)
    }

/** Buffer only the ordered suffix whose fields must register their participants before the closure. */
internal fun writeCashImportReviewConclusion(
    fields: Map<String, () -> JsonElement>, participants: () -> JsonElement, append: (Int) -> Unit,
) {
    val participantField = "participants"
    fun writeField(key: String, value: () -> JsonElement, output: (Int) -> Unit) {
        writeConclusionText(JsonPrimitive(key).toString(), output)
        output(':'.code)
        writeCanonicalConclusion(value(), output)
    }

    check(participantField !in fields)
    val keys = fields.keys.sortedWith(::pythonStringOrder)
    val deferred = ConclusionAsciiChunks()
    append('{'.code)
    for (key in keys) {
        if (pythonStringOrder(key, participantField) < 0) {
            writeField(key, fields.getValue(key), append)
            append(','.code)
        } else {
            deferred.append(','.code)
            writeField(key, fields.getValue(key), deferred::append)
        }
    }
    writeField(participantField, participants, append)
    deferred.writeTo(append)
    append('}'.code)
}

private class ConclusionAsciiChunks {
    private val full = mutableListOf<ByteArray>()
    private var tail = ByteArray(8192)
    private var used = 0

    fun append(ascii: Int) {
        if (used == tail.size) {
            full += tail
            tail = ByteArray(tail.size)
            used = 0
        }
        tail[used++] = ascii.toByte()
    }

    fun writeTo(append: (Int) -> Unit) {
        for (chunk in full) for (byte in chunk) append(byte.toInt())
        for (index in 0 until used) append(tail[index].toInt())
    }
}

@OptIn(ExperimentalSerializationApi::class)
private fun writeConclusionWrites(writes: CashWriteSetDto, append: (Int) -> Unit) {
    val serializer = CashWriteSetDto.serializer()
    val descriptor = serializer.descriptor
    if ((0 until descriptor.elementsCount).any {
        val field = descriptor.getElementDescriptor(it)
        field.kind != StructureKind.LIST || field.isNullable
    }) {
        writeCanonicalConclusion(fullConclusionJson.encodeToJsonElement(serializer, writes), append)
        return
    }
    val fields = mutableMapOf<String, () -> Unit>()
    serializer.serialize(object : AbstractEncoder() {
        override val serializersModule = fullConclusionJson.serializersModule

        override fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int): Boolean = true

        override fun <T> encodeSerializableElement(
            descriptor: SerialDescriptor, index: Int, serializer: SerializationStrategy<T>, value: T,
        ) {
            fields[descriptor.getElementName(index)] = {
                append('['.code)
                serializer.serialize(ConclusionArrayEncoder(append), value)
                append(']'.code)
            }
        }
    }, writes)
    check(fields.size == descriptor.elementsCount)
    writeConclusionFields(fields, append)
}

@OptIn(ExperimentalSerializationApi::class)
private class ConclusionArrayEncoder(private val append: (Int) -> Unit) : AbstractEncoder() {
    override val serializersModule = fullConclusionJson.serializersModule

    override fun <T> encodeSerializableElement(
        descriptor: SerialDescriptor, index: Int, serializer: SerializationStrategy<T>, value: T,
    ) {
        if (index != 0) append(','.code)
        writeCanonicalConclusion(fullConclusionJson.encodeToJsonElement(serializer, value), append)
    }
}

private fun writeConclusionFields(fields: Map<String, () -> Unit>, append: (Int) -> Unit) {
    append('{'.code)
    fields.keys.sortedWith(::pythonStringOrder).forEachIndexed { index, key ->
        if (index != 0) append(','.code)
        writeConclusionText(JsonPrimitive(key).toString(), append)
        append(':'.code)
        fields.getValue(key)()
    }
    append('}'.code)
}

private fun writeCanonicalConclusion(value: JsonElement, append: (Int) -> Unit) {
    when (value) {
        is JsonObject -> {
            append('{'.code)
            value.keys.sortedWith(::pythonStringOrder).forEachIndexed { index, key ->
                if (index != 0) append(','.code)
                writeConclusionText(JsonPrimitive(key).toString(), append)
                append(':'.code)
                writeCanonicalConclusion(value.getValue(key), append)
            }
            append('}'.code)
        }
        is JsonArray -> {
            append('['.code)
            value.forEachIndexed { index, child ->
                if (index != 0) append(','.code)
                writeCanonicalConclusion(child, append)
            }
            append(']'.code)
        }
        else -> writeConclusionText(value.toString(), append)
    }
}

internal inline fun writeConclusionText(value: String, append: (Int) -> Unit) {
    val digits = "0123456789abcdef"
    for (char in value) {
        if (char.code < 128) append(char.code)
        else {
            append('\\'.code)
            append('u'.code)
            for (shift in 12 downTo 0 step 4) append(digits[(char.code ushr shift) and 15].code)
        }
    }
}
