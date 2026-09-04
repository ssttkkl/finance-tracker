import { useEffect, useMemo, useRef, useState } from "react";
import { commitCashImport, previewCashImport, scanCashImport } from "../api/cashLedger";
import type {
  ImportCommitResult,
  ImportMappingDecision,
  ImportPreview,
  ImportPreviewItem,
  ImportRelation,
  ImportRelationRecord,
  ImportScan,
  ImportSourceGroup,
} from "../api/types";
import { formatOccurredAt, isZeroAmount } from "../format";
import { buildTransactionMonthlySummaries, TransactionTable, type TransactionTableItem } from "../components/TransactionTable";

type Stage = "select" | "mapping" | "preview" | "relations" | "success";
type RelationFilter = "all" | "automatic" | "pending";
type ImportPreviewFilter = "all" | "new" | "existing" | "unresolved";
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

type MappingDraft = {
  accountId: number | null;
  newAccount: { draftId: string; name: string; type: string; currencies: string[] } | null;
};

const PAGE_SIZES = [20, 50, 100];

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

function unresolvedCount(preview: ImportPreview): number {
  return preview.summary.unresolved ?? 0;
}

function ordinaryUnsupportedCount(preview: ImportPreview): number {
  return Math.max(0, preview.summary.unsupported - unresolvedCount(preview));
}

function recordDate(value: string): string {
  return formatOccurredAt(value);
}

const importStatusLabels = {
  new: "待新增",
  existing: "已存在",
  unsupported: "暂不支持",
  unresolved: "无法识别",
} as const;

function importDirection(item: ImportPreviewItem): TransactionTableItem<ImportPreviewItem>["direction"] {
  if (isZeroAmount(item.amount)) return "unknown";
  if (item.amount.startsWith("-")) return "expense";
  if (item.amount.startsWith("+") || item.amount !== "0") return "income";
  if (["transfer_out", "withdrawal_out"].includes(item.record_type)) return "expense";
  if (["transfer_in", "withdrawal_in"].includes(item.record_type)) return "income";
  return "unknown";
}

function importAmountLabel(item: ImportPreviewItem): string {
  const amount = isZeroAmount(item.amount) ? item.amount.replace(/^[+-]/, "") : item.amount.startsWith("-") || item.amount.startsWith("+") ? item.amount : `+${item.amount}`;
  return `${amount} ${item.currency}`;
}

