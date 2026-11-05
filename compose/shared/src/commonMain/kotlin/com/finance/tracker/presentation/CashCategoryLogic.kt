package com.finance.tracker.presentation

import com.finance.tracker.domain.*

internal fun availableCategoryParents(
    items: List<CashCategory>,
    editingCategoryId: String?,
): List<CashCategory> {
    val eligible = com.finance.tracker.business.domain.availableCategoryParentIds(
        items.map { item -> com.finance.tracker.business.domain.CategoryPosition(item.id, item.depth, item.path.map { it.id }) },
        editingCategoryId,
    ).toSet()
    return items.filter { it.id in eligible }
}

internal fun canDeleteCashCategory(impact: CashCategoryDeleteImpact): Boolean = impact.childCount == 0

internal fun filterCashCategoriesByAncestorPath(
    items: List<CashCategory>,
    search: String,
): List<CashCategory> {
    val term = search.trim()
    if (term.isEmpty()) return items
    return items.filter { category ->
        category.path.joinToString(" / ") { it.name }.contains(term, ignoreCase = true)
    }
}

internal fun categoryCanMove(items: List<CashCategory>, categoryId: String, direction: String): Boolean {
    val item = items.firstOrNull { it.id == categoryId } ?: return false
    val siblings = items.filter { it.parentId == item.parentId }
    val index = siblings.indexOfFirst { it.id == categoryId }
    return when (direction) {
        "before" -> index > 0
        "after" -> index >= 0 && index < siblings.lastIndex
        else -> false
    }
}
