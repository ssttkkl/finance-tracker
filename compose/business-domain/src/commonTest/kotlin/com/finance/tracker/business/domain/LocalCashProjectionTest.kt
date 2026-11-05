package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashProjectionTest {
    private fun fact(id: String, amount: String?) = LedgerCashTransactionFactDto(
        id = id, accountId = "10", state = "posted", recordOrigin = "legacy", manualTransferId = null,
        rowRevision = "1", cashGranularity = "atomic", sourceType = null, recordId = "record:$id",
        sourcePayload = JsonNull, relationMetadata = JsonNull, sourceFingerprint = null,
        manualOverrides = JsonArray(emptyList()), occurredAt = "2026-08-12T03:04:05.123456Z", amount = amount,
        currency = "CNY", counterparty = "商户", counterpartyAccount = "", counterpartyAccountAttrs = JsonArray(emptyList()),
        note = "", categoryId = null, recordType = "consumption", recordSubtype = "not_applicable",
        createdAt = "2026-08-12T03:04:05.123456Z", deletedAt = null, deletedBy = "", deleteReason = "")
    private fun snapshot(transactions:List<LedgerCashTransactionFactDto>):LedgerSnapshotDto {
        val empty = LedgerFactsDto(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0")
        return LedgerSnapshotDto("w", "9007199254740993", CASH_RULES_VERSION, empty.copy(cashTransactions=transactions))
    }
    private fun entry(id:String,root:String)=LedgerCashEntryFactDto(id,root,root,"9007199254740993","public-category",
        "人工备注","公共对方","",JsonArray(emptyList()),JsonArray(listOf(JsonPrimitive("note"))),null)
    @Test fun ledgerEntryKeepsStoredIdentityPublicFieldsAndOriginalSourceValues() {
        val source=fact("9007199254740993","-12.340000000000000001")
        val entry=entry("stable-entry-id",source.id)
        val base=snapshot(listOf(source))
        val input=base.copy(facts=base.facts.copy(cashEntries=listOf(entry),cashEntryMembers=listOf(
            LedgerCashEntryMemberFactDto(source.id,entry.id,JsonObject(emptyMap()),JsonArray(emptyList())))))
        val row=buildLocalCashLedgerEntries(input).single()
        assertEquals(entry,row.entry);assertEquals("cash-entry:stable-entry-id",row.projectionId)
        assertEquals(source.id,row.transactionId)
        assertEquals("商户",row.projection.primaryRecord.counterparty);assertEquals("",row.projection.primaryRecord.note)
        assertNull(row.projection.primaryRecord.categoryId)
        assertEquals(CashAmount.parse(source.amount!!),row.projection.netAmount)
        assertEquals(listOf(source),input.facts.cashTransactions)
    }
    @Test fun completeLedgerReadModelKeepsPublicFieldsCategoryPathAndExactVersion() {
        val source=fact("9007199254740993","-12.340000000000000001")
        val entry=entry("stable-entry-id",source.id)
        val base=snapshot(listOf(source))
        fun category(id:String,parent:String?,name:String)=LedgerCategoryFactDto(id,parent,parent ?: "",name,name,null,
            "/$id/",1,"0","1","2026-08-12T00:00:00Z","2026-08-12T00:00:00Z")
        val input=base.copy(facts=base.facts.copy(
            accounts=listOf(LedgerAccountFactDto("10","Cash","cash",true,JsonArray(listOf(JsonPrimitive("CNY"))),"1")),
            cashCategories=listOf(category("root",null,"Root"),category("public-category","root","Leaf")),
            cashEntries=listOf(entry),cashEntryMembers=listOf(LedgerCashEntryMemberFactDto(source.id,entry.id,JsonObject(emptyMap()),JsonArray(emptyList())))))
        val row=buildLocalCashProjectionViews(input).single()
        assertEquals(CashProjectionDto("cash-entry:stable-entry-id","2026-08-12T03:04:05.123456+00:00",
            account=AccountReferenceDto(10,"Cash","cash"),counterparty="公共对方",note="人工备注",
            category=CashCategoryDto("public-category",name="Leaf",path=listOf(CashCategoryPathItemDto("root","Root"),CashCategoryPathItemDto("public-category","Leaf"))),
            amount="-12.340000000000000001",currency="CNY",economicType="expense",recordId=source.recordId,
            projectionVersion=9007199254740993L,entryId=entry.id,transactionId=source.id,recordType="consumption",recordSubtype="not_applicable"),row)
        assertEquals(listOf(source),input.facts.cashTransactions)
    }
    @Test fun fundingReadViewUsesOnlyLinkedMinimalInvestmentFieldsInBothDirections() {
        for(incoming in listOf(true,false)) {
            val source=fact("9007199254740993",if(incoming)"-12.340000000000000001" else "12.340000000000000001")
                .copy(recordType=if(incoming)"transfer_out" else "transfer_in",recordSubtype="ordinary_transfer")
            val entry=entry("funding-entry",source.id)
            val base=snapshot(listOf(source))
            val investment=LedgerFundingEventFactDto("20","USD",if(incoming)null else "-2.340000000000000001",if(incoming)"2.340000000000000001" else null)
            val link=LedgerInvestmentFundingFactDto("3","2","4",if(incoming)"cash_to_investment" else "investment_to_cash",
                "accepted","fixture",JsonObject(emptyMap()),"active","system",source.createdAt,"user",source.createdAt,"",investment)
            val input=base.copy(facts=base.facts.copy(
                accounts=listOf(LedgerAccountFactDto("10","Cash","cash",true,JsonArray(emptyList()),"1"),
                    LedgerAccountFactDto("20","Investment","security",true,JsonArray(emptyList()),"1")),
                cashTransactionComponents=listOf(LedgerCashComponentFactDto("2",source.id,"10",source.amount,"CNY","0","","",JsonObject(emptyMap()),source.createdAt)),
                cashInvestmentFundingRelations=listOf(link),cashEntries=listOf(entry),
                cashEntryMembers=listOf(LedgerCashEntryMemberFactDto(source.id,entry.id,JsonObject(emptyMap()),JsonArray(emptyList())))))
            val row=buildLocalCashProjectionViews(input).single()
            val cash=AccountReferenceDto(10,"Cash","cash");val security=AccountReferenceDto(20,"Investment","security")
            assertEquals("bank_security_transfer",row.businessLabel)
            assertEquals(if(incoming)CashTransferDto(cash,source.amount!!,"CNY",security,"2.340000000000000001","USD")
                else CashTransferDto(security,"-2.340000000000000001","USD",cash,source.amount!!,"CNY"),row.transfer)
            assertNull(buildLocalCashProjectionViews(input.copy(facts=input.facts.copy(
                cashInvestmentFundingRelations=listOf(link.copy(investmentEvent=null))))).single().transfer)
            assertNull(buildLocalCashProjectionViews(input.copy(facts=input.facts.copy(
                cashInvestmentFundingRelations=listOf(link.copy(investmentEvent=investment.copy(fromAmount=null,toAmount=null)))))).single().transfer)
        }
    }
    @Test fun missingAndInconsistentEntryReferencesDoNotFabricatePublicIdentity() {
        val base=snapshot(listOf(fact("1","-10")))
        fun rejects(input:LedgerSnapshotDto) =assertEquals("projection.incomplete",assertFailsWith<CashProjectionDomainFailure> {
            buildLocalCashLedgerEntries(input)
        }.code)
        rejects(base)
        val member=LedgerCashEntryMemberFactDto("1","entry",JsonObject(emptyMap()),JsonArray(emptyList()))
        rejects(base.copy(facts=base.facts.copy(cashEntryMembers=listOf(member))))
        rejects(base.copy(facts=base.facts.copy(cashTransactions=listOf(fact("1","-10"),fact("2","-20")),
            cashEntries=listOf(entry("entry","1")),cashEntryMembers=listOf(member,member.copy(transactionId="2")))))
    }
    @Test fun completeSnapshotPreservesLargeIdsDraftsAndHiddenAdjustmentsWithoutGraphComponents() {
        val transactions = listOf(fact("9007199254740993", "-12.340000000000000001"), fact("2", "0"),
            fact("3", null).copy(state = "draft", occurredAt = null, currency = null),
            fact("4", "25").copy(deletedAt = "2026-08-13T00:00:00Z"))
        val snapshot = snapshot(transactions)
        val result = buildLocalCashProjections(snapshot)
        assertEquals(setOf(9007199254740993L, 2L, 3L), result.memberIds)
        val expense = result.projections.single { it.primaryRecord.id == 9007199254740993L }
        assertEquals(CashAmount.parse("-12.340000000000000001").representation, expense.netAmount!!.representation)
        assertEquals(CashEconomicType.EXPENSE, expense.economicType)
        assertEquals("2026-08-12T03:04:05.123456Z", expense.sortAt.toString())
        val zero = result.projections.single { it.primaryRecord.id == 2L }
        assertFalse(zero.visible)
        assertEquals("balance_adjustment", zero.hiddenReason)
        val draft = result.projections.single { it.primaryRecord.id == 3L }
        assertEquals("draft", draft.state)
        assertFalse(draft.statisticsEligible)
        assertEquals("pending", draft.confirmationStatus)
        assertEquals(transactions, snapshot.facts.cashTransactions)
        val selected=result.projections.filter {it.visible || it.attentionCount>0}
            .sortedWith(compareByDescending<CashProjection<Long,kotlin.time.Instant>> {it.sortAt}.thenByDescending {it.projectionId})
        val monthly=summarizeLocalCashProjections(selected) {it.toString().take(7)}
        assertEquals(2L,monthly.total);assertEquals(1L,monthly.pending)
        assertEquals(Json.parseToJsonElement("""[{"month":"2026-08","currencies":[{"currency":"CNY","income":"0","expense":"-12.340000000000000001"}]}]"""),monthly.summaries)
        assertEquals(Json.parseToJsonElement("""[{"month":"2026-08","start_index":0,"end_index":1},{"month":"unknown","start_index":1,"end_index":2}]"""),monthly.positions)
        fun binding(id: String, kind: String, scope: String) = LedgerSourceBindingFactDto(
            id, "9007199254740993", "alipay", scope, "owner", kind, "identity:$id", "strong", null,
            "v1", JsonNull, "1", "2026-08-12T00:00:00Z", "2026-08-12T00:00:00Z")
        val withBindings = snapshot.copy(facts = snapshot.facts.copy(cashSourceBindings = listOf(
            binding("1", "document_fingerprint", "account"), binding("2", "provider_id", "document"),
            binding("3", "provider_id", "account"))))
        assertEquals(listOf("alipay", "account", "owner", "provider_id", "identity:3"),
            buildLocalCashProjections(withBindings).projections.single { it.primaryRecord.id == 9007199254740993L }.primaryRecord.stableSourceKey)
    }
}
