import { useEffect, useRef, useState } from "react";
import { cancelCashRelation, classifyCashProjections, fetchCashAccounts, fetchCashPage, fetchCashRecord, fetchEvidence, fetchLedgerOptions } from "../api/cashLedger";
import type { Account, CashCategory, CashFilterOptions, CashFilters, CashMonthlySummary, CashProjection, CashRecord, CashRecordDetail, Evidence, EvidenceRecord, LedgerOptions } from "../api/types";
import { CashFiltersBar } from "../components/CashFilters";
import { CashTable } from "../components/CashTable";
import { EvidenceDetail } from "../components/EvidenceDetail";
import { LoadMoreControl } from "../components/Pagination";
import { StatusView } from "../components/StatusView";
import { RecordDrawer } from "../components/RecordDrawer";
import { ImportDrawer } from "../components/ImportDrawer";

const requestErrorMessages: Record<string, string> = {
  api_origin_invalid: "账本暂不可用，请稍后重试。",
  "storage.busy": "账本正被其他操作占用，请稍后重试。",
  "storage.readonly": "账本当前不可读取，请稍后重试。",
  "storage.connect": "无法读取账本，请稍后重试。",
  "storage.schema": "账本暂不可用，请稍后重试。",
  "storage.workspace": "账本暂不可用，请稍后重试。",
  "storage.config": "账本暂不可用，请稍后重试。",
  "projection.unavailable": "账本数据暂不可用，请先完成更新。",
  invalid_filter: "请修正标记的金额筛选条件后重试。",
  invalid_cursor: "加载位置已失效，请重新读取记录。",
  api_request_failed: "请求失败，请稍后重试。",
};

const relationLabels: Record<string, string> = {
  payment_mirror: "同笔支付",
  refund_offset: "退款冲销",
  transfer_pair: "个人转账",
  cash_investment_funding: "银证转账",
};

function fallbackRecordType(record: EvidenceRecord, projection: CashProjection): string {
  if (record.record_type) return record.record_type;
  if (projection.economic_type === "expense") return "expense";
  if (projection.economic_type === "income") return "income";
  return record.amount.startsWith("-") ? "transfer_out" : "transfer_in";
}

function cashRecordFromEvidence(record: EvidenceRecord, projection: CashProjection): CashRecord {
  return {
    id: record.id,
    occurred_at: record.occurred_at,
    amount: record.amount,
    currency: record.currency,
    counterparty: record.counterparty,
    counterparty_account: record.counterparty_account ?? "",
    note: record.note,
    category: record.category,
    category_id: record.category_id ?? projection.category?.id ?? null,
    record_type: fallbackRecordType(record, projection),
    record_subtype: record.record_subtype ?? projection.transfer_subtype ?? "not_applicable",
    account_name: record.account_name ?? record.account.name,
    account_id: record.account_id ?? record.account.id,
    account_type: record.account_type ?? record.account.type,
    source_type: record.source_type ?? "",
  };
}

function detailFromEvidence(evidence: Evidence, recordId: string, options: LedgerOptions): CashRecordDetail | null {
  const sourceRecords = [evidence.root_record, ...evidence.members];
  const source = sourceRecords.find((item) => item.id === recordId);
  if (!source) return null;
  const relationRows = [
    ...evidence.accepted_relations.map((relation) => ({ ...relation, status: "accepted" as const })),
    ...evidence.inactive_relation_hints,
  ];
  return {
    record: cashRecordFromEvidence(source, evidence.projection),
    relations: relationRows.map((relation) => ({
      id: relation.id,
      kind: relation.kind,
      label: relationLabels[relation.kind] ?? relation.kind,
      subtype: relation.subtype,
      status: relation.status,
      primary_record: relation.primary_record ? cashRecordFromEvidence(relation.primary_record, evidence.projection) : null,
      secondary_record: relation.secondary_record ? cashRecordFromEvidence(relation.secondary_record, evidence.projection) : null,
    })),
    options,
  };
}

