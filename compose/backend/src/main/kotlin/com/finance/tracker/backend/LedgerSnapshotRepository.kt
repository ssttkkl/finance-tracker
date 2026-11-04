package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.*
import java.sql.Connection
import kotlinx.serialization.json.JsonNull

internal class LedgerSnapshotRepository(connection: Connection, private val workspace: String, sqlite: Boolean) {
    private val sql = connection.jooq(sqlite)
    fun facts(): LedgerFactsDto = LedgerFactsDto(
        accounts = accounts(),
        accountAliases = accountAliases(),
        statementAccountMappings = statementAccountMappings(),
        cashCategories = cashCategories(),
        cashTransactions = cashTransactions(),
        cashTransactionComponents = cashTransactionComponents(),
        cashSourceBindings = cashSourceBindings(),
        cashSourceEmptyPages = cashSourceEmptyPages(),
        transactionRelations = transactionRelations(),
        cashRelationCandidates = cashRelationCandidates(),
        cashTransactionIssues = cashTransactionIssues(),
        cashIssueCandidates = cashIssueCandidates(),
        cashEntries = cashEntries(),
        cashEntryMembers = cashEntryMembers(),
        cashInvestmentFundingRelations = cashInvestmentFundingRelations(),
        categoryDirectoryRevision = sql.select(CASH_CATEGORY_STATES.REVISION).from(CASH_CATEGORY_STATES)
            .where(CASH_CATEGORY_STATES.WORKSPACE_ID.eq(workspace)).fetchOne(CASH_CATEGORY_STATES.REVISION)?.toString() ?: "0",
    )

    private fun accounts(): List<LedgerAccountFactDto> {
        val table = ACCOUNTS
        return sql.select(listOf(
            table.ID, table.NAME, table.TYPE, table.ACTIVE,
            table.CURRENCIES, table.REVISION,
        )).from(table).where(table.WORKSPACE_ID.eq(workspace))
            .orderBy(table.ID).fetch { row -> LedgerAccountFactDto(
                id = row[table.ID]!!.toString(),
                name = row[table.NAME]!!,
                type = row[table.TYPE]!!,
                active = row[table.ACTIVE]!!,
                currencies = row[table.CURRENCIES] ?: JsonNull,
                revision = row[table.REVISION]!!.toString(),
            ) }
    }

    private fun accountAliases(): List<LedgerAccountAliasFactDto> {
        val table = ACCOUNT_ALIASES
        return sql.select(listOf(
            table.ID, table.ALIAS_TYPE, table.ALIAS_VALUE, table.ACCOUNT_ID,
            table.CREATED_AT, table.UPDATED_AT,
        )).from(table).where(table.WORKSPACE_ID.eq(workspace))
            .orderBy(table.ID).fetch { row -> LedgerAccountAliasFactDto(
                id = row[table.ID]!!.toString(),
                aliasType = row[table.ALIAS_TYPE]!!,
                aliasValue = row[table.ALIAS_VALUE]!!,
                accountId = row[table.ACCOUNT_ID]!!.toString(),
                createdAt = row[table.CREATED_AT]!!.let(::manualInstantText),
                updatedAt = row[table.UPDATED_AT]!!.let(::manualInstantText),
            ) }
    }

    private fun statementAccountMappings(): List<LedgerStatementMappingFactDto> {
        val table = STATEMENT_ACCOUNT_MAPPINGS
        return sql.select(listOf(
            table.ID, table.SOURCE_TYPE, table.IDENTITY_KIND, table.SOURCE_ACCOUNT_KEY,
            table.ACCOUNT_ID, table.CONFIRMED_BY, table.REVISION, table.CREATED_AT,
            table.UPDATED_AT,
        )).from(table).where(table.WORKSPACE_ID.eq(workspace))
            .orderBy(table.ID).fetch { row -> LedgerStatementMappingFactDto(
                id = row[table.ID]!!.toString(),
                sourceType = row[table.SOURCE_TYPE]!!,
                identityKind = row[table.IDENTITY_KIND]!!,
                sourceAccountKey = row[table.SOURCE_ACCOUNT_KEY]!!,
                accountId = row[table.ACCOUNT_ID]?.toString(),
                confirmedBy = row[table.CONFIRMED_BY]!!,
                revision = row[table.REVISION]!!.toString(),
                createdAt = row[table.CREATED_AT]!!.let(::manualInstantText),
                updatedAt = row[table.UPDATED_AT]!!.let(::manualInstantText),
            ) }
    }

