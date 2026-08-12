import { useMemo, useState } from "react";
import { commitCashImport, detectCashImport, previewCashImport } from "../api/cashLedger";
import type {
  ImportCommitResult,
  ImportDetection,
  ImportPreview,
  ImportPreviewItem,
  ImportRelation,
  ImportRelationRecord,
} from "../api/types";

type Stage = "select" | "preview" | "relations" | "success";
type RelationFilter = "all" | "automatic" | "pending";
type RelationState = "automatic" | "pending" | "accepted" | "rejected";

type RelationDraft = {
  state: RelationState;
  kind: string;
  secondary: ImportRelationRecord | null;
  restore?: {
    state: RelationState;
    kind: string;
    secondary: ImportRelationRecord | null;
  };
};

const PAGE_SIZES = [20, 50, 100];

const columnLabels: Record<string, string> = {
  occurred_at: "发生时间",
  amount: "金额",
  currency: "币种",
  account_name: "账户",
  counterparty: "交易对方",
  counterparty_account: "对方账号",
  record_type: "流水类型",
  record_subtype: "业务细分",
  category: "分类",
  note: "备注",
  channel: "渠道",
  status: "状态",
};

const recordTypeLabels: Record<string, string> = {
  consumption: "消费",
  refund: "退款",
  income: "收入",
  transfer_in: "转账入账",
  transfer_out: "转账转出",
  repayment: "还款",
  withdrawal_in: "提现入账",
  withdrawal_out: "提现",
  fx_in: "换汇转入",
  fx_out: "换汇转出",
  other: "其他",
};

const relationKindLabels: Record<string, string> = {
  payment_mirror: "同笔支付",
  refund_offset: "退款冲销",
  transfer_pair: "个人转账",
};

const relationStateLabels: Record<RelationState, string> = {
  automatic: "自动",
  pending: "待处理",
  accepted: "已配对",
  rejected: "已拒绝",
};

function displayValue(item: ImportPreviewItem, column: string): string {
  const value = item[column as keyof ImportPreviewItem];
  if (column === "record_type") return recordTypeLabels[String(value)] ?? String(value || "—");
  return value === undefined || value === "" ? "—" : String(value);
}

function recordDate(value: string): string {
  return value.replace("T", " ").slice(0, 16);
}

function relationRecordLabel(record: ImportRelationRecord): string {
  return `${record.counterparty || "未填写对方"} · ${record.amount} ${record.currency} · ${recordDate(record.occurred_at)}`;
}

function relationDraftFor(relation: ImportRelation): RelationDraft {
  return {
    state: relation.automatic ? "automatic" : "pending",
    kind: relation.kind,
    secondary: relation.automatic ? relation.secondary : null,
  };
}

function relationDecision(
  relation: ImportRelation,
  draft: RelationDraft,
): Record<string, unknown> | null {
  const endpoint = (
    record: ImportRelationRecord | null,
    factKey: "primary_fact_id" | "secondary_fact_id",
  ) => {
    if (!record) return {};
    return record.fact_id
      ? { [factKey]: record.fact_id }
      : { [`${factKey === "primary_fact_id" ? "primary" : "secondary"}_record_id`]: record.record_id };
  };
  const base = {
    kind: draft.kind,
    subtype: relation.subtype,
    rule_id: relation.rule_id,
    ...endpoint(relation.primary, "primary_fact_id"),
  };
  if (draft.state === "rejected") return { ...base, status: "rejected" };
  if (draft.state === "pending" || !draft.secondary) return null;
  return { ...base, ...endpoint(draft.secondary, "secondary_fact_id"), status: "accepted" };
}

function RelationRecord({ record }: { record: ImportRelationRecord | null }) {
  if (!record) return <span className="compact-record-empty">—</span>;
  return (
    <span className="compact-record">
      <strong>{record.counterparty || "未填写对方"}</strong>
      <small>{record.account_name} · {recordDate(record.occurred_at)}</small>
    </span>
  );
}

function RelationActionIcon({ undo }: { undo: boolean }) {
  return undo ? (
    <svg className="ui-icon" aria-hidden="true" viewBox="0 0 24 24">
      <path d="M9 8H5V4M5 8a7 7 0 1 1 1.8 7.4" />
    </svg>
  ) : (
    <svg className="ui-icon" aria-hidden="true" viewBox="0 0 24 24">
      <path d="M7 4h10M9 4v-1h6v1M5 6h14M8 6v14h8V6M10 10v7M14 10v7" />
    </svg>
  );
}

