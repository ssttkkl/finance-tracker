package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class NormalizedCashDocumentWireTest {
    @Test fun completeDocumentRestoresRowsEvidenceAndExactValuesForLocalPlanning() {
        val fields=Json.parseToJsonElement("""{"amount":"-12.340000000000000001","occurred_at":"2026-10-01T00:00:00+08:00","source_payload":{"id":9007199254740993,"active":true}}""").jsonObject
        val explanation=Json.parseToJsonElement("""{"native_type":"消费","page":2}""").jsonObject
        val coverage=CashSourceCoverage("2026-10-01T00:00:00+08:00","2026-11-01T00:00:00+08:00",true,"synthetic",emptyMap(),"coverage-v1",
            documentKey="a".repeat(64),pageTotal=2,pageFingerprints=mapOf("2" to "b".repeat(64)))
        val candidate=NormalizedCashRowDto(2,fields,"card","source-id",listOf("review"),null,"candidate","1234","2:1",explanation)
        val bad=NormalizedCashRowDto(3,null,"",null,emptyList(),"row_shape_invalid","error",null,"2:2",JsonObject(emptyMap()))
        val wire=NormalizedCashDocumentDto("icbc","icbc_pdf","parser-v1",listOf(candidate,bad),"1234","卡片",coverage,2L,2,"昵称")
        val local=localNormalizedCashDocument(Json.decodeFromString<NormalizedCashDocumentDto>(Json.encodeToString(wire)))
        assertEquals("-12.340000000000000001",local.rows.first().fields!!["amount"])
        assertEquals(Json.parseToJsonElement("9007199254740993"),(local.rows.first().fields!!["source_payload"] as Map<*,*>)["id"])
        assertNull(local.rows.last().fields)
        assertEquals(wire,normalizedCashDocumentWire(local) { encodeLocalCashValue(it).jsonObject })
    }
}
