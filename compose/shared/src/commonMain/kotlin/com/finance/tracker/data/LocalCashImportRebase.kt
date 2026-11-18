package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashWriteSetDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.ParsedImportFile
import kotlinx.serialization.json.JsonObject

/** The complete original calculation, bound to the supplied files, snapshot and recovery inputs. */
internal typealias LocalCashImportCalculator = (List<ParsedImportFile>, LedgerSnapshotDto, JsonObject,
    Map<String, Any?>, Map<String, String>) -> Pair<Map<String, Any?>, CashWriteSetDto>

internal class LocalCashImportRetryLimitReached(val rejection: ApiFailure) : Exception("cash_import_retry_exhausted", rejection)