export function CashImportPage({ onBack, onDone }: { onBack: () => void; onDone?: () => void }) {
  const [stage, setStage] = useState<Stage>("select");
  const [file, setFile] = useState<File | null>(null);
  const [detection, setDetection] = useState<ImportDetection | null>(null);
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [relationDrafts, setRelationDrafts] = useState<Record<string, RelationDraft>>({});
  const [relationFilter, setRelationFilter] = useState<RelationFilter>("all");
  const [pageSize, setPageSize] = useState(20);
  const [page, setPage] = useState(1);
  const [result, setResult] = useState<ImportCommitResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  const chooseFile = async (nextFile: File | undefined) => {
    if (!nextFile) return;
    setFile(nextFile);
    setDetection(null);
    setPreview(null);
    setRelationDrafts({});
    setResult(null);
    setError(undefined);
    setStage("select");
    setBusy(true);
    try {
      setDetection(await detectCashImport(nextFile));
    } catch (cause) {
      setError(cause instanceof Error && cause.message === "import_channel_unrecognized"
        ? "无法识别账单渠道，请重新选择文件。"
        : "文件识别失败，请重试。");
    } finally {
      setBusy(false);
    }
  };

  const loadPreview = async () => {
    if (!file || !detection) return;
    setBusy(true);
    setError(undefined);
    try {
      setPreview(await previewCashImport(file));
      setRelationDrafts({});
      setStage("preview");
    } catch {
      setError("账单预览失败，请重试。");
    } finally {
      setBusy(false);
    }
  };

  const openRelations = () => {
    if (!preview) return;
    setRelationDrafts((current) => Object.fromEntries(
      preview.relations.map((relation) => [relation.id, current[relation.id] ?? relationDraftFor(relation)]),
    ));
    setRelationFilter("all");
    setPage(1);
    setStage("relations");
  };

  const updateDraft = (relation: ImportRelation, update: Partial<RelationDraft>) => {
    setRelationDrafts((current) => ({
      ...current,
      [relation.id]: { ...(current[relation.id] ?? relationDraftFor(relation)), ...update },
    }));
  };

  const setKind = (relation: ImportRelation, kind: string) => updateDraft(relation, { kind });

  const setSecondary = (relation: ImportRelation, value: string) => {
    const secondary = relation.candidates.find((candidate) => candidate.record_id === value) ?? null;
    updateDraft(relation, { state: secondary ? "accepted" : "pending", secondary });
  };

  const toggleRejected = (relation: ImportRelation) => {
    const current = relationDrafts[relation.id] ?? relationDraftFor(relation);
    if (current.state === "rejected") {
      const restored = current.restore ?? relationDraftFor(relation);
      updateDraft(relation, { ...restored, restore: undefined });
      return;
    }
    updateDraft(relation, {
      state: "rejected",
      restore: { state: current.state, kind: current.kind, secondary: current.secondary },
    });
  };

  const confirmImport = async () => {
    if (!file || !preview || preview.summary.unsupported > 0) return;
    setBusy(true);
    setError(undefined);
    const decisions = preview.relations.flatMap((relation) => {
      const draft = relationDrafts[relation.id] ?? relationDraftFor(relation);
      const decision = relationDecision(relation, draft);
      return decision ? [decision] : [];
    });
    try {
      const committed = await commitCashImport(file, "", undefined, {
        previewDigest: preview.file.digest,
        previewChannel: preview.channel,
        relations: decisions,
      });
      setResult(committed);
      setStage("success");
      onDone?.();
    } catch (cause) {
      setError(cause instanceof Error && cause.message === "import_preview_stale"
        ? "文件内容已经变化，请重新选择文件。"
        : cause instanceof Error && cause.message === "relation_impact_required"
          ? "这次导入会影响已关联的流水，请先处理关联。"
          : "确认导入失败，请重试。");
    } finally {
      setBusy(false);
    }
  };

  const relationItems = preview?.relations ?? [];
  const filteredRelations = useMemo(() => relationItems.filter((relation) => {
    if (relationFilter === "all") return true;
    const draft = relationDrafts[relation.id] ?? relationDraftFor(relation);
    if (relationFilter === "automatic") return relation.automatic;
    return draft.state === "pending";
  }), [relationDrafts, relationFilter, relationItems]);
  const pageTotal = Math.max(1, Math.ceil(filteredRelations.length / pageSize));
  const currentPage = Math.min(page, pageTotal);
  const visibleRelations = filteredRelations.slice((currentPage - 1) * pageSize, currentPage * pageSize);
  const pendingCount = relationItems.filter((relation) => (
    (relationDrafts[relation.id] ?? relationDraftFor(relation)).state === "pending"
  )).length;
  const automaticCount = relationItems.filter((relation) => relation.automatic).length;
  const stageIndex = stage === "select" ? 1 : stage === "preview" ? 2 : 3;

  const visitStep = (number: number) => {
    if (number === 1) setStage("select");
    if (number === 2 && preview) setStage("preview");
    if (number === 3 && preview) setStage("relations");
  };

  return (
    <div className="page-layout cash-import-page">
      <main className="app-shell">
        <aside className="sidebar">
          <strong>Finance Tracker</strong>
          <nav aria-label="主要导航">
            <a href="#cash-ledger" onClick={(event) => { event.preventDefault(); onBack(); }}>收支账本</a>
            <a aria-current="page" href="#cash-import">导入账单</a>
          </nav>
        </aside>
        <section className="ledger cash-import-shell" id="cash-import" aria-label="导入账单">
          <header className="page-header cash-import-header"><h1>导入账单</h1></header>
          <nav className="import-steps" aria-label="导入步骤">
            {[{ number: 1, label: "选择文件" }, { number: 2, label: "核对流水" }, { number: 3, label: "配对" }].map((item) => (
              <button
                type="button"
                key={item.number}
                className={item.number === stageIndex ? "is-current" : item.number < stageIndex ? "is-complete" : ""}
                aria-current={item.number === stageIndex ? "step" : undefined}
                disabled={busy || item.number > stageIndex || (item.number > 1 && !preview)}
                onClick={() => visitStep(item.number)}
              ><b>{item.number}</b>{item.label}</button>
            ))}
          </nav>
          {error ? <div className="form-error cash-import-error" role="alert">{error}</div> : null}

          {stage === "select" ? <section className="import-stage" aria-labelledby="import-select-heading">
            <h2 id="import-select-heading">选择文件</h2>
            <label className="import-dropzone">
              <input type="file" aria-label="选择账单文件" onChange={(event) => void chooseFile(event.target.files?.[0])} />
              <span className="dropzone-mark">↑</span>
              <strong>{file ? file.name : "拖入账单文件"}</strong>
              <small>CSV、XLS、XLSX、PDF</small>
            </label>
            {detection ? <div className="detection-result" role="status"><strong>{detection.channel_label}账单</strong><span className="status-chip">已识别</span></div> : null}
            <div className="stage-actions"><button type="button" className="button-secondary" onClick={onBack}>取消</button><button type="button" className="button-primary" disabled={!detection || busy} onClick={() => void loadPreview()}>{busy ? "识别中…" : "核对流水"}</button></div>
          </section> : null}

          {stage === "preview" && preview ? <section className="import-stage import-preview-stage" aria-labelledby="import-preview-heading">
            <div className="import-stage-heading"><h2 id="import-preview-heading">核对流水</h2><span className="channel-badge">{preview.channel_label}</span></div>
            <div className="stage-actions-top"><button type="button" className="button-secondary" onClick={() => setStage("select")}>上一步</button><button type="button" className="button-primary" disabled={busy} onClick={openRelations}>下一步</button></div>
            <div className="import-summary-cards">{[
              { label: "全部", value: preview.summary.total, tone: "total" },
              { label: "待新增", value: preview.summary.new, tone: "new" },
              { label: "已存在", value: preview.summary.existing, tone: "existing" },
              { label: "暂不支持", value: preview.summary.unsupported, tone: "unsupported" },
            ].map((summary) => <div key={summary.label} className={`import-summary-card ${summary.tone}`}><small>{summary.label}</small><strong>{summary.value}</strong></div>)}</div>
            <div className="standard-table-wrap" role="region" aria-label="账单流水表格" tabIndex={0}>
              <table className="standard-import-table"><caption className="sr-only">账单流水</caption><thead><tr>{preview.columns.map((column) => <th key={column} scope="col">{columnLabels[column] ?? column}</th>)}</tr></thead><tbody>{preview.items.map((item) => <tr key={item.record_id}>{preview.columns.map((column) => <td key={column} data-label={columnLabels[column] ?? column}>{column === "status" ? <span className={`import-status ${item.status}`}>{({ new: "待新增", existing: "已存在", unsupported: "暂不支持" } as const)[item.status]}</span> : displayValue(item, column)}</td>)}</tr>)}</tbody></table>
            </div>
            {preview.summary.unsupported > 0 ? <p className="import-stage-warning" role="status">有流水暂不支持。</p> : null}
          </section> : null}

          {stage === "relations" && preview ? <section className="import-stage" aria-labelledby="import-relations-heading">
            <div className="import-stage-heading"><h2 id="import-relations-heading">配对</h2></div>
            <div className="stage-actions-top"><button type="button" className="button-secondary" onClick={() => setStage("preview")}>上一步</button><button type="button" className="button-primary" disabled={busy || preview.summary.unsupported > 0} onClick={() => void confirmImport()}>{busy ? "导入中…" : "确认导入"}</button></div>
            {relationItems.length === 0 ? <div className="import-empty-state"><strong>没有配对</strong></div> : <>
              <div className="relation-toolbar"><div className="relation-filters" role="group" aria-label="配对筛选">
                {[
                  { value: "all" as const, label: "全部", count: relationItems.length },
                  { value: "automatic" as const, label: "自动", count: automaticCount },
                  { value: "pending" as const, label: "待处理", count: pendingCount },
                ].map((filter) => <button key={filter.value} className="relation-filter" type="button" aria-pressed={relationFilter === filter.value} onClick={() => { setRelationFilter(filter.value); setPage(1); }}>{filter.label} <b>{filter.count}</b></button>)}
              </div><label className="page-size">每页<select value={pageSize} aria-label="每页显示条数" onChange={(event) => { setPageSize(Number(event.target.value)); setPage(1); }}>{PAGE_SIZES.map((size) => <option value={size} key={size}>{size} 条</option>)}</select></label></div>
              <div className="relation-table-wrap" role="region" aria-label="配对列表" aria-live="polite"><table className="relation-table"><caption className="sr-only">配对列表</caption><thead><tr><th scope="col">状态</th><th scope="col">类型</th><th scope="col">现金流水</th><th scope="col">对侧流水</th><th scope="col">金额</th><th scope="col" aria-label="拒绝或撤销" /></tr></thead><tbody>
                {visibleRelations.map((relation) => {
                  const draft = relationDrafts[relation.id] ?? relationDraftFor(relation);
                  const rejected = draft.state === "rejected";
                  const selectedValue = draft.secondary?.record_id ?? "";
                  return <tr key={relation.id} className={rejected ? "is-rejected" : undefined}>
                    <td data-label="状态"><span className={`status ${draft.state === "rejected" ? "is-rejected" : draft.state === "pending" ? "is-pending" : "is-auto"}`}>{relationStateLabels[draft.state]}</span></td>
                    <td data-label="类型">{relation.automatic ? relationKindLabels[draft.kind] ?? draft.kind : <select className="relation-kind-select" aria-label={`${relation.label}关系类型`} value={draft.kind} disabled={rejected} onChange={(event) => setKind(relation, event.target.value)}>{Object.entries(relationKindLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select>}</td>
                    <td data-label="现金流水"><RelationRecord record={relation.primary} /></td>
                    <td data-label="对侧流水">{relation.automatic ? <RelationRecord record={draft.secondary ?? relation.secondary} /> : <select className="compact-select" aria-label={`${relation.label}对侧流水`} value={selectedValue} disabled={rejected} onChange={(event) => setSecondary(relation, event.target.value)}><option value="">选择对侧流水</option><option value="skip">暂不处理</option>{relation.candidates.map((candidate) => <option value={candidate.record_id} key={candidate.record_id}>{relationRecordLabel(candidate)}</option>)}</select>}</td>
                    <td data-label="金额"><span className="compact-amount">{relation.primary.amount}{draft.secondary ? ` / ${draft.secondary.amount}` : ""} {relation.primary.currency}</span></td>
                    <td data-label="拒绝或撤销"><button type="button" className="icon-only-button icon-quiet-button relation-action" aria-label={rejected ? "撤销拒绝" : "拒绝配对"} title={rejected ? "撤销拒绝" : "拒绝配对"} onClick={() => toggleRejected(relation)}><RelationActionIcon undo={rejected} /></button></td>
                  </tr>;
                })}
              </tbody></table></div>
              <div className="relation-pager" aria-label="配对分页"><button type="button" className="button-secondary" disabled={currentPage === 1} onClick={() => setPage((value) => Math.max(1, value - 1))}>上一页</button><span aria-live="polite">第 {currentPage} / {pageTotal} 页</span><button type="button" className="button-secondary" disabled={currentPage === pageTotal} onClick={() => setPage((value) => Math.min(pageTotal, value + 1))}>下一页</button></div>
            </>}
          </section> : null}

          {stage === "success" && result ? <section className="import-stage import-success-stage" aria-labelledby="import-success-heading"><div className="success-mark">✓</div><h2 id="import-success-heading">导入完成</h2><div className="import-success-stats"><span><strong>{result.new_rows}</strong>待新增</span><span><strong>{result.updated_rows}</strong>已更新</span><span><strong>{preview?.summary.existing ?? 0}</strong>已存在</span></div><div className="stage-actions"><button type="button" className="button-primary" onClick={onBack}>返回收支账本</button></div></section> : null}
        </section>
      </main>
    </div>
  );
}
