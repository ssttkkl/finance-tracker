package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.ACCOUNT_ALIASES
import com.finance.tracker.business.domain.CashImportRelationDiscoveryRules

/** 将导入预计现金部分交给既有关系管线；提议在完整组校验前只存在于内存。 */
internal class CashImportRelationDiscovery(private val context: CashSqlContext) {
    companion object {
        fun originalExpense(value: Map<String, Any?>): Boolean = com.finance.tracker.business.domain.cashImportOriginalExpense(
            value, { it.arithmeticValue().signum() < 0 }, CashPlatformRefund::wechatIsRefundOriginExpense)
    }

    private val rules = CashImportRelationDiscoveryRules(context::requireActive, CashRecordPolicy::copyValue,
        ::originalExpense, { value -> cashRelationDecimal(value)!!.arithmeticValue() }, { it.toString().toInt() },
        CashRelationPipeline::needsRelationDiscovery,
        { facts, seeds, accepted, tails, identifiers, workspace ->
            CashRelationPlanning.planRelationProposals(facts, seedIds = seeds, acceptedRelations = accepted,
                aliasesByTail = tails, accountIdentifiers = identifiers, workspaceId = workspace, requireUniqueMirrors = true).proposals
        })

    fun matchingFacts(snapshot: CashImportPreviewSnapshot, accounts: Map<String, Map<String, Any?>>): List<RelationFact> =
        rules.matchingFacts(snapshot, accounts)

    fun discover(snapshot: CashImportPreviewSnapshot, seedIds: Set<String>, accounts: Map<String, Map<String, Any?>>): List<RelationProposal> =
        rules.discover(snapshot, seedIds, accounts, context.workspace) {
            val table = ACCOUNT_ALIASES
            context.sql.select(table.ALIAS_TYPE, table.ALIAS_VALUE, table.ACCOUNT_ID).from(table)
                .where(table.WORKSPACE_ID.eq(context.workspace)).fetch().map { row -> mapOf(
                    "alias_type" to row[table.ALIAS_TYPE], "alias_value" to row[table.ALIAS_VALUE],
                    "account_id" to row[table.ACCOUNT_ID]!!.toString()) }
        }
}