    private fun cashCategories(): List<LedgerCategoryFactDto> {
        val table = CASH_CATEGORIES
        return sql.select(listOf(
            table.ID, table.PARENT_ID, table.PARENT_SCOPE_KEY, table.NAME,
            table.NORMALIZED_NAME, table.DESCRIPTION, table.CATEGORY_PATH, table.DEPTH,
            table.SORT_ORDER, table.REVISION, table.CREATED_AT, table.UPDATED_AT,
        )).from(table).where(table.WORKSPACE_ID.eq(workspace))
            .orderBy(table.ID).fetch { row -> LedgerCategoryFactDto(
                id = row[table.ID]!!,
                parentId = row[table.PARENT_ID],
                parentScopeKey = row[table.PARENT_SCOPE_KEY]!!,
                name = row[table.NAME]!!,
                normalizedName = row[table.NORMALIZED_NAME]!!,
                description = row[table.DESCRIPTION],
                categoryPath = row[table.CATEGORY_PATH]!!,
                depth = Math.toIntExact(row[table.DEPTH]!!),
                sortOrder = row[table.SORT_ORDER]!!.toString(),
                revision = row[table.REVISION]!!.toString(),
                createdAt = row[table.CREATED_AT]!!.let(::manualInstantText),
                updatedAt = row[table.UPDATED_AT]!!.let(::manualInstantText),
            ) }
    }

    private fun cashTransactions(): List<LedgerCashTransactionFactDto> {
        val table = CASH_TRANSACTIONS
        return sql.select(listOf(
            table.ID, table.ACCOUNT_ID, table.STATE, table.RECORD_ORIGIN,
            table.MANUAL_TRANSFER_ID, table.ROW_REVISION, table.CASH_GRANULARITY, table.SOURCE_TYPE,
            table.RECORD_ID, table.SOURCE_PAYLOAD, table.RELATION_METADATA, table.SOURCE_FINGERPRINT,
            table.MANUAL_OVERRIDES, table.OCCURRED_AT, table.AMOUNT, table.CURRENCY,
            table.COUNTERPARTY, table.COUNTERPARTY_ACCOUNT, table.COUNTERPARTY_ACCOUNT_ATTRS, table.NOTE,
            table.CATEGORY_ID, table.RECORD_TYPE, table.RECORD_SUBTYPE, table.CREATED_AT,
            table.DELETED_AT, table.DELETED_BY, table.DELETE_REASON,
        )).from(table).where(table.WORKSPACE_ID.eq(workspace))
            .orderBy(table.ID).fetch { row -> LedgerCashTransactionFactDto(
                id = row[table.ID]!!.toString(),
                accountId = row[table.ACCOUNT_ID]?.toString(),
                state = row[table.STATE]!!,
                recordOrigin = row[table.RECORD_ORIGIN]!!,
                manualTransferId = row[table.MANUAL_TRANSFER_ID],
                rowRevision = row[table.ROW_REVISION]!!.toString(),
                cashGranularity = row[table.CASH_GRANULARITY]!!,
                sourceType = row[table.SOURCE_TYPE],
                recordId = row[table.RECORD_ID]!!,
                sourcePayload = row[table.SOURCE_PAYLOAD] ?: JsonNull,
                relationMetadata = row[table.RELATION_METADATA] ?: JsonNull,
                sourceFingerprint = row[table.SOURCE_FINGERPRINT],
                manualOverrides = row[table.MANUAL_OVERRIDES] ?: JsonNull,
                occurredAt = row[table.OCCURRED_AT]?.let(::manualInstantText),
                amount = row[table.AMOUNT]?.text,
                currency = row[table.CURRENCY],
                counterparty = row[table.COUNTERPARTY]!!,
                counterpartyAccount = row[table.COUNTERPARTY_ACCOUNT]!!,
                counterpartyAccountAttrs = row[table.COUNTERPARTY_ACCOUNT_ATTRS] ?: JsonNull,
                note = row[table.NOTE]!!,
                categoryId = row[table.CATEGORY_ID],
                recordType = row[table.RECORD_TYPE],
                recordSubtype = row[table.RECORD_SUBTYPE],
                createdAt = row[table.CREATED_AT]!!.let(::manualInstantText),
                deletedAt = row[table.DELETED_AT]?.let(::manualInstantText),
                deletedBy = row[table.DELETED_BY]!!,
                deleteReason = row[table.DELETE_REASON]!!,
            ) }
    }

