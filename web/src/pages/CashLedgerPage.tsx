import { useEffect, useRef, useState } from "react";
import { fetchCashAccounts, fetchCashPage, fetchEvidence } from "../api/cashLedger";
import type { Account, CashFilters, CashProjection, Evidence } from "../api/types";
import { CashFiltersBar } from "../components/CashFilters";
import { CashTable } from "../components/CashTable";
import { EvidenceDetail } from "../components/EvidenceDetail";
import { LoadMoreControl } from "../components/Pagination";
import { StatusView } from "../components/StatusView";

const requestErrorMessages: Record<string, string> = {
  api_origin_invalid: "前端 API 地址无效。请设置 VITE_FT_API_ORIGIN 后重启。",
  "storage.busy": "账本正被其他操作占用，请稍后重试。",
  "storage.readonly": "账本当前不可读取，请检查本机 API 配置后重试。",
  "storage.connect": "无法连接本机账本，请检查 API 和数据库连接后重试。",
  "storage.schema": "账本结构不可用，请检查本机 API 配置后重试。",
  "storage.workspace": "当前工作区不可用，请检查本机 API 配置后重试。",
  "storage.config": "账本配置无效，请检查本机 API 配置后重试。",
  "projection.unavailable": "收支投影暂不可用，请先完成重建。",
  invalid_filter: "筛选条件有误，请检查日期、金额和选项后重试。",
  invalid_cursor: "加载位置已失效，请重新读取记录。",
  api_request_failed: "请求失败，请稍后重试。",
};

