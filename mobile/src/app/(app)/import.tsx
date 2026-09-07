import { useMemo, useReducer, useState } from "react";
import { router } from "expo-router";
import { ActivityIndicator, StyleSheet, Text, TextInput, View } from "react-native";
import type {
  Account,
  ImportCommitResult,
  ImportMappingDecision,
  ImportPreview,
  ImportRelation,
  ImportRelationRecord,
  ImportScan,
} from "@finance-tracker/contracts";
import { ApiError } from "@finance-tracker/api-client";
import {
  createImportSession,
  importSessionReducer,
  type ImportSessionState,
} from "@finance-tracker/core";
import { Button, Header, Label, Screen, StatusMessage, Surface } from "@/components/NativeShell";
import { pickStatementFile } from "@/platform/fileSource";
import { withWriteTimeout } from "@/platform/timeout";
import { errorMessage, useSession } from "@/state/session";
import { nativeColors, nativeTypography } from "@finance-tracker/design-tokens";

type Stage = "select" | "mapping" | "preview" | "relations";
type MappingDraft = {
  accountId: number | null;
  newAccount: { draftId: string; name: string; type: string; currencies: string[] } | null;
};
type RelationState = "automatic" | "pending" | "accepted" | "rejected";
type RelationDraft = {
  state: RelationState;
  secondary: ImportRelationRecord | null;
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

function errorCode(cause: unknown): string {
  if (cause instanceof ApiError) return cause.code;
  if (cause instanceof Error && cause.message) return cause.message;
  return "request_failed";
}

function errorText(code: string | null): string | null {
  if (!code) return null;
  if (code === "import_password_required") return "请输入账单密码。";
  if (code === "import_password_invalid") return "账单密码错误，请重试。";
  if (code === "import_channel_unrecognized") return "无法识别账单渠道，请重新选择文件。";
  if (code === "import_mapping_incomplete") return "请为每个来源账户选择系统账户。";
  if (code === "import_account_unavailable") return "所选账户已不可用，请重新选择。";
  if (code === "import_account_name_conflict") return "账户名称已存在，请修改后重试。";
  if (code === "import_account_draft_invalid") return "新账户信息无效，请修改后重试。";
  if (code === "import_mapping_stale") return "账户映射已变化，请重新扫描。";
  if (code === "import_preview_stale") return "文件内容已经变化，请重新选择文件。";
  if (code === "import_relation_reconfirmation_required" || code === "import_relation_preview_stale" || code === "import_relation_candidate_invalid") {
    return "相关流水已变化，请重新确认配对。";
  }
  if (code === "relation_impact_required") return "这次导入会影响已关联的流水，请先处理关联。";
  return errorMessage(code);
}

function importTokenFrom(cause: unknown): string | null {
  if (!(cause instanceof ApiError)) return null;
  return typeof cause.importToken === "string" && cause.importToken ? cause.importToken : null;
}

function newIdempotencyKey(): string {
  const random = typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `cash-import-${random}`;
}

function mappingFor(scan: ImportScan): Record<string, MappingDraft> {
  return Object.fromEntries(scan.groups.map((group) => [
    group.group_id,
    { accountId: group.suggestion.account_id, newAccount: null },
  ]));
}

function relationDraftFor(relation: ImportRelation): RelationDraft {
  return {
    state: relation.automatic ? "automatic" : "pending",
    secondary: relation.automatic ? relation.secondary : null,
  };
}

function relationDecision(relation: ImportRelation, draft: RelationDraft): Record<string, unknown> | null {
  const endpoint = (record: ImportRelationRecord | null, key: "primary" | "secondary") => {
    if (!record) return {};
    return record.fact_id
      ? { [`${key}_fact_id`]: record.fact_id }
      : { [`${key}_record_id`]: record.record_id };
  };
  const base = {
    proposal_key: relation.id,
    kind: relation.kind,
    subtype: relation.subtype,
    rule_id: relation.rule_id,
    ...endpoint(relation.primary, "primary"),
  };
  if (draft.state === "rejected") return { ...base, status: "rejected" };
  if (!draft.secondary) return null;
  return { ...base, ...endpoint(draft.secondary, "secondary"), status: "accepted" };
}

function recordLabel(record: ImportRelationRecord | null): string {
  if (!record) return "未选择对侧流水";
  return `${record.counterparty || "未填写对方"} · ${record.amount} ${record.currency}`;
}

function mappingDecision(scan: ImportScan, drafts: Record<string, MappingDraft>): ImportMappingDecision[] {
  return scan.groups.map((group) => {
    const draft = drafts[group.group_id];
    return {
      group_id: group.group_id,
      account_id: draft?.newAccount ? null : draft?.accountId ?? null,
      mapping_revision: group.suggestion.mapping_revision,
      new_account: draft?.newAccount
        ? {
            draft_id: draft.newAccount.draftId,
            name: draft.newAccount.name.trim(),
            type: draft.newAccount.type,
            currencies: draft.newAccount.currencies,
          }
        : null,
    };
  });
}

function incompleteMapping(scan: ImportScan | null, drafts: Record<string, MappingDraft>): boolean {
  return !scan || scan.groups.length === 0 || scan.groups.some((group) => {
    const draft = drafts[group.group_id];
    return !draft?.accountId && !draft?.newAccount?.name.trim();
  });
}

function ordinaryUnsupportedCount(preview: ImportPreview): number {
  return Math.max(0, preview.summary.unsupported - (preview.summary.unresolved ?? 0));
}

export default function ImportScreen() {
  const { client, activeRole } = useSession();
  const [importState, dispatch] = useReducer(importSessionReducer, undefined, createImportSession);
  const [stage, setStage] = useState<Stage>("select");
  const [file, setFile] = useState<import("@finance-tracker/contracts").FileSource | null>(null);
  const [scan, setScan] = useState<ImportScan | null>(null);
  const [drafts, setDrafts] = useState<Record<string, MappingDraft>>({});
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [relationDrafts, setRelationDrafts] = useState<Record<string, RelationDraft>>({});
  const [password, setPassword] = useState("");
  const [passwordRequired, setPasswordRequired] = useState(false);
  const [importToken, setImportToken] = useState<string | null>(null);
  const [idempotencyKey, setIdempotencyKey] = useState<string | null>(null);
  const [errorOverride, setErrorOverride] = useState<string | null>(null);
  const [result, setResult] = useState<ImportCommitResult | null>(null);
  const writable = activeRole === "admin" || activeRole === "editor";

  const mappingComplete = !incompleteMapping(scan, drafts);
  const pendingRelations = useMemo(() => (preview?.relations ?? []).filter((relation) => {
    const draft = relationDrafts[relation.id] ?? relationDraftFor(relation);
    return draft.state === "pending";
  }).length, [preview, relationDrafts]);

  async function scanFile(nextFile: import("@finance-tracker/contracts").FileSource, nextPassword = "", nextToken?: string) {
    dispatch({ type: "request_started" });
    setErrorOverride(null);
    try {
      const nextScan = await client.scanCashImport(nextFile, undefined, nextPassword || undefined, nextToken);
      setFile(nextFile);
      setScan(nextScan);
      setDrafts(mappingFor(nextScan));
      setPreview(null);
      setRelationDrafts({});
      setImportToken(nextScan.import_token ?? nextToken ?? null);
      if (nextScan.import_token || nextToken) setIdempotencyKey((current) => current ?? newIdempotencyKey());
      setPasswordRequired(false);
      setPassword("");
      setStage("mapping");
      dispatch({ type: "file_selected", fileName: nextFile.name });
    } catch (cause) {
      const token = importTokenFrom(cause);
      if (token) {
        setImportToken(token);
        setIdempotencyKey((current) => current ?? newIdempotencyKey());
      }
      const code = errorCode(cause);
      dispatch({ type: "request_failed", errorCode: code, importToken: token ?? undefined });
      if (code === "import_password_required" || code === "import_password_invalid") {
        setPasswordRequired(true);
        setPassword("");
      }
      setErrorOverride(null);
    }
  }

  async function chooseFile() {
    setErrorOverride(null);
    try {
      const nextFile = await pickStatementFile();
      if (!nextFile) {
        dispatch({ type: "file_cancelled" });
        return;
      }
      setFile(nextFile);
      setScan(null);
      setDrafts({});
      setPreview(null);
      setRelationDrafts({});
      setResult(null);
      setImportToken(null);
      setIdempotencyKey(null);
      setPassword("");
      setPasswordRequired(false);
      setStage("select");
      dispatch({ type: "file_selected", fileName: nextFile.name });
      await scanFile(nextFile);
    } catch (cause) {
      const code = errorCode(cause);
      dispatch({ type: "request_failed", errorCode: code });
      setErrorOverride(code === "request_failed" ? "文件读取失败，请重新选择。" : null);
    }
  }

  async function scanWithPassword() {
    if (!file || !password) return;
    await scanFile(file, password, importToken ?? undefined);
  }

  async function loadPreview() {
    if (!file || !scan || !mappingComplete) return;
    dispatch({ type: "request_started" });
    setErrorOverride(null);
    try {
      const nextPreview = await client.previewCashImport(
        file,
        "",
        undefined,
        password || undefined,
        mappingDecision(scan, drafts),
        importToken ?? undefined,
      );
      setPreview(nextPreview);
      setImportToken(nextPreview.import_token ?? importToken);
      setRelationDrafts({});
      dispatch({ type: "preview_ready", preview: nextPreview });
      setStage("preview");
    } catch (cause) {
      const token = importTokenFrom(cause);
      if (token) setImportToken(token);
      const code = errorCode(cause);
      dispatch({ type: "request_failed", errorCode: code, importToken: token ?? undefined });
      if (code === "import_password_required" || code === "import_password_invalid") {
        setPasswordRequired(true);
        setPassword("");
        setStage("select");
      }
    }
  }

  function openRelations() {
    if (!preview) return;
    setRelationDrafts(Object.fromEntries(preview.relations.map((relation) => [relation.id, relationDrafts[relation.id] ?? relationDraftFor(relation)])));
    setStage("relations");
  }

  function chooseCandidate(relation: ImportRelation, candidate: ImportRelationRecord | null) {
    setRelationDrafts((current) => ({
      ...current,
      [relation.id]: { state: candidate ? "accepted" : "pending", secondary: candidate },
    }));
  }

  function toggleRejected(relation: ImportRelation) {
    const current = relationDrafts[relation.id] ?? relationDraftFor(relation);
    setRelationDrafts((draftsForRelations) => ({
      ...draftsForRelations,
      [relation.id]: current.state === "rejected"
        ? relationDraftFor(relation)
        : { ...current, state: "rejected" },
    }));
  }

  async function confirmImport() {
    if (!file || !preview || ordinaryUnsupportedCount(preview) > 0 || !writable) return;
    const decisions = preview.relations.flatMap((relation) => {
      const draft = relationDrafts[relation.id] ?? relationDraftFor(relation);
      const decision = relationDecision(relation, draft);
      return decision ? [decision] : [];
    });
    const commitKey = importToken ? idempotencyKey ?? newIdempotencyKey() : undefined;
    if (commitKey && !idempotencyKey) setIdempotencyKey(commitKey);
    dispatch({ type: "commit_started" });
    setErrorOverride(null);
    try {
      const committed = await withWriteTimeout(client.commitCashImport(file, "", undefined, {
        password: password || undefined,
        previewDigest: preview.file.digest,
        previewRelationDigest: preview.relation_digest,
        previewChannel: preview.channel,
        relations: decisions,
        mapping: mappingDecision(scan as ImportScan, drafts),
        importToken: importToken ?? undefined,
        idempotencyKey: commitKey,
      }));
      setResult(committed);
      dispatch({ type: "commit_succeeded", result: committed });
    } catch (cause) {
      const token = importTokenFrom(cause);
      if (token) setImportToken(token);
      const code = errorCode(cause);
      dispatch({ type: "request_failed", errorCode: code, importToken: token ?? undefined });
      if (code === "import_password_required" || code === "import_password_invalid") {
        setPasswordRequired(true);
        setPassword("");
        setStage("select");
      }
    }
  }

  const currentError = errorOverride ?? errorText(importState.errorCode);
  if (importState.status === "success" && result) {
    return <Screen>
      <Header title="导入完成" detail="本次账单已导入" action={<Button onPress={() => router.replace("/(app)/ledger" as never)}>返回</Button>} />
      <Surface>
        <Text style={styles.successMark}>✓</Text>
        <Text style={styles.successTitle}>账单已导入</Text>
        <Text style={styles.successDetail}>{result.new_rows} 条新增 · {result.updated_rows} 条更新{result.skipped_rows ? ` · ${result.skipped_rows} 条跳过` : ""}</Text>
        <Button onPress={() => router.replace("/(app)/ledger" as never)} variant="primary">返回收支账本</Button>
      </Surface>
    </Screen>;
  }

  return <Screen>
      <Header title="导入账单" detail="原文件只用于本次导入" action={<Button onPress={() => router.back()}>取消</Button>} />
    {!writable && <StatusMessage title="当前角色仅可查看" detail="请切换到可编辑工作区后再导入账单。" tone="error" />}
    {currentError && <StatusMessage title={currentError} tone="error" />}

    {stage === "select" && <Surface>
      <Text style={styles.sectionTitle}>选择账单文件</Text>
      <Text style={styles.muted}>支持 CSV、PDF 和 Excel 文件。</Text>
      {file && <View style={styles.fileCard}><Text style={styles.fileName}>{file.name}</Text></View>}
      <Button disabled={!writable || importState.status === "loading"} onPress={() => void chooseFile()} variant="primary">{importState.status === "loading" ? "扫描中…" : file ? "重新选择文件" : "选择文件"}</Button>
      {passwordRequired && <View style={styles.passwordBox}><Label>账单密码</Label><TextInput autoCapitalize="none" editable={importState.status !== "loading"} onChangeText={setPassword} placeholder="输入密码后重新扫描" placeholderTextColor={nativeColors.inkFaint} secureTextEntry style={styles.input} value={password} /><Button disabled={!password || importState.status === "loading"} onPress={() => void scanWithPassword()} variant="secondary">使用密码扫描</Button></View>}
    </Surface>}

    {stage === "mapping" && scan && <Surface>
      <View style={styles.stageHeader}><Text style={styles.sectionTitle}>映射账户</Text><Text style={styles.mono}>{scan.channel_label}</Text></View>
      <Text style={styles.muted}>识别到 {scan.groups.length} 个来源账户</Text>
      {scan.unresolved_count ? <StatusMessage title={`${scan.unresolved_count} 条流水无法准确归属`} detail="确认后会跳过，其余流水仍可导入。" /> : null}
      {scan.groups.map((group) => {
        const draft = drafts[group.group_id] ?? { accountId: null, newAccount: null };
        const selected = draft.accountId ? scan.accounts.find((account) => account.id === draft.accountId) : null;
        const missingCurrencies = selected ? group.currencies.filter((currency) => !(selected.currencies ?? []).includes(currency)) : [];
        return <View key={group.group_id} style={styles.mappingGroup}>
          <Text style={styles.groupName}>{group.display_name}</Text>
          <Text style={styles.muted}>{group.masked_evidence} · {group.currencies.join(" / ")} · {group.row_count} 条流水</Text>
          <View style={styles.choiceList}>{scan.accounts.map((account) => <Button key={account.id} disabled={!writable || importState.status === "loading"} onPress={() => setDrafts((current) => ({ ...current, [group.group_id]: { accountId: account.id, newAccount: null } }))} variant={draft.accountId === account.id ? "primary" : "secondary"}>{account.name}</Button>)}</View>
          <Button disabled={!writable || importState.status === "loading"} onPress={() => setDrafts((current) => ({ ...current, [group.group_id]: { accountId: null, newAccount: draft.newAccount ?? { draftId: `draft-${group.group_id}`, name: group.display_name, type: "cash", currencies: [...group.currencies] } } }))} variant={draft.newAccount ? "primary" : "secondary"}>{draft.newAccount ? `新账户：${draft.newAccount.name}` : "创建同名新账户"}</Button>
          {draft.newAccount && <View style={styles.newAccountBox}><Label>新账户名称</Label><TextInput editable={writable} onChangeText={(name) => setDrafts((current) => ({ ...current, [group.group_id]: { ...draft, newAccount: draft.newAccount ? { ...draft.newAccount, name } : null } }))} style={styles.input} value={draft.newAccount.name} /><Label>账户类型</Label><View style={styles.choiceList}>{[["cash", "现金账户"], ["loan", "贷款账户"], ["lend", "借款账户"]].map(([value, label]) => <Button key={value} disabled={!writable} onPress={() => setDrafts((current) => ({ ...current, [group.group_id]: { ...draft, newAccount: draft.newAccount ? { ...draft.newAccount, type: value } : null } }))} variant={draft.newAccount?.type === value ? "primary" : "secondary"}>{label}</Button>)}</View></View>}
          {missingCurrencies.length > 0 && <Text style={styles.warning}>将为「{selected?.name}」补充：{missingCurrencies.join("、")}</Text>}
          {!draft.accountId && !draft.newAccount && <Text style={styles.warning}>请选择系统账户或创建新账户。</Text>}
        </View>;
      })}
      <Button disabled={!mappingComplete || importState.status === "loading" || !writable} onPress={() => void loadPreview()} variant="primary">{importState.status === "loading" ? "核对中…" : "确认映射"}</Button>
    </Surface>}

    {stage === "preview" && preview && <Surface>
      <View style={styles.stageHeader}><Text style={styles.sectionTitle}>核对流水</Text><Text style={styles.mono}>{preview.channel_label}</Text></View>
      <View style={styles.summary}><Summary label="全部" value={preview.summary.total} /><Summary label="待新增" value={preview.summary.new} /><Summary label="已存在" value={preview.summary.existing} /><Summary label="无法识别" value={preview.summary.unresolved ?? 0} /></View>
      {preview.items.length === 0 ? <StatusMessage title="没有可核对流水" /> : <View style={styles.previewList}>{preview.items.slice(0, 40).map((item) => <View key={item.record_id} style={styles.previewRow}><View style={styles.rowMain}><Text style={styles.groupName}>{item.counterparty || "未填写对方"}</Text><Text style={styles.muted}>{item.account_name} · {item.occurred_at}</Text><Text style={styles.muted}>{recordTypeLabels[item.record_type] ?? "其他"} · {item.status === "new" ? "待新增" : item.status === "existing" ? "已存在" : item.status === "unresolved" ? "无法识别" : "暂不支持"}</Text></View><Text style={styles.amount}>{item.amount} {item.currency}</Text></View>)}</View>}
      {preview.items.length > 40 && <Text style={styles.muted}>仅展示前 40 条，确认时将处理全部记录。</Text>}
      {preview.summary.unresolved ? <Text style={styles.warning}>{preview.summary.unresolved} 条无法识别，确认后会跳过。</Text> : null}
      {ordinaryUnsupportedCount(preview) > 0 ? <Text style={styles.warning}>有暂不支持的流水，暂不能确认导入。</Text> : null}
      <Button disabled={importState.status === "loading"} onPress={() => openRelations()} variant="primary">进入关系复核</Button>
    </Surface>}

    {stage === "relations" && preview && <Surface>
      <View style={styles.stageHeader}><Text style={styles.sectionTitle}>关系复核</Text><Text style={styles.mono}>{pendingRelations} 条待处理</Text></View>
      {preview.relations.length === 0 ? <StatusMessage title="没有配对建议" detail="可以直接确认导入。" /> : <View style={styles.relationList}>{preview.relations.map((relation) => {
        const draft = relationDrafts[relation.id] ?? relationDraftFor(relation);
        const rejected = draft.state === "rejected";
        return <View key={relation.id} style={[styles.relationCard, rejected && styles.relationRejected]}>
          <View style={styles.stageHeader}><Text style={styles.groupName}>{relation.label}</Text><Text style={styles.mono}>{rejected ? "已拒绝" : relation.automatic ? "自动建议" : draft.state === "accepted" ? "已确认" : "待处理"}</Text></View>
          <Text style={styles.muted}>现金流水：{recordLabel(relation.primary)}</Text>
          {relation.secondary && <Text style={styles.muted}>建议对侧：{recordLabel(relation.secondary)}</Text>}
          {!rejected && relation.candidates.length > 0 && <View style={styles.choiceList}>{relation.candidates.map((candidate) => <Button key={candidate.record_id} disabled={importState.status === "committing" || relation.automatic} onPress={() => chooseCandidate(relation, candidate)} variant={draft.secondary?.record_id === candidate.record_id ? "primary" : "secondary"}>{recordLabel(candidate)}</Button>)}</View>}
          {!rejected && !relation.automatic && <Button disabled={importState.status === "committing"} onPress={() => chooseCandidate(relation, null)}>暂不处理</Button>}
          <Button disabled={importState.status === "committing"} onPress={() => toggleRejected(relation)} variant={rejected ? "secondary" : "danger"}>{rejected ? "撤销拒绝" : "拒绝配对"}</Button>
        </View>;
      })}</View>}
      {ordinaryUnsupportedCount(preview) > 0 && <Text style={styles.warning}>有暂不支持的流水，暂不能确认导入。</Text>}
      {importState.status === "committing" && <StatusMessage title="正在导入…" detail="请保持网络连接，不会自动重复提交。" action={<ActivityIndicator color={nativeColors.accent} />} />}
      <Button disabled={!writable || importState.status === "committing" || ordinaryUnsupportedCount(preview) > 0} onPress={() => void confirmImport()} variant="primary">确认导入</Button>
    </Surface>}
  </Screen>;
}

function Summary({ label, value }: { label: string; value: number }) {
  return <View style={styles.summaryItem}><Text style={styles.muted}>{label}</Text><Text style={styles.summaryValue}>{value}</Text></View>;
}

const styles = StyleSheet.create({
  sectionTitle: { color: nativeColors.ink, fontSize: 17, fontWeight: "700" },
  stageHeader: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: 12 },
  muted: { color: nativeColors.inkMuted, fontSize: 13, lineHeight: 19 },
  mono: { color: nativeColors.accent, fontFamily: nativeTypography.mono, fontSize: 11 },
  fileCard: { gap: 4, padding: 12, borderWidth: 1, borderColor: nativeColors.rule, backgroundColor: nativeColors.paperMuted },
  fileName: { color: nativeColors.ink, fontSize: 15, fontWeight: "700" },
  passwordBox: { gap: 8, paddingTop: 12, borderTopWidth: 1, borderTopColor: nativeColors.rule },
  input: { minHeight: 48, paddingHorizontal: 12, borderWidth: 1, borderColor: nativeColors.rule, borderRadius: 3, color: nativeColors.ink, backgroundColor: nativeColors.paperRaised, fontSize: 16 },
  mappingGroup: { gap: 8, paddingTop: 14, paddingBottom: 14, borderTopWidth: 1, borderTopColor: nativeColors.rule },
  groupName: { color: nativeColors.ink, fontSize: 15, fontWeight: "700" },
  choiceList: { flexDirection: "row", flexWrap: "wrap", gap: 8 },
  newAccountBox: { gap: 8, padding: 12, borderLeftWidth: 3, borderLeftColor: nativeColors.accent, backgroundColor: nativeColors.paperMuted },
  warning: { color: nativeColors.danger, fontSize: 13, lineHeight: 19 },
  summary: { flexDirection: "row", flexWrap: "wrap", gap: 8 },
  summaryItem: { minWidth: 76, flex: 1, gap: 3, padding: 10, borderWidth: 1, borderColor: nativeColors.rule, backgroundColor: nativeColors.paperMuted },
  summaryValue: { color: nativeColors.ink, fontFamily: nativeTypography.mono, fontSize: 20, fontWeight: "700" },
  previewList: { gap: 8 },
  previewRow: { minHeight: 72, flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: 12, padding: 12, borderWidth: 1, borderColor: nativeColors.rule, backgroundColor: nativeColors.paperRaised },
  rowMain: { flex: 1, gap: 3 },
  amount: { color: nativeColors.ink, fontFamily: nativeTypography.mono, fontSize: 13, fontWeight: "700" },
  relationList: { gap: 10 },
  relationCard: { gap: 8, padding: 12, borderWidth: 1, borderColor: nativeColors.rule, backgroundColor: nativeColors.paperRaised },
  relationRejected: { borderColor: nativeColors.danger, opacity: 0.72 },
  successMark: { color: nativeColors.income, fontFamily: nativeTypography.mono, fontSize: 42, fontWeight: "700" },
  successTitle: { color: nativeColors.ink, fontSize: 21, fontWeight: "700" },
  successDetail: { color: nativeColors.inkMuted, fontSize: 14, lineHeight: 21 },
});
