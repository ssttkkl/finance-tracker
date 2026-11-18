package com.finance.tracker.business.domain

import kotlin.test.*
import kotlinx.serialization.json.*

class CashRelationPlanCacheTest {
    private val golden=Json.parseToJsonElement("""{"cached":{"version":1,"plan_digest":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd","external_context_digest":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","proposals":[{"proposal_key":"proposal:a9468b3f1e8ae985e19d61ee4dcb5b58","kind":"refund_offset","status":"pending_review","rule_id":"scan.refund","confidence":"weak","primary_ref":"alipay:R1","secondary_ref":"wechat:R2","anchor_ref":"alipay:R1","primary_fact_type":"cash","secondary_fact_type":"cash","created_by":"system","open_leg":false,"evidence":{"amount_delta":"0.00","time_delta_seconds":null,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"","candidate_count":1,"signals":[],"open_leg":false,"anchor_role":"","candidate_refs":["wechat:R2","alipay:R1"],"extras":{"refund_amount":"30.00"}}},{"proposal_key":"proposal:b033578bd7cee042c047a749ef853aef","kind":"refund_offset","status":"accepted","rule_id":"scan.refund","confidence":"weak","primary_ref":"alipay:R1","secondary_ref":"wechat:R2","anchor_ref":"alipay:R1","primary_fact_type":"cash","secondary_fact_type":"cash","created_by":"system","open_leg":false,"evidence":{"amount_delta":"0","time_delta_seconds":5,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"","candidate_count":1,"signals":["refund"],"open_leg":false,"anchor_role":"","candidate_refs":["wechat:R2"],"extras":{"refund_amount":"30.00"}}},{"proposal_key":"proposal:e50b7ad4ba72100e36d1a58713c9907c","kind":"transfer_pair","status":"pending_review","rule_id":"scan.open","confidence":"weak","primary_ref":"wechat:R2","secondary_ref":"","anchor_ref":"wechat:R2","primary_fact_type":"cash","secondary_fact_type":"cash","created_by":"system","open_leg":true,"evidence":{"amount_delta":"0","time_delta_seconds":0,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"","candidate_count":1,"signals":[],"open_leg":true,"anchor_role":"","candidate_refs":["alipay:R1"],"extras":{}}}]},"decoded":[{"key":"proposal:a9468b3f1e8ae985e19d61ee4dcb5b58","body":{"kind":"refund_offset","primary_fact_id":"p1","secondary_fact_id":"q1","primary_fact_type":"cash","secondary_fact_type":"cash","status":"pending_review","rule_id":"scan.refund","confidence":"weak","evidence":{"amount_delta":"0.00","time_delta_seconds":null,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"","candidate_count":1,"signals":[],"open_leg":false,"anchor_role":"","candidate_fact_ids":["q1","p1"],"extras":{"refund_amount":"30.00"}},"created_by":"system","anchor_fact_id":"p1","open_leg":false}},{"key":"proposal:b033578bd7cee042c047a749ef853aef","body":{"kind":"refund_offset","primary_fact_id":"p1","secondary_fact_id":"q1","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"scan.refund","confidence":"weak","evidence":{"amount_delta":"0","time_delta_seconds":5,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"","candidate_count":1,"signals":["refund"],"open_leg":false,"anchor_role":"","candidate_fact_ids":["q1"],"extras":{"refund_amount":"30.00"}},"created_by":"system","anchor_fact_id":"p1","open_leg":false}},{"key":"proposal:e50b7ad4ba72100e36d1a58713c9907c","body":{"kind":"transfer_pair","primary_fact_id":"q1","secondary_fact_id":null,"primary_fact_type":"cash","secondary_fact_type":"cash","status":"pending_review","rule_id":"scan.open","confidence":"weak","evidence":{"amount_delta":"0","time_delta_seconds":0,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"","candidate_count":1,"signals":[],"open_leg":true,"anchor_role":"","candidate_fact_ids":["p1"],"extras":{}},"created_by":"system","anchor_fact_id":"q1","open_leg":true}}],"decoded_facts":["p1","q1"],"by_reference":["alipay:R1","p1","q1","wechat:R2"],"resolve_ref":["p1","p1","p1","q1","missing"],"seeds":{"expanded":["p1","unknown","q1"],"all":["p1","q1"]},"aliases":{"card_tails":{"1234":["a","b"]},"account_identifiers":{"622202123456":["a"]}},"expanded":[{"id":"c1","parent_id":"p1","component_ordinal":1,"amount":"-60.00","account_id":"a","cash_granularity":"aggregate","currency":"CNY","record_id":"R1"},{"id":"c2","parent_id":"p1","component_ordinal":2,"amount":"-40.00","account_id":"b","cash_granularity":"aggregate","currency":"CNY","record_id":"R1"},{"id":"c3","parent_id":"q1","component_ordinal":null,"amount":"30.00","account_id":"a","cash_granularity":"atomic","currency":"CNY","record_id":"R2"},{"id":"s1","parent_id":null,"component_ordinal":null,"amount":"-5.00","account_id":null,"cash_granularity":null,"currency":"CNY","record_id":"R3"}],"remaining":{"p1":"70.00"},"decisions":{"mirror":["p1","q1"],"matches":true,"primary":true,"wrong_key":false,"candidate":false},"legality":["import_relation_reconfirmation_required","import_relation_reconfirmation_required"],"decode_error":"import_relation_reconfirmation_required","key_error":"import_relation_reconfirmation_required"}""").jsonObject
    private val serialization=RelationSerializationTestAdapters.rules
    private val rules=CashRelationPlanCacheRules(cashRelationCore,serialization,PlatformRefundTestAdapters::absolute,
        RelationPipelineTestAdapters::difference,::truthy,
        {value ->when(value) {is Boolean ->if(value) 1 else 0;is Number ->value.toInt();else ->value.toString().toInt()}})
    private fun truthy(value:Any?):Boolean=when(value) {
        null ->false;is Boolean ->value;is String ->value.isNotEmpty();is Number ->value.toDouble()!=0.0
        is Collection<*> ->value.isNotEmpty();is Map<*,*> ->value.isNotEmpty();else ->true
    }
    private fun encode(value:Any?)=sourceJson(value,{null},::pythonFloatText)
    private fun fact(id: String = "p1", amount: String = "-100.00", recordId: String = "R1",
        source: String = "alipay", recordType: String = "consumption",
        occurred: String = "2026-10-01T10:00:00+00:00") = RelationFact(id = id, amount = CashAmount.parse(amount),
        currency = "CNY", accountId = "a", accountName = "账户", occurredAt = occurred, counterparty = "商户",
        recordType = recordType, recordSubtype = defaultCashRecordSubtype(recordType), billSource = source,
        source = source, recordId = recordId)
    private fun proposal() = RelationProposal(kind = "refund_offset", primaryFactId = "p1", secondaryFactId = "q1",
        status = "accepted", ruleId = "scan.refund", anchorFactId = "p1",
        evidence = RelationEvidence(amountDelta = "0", timeDeltaSeconds = 5, signals = listOf("refund"),
            candidateFactIds = listOf("q1"), extras = linkedMapOf("refund_amount" to "30.00")))
    private fun proposalJson(proposals:List<RelationProposal>,facts:List<RelationFact<CashAmount>>)=JsonArray(proposals.map {
        JsonObject(mapOf("key" to JsonPrimitive(serialization.proposalKey(it,facts)),"body" to it.toJsonValue(::encode)))})

