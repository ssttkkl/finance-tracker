package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class CashTransferRulesTest {
    private fun account(name:String,type:String)=CashTransferAccount(name,type)
    @Test fun finalSigmaAndCaseIgnorableLettersUseTheExistingInvestmentPosition() {
        assertEquals("οςʰ",pythonCashLower("ΟΣʰ"));assertEquals("οσʰa",pythonCashLower("ΟΣʰA"))
        assertEquals("σ",pythonCashLower("Σ"));assertEquals("aς",pythonCashLower("AΣ"))
        val source=Json.parseToJsonElement("""{"accounts":{"security":{"A":{"positions":{"οςʰ":{"shares":"5","total_cost":"6","cost_currency":"ΟΣʰ"}}}}}}""").jsonObject
        val changed=applyCashTransferSnapshot(source,account("A","security"),"ΟΣʰ",CashAmount.parse("1"))
        val positions=changed.getValue("accounts").jsonObject.getValue("security").jsonObject.getValue("A").jsonObject.getValue("positions").jsonObject
        assertEquals(setOf("οςʰ"),positions.keys);assertEquals(JsonPrimitive("6"),positions.getValue("οςʰ").jsonObject["shares"])
        assertEquals(JsonPrimitive("7"),positions.getValue("οςʰ").jsonObject["total_cost"])
        assertEquals("-0E+2147483648",CashAmount.parse("-0E2147483648").pythonTransferText())
    }
    @Test fun pureEventHelperNegatesSignedAmountsAndZerosExactly() {
        for((amount,expected) in listOf("1.20" to "-1.20","-1.20" to "1.20","0.00" to "-0.00","-0.00" to "0.00"))
            assertEquals(JsonPrimitive(expected),cashTransferEvent("date",CashAmount.parse(amount),account("A","cash"),account("B","cash"),"CNY","CNY",true,"")["amount"])
    }
    @Test fun cashLoanAndInvestmentSidesKeepTheirOriginalEventSemantics() {
        for(fromType in listOf("cash","loan","lend","security","crypto")) for(toType in listOf("cash","loan","lend","security","crypto")) {
            val from=account("来源",fromType);val to=account("目标",toType)
            for(currency in listOf("CNY","USD")) {
                val outgoing=cashTransferEvent("2026-10-24 04:30:00",CashAmount.parse("123.4500"),from,to,"CNY",currency,true,"")
                val incoming=cashTransferEvent("2026-10-24 04:30:00",CashAmount.parse("17.25000"),to,from,currency,"CNY",false,"")
                if(fromType in setOf("security","crypto")) {
                    assertEquals(JsonPrimitive("funding"),outgoing["record_type"])
                    assertEquals(JsonPrimitive("subaccount"),outgoing["record_subtype"])
                    assertEquals(JsonPrimitive("cny"),outgoing["from_ticker"])
                    assertEquals(JsonPrimitive("123.4500"),outgoing["from_amount"])
                    assertEquals(JsonPrimitive("0"),outgoing["to_amount"])
                    assertEquals(JsonPrimitive("transfer to:目标"),outgoing["note"])
                } else {
                    val expected=if(toType in setOf("security","crypto")) "transfer_out" else if(currency!="CNY") "fx_out" else if("loan" in setOf(fromType,toType)) "repayment" else "transfer_out"
                    assertEquals(JsonPrimitive(expected),outgoing["record_type"])
                    assertEquals(JsonPrimitive(defaultCashRecordSubtype(expected)),outgoing["record_subtype"])
                    assertEquals(JsonPrimitive("-123.4500"),outgoing["amount"])
                    assertEquals(JsonPrimitive("legacy"),outgoing["record_origin"])
                    assertEquals(JsonPrimitive(if(currency!="CNY") "购汇至USD" else "转账至目标"),outgoing["note"])
                }
                if(toType in setOf("security","crypto")) {
                    assertEquals(JsonPrimitive("17.25000"),incoming["to_amount"])
                    assertEquals(JsonPrimitive("0"),incoming["from_amount"])
                    assertEquals(JsonPrimitive(currency.lowercase()),incoming["to_ticker"])
                    assertEquals(JsonPrimitive("transfer from:来源"),incoming["note"])
                } else assertEquals(JsonPrimitive("17.25000"),incoming["amount"])
                assertEquals(JsonPrimitive("指定备注"),cashTransferEvent("date",CashAmount.parse("1"),from,to,"CNY",currency,true,"指定备注")["note"])
            }
        }
    }
    @Test fun transferBalancesPreserveCashAndInvestmentPrecisionAndUnknownFields() {
        var value=Json.parseToJsonElement("""{"unknown":true,"accounts":{"security":{"券商":{"currency":"HKD","extra":7,"positions":{"usd":{"shares":"0.000000000000000001","total_cost":"2.00","cost_currency":"HKD","other":true},"aapl":{"shares":"5"}}}}}}""").jsonObject
        value=applyCashTransferSnapshot(value,account("现金","cash"),"CNY",CashAmount.parse("-1.230000000000000001"))
        value=applyCashTransferSnapshot(value,account("券商","crypto"),"USD",CashAmount.parse("0.123456789012345678"))
        assertEquals(JsonPrimitive(true),value["unknown"])
        val accounts=value.getValue("accounts").jsonObject
        assertEquals(JsonPrimitive("-1.230000000000000001"),accounts.getValue("cash").jsonObject.getValue("现金").jsonObject["CNY"])
        val broker=accounts.getValue("security").jsonObject.getValue("券商").jsonObject
        assertEquals(JsonPrimitive("HKD"),broker["currency"]);assertEquals(JsonPrimitive(7),broker["extra"])
        val usd=broker.getValue("positions").jsonObject.getValue("usd").jsonObject
        assertEquals(JsonPrimitive("0.123456789012345679"),usd["shares"])
        assertEquals(JsonPrimitive("2.123456789012345678"),usd["total_cost"])
        assertEquals(JsonPrimitive("HKD"),usd["cost_currency"]);assertEquals(JsonPrimitive(true),usd["other"])
        assertEquals(Json.parseToJsonElement("""{"shares":"5"}"""),broker.getValue("positions").jsonObject["aapl"])
    }
    @Test fun overflowAndMalformedBalancesStopTheWholeCalculation() {
        val initial=Json.parseToJsonElement("""{"accounts":{"cash":{"A":{"CNY":"99999999999999999999"}}}}""").jsonObject
        assertFailsWith<IllegalArgumentException> {applyCashTransferSnapshot(initial,account("A","cash"),"CNY",CashAmount.parse("1"))}
        assertEquals(JsonPrimitive("99999999999999999999"),initial.getValue("accounts").jsonObject.getValue("cash").jsonObject.getValue("A").jsonObject["CNY"])
        for(value in listOf("NaN","Infinity","bad","0.0000000000000000001")) {
            val source=Json.parseToJsonElement("""{"accounts":{"security":{"A":{"positions":{"usd":{"shares":"$value","total_cost":"0"}}}}}}""").jsonObject
            assertFailsWith<IllegalArgumentException> {applyCashTransferSnapshot(source,account("A","security"),"USD",CashAmount.parse("1"))}
        }
    }
    @Test fun pythonReferenceCoversEveryAccountPairAndSnapshotBoundary() {
        val data=Json.parseToJsonElement(requireNotNull(javaClass.getResource("/python-cash-transfer-rules.json")).readText()).jsonObject
        for(raw in data.getValue("events").jsonArray) {
            val row=raw.jsonObject
            fun text(key:String)=row.getValue(key).jsonPrimitive.content
            assertEquals(row.getValue("expected"),cashTransferEvent(text("date"),CashAmount.parse(text("amount")),account(text("name"),text("type")),account(text("counterpart"),text("counterpart_type")),text("currency"),text("counterpart_currency"),row.getValue("outgoing").jsonPrimitive.boolean,text("note")),row.toString())
        }
        for(raw in data.getValue("snapshots").jsonArray) {
            val row=raw.jsonObject;val source=row.getValue("snapshot").jsonObject
            fun apply()=applyCashTransferSnapshot(source,account("A",row.getValue("type").jsonPrimitive.content),row["currency"]?.jsonPrimitive?.content ?: "USD",CashAmount.parse(row.getValue("amount").jsonPrimitive.content))
            if("error" in row) assertEquals(row.getValue("error").jsonPrimitive.content,assertFailsWith<IllegalArgumentException> {apply()}.message,row.toString())
            else assertEquals(row.getValue("expected"),apply(),row.toString())
        }
        assertTrue(data.getValue("events").jsonArray.size>=200)
        assertTrue(data.getValue("snapshots").jsonArray.size>=100)
    }
}
