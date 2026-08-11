import { useEffect, useState } from "react";
import {
  cancelCashRelation,
  createCashRecord,
  createCashRelation,
  deleteCashRecord,
  dissolveCashRelations,
  fetchCashRecords,
  updateCashRelation,
  updateCashRecord,
} from "../api/cashLedger";
import type { Account, CashRecord, CashRecordDetail, LedgerOptions } from "../api/types";
import { formatOccurredAt } from "../format";
import { UiIcon } from "./UiIcon";
import { PageNavigation } from "./Pagination";

type Props = {
  detail?: CashRecordDetail | null;
  mode?: "new" | "edit";
  embedded?: boolean;
  loading?: boolean;
  loadError?: boolean;
  initialRelationOpen?: boolean;
  accounts: Account[];
  options: LedgerOptions;
  onClose: () => void;
  onRetry?: () => void;
  onSaved: (detail: CashRecordDetail, created: boolean) => void;
  onDeleted: (id: string) => void;
};

function shiftDate(value: string, days: number): string {
  const date = new Date(`${value}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

function localCalendarDate(value: string): string {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value.slice(0, 10);
  const parts = new Intl.DateTimeFormat("en-CA", { year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(parsed);
  const values = Object.fromEntries(parts.filter((part) => part.type !== "literal").map((part) => [part.type, part.value]));
  return values.year && values.month && values.day ? `${values.year}-${values.month}-${values.day}` : value.slice(0, 10);
}

function relationDateRange(record?: CashRecord | null): { from: string; to: string } {
  const date = record?.occurred_at ? localCalendarDate(record.occurred_at) : "";
  return date ? { from: shiftDate(date, -3), to: shiftDate(date, 3) } : { from: "", to: "" };
}

function browserTimezone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
}

function initialForm(record: CashRecord | null | undefined, defaultAccount?: Account, defaultRecordType?: string): Record<string, string> {
  return {
    account_name: record?.account_name ?? defaultAccount?.name ?? "",
    amount: record?.amount ?? "0",
    currency: record?.currency ?? defaultAccount?.currencies?.[0] ?? "",
    occurred_at: record?.occurred_at ? record.occurred_at.slice(0, 16) : "",
    counterparty: record?.counterparty ?? "",
    counterparty_account: record?.counterparty_account ?? "",
    category: record?.category ?? "",
    record_type: record?.record_type ?? defaultRecordType ?? "",
    record_subtype: record?.record_subtype ?? "not_applicable",
    note: record?.note ?? "",
  };
}

export function RecordDrawer({ detail, mode, embedded = false, loading = false, loadError = false, initialRelationOpen = false, accounts, options, onClose, onRetry, onSaved, onDeleted }: Props) {
  const record = detail?.record;
  const isNew = mode ? mode === "new" : !record;
  const [form, setForm] = useState(() => initialForm(record, accounts[0], options.record_types[0]?.value));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [relationImpactOpen, setRelationImpactOpen] = useState(false);
  const [relationOpen, setRelationOpen] = useState(initialRelationOpen);
  const [relationQuery, setRelationQuery] = useState("");
  const [relationDateFrom, setRelationDateFrom] = useState(() => relationDateRange(record).from);
  const [relationDateTo, setRelationDateTo] = useState(() => relationDateRange(record).to);
  const [relationCandidates, setRelationCandidates] = useState<CashRecord[]>([]);
  const [relationNextCursor, setRelationNextCursor] = useState<string | null>(null);
  const [relationLoading, setRelationLoading] = useState(false);
  const [relationPageNumber, setRelationPageNumber] = useState(1);
  const [relationPageStarts, setRelationPageStarts] = useState<(string | null)[]>([null]);
  const [relationErrorPage, setRelationErrorPage] = useState<number | null>(null);
  const [relationErrorCursor, setRelationErrorCursor] = useState<string | null>(null);
  const [relationLoadError, setRelationLoadError] = useState(false);
  const [relationReload, setRelationReload] = useState(0);
  const [relationTarget, setRelationTarget] = useState("");
  const [relationKind, setRelationKind] = useState(options.relation_types[0]?.value ?? "payment_mirror");
  const [relationSaving, setRelationSaving] = useState(false);
  const [editingRelationId, setEditingRelationId] = useState<string>();
  const [editingRelationKind, setEditingRelationKind] = useState<string>();

  useEffect(() => {
    setForm(initialForm(record, accounts[0], options.record_types[0]?.value));
    setRelationImpactOpen(false);
  }, [record?.id]);
  useEffect(() => {
    setRelationOpen(initialRelationOpen);
    setRelationQuery("");
    const range = relationDateRange(record);
    setRelationDateFrom(range.from);
    setRelationDateTo(range.to);
    setRelationCandidates([]);
    setRelationNextCursor(null);
    setRelationPageNumber(1);
    setRelationPageStarts([null]);
    setRelationErrorPage(null);
    setRelationErrorCursor(null);
    setRelationTarget("");
    setRelationLoadError(false);
  }, [initialRelationOpen, record?.id]);
  useEffect(() => {
    if (!isNew || form.account_name || !accounts.length) return;
    setForm((current) => ({ ...current, account_name: accounts[0].name, currency: accounts[0].currencies?.[0] ?? "" }));
  }, [accounts, form.account_name, isNew]);
  useEffect(() => {
    if (!isNew || !options.record_types.length || options.record_types.some((item) => item.value === form.record_type)) return;
    const first = options.record_types[0];
    setForm((current) => ({ ...current, record_type: first.value, record_subtype: first.subtypes[0]?.value ?? "not_applicable" }));
  }, [form.record_type, isNew, options.record_types]);

  const account = accounts.find((item) => item.name === form.account_name);
  const currencies = account?.currencies ?? [];
  const selectedType = options.record_types.find((item) => item.value === form.record_type);
  const subtypeOptions = selectedType?.subtypes ?? [];
  useEffect(() => {
    if (!relationOpen || !record || !relationDateFrom || !relationDateTo) return;
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      setRelationLoading(true);
      setRelationLoadError(false);
      fetchCashRecords({ query: relationQuery.trim(), excludeId: record.id, dateFrom: relationDateFrom, dateTo: relationDateTo, timezone: browserTimezone(), limit: 20 }, controller.signal)
        .then((value) => {
          setRelationCandidates(value.items);
          setRelationNextCursor(value.next_cursor ?? null);
          setRelationPageNumber(1);
          setRelationPageStarts([null, value.next_cursor ?? null]);
          setRelationErrorPage(null);
          setRelationErrorCursor(null);
          setRelationLoading(false);
        })
        .catch(() => {
          if (controller.signal.aborted) return;
          setRelationCandidates([]);
          setRelationNextCursor(null);
          setRelationLoadError(true);
          setRelationLoading(false);
        });
    }, relationQuery ? 250 : 0);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [record?.id, relationDateFrom, relationDateTo, relationOpen, relationQuery, relationReload]);

  const set = (key: string, value: string) => setForm((current) => ({ ...current, [key]: value }));
  const selectAccount = (value: string) => {
    const next = accounts.find((item) => item.name === value);
    setForm((current) => ({ ...current, account_name: value, currency: next?.currencies?.[0] ?? "" }));
  };
  const selectType = (value: string) => {
    const type = options.record_types.find((item) => item.value === value);
    setForm((current) => ({ ...current, record_type: value, record_subtype: type?.subtypes[0]?.value ?? "not_applicable" }));
  };

  const save = async (confirmRelationImpact = false) => {
    setSaving(true); setError(undefined);
    try {
      const body = {
        ...form,
        amount: form.amount || "0",
        record_subtype: form.record_subtype || "not_applicable",
        ...(confirmRelationImpact ? { confirm_relation_impact: true } : {}),
      };
      const value = isNew ? await createCashRecord(body) : await updateCashRecord(record!.id, body);
      onSaved(value, isNew);
    } catch (cause) {
      if (cause instanceof Error && cause.message === "relation_impact_required") {
        setRelationImpactOpen(true);
      } else {
        setError(cause instanceof Error && cause.message === "invalid_record" ? "请检查标记的字段。" : "保存失败，请稍后重试。" );
      }
    } finally { setSaving(false); }
  };

  const addRelation = async () => {
    if (!record || !relationTarget) return;
    setRelationSaving(true); setError(undefined);
    try {
      const value = await createCashRelation({ primary_fact_id: record.id, secondary_fact_id: relationTarget, kind: relationKind, status: "accepted" });
      onSaved(value, false);
      setRelationTarget("");
      setRelationOpen(false);
    } catch { setError("关联失败，请检查两条流水是否可以合并。" ); }
    finally { setRelationSaving(false); }
  };

  const loadRelationPage = async (targetPage: number, cursor: string | null) => {
    if (!record || relationLoading) return;
    setRelationLoading(true);
    setRelationLoadError(false);
    setRelationErrorPage(null);
    setRelationErrorCursor(null);
    try {
      const value = await fetchCashRecords({
        query: relationQuery.trim(),
        excludeId: record.id,
        dateFrom: relationDateFrom,
        dateTo: relationDateTo,
        timezone: browserTimezone(),
        cursor,
        limit: 20,
      });
      setRelationCandidates(value.items);
      setRelationNextCursor(value.next_cursor ?? null);
      setRelationPageNumber(targetPage);
      setRelationPageStarts((current) => {
        const starts = current.slice(0, targetPage);
        starts[targetPage] = value.next_cursor ?? null;
        return starts;
      });
    } catch {
      setRelationLoadError(true);
      setRelationErrorPage(targetPage);
      setRelationErrorCursor(cursor);
    } finally { setRelationLoading(false); }
  };

  const loadNextRelationPage = () => {
    if (relationNextCursor) void loadRelationPage(relationPageNumber + 1, relationNextCursor);
  };
  const loadPreviousRelationPage = () => {
    if (relationPageNumber > 1) void loadRelationPage(relationPageNumber - 1, relationPageStarts[relationPageNumber - 2] ?? null);
  };
  const retryRelationPage = () => {
    if (relationErrorPage !== null) void loadRelationPage(relationErrorPage, relationErrorCursor);
  };

  const closeRelationComposer = () => {
    setRelationOpen(false);
    setRelationQuery("");
    const range = relationDateRange(record);
    setRelationDateFrom(range.from);
    setRelationDateTo(range.to);
    setRelationCandidates([]);
    setRelationNextCursor(null);
    setRelationPageNumber(1);
    setRelationPageStarts([null]);
    setRelationErrorPage(null);
    setRelationErrorCursor(null);
    setRelationTarget("");
    setRelationLoadError(false);
  };

  const unlink = async (relationId: string) => {
    setRelationSaving(true); setError(undefined);
    try {
      await cancelCashRelation(relationId);
      if (record && detail) {
        onSaved({ ...detail, relations: detail.relations.map((item) => item.id === relationId ? { ...item, status: "rejected" as const } : item) }, false);
      }
    } catch { setError("取消关联失败，请稍后重试。" ); }
    finally { setRelationSaving(false); }
  };

  const dissolve = async () => {
    if (!record) return;
    setRelationSaving(true); setError(undefined);
    try {
      const value = await dissolveCashRelations(record.id);
      onSaved(value, false);
    } catch { setError("解散关联失败，请稍后重试。" ); }
    finally { setRelationSaving(false); }
  };

  const saveRelationType = async (relationId: string) => {
    if (!editingRelationKind) return;
    setRelationSaving(true); setError(undefined);
    try {
      const value = await updateCashRelation(relationId, { kind: editingRelationKind });
      onSaved(value, false);
      setEditingRelationId(undefined);
      setEditingRelationKind(undefined);
    } catch { setError("关联类型更新失败，请稍后重试。" ); }
    finally { setRelationSaving(false); }
  };

  const confirmDelete = async (mode: "delete_all" | "delete_current_dissolve") => {
    if (!record) return;
    setSaving(true); setError(undefined);
    try { await deleteCashRecord(record.id, mode); onDeleted(record.id); }
    catch { setError("删除失败，请稍后重试。" ); setSaving(false); }
  };

  const relations = detail?.relations.filter((item) => item.status === "accepted") ?? [];
  const activeRelationCount = relations.length;

  const drawerContent = <>
      <header>
        <div><p className="evidence-eyebrow">收支账本</p><h2>{isNew ? "新建流水" : "编辑收支详情"}</h2></div>
        <button type="button" className="icon-only-button" aria-label={embedded ? "返回" : "关闭"} title={embedded ? "返回" : "关闭"} autoFocus={embedded} onClick={onClose}><UiIcon name={embedded ? "arrow-left" : "x"} /></button>
      </header>
      <div className="evidence-content">
        {!isNew && !record ? <p className="evidence-state" role={loadError ? "alert" : "status"}>{loadError ? <>无法读取流水，请重试。<br /><button type="button" onClick={onRetry}>重试</button></> : loading ? "正在读取流水…" : "正在准备流水…"}</p> : <>
        {error ? <p className="form-error" role="alert">{error}</p> : null}
        <section className="evidence-section record-form-section" aria-label="编辑收支详情">
          <div className="drawer-summary record-edit-summary" aria-label="金额">
            <div className="summary-edit"><input aria-label="金额" className="mono" inputMode="decimal" value={form.amount} onChange={(event) => set("amount", event.target.value)} /><select aria-label="币种" value={form.currency} onChange={(event) => set("currency", event.target.value)} disabled={!currencies.length}>{currencies.map((item) => <option key={item} value={item}>{item}</option>)}</select></div>
          </div>
          <div className="edit-fields">
            <div className="edit-row"><label htmlFor="record-counterparty">交易对方</label><input id="record-counterparty" value={form.counterparty} onChange={(event) => set("counterparty", event.target.value)} /></div>
            <div className="edit-row"><label htmlFor="record-counterparty-account">对方账号</label><input id="record-counterparty-account" value={form.counterparty_account} onChange={(event) => set("counterparty_account", event.target.value)} /></div>
            <div className="edit-row"><label htmlFor="record-occurred-at">发生时间</label><input id="record-occurred-at" type="datetime-local" value={form.occurred_at} onChange={(event) => set("occurred_at", event.target.value)} /></div>
            <div className="edit-row"><label htmlFor="record-account">账户</label><select id="record-account" value={form.account_name} onChange={(event) => selectAccount(event.target.value)}>{accounts.map((item) => <option key={item.id} value={item.name}>{item.name}</option>)}</select></div>
            <div className="edit-row"><label htmlFor="record-type">流水类型</label><select id="record-type" value={form.record_type} onChange={(event) => selectType(event.target.value)}>{options.record_types.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}</select></div>
            {subtypeOptions.length > 1 ? <div className="edit-row"><label htmlFor="record-subtype">业务细分</label><select id="record-subtype" value={form.record_subtype} onChange={(event) => set("record_subtype", event.target.value)}>{subtypeOptions.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}</select></div> : null}
            <div className="edit-row"><label htmlFor="record-category">分类</label><input id="record-category" value={form.category} onChange={(event) => set("category", event.target.value)} /></div>
            <div className="edit-row"><label htmlFor="record-note">备注</label><textarea id="record-note" value={form.note} onChange={(event) => set("note", event.target.value)} /></div>
            {!isNew && record?.source_type ? <div className="edit-row"><span>来源</span><div className="readonly-value">{record.source_type}</div></div> : null}
          </div>
          {!currencies.length ? <p className="field-hint">该账户暂未配置可用币种。</p> : null}
          <div className="drawer-actions"><button type="button" className="button-primary" disabled={saving || !form.account_name || !form.currency} onClick={() => save()}>{saving ? "保存中…" : "保存"}</button>{!isNew ? <button type="button" className="button-danger" onClick={() => setDeleteOpen(true)}>删除流水</button> : null}</div>
        </section>
        {!isNew && record ? <section className="evidence-section evidence-related relation-manager" aria-label="关联流水">
          <div className="section-heading"><h3>关联流水</h3><div className="section-heading-actions">{relations.length > 0 ? <button type="button" className="text-button" disabled={relationSaving} onClick={dissolve}>解散关联</button> : null}{!relationOpen ? <button type="button" className="icon-only-button icon-quiet-button" aria-label="添加关联" title="添加关联" aria-expanded="false" onClick={() => setRelationOpen(true)}><UiIcon name="plus" /></button> : null}</div></div>
          {relations.length ? <ul className="evidence-record-list">{relations.map((item) => {
            const related = item.primary_record?.id === record.id ? item.secondary_record : item.primary_record;
            return <li className="evidence-record" key={item.id}>
              <div className="related-record-title"><strong>{item.label}</strong>{item.status === "pending_review" ? <span className="status-chip">待确认</span> : item.status === "rejected" ? <span className="status-chip muted">已取消</span> : null}</div>
              <dl><dt>金额</dt><dd>{related?.amount ?? "-"} {related?.currency ?? ""}</dd><dt>发生时间</dt><dd>{related?.occurred_at ? formatOccurredAt(related.occurred_at) : "-"}</dd><dt>账户</dt><dd>{related?.account_name ?? "-"}</dd><dt>交易对方</dt><dd>{related?.counterparty || "-"}</dd></dl>
              {editingRelationId === item.id ? <div className="relation-edit-actions"><select aria-label="更改关联类型" value={editingRelationKind ?? item.kind} onChange={(event) => setEditingRelationKind(event.target.value)}>{options.relation_types.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select><button type="button" className="text-button" disabled={relationSaving} onClick={() => saveRelationType(item.id)}>保存</button><button type="button" className="text-button" disabled={relationSaving} onClick={() => { setEditingRelationId(undefined); setEditingRelationKind(undefined); }}>取消</button></div> : <div className="related-actions">{item.status !== "rejected" ? <button type="button" className="text-button" disabled={relationSaving} onClick={() => { setEditingRelationId(item.id); setEditingRelationKind(item.kind); }}>更改类型</button> : null}{item.status !== "rejected" ? <button type="button" className="text-button" disabled={relationSaving} onClick={() => unlink(item.id)}>取消关联</button> : null}</div>}
            </li>;
          })}</ul> : !relationOpen ? <p className="empty-related">暂无关联流水</p> : null}
          {relationOpen ? <div className="relation-composer">
            <label className="relation-type-field" htmlFor="new-relation-kind">关联类型<select id="new-relation-kind" value={relationKind} onChange={(event) => setRelationKind(event.target.value)}>{options.relation_types.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}</select></label>
            <fieldset className="relation-date-range">
              <legend>时间范围</legend>
              <label htmlFor="relation-date-from">从<input id="relation-date-from" aria-label="开始日期" type="date" value={relationDateFrom} onChange={(event) => { setRelationDateFrom(event.target.value); setRelationTarget(""); }} /></label>
              <label htmlFor="relation-date-to">到<input id="relation-date-to" aria-label="结束日期" type="date" value={relationDateTo} onChange={(event) => { setRelationDateTo(event.target.value); setRelationTarget(""); }} /></label>
            </fieldset>
            <label className="relation-search-field" htmlFor="relation-search">搜索流水<input id="relation-search" type="search" placeholder="交易对方、账户或金额" value={relationQuery} onChange={(event) => { setRelationQuery(event.target.value); setRelationTarget(""); }} /></label>
            {relationLoading ? <div className="relation-search-state" role="status">正在搜索…</div> : null}
            {relationLoadError && !relationCandidates.length ? <div className="relation-search-state is-error" role="alert"><span>无法读取流水。</span><button type="button" className="text-button" onClick={() => setRelationReload((value) => value + 1)}>重试</button></div> : null}
            {!relationLoading && !relationLoadError && relationCandidates.length ? <div className="relation-candidate-list" role="radiogroup" aria-label="选择已有流水">{relationCandidates.map((item) => <button type="button" role="radio" aria-checked={relationTarget === item.id} className={`relation-candidate${relationTarget === item.id ? " is-selected" : ""}`} key={item.id} onClick={() => setRelationTarget(item.id)}><span className="relation-candidate-main"><strong>{item.counterparty || "未填写交易对方"}</strong><small>{item.account_name} · {formatOccurredAt(item.occurred_at)}</small></span><span className="relation-candidate-amount mono">{item.amount} {item.currency}</span></button>)}</div> : null}
            {!relationLoading && !relationLoadError && !relationCandidates.length ? <p className="relation-search-state">没有找到流水</p> : null}
            {relationCandidates.length ? <PageNavigation ariaLabel="关联流水分页" page={relationPageNumber} hasPrevious={relationPageNumber > 1} hasNext={Boolean(relationNextCursor)} loading={relationLoading} error={relationLoadError ? "无法读取流水，请重试。" : undefined} onPrevious={loadPreviousRelationPage} onNext={loadNextRelationPage} onRetry={retryRelationPage} /> : null}
            <div className="drawer-actions relation-composer-actions"><button type="button" className="button-secondary" onClick={closeRelationComposer}>取消</button><button type="button" className="button-primary" disabled={!relationTarget || relationSaving} onClick={addRelation}>{relationSaving ? "正在添加…" : "添加关联"}</button></div>
          </div> : null}
        </section> : null}
        </>}
      </div>
      {relationImpactOpen && record ? <div className="confirm-layer" role="alertdialog" aria-label="保存关联影响确认"><div className="confirm-card"><h3>保存并拆开关联流水？</h3><p>当前修改会让这组关联流水分开显示。</p><div className="drawer-actions"><button type="button" onClick={() => setRelationImpactOpen(false)}>取消</button><button type="button" className="button-primary" disabled={saving} onClick={() => { setRelationImpactOpen(false); void save(true); }}>保存并拆开</button></div></div></div> : null}
      {deleteOpen && record ? <div className="confirm-layer" role="alertdialog" aria-label="删除流水确认"><div className="confirm-card"><h3>删除这条流水？</h3>{activeRelationCount ? <><p>这条流水已添加关联流水，请选择处理方式。</p><div className="drawer-actions delete-choice-actions"><button type="button" onClick={() => setDeleteOpen(false)}>取消</button><button type="button" className="button-danger" disabled={saving} onClick={() => void confirmDelete("delete_current_dissolve")}>只删除当前流水并解散关联</button><button type="button" className="button-danger" disabled={saving} onClick={() => void confirmDelete("delete_all")}>删除全部流水</button></div></> : <><p>删除后将从账本中移除。</p><div className="drawer-actions"><button type="button" onClick={() => setDeleteOpen(false)}>取消</button><button type="button" className="button-danger" disabled={saving} onClick={() => void confirmDelete("delete_current_dissolve")}>确认删除</button></div></>}</div></div> : null}
    </>;

  if (embedded) return drawerContent;
  return <div className="evidence-layer">
    <button type="button" className="evidence-backdrop" aria-label="点击遮罩关闭流水抽屉" onClick={onClose} />
    <aside className="evidence evidence-panel record-drawer" role="dialog" aria-modal="true" aria-label={isNew ? "新建流水" : "编辑收支详情"}>
      {drawerContent}
    </aside>
  </div>;
}
