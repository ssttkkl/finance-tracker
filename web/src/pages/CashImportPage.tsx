import { useState } from "react";
import { commitCashImport, detectCashImport, previewCashImport } from "../api/cashLedger";
import type { ImportCommitResult, ImportDetection, ImportPreview, ImportPreviewItem, ImportRelation, ImportRelationRecord } from "../api/types";

type Stage = "select" | "preview" | "relations" | "success";

const columnLabels: Record<string, string> = {
  occurred_at: "发生时间",
  amount: "金额",
  currency: "币种",
  account_name: "目标账户",
  counterparty: "交易对方",
  counterparty_account: "对方账号",
  record_type: "记录类型",
  record_subtype: "记录子类型",
  category: "分类",
  note: "备注",
  channel: "渠道",
  status: "处理状态",
};

const recordTypeLabels: Record<string, string> = {
  consumption: "消费", refund: "退款", income: "收入", transfer_in: "转账入账",
  transfer_out: "转账转出", repayment: "还款", withdrawal_in: "提现入账", withdrawal_out: "提现",
  fx_in: "换汇转入", fx_out: "换汇转出", other: "其他",
};

const statusLabels: Record<ImportPreviewItem["status"], string> = {
  new: "待新增", existing: "已存在", unsupported: "暂不支持",
};

function displayValue(item: ImportPreviewItem, column: string): string {
  const value = item[column as keyof ImportPreviewItem];
  if (column === "record_type") return recordTypeLabels[String(value)] ?? String(value || "—");
  return value === undefined || value === "" ? "—" : String(value);
}

function relationRecordLabel(record: ImportRelationRecord | null): string {
  if (!record) return "尚未选择对侧流水";
  return `${record.counterparty || "未填写对方"} · ${record.amount} ${record.currency}`;
}

function relationDecision(relation: ImportRelation, secondary: ImportRelationRecord): Record<string, unknown> {
  const endpoint = (record: ImportRelationRecord, factKey: "primary_fact_id" | "secondary_fact_id") => (
    record.fact_id ? { [factKey]: record.fact_id } : { [`${factKey === "primary_fact_id" ? "primary" : "secondary"}_record_id`]: record.record_id }
  );
  return {
    kind: relation.kind,
    subtype: relation.subtype,
    rule_id: relation.rule_id,
    ...endpoint(relation.primary, "primary_fact_id"),
    ...endpoint(secondary, "secondary_fact_id"),
    status: "accepted",
  };
}

