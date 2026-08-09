import { useState } from "react";
import { commitCashImport, importChannelLabels, previewCashImport } from "../api/cashLedger";
import type { ImportPreview } from "../api/types";
import { UiIcon } from "./UiIcon";

type Props = { onClose: () => void; onDone: () => void };

const channels = Object.entries(importChannelLabels);

export function ImportDrawer({ onClose, onDone }: Props) {
  const [source, setSource] = useState(channels[0]?.[0] ?? "alipay");
  const [file, setFile] = useState<File>();
  const [preview, setPreview] = useState<ImportPreview>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [done, setDone] = useState<string>();

  const previewFile = async () => {
    if (!file) return;
    setBusy(true); setError(undefined); setDone(undefined);
    try { setPreview(await previewCashImport(file, source)); }
    catch { setError("无法识别这份账单，请检查渠道和文件后重试。" ); }
    finally { setBusy(false); }
  };
  const commit = async () => {
    if (!file) return;
    setBusy(true); setError(undefined);
    try { const result = await commitCashImport(file, source); setDone(`已新增 ${result.new_rows} 条，更新 ${result.updated_rows} 条`); onDone(); }
    catch { setError("导入失败，请检查未支持的币种或账单内容。" ); }
    finally { setBusy(false); }
  };
  const statusLabel = (status: string) => status === "new" ? "待新增" : status === "existing" ? "已存在" : status === "unsupported" ? "暂不支持" : "需处理";

  return <div className="evidence-layer">
    <button type="button" className="evidence-backdrop" aria-label="点击遮罩关闭导入账单" onClick={onClose} />
    <aside className="evidence evidence-panel import-drawer" role="dialog" aria-modal="true" aria-label="导入账单">
      <header><div><p className="evidence-eyebrow">收支账本</p><h2>导入账单</h2></div><button type="button" className="icon-only-button" aria-label="关闭" title="关闭" onClick={onClose}><UiIcon name="x" /></button></header>
      <div className="evidence-content">
        {error ? <p className="form-error" role="alert">{error}</p> : null}
        {done ? <p className="success-message" role="status">{done}</p> : null}
        <section className="evidence-section record-form-section"><div className="form-grid"><label>账单渠道<select aria-label="账单渠道" value={source} onChange={(event) => { setSource(event.target.value); setPreview(undefined); }}>{channels.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label><label>账单文件<input aria-label="账单文件" type="file" onChange={(event) => { setFile(event.target.files?.[0]); setPreview(undefined); }} /></label></div><div className="drawer-actions"><button type="button" className="button-secondary" disabled={!file || busy} onClick={previewFile}>{busy ? "处理中…" : "预览"}</button></div></section>
        {preview ? <section className="evidence-section import-preview" aria-label="导入预览"><div className="section-heading"><h3>导入预览</h3><span>{preview.items.length} 条记录</span></div><div className="import-summary">待新增 {preview.summary.new ?? 0} · 已存在 {preview.summary.existing ?? 0} · 暂不支持 {preview.summary.unsupported ?? 0}</div><ul>{preview.items.map((item) => <li key={item.record_id}><div><strong>{item.counterparty || "未填写对方"}</strong><span>{item.occurred_at.replace("T", " ").slice(0, 16)} · {item.amount} {item.currency} · {item.account_name}</span></div><div className={`import-status ${item.status}`}>{statusLabel(item.status)}{item.message ? <small>{item.message}</small> : null}</div></li>)}</ul><button type="button" className="button-primary" disabled={busy || preview.items.some((item) => item.status === "unsupported" || item.status === "error")} onClick={commit}>确认导入</button></section> : null}
      </div>
    </aside>
  </div>;
}