export function CashLedgerPage() {
  const [filters, setFilters] = useState<CashFilters>({});
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [accountsError, setAccountsError] = useState(false);
  const [items, setItems] = useState<CashProjection[]>([]);
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

  const resetAndLoad = () => {
    pageAbortController.current?.abort();
    appendingCursor.current = null;
    const controller = new AbortController(); pageAbortController.current = controller;
    const requestId = ++pageRequestId.current;
    setStatus("loading"); setErrorMessage(undefined); setAppendError(undefined); setAppendLoading(false); setItems([]); setNextCursor(null);
    fetchCashPage(filters, null, controller.signal).then((value) => {
      if (requestId !== pageRequestId.current) return;
      setItems(value.items); setNextCursor(value.next_cursor); setAmountFilterState(filters.amount_min || filters.amount_max ? "success" : undefined); setStatus(value.items.length ? "ready" : "empty");
    }).catch((error: unknown) => {
      if (controller.signal.aborted || requestId !== pageRequestId.current) return;
      if (error instanceof Error && error.message === "projection.updated") {
        closeEvidence(); setProjectionUpdated(true); setRefreshGeneration((value) => value + 1); return;
      }
      const code = error instanceof Error ? error.message : "api_request_failed";
      setAmountFilterState(code === "invalid_filter" ? "error" : undefined);
      setErrorMessage(requestErrorMessages[code] ?? requestErrorMessages.api_request_failed); setStatus("error");
    });
  };

  const loadMore = (retry = false) => {
    const cursor = nextCursor;
    if (!cursor || appendLoading || (!retry && appendError) || appendingCursor.current === cursor) return;
    appendingCursor.current = cursor;
    const controller = new AbortController(); pageAbortController.current = controller;
    const requestId = ++pageRequestId.current;
    setAppendLoading(true);
    fetchCashPage(filters, cursor, controller.signal).then((value) => {
      if (requestId !== pageRequestId.current) return;
      setItems((current) => [...current, ...value.items.filter((item) => !current.some((old) => old.projection_id === item.projection_id))]);
      setNextCursor(value.next_cursor); setAppendLoading(false); appendingCursor.current = null;
    }).catch((error: unknown) => {
      if (controller.signal.aborted || requestId !== pageRequestId.current) return;
      const code = error instanceof Error ? error.message : "api_request_failed";
      setAppendError(requestErrorMessages[code] ?? requestErrorMessages.api_request_failed); setAppendLoading(false); appendingCursor.current = null;
    });
  };
  loadMoreRef.current = loadMore;
  const retryMore = () => { setAppendError(undefined); loadMoreRef.current(true); };
  const loadAccounts = () => { accountsAbortController.current?.abort(); const controller = new AbortController(); accountsAbortController.current = controller; const requestId = ++accountsRequestId.current; fetchCashAccounts(controller.signal).then((value) => { if (requestId === accountsRequestId.current) { setAccounts(value); setAccountsError(false); } }).catch(() => { if (!controller.signal.aborted && requestId === accountsRequestId.current) setAccountsError(true); }); };
  useEffect(() => { loadAccounts(); return () => accountsAbortController.current?.abort(); }, []);
  useEffect(() => { resetAndLoad(); return () => pageAbortController.current?.abort(); }, [filters.date_from, filters.date_to, filters.account_id, filters.counterparty, filters.category, filters.currency, filters.amount_min, filters.amount_max, filters.economic_type, filters.composition, refreshGeneration]);
  useEffect(() => { if (selected || !restoreEvidenceFocus.current) return; restoreEvidenceFocus.current = false; opener.current?.focus(); }, [selected]);
  useEffect(() => { if (projectionUpdated && status === "ready") updateConfirmation.current?.focus(); }, [projectionUpdated, status]);
  const confirmUpdatedList = () => { setProjectionUpdated(false); requestAnimationFrame(() => document.querySelector<HTMLButtonElement>(".cash-row .icon-button")?.focus()); };
  const updateFilters = (value: CashFilters) => setFilters(value);
  const clearAmountFilterState = () => setAmountFilterState(undefined);
  const openEvidence = (projection: CashProjection, source: HTMLButtonElement) => { evidenceAbortController.current?.abort(); const controller = new AbortController(); evidenceAbortController.current = controller; const requestId = ++evidenceRequestId.current; opener.current = source; setSelected(projection); setEvidence(null); setEvidenceState("loading"); fetchEvidence(projection.projection_id, controller.signal).then((value) => { if (requestId === evidenceRequestId.current) { setEvidence(value); setEvidenceState("ready"); } }).catch(() => { if (!controller.signal.aborted && requestId === evidenceRequestId.current) setEvidenceState("error"); }); };
  const closeEvidence = () => { evidenceAbortController.current?.abort(); evidenceRequestId.current += 1; restoreEvidenceFocus.current = true; setSelected(null); setEvidence(null); };

  return <div className={`page-layout${selected ? " evidence-open" : ""}`}><main className="app-shell" inert={Boolean(selected) || undefined}><aside className="sidebar"><strong>Finance Tracker</strong><nav aria-label="主要导航"><a aria-current="page" href="#cash-ledger">收支账本</a></nav></aside><section className="ledger ledger-workbench" id="cash-ledger" aria-label="收支账本工作台"><header className="page-header"><div><h1>收支账本</h1></div></header>{accountsError ? <div className="status-view status-error" data-status-kind="error" role="alert"><p>无法读取账户目录。请检查本机 API 后重试。</p><button type="button" onClick={loadAccounts}>重试账户目录</button></div> : null}<CashFiltersBar filters={filters} accounts={accounts} amountFilterState={amountFilterState} onChange={updateFilters} onAmountFilterChange={clearAmountFilterState} />{status === "loading" ? <><CashTable items={[]} loading onEvidence={openEvidence} /><StatusView kind="loading" message={projectionUpdated ? "账本已更新，正在刷新记录。" : undefined} /></> : null}{status === "empty" ? <StatusView kind="empty" /> : null}{status === "error" ? <StatusView kind="error" message={errorMessage} onRetry={resetAndLoad} /> : null}{status === "ready" ? <>{projectionUpdated ? <div className="update-notice" role="status"><p>账本已更新，已刷新记录。</p><button ref={updateConfirmation} type="button" onClick={confirmUpdatedList}>查看更新后的列表</button></div> : null}<CashTable items={items} onEvidence={openEvidence} /><LoadMoreControl hasMore={Boolean(nextCursor)} loading={appendLoading} error={appendError} onLoadMore={appendError ? retryMore : loadMore} /></> : null}</section></main>{selected ? <EvidenceDetail evidence={evidence} loading={evidenceState === "loading"} error={evidenceState === "error"} onClose={closeEvidence} onRetry={() => openEvidence(selected, opener.current ?? document.createElement("button"))} /> : null}</div>;
}