export function CashImportPage({ onBack, onDone }: { onBack: () => void; onDone?: () => void }) {
  const [stage, setStage] = useState<Stage>("select");
  const [file, setFile] = useState<File | null>(null);
  const [detection, setDetection] = useState<ImportDetection | null>(null);
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [selectedCandidates, setSelectedCandidates] = useState<Record<string, ImportRelationRecord>>({});
  const [skipped, setSkipped] = useState<Set<string>>(new Set());
  const [result, setResult] = useState<ImportCommitResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  const chooseFile = async (nextFile: File | undefined) => {
    if (!nextFile) return;
    setFile(nextFile); setDetection(null); setPreview(null); setResult(null); setError(undefined); setStage("select");
    setBusy(true);
    try {
      setDetection(await detectCashImport(nextFile));
    } catch (cause) {
      setError(cause instanceof Error && cause.message === "import_channel_unrecognized"
        ? "无法唯一识别账单渠道，请确认文件是支付宝、微信或支持的银行卡账单。"
        : "文件识别失败，请检查文件内容后重试。");
    } finally { setBusy(false); }
  };

  const loadPreview = async () => {
    if (!file || !detection) return;
    setBusy(true); setError(undefined);
    try {
      setPreview(await previewCashImport(file));
      setStage("preview");
    } catch { setError("账单预览失败，请检查文件内容或账户币种配置。"); }
    finally { setBusy(false); }
  };

  const openRelations = () => {
    if (!preview) return;
    setSelectedCandidates({}); setSkipped(new Set()); setStage("relations");
  };

  const confirmImport = async () => {
    if (!file || !preview || preview.summary.unsupported > 0) return;
    setBusy(true); setError(undefined);
    const decisions = preview.relations.flatMap((relation) => {
      if (relation.automatic && relation.secondary) return [relationDecision(relation, relation.secondary)];
      const selected = selectedCandidates[relation.id];
      return selected && !skipped.has(relation.id) ? [relationDecision(relation, selected)] : [];
    });
    try {
      const committed = await commitCashImport(file, "", undefined, {
        previewDigest: preview.file.digest,
        previewChannel: preview.channel,
        relations: decisions,
      });
      setResult(committed); setStage("success"); onDone?.();
    } catch (cause) {
      setError(cause instanceof Error && cause.message === "import_preview_stale"
        ? "文件内容已经变化，请返回第一步重新选择文件。"
        : cause instanceof Error && cause.message === "relation_impact_required"
          ? "这次导入会影响已关联的流水，请先在收支详情中处理关联。"
          : "确认导入失败，请检查账单内容后重试。");
    } finally { setBusy(false); }
  };

  const stageIndex = stage === "select" ? 1 : stage === "preview" ? 2 : stage === "relations" || stage === "success" ? 3 : 1;
  const visitStep = (number: number) => {
    if (number === 1) setStage("select");
    if (number === 2 && preview) setStage("preview");
    if (number === 3 && preview) setStage("relations");
  };
  const setCandidate = (relation: ImportRelation, candidate: ImportRelationRecord) => {
    setSelectedCandidates((current) => ({ ...current, [relation.id]: candidate }));
    setSkipped((current) => { const next = new Set(current); next.delete(relation.id); return next; });
  };
  const skipRelation = (relationId: string) => {
    setSkipped((current) => new Set(current).add(relationId));
    setSelectedCandidates((current) => { const next = { ...current }; delete next[relationId]; return next; });
  };

  return <div className="page-layout cash-import-page">
    <main className="app-shell">
      <aside className="sidebar"><strong>Finance Tracker</strong><nav aria-label="主要导航"><a href="#cash-ledger" onClick={(event) => { event.preventDefault(); onBack(); }}>收支账本</a><a aria-current="page" href="#cash-import">导入账单</a></nav></aside>
      <section className="ledger cash-import-shell" id="cash-import" aria-label="导入账单">
        <header className="page-header cash-import-header"><div><button type="button" className="text-button cash-import-back" onClick={onBack}>← 返回收支账本</button><p className="evidence-eyebrow">收支账本 / 导入处理</p><h1>导入账单</h1><p className="cash-import-intro">先识别渠道，再核对标准化流水与配对关系；确认前不会写入账本。</p></div></header>
        <nav className="import-steps" aria-label="导入步骤">
          {[{ number: 1, label: "选择文件" }, { number: 2, label: "导入预览" }, { number: 3, label: "确认配对" }].map((item) => <button type="button" key={item.number} className={item.number === stageIndex ? "is-current" : item.number < stageIndex ? "is-complete" : ""} aria-current={item.number === stageIndex ? "step" : undefined} disabled={busy || item.number > stageIndex || (item.number > 1 && !preview)} onClick={() => visitStep(item.number)}><b>{item.number}</b>{item.label}</button>)}
        </nav>
        {error ? <div className="form-error cash-import-error" role="alert">{error}</div> : null}
        {stage === "select" ? <section className="import-stage" aria-labelledby="import-select-heading"><div className="import-stage-heading"><div><p className="stage-kicker">STEP 01</p><h2 id="import-select-heading">选择账单文件</h2><p>上传后系统会自动识别账单渠道，不需要手动选择来源。</p></div></div><label className="import-dropzone"><input type="file" aria-label="选择账单文件" onChange={(event) => void chooseFile(event.target.files?.[0])} /><span className="dropzone-mark">↑</span><strong>{file ? file.name : "选择或拖入账单文件"}</strong><small>{file ? `${(file.size / 1024).toFixed(1)} KB · 已选择` : "支持支付宝、微信、工商银行、建设银行及工银亚洲账单"}</small></label>{detection ? <div className="detection-result" role="status"><span className="detection-icon">✓</span><div><strong>已识别为{detection.channel_label}账单</strong><p>{detection.row_count} 条可识别记录 · 文件摘要已锁定</p></div><span className="status-chip">识别成功</span></div> : null}<div className="stage-actions"><button type="button" className="button-secondary" onClick={onBack}>取消</button><button type="button" className="button-primary" disabled={!detection || busy} onClick={() => void loadPreview()}>{busy ? "识别中…" : "继续查看预览 →"}</button></div></section> : null}
        {stage === "preview" && preview ? <section className="import-stage import-preview-stage" aria-labelledby="import-preview-heading"><div className="import-stage-heading"><div><p className="stage-kicker">STEP 02</p><h2 id="import-preview-heading">导入预览</h2><p>以下全部为系统标准化字段，不展示来源账单原始列。</p></div><span className="channel-badge">{preview.channel_label}</span></div><div className="import-summary-cards">{[{ label: "总记录数", value: preview.summary.total, tone: "total" }, { label: "待新增", value: preview.summary.new, tone: "new" }, { label: "已存在", value: preview.summary.existing, tone: "existing" }, { label: "暂不支持", value: preview.summary.unsupported, tone: "unsupported" }].map((item) => <div key={item.label} className={`import-summary-card ${item.tone}`}><small>{item.label}</small><strong>{item.value}</strong></div>)}</div><div className="standard-table-wrap" role="region" aria-label="标准化账单字段表格" tabIndex={0}><table className="standard-import-table"><caption className="sr-only">账单标准化字段预览</caption><thead><tr>{preview.columns.map((column) => <th key={column} scope="col">{columnLabels[column] ?? column}</th>)}</tr></thead><tbody>{preview.items.map((item) => <tr key={item.record_id}>{preview.columns.map((column) => <td key={column} data-label={columnLabels[column] ?? column}>{column === "status" ? <span className={`import-status ${item.status}`}>{statusLabels[item.status]}</span> : displayValue(item, column)}</td>)}</tr>)}</tbody></table></div>{preview.summary.unsupported > 0 ? <p className="import-stage-warning" role="status">有记录暂不支持导入，请处理账户或币种配置后重新选择文件。</p> : null}<div className="stage-actions"><button type="button" className="button-secondary" onClick={() => setStage("select")}>← 返回选择文件</button><button type="button" className="button-primary" disabled={busy} onClick={openRelations}>下一步：查看配对 →</button></div></section> : null}
        {stage === "relations" && preview ? <section className="import-stage" aria-labelledby="import-relations-heading"><div className="import-stage-heading"><div><p className="stage-kicker">STEP 03</p><h2 id="import-relations-heading">确认配对关系</h2><p>自动配对会随本次确认一起保存；手动配对可以先暂不处理。</p></div></div><div className="relation-summary-line"><span><strong>{preview.relations.filter((item) => item.automatic).length}</strong> 条自动配对</span><span><strong>{preview.relations.filter((item) => !item.automatic).length}</strong> 条待手动处理</span></div>{preview.relations.length === 0 ? <div className="import-empty-state"><strong>没有发现需要处理的配对关系</strong><p>账单流水仍会按标准化预览结果导入。</p></div> : <div className="import-relations-list">{preview.relations.map((relation) => { const selected = selectedCandidates[relation.id]; const isSkipped = skipped.has(relation.id); return <article className={`import-relation-card ${relation.automatic ? "is-automatic" : "is-manual"}`} key={relation.id}><div className="relation-card-header"><div><span className="relation-kind">{relation.label}</span><h3>{relationRecordLabel(relation.primary)} <span aria-hidden="true">↔</span> {relationRecordLabel(relation.secondary ?? selected ?? null)}</h3></div><span className={`status-chip ${relation.automatic ? "" : "muted"}`}>{relation.automatic ? "自动配对" : isSkipped ? "已暂不处理" : "待手动配对"}</span></div><p className="relation-reason">{relation.reason}</p>{!relation.automatic ? <><div className="relation-candidate-list" role="group" aria-label={`${relation.label}候选流水`}>{relation.candidates.map((candidate) => <button type="button" className={`relation-candidate ${selected?.record_id === candidate.record_id ? "is-selected" : ""}`} key={`${relation.id}-${candidate.record_id}`} onClick={() => setCandidate(relation, candidate)}><span className="relation-candidate-main"><strong>{candidate.counterparty || "未填写对方"}</strong><small>{candidate.occurred_at.replace("T", " ").slice(0, 16)} · {candidate.account_name} · {candidate.channel}</small></span><span className="relation-candidate-amount">{candidate.amount} {candidate.currency}</span></button>)}</div><button type="button" className="text-button relation-skip" onClick={() => skipRelation(relation.id)}>{isSkipped ? "已暂不处理" : "暂不处理"}</button></> : null}</article>; })}</div>}<div className="stage-actions"><button type="button" className="button-secondary" onClick={() => setStage("preview")}>← 返回预览</button><button type="button" className="button-primary" disabled={busy || preview.summary.unsupported > 0} onClick={() => void confirmImport()}>{busy ? "正在确认…" : "确认导入"}</button></div></section> : null}
        {stage === "success" && result ? <section className="import-stage import-success-stage" aria-labelledby="import-success-heading"><div className="success-mark">✓</div><p className="stage-kicker">IMPORT COMPLETE</p><h2 id="import-success-heading">导入已完成</h2><p>{result.message}</p><div className="import-success-stats"><span><strong>{result.new_rows}</strong>待新增</span><span><strong>{result.updated_rows}</strong>已更新</span><span><strong>{preview?.summary.existing ?? 0}</strong>已存在</span></div><div className="stage-actions"><button type="button" className="button-primary" onClick={onBack}>返回收支账本</button></div></section> : null}
      </section>
    </main>
  </div>;
}
