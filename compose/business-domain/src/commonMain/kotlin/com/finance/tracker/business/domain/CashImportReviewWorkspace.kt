package com.finance.tracker.business.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonArray

/** Temporary, operation-scoped storage for exact JSON blocks. */
interface CashImportReviewWorkspace {
    fun write(text: String): Int
    fun read(block: Int): String
}

private const val reviewBlockUnits = 64 * 1024

internal fun CashImportReviewWorkspace.storeList(values: Sequence<JsonElement>): List<JsonElement> {
    val pages = mutableListOf<Pair<Int, Int>>()
    val pending = StringBuilder()
    var size = 0
    fun flush() {
        if (pending.isEmpty()) return
        pages += size to write("[$pending]")
        pending.clear()
    }
    for (value in values) {
        val text = value.toString()
        if (pending.isNotEmpty() && text.length + pending.length + 3 > reviewBlockUnits) flush()
        if (pending.isNotEmpty()) pending.append(',')
        pending.append(text)
        size++
    }
    flush()
    return StoredReviewList(this, pages, size)
}

private class StoredReviewList(
    private val workspace: CashImportReviewWorkspace,
    private val pages: List<Pair<Int, Int>>,
    override val size: Int,
) : AbstractList<JsonElement>() {
    private var loadedPage = -1
    private var loaded: JsonArray? = null

    override fun get(index: Int): JsonElement {
        if (index !in indices) throw IndexOutOfBoundsException("Invalid review item index")
        val boundary = pages.binarySearchBy(index + 1) { it.first }
        val page = if (boundary < 0) -boundary - 1 else boundary
        val start = if (page == 0) 0 else pages[page - 1].first
        if (loadedPage != page) {
            val decoded = storedReviewValue(Json.parseToJsonElement(workspace.read(pages[page].second))).jsonArray
            check(decoded.size == pages[page].first - start) { "cash_review_block_incomplete" }
            loaded = decoded
            loadedPage = page
        }
        return checkNotNull(loaded)[index - start]
    }

    override fun iterator(): Iterator<JsonElement> = StoredReviewIterator(super.iterator())
}

private class StoredReviewIterator(private val values: Iterator<JsonElement>) : Iterator<JsonElement> by values

internal fun JsonArray.isStoredReviewList(): Boolean = iterator() is StoredReviewIterator

private interface StoredReviewEntries

internal fun JsonObject.isStoredReviewMap(): Boolean = entries is StoredReviewEntries

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private fun storedReviewValue(value: JsonElement): JsonElement = when (value) {
    JsonNull -> JsonNull
    is JsonObject -> JsonObject(buildMap(value.size) {
        for ((key, item) in value) put(key, storedReviewValue(item))
    })
    is JsonArray -> JsonArray(buildList(value.size) { for (item in value) add(storedReviewValue(item)) })
    is JsonPrimitive -> if (value.isString || value.content == "true" || value.content == "false") value
        else JsonUnquotedLiteral(value.content)
}

internal fun <V> lazyReviewMap(keys: List<String>, load: (Int) -> V): Map<String, V> = object : AbstractMap<String, V>() {
    private val positions = keys.withIndex().associate { (index, key) -> key to index }
    override val size get() = keys.size
    override fun containsKey(key: String) = key in positions
    override fun get(key: String): V? = positions[key]?.let(load)
    override val entries: Set<Map.Entry<String, V>> = object : AbstractSet<Map.Entry<String, V>>(), StoredReviewEntries {
        override val size get() = keys.size
        override fun iterator(): Iterator<Map.Entry<String, V>> = keys.indices.asSequence().map { index ->
            object : Map.Entry<String, V> {
                override val key = keys[index]
                override val value: V get() = load(index)
                override fun equals(other: Any?): Boolean = other is Map.Entry<*, *> && key == other.key && value == other.value
                override fun hashCode(): Int = key.hashCode() xor (value?.hashCode() ?: 0)
            }
        }.iterator()
    }
}