    private fun cashTransactionComponents(): List<LedgerCashComponentFactDto> {
        val table = CASH_TRANSACTION_COMPONENTS
        return sql.select(listOf(
            table.ID, table.CASH_TRANSACTION_ID, table.ACCOUNT_ID, table.AMOUNT,
            table.CURRENCY, table.ORDINAL, table.LABEL, table.SOURCE_KEY,
            table.METADATA_JSON, table.CREATED_AT,
        )).from(table).where(table.WORKSPACE_ID.eq(workspace))
            .orderBy(table.ID).fetch { row -> LedgerCashComponentFactDto(
                id = row[table.ID]!!.toString(),
                cashTransactionId = row[table.CASH_TRANSACTION_ID]!!.toString(),
                accountId = row[table.ACCOUNT_ID]?.toString(),
                amount = row[table.AMOUNT]?.text,
                currency = row[table.CURRENCY],
                ordinal = row[table.ORDINAL]!!.toString(),
                label = row[table.LABEL]!!,
                sourceKey = row[table.SOURCE_KEY]!!,
                metadataJson = row[table.METADATA_JSON] ?: JsonNull,
                createdAt = row[table.CREATED_AT]!!.let(::manualInstantText),
            ) }
    }

    private fun cashSourceBindings(): List<LedgerSourceBindingFactDto> {
        val table = CASH_SOURCE_BINDINGS
        return sql.select(listOf(
            table.ID, table.TRANSACTION_ID, table.SOURCE_TYPE, table.SOURCE_SCOPE_KIND,
            table.SOURCE_SCOPE_KEY, table.IDENTITY_KIND, table.IDENTITY_KEY, table.IDENTITY_STRENGTH,
            table.STABLE_EVIDENCE_KEY, table.STRATEGY_VERSION, table.COVERAGE, table.REVISION,
            table.CREATED_AT, table.UPDATED_AT,
        )).from(table).where(table.WORKSPACE_ID.eq(workspace))
            .orderBy(table.ID).fetch { row -> LedgerSourceBindingFactDto(
                id = row[table.ID]!!.toString(),
                transactionId = row[table.TRANSACTION_ID]!!.toString(),
                sourceType = row[table.SOURCE_TYPE]!!,
                sourceScopeKind = row[table.SOURCE_SCOPE_KIND]!!,
                sourceScopeKey = row[table.SOURCE_SCOPE_KEY]!!,
                identityKind = row[table.IDENTITY_KIND]!!,
                identityKey = row[table.IDENTITY_KEY]!!,
                identityStrength = row[table.IDENTITY_STRENGTH]!!,
                stableEvidenceKey = row[table.STABLE_EVIDENCE_KEY],
                strategyVersion = row[table.STRATEGY_VERSION]!!,
                coverage = row[table.COVERAGE] ?: JsonNull,
                revision = row[table.REVISION]!!.toString(),
                createdAt = row[table.CREATED_AT]!!.let(::manualInstantText),
                updatedAt = row[table.UPDATED_AT]!!.let(::manualInstantText),
            ) }
    }

    private fun cashSourceEmptyPages(): List<LedgerSourceEmptyPageFactDto> {
        val table = CASH_SOURCE_EMPTY_PAGES
        return sql.select(listOf(
            table.SOURCE_TYPE, table.PARSER_VERSION, table.DOCUMENT_KEY, table.PAGE_NUMBER,
            table.COVERAGE,
        )).from(table).where(table.WORKSPACE_ID.eq(workspace))
            .orderBy(table.SOURCE_TYPE, table.PARSER_VERSION, table.DOCUMENT_KEY, table.PAGE_NUMBER).fetch { row -> LedgerSourceEmptyPageFactDto(
                sourceType = row[table.SOURCE_TYPE]!!,
                parserVersion = row[table.PARSER_VERSION]!!,
                documentKey = row[table.DOCUMENT_KEY]!!,
                pageNumber = Math.toIntExact(row[table.PAGE_NUMBER]!!),
                coverage = row[table.COVERAGE] ?: JsonNull,
            ) }
    }

