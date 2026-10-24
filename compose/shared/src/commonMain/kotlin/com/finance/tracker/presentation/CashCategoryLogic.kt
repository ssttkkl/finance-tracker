package com.finance.tracker.presentation

import com.finance.tracker.domain.*

internal fun availableCategoryParents(
    items: List<CashCategory>,
    editingCategoryId: String?,
): List<CashCategory> {
    val editing = items.firstOrNull { it.id == editingCategoryId }
    val deepestDescendant = editing?.let { current ->
        items.asSequence()
            .filter { candidate -> candidate.id == current.id || candidate.path.any { it.id == current.id } }
            .maxOfOrNull(CashCategory::depth)
            ?: current.depth
    }
    val subtreeHeight = editing?.let { current -> deepestDescendant?.minus(current.depth) ?: 0 }

    return items.filter { candidate ->
        val isSelfOrDescendant = editing != null &&
            (candidate.id == editing.id || candidate.path.any { it.id == editing.id })
        val fitsDepthLimit = subtreeHeight == null || candidate.depth + 1 + subtreeHeight <= 5
        !isSelfOrDescendant && candidate.depth < 5 && fitsDepthLimit
    }
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
