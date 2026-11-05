package com.finance.tracker.backend

import kotlin.test.*

/** Python 动态 str 子类无法在 JVM 上携带属性；这里固定语言边界与容器复制语义。 */
class CashRecordPolicyLanguageBoundaryTest {
    @Test fun componentNormalizationCopiesSharedContainersWithoutMutatingInput() {
        val shared = linkedMapOf<String, Any?>("labels" to mutableListOf("original"))
        val source = linkedMapOf<String, Any?>(
            "account_id" to 41, "amount" to CashAmount.parse("1.5"), "currency" to "CNY", "ordinal" to 0,
            "optional" to null, "flag" to true, "first" to shared, "second" to shared,
        )
        val result = CashRecordPolicy.normalize("components", listOf(source)) as List<MutableMap<String, Any?>>
        val copied = result.single()
        assertNotSame(source, copied)
        assertEquals("41", copied["account_id"])
        assertEquals(41, source["account_id"])
        assertEquals(source["amount"], copied["amount"])
        copied["ordinal"] = 2
        assertEquals(0, source["ordinal"])
        assertSame(copied["first"], copied["second"])
        @Suppress("UNCHECKED_CAST")
        ((copied["first"] as Map<String, Any?>)["labels"] as MutableList<Any?>).add("changed")
        assertEquals(listOf("original"), (shared["labels"] as List<*>))
    }

    @Test fun jvmStringsCannotCarryMutableAttributesAndRemainByValue() {
        // Python 的 str 子类可附加属性；JVM String 是 final 且不可变，复制按值进行。
        val key = "tag"
        val value = "original"
        val source = linkedMapOf<String, Any?>(key to value)
        @Suppress("UNCHECKED_CAST")
        val result = CashRecordPolicy.normalize("components", listOf(source)) as List<MutableMap<String, Any?>>
        val copied = result.single()
        assertEquals("tag", copied.keys.single())
        assertEquals("original", copied["tag"])
        assertIs<String>(copied["tag"])
        copied["tag"] = "changed"
        assertEquals("original", source["tag"])
        assertEquals("tag", source.keys.single())
    }
}