    private fun transactionRelations(): List<LedgerCashRelationFactDto> {
        val table = TRANSACTION_RELATIONS
        return sql.select(listOf(
            table.ID, table.KIND, table.PRIMARY_TRANSACTION_ID, table.SECONDARY_TRANSACTION_ID,
            table.PRIMARY_COMPONENT_ID, table.SECONDARY_COMPONENT_ID, table.ANCHOR_TRANSACTION_ID, table.ANCHOR_COMPONENT_ID,
            table.PRIMARY_ROLE, table.SECONDARY_ROLE, table.STATE, table.ACTIVE,
            table.ROW_REVISION, table.DECISION_GROUP_ID, table.DECISION_SOURCE, table.EVIDENCE,
            table.APPLIED_AMOUNT, table.RULE_ID, table.CREATED_BY, table.CREATED_AT,
            table.DECIDED_BY, table.DECIDED_AT, table.DECISION_REASON,
        )).from(table).where(table.WORKSPACE_ID.eq(workspace))
            .orderBy(table.ID).fetch { row -> LedgerCashRelationFactDto(
                id = row[table.ID]!!.toString(),
                kind = row[table.KIND]!!,
                primaryTransactionId = row[table.PRIMARY_TRANSACTION_ID]!!.toString(),
                secondaryTransactionId = row[table.SECONDARY_TRANSACTION_ID]?.toString(),
                primaryComponentId = row[table.PRIMARY_COMPONENT_ID]?.toString(),
                secondaryComponentId = row[table.SECONDARY_COMPONENT_ID]?.toString(),
                anchorTransactionId = row[table.ANCHOR_TRANSACTION_ID]!!.toString(),
                anchorComponentId = row[table.ANCHOR_COMPONENT_ID]?.toString(),
                primaryRole = row[table.PRIMARY_ROLE],
                secondaryRole = row[table.SECONDARY_ROLE],
                state = row[table.STATE]!!,
                active = row[table.ACTIVE]!!,
                rowRevision = row[table.ROW_REVISION]!!.toString(),
                decisionGroupId = row[table.DECISION_GROUP_ID],
                decisionSource = row[table.DECISION_SOURCE]!!,
                evidence = row[table.EVIDENCE] ?: JsonNull,
                appliedAmount = row[table.APPLIED_AMOUNT]!!.text,
                ruleId = row[table.RULE_ID]!!,
                createdBy = row[table.CREATED_BY]!!,
                createdAt = row[table.CREATED_AT]!!.let(::manualInstantText),
                decidedBy = row[table.DECIDED_BY]!!,
                decidedAt = row[table.DECIDED_AT]?.let(::manualInstantText),
                decisionReason = row[table.DECISION_REASON]!!,
            ) }
    }

    private fun cashRelationCandidates(): List<LedgerRelationCandidateFactDto> {
        val table = CASH_RELATION_CANDIDATES
        return sql.select(listOf(
            table.ID, table.RELATION_ID, table.CANDIDATE_TRANSACTION_ID, table.CANDIDATE_COMPONENT_ID,
            table.EXPECTED_ROW_REVISION, table.EVIDENCE, table.VALIDITY,
        )).from(table).where(table.WORKSPACE_ID.eq(workspace))
            .orderBy(table.ID).fetch { row -> LedgerRelationCandidateFactDto(
                id = row[table.ID]!!.toString(),
                relationId = row[table.RELATION_ID]!!.toString(),
                candidateTransactionId = row[table.CANDIDATE_TRANSACTION_ID]!!.toString(),
                candidateComponentId = row[table.CANDIDATE_COMPONENT_ID]?.toString(),
                expectedRowRevision = row[table.EXPECTED_ROW_REVISION]!!.toString(),
                evidence = row[table.EVIDENCE] ?: JsonNull,
                validity = row[table.VALIDITY]!!,
            ) }
    }

    private fun cashTransactionIssues(): List<LedgerCashIssueFactDto> {
        val table = CASH_TRANSACTION_ISSUES
        return sql.select(listOf(
            table.ID, table.TRANSACTION_ID, table.ISSUE_KEY, table.CODE,
            table.FIELD_PATH, table.PENDING_SOURCE_PAYLOAD, table.PENDING_FIELDS, table.ORIGIN,
            table.REVISION, table.CREATED_AT, table.UPDATED_AT,
        )).from(table).where(table.WORKSPACE_ID.eq(workspace))
            .orderBy(table.ID).fetch { row -> LedgerCashIssueFactDto(
                id = row[table.ID]!!.toString(),
                transactionId = row[table.TRANSACTION_ID]!!.toString(),
                issueKey = row[table.ISSUE_KEY]!!,
                code = row[table.CODE]!!,
                fieldPath = row[table.FIELD_PATH],
                pendingSourcePayload = row[table.PENDING_SOURCE_PAYLOAD] ?: JsonNull,
                pendingFields = row[table.PENDING_FIELDS] ?: JsonNull,
                origin = row[table.ORIGIN] ?: JsonNull,
                revision = row[table.REVISION]!!.toString(),
                createdAt = row[table.CREATED_AT]!!.let(::manualInstantText),
                updatedAt = row[table.UPDATED_AT]!!.let(::manualInstantText),
            ) }
    }

