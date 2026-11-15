package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.domain.LocalCashImportReview
import kotlinx.serialization.json.JsonObject

/** A computed preview and its confirmation inputs. Nothing is saved or submitted until the user confirms. */
internal class LocalCashImportCalculation(val plan: JsonObject, val review: LocalCashImportReview, val request: CashWriteSetRequestDto)
