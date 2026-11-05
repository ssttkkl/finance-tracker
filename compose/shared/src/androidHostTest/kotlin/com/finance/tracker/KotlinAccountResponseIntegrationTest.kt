package com.finance.tracker

import com.finance.tracker.data.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue

class KotlinAccountResponseIntegrationTest {
    @Test fun actualKtorAccountResponsesDecodeThroughTheExistingComposeCommands() = runTest {
        val root=System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")
        assumeTrue("Requires explicitly captured anonymous Ktor responses; skip is not migration acceptance",root!=null)
        val captured=Json.parseToJsonElement(Files.readString(Path.of(requireNotNull(root),"account-responses.json"))).jsonObject
        val seen=mutableSetOf<String>()
        for(item in captured.getValue("responses").jsonArray) {
            val source=item.jsonObject
            val method=source.getValue("method").jsonPrimitive.content
            val path=source.getValue("path").jsonPrimitive.content
            val body=source.getValue("body").jsonPrimitive.content
            val expected=Json.decodeFromString<ImportAccountDto>(body)
            val engine=MockEngine { request ->
                assertEquals(method,request.method.value);assertEquals(path,request.url.encodedPath)
                if(method!="GET") assertEquals("account-key",request.headers["Idempotency-Key"])
                respond(body,headers=headersOf(HttpHeaders.ContentType,"application/json"))
            }
            val client=FinanceApiClient({"https://api.example.invalid"},CategoryTokenStore(),engine)
            try {
                val actual=when(method) {
                    "POST" -> client.createImportAccount(expected.name,expected.type,expected.currencies,"account-key")
                    "PATCH" -> client.updateImportAccount(expected.accountId,1,buildJsonObject {},"account-key")
                    "DELETE" -> client.deleteImportAccount(expected.accountId,1,"account-key")
                    else -> client.readImportAccount(expected.accountId)
                }
                assertEquals(expected,actual)
                if(method=="GET") {assertNull(actual.receiptId);assertNull(actual.committed)}
                else {assertNotNull(actual.receiptId);assertEquals(true,actual.committed)}
                seen+=method
            } finally {client.close()}
        }
        assertEquals(setOf("GET","POST","PATCH","DELETE"),seen)
    }
    @Test fun actualKtorCashAndInvestmentListsDecodeThroughBothExistingComposeConsumers() = runTest {
        val root=System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")
        assumeTrue("Requires actual anonymous Ktor captures; skip is not acceptance",root!=null)
        val captured=Json.parseToJsonElement(Files.readString(Path.of(requireNotNull(root),"account-responses.json"))).jsonObject
        val seen=mutableSetOf<String>()
        for(value in captured.getValue("lists").jsonArray) {
            val source=value.jsonObject;val view=source.getValue("view").jsonPrimitive.content;val body=source.getValue("body").jsonPrimitive.content
            val expected=Json.parseToJsonElement(body).jsonObject.getValue("items").jsonArray
            val engine=MockEngine {request ->assertEquals("/api/v1/accounts",request.url.encodedPath);assertEquals(view,request.url.parameters["view"]);respond(body,headers=headersOf(HttpHeaders.ContentType,"application/json"))}
            val client=FinanceApiClient({"https://api.example.invalid"},CategoryTokenStore(),engine)
            try {
                val accounts=if(view=="cash") client.fetchCashAccounts() else client.fetchInvestmentAccounts()
                assertEquals(expected.map {it.jsonObject.getValue("id").jsonPrimitive.long},accounts.map {it.id})
                assertEquals(expected.map {it.jsonObject.getValue("name").jsonPrimitive.content},accounts.map {it.name})
                if(view=="cash") assertEquals(accounts.map {it.id.toString()},client.listCashImportAccounts().map {it.id})
                seen+=view
            } finally {client.close()}
        }
        assertEquals(setOf("cash","investment"),seen)
    }

}
