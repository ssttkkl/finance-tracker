package com.finance.tracker.business.domain

/** A snapshot of identity and ancestry; callers retain authority over its source. */
data class CategoryPosition(val id: String, val depth: Int, val ancestorIds: List<String> = emptyList())

fun availableCategoryParentIds(items: List<CategoryPosition>, editingCategoryId: String?): List<String> {
    val editing = items.firstOrNull { it.id == editingCategoryId }
    val height = editing?.let { root -> items.filter { it.id == root.id || root.id in it.ancestorIds }.maxOf { it.depth } - root.depth } ?: 0
    return items.filter { candidate -> if (editing == null) candidate.depth < 5
        else categoryParentViolation(editing, candidate, height) == null }.map { it.id }
}

data class CategoryMovePlan(val depths: Map<String, Int> = emptyMap(), val violation: String? = null)

private fun categoryParentViolation(category: CategoryPosition, parent: CategoryPosition?, subtreeHeight: Int): String? = when {
    parent != null && (parent.id == category.id || category.id in parent.ancestorIds) -> "category.cycle"
    (parent?.depth ?: 0) + 1 + subtreeHeight > 5 -> "category.depth_limit"
    else -> null
}

fun categoryMovePlan(items: List<CategoryPosition>, categoryId: String, parentId: String?): CategoryMovePlan {
    val original = items.firstOrNull { it.id == categoryId } ?: return CategoryMovePlan(violation = "category.not_found")
    val parent = parentId?.let { id -> items.firstOrNull { it.id == id } ?: return CategoryMovePlan(violation = "category.not_found") }
    val subtree = items.filter { it.id == categoryId || categoryId in it.ancestorIds }
    val height = subtree.maxOf { it.depth } - original.depth
    categoryParentViolation(original, parent, height)?.let { return CategoryMovePlan(violation = it) }
    val newDepth = (parent?.depth ?: 0) + 1
    val depths = subtree.associate { it.id to newDepth + (it.depth - original.depth) }
    return CategoryMovePlan(depths)
}

data class CategoryNode(val id: String, val parentId: String?, val name: String, val sortOrder: Int)

/** Uses the same stable sibling order and pre-order traversal as the ledger directory. */
fun orderedCategoryNodes(nodes: List<CategoryNode>): List<CategoryNode> {
    val byParent = nodes.groupBy { it.parentId }
    val ordered = mutableListOf<CategoryNode>()
    fun visit(parentId: String?) {
        byParent[parentId].orEmpty().sortedWith(compareBy<CategoryNode> { it.sortOrder }.thenBy { it.id }).forEach {
            ordered.add(it)
            visit(it.id)
        }
    }
    visit(null)
    return ordered
}

fun categoryAncestors(node: CategoryNode, byId: Map<String, CategoryNode>): List<CategoryNode> {
    val ancestors = mutableListOf<CategoryNode>()
    val visited = mutableSetOf(node.id)
    var parentId = node.parentId
    while (parentId != null) {
        require(visited.add(parentId)) { "category.cycle" }
        val parent = requireNotNull(byId[parentId]) { "category.missing_parent" }
        ancestors.add(parent)
        parentId = parent.parentId
    }
    return ancestors.asReversed()
}

fun categoryNamePath(node: CategoryNode, byId: Map<String, CategoryNode>): List<String> =
    categoryAncestors(node, byId).map { it.name } + node.name
