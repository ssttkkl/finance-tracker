package com.finance.tracker

internal fun availableCategoryParents(
    items: List<CashCategoryDto>,
    editingCategoryId: String?,
): List<CashCategoryDto> {
    val editing = items.firstOrNull { it.id == editingCategoryId }
    val deepestDescendant = editing?.let { current ->
        items.asSequence()
            .filter { candidate -> candidate.id == current.id || candidate.path.any { it.id == current.id } }
            .maxOfOrNull(CashCategoryDto::depth)
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

internal fun canDeleteCashCategory(impact: CashCategoryDeleteImpactDto): Boolean = impact.childCount == 0

internal fun filterCashCategoriesByAncestorPath(
    items: List<CashCategoryDto>,
    search: String,
): List<CashCategoryDto> {
    val term = search.trim()
    if (term.isEmpty()) return items
    return items.filter { category ->
        category.path.joinToString(" / ") { it.name }.contains(term, ignoreCase = true)
    }
}
