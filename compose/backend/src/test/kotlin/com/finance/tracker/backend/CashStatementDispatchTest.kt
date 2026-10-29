package com.finance.tracker.backend

import java.util.Base64
import kotlin.test.*
import kotlinx.serialization.json.*

class CashStatementDispatchTest {
    private fun golden(file: String, name: String? = null): JsonObject {
        val cases = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream(file))
            .bufferedReader().use { it.readText() }).jsonArray
        return (if (name == null) cases.first() else cases.first { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull == name }).jsonObject
    }

    @Test fun parsesAndNormalizesEachDocumentFormat() {
        val pdf = golden("/python-icbc-pdf-documents.json", "credit")
        val pdfContent = Base64.getDecoder().decode(pdf.getValue("content").jsonPrimitive.content)
        assertEquals("icbc_credit", CashStatementRowNormalization.parseCashStatement(pdfContent).sourceType)

        val platform = golden("/python-cash-statement-documents.json")
        val platformContent = Base64.getDecoder().decode(platform.getValue("content").jsonPrimitive.content)
        val platformSource = platform.getValue("source").jsonPrimitive.content
        assertEquals(platformSource, CashStatementRowNormalization.parseCashStatement(platformContent, platformSource).sourceType)

        val bank = golden("/python-bank-statement-documents.json")
        val bankContent = Base64.getDecoder().decode(bank.getValue("content").jsonPrimitive.content)
        assertEquals("ccb_debit", CashStatementRowNormalization.parseCashStatement(bankContent).sourceType)
    }

    @Test fun rejectsUnknownSourceWithoutReading() {
        val failure = assertFailsWith<StatementDocumentFailure> {
            CashStatementRowNormalization.parseCashStatement("not a statement".encodeToByteArray(), "unknown")
        }
        assertEquals("statement_source_unsupported", failure.code)
    }
}
