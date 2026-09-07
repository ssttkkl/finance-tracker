import type {
  Account,
  CashCategory,
  CashCategoryDirectory,
  CashFilters,
  CashPage,
  CashProjectionDeleteImpact,
  CashProjectionDeleteResult,
  CashRecordDetail,
  CashRecordPage,
  Evidence,
  FileSource,
  ImportCommitResult,
  ImportDetection,
  ImportMappingDecision,
  ImportPreview,
  ImportScan,
  LedgerOptions,
} from "@finance-tracker/contracts";
import { apiClient } from "./access";

export type { Account, CashCategory, CashCategoryDirectory, CashFilters, CashPage, CashProjectionDeleteImpact, CashProjectionDeleteResult, CashRecordDetail, CashRecordPage, Evidence, ImportCommitResult, ImportDetection, ImportMappingDecision, ImportPreview, ImportScan, LedgerOptions } from "@finance-tracker/contracts";

const importChannelLabels: Record<string, string> = {
  alipay: "支付宝", wechat: "微信", icbc: "工行信用卡", "icbc-debit": "工行借记卡",
  "ccb-debit": "建行借记卡", "icbc-asia": "工银亚洲",
};
export { importChannelLabels };

function browserFileSource(file: File): FileSource {
  return {
    name: file.name,
    mediaType: file.type || null,
    size: Number.isFinite(file.size) ? file.size : null,
    async read() {
      if (typeof file.arrayBuffer === "function") return new Uint8Array(await file.arrayBuffer());
      return new Uint8Array(await new Response(file).arrayBuffer());
    },
  };
}

export function fetchCashPage(filters: CashFilters, cursor?: string | null, signal?: AbortSignal): Promise<CashPage> {
  return apiClient().fetchCashPage(filters, cursor, signal);
}

export function fetchCashAccounts(signal?: AbortSignal): Promise<Account[]> {
  return apiClient().fetchCashAccounts(signal);
}

export function fetchEvidence(id: string, signal?: AbortSignal): Promise<Evidence> {
  return apiClient().fetchEvidence(id, signal);
}

export function fetchLedgerOptions(signal?: AbortSignal): Promise<LedgerOptions> {
  return apiClient().fetchLedgerOptions(signal);
}

export function fetchCashCategories(signal?: AbortSignal): Promise<CashCategoryDirectory> {
  return apiClient().fetchCashCategories(signal);
}

export function createCashCategory(body: { name: string; parent_id?: string | null; description?: string; expected_revision?: number }, signal?: AbortSignal): Promise<CashCategory> {
  return apiClient().createCashCategory(body, signal);
}

export function updateCashCategory(id: string, body: { name?: string; parent_id?: string | null; description?: string; expected_revision?: number }, signal?: AbortSignal): Promise<CashCategory> {
  return apiClient().updateCashCategory(id, body, signal);
}

export function reorderCashCategory(id: string, direction: "before" | "after", expected_revision: number, signal?: AbortSignal): Promise<CashCategory> {
  return apiClient().reorderCashCategory(id, direction, expected_revision, signal);
}

export function fetchCashCategoryDeletionImpact(id: string, signal?: AbortSignal): Promise<{ category_id: string; revision: number; category_revision: number; child_count: number; direct_usage_count: number }> {
  return apiClient().fetchCashCategoryDeletionImpact(id, signal) as Promise<{ category_id: string; revision: number; category_revision: number; child_count: number; direct_usage_count: number }>;
}

export function deleteCashCategory(id: string, body: { expected_revision: number; expected_category_revision: number; expected_usage_count: number; confirmed: boolean }, signal?: AbortSignal): Promise<{ category_id: string; cleared_transaction_count: number; revision: number }> {
  return apiClient().deleteCashCategory(id, body, signal) as Promise<{ category_id: string; cleared_transaction_count: number; revision: number }>;
}

