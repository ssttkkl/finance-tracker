package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CategoryDirectoryDto
import com.finance.tracker.business.contracts.CategoryDirectoryItemDto
import com.finance.tracker.business.contracts.LedgerCategoryFactDto
import com.finance.tracker.business.contracts.LedgerOptionsDto
import kotlinx.serialization.json.Json
import kotlin.test.*

class CashLedgerCatalogTest {
    @Test fun allLabelsAndSubtypeOrderingMatchTheUnmodifiedOriginalGolden() {
        assertEquals(Json.decodeFromString<LedgerOptionsDto>(originalLedgerOptionsJson), buildCashLedgerOptions())
    }

    @Test fun callersCannotMutateOptionsReturnedToAnotherCaller() {
        val first = buildCashLedgerOptions()
        (first.recordTypes as MutableList).clear()
        assertEquals(17, buildCashLedgerOptions().recordTypes.size)
        val second = buildCashLedgerOptions()
        (second.recordTypes.first().subtypes as MutableList).clear()
        assertEquals(1, buildCashLedgerOptions().recordTypes.first().subtypes.size)
    }

    @Test fun categoryDirectoryUsesGlobalRevisionAndRebuildsFullPathsInOriginalTreeOrder() {
        val revision = 9007199254740993L
        val categories = listOf(
            category("b", null, "Other", "2"),
            category("a2", "a", "Second child", "3"),
            category("deep", "a2", "Grandchild", "0"),
            category("a1", "a", "First child", "3"),
            category("a", null, "Root", "1"),
        )
        assertEquals(CategoryDirectoryDto(revision, listOf(
            item("a", null, "Root", listOf("Root"), 1, revision),
            item("a1", "a", "First child", listOf("Root", "First child"), 3, revision),
            item("a2", "a", "Second child", listOf("Root", "Second child"), 3, revision),
            item("deep", "a2", "Grandchild", listOf("Root", "Second child", "Grandchild"), 0, revision),
            item("b", null, "Other", listOf("Other"), 2, revision),
        )), buildLocalCashCategoryDirectory(categories, revision.toString()))
    }

    @Test fun anEmptyDirectoryKeepsItsActualRevision() {
        assertEquals(CategoryDirectoryDto(7, emptyList()), buildLocalCashCategoryDirectory(emptyList(), "7"))
    }

    @Test fun storedLongSortOrderUsesTheOriginalDirectoryIntConversion() {
        val value = buildLocalCashCategoryDirectory(listOf(category("wide", null, "Wide", "4294967297")), "0")
        assertEquals(1, value.items.single().sortOrder)
    }

    @Test fun malformedVersionsAndSortOrderCannotBecomeDefaultValues() {
        assertFailsWith<NumberFormatException> { buildLocalCashCategoryDirectory(emptyList(), "not-a-version") }
        assertFailsWith<NumberFormatException> {
            buildLocalCashCategoryDirectory(listOf(category("bad", null, "Bad", "not-a-sort-order")), "0")
        }
    }

    private fun category(id: String, parent: String?, name: String, sort: String) = LedgerCategoryFactDto(
        id, parent, parent ?: "", name, name.lowercase(), "description:$id", "unused stale stored path",
        1, sort, "2", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")

    private fun item(id: String, parent: String?, name: String, path: List<String>, sort: Int, revision: Long) =
        CategoryDirectoryItemDto(id, parent, name, "description:$id", path, 1, sort, revision)
}
