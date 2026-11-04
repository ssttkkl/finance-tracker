package com.finance.tracker.business.domain

/** Immutable references reused by a resolution group; hashing never rescans a shared candidate list. */
internal class SourceReferences(values: List<String>) : AbstractList<String>() {
    private val items = values.toList()
    private val contentHash = items.hashCode()
    override val size: Int get() = items.size
    override fun get(index: Int): String = items[index]
    override fun equals(other: Any?): Boolean = this === other || items == other
    override fun hashCode(): Int = contentHash
}
