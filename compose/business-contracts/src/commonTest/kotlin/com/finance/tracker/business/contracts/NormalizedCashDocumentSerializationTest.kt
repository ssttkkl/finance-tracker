package com.finance.tracker.business.contracts

import kotlinx.serialization.json.*
import kotlin.test.*

class NormalizedCashDocumentSerializationTest {
    @Test fun normalizedOpenValuesSurviveDecodeAndReencodeWithoutNumberCoercion() {
        val values=Json.parseToJsonElement("""{"nested":[9007199254740993,1e0,-0,1.2300,1e400,18446744073709551616,true,null,"1e0"]}""").jsonObject
        val row=NormalizedCashRowDto(1,values,"",null,emptyList(),null,"candidate",null,"1:1",values)
        val decoded=Json.decodeFromString<NormalizedCashRowDto>(Json.encodeToString(row))
        assertEquals(row,decoded)
        assertEquals(row,Json.decodeFromString<NormalizedCashRowDto>(Json.encodeToString(decoded)))
    }
}
