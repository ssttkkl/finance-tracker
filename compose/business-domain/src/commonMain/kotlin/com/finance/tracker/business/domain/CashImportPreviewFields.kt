package com.finance.tracker.business.domain

internal val previewProjectionFields = commonCashFields + financialCashFields + setOf(
    "state", "record_origin", "record_id", "source_type", "record_type", "record_subtype", "source_payload",
)

private val previewFields = (previewProjectionFields + listOf(
    "created_at", "source_explanation", "source_scope_key", "matching_record_id", "stable_source_key",
)).toList().also { check(it.size <= Int.SIZE_BITS) }

private val previewFieldPositions = previewFields.withIndex().associate { (position, field) -> field to position }

/** Store the fields of each locally overlaid parent without a separate hash entry per field. */
internal class CashImportPreviewFields : AbstractMutableMap<String, Any?>() {
    private val data = arrayOfNulls<Any>(previewFields.size)
    private val order = IntArray(previewFields.size)
    private var occupied = 0
    private var count = 0

    override val size get() = count

    override fun get(key: String): Any? = previewFieldPositions[key]?.let { data[it] }

    override fun containsKey(key: String): Boolean =
        previewFieldPositions[key]?.let { occupied and (1 shl it) != 0 } ?: false

    override fun put(key: String, value: Any?): Any? {
        val position = previewFieldPositions.getValue(key)
        val mask = 1 shl position
        if (occupied and mask == 0) {
            order[count++] = position
            occupied = occupied or mask
        }
        val previous = data[position]
        data[position] = value
        return previous
    }

    override val entries: MutableSet<MutableMap.MutableEntry<String, Any?>>
        get() = object : AbstractMutableSet<MutableMap.MutableEntry<String, Any?>>() {
            override val size get() = count

            override fun add(element: MutableMap.MutableEntry<String, Any?>): Boolean = throw UnsupportedOperationException()

            override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, Any?>> = object :
                MutableIterator<MutableMap.MutableEntry<String, Any?>> {
                private var next = 0
                private var canRemove = false

                override fun hasNext() = next < count

                override fun next(): MutableMap.MutableEntry<String, Any?> {
                    if (!hasNext()) throw NoSuchElementException()
                    val position = order[next++]
                    canRemove = true
                    return object : MutableMap.MutableEntry<String, Any?> {
                        override val key get() = previewFields[position]
                        override val value get() = data[position]
                        override fun setValue(newValue: Any?): Any? = put(key, newValue)
                        override fun equals(other: Any?): Boolean =
                            other is Map.Entry<*, *> && key == other.key && value == other.value
                        override fun hashCode(): Int = key.hashCode() xor (value?.hashCode() ?: 0)
                    }
                }

                override fun remove() {
                    check(canRemove)
                    next--
                    val position = order[next]
                    data[position] = null
                    occupied = occupied and (1 shl position).inv()
                    count--
                    order.copyInto(order, next, next + 1, count + 1)
                    canRemove = false
                }
            }
        }
}
