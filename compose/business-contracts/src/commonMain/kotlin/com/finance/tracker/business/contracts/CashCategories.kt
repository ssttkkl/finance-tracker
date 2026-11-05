package com.finance.tracker.business.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CategoryDirectoryDto(val revision: Long, val items: List<CategoryDirectoryItemDto>)

/** Directory and mutation responses use names, unlike the ledger's object-path summaries. */
@Serializable
data class CategoryDirectoryItemDto(
    val id: String,
    @SerialName("parent_id") val parentId: String?,
    val name: String,
    val description: String?,
    val path: List<String>,
    val depth: Int,
    @SerialName("sort_order") val sortOrder: Int,
    val revision: Long,
)
