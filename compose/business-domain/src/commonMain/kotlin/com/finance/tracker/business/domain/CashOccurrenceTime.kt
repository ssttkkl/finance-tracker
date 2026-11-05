package com.finance.tracker.business.domain

/** UTC epoch-second range for cash occurred_at only; raw dates and system/investment times have separate contracts. */
fun cashOccurrenceTimeInRange(epochSeconds: Long): Boolean = epochSeconds >= -2208988800L && epochSeconds < 4133980800L
