package com.finance.tracker.backend

import kotlin.test.*

class ReceiptProtocolMigrationTest {
    @Test fun legacyKeyQueryPreservesFullTextAndRejectsEveryOriginalInvalidQuery() {
        for(key in listOf("旧结果/键 ?&+=#%","导".repeat(255)," key with internal spaces "))
            assertEquals(key,receiptKeyQuery("key="+java.net.URLEncoder.encode(key,Charsets.UTF_8)))
        for(query in listOf("","key=","key=a&key=b","key=a&private=secret","key=%FF","key=%","key="+"a".repeat(256),"key="+"a".repeat(5000)))
            assertEquals("import_payload_invalid",assertFailsWith<ReceiptFailure> {receiptKeyQuery(query)}.code)
    }
    @Test fun resultPageInputIsStrictBoundedAndRejectsDuplicateOrUnknownFields() {
        assertEquals(ReceiptPage(0,50),receiptPageQuery(""));assertEquals(ReceiptPage(Long.MAX_VALUE,200),receiptPageQuery("after=9223372036854775807&limit=200"))
        for(query in listOf("limit=0","limit=201","limit=-1","limit=01","limit=1.0","limit= 1","after=-1","after=01","after=9223372036854775808","limit=1&limit=2","key=private","after="))
            assertEquals("import_payload_invalid",assertFailsWith<ReceiptFailure> {receiptPageQuery(query)}.code)
    }
}
