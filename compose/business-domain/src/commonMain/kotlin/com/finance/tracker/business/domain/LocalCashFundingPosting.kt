package com.finance.tracker.business.domain

/** Funding and source updates validate with the same production local record values. */
internal object LocalCashFundingPosting {
    fun validate(record: Map<*, *>) = LocalCashRecordValues.validateForPosting(record)
}
