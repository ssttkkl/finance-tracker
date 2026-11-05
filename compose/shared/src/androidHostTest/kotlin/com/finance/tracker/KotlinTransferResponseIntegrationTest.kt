package com.finance.tracker

import com.finance.tracker.data.*
import com.finance.tracker.domain.DomainFailure
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue

class KotlinTransferResponseIntegrationTest {
    @Test fun actualKtorTransferResponsesUseTheExistingComposeConsumerWithoutRetry()=runTest {
        val root=System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")
        assumeTrue("Actual Ktor captures are required; skip is not acceptance",root!=null)
        val captured=Json.parseToJsonElement(Files.readString(Path.of(requireNotNull(root),"cash-transfer-responses.json"))).jsonObject
        assertTrue(captured.getValue("backend").jsonPrimitive.content in setOf("sqlite","postgresql"))
        var successes=0;var failures=0;var precise=false
        for(raw in captured.getValue("responses").jsonArray) {
            val source=raw.jsonObject;val request=source.getValue("request").jsonObject
            val status=source.getValue("status").jsonPrimitive.int;val text=source.getValue("body").jsonPrimitive.content
            var calls=0
            val engine=MockEngine {actual ->
                calls++;assertEquals(HttpMethod.Post,actual.method);assertEquals("/api/v1/cashflow/transfer",actual.url.encodedPath)
                assertEquals(request,Json.parseToJsonElement((actual.body as TextContent).text))
                respond(text,HttpStatusCode.fromValue(status),headersOf(HttpHeaders.ContentType,"application/json"))
            }
            val client=FinanceApiClient({"https://api.example.invalid"},CategoryTokenStore(),engine)
            try {
                val repository=KtorCashCreationRepository(client)
                if(status==201) {repository.createTransfer(request.toStructuredObject());successes++
                    precise=precise || request["amount"]==JsonPrimitive("1.230000000000000001")
                } else {
                    val failure=assertFailsWith<DomainFailure> {repository.createTransfer(request.toStructuredObject())}
                    val expected=Json.parseToJsonElement(text).jsonObject.getValue("error").jsonObject
                    assertEquals(status,failure.status);assertEquals(expected.getValue("code").jsonPrimitive.content,failure.code)
                    failures++
                }
                assertEquals(1,calls)
            } finally {client.close()}
        }
        assertTrue(successes>=50);assertTrue(failures>=10);assertTrue(precise)
    }
}
