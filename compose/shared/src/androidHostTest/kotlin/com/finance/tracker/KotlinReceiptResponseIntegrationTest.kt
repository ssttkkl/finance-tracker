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

class KotlinReceiptResponseIntegrationTest {
    @Test fun actualKtorReceiptAndResultPagesUseExistingComposeApiConsumers() = runTest {
        val root=System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")
        assumeTrue("Requires actual anonymous Ktor captures; skip is not acceptance",root!=null)
        val captured=Json.parseToJsonElement(Files.readString(Path.of(requireNotNull(root),"receipt-responses.json"))).jsonObject
        val seen=mutableSetOf<String>()
        for(raw in captured.getValue("responses").jsonArray) {
            val source=raw.jsonObject;val path=source.getValue("path").jsonPrimitive.content;val text=source.getValue("body").jsonPrimitive.content
            if(path.endsWith("/receipt-large-count")) {
                // This pre-existing client boundary stays explicit until its count model is migrated.
                assertFails {Json.decodeFromString<CashMutationReceiptDto>(text)}
                seen+="large-count-boundary";continue
            }
            val url=Url("https://api.example.invalid$path")
            val engine=MockEngine {request ->
                assertEquals(HttpMethod.Get,request.method);assertEquals(url.encodedPath,request.url.encodedPath)
                assertEquals(url.parameters,request.url.parameters)
                respond(text,headers=headersOf(HttpHeaders.ContentType,"application/json"))
            }
            val client=FinanceApiClient({"https://api.example.invalid"},CategoryTokenStore(),engine)
            try {
                when {
                    url.encodedPath.endsWith("/rows") || url.encodedPath.endsWith("/relations") -> {
                        val kind=if(url.encodedPath.endsWith("/rows")) CashMutationResultKind.ROW else CashMutationResultKind.RELATION
                        val after=url.parameters["after"] ?: "0";val limit=url.parameters["limit"]?.toInt() ?: 50
                        val expected=Json.decodeFromString<CashMutationResultPageDto>(text)
                        // The existing client always emits both canonical page parameters.
                        val pageEngine=MockEngine {request ->
                            assertEquals(url.encodedPath,request.url.encodedPath);assertEquals(after,request.url.parameters["after"]);assertEquals(limit.toString(),request.url.parameters["limit"])
                            respond(text,headers=headersOf(HttpHeaders.ContentType,"application/json"))
                        }
                        val pageClient=FinanceApiClient({"https://api.example.invalid"},CategoryTokenStore(),pageEngine)
                        try {assertEquals(expected,pageClient.readCashMutationResults(url.encodedPath.substringBeforeLast('/').substringAfterLast('/'),kind,after,limit))} finally {pageClient.close()}
                        seen+=if(kind==CashMutationResultKind.ROW) "rows" else "relations"
                    }
                    url.encodedPath.endsWith("/by-key") -> {
                        assertEquals(Json.decodeFromString<CashMutationReceiptDto>(text),client.readCashMutationReceiptByKey(url.parameters["key"]!!));seen+="key"
                    }
                    "/by-key/" in url.encodedPath -> {
                        assertEquals(Json.decodeFromString<CashMutationReceiptDto>(text).receiptId,"receipt-main");seen+="legacy-path"
                    }
                    else -> {
                        assertEquals(Json.decodeFromString<CashMutationReceiptDto>(text),client.readCashMutationReceipt(url.encodedPath.substringAfterLast('/')));seen+="id"
                    }
                }
            } finally {client.close()}
        }
        assertEquals(setOf("id","key","rows","relations","legacy-path","large-count-boundary"),seen)
    }
}