    @Test fun cachedRelationPlanMatchesPython() {
        val refund = fact(id = "q1", amount = "30.00", recordId = "R2", source = "wechat",
            recordType = "refund", occurred = "2026-10-02T10:00:00+00:00")
        val facts = listOf(fact(), refund)
        val openLeg = proposal().copy(kind = "transfer_pair", primaryFactId = "q1", secondaryFactId = null,
            status = "pending_review", ruleId = "scan.open", anchorFactId = "",
            evidence = RelationEvidence(openLeg = true, candidateFactIds = listOf("p1")))
        val pending = proposal().copy(status = "pending_review", confidence = "weak",
            evidence = RelationEvidence(amountDelta = "0.00", timeDeltaSeconds = null,
                candidateFactIds = listOf("q1", "p1"), extras = linkedMapOf("refund_amount" to "30.00")))
        val plan = RelationPlan(facts, listOf(proposal(), openLeg, pending), "d".repeat(64), "e".repeat(64))
        val cached = serialization.serialize(plan)
        assertEquals(golden.getValue("cached"), encode(serialization.serialize(plan,::SourceJsonInteger)))

        val decoded = rules.decodeCachedProposals(facts, cached)
        assertEquals(golden.getValue("decoded"), proposalJson(decoded,facts))
        assertEquals(golden.getValue("decoded_facts"), encode(facts.map { it.id }))
        assertEquals(golden.getValue("by_reference"),
            encode(rules.factsByCachedReference(facts).keys.sorted()))
        assertEquals(golden.getValue("resolve_ref"), encode(listOf("p1", "preview:p1", "alipay:R1", "R2", "missing")
            .map { rules.resolveFactReference(it, facts) }))
        assertEquals(golden.getValue("seeds"), encode(linkedMapOf(
            "expanded" to rules.resolveSeeds(facts, listOf("p1", "unknown", "q1")),
            "all" to rules.resolveSeeds(facts, null))))

        val aliases = listOf(
            mapOf("alias_type" to "card_tail", "alias_value" to "1234", "account_id" to "a"),
            mapOf("alias_type" to "card_tail", "alias_value" to "12-34", "account_id" to "b"),
            mapOf("alias_type" to "card_tail", "alias_value" to "12345", "account_id" to "c"),
            mapOf("alias_type" to "account_identifier", "alias_value" to "622202123456", "account_id" to "a"),
            mapOf("alias_type" to "account_identifier", "alias_value" to "1234", "account_id" to "d"))
        val (tails, identifiers) = rules.aliasIndexes(aliases)
        assertEquals(golden.getValue("aliases"), encode(linkedMapOf(
            "card_tails" to tails.mapValues { it.value.sorted() },
            "account_identifiers" to identifiers.mapValues { it.value.sorted() })))

        val rows = listOf<Map<String, Any?>>(
            linkedMapOf("id" to "p1", "record_id" to "R1", "amount" to "-100.00", "currency" to "CNY",
                "record_type" to "consumption", "cash_granularity" to "aggregate",
                "components" to listOf(linkedMapOf("id" to "c1", "ordinal" to 1, "amount" to "-60.00",
                    "currency" to "CNY", "account_id" to "a"),
                    linkedMapOf("id" to "c2", "ordinal" to 2, "amount" to "-40.00", "currency" to "CNY",
                        "account_id" to "b"))),
            linkedMapOf("id" to "q1", "record_id" to "R2", "amount" to "30.00", "currency" to "CNY",
                "record_type" to "refund", "components" to listOf(linkedMapOf("id" to "c3", "ordinal" to 1,
                    "amount" to "30.00", "currency" to "CNY", "account_id" to "a"))),
            linkedMapOf("id" to "s1", "record_id" to "R3", "amount" to "-5.00", "currency" to "CNY",
                "record_type" to "consumption"))
        val expanded = rules.expandComponentRows(rows).map { item ->
            linkedMapOf<String, Any?>().apply {
                for (key in listOf("id", "parent_id", "component_ordinal", "amount", "account_id",
                    "cash_granularity", "currency", "record_id")) put(key, item[key])
            }
        }
        val expectedExpanded:List<Map<String,Any?>> =golden.getValue("expanded").jsonArray.map {entry ->entry.jsonObject.mapValues {(key,value) ->
            if(value is JsonNull) null else if(key=="component_ordinal") value.jsonPrimitive.int else value.jsonPrimitive.content
        }}
        assertEquals(expectedExpanded,expanded)

        val relations = listOf<Map<String, Any?>>(
            linkedMapOf("id" to 1, "kind" to "refund_offset", "status" to "accepted", "primary_fact_id" to "p1",
                "secondary_fact_id" to "q1", "applied_amount" to "10.00", "created_by" to "system", "decided_by" to ""),
            linkedMapOf("id" to 2, "kind" to "refund_offset", "status" to "accepted", "primary_fact_id" to "s1",
                "secondary_fact_id" to "missing", "created_by" to "system", "decided_by" to ""),
            linkedMapOf("id" to 3, "kind" to "payment_mirror", "status" to "accepted", "primary_fact_id" to "p1",
                "secondary_fact_id" to "s1", "created_by" to "system", "decided_by" to ""))
        assertEquals(golden.getValue("remaining"), encode(rules.refundRemaining(facts, relations)
            .mapValues { cashAbsolutePlainText(it.value) }))

        val decision = linkedMapOf<String, Any?>("proposal_key" to
            serialization.proposalKey(proposal(), facts),
            "primary_record_ref" to "alipay:R1", "secondary_record_ref" to "R2")
        val decisions = linkedMapOf<String, Any?>(
            "mirror" to (rules.decisionMirrorEndpoints(
                proposal().copy(kind = "payment_mirror"), decision, facts)?.toList()),
            "matches" to rules.decisionMatches(proposal(), decision, facts),
            "primary" to rules.decisionPrimaryMatches(proposal(), decision, facts),
            "wrong_key" to rules.decisionMatches(proposal(),
                decision + ("proposal_key" to "proposal:bad"), facts),
            "candidate" to rules.decisionMatches(proposal(),
                decision + ("secondary_record_ref" to "R1"), facts))
        assertEquals(golden.getValue("decisions"), encode(decisions))

        val validation=RelationValidationTestAdapters.rules
        val acceptedReason=runCatching {validation.validateCachedPlanLegality(facts,
            listOf(mapOf("kind" to "refund_offset","status" to "accepted","primary_fact_id" to "p1",
                "secondary_fact_id" to "q1","applied_amount" to "10.00")),listOf(proposal()))}.exceptionOrNull()
        val pendingReason=runCatching {validation.validateCachedPlanLegality(facts,emptyList(),
            listOf(proposal().copy(status="pending_review",evidence=RelationEvidence(extras=linkedMapOf("refund_amount" to "130.00")))))}.exceptionOrNull()
        assertEquals(golden.getValue("legality"),encode(listOf(acceptedReason?.message,pendingReason?.message)))

        assertEquals(golden.getValue("decode_error").jsonPrimitive.content,
            assertFailsWith<IllegalArgumentException> {
                rules.decodeCachedProposals(facts, cached + ("version" to 2))
            }.message)
        @Suppress("UNCHECKED_CAST")
        val broken = LinkedHashMap(cached)
        val proposals = (cached["proposals"] as List<Map<String, Any?>>).map { LinkedHashMap(it) }.toMutableList()
        proposals[0]["proposal_key"] = "proposal:bad"
        broken["proposals"] = proposals
        assertEquals(golden.getValue("key_error").jsonPrimitive.content,
            assertFailsWith<IllegalArgumentException> {
                rules.decodeCachedProposals(facts, broken)
            }.message)
    }
    @Test fun stableDecisionsSurvivePreviewIdsButAmbiguityRequiresReconfirmation() {
        val facts=listOf(fact(),fact(id="q1",amount="30.00",recordId="R2",source="wechat",recordType="refund"))
        val plan=RelationPlan(facts,listOf(proposal()),"d".repeat(64),"e".repeat(64))
        val cached=serialization.serialize(plan)
        val renamed=facts.map {it.copy(id="preview:new-${it.id}")}
        val decoded=rules.decodeCachedProposals(renamed,cached)
        assertEquals("preview:new-p1",decoded.single().primaryFactId)
        assertEquals("preview:new-q1",decoded.single().secondaryFactId)
        assertEquals(decoded,rules.decodeCachedProposals(renamed,cached+("version" to true)))
        assertEquals("import_relation_reconfirmation_required",assertFailsWith<IllegalArgumentException> {
            rules.factsByCachedReference(facts+facts[0].copy(id="duplicate"))
        }.message)
        assertEquals(listOf("c1","c2","missing"),rules.resolveSeeds(listOf(
            facts[0].copy(id="c1",parentId="parent"),facts[0].copy(id="c2",parentId="parent")),listOf("parent","c1","missing")))
    }

    @Test fun refundRemainingUsesFullRefundWhileInitialRemainingUsesAppliedAmount() {
        val facts=listOf(fact(amount="-10.00"),fact(id="q1",amount="30.00",recordType="refund"))
        val relations=listOf(mapOf("kind" to "refund_offset","status" to "accepted",
            "primary_fact_id" to "p1","secondary_fact_id" to "q1","applied_amount" to "1.00"))
        assertEquals(CashAmount.parse("-20.00"),rules.refundRemaining(facts,relations)["p1"])
        assertEquals(CashAmount.parse("9.00"),serialization.initialRemaining(facts,relations)["p1"])
        val row=mapOf("id" to "parent","amount" to "-10.00","components" to listOf(
            mapOf("id" to "c1","amount" to "-4.00"),mapOf("id" to "c2","amount" to "-6.00","ordinal" to null)))
        val expanded=rules.expandComponentRows(listOf(row))
        assertEquals(0,expanded[0]["component_ordinal"])
        assertNull(expanded[1]["component_ordinal"])
        assertFalse("parent_id" in row)
    }

}
