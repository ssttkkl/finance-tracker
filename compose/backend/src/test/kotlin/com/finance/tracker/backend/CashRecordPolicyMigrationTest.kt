package com.finance.tracker.backend

import java.time.Instant
import kotlin.test.*

class CashRecordPolicyMigrationTest {
    private fun record(vararg changes: Pair<String,Any?>): Map<String,Any?> = linkedMapOf<String,Any?>(
        "state" to "posted", "amount" to CashAmount.parse("-20"), "currency" to "CNY",
        "occurred_at" to Instant.parse("2026-08-01T00:00:00Z"), "account_id" to "41",
        "record_id" to "source-1", "source_type" to "wechat", "source_scope_key" to "source-owner",
        "cash_granularity" to "atomic", "components" to listOf(part()), "counterparty" to "商户",
        "counterparty_account" to "1234", "counterparty_account_attrs" to listOf("tail"), "note" to "来源备注",
        "category_id" to null, "record_type" to "consumption", "record_subtype" to "not_applicable",
        "source_payload" to mapOf("备注" to "来源备注")) + changes
    private fun part(vararg changes: Pair<String,Any?>): Map<String,Any?> = linkedMapOf<String,Any?>(
        "component_id" to "501", "account_id" to "41", "amount" to "-20", "currency" to "CNY", "ordinal" to 0) + changes
    private fun allocation(vararg changes: Pair<String,Any?>) = part(*changes)-"component_id"
    private fun error(code:String,fields:List<String>?=null,block:()->Unit) {
        val failure=assertFailsWith<CashPolicyError>(block=block);assertEquals(code,failure.code)
        if(fields!=null) assertEquals(fields,failure.fields)
    }
    @Test fun postedFinancialFieldsCannotChangeOrPartiallyApplyText() {
        for((field,value) in listOf("amount" to "-21","currency" to "USD","occurred_at" to "2026-08-02T00:00:00Z",
            "account_id" to "42","cash_granularity" to "aggregate","components" to listOf(part("amount" to "-21")))) {
            val current=record();val before=record()
            error("fixed_field_conflict",listOf(field)) {CashRecordPolicy.prepare(current,mapOf("counterparty" to "新名称",field to value))}
            assertEquals(before,current)
        }
    }
    @Test fun fixedFieldsAcceptEqualValuesWithoutRewritingThem() {
        val result=CashRecordPolicy.prepare(record(),mapOf("amount" to "-20.000","account_id" to 41,
            "occurred_at" to "2026-08-01T08:00:00+08:00","currency" to "CNY",
            "components" to listOf(part("component_id" to 501,"account_id" to 41,"amount" to CashAmount.parse("-20.0"))),"counterparty" to "新名称"))
        assertEquals(mapOf("counterparty" to "新名称"),result.values);assertFalse(result.typeChanged)
    }
    @Test fun sourceIdentityIsFixedForDraftAndPosted() {
        for(state in listOf("draft","posted")) for(field in listOf("record_id","source_type","source_scope_key"))
            error("fixed_field_conflict") {CashRecordPolicy.prepare(record("state" to state),mapOf(field to "another"))}
    }
    @Test fun patchCannotChangeSystemFields() {
        for(field in listOf("state","row_revision","workspace_id","manual_overrides","unexpected"))
            error("field_not_editable") {CashRecordPolicy.prepare(record(),mapOf(field to "posted"))}
    }
    @Test fun accountAndAttributesAreDirectlyOverwritable() {
        val result=CashRecordPolicy.prepare(record(),mapOf("counterparty_account" to "a different unverified account","counterparty_account_attrs" to listOf("tail","reconstructed")))
        assertEquals("a different unverified account",result.values["counterparty_account"])
        assertEquals(listOf("tail","reconstructed"),result.values["counterparty_account_attrs"]);assertFalse(result.typeChanged)
    }
    @Test fun userClearedNoteAndCategoryCanBeProtectedWhileSourceUpdates() {
        val result=CashRecordPolicy.prepare(record("note" to "","category_id" to "cat-user"),
            mapOf("note" to "新来源","category_id" to null,"source_payload" to mapOf("备注" to "新来源"),"counterparty" to "新商户"),setOf("note","category_id"))
        assertEquals(mapOf("source_payload" to mapOf("备注" to "新来源"),"counterparty" to "新商户"),result.values)
        assertEquals(listOf("category_id","note"),result.protectedFields)
    }
    @Test fun manualChangesCanExplicitlyReplaceProtectedValues() {
        assertEquals(mapOf("note" to "手动备注"),CashRecordPolicy.prepare(record("note" to ""),mapOf("note" to "手动备注")).values)
    }
    @Test fun manualTypeChangeRequiresLegalPair() {
        error("invalid_record_type") {CashRecordPolicy.prepare(record("record_origin" to "manual"),mapOf("record_type" to "transfer_out"))}
        val result=CashRecordPolicy.prepare(record("record_origin" to "manual"),mapOf("record_type" to "transfer_out","record_subtype" to "ordinary_transfer"))
        assertTrue(result.typeChanged);assertEquals(mapOf("record_type" to "transfer_out","record_subtype" to "ordinary_transfer"),result.values)
    }
    @Test fun draftCompletionDoesNotPromoteState() {
        val result=CashRecordPolicy.prepare(record("state" to "draft","amount" to null,"account_id" to null),mapOf("amount" to "-20","account_id" to "41"))
        assertEquals(mapOf("amount" to CashAmount.parse("-20"),"account_id" to "41"),result.values);assertFalse("state" in result.values)
    }
    @Test fun equalDraftFinancialValuesDoNotCreateAnUpdate() {
        assertEquals(emptyMap(),CashRecordPolicy.prepare(record("state" to "draft","amount" to "-20.00","occurred_at" to "2026-08-01T00:00:00Z"),mapOf("amount" to "-20","occurred_at" to "2026-08-01T08:00:00+08:00")).values)
    }
    @Test fun draftAmountsRequireExactlyRepresentableDecimals() {
        for(value in listOf(0.1,true,"NaN","1e-19")) error("invalid_field") {CashRecordPolicy.prepare(record("state" to "draft","amount" to null),mapOf("amount" to value))}
    }
    @Test fun sourcePayloadIsDetachedFromCaller() {
        val nested=mutableListOf("source");val incoming=mapOf("source_payload" to mapOf("nested" to nested))
        val result=CashRecordPolicy.prepare(record(),incoming);nested.add("changed")
        assertEquals(mapOf("nested" to listOf("source")),result.values["source_payload"])
    }
    @Test fun sourceNoteKeepsOnlyFullOriginalSourceText() {
        val original="来源原文".repeat(200)
        assertEquals(original,CashRecordPolicy.sourceNote(mapOf("备注" to original)))
        assertEquals("商品原文",CashRecordPolicy.sourceNote(mapOf("商品说明" to "商品原文")))
        assertEquals("",CashRecordPolicy.sourceNote(mapOf("交易对方" to "商户","交易类型" to "支付")))
        assertEquals("来源描述",CashRecordPolicy.sourceNote(mapOf("备注" to "","描述" to "来源描述")))
        assertEquals("原文備註",CashRecordPolicy.sourceNote(mapOf("備註" to "原文備註")))
    }
    @Test fun ccbRawLocationPrecedesClassificationSummary() {
        val original="  原始交易地点／附言\n"+"原文".repeat(120)+"  "
        assertEquals(original,CashRecordPolicy.sourceNote(mapOf("摘要" to "消费","交易地点/附言" to original)))
        assertEquals("其他原备注",CashRecordPolicy.sourceNote(mapOf("备注" to "其他原备注","交易地点/附言" to original)))
        assertEquals("转账",CashRecordPolicy.sourceNote(mapOf("摘要" to "转账","交易地点/附言" to "  ")))
    }
    @Test fun postingRejectsOffsettingComponentsEvenWhenTotalsBalance() {
        for((total,amounts) in listOf("-20" to listOf("-30","10"),"20" to listOf("30","-10"),"0" to listOf("-10","10"))) {
            val current=record("state" to "draft","amount" to total,"account_id" to null,"cash_granularity" to "aggregate",
                "components" to amounts.mapIndexed {index,value ->allocation("ordinal" to index,"account_id" to (41+index).toString(),"amount" to value)})
            error("component_direction_mismatch") {CashRecordPolicy.validateForPosting(current)};assertEquals("draft",current["state"])
        }
    }
    @Test fun sourcePayloadMustBeJsonCompatible() {
        for(value in listOf(mapOf("x" to setOf(1,2)),mapOf("x" to Double.NaN),mapOf(1 to "value")))
            error("invalid_field") {CashRecordPolicy.prepare(record(),mapOf("source_payload" to value))}
    }
    @Test fun incompleteDraftStillValidatesEverySuppliedStructure() {
        for(patch in listOf(mapOf("account_id" to emptyList<Any>()),mapOf("cash_granularity" to emptyMap<String,Any>()),mapOf("currency" to "Y"),
            mapOf("components" to listOf(mapOf("ordinal" to -1))),mapOf("components" to listOf(mapOf("ordinal" to true))),mapOf(1 to "bad","x" to "bad")))
            error("invalid_field") {CashRecordPolicy.prepare(record("state" to "draft"),patch)}
    }
    @Test fun componentOrdinalFitsBothDatabaseIntegerColumns() {
        error("invalid_field") {CashRecordPolicy.prepare(record("state" to "draft"),mapOf("components" to listOf(part("ordinal" to 2147483648L))))}
        val result=CashRecordPolicy.prepare(record("state" to "draft"),mapOf("components" to listOf(part("ordinal" to Int.MAX_VALUE))))
        assertEquals(Int.MAX_VALUE,(result.values.getValue("components") as List<*>).single().let {it as Map<*,*>}["ordinal"])
    }
    @Test fun postingRequiresCompleteParentAndBalancedComponents() {
        CashRecordPolicy.validateForPosting(record())
        for(field in listOf("amount","occurred_at","currency","record_type","record_subtype","account_id"))
            error("incomplete_record") {CashRecordPolicy.validateForPosting(record(field to null))}
        val combined=record("account_id" to null,"cash_granularity" to "aggregate","amount" to "-100","components" to listOf(allocation("account_id" to "41","amount" to "-30","ordinal" to 0),allocation("account_id" to "42","amount" to "-70","ordinal" to 1)))
        CashRecordPolicy.validateForPosting(combined)
        error("component_total_mismatch") {CashRecordPolicy.validateForPosting(combined+("components" to listOf(allocation("account_id" to "41","amount" to "-30","ordinal" to 0),allocation("account_id" to "42","amount" to "-69","ordinal" to 1))))}
    }
    @Test fun postingRejectsMissingAndInconsistentComponentFields() {
        for(components in listOf(emptyList(),listOf(allocation("account_id" to null)),listOf(allocation("amount" to null)),listOf(allocation("currency" to "USD")),listOf(allocation("account_id" to "42"))))
            assertFailsWith<CashPolicyError> {CashRecordPolicy.validateForPosting(record("components" to components))}
    }
    @Test fun validationDoesNotPromoteDraft() {val current=record("state" to "draft");CashRecordPolicy.validateForPosting(current);assertEquals("draft",current["state"])}
    @Test fun componentTotalsUseFullSupportedPrecision() {
        CashRecordPolicy.validateForPosting(record("account_id" to null,"cash_granularity" to "aggregate","amount" to "-12345678901234567890.123456789012345678","components" to listOf(
            allocation("account_id" to "41","amount" to "-10000000000000000000","ordinal" to 0),allocation("account_id" to "42","amount" to "-2345678901234567890.123456789012345678","ordinal" to 1))))
    }
    @Test fun postingRequiresPresentUniqueOrdinals() {
        for(first in listOf(null,0)) {
            val one=allocation("account_id" to "41","amount" to "-10").toMutableMap().also {it.remove("ordinal");if(first!=null) it["ordinal"]=first}
            assertFailsWith<CashPolicyError> {CashRecordPolicy.validateForPosting(record("account_id" to null,"cash_granularity" to "aggregate","amount" to "-20","components" to listOf(one,allocation("account_id" to "42","amount" to "-10","ordinal" to if(first==null) 1 else 0))))}
        }
    }
    @Test fun aggregatePostingRequiresEmptyParentAccount() {
        for(account in listOf("41",emptyList<Any>())) assertFailsWith<CashPolicyError> {CashRecordPolicy.validateForPosting(record("account_id" to account,"cash_granularity" to "aggregate","amount" to "-20","components" to listOf(allocation("account_id" to "41","amount" to "-10","ordinal" to 0),allocation("account_id" to "42","amount" to "-10","ordinal" to 1))))}
    }
    @Test fun normalizedUpdateRetainsExplicitNulls() {
        val result=CashRecordPolicy.prepare(mapOf("state" to "draft"),mapOf("category_id" to null,"note" to "","amount" to "-1.00"))
        assertEquals(mapOf("category_id" to null,"note" to "","amount" to CashAmount.parse("-1.00")),result.normalizedValues)
        assertFalse("category_id" in result.values)
    }
    @Test fun componentNormalizationPreservesRepresentationSharedReferencesAndIsolation() {
        for(nested in listOf(false,true)) {
            val original=CashAmount.parse("-1.2300");val source=linkedMapOf<String,Any?>("component_id" to "11","account_id" to 41,"amount" to original,"currency" to "CNY","ordinal" to 0,"optional" to null,"flag" to true)
            val labels=mutableListOf("original");val shared=mapOf("labels" to labels)
            if(nested) {source["first"]=shared;source["second"]=shared}
            val copied=CashRecordPolicy.componentValues(listOf(source)).single()
            assertNotSame(source,copied);assertEquals("41",copied["account_id"]);assertEquals(41,source["account_id"])
            assertEquals(original.representation,(copied["amount"] as CashAmount).representation)
            copied["ordinal"]=2;assertEquals(0,source["ordinal"])
            if(nested) {assertSame(copied["first"],copied["second"]);@Suppress("UNCHECKED_CAST") val changed=(copied["first"] as Map<String,Any?>)["labels"] as MutableList<String>;changed.add("changed");assertEquals(listOf("original"),labels)}
        }
    }
    @Test fun acceptedZeroExtremeExponentDoesNotEnterJvmBoundedDecimalConversion() {
        val value=CashAmount.parse("-0e999999999999999999")
        val result=CashRecordPolicy.prepare(mapOf("state" to "draft"),mapOf("amount" to value))
        assertEquals(value.representation,(result.values.getValue("amount") as CashAmount).representation)
        assertTrue(CashRecordPolicy.prepare(mapOf("state" to "draft","amount" to CashAmount.parse("0")),mapOf("amount" to value)).values.isEmpty())
    }
    @Test fun separateNonfiniteComponentMetadataDoesNotBecomeEqualByJvmNumberEquality() {
        val current=record("components" to listOf(part("extra" to "NaN".toDouble())))
        error("fixed_field_conflict",listOf("components")) {CashRecordPolicy.prepare(current,mapOf("components" to listOf(part("extra" to "NaN".toDouble()))))}
    }
    @Test fun componentMetadataMapKeysAndSetsUsePythonNumericAndIdentityEquality() {
        fun unchanged(left: Any,right: Any) = assertTrue(CashRecordPolicy.prepare(
            record("components" to listOf(part("extra" to left))),
            mapOf("components" to listOf(part("extra" to right)))).values.isEmpty())
        unchanged(mapOf(1 to "x"),mapOf(true to "x"))
        unchanged(setOf(1),setOf(true))
        val nan: Any="NaN".toDouble()
        unchanged(setOf(nan),setOf(nan))
        unchanged(mapOf(nan to "x"),mapOf(nan to "x"))
        for((left,right) in listOf(setOf("NaN".toDouble()) to setOf("NaN".toDouble()),
            mapOf("NaN".toDouble() to "x") to mapOf("NaN".toDouble() to "x")))
            error("fixed_field_conflict",listOf("components")) {CashRecordPolicy.prepare(
                record("components" to listOf(part("extra" to left))),mapOf("components" to listOf(part("extra" to right))))}
    }
    @Test fun cashTimeRejectsGlobalZReplacementAmbiguityAndResolvesWeekDateBoundaries() {
        val rejected="2026-08-01Z12:00:00+00:00"
        error("invalid_field",listOf("occurred_at")) {CashRecordPolicy.prepare(record("state" to "draft"),mapOf("occurred_at" to rejected))}
        error("invalid_field",listOf("occurred_at")) {CashRecordPolicy.validateForPosting(record("occurred_at" to rejected))}
        for((text,expected) in listOf("2026-W01-1112Z" to "2025-12-29T11:12:00Z",
            "2026-W01-1212Z" to "2025-12-29T12:12:00Z","2026-W01-12Z" to "2025-12-29T12:00:00Z",
            "2026-08-01T12:00:00Z" to "2026-08-01T12:00:00Z")) {
            val result=CashRecordPolicy.prepare(record("state" to "draft"),mapOf("occurred_at" to text))
            assertEquals(Instant.parse(expected),result.values["occurred_at"])
        }
    }

}
