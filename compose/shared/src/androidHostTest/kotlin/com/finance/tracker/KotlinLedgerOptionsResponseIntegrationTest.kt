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

class KotlinLedgerOptionsResponseIntegrationTest {
    @Test fun actualKtorOptionsDecodeAndMapThroughTheExistingComposeConsumer() = runTest {
        val root=System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")
        assumeTrue("Requires actual anonymous Ktor captures; skip is not acceptance",root!=null)
        val captured=Json.parseToJsonElement(Files.readString(Path.of(requireNotNull(root),"ledger-options-responses.json"))).jsonObject
        val roles=mutableSetOf<String>()
        for(raw in captured.getValue("responses").jsonArray) {
            val source=raw.jsonObject;val text=source.getValue("body").jsonPrimitive.content
            val expected=Json.decodeFromString<LedgerOptionsDto>(text)
            val engine=MockEngine {request ->
                assertEquals(HttpMethod.Get,request.method);assertEquals("/api/v1/cash-ledger/options",request.url.encodedPath)
                respond(text,headers=headersOf(HttpHeaders.ContentType,"application/json"))
            }
            val client=FinanceApiClient({"https://api.example.invalid"},CategoryTokenStore(),engine)
            try {
                val actual=client.fetchLedgerOptions();assertEquals(expected,actual);assertEquals(expected,actual.toDomain().toDto())
                assertEquals(17,actual.recordTypes.size);assertEquals(3,actual.relationTypes.size)
                assertEquals(listOf("ordinary_transfer","cross_border_remittance","internal_account_transfer"),actual.recordTypes.single {it.value=="transfer_out"}.subtypes.map {it.value})
                assertEquals("",actual.recordTypes.single {it.value=="consumption"}.subtypes.single().label)
                roles+=source.getValue("token_role").jsonPrimitive.content
            } finally {client.close()}
        }
        assertEquals(setOf("admin","viewer","editor"),roles)
    }
}
