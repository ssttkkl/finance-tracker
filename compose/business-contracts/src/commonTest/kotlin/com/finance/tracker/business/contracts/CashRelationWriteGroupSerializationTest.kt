package com.finance.tracker.business.contracts

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.test.*

class CashRelationWriteGroupSerializationTest {
    private val json = Json { encodeDefaults = true }
    private fun edge(ref: String) = buildJsonObject { put("type", "new"); put("ref", ref) }
    private fun group(vararg refs: String, patch: JsonElement? = null) = buildJsonObject {
        put("relations", JsonArray(refs.map(::edge)))
        if (patch != null) put("common_fields", patch)
    }
    private fun encode(groups: List<JsonObject>): JsonArray {
        val input = buildJsonObject { put("relation_groups", JsonArray(groups)) }
        val decoded = json.decodeFromJsonElement<CashWriteSetDto>(input)
        return json.encodeToJsonElement(CashWriteSetDto.serializer(), decoded).jsonObject.getValue("relation_groups").jsonArray
    }

    @Test fun orderedGroupsPreserveTheirCompleteEdgePartitionsAndIndependentPublicChoices() {
        val input = listOf(group("second", patch = buildJsonObject { put("note", "B"); put("category_id", JsonNull) }),
            group("first", "9007199254740993", patch = JsonObject(emptyMap())))
        assertEquals(JsonArray(input), encode(input))
        val groups = encode(input)
        assertEquals(JsonPrimitive("B"), groups.first().jsonObject.getValue("common_fields").jsonObject.getValue("note"))
        assertEquals("9007199254740993", groups.last().jsonObject.getValue("relations").jsonArray.last().jsonObject.getValue("ref").jsonPrimitive.content)
    }

    @Test fun anOmittedPatchIsAnEmptyObjectButAnExplicitNullOrWrongShapeIsRejected() {
        assertEquals(JsonObject(emptyMap()), encode(listOf(group("edge"))).single().jsonObject.getValue("common_fields"))
        for (bad in listOf(JsonNull, JsonArray(emptyList()), JsonPrimitive("patch"))) {
            assertFailsWith<SerializationException> { encode(listOf(group("edge", patch = bad))) }
        }
    }
}
