package com.finance.tracker

import com.finance.tracker.data.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlinx.serialization.decodeFromString
import org.junit.Assume.assumeTrue

class KotlinClassificationResponseIntegrationTest {
    @Test fun actualKtorClassificationResponsesUseTheExistingComposeRequestAndDomainMapping() = runTest {
        val root=System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")
        assumeTrue("Requires actual captured Ktor responses; skip is not acceptance",root!=null)
        val captured=Json.parseToJsonElement(Files.readString(Path.of(requireNotNull(root),"classification-responses.json"))).jsonObject
        assertTrue(captured.getValue("backend").jsonPrimitive.content in setOf("sqlite","postgresql"))
        var calls=0;var sawClear=false;var sawBatch=false;var sawNoChange=false
        var previous:CashClassificationResultDto?=null
        for(raw in captured.getValue("responses").jsonArray) {
            val source=raw.jsonObject;val request=source.getValue("request").jsonObject
            if(source.getValue("path").jsonPrimitive.content!="/api/v1/cash-projections/categories" || request["projection_ids"] !is JsonArray) continue
            val ids=request.getValue("projection_ids").jsonArray.map {it.jsonPrimitive.content}
            val version=request.getValue("projection_version").jsonPrimitive.long
            val category=request["category_id"]?.takeIf {it!=JsonNull}?.jsonPrimitive?.content
            val text=source.getValue("body").jsonPrimitive.content;val expected=Json.decodeFromString<CashClassificationResultDto>(text)
            val engine=MockEngine {actual ->
                assertEquals(HttpMethod.Put,actual.method);assertEquals("/api/v1/cash-projections/categories",actual.url.encodedPath)
                assertEquals(request,Json.parseToJsonElement((actual.body as TextContent).text))
                respond(text,headers=headersOf(HttpHeaders.ContentType,"application/json"))
            }
            val client=FinanceApiClient({"https://api.example.invalid"},CategoryTokenStore(),engine)
            try {
                val actual=client.classifyCashProjections(ids,version,category);assertEquals(expected,actual)
                val domain=actual.toDomain();assertEquals(actual.projectionVersion,domain.projectionVersion);assertEquals(actual.categoryId,domain.categoryId)
                assertEquals(actual.projectionCount,domain.projectionCount);assertEquals(actual.updatedTransactionCount,domain.updatedTransactionCount)
                sawClear=sawClear || category==null;sawBatch=sawBatch || actual.projectionCount>1;sawNoChange=sawNoChange || expected==previous
                previous=expected;calls++
            } finally {client.close()}
        }
        assertTrue(calls>=5);assertTrue(sawClear);assertTrue(sawBatch);assertTrue(sawNoChange)
    }
}
