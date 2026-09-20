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
import type { Account, CashCategory, CashRecord, CashRecordDetail, LedgerOptions } from "../api/types";
import { CashCategorySelect } from "./CashCategorySelect";
import { formatOccurredAt } from "../format";
import { UiIcon } from "./UiIcon";
import { PageNavigation } from "./Pagination";
import { copy, semanticIds } from "@finance-tracker/presentation";

type Props = {
  detail?: CashRecordDetail | null;
  mode?: "new" | "edit";
  embedded?: boolean;
  loading?: boolean;
  loadError?: boolean;
  initialRelationOpen?: boolean;
  initialDeleteOpen?: boolean;
  accounts: Account[];
  options: LedgerOptions;
  categories?: CashCategory[];
  projectionVersion?: number | null;
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

type ComponentDraft = { account_name: string; amount: string };

function componentDrafts(record: CashRecord | null | undefined, defaultAccount?: Account): ComponentDraft[] {
  if (record?.components?.length) return record.components.map((component) => ({ account_name: component.account_name, amount: component.amount }));
  return [{ account_name: record?.account_name ?? defaultAccount?.name ?? "", amount: record?.amount ?? "0" }];
}

function decimalParts(value: string): { integer: bigint; scale: number } | null {
  const trimmed = value.trim();
  if (!/^[+-]?\d+(?:\.\d+)?$/.test(trimmed)) return null;
  const sign = trimmed.startsWith("-") ? -1n : 1n;
  const unsigned = trimmed.replace(/^[+-]/, "");
  const [whole, fraction = ""] = unsigned.split(".");
  return { integer: sign * BigInt(`${whole}${fraction}`), scale: fraction.length };
}

function decimalEqualsTotal(values: string[], total: string): boolean {
  const parsed = [...values, total].map(decimalParts);
  if (parsed.some((item) => !item)) return false;
  const scale = Math.max(...parsed.map((item) => item!.scale));
  const scaled = parsed.map((item) => item!.integer * 10n ** BigInt(scale - item!.scale));
  return scaled.slice(0, -1).reduce((sum, value) => sum + value, 0n) === scaled.at(-1)!;
}

function initialForm(record: CashRecord | null | undefined, defaultAccount?: Account, defaultRecordType?: string): Record<string, string> {
  return {
    account_name: record?.account_name ?? defaultAccount?.name ?? "",
    amount: record?.amount ?? "0",
    currency: record?.currency ?? defaultAccount?.currencies?.[0] ?? "",
    occurred_at: record?.occurred_at ? record.occurred_at.slice(0, 16) : "",
    counterparty: record?.counterparty ?? "",
    counterparty_account: record?.counterparty_account ?? "",
    category_id: record?.category_id ?? record?.category?.id ?? "",
    record_type: record?.record_type ?? defaultRecordType ?? "",
    record_subtype: record?.record_subtype ?? "not_applicable",
    note: record?.note ?? "",
  };
}

export function RecordDrawer({ detail, mode, embedded = false, loading = false, loadError = false, initialRelationOpen = false, initialDeleteOpen = false, accounts, options, categories = [], projectionVersion = null, onClose, onRetry, onSaved, onDeleted }: Props) {
  const record = detail?.record;
  const isNew = mode ? mode === "new" : !record;
  const [form, setForm] = useState(() => initialForm(record, accounts[0], options.record_types[0]?.value));
  const [components, setComponents] = useState<ComponentDraft[]>(() => componentDrafts(record, accounts[0]));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const [deleteOpen, setDeleteOpen] = useState(initialDeleteOpen);
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
    setComponents(componentDrafts(record, accounts[0]));
    setRelationImpactOpen(false);
    setDeleteOpen(initialDeleteOpen);
  }, [initialDeleteOpen, record?.id]);
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

  const account = accounts.find((item) => item.name === form.account_name) ?? accounts.find((item) => item.name === components[0]?.account_name);
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
    setComponents((current) => current.length === 1 ? [{ ...current[0], account_name: value }] : current);
  };
  const selectType = (value: string) => {
    const type = options.record_types.find((item) => item.value === value);
    setForm((current) => ({ ...current, record_type: value, record_subtype: type?.subtypes[0]?.value ?? "not_applicable" }));
  };

  const save = async (confirmRelationImpact = false) => {
    if (!components.length || components.some((component) => !component.account_name.trim()) || !decimalEqualsTotal(components.map((component) => component.amount), form.amount)) {
      setError("请补齐账户分配，并确保金额合计等于流水金额。");
      return;
    }
    setSaving(true); setError(undefined);
    try {
      const body = {
        ...form,
        account_name: components.length === 1 ? components[0].account_name : "",
        components: components.map((component) => ({ account_name: component.account_name, amount: component.amount })),
        amount: form.amount || "0",
        record_subtype: form.record_subtype || "not_applicable",
        ...(!isNew && form.category_id !== (record?.category_id ?? record?.category?.id ?? "") && projectionVersion !== null ? { projection_version: projectionVersion } : {}),
        ...(confirmRelationImpact ? { confirm_relation_impact: true } : {}),
      };
      const value = isNew ? await createCashRecord(body) : await updateCashRecord(record!.id, body);
      onSaved(value, isNew);
    } catch (cause) {
      if (cause instanceof Error && cause.message === "relation_impact_required") {
        setRelationImpactOpen(true);
      } else {
        setError(cause instanceof Error && cause.message === "invalid_record"
          ? "请检查标记的字段。"
            : cause instanceof Error && cause.message === "cash_transaction_components_in_use"
            ? copy.record.allocationInUseError
            : "保存失败，请稍后重试。" );
      }
    } finally { setSaving(false); }
  };

  const updateComponent = (index: number, field: keyof ComponentDraft, value: string) => {
    setComponents((current) => current.map((component, currentIndex) => currentIndex === index ? { ...component, [field]: value } : component));
  };
  const addComponent = () => setComponents((current) => [...current, { account_name: "", amount: "" }]);
  const removeComponent = (index: number) => setComponents((current) => current.length > 1 ? current.filter((_, currentIndex) => currentIndex !== index) : current);
  const allocationMatches = components.length > 0 && components.every((component) => component.account_name.trim()) && decimalEqualsTotal(components.map((component) => component.amount), form.amount);

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
        <div><p className="evidence-eyebrow">{copy.ledger.title}</p><h2>{isNew ? copy.record.newTitle : copy.record.editTitle}</h2></div>
        <button data-testid={semanticIds.recordCancel} type="button" className="icon-only-button" aria-label={embedded ? copy.common.back : "关闭"} title={embedded ? copy.common.back : "关闭"} autoFocus={embedded} onClick={onClose}><UiIcon name={embedded ? "arrow-left" : "x"} /></button>
      </header>
      <div className="evidence-content">
        {!isNew && !record ? <p className="evidence-state" role={loadError ? "alert" : "status"}>{loadError ? <>无法读取流水，请重试。<br /><button type="button" onClick={onRetry}>重试</button></> : loading ? "正在读取流水…" : "正在准备流水…"}</p> : <>
        {error ? <p className="form-error" role="alert">{error}</p> : null}
        <section className="evidence-section record-form-section" data-testid={semanticIds.recordScreen} aria-label={copy.record.editTitle}>
            <div className="drawer-summary record-edit-summary" aria-label={copy.record.amount}>
            <div className="summary-edit"><input data-testid={semanticIds.recordAmount} aria-label={copy.record.amount} className="mono" inputMode="decimal" value={form.amount} onChange={(event) => set("amount", event.target.value)} /><select data-testid={semanticIds.recordCurrency} aria-label={copy.record.currency} value={form.currency} onChange={(event) => set("currency", event.target.value)} disabled={!currencies.length}>{currencies.map((item) => <option key={item} value={item}>{item}</option>)}</select></div>
          </div>
          <div className="edit-fields">
            <div className="edit-row"><label className="edit-field-label" htmlFor="record-counterparty">{copy.record.counterparty}</label><input id="record-counterparty" value={form.counterparty} onChange={(event) => set("counterparty", event.target.value)} /></div>
            <div className="edit-row"><label className="edit-field-label" htmlFor="record-counterparty-account">{copy.record.counterpartyAccount}</label><input id="record-counterparty-account" value={form.counterparty_account} onChange={(event) => set("counterparty_account", event.target.value)} /></div>
            <div className="edit-row"><label className="edit-field-label" htmlFor="record-occurred-at"><UiIcon name="calendar" /><span>{copy.record.occurredAt}</span></label><input id="record-occurred-at" type="datetime-local" value={form.occurred_at} onChange={(event) => set("occurred_at", event.target.value)} /></div>
            <div data-testid={semanticIds.recordAllocation} className="edit-row component-editor-row"><span className="edit-field-label"><UiIcon name="account" /><span>{copy.record.allocation}</span></span><div className="component-editor">{components.map((component, index) => <div className="component-editor-item" key={`${index}-${component.account_name}`}><select data-testid={index === 0 ? semanticIds.recordAccount : undefined} aria-label={index === 0 ? copy.record.account : `分配项${index + 1}`} value={component.account_name} onChange={(event) => updateComponent(index, "account_name", event.target.value)}><option value="">选择账户</option>{accounts.map((item) => <option key={item.id} value={item.name}>{item.name}</option>)}</select><input className="mono" inputMode="decimal" aria-label={`分配项${index + 1}`} value={component.amount} onChange={(event) => updateComponent(index, "amount", event.target.value)} /><button type="button" className="icon-only-button icon-quiet-button" aria-label={copy.record.allocationRemove.replace("账户", "")} title={copy.record.allocationRemove} disabled={components.length <= 1} onClick={() => removeComponent(index)}><UiIcon name="x" /></button></div>)}<button type="button" className="text-button component-add-button" onClick={addComponent}>{copy.record.allocationAdd}</button><p className={`component-total ${allocationMatches ? "is-complete" : "is-incomplete"}`} role="status">{allocationMatches ? copy.record.allocationMatch : copy.record.allocationIncomplete}</p></div></div>
            <div className="edit-row"><label className="edit-field-label" htmlFor="record-type"><UiIcon name="receipt" /><span>{copy.record.type}</span></label><select id="record-type" value={form.record_type} onChange={(event) => selectType(event.target.value)}>{options.record_types.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}</select></div>
            {subtypeOptions.length > 1 ? <div className="edit-row"><label className="edit-field-label" htmlFor="record-subtype"><UiIcon name="layers" /><span>业务细分</span></label><select id="record-subtype" value={form.record_subtype} onChange={(event) => set("record_subtype", event.target.value)}>{subtypeOptions.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}</select></div> : null}
            <div className="edit-row category-edit-row"><CashCategorySelect testID={semanticIds.recordCategory} categories={categories} value={form.category_id || null} onChange={(value) => set("category_id", value ?? "")} /></div>
            <div className="edit-row"><label className="edit-field-label" htmlFor="record-note"><span>{copy.record.note}</span></label><textarea id="record-note" value={form.note} onChange={(event) => set("note", event.target.value)} /></div>
            {!isNew && record?.source_type ? <div className="edit-row"><span className="edit-field-label"><UiIcon name="layers" /><span>{copy.record.source}</span></span><div className="readonly-value">{record.source_type}</div></div> : null}
          </div>
          {!currencies.length ? <p className="field-hint">该账户暂未配置可用币种。</p> : null}
          <div className="drawer-actions"><button data-testid={semanticIds.recordSave} type="button" className="button-primary" disabled={saving || !form.currency || !allocationMatches} onClick={() => save()}>{saving ? copy.record.saving : copy.record.save}</button>{!isNew ? <button type="button" className="button-danger" onClick={() => setDeleteOpen(true)}>{copy.record.delete}</button> : null}</div>
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
      {deleteOpen && record ? <div className="confirm-layer" role="alertdialog" aria-label="删除流水确认"><div className="confirm-card"><h3>删除这条流水？</h3>{activeRelationCount ? <><p>这条流水已添加关联流水，请选择处理方式。</p><div className="drawer-actions delete-choice-actions"><button autoFocus type="button" onClick={() => setDeleteOpen(false)}>取消</button><button type="button" className="button-danger" disabled={saving} onClick={() => void confirmDelete("delete_current_dissolve")}>只删除当前流水并解散关联</button><button type="button" className="button-danger" disabled={saving} onClick={() => void confirmDelete("delete_all")}>删除全部流水</button></div></> : <><p>删除后将从账本中移除。</p><div className="drawer-actions"><button autoFocus type="button" onClick={() => setDeleteOpen(false)}>取消</button><button type="button" className="button-danger" disabled={saving} onClick={() => void confirmDelete("delete_current_dissolve")}>确认删除</button></div></>}</div></div> : null}
    </>;

  if (embedded) return drawerContent;
  return <div className="evidence-layer">
    <button type="button" className="evidence-backdrop" aria-label="点击遮罩关闭流水抽屉" onClick={onClose} />
    <aside className="evidence evidence-panel record-drawer" role="dialog" aria-modal="true" aria-label={isNew ? "新建流水" : "编辑收支详情"}>
      {drawerContent}
    </aside>
  </div>;
}
