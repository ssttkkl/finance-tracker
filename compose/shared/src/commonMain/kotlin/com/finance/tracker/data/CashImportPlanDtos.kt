package com.finance.tracker.data

typealias CashImportRowAction = com.finance.tracker.business.contracts.CashImportRowAction
typealias CashImportRelationAction = com.finance.tracker.business.contracts.CashImportRelationAction
typealias CashImportRowInput = com.finance.tracker.business.contracts.CashImportRowInput
typealias CashImportRelationInput = com.finance.tracker.business.contracts.CashImportRelationInput
typealias CashImportEntryInput = com.finance.tracker.business.contracts.CashImportEntryInput
typealias CashImportPreviewInput = com.finance.tracker.business.contracts.CashImportPreviewInput
typealias CashPlanMemberRefDto = com.finance.tracker.business.contracts.CashPlanMemberRefDto
typealias CashPlanItemDto = com.finance.tracker.business.contracts.CashPlanItemDto
typealias CashPlanFileDto = com.finance.tracker.business.contracts.CashPlanFileDto
typealias CashInvalidatedChoiceDto = com.finance.tracker.business.contracts.CashInvalidatedChoiceDto
typealias CashPlanPagesDto = com.finance.tracker.business.contracts.CashPlanPagesDto
typealias CashImportPlanDto = com.finance.tracker.business.contracts.CashImportPlanDto
typealias CashPlanItemPageDto = com.finance.tracker.business.contracts.CashPlanItemPageDto
typealias CashPlanProcessingDto = com.finance.tracker.business.contracts.CashPlanProcessingDto
typealias CashPlanProcessingPageDto = com.finance.tracker.business.contracts.CashPlanProcessingPageDto
typealias CashPlanMemberDto = com.finance.tracker.business.contracts.CashPlanMemberDto
typealias CashPlanFieldDiffDto = com.finance.tracker.business.contracts.CashPlanFieldDiffDto
typealias CashPlanRelationSummaryDto = com.finance.tracker.business.contracts.CashPlanRelationSummaryDto
typealias CashPlanRelationCandidateDto = com.finance.tracker.business.contracts.CashPlanRelationCandidateDto
typealias CashSplitFieldDiffDto = com.finance.tracker.business.contracts.CashSplitFieldDiffDto
typealias CashPlanRelationChoiceDto = com.finance.tracker.business.contracts.CashPlanRelationChoiceDto
typealias CashPlanRelationEffectDto = com.finance.tracker.business.contracts.CashPlanRelationEffectDto
typealias CashPlanEntryDto = com.finance.tracker.business.contracts.CashPlanEntryDto
typealias CashPlanItemDetailDto = com.finance.tracker.business.contracts.CashPlanItemDetailDto
typealias CashImportAccountInput = com.finance.tracker.business.contracts.CashImportAccountInput
typealias CashPlanDisplayDto = com.finance.tracker.business.contracts.CashPlanDisplayDto

typealias CashImportCommitOutcomeDto = com.finance.tracker.business.contracts.CashImportCommitOutcomeDto
typealias CashMutationReceiptDto = com.finance.tracker.business.contracts.CashMutationReceiptDto
typealias CashMutationResultDto = com.finance.tracker.business.contracts.CashMutationResultDto
typealias CashMutationProjectionRefDto = com.finance.tracker.business.contracts.CashMutationProjectionRefDto
typealias CashMutationResultPageDto = com.finance.tracker.business.contracts.CashMutationResultPageDto
typealias CashMutationResolveDto = com.finance.tracker.business.contracts.CashMutationResolveDto

enum class CashMutationResultKind(internal val path: String) { ROW("rows"), RELATION("relations") }
