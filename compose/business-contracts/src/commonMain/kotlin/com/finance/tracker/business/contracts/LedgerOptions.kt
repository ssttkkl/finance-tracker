package com.finance.tracker.business.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SimpleOptionDto(val value: String,val label: String)

@Serializable
data class RecordTypeOptionDto(
    val value: String,
    val label: String,
    val subtypes: List<SimpleOptionDto> = emptyList(),
)

@Serializable
data class LedgerOptionsDto(
    @SerialName("record_types") val recordTypes: List<RecordTypeOptionDto> = emptyList(),
    @SerialName("relation_types") val relationTypes: List<SimpleOptionDto> = emptyList(),
)