    private fun cashIssueCandidates(): List<LedgerIssueCandidateFactDto> {
        val table = CASH_ISSUE_CANDIDATES
        return sql.select(listOf(
            table.ID, table.ISSUE_ID, table.CANDIDATE_TRANSACTION_ID, table.EXPECTED_ROW_REVISION,
        )).from(table).where(table.WORKSPACE_ID.eq(workspace))
            .orderBy(table.ID).fetch { row -> LedgerIssueCandidateFactDto(
                id = row[table.ID]!!.toString(),
                issueId = row[table.ISSUE_ID]!!.toString(),
                candidateTransactionId = row[table.CANDIDATE_TRANSACTION_ID]!!.toString(),
                expectedRowRevision = row[table.EXPECTED_ROW_REVISION]!!.toString(),
            ) }
    }

    private fun cashEntries(): List<LedgerCashEntryFactDto> {
        val table = CASH_ENTRIES
        return sql.select(listOf(
            table.ID, table.ROOT_TRANSACTION_ID, table.IDENTITY_TRANSACTION_ID, table.REVISION,
            table.CATEGORY_ID, table.NOTE, table.COUNTERPARTY, table.COUNTERPARTY_ACCOUNT,
            table.COUNTERPARTY_ACCOUNT_ATTRS, table.MANUAL_FIELDS, table.SPLIT_INHERIT,
        )).from(table).where(table.WORKSPACE_ID.eq(workspace))
            .orderBy(table.ID).fetch { row -> LedgerCashEntryFactDto(
                id = row[table.ID]!!,
                rootTransactionId = row[table.ROOT_TRANSACTION_ID]!!.toString(),
                identityTransactionId = row[table.IDENTITY_TRANSACTION_ID]!!.toString(),
                revision = row[table.REVISION]!!.toString(),
                categoryId = row[table.CATEGORY_ID],
                note = row[table.NOTE]!!,
                counterparty = row[table.COUNTERPARTY]!!,
                counterpartyAccount = row[table.COUNTERPARTY_ACCOUNT]!!,
                counterpartyAccountAttrs = row[table.COUNTERPARTY_ACCOUNT_ATTRS] ?: JsonNull,
                manualFields = row[table.MANUAL_FIELDS] ?: JsonNull,
                splitInherit = row[table.SPLIT_INHERIT],
            ) }
    }

    private fun cashEntryMembers(): List<LedgerCashEntryMemberFactDto> {
        val table = CASH_ENTRY_MEMBERS
        return sql.select(listOf(
            table.TRANSACTION_ID, table.ENTRY_ID, table.STANDALONE_VALUES, table.STANDALONE_MANUAL_FIELDS,
        )).from(table).where(table.WORKSPACE_ID.eq(workspace))
            .orderBy(table.TRANSACTION_ID).fetch { row -> LedgerCashEntryMemberFactDto(
                transactionId = row[table.TRANSACTION_ID]!!.toString(),
                entryId = row[table.ENTRY_ID]!!,
                standaloneValues = row[table.STANDALONE_VALUES] ?: JsonNull,
                standaloneManualFields = row[table.STANDALONE_MANUAL_FIELDS] ?: JsonNull,
            ) }
    }

    private fun cashInvestmentFundingRelations(): List<LedgerInvestmentFundingFactDto> {
        val table = CASH_INVESTMENT_FUNDING_RELATIONS
        return sql.select(listOf(
            table.ID, table.CASH_TRANSACTION_COMPONENT_ID, table.INVESTMENT_EVENT_ID, table.DIRECTION,
            table.STATUS, table.RULE_ID, table.EVIDENCE, table.ACTIVE_SLOT,
            table.CREATED_BY, table.CREATED_AT, table.DECIDED_BY, table.DECIDED_AT,
            table.DECISION_REASON,
        )).from(table).where(table.WORKSPACE_ID.eq(workspace))
            .orderBy(table.ID).fetch { row -> LedgerInvestmentFundingFactDto(
                id = row[table.ID]!!.toString(),
                cashTransactionComponentId = row[table.CASH_TRANSACTION_COMPONENT_ID]!!.toString(),
                investmentEventId = row[table.INVESTMENT_EVENT_ID]!!.toString(),
                direction = row[table.DIRECTION]!!,
                status = row[table.STATUS]!!,
                ruleId = row[table.RULE_ID]!!,
                evidence = row[table.EVIDENCE] ?: JsonNull,
                activeSlot = row[table.ACTIVE_SLOT]!!,
                createdBy = row[table.CREATED_BY]!!,
                createdAt = row[table.CREATED_AT]!!.let(::manualInstantText),
                decidedBy = row[table.DECIDED_BY]!!,
                decidedAt = row[table.DECIDED_AT]?.let(::manualInstantText),
                decisionReason = row[table.DECISION_REASON]!!,
            ) }
    }

}
