package com.finance.tracker.business.contracts

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class AccountWireContractTest {
    @Test fun commandResponseOmitsReadOnlyCapabilitiesAndNullReceiptMetadata() {
        val json=Json {encodeDefaults=true;explicitNulls=false}
        val value=AccountCommandViewDto("9223372036854775806","账户","cash",true,listOf("CNY"),9223372036854775806L,false)
        val encoded=Json.parseToJsonElement(json.encodeToString(value)).jsonObject
        assertEquals(setOf("account_id","name","type","active","currencies","account_revision","deleted"),encoded.keys)
        assertEquals("9223372036854775806",encoded.getValue("account_revision").jsonPrimitive.content)
        val read=Json.parseToJsonElement(json.encodeToString(value.copy(capabilities=mapOf("can_update" to true,"can_delete" to false)))).jsonObject
        assertEquals(JsonPrimitive(false),read.getValue("capabilities").jsonObject["can_delete"])
    }
}