export function CashLedgerPage() {
  const [filters, setFilters] = useState<CashFilters>({});
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [filterOptions, setFilterOptions] = useState<CashFilterOptions>({ categories: [], currencies: [], economic_types: [] });
  const [filterOptionsReady, setFilterOptionsReady] = useState(false);
  const [filterOptionsLoading, setFilterOptionsLoading] = useState(true);
  const [categories, setCategories] = useState<CashCategory[]>([]);
  const [monthlySummaries, setMonthlySummaries] = useState<CashMonthlySummary[]>([]);
  const [accountsError, setAccountsError] = useState(false);
  const [items, setItems] = useState<CashProjection[]>([]);
  const [projectionVersion, setProjectionVersion] = useState<number | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set());
  const [batchOpen, setBatchOpen] = useState(false);
  const [batchCategoryId, setBatchCategoryId] = useState<string | null>(null);
  const [batchSaving, setBatchSaving] = useState(false);
  const [batchError, setBatchError] = useState<string | undefined>();
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "empty" | "error">("loading");
  const [appendLoading, setAppendLoading] = useState(false);
  const [appendError, setAppendError] = useState<string | undefined>();
  const [errorMessage, setErrorMessage] = useState<string | undefined>();
  const [amountFilterState, setAmountFilterState] = useState<"error" | "success" | undefined>();
  const [projectionUpdated, setProjectionUpdated] = useState(false);
  const [refreshGeneration, setRefreshGeneration] = useState(0);
  const [selected, setSelected] = useState<CashProjection | null>(null);
  const [evidence, setEvidence] = useState<Evidence | null>(null);
  const [evidenceState, setEvidenceState] = useState<"loading" | "ready" | "error">("loading");
  const [ledgerOptions, setLedgerOptions] = useState<LedgerOptions>({ record_types: [], relation_types: [] });
  const [recordDetail, setRecordDetail] = useState<CashRecordDetail | null>(null);
  const [recordLoading, setRecordLoading] = useState(false);
  const [recordLoadError, setRecordLoadError] = useState(false);
  const [relationComposerOpen, setRelationComposerOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [importing, setImporting] = useState(false);
  const opener = useRef<HTMLButtonElement | null>(null);
  const pageAbortController = useRef<AbortController | null>(null);
  const evidenceAbortController = useRef<AbortController | null>(null);
  const restoreEvidenceFocus = useRef(false);
  const accountsAbortController = useRef<AbortController | null>(null);
  const pageRequestId = useRef(0);
  const evidenceRequestId = useRef(0);
  const accountsRequestId = useRef(0);
  const updateConfirmation = useRef<HTMLButtonElement | null>(null);
  const loadMoreRef = useRef<(retry?: boolean) => void>(() => undefined);
  const appendingCursor = useRef<string | null>(null);
  const batchErrorAfterRefresh = useRef<string | undefined>(undefined);

  const resetAndLoad = () => {
    pageAbortController.current?.abort();
    appendingCursor.current = null;
    const controller = new AbortController(); pageAbortController.current = controller;
    const requestId = ++pageRequestId.current;
    setStatus("loading"); setFilterOptionsLoading(true); setItems([]); setNextCursor(null); setMonthlySummaries([]); setProjectionVersion(null); setSelectedIds(new Set()); setBatchOpen(false); setBatchError(batchErrorAfterRefresh.current); batchErrorAfterRefresh.current = undefined; setErrorMessage(undefined); setAppendError(undefined); setAppendLoading(false);
    fetchCashPage(filters, null, controller.signal).then((value) => {
      if (requestId !== pageRequestId.current) return;
      setItems(value.items); setNextCursor(value.next_cursor); setProjectionVersion(value.projection_version); setMonthlySummaries(value.monthly_summaries ?? []);
      if (value.filter_options) {
        setFilterOptions(value.filter_options);
        setCategories(value.filter_options.categories);
      }
      setFilterOptionsReady(true); setFilterOptionsLoading(false); setAmountFilterState(filters.amount_min || filters.amount_max ? "success" : undefined); setStatus(value.items.length ? "ready" : "empty");
    }).catch((error: unknown) => {
      if (controller.signal.aborted || requestId !== pageRequestId.current) return;
      if (error instanceof Error && error.message === "projection.updated") {
        closeEvidence(); setProjectionUpdated(true); setRefreshGeneration((value) => value + 1); return;
      }
      const code = error instanceof Error ? error.message : "api_request_failed";
      setAmountFilterState(code === "invalid_filter" ? "error" : undefined);
      setFilterOptionsLoading(false); setErrorMessage(requestErrorMessages[code] ?? requestErrorMessages.api_request_failed); setStatus("error");
    });
  };
  const loadMore = (retry = false) => {
    const cursor = nextCursor;
    if (!cursor || appendLoading || (!retry && appendError) || appendingCursor.current === cursor) return;
    appendingCursor.current = cursor;
    const controller = new AbortController(); pageAbortController.current = controller;
    const requestId = ++pageRequestId.current;
    setAppendLoading(true); if (retry) setAppendError(undefined);
    fetchCashPage(filters, cursor, controller.signal).then((value) => {
      if (requestId !== pageRequestId.current) return;
      if (projectionVersion !== null && value.projection_version !== projectionVersion) setSelectedIds(new Set());
      setItems((current) => [...current, ...value.items.filter((item) => !current.some((old) => old.projection_id === item.projection_id))]);
      setProjectionVersion(value.projection_version); setNextCursor(value.next_cursor); if (value.monthly_summaries) setMonthlySummaries(value.monthly_summaries); setAppendLoading(false); setAppendError(undefined); appendingCursor.current = null;
    }).catch((error: unknown) => {
      if (controller.signal.aborted || requestId !== pageRequestId.current) return;
      if (error instanceof Error && error.message === "projection.updated") {
        setAppendLoading(false); appendingCursor.current = null; closeEvidence(); setProjectionUpdated(true); setRefreshGeneration((value) => value + 1); return;
      }
      const code = error instanceof Error ? error.message : "api_request_failed";
      setAppendError(requestErrorMessages[code] ?? requestErrorMessages.api_request_failed); setAppendLoading(false); appendingCursor.current = null;
    });
  };
  loadMoreRef.current = loadMore;
  const retryMore = () => { setAppendError(undefined); loadMoreRef.current(true); };
  const loadAccounts = () => { accountsAbortController.current?.abort(); const controller = new AbortController(); accountsAbortController.current = controller; const requestId = ++accountsRequestId.current; fetchCashAccounts(controller.signal).then((value) => { if (requestId === accountsRequestId.current) { setAccounts(value); setAccountsError(false); } }).catch(() => { if (!controller.signal.aborted && requestId === accountsRequestId.current) setAccountsError(true); }); };
  useEffect(() => { loadAccounts(); return () => accountsAbortController.current?.abort(); }, []);
  useEffect(() => { resetAndLoad(); return () => pageAbortController.current?.abort(); }, [filters.date_from, filters.date_to, filters.account_id, filters.counterparty, filters.category_id, filters.uncategorized, filters.currency, filters.amount_min, filters.amount_max, filters.economic_type, filters.transfer_subtype, filters.composition, refreshGeneration]);
  useEffect(() => { if (selected || !restoreEvidenceFocus.current) return; restoreEvidenceFocus.current = false; opener.current?.focus(); }, [selected]);
  useEffect(() => { if (projectionUpdated && status === "ready") updateConfirmation.current?.focus(); }, [projectionUpdated, status]);
  const confirmUpdatedList = () => { setProjectionUpdated(false); requestAnimationFrame(() => document.querySelector<HTMLButtonElement>(".cash-row .icon-button")?.focus()); };
  const updateFilters = (value: CashFilters) => { setSelectedIds(new Set()); setBatchError(undefined); setFilters(value); };
  const clearAmountFilterState = () => setAmountFilterState(undefined);
  const toggleSelection = (item: CashProjection) => setSelectedIds((current) => {
    const next = new Set(current);
    if (next.has(item.projection_id)) next.delete(item.projection_id); else next.add(item.projection_id);
    return next;
  });
  const toggleAllSelection = (checked: boolean) => setSelectedIds(checked ? new Set(items.map((item) => item.projection_id)) : new Set());
  const openBatchClassification = () => { setBatchCategoryId(null); setBatchError(undefined); setBatchOpen(true); };
  const closeBatchClassification = () => { if (!batchSaving) setBatchOpen(false); };
  const saveBatchClassification = async () => {
    if (!projectionVersion || !selectedIds.size) return;
    setBatchSaving(true); setBatchError(undefined);
    try {
      await classifyCashProjections(Array.from(selectedIds), projectionVersion, batchCategoryId);
      setBatchOpen(false); setBatchSaving(false); setSelectedIds(new Set()); setRefreshGeneration((value) => value + 1);
    } catch (error) {
      setBatchSaving(false);
      if (error instanceof Error && error.message === "projection.version_conflict") {
        batchErrorAfterRefresh.current = "列表已更新，请重新选择记录。"; setBatchOpen(false); setSelectedIds(new Set()); setRefreshGeneration((value) => value + 1);
      } else setBatchError("保存失败，请稍后重试。");
    }
  };
  const openEvidence = (projection: CashProjection, source: HTMLButtonElement) => { evidenceAbortController.current?.abort(); const controller = new AbortController(); evidenceAbortController.current = controller; const requestId = ++evidenceRequestId.current; opener.current = source; setSelected(projection); setEvidence(null); setRecordDetail(null); setEvidenceState("loading"); fetchEvidence(projection.projection_id, controller.signal).then((value) => { if (requestId === evidenceRequestId.current) { setEvidence(value); setEvidenceState("ready"); } }).catch(() => { if (!controller.signal.aborted && requestId === evidenceRequestId.current) setEvidenceState("error"); }); };
  const closeEvidence = () => { evidenceAbortController.current?.abort(); evidenceRequestId.current += 1; restoreEvidenceFocus.current = true; opener.current?.focus(); setSelected(null); setEvidence(null); setRecordDetail(null); setRecordLoading(false); setRecordLoadError(false); };
  const loadEditor = (id: string | null, openRelation = false) => {
    const evidenceCached = id && evidence ? detailFromEvidence(evidence, id, ledgerOptions) : null;
    const stateCached = id && recordDetail?.record.id === id ? recordDetail : null;
    const cached = stateCached ?? evidenceCached;
    setEditingId(id); setCreating(id === null); setRelationComposerOpen(openRelation); setRecordDetail(cached); setRecordLoading(Boolean(id && !cached)); setRecordLoadError(false);
    if (!ledgerOptions.record_types.length || !ledgerOptions.relation_types.length) {
      fetchLedgerOptions().then((value) => { if (value.record_types && value.relation_types) setLedgerOptions(value); }).catch(() => undefined);
    }
    if (id && !cached) fetchCashRecord(id).then((value) => { setRecordDetail(value); setRecordLoading(false); }).catch(() => { setRecordLoadError(true); setRecordLoading(false); });
  };
  const openEditor = (id: string) => { loadEditor(id); };
  const openRelationEditor = (id: string) => { loadEditor(id, true); };
  const openNew = () => loadEditor(null);
  const handleRecordSaved = (value: CashRecordDetail, created: boolean) => { setRecordDetail(value); setRefreshGeneration((current) => current + 1); if (selected) returnToEvidence(); else if (created) { setCreating(false); setEditingId(null); } };
  const returnToEvidence = () => {
    if (!selected) return;
    const current = selected;
    setEditingId(null); setCreating(false); setRelationComposerOpen(false); setRecordDetail(null); setRecordLoading(false); setRecordLoadError(false);
    openEvidence(current, opener.current ?? document.createElement("button"));
  };
  const retryEditor = () => { if (editingId) loadEditor(editingId); };
  const handleCancelRelation = async (id: string) => { try { await cancelCashRelation(id); setRefreshGeneration((current) => current + 1); returnToEvidence(); } catch { /* drawer keeps the current state and can retry */ } };
  const handleRecordDeleted = (id: string) => { if (selected) closeEvidence(); setEditingId(null); setCreating(false); setRelationComposerOpen(false); setRecordDetail(null); setRecordLoading(false); setRecordLoadError(false); setRefreshGeneration((current) => current + 1); void id; };

  const drawerOpen = Boolean(selected || editingId || creating || importing);
  const editingSelected = Boolean(selected && (editingId || creating));
  const availableCategories = categories.length ? categories : filterOptions.categories;
  return <div className="page-layout"><main className="app-shell" inert={drawerOpen || batchOpen || undefined}><aside className="sidebar"><strong>Finance Tracker</strong><nav aria-label="主要导航"><a aria-current="page" href="/">收支账本</a><a href="/cash-categories">分类管理</a></nav></aside><section className="ledger ledger-workbench" id="cash-ledger" aria-label="收支账本"><header className="page-header"><div><h1>收支账本</h1></div><div className="page-header-actions"><button type="button" className="button-secondary" onClick={openNew}>新建流水</button><button type="button" className="button-primary" onClick={() => setImporting(true)}>导入账单</button></div></header>{accountsError ? <div className="status-view status-error" data-status-kind="error" role="alert"><p>无法读取账户，请稍后重试。</p><button type="button" onClick={loadAccounts}>重试账户</button></div> : null}{batchError ? <div className="batch-error" role="alert">{batchError}</div> : null}<CashFiltersBar filters={filters} accounts={accounts} filterOptions={filterOptions} filterOptionsReady={filterOptionsReady} filterOptionsLoading={filterOptionsLoading} amountFilterState={amountFilterState} onChange={updateFilters} onAmountFilterChange={clearAmountFilterState} />{status === "loading" ? <><CashTable items={[]} loading onEvidence={openEvidence} /><StatusView kind="loading" message={projectionUpdated ? "账本已更新，正在刷新记录。" : undefined} /></> : null}{status === "empty" ? <StatusView kind="empty" /> : null}{status === "error" ? <StatusView kind="error" message={errorMessage} onRetry={resetAndLoad} /> : null}{status === "ready" ? <>{projectionUpdated ? <div className="update-notice" role="status"><p>账本已更新，已刷新记录。</p><button ref={updateConfirmation} type="button" onClick={confirmUpdatedList}>查看更新后的列表</button></div> : null}<CashTable items={items} monthlySummaries={monthlySummaries} selectable selectedIds={selectedIds} onToggleSelection={toggleSelection} onToggleAll={toggleAllSelection} onEvidence={openEvidence} /><LoadMoreControl hasMore={Boolean(nextCursor)} loading={appendLoading} error={appendError} onLoadMore={appendError ? retryMore : loadMore} />{selectedIds.size ? <div className="batch-toolbar" role="toolbar" aria-label="批量操作"><span>已选 {selectedIds.size} 项</span><button type="button" className="button-primary" onClick={openBatchClassification}>修改分类</button><button type="button" className="button-secondary" onClick={() => setSelectedIds(new Set())}>取消选择</button></div> : null}</> : null}</section></main>{selected ? <EvidenceDetail evidence={evidence} loading={evidenceState === "loading"} error={evidenceState === "error"} editing={editingSelected} editMode={creating ? "new" : "edit"} editDetail={recordDetail} editAccounts={accounts} editOptions={ledgerOptions} categories={categories} projectionVersion={evidence?.projection_version ?? projectionVersion} editRelationOpen={relationComposerOpen} editLoading={recordLoading} editLoadError={recordLoadError} onClose={editingSelected ? returnToEvidence : closeEvidence} onRetry={() => openEvidence(selected, opener.current ?? document.createElement("button"))} onEditRetry={retryEditor} onRecordSaved={handleRecordSaved} onEditRecord={openEditor} onAddRelation={openRelationEditor} onCancelRelation={handleCancelRelation} onRecordDeleted={handleRecordDeleted} /> : null}{!selected && (editingId || creating) ? <RecordDrawer mode={creating ? "new" : "edit"} detail={recordDetail} accounts={accounts} options={ledgerOptions} categories={categories} projectionVersion={projectionVersion} loading={recordLoading} loadError={recordLoadError} onRetry={retryEditor} onClose={() => { setEditingId(null); setCreating(false); setRelationComposerOpen(false); setRecordDetail(null); setRecordLoading(false); setRecordLoadError(false); }} onSaved={handleRecordSaved} onDeleted={handleRecordDeleted} /> : null}{importing ? <ImportDrawer onClose={() => { setImporting(false); }} onDone={() => { setImporting(false); setRefreshGeneration((current) => current + 1); }} /> : null}{batchOpen ? <div className="evidence-layer batch-layer"><button type="button" className="evidence-backdrop" aria-label="关闭修改分类" onClick={closeBatchClassification} /><aside className="evidence batch-dialog" role="dialog" aria-modal="true" aria-label="修改分类"><header><div><h2>修改分类</h2></div><button type="button" className="icon-only-button" aria-label="关闭修改分类" onClick={closeBatchClassification}>×</button></header><div className="batch-dialog-content"><label htmlFor="batch-category">分类<select id="batch-category" aria-label="分类" value={batchCategoryId ?? ""} onChange={(event) => setBatchCategoryId(event.target.value || null)}><option value="">无分类</option>{availableCategories.map((category) => <option key={category.id} value={category.id}>{category.path.map((item) => item.name).join(" / ")}</option>)}</select></label><div className="drawer-actions"><button type="button" className="button-secondary" onClick={closeBatchClassification} disabled={batchSaving}>取消</button><button type="button" className="button-primary" onClick={() => void saveBatchClassification()} disabled={batchSaving || !projectionVersion}>{batchSaving ? "保存中…" : "保存"}</button></div></div></aside></div> : null}</div>;
}
