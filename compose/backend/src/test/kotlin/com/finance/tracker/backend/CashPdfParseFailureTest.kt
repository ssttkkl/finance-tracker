package com.finance.tracker.backend

import com.finance.tracker.business.contracts.CashPdfParseResultDto
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import com.finance.tracker.business.domain.normalizedCashDocumentWire
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class CashPdfParseFailureTest {
    @Test fun unknownAuthorizationFailureKeepsItsCauseAndSuppressedParserFailureInsideTheSafeBoundary()=runBlocking {
        val original=IllegalStateException("synthetic private authorization invariant")
        val parser=AssertionError("synthetic private parser invariant")
        val failure=assertFailsWith<CashStatementParseInternalFailure> {
            authorizedCashParse({throw parser}) {throw original}
        }
        assertSame(original,failure.cause)
        assertSame(parser,original.suppressed.single())
    }
    @Test fun successfulAuthorizationPreservesProgramCauseForTheSafeOuterBoundary()=runBlocking {
        val original=AssertionError("synthetic private parser invariant")
        val service=CashPdfParseService { _,_,_ ->throw original }
        val failure=assertFailsWith<CashStatementParseInternalFailure> {
            service.parseAuthorized(CashImportUpload("a","synthetic.pdf",byteArrayOf(1))) {}
        }
        assertSame(original,failure.cause)
        val trace=statementFailureTrace(failure).toString()
        assertTrue(trace.contains("java.lang.AssertionError"))
        assertFalse(trace.contains("synthetic private parser invariant"))
    }
    @Test fun authorizationFailureRetainsOriginalProgramFailureWithoutExposingIt()=runBlocking {
        val original=AssertionError("synthetic parser invariant")
        val denied=AccessFailure(true)
        val service=CashPdfParseService { _,_,_ ->throw original }
        val bytes=byteArrayOf(1)
        val actual=assertFailsWith<AccessFailure> {
            service.parseAuthorized(CashImportUpload("a","synthetic.pdf",bytes)) {throw denied}
        }
        assertSame(denied,actual)
        assertSame(original,actual.suppressed.single())
        assertTrue(actual.forbidden)
    }

    @Test fun parserCancellationPropagatesBeforeAuthorizationOrErrorConversion()=runBlocking {
        val cancellation=CancellationException("synthetic cancel")
        var authorized=false
        val service=CashPdfParseService { _,_,_ ->throw cancellation }
        val propagated=assertFailsWith<CancellationException> {
            service.parseAuthorized(CashImportUpload("a","synthetic.pdf",byteArrayOf(1))) {authorized=true}
        }
        assertEquals(cancellation.message,propagated.message)
        assertTrue(generateSequence<Throwable>(propagated) {it.cause}.any {it===cancellation})
        assertFalse(authorized)
    }
    @Test fun normalizedResponseBudgetCountsActualUtf8AndIncludesDefaultCoverageFields() {
        val document=ParsedCashDocument("icbc","icbc_pdf","v1",listOf(ParsedCashRow(1,mapOf("note" to "账".repeat(512)))),
            null,null,CashSourceCoverage(null,null,false,"",emptyMap(),"v1"),1L,1)
        val value=CashPdfParseResultDto(CASH_RULES_VERSION,normalizedCashDocumentWire(document) {sourceJson(it).jsonObject})
        val budget=Json {encodeDefaults=true;explicitNulls=true}.encodeToString(value).encodeToByteArray().size
        val upload=CashImportUpload("a","synthetic.pdf",byteArrayOf(1))
        assertEquals(value,CashPdfParseService(maxResponseBytes=budget) {_,_,_->document}.parse(upload))
        assertEquals("statement_rows_too_large",assertFailsWith<StatementDocumentFailure> {
            CashPdfParseService(maxResponseBytes=budget-1) {_,_,_->document}.parse(upload)
        }.code)
    }

}