export function classifyCashProjection(id: string, projection_version: number, category_id: string | null, signal?: AbortSignal): Promise<{ projection_version: number; projection_count: number; updated_transaction_count: number; category_id: string | null }> {
  return classifyCashProjections([id], projection_version, category_id, signal);
}

export function classifyCashProjections(projection_ids: string[], projection_version: number, category_id: string | null, signal?: AbortSignal): Promise<{ projection_version: number; projection_count: number; updated_transaction_count: number; category_id: string | null }> {
  return apiClient().classifyCashProjections(projection_ids, projection_version, category_id, signal) as Promise<{ projection_version: number; projection_count: number; updated_transaction_count: number; category_id: string | null }>;
}

export function fetchCashProjectionDeleteImpact(projection_ids: string[], projection_version: number, signal?: AbortSignal): Promise<CashProjectionDeleteImpact> {
  return apiClient().fetchCashProjectionDeleteImpact(projection_ids, projection_version, signal);
}

export function deleteCashProjections(projection_ids: string[], projection_version: number, signal?: AbortSignal): Promise<CashProjectionDeleteResult> {
  return apiClient().deleteCashProjections(projection_ids, projection_version, signal);
}

export function fetchCashRecord(id: string, signal?: AbortSignal): Promise<CashRecordDetail> {
  return apiClient().fetchCashRecord(id, signal);
}

export function fetchCashRecords(
  values: { query?: string; excludeId?: string; dateFrom?: string; dateTo?: string; timezone?: string; cursor?: string | null; limit?: number } = {},
  signal?: AbortSignal,
): Promise<CashRecordPage> {
  return apiClient().fetchCashRecords(values, signal);
}

export function createCashRecord(body: Record<string, unknown>, signal?: AbortSignal): Promise<CashRecordDetail> {
  return apiClient().createCashRecord(body, signal);
}

export function updateCashRecord(id: string, body: Record<string, unknown>, signal?: AbortSignal): Promise<CashRecordDetail> {
  return apiClient().updateCashRecord(id, body, signal);
}

export function deleteCashRecord(id: string, mode: "delete_all" | "delete_current_dissolve", signal?: AbortSignal): Promise<{ deleted: boolean; related_count: number; deleted_fact_ids: string[] }> {
  return apiClient().deleteCashRecord(id, mode, signal);
}

export function createCashRelation(body: Record<string, unknown>, signal?: AbortSignal): Promise<CashRecordDetail> {
  return apiClient().createCashRelation(body, signal);
}

export function updateCashRelation(id: string, body: Record<string, unknown>, signal?: AbortSignal): Promise<CashRecordDetail> {
  return apiClient().updateCashRelation(id, body, signal);
}

export function cancelCashRelation(id: string, signal?: AbortSignal): Promise<unknown> {
  return apiClient().cancelCashRelation(id, signal);
}

export function dissolveCashRelations(factId: string, signal?: AbortSignal): Promise<CashRecordDetail> {
  return apiClient().dissolveCashRelations(factId, signal);
}

export function detectCashImport(file: File, currency?: string, password?: string): Promise<ImportDetection> {
  return apiClient().detectCashImport(browserFileSource(file), currency, password);
}

export function scanCashImport(file: File, currency?: string, password?: string, importToken?: string): Promise<ImportScan> {
  return apiClient().scanCashImport(browserFileSource(file), currency, password, importToken);
}

export function previewCashImport(file: File, source = "", currency?: string, password?: string, mapping?: ImportMappingDecision[], importToken?: string): Promise<ImportPreview> {
  return apiClient().previewCashImport(browserFileSource(file), source, currency, password, mapping, importToken);
}

export function commitCashImport(
  file: File,
  source = "",
  currency?: string,
  options: { password?: string; previewDigest?: string; previewRelationDigest?: string; previewChannel?: string; relations?: Record<string, unknown>[]; mapping?: ImportMappingDecision[]; importToken?: string; idempotencyKey?: string } = {},
): Promise<ImportCommitResult> {
  return apiClient().commitCashImport(browserFileSource(file), source, currency, options);
}