function importTableItem(item: ImportPreviewItem): TransactionTableItem<ImportPreviewItem> {
  return {
    id: item.record_id,
    source: item,
    occurredAt: item.occurred_at,
    accountLabel: item.account_name,
    counterparty: item.counterparty,
    note: item.note,
    flowLabel: recordTypeLabels[item.record_type] ?? "其他",
    direction: importDirection(item),
    amountLabel: importAmountLabel(item),
    statusLabel: importStatusLabels[item.status],
    statusTone: item.status,
    summaryAmount: item.amount,
    summaryCurrency: item.currency,
  };
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

function passwordErrorMessage(cause: unknown): string | null {
  if (!(cause instanceof Error)) return null;
  if (cause.message === "import_password_required") return "请输入账单密码。";
  if (cause.message === "import_password_invalid") return "账单密码错误，请重试。";
  return null;
}

function importTokenFromError(cause: unknown): string | null {
  if (!(cause instanceof Error)) return null;
  const token = (cause as Error & { importToken?: unknown }).importToken;
  return typeof token === "string" && token ? token : null;
}

function newImportIdempotencyKey(): string {
  const random = typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `cash-import-${random}`;
}

function mappingErrorMessage(cause: unknown): string | null {
  if (!(cause instanceof Error)) return null;
  const messages: Record<string, string> = {
    import_account_unavailable: "所选账户已不可用，请重新选择。",
    import_account_name_conflict: "账户名称已存在，请修改后重试。",
    import_account_draft_invalid: "新账户信息无效，请修改后重试。",
    import_mapping_incomplete: "请为每个来源账户选择系统账户。",
    import_composite_payment_unresolved: "账单包含无法准确归属的组合支付，请拆分后重试。",
  };
  return messages[cause.message] ?? null;
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
    proposal_key: relation.id,
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
  const [scan, setScan] = useState<ImportScan | null>(null);
  const [mappingDrafts, setMappingDrafts] = useState<Record<string, MappingDraft>>({});
  const [editingGroup, setEditingGroup] = useState<ImportSourceGroup | null>(null);
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [previewFilter, setPreviewFilter] = useState<ImportPreviewFilter>("all");
  const [relationDrafts, setRelationDrafts] = useState<Record<string, RelationDraft>>({});
  const [relationFilter, setRelationFilter] = useState<RelationFilter>("all");
  const [pageSize, setPageSize] = useState(20);
  const [page, setPage] = useState(1);
  const [result, setResult] = useState<ImportCommitResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [password, setPassword] = useState("");
  const [passwordRequired, setPasswordRequired] = useState(false);
  const [importToken, setImportToken] = useState<string | null>(null);
  const [idempotencyKey, setIdempotencyKey] = useState<string | null>(null);
  const [focusRelations, setFocusRelations] = useState(false);
  const relationHeadingRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    if (stage !== "relations" || !focusRelations) return;
    relationHeadingRef.current?.focus();
    setFocusRelations(false);
  }, [focusRelations, stage]);

  const returnToPasswordEntry = (cause: unknown): boolean => {
    const message = passwordErrorMessage(cause);
    if (!message) return false;
    setScan(null);
    setMappingDrafts({});
    setPreview(null);
    setPreviewFilter("all");
    setRelationDrafts({});
    setPassword("");
    setPasswordRequired(true);
    setStage("select");
    setError(message);
    return true;
  };

  const chooseFile = async (nextFile: File | undefined) => {
    if (!nextFile) return;
    setFile(nextFile);
    setScan(null);
    setMappingDrafts({});
    setPreview(null);
    setPreviewFilter("all");
    setRelationDrafts({});
    setResult(null);
    setError(undefined);
    setPassword("");
    setPasswordRequired(false);
    setImportToken(null);
    setIdempotencyKey(null);
    setStage("select");
    setBusy(true);
    try {
      const nextScan = await scanCashImport(nextFile);
      setScan(nextScan);
      setImportToken(nextScan.import_token ?? null);
      setIdempotencyKey(nextScan.import_token ? newImportIdempotencyKey() : null);
      setMappingDrafts(Object.fromEntries(nextScan.groups.map((group) => [
        group.group_id,
        { accountId: group.suggestion.account_id, newAccount: null },
      ])));
      setStage("mapping");
    } catch (cause) {
      const token = importTokenFromError(cause);
      if (token) {
        setImportToken(token);
        setIdempotencyKey((current) => current ?? newImportIdempotencyKey());
      }
      if (cause instanceof Error && cause.message === "import_password_required") {
        setPasswordRequired(true);
        setError(undefined);
      } else {
        setError(cause instanceof Error && cause.message === "import_channel_unrecognized"
          ? "无法识别账单渠道，请重新选择文件。"
          : mappingErrorMessage(cause) ?? "文件识别失败，请重试。");
      }
    } finally {
      setBusy(false);
    }
  };

  const detectWithPassword = async () => {
    if (!file || !password) return;
    setBusy(true);
    setError(undefined);
    try {
      const nextScan = await scanCashImport(file, undefined, password, importToken ?? undefined);
      setScan(nextScan);
      setImportToken(nextScan.import_token ?? importToken);
      if (nextScan.import_token || importToken) setIdempotencyKey((current) => current ?? newImportIdempotencyKey());
      setMappingDrafts(Object.fromEntries(nextScan.groups.map((group) => [
        group.group_id,
        { accountId: group.suggestion.account_id, newAccount: null },
      ])));
      setStage("mapping");
      setPasswordRequired(false);
    } catch (cause) {
      const token = importTokenFromError(cause);
      if (token) setImportToken(token);
      if (!returnToPasswordEntry(cause)) {
        setError(cause instanceof Error && cause.message === "import_password_invalid"
          ? "账单密码错误，请重试。"
          : mappingErrorMessage(cause) ?? "文件识别失败，请重试。");
      }
    } finally {
      setBusy(false);
    }
  };

  const continueFromSelect = () => {
    if (!file || busy) return;
    setError(undefined);
    if (scan) {
      setStage("mapping");
      return;
    }
    if (passwordRequired) {
      void detectWithPassword();
      return;
    }
    void chooseFile(file);
  };

  const mappingPayload = (): ImportMappingDecision[] => (scan?.groups ?? []).map((group) => {
    const draft = mappingDrafts[group.group_id];
    return {
      group_id: group.group_id,
      account_id: draft?.newAccount ? null : draft?.accountId,
      mapping_revision: group.suggestion.mapping_revision,
      new_account: draft?.newAccount
        ? {
            draft_id: draft.newAccount.draftId,
            name: draft.newAccount.name,
            type: draft.newAccount.type,
            currencies: draft.newAccount.currencies,
          }
        : null,
    };
  });

  const mappingComplete = Boolean(scan && scan.groups.length > 0 && scan.groups.every((group) => {
    const draft = mappingDrafts[group.group_id];
    return Boolean(draft?.accountId || draft?.newAccount);
  }));

  const newAccountDraftFor = (group: ImportSourceGroup): NonNullable<MappingDraft["newAccount"]> => ({
    draftId: `draft-${group.group_id}`,
    name: group.display_name,
    type: group.display_name.includes("花呗") || group.display_name.includes("信用卡") ? "loan" : "cash",
    currencies: [...group.currencies],
  });

  const selectMapping = (group: ImportSourceGroup, value: string) => {
    setMappingDrafts((current) => {
      if (value === "__create__") {
        return { ...current, [group.group_id]: { accountId: null, newAccount: newAccountDraftFor(group) } };
      }
      if (value.startsWith("__draft__")) {
        const draftId = value.slice("__draft__".length);
        const selected = Object.values(current).find((item) => item.newAccount?.draftId === draftId)?.newAccount;
        if (!selected) return current;
        const merged = {
          ...selected,
          currencies: Array.from(new Set([...selected.currencies, ...group.currencies])).sort(),
        };
        const next = Object.fromEntries(Object.entries(current).map(([groupId, item]) => (
          item.newAccount?.draftId === draftId
            ? [groupId, { accountId: null, newAccount: merged }]
            : [groupId, item]
        )));
        next[group.group_id] = { accountId: null, newAccount: merged };
        return next;
      }
      return { ...current, [group.group_id]: { accountId: Number(value), newAccount: null } };
    });
    setPreview(null);
    setPreviewFilter("all");
    setRelationDrafts({});
  };

  const updateNewAccountDraft = (group: ImportSourceGroup, update: Partial<NonNullable<MappingDraft["newAccount"]>>) => {
    setMappingDrafts((current) => {
      const existing = current[group.group_id]?.newAccount ?? newAccountDraftFor(group);
      const updated = { ...existing, ...update };
      return Object.fromEntries(Object.entries(current).map(([groupId, item]) => (
        item.newAccount?.draftId === existing.draftId
          ? [groupId, { accountId: null, newAccount: updated }]
          : [groupId, item]
      )));
    });
    setPreview(null);
    setPreviewFilter("all");
    setRelationDrafts({});
  };

  const loadPreview = async (nextStage: "preview" | "relations" = "preview"): Promise<boolean> => {
    if (!file || !scan || !mappingComplete) return false;
    setBusy(true);
    setError(undefined);
    try {
      const nextPreview = await previewCashImport(file, "", undefined, password, mappingPayload(), importToken ?? undefined);
      setPreview(nextPreview);
      setImportToken(nextPreview.import_token ?? importToken);
      setRelationDrafts({});
      setRelationFilter("all");
      setPage(1);
      setStage(nextStage);
      if (nextStage === "relations") setFocusRelations(true);
      return true;
    } catch (cause) {
      if (!returnToPasswordEntry(cause)) {
        if (cause instanceof Error && cause.message === "import_mapping_stale") {
          setScan(null);
          setMappingDrafts({});
          setStage("select");
          setError("账户映射已变化，请重新扫描。");
        } else {
          setError(mappingErrorMessage(cause) ?? "账单预览失败，请重试。");
        }
      }
      return false;
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
    if (!file || !preview || ordinaryUnsupportedCount(preview) > 0) return;
    setBusy(true);
    setError(undefined);
    const decisions = preview.relations.flatMap((relation) => {
      const draft = relationDrafts[relation.id] ?? relationDraftFor(relation);
      const decision = relationDecision(relation, draft);
      return decision ? [decision] : [];
    });
    try {
      const commitKey = importToken ? (idempotencyKey ?? newImportIdempotencyKey()) : undefined;
      if (commitKey && !idempotencyKey) setIdempotencyKey(commitKey);
      const committed = await commitCashImport(file, "", undefined, {
        previewDigest: preview.file.digest,
        previewRelationDigest: preview.relation_digest,
        previewChannel: preview.channel,
        password,
        relations: decisions,
        mapping: mappingPayload(),
        importToken: importToken ?? undefined,
        idempotencyKey: commitKey,
      });
      setResult(committed);
      setStage("success");
      onDone?.();
    } catch (cause) {
      const token = importTokenFromError(cause);
      if (token) setImportToken(token);
      if (returnToPasswordEntry(cause)) {
        // The password entry state already contains the actionable error.
      } else {
        const mappingError = mappingErrorMessage(cause);
        if (mappingError) {
          setPreview(null);
          setRelationDrafts({});
          setStage("mapping");
          setError(mappingError);
        } else {
          const requiresRelationReconfirmation = cause instanceof Error && [
            "import_relation_reconfirmation_required",
            "import_relation_preview_stale",
            "import_relation_candidate_invalid",
          ].includes(cause.message);
          if (requiresRelationReconfirmation) {
            const refreshed = await loadPreview("relations");
            if (refreshed) setError("相关流水已变化，请重新确认配对。");
            return;
          }
          setError(cause instanceof Error && cause.message === "import_preview_stale"
            ? "文件内容已经变化，请重新选择文件。"
            : cause instanceof Error && cause.message === "relation_impact_required"
              ? "这次导入会影响已关联的流水，请先处理关联。"
              : "确认导入失败，请重试。");
        }
      }
    } finally {
      setBusy(false);
    }
  };

  const relationItems = preview?.relations ?? [];
  const importTableItems = useMemo(() => (preview?.items ?? []).map(importTableItem), [preview]);
  const filteredImportTableItems = useMemo(() => {
    if (previewFilter === "all") return importTableItems;
    return importTableItems.filter((item) => item.source?.status === previewFilter);
  }, [importTableItems, previewFilter]);
  const importMonthlySummaries = useMemo(() => buildTransactionMonthlySummaries(filteredImportTableItems), [filteredImportTableItems]);
  const sharedDrafts = Array.from(new Map(
    Object.values(mappingDrafts)
      .flatMap((item) => item.newAccount ? [[item.newAccount.draftId, item.newAccount] as const] : []),
  ).values());
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
  const stageIndex = stage === "select" ? 1 : stage === "mapping" ? 2 : stage === "preview" ? 3 : 4;

  const restartAfterCompletedImport = () => {
    setFile(null);
    setScan(null);
    setMappingDrafts({});
    setEditingGroup(null);
    setPreview(null);
    setPreviewFilter("all");
    setRelationDrafts({});
    setRelationFilter("all");
    setPage(1);
    setResult(null);
    setError(undefined);
    setPassword("");
    setPasswordRequired(false);
    setImportToken(null);
    setIdempotencyKey(null);
    setFocusRelations(false);
    setStage("select");
  };

  const visitStep = (number: number) => {
    if (number === 1) {
      if (stage === "success") restartAfterCompletedImport();
      else setStage("select");
    }
    if (number === 2 && scan) setStage("mapping");
    if (number === 3 && preview) setStage("preview");
    if (number === 4 && preview) setStage("relations");
  };

  return (
    <section className="ledger cash-import-shell" id="cash-import" aria-label="导入账单">
          <header className="page-header cash-import-header"><h1>导入账单</h1></header>
          <nav className="import-steps" aria-label="导入步骤">
            {[{ number: 1, label: "选择文件" }, { number: 2, label: "映射账户" }, { number: 3, label: "核对流水" }, { number: 4, label: "配对" }].map((item) => (
              <button
                type="button"
                key={item.number}
                className={item.number === stageIndex ? "is-current" : item.number < stageIndex ? "is-complete" : ""}
                aria-current={item.number === stageIndex ? "step" : undefined}
                disabled={busy || item.number > stageIndex || (item.number === 2 && !scan) || (item.number >= 3 && !preview)}
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
            {scan ? <div className="detection-result" role="status"><strong>{scan.channel_label}账单</strong><span className="status-chip">已识别</span></div> : null}
            {passwordRequired ? <div className="import-password-panel">
              <label htmlFor="cash-import-password">账单密码</label>
              <div className="import-password-row">
                <input id="cash-import-password" type="password" value={password} autoComplete="off" onChange={(event) => setPassword(event.target.value)} />
              </div>
            </div> : null}
            <div className="stage-actions"><button type="button" className="button-secondary" onClick={onBack}>取消</button><button type="button" className="button-primary" disabled={!file || busy || (passwordRequired && !password)} onClick={continueFromSelect}>{busy ? "扫描中…" : "下一步"}</button></div>
          </section> : null}

          {stage === "mapping" && scan ? <section className="import-stage import-mapping-stage" aria-labelledby="import-mapping-heading">
            <div className="import-stage-heading"><h2 id="import-mapping-heading">映射账户</h2><span className="channel-badge">{scan.channel_label}</span></div>
            <div className="stage-actions-top"><button type="button" className="button-secondary" onClick={() => setStage("select")}>上一步</button><button type="button" className="button-primary" disabled={!mappingComplete || busy} onClick={() => void loadPreview()}>{busy ? "核对中…" : "确认映射"}</button></div>
            <p className="import-stage-lead">识别到 {scan.groups.length} 个来源账户</p>
            {scan.unresolved_count ? <p className="import-stage-warning" role="status">有 {scan.unresolved_count} 条流水无法准确归属，确认导入时会跳过；其余流水可正常导入。</p> : null}
            <div className="mapping-groups">
              {scan.groups.map((group) => {
                const draft = mappingDrafts[group.group_id] ?? { accountId: null, newAccount: null };
                const selected = draft.newAccount ? null : scan.accounts.find((account) => account.id === draft.accountId) ?? null;
                const missing = selected ? group.currencies.filter((currency) => !(selected.currencies ?? []).includes(currency)) : [];
                const typeLabels: Record<string, string> = { cash: "现金账户", loan: "贷款账户", lend: "借款账户" };
                return <article className="mapping-group" key={group.group_id}>
                  <div className="mapping-group-source"><strong>{group.display_name}</strong><small>{group.masked_evidence} · {group.currencies.join(" / ")} · {group.row_count} 条流水</small></div>
                  <label className="mapping-account-field">系统账户<select aria-label={`${group.display_name}系统账户`} value={draft.newAccount ? `__draft__${draft.newAccount.draftId}` : draft.accountId ? String(draft.accountId) : ""} onChange={(event) => selectMapping(group, event.target.value)}>
                    <option value="">请选择账户</option>{scan.accounts.map((account) => <option value={account.id} key={account.id}>{account.name}</option>)}{sharedDrafts.map((item) => <option value={`__draft__${item.draftId}`} key={item.draftId}>即将创建「{item.name}」</option>)}<option value="__create__">创建新账户</option>
                  </select>
                  {draft.newAccount ? <span className="row-commitment">将创建「{draft.newAccount.name}」 · {typeLabels[draft.newAccount.type] ?? draft.newAccount.type} · {draft.newAccount.currencies.join(" / ")} <button type="button" className="commitment-action" onClick={() => setEditingGroup(group)}>修改</button></span> : missing.length > 0 ? <span className="row-commitment">将为「{selected?.name}」新增 {missing.join("、")}</span> : null}
                  {!draft.accountId && !draft.newAccount ? <span className="mapping-field-error" role="status">请选择系统账户或创建新账户</span> : null}
                  </label>
                </article>;
              })}
            </div>
            {editingGroup ? <div className="import-dialog-backdrop" role="presentation" onMouseDown={() => setEditingGroup(null)}><div className="import-dialog" role="dialog" aria-modal="true" aria-labelledby="edit-account-heading" onMouseDown={(event) => event.stopPropagation()}><h3 id="edit-account-heading">修改新账户</h3><label>账户名称<input value={mappingDrafts[editingGroup.group_id]?.newAccount?.name ?? ""} onChange={(event) => updateNewAccountDraft(editingGroup, { name: event.target.value })} /></label><label>账户类型<select value={mappingDrafts[editingGroup.group_id]?.newAccount?.type ?? "cash"} onChange={(event) => updateNewAccountDraft(editingGroup, { type: event.target.value })}><option value="cash">现金账户</option><option value="loan">贷款账户</option><option value="lend">借款账户</option></select></label><div className="stage-actions"><button type="button" className="button-secondary" onClick={() => setEditingGroup(null)}>取消</button><button type="button" className="button-primary" onClick={() => setEditingGroup(null)}>完成</button></div></div></div> : null}
          </section> : null}

          {stage === "preview" && preview ? <section className="import-stage import-preview-stage" aria-labelledby="import-preview-heading">
            <div className="import-stage-heading"><h2 id="import-preview-heading">核对流水</h2><span className="channel-badge">{preview.channel_label}</span></div>
            <div className="stage-actions-top"><button type="button" className="button-secondary" onClick={() => setStage("mapping")}>上一步</button><button type="button" className="button-primary" disabled={busy} onClick={openRelations}>下一步</button></div>
            <div className="import-summary-cards" role="group" aria-label="预览流水筛选">{[
              { filter: "all" as const, label: "全部", value: preview.summary.total, tone: "total" },
              { filter: "new" as const, label: "待新增", value: preview.summary.new, tone: "new" },
              { filter: "existing" as const, label: "已存在", value: preview.summary.existing, tone: "existing" },
              { filter: "unresolved" as const, label: "无法识别", value: preview.summary.unresolved ?? 0, tone: "unsupported" },
            ].map((summary) => <button
              type="button"
              key={summary.label}
              className={`import-summary-card ${summary.tone}`}
              aria-pressed={previewFilter === summary.filter}
              aria-controls="import-preview-table"
              onClick={() => setPreviewFilter(summary.filter)}
            ><small>{summary.label}</small><strong>{summary.value}</strong></button>)}</div>
            <div id="import-preview-table">
              {filteredImportTableItems.length === 0
                ? <div className="import-empty-state" role="status"><strong>{preview.items.length === 0 ? "没有可核对流水" : "没有符合条件的流水"}</strong></div>
                : <TransactionTable
                  items={filteredImportTableItems}
                  variant="import"
                  groupByMonth
                  monthlySummaries={importMonthlySummaries}
                  showStatus
                  wrapperClassName="standard-table-wrap"
                  wrapperProps={{ role: "region", "aria-label": "账单流水表格", tabIndex: 0 }}
                  caption="账单流水"
                  columnIdPrefix="import"
                />}
            </div>
            {unresolvedCount(preview) > 0 ? <p className="import-stage-warning" role="status">有 {unresolvedCount(preview)} 条流水无法准确归属，确认后将跳过；其他流水正常导入。</p> : null}
            {ordinaryUnsupportedCount(preview) > 0 ? <p className="import-stage-warning" role="status">有流水暂不支持。</p> : null}
          </section> : null}

          {stage === "relations" && preview ? <section className="import-stage" aria-labelledby="import-relations-heading">
            <div className="import-stage-heading"><h2 id="import-relations-heading" ref={relationHeadingRef} tabIndex={-1}>配对</h2></div>
            <div className="stage-actions-top"><button type="button" className="button-secondary" onClick={() => setStage("preview")}>上一步</button><button type="button" className="button-primary" disabled={busy || ordinaryUnsupportedCount(preview) > 0} onClick={() => void confirmImport()}>{busy ? "导入中…" : "确认导入"}</button></div>
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

          {stage === "success" && result ? <section className="import-stage import-success-stage" aria-labelledby="import-success-heading"><div className="success-mark">✓</div><h2 id="import-success-heading">导入完成</h2><div className="import-success-stats"><span><strong>{result.new_rows}</strong>待新增</span><span><strong>{result.updated_rows}</strong>已更新</span><span><strong>{preview?.summary.existing ?? 0}</strong>已存在</span>{result.skipped_rows ? <span><strong>{result.skipped_rows}</strong>无法识别</span> : null}</div><div className="stage-actions"><button type="button" className="button-primary" onClick={onBack}>返回收支账本</button></div></section> : null}
    </section>
  );
}
