import { useMemo, useReducer, useState } from "react";
import { router } from "expo-router";
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View } from "react-native";
import type {
  ImportCommitResult,
  FileSource,
  ImportMappingDecision,
  ImportPreview,
  ImportRelation,
  ImportRelationRecord,
  ImportScan,
} from "@finance-tracker/contracts";
import { ApiError } from "@finance-tracker/api-client";
import {
  allocationBalance,
  allocationMatches,
  createImportSession,
  importSessionReducer,
  sha1Hex,
  type AllocationBalance,
} from "@finance-tracker/core";
import { Button, Header, Label, Screen, StatusMessage, Surface } from "@/components/NativeShell";
import { pickStatementFiles } from "@/platform/fileSource";
import { withWriteTimeout } from "@/platform/timeout";
import { errorMessage, useSession } from "@/state/session";
import { nativeColors, nativeTypography } from "@finance-tracker/design-tokens";
import { copy, semanticIds } from "@finance-tracker/presentation";

type Stage = "select" | "mapping" | "preview" | "relations";
type MappingDraft = {
  accountId: number | null;
  newAccount: { draftId: string; name: string; type: string; currencies: string[] } | null;
};
type RelationState = "automatic" | "pending" | "accepted" | "rejected";
type RelationDraft = {
  state: RelationState;
  kind: string;
  secondary: ImportRelationRecord | null;
};

type SelectedImportFile = {
  source: FileSource;
  sha1: string;
};

const recordTypeLabels: Record<string, string> = {
  consumption: copy.record.typeLabels.consumption,
  refund: copy.record.typeLabels.refund,
  income: copy.record.typeLabels.income,
  transfer_in: copy.record.typeLabels.transferIn,
  transfer_out: copy.record.typeLabels.transferOut,
  repayment: copy.record.typeLabels.repayment,
  withdrawal_in: copy.record.typeLabels.withdrawalIn,
  withdrawal_out: copy.record.typeLabels.withdrawalOut,
  fx_in: copy.record.typeLabels.fxIn,
  fx_out: copy.record.typeLabels.fxOut,
  other: copy.record.typeLabels.other,
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
  if (code === "import_component_allocation_incomplete") return "请补齐组合支付各组成项金额。";
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
    kind: relation.kind,
    secondary: relation.automatic ? relation.secondary : null,
  };
}

function relationDecision(relation: ImportRelation, draft: RelationDraft): Record<string, unknown> | null {
  const endpoint = (record: ImportRelationRecord | null, key: "primary" | "secondary") => {
    if (!record) return {};
    return record.fact_id
      ? { [`${key}_fact_id`]: record.fact_id }
      : record.relation_ref
        ? { [`${key}_record_ref`]: record.relation_ref }
      : { [`${key}_record_id`]: record.record_id };
  };
  const base = {
    proposal_key: relation.id,
    kind: draft.kind,
    subtype: relation.subtype,
    rule_id: relation.rule_id,
    ...endpoint(relation.primary, "primary"),
  };
  if (draft.state === "rejected") return { ...base, status: "rejected" };
  if (!draft.secondary) return null;
  return { ...base, ...endpoint(draft.secondary, "secondary"), status: "accepted" };
}

function recordLabel(record: ImportRelationRecord | null): string {
  if (!record) return copy.import.notSelectedSecondary;
  return `${record.counterparty || copy.ledger.noCounterparty} · ${record.amount} ${record.currency}`;
}

function mappingDecision(scan: ImportScan, drafts: Record<string, MappingDraft>, allocationDrafts: Record<string, string[]> = {}): ImportMappingDecision[] {
  const decisions: ImportMappingDecision[] = scan.groups.map((group) => {
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
  const componentAllocations = Object.fromEntries(
    Object.entries(allocationDrafts)
      .filter(([, values]) => values.length > 1)
      .map(([recordId, values]) => [recordId, values.map((amount) => ({ amount }))]),
  );
  if (decisions.length > 0 && Object.keys(componentAllocations).length > 0) {
    decisions[0] = { ...decisions[0], component_allocations: componentAllocations };
  }
  return decisions;
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

function allocationRequiredCount(preview: ImportPreview): number {
  return preview.summary.requires_allocation
    ?? preview.items.filter((item) => item.status === "requires_allocation").length;
}

function unsignedAmount(value: string | null | undefined): string {
  return String(value ?? "").trim().replace(/^[+-]/, "");
}

function allocationStatusLabel(item: ImportPreview["items"][number], balance: AllocationBalance): string {
  if (balance.state === "invalid") return "金额无效";
  if (balance.state === "complete") return `已匹配 ${balance.total} ${item.currency}`;
  if (balance.difference.startsWith("-")) return `超出 ${balance.difference.slice(1)} ${item.currency}`;
  return `还差 ${balance.difference} ${item.currency}`;
}

export default function ImportScreen() {
  const { client, activeRole } = useSession();
  const [importState, dispatch] = useReducer(importSessionReducer, undefined, createImportSession);
  const [stage, setStage] = useState<Stage>("select");
  const [selectedFiles, setSelectedFiles] = useState<SelectedImportFile[]>([]);
  const [scan, setScan] = useState<ImportScan | null>(null);
  const [drafts, setDrafts] = useState<Record<string, MappingDraft>>({});
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [allocationDrafts, setAllocationDrafts] = useState<Record<string, string[]>>({});
  const [relationDrafts, setRelationDrafts] = useState<Record<string, RelationDraft>>({});
  const [passwords, setPasswords] = useState<Record<string, string>>({});
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

  function clearImportProgress() {
    setScan(null);
    setDrafts({});
    setPreview(null);
    setAllocationDrafts({});
    setRelationDrafts({});
    setResult(null);
    setImportToken(null);
    setIdempotencyKey(null);
    setPasswords({});
    setStage("select");
  }

  async function scanSelectedFiles(nextPasswords = passwords, nextToken = importToken) {
    if (selectedFiles.length === 0 || importState.status === "loading") return;
    dispatch({ type: "request_started" });
    setErrorOverride(null);
    try {
      const nextScan = await client.scanCashImportBatch(
        selectedFiles.map((item) => item.source),
        undefined,
        nextPasswords,
        nextToken ?? undefined,
      );
      setScan(nextScan);
      setPreview(null);
      setAllocationDrafts({});
      setRelationDrafts({});
      setImportToken(nextScan.import_token ?? nextToken ?? null);
      if (nextScan.import_token || nextToken) setIdempotencyKey((current) => current ?? newIdempotencyKey());
      const ready = nextScan.ready !== false && !(nextScan.files ?? []).some((item) => item.status !== "ready");
      if (ready) {
        setDrafts(mappingFor(nextScan));
        setStage("mapping");
      }
      dispatch({ type: "file_selected", fileName: selectedFiles[0]?.source.name ?? "statement" });
    } catch (cause) {
      const token = importTokenFrom(cause);
      if (token) {
        setImportToken(token);
        setIdempotencyKey((current) => current ?? newIdempotencyKey());
      }
      const code = errorCode(cause);
      dispatch({ type: "request_failed", errorCode: code, importToken: token ?? undefined });
      setErrorOverride(code === "import_channel_unrecognized"
        ? "无法识别账单渠道，请删除对应文件后重试。"
        : code === "import_files_not_ready"
          ? "请先补充密码或删除无法识别的文件。"
          : "文件识别失败，请重试。");
    }
  }

  async function chooseFile() {
    setErrorOverride(null);
    try {
      const picked = await pickStatementFiles();
      if (picked.length === 0) {
        dispatch({ type: "file_cancelled" });
        return;
      }
      const existing = new Set(selectedFiles.map((item) => item.sha1));
      const next: SelectedImportFile[] = [];
      for (const source of picked) {
        if (selectedFiles.length + next.length >= 20) break;
        const bytes = await source.read();
        const sha1 = sha1Hex(bytes);
        if (existing.has(sha1)) continue;
        const cached = { ...source, size: source.size ?? bytes.byteLength, read: async () => bytes };
        next.push({ source: cached, sha1 });
        existing.add(sha1);
      }
      if (next.length > 0) {
        setSelectedFiles((current) => [...current, ...next]);
        clearImportProgress();
      }
    } catch (cause) {
      const code = errorCode(cause);
      dispatch({ type: "request_failed", errorCode: code });
      setErrorOverride(code === "request_failed" ? "文件读取失败，请重新选择。" : null);
    }
  }

  function removeFile(sha1: string) {
    setSelectedFiles((current) => current.filter((item) => item.sha1 !== sha1));
    clearImportProgress();
  }

  async function loadPreview(nextStage: "preview" | "relations" = "preview") {
    if (selectedFiles.length === 0 || !scan || !mappingComplete) return;
    dispatch({ type: "request_started" });
    setErrorOverride(null);
    try {
      const nextPreview = await client.previewCashImportBatch(
        undefined,
        passwords,
        mappingDecision(scan, drafts, allocationDrafts),
        importToken ?? undefined,
      );
      setPreview(nextPreview);
      setAllocationDrafts((current) => {
        const next = { ...current };
        for (const item of nextPreview.items) {
          const components = item.components ?? [];
          if (components.length < 2) continue;
          const key = item.relation_ref ?? item.record_id;
          next[key] = components.map((component, index) => current[key]?.[index] ?? unsignedAmount(component.amount));
        }
        return next;
      });
      setImportToken(nextPreview.import_token ?? importToken);
      setRelationDrafts({});
      dispatch({ type: "preview_ready", preview: nextPreview });
      setStage(nextStage);
    } catch (cause) {
      const token = importTokenFrom(cause);
      if (token) setImportToken(token);
      const code = errorCode(cause);
      dispatch({ type: "request_failed", errorCode: code, importToken: token ?? undefined });
      if (code === "import_password_required" || code === "import_password_invalid") {
        setPasswords({});
        setStage("select");
      }
    }
  }

  function openRelations() {
    if (!preview) return;
    const aggregateItems = preview.items.filter((item) => (item.components?.length ?? 0) > 1);
    const allocationReady = aggregateItems.length > 0
      ? aggregateItems.every((item) => allocationMatches(item, allocationDrafts[item.relation_ref ?? item.record_id] ?? []))
      : allocationRequiredCount(preview) === 0;
    if (!allocationReady) {
      setErrorOverride("请补齐组合支付各组成项金额，且合计等于流水金额。");
      return;
    }
    if (aggregateItems.length > 0) {
      void loadPreview("relations");
      return;
    }
    setRelationDrafts(Object.fromEntries(preview.relations.map((relation) => [relation.id, relationDrafts[relation.id] ?? relationDraftFor(relation)])));
    setStage("relations");
  }

  function chooseCandidate(relation: ImportRelation, candidate: ImportRelationRecord | null) {
    const previous = relationDrafts[relation.id] ?? relationDraftFor(relation);
    setRelationDrafts((current) => ({
      ...current,
      [relation.id]: { ...previous, state: candidate ? "accepted" : "pending", secondary: candidate },
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
    if (selectedFiles.length === 0 || !preview || ordinaryUnsupportedCount(preview) > 0 || !writable) return;
    const aggregateItems = preview.items.filter((item) => (item.components?.length ?? 0) > 1);
    const allocationReady = aggregateItems.length > 0
      ? aggregateItems.every((item) => allocationMatches(item, allocationDrafts[item.relation_ref ?? item.record_id] ?? []))
      : allocationRequiredCount(preview) === 0;
    if (!allocationReady) {
      setErrorOverride("请补齐组合支付各组成项金额，且合计等于流水金额。");
      return;
    }
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
      const committed = await withWriteTimeout(client.commitCashImportBatch(undefined, {
        passwords,
        previewDigest: preview.file.digest,
        previewRelationDigest: preview.relation_digest,
        previewChannel: preview.channel,
        relations: decisions,
        mapping: mappingDecision(scan as ImportScan, drafts, allocationDrafts),
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
        setPasswords({});
        setStage("select");
      }
    }
  }

  const currentError = errorOverride ?? errorText(importState.errorCode);
  const allocationItems = preview?.items.filter((item) => (item.components?.length ?? 0) > 1) ?? [];
  const allocationComplete = preview
    ? allocationItems.length > 0
      ? allocationItems.every((item) => allocationMatches(item, allocationDrafts[item.relation_ref ?? item.record_id] ?? []))
      : allocationRequiredCount(preview) === 0
    : false;
  const renderAllocationDetail = (item: ImportPreview["items"][number]) => {
    const components = item.components ?? [];
    if (components.length < 2) return null;
    const allocationKey = item.relation_ref ?? item.record_id;
    const values = allocationDrafts[allocationKey] ?? [];
    const balance = allocationBalance(item, values);
    return <View
      testID={`${semanticIds.importAllocation}.${allocationKey}`}
      style={[styles.allocationDetail, balance.state === "complete" && styles.allocationDetailComplete]}
    >
      <View style={styles.allocationFields}>
        {components.map((component, index) => <View key={`${allocationKey}-${component.ordinal}`} style={styles.allocationField}>
          <View style={styles.allocationLabel}><Text style={styles.muted}>{component.account_name || component.source_label}</Text><Text style={styles.allocationHint}>账户 · {item.currency}</Text></View>
          <TextInput
            testID={`import-allocation-${allocationKey}-${component.ordinal}`}
            accessibilityLabel={`${component.account_name || component.source_label}分摊金额`}
            editable={writable && importState.status !== "loading" && importState.status !== "committing"}
            keyboardType="decimal-pad"
            onChangeText={(value) => {
              setAllocationDrafts((current) => {
                const next = [...(current[allocationKey] ?? [])];
                next[index] = value;
                return { ...current, [allocationKey]: next };
              });
              setErrorOverride(null);
            }}
            style={styles.allocationInput}
            value={values[index] ?? ""}
          />
        </View>)}
      </View>
      <View style={styles.allocationSummary}><Text style={balance.state === "complete" ? styles.allocationComplete : styles.allocationIncomplete}>{allocationStatusLabel(item, balance)}</Text></View>
    </View>;
  };
  const stageIndex = stage === "select" ? 1 : stage === "mapping" ? 2 : stage === "preview" ? 3 : 4;
  if (importState.status === "success" && result) {
    return <Screen testID={semanticIds.importSuccess}>
      <Header title={copy.import.success} detail={copy.import.successDetail} action={<Button onPress={() => router.replace("/(app)/ledger" as never)}>{copy.common.back}</Button>} />
      <Surface>
        <Text style={styles.successMark}>✓</Text>
        <Text style={styles.successTitle}>{copy.import.successDetail}</Text>
        <Text style={styles.successDetail}>{result.new_rows} {copy.import.statusNew} · {result.updated_rows} 已更新{result.skipped_rows ? ` · ${result.skipped_rows} ${copy.import.unresolved}` : ""}</Text>
        <Button onPress={() => router.replace("/(app)/ledger" as never)} variant="primary">{copy.import.backLedger}</Button>
      </Surface>
    </Screen>;
  }

  return <Screen testID={semanticIds.importScreen}>
      <Header title={copy.import.title} detail={copy.import.detail} action={<Button onPress={() => router.back()}>{copy.common.cancel}</Button>} />
    <View testID={semanticIds.importStepper} style={styles.stepper}>{[
      ["select", copy.import.selectFile],
      ["mapping", copy.import.mapAccount],
      ["preview", copy.import.preview],
      ["relations", copy.import.relations],
    ].map(([value, label], index) => <Pressable key={value} accessibilityRole="button" disabled={index > stageIndex - 1 || importState.status === "loading" || importState.status === "committing"} onPress={() => {
      if (value === "select") setStage("select");
      if (value === "mapping" && scan) setStage("mapping");
      if (value === "preview" && preview) setStage("preview");
      if (value === "relations" && preview) openRelations();
    }} style={[styles.step, value === stage && styles.stepActive, index < stageIndex - 1 && styles.stepComplete]}><Text style={styles.stepNumber}>{index + 1}</Text><Text numberOfLines={1} style={styles.stepLabel}>{label}</Text></Pressable>)}</View>
    {!writable && <StatusMessage title={copy.ledger.readOnly} detail={copy.ledger.readOnlyImportDetail} tone="error" />}
    {currentError && <StatusMessage title={currentError} tone="error" />}

    {stage === "select" && <Surface testID={semanticIds.importFile}>
      <Text style={styles.sectionTitle}>{copy.import.chooseFile}</Text>
      <Text style={styles.muted}>{copy.import.supportedFiles}</Text>
      <Button testID={semanticIds.importChooseFile} disabled={!writable || importState.status === "loading" || selectedFiles.length >= 20} onPress={() => void chooseFile()} variant="primary">{selectedFiles.length > 0 ? copy.import.chooseMore : copy.import.selectFile}</Button>
      {selectedFiles.length > 0 && <View testID={semanticIds.importSelectedFiles} style={styles.selectedFilesBox}>
        <View style={styles.stageHeader}><Text style={styles.muted}>{copy.import.selectedFiles} {selectedFiles.length}/20</Text><Text style={styles.muted}>SHA-1 去重</Text></View>
        {selectedFiles.map((entry, index) => {
          const status = scan?.files?.find((item) => item.index === index);
          return <View key={entry.sha1} style={styles.fileRow}>
            <View style={styles.fileRowMain}><Text style={styles.fileName}>{entry.source.name}</Text><Text style={styles.muted}>{status?.channel_label ?? (entry.source.size ? `${Math.ceil(entry.source.size / 1024)} KB` : "")}</Text></View>
            <Text style={styles.fileStatus}>{status?.status === "ready" ? copy.import.fileReady : status?.status === "error" ? copy.import.fileScanError : status?.status === "password_required" ? copy.import.password : ""}</Text>
            <Button testID={`${semanticIds.importRemoveFile}.${entry.sha1}`} disabled={importState.status === "loading"} onPress={() => removeFile(entry.sha1)} variant="secondary">{copy.import.removeFile}</Button>
          </View>;
        })}
      </View>}
      {scan?.files?.filter((item) => item.status === "password_required").map((item) => {
        const selected = selectedFiles[item.index];
        if (!selected) return null;
        return <View key={item.index} style={styles.passwordBox}>
          <Label>{selected.source.name} · {copy.import.filePassword}</Label>
          <TextInput testID={`${semanticIds.importFilePassword}.${item.index}`} autoCapitalize="none" editable={importState.status !== "loading"} onChangeText={(value) => setPasswords((current) => ({ ...current, [String(item.index)]: value }))} placeholder={copy.import.filePassword} placeholderTextColor={nativeColors.inkFaint} secureTextEntry style={styles.input} value={passwords[String(item.index)] ?? ""} />
        </View>;
      })}
      {scan?.files?.filter((item) => item.status === "error").map((item) => <Text key={item.index} style={styles.warning}>{item.filename ?? selectedFiles[item.index]?.source.name}：{copy.import.fileScanError}</Text>)}
      <View style={styles.stageActions}><Button disabled={importState.status === "loading"} onPress={() => router.back()}>{copy.common.cancel}</Button><Button testID={semanticIds.importNext} disabled={!writable || selectedFiles.length === 0 || importState.status === "loading" || Boolean(scan?.files?.some((item) => item.status === "error" || (item.status === "password_required" && !(passwords[String(item.index)] ?? "").trim())))} onPress={() => void scanSelectedFiles()} variant="primary">{importState.status === "loading" ? copy.import.scanning : copy.import.next}</Button></View>
    </Surface>}

    {stage === "mapping" && scan && <Surface testID={semanticIds.importMapping}>
      <View style={styles.stageHeader}><Text style={styles.sectionTitle}>{copy.import.mapAccount}</Text><Text style={styles.mono}>{scan.channel_label}</Text></View>
      <Text style={styles.muted}>识别到 {scan.groups.length} {copy.import.accountSourceCount}</Text>
      {scan.unresolved_count ? <StatusMessage title={`${scan.unresolved_count} ${copy.import.unresolvedCountDetail}`} detail={copy.import.skipDetail} /> : null}
      {scan.groups.map((group) => {
        const draft = drafts[group.group_id] ?? { accountId: null, newAccount: null };
        const selected = draft.accountId ? scan.accounts.find((account) => account.id === draft.accountId) : null;
        const missingCurrencies = selected ? group.currencies.filter((currency) => !(selected.currencies ?? []).includes(currency)) : [];
        return <View key={group.group_id} style={styles.mappingGroup}>
          <Text style={styles.groupName}>{group.display_name}</Text>
          <Text style={styles.muted}>{group.masked_evidence} · {group.currencies.join(" / ")} · {group.row_count} 条流水</Text>
          <View style={styles.choiceList}>{scan.accounts.map((account) => <Button key={account.id} disabled={!writable || importState.status === "loading"} onPress={() => { setDrafts((current) => ({ ...current, [group.group_id]: { accountId: account.id, newAccount: null } })); setPreview(null); setAllocationDrafts({}); setRelationDrafts({}); }} variant={draft.accountId === account.id ? "primary" : "secondary"}>{account.name}</Button>)}</View>
          <Button disabled={!writable || importState.status === "loading"} onPress={() => { setDrafts((current) => ({ ...current, [group.group_id]: { accountId: null, newAccount: draft.newAccount ?? { draftId: `draft-${group.group_id}`, name: group.display_name, type: "cash", currencies: [...group.currencies] } } })); setPreview(null); setAllocationDrafts({}); setRelationDrafts({}); }} variant={draft.newAccount ? "primary" : "secondary"}>{draft.newAccount ? `${copy.import.newAccountPrefix}${draft.newAccount.name}` : copy.import.createNamedAccount}</Button>
          {draft.newAccount && <View style={styles.newAccountBox}><Label>{copy.import.newAccountName}</Label><TextInput editable={writable} onChangeText={(name) => { setDrafts((current) => ({ ...current, [group.group_id]: { ...draft, newAccount: draft.newAccount ? { ...draft.newAccount, name } : null } })); setPreview(null); setAllocationDrafts({}); setRelationDrafts({}); }} style={styles.input} value={draft.newAccount.name} /><Label>{copy.import.accountType}</Label><View style={styles.choiceList}>{[["cash", copy.import.cashAccount], ["loan", copy.import.loanAccount], ["lend", copy.import.lendAccount]].map(([value, label]) => <Button key={value} disabled={!writable} onPress={() => { setDrafts((current) => ({ ...current, [group.group_id]: { ...draft, newAccount: draft.newAccount ? { ...draft.newAccount, type: value } : null } })); setPreview(null); setAllocationDrafts({}); setRelationDrafts({}); }} variant={draft.newAccount?.type === value ? "primary" : "secondary"}>{label}</Button>)}</View></View>}
          {missingCurrencies.length > 0 && <Text style={styles.warning}>{copy.import.currencySupplementPrefix}「{selected?.name}」{copy.import.currencySupplementSuffix}：{missingCurrencies.join("、")}</Text>}
          {!draft.accountId && !draft.newAccount && <Text style={styles.warning}>{copy.import.selectMapping}</Text>}
        </View>;
      })}
      <View style={styles.stageActions}><Button testID={semanticIds.importPrevious} disabled={importState.status === "loading"} onPress={() => setStage("select")}>{copy.import.previous}</Button><Button testID={semanticIds.importNext} disabled={!mappingComplete || importState.status === "loading" || !writable} onPress={() => void loadPreview()} variant="primary">{importState.status === "loading" ? copy.import.mapping : copy.import.mapConfirm}</Button></View>
    </Surface>}

    {stage === "preview" && preview && <Surface testID={semanticIds.importPreview}>
      <View style={styles.stageHeader}><Text style={styles.sectionTitle}>{copy.import.preview}</Text><Text style={styles.mono}>{preview.channel_label}</Text></View>
      <View style={styles.summary}><Summary label={copy.import.all} value={preview.summary.total} /><Summary label={copy.import.new} value={preview.summary.new} /><Summary label={copy.import.existing} value={preview.summary.existing} /><Summary label={copy.import.statusUnresolved} value={preview.summary.unresolved ?? 0} /></View>
      {preview.items.length === 0 ? <StatusMessage title={copy.import.noPreviewRecords} /> : <View style={styles.previewList}>{preview.items.map((item) => <View key={item.relation_ref ?? item.record_id} style={styles.previewItem}><View style={styles.previewRow}><View style={styles.rowMain}><Text style={styles.groupName}>{item.counterparty || copy.ledger.noCounterparty}</Text><Text style={styles.muted}>{item.account_name} · {item.occurred_at}</Text><Text style={styles.muted}>{recordTypeLabels[item.record_type] ?? copy.record.typeLabels.other} · {item.status === "new" ? copy.import.statusNew : item.status === "existing" ? copy.import.statusExisting : item.status === "unresolved" ? copy.import.statusUnresolved : item.status === "requires_allocation" ? "待补分配" : copy.import.statusUnsupported}</Text></View><Text style={styles.amount}>{item.amount} {item.currency}</Text></View>{renderAllocationDetail(item)}</View>)}</View>}
      {preview.summary.unresolved ? <Text style={styles.warning}>{preview.summary.unresolved} 条无法识别，确认后会跳过。</Text> : null}
      {ordinaryUnsupportedCount(preview) > 0 ? <Text style={styles.warning}>{copy.import.unsupportedCannotConfirm}</Text> : null}
      <View style={styles.stageActions}><Button testID={semanticIds.importPrevious} disabled={importState.status === "loading"} onPress={() => setStage("mapping")}>{copy.import.previous}</Button><Button testID={semanticIds.importNext} disabled={importState.status === "loading" || !allocationComplete || !writable} onPress={() => openRelations()} variant="primary">{copy.import.next}</Button></View>
    </Surface>}

    {stage === "relations" && preview && <Surface testID={semanticIds.importRelations}>
      <View style={styles.stageHeader}><Text style={styles.sectionTitle}>{copy.import.relations}</Text><Text style={styles.mono}>{pendingRelations} {copy.import.pendingCount}</Text></View>
      {preview.relations.length === 0 ? <StatusMessage title={copy.import.noRelationSuggestions} detail={copy.import.noRelationSuggestionsDetail} /> : <View style={styles.relationList}>{preview.relations.map((relation) => {
        const draft = relationDrafts[relation.id] ?? relationDraftFor(relation);
        const rejected = draft.state === "rejected";
        return <View key={relation.id} style={[styles.relationCard, rejected && styles.relationRejected]}>
          <View style={styles.stageHeader}><Text style={styles.groupName}>{relation.label}</Text><Text style={styles.mono}>{rejected ? copy.import.statusRejected : relation.automatic ? copy.import.automaticSuggestion : draft.state === "accepted" ? copy.import.confirmed : copy.import.statusPending}</Text></View>
          {!relation.automatic && <View style={styles.choiceList}>{Object.entries(copy.import.relationKinds).map(([value, label]) => <Button key={value} disabled={importState.status === "committing"} onPress={() => setRelationDrafts((current) => ({ ...current, [relation.id]: { ...(current[relation.id] ?? relationDraftFor(relation)), kind: value } }))} variant={draft.kind === value ? "primary" : "secondary"}>{label}</Button>)}</View>}
          <Text style={styles.muted}>{copy.import.cashRecord}：{recordLabel(relation.primary)}</Text>
          {relation.secondary && <Text style={styles.muted}>{copy.import.suggestedCounterRecord}：{recordLabel(relation.secondary)}</Text>}
          {!rejected && relation.candidates.length > 0 && <View style={styles.choiceList}>{relation.candidates.map((candidate) => <Button key={candidate.record_id} disabled={importState.status === "committing" || relation.automatic} onPress={() => chooseCandidate(relation, candidate)} variant={draft.secondary?.record_id === candidate.record_id ? "primary" : "secondary"}>{recordLabel(candidate)}</Button>)}</View>}
          {!rejected && !relation.automatic && <Button disabled={importState.status === "committing"} onPress={() => chooseCandidate(relation, null)}>{copy.import.defer}</Button>}
          <Button disabled={importState.status === "committing"} onPress={() => toggleRejected(relation)} variant={rejected ? "secondary" : "danger"}>{rejected ? copy.import.undoReject : copy.import.reject}</Button>
        </View>;
      })}</View>}
      {ordinaryUnsupportedCount(preview) > 0 && <Text style={styles.warning}>{copy.import.unsupportedCannotConfirm}</Text>}
      {importState.status === "committing" && <StatusMessage title={copy.import.committing} detail={copy.import.committingDetail} action={<ActivityIndicator color={nativeColors.accent} />} />}
      <View style={styles.stageActions}><Button testID={semanticIds.importPrevious} disabled={importState.status === "committing"} onPress={() => setStage("preview")}>{copy.import.previous}</Button><Button testID={semanticIds.importConfirm} disabled={!writable || importState.status === "committing" || ordinaryUnsupportedCount(preview) > 0 || !allocationComplete} onPress={() => void confirmImport()} variant="primary">{copy.import.confirm}</Button></View>
    </Surface>}
  </Screen>;
}

function Summary({ label, value }: { label: string; value: number }) {
  return <View style={styles.summaryItem}><Text style={styles.muted}>{label}</Text><Text style={styles.summaryValue}>{value}</Text></View>;
}

const styles = StyleSheet.create({
  stepper: { flexDirection: "row", gap: 4, paddingVertical: 4 },
  step: { minHeight: 44, flex: 1, alignItems: "center", justifyContent: "center", gap: 3, borderBottomWidth: 2, borderBottomColor: nativeColors.rule, opacity: 0.7 },
  stepActive: { borderBottomColor: nativeColors.accent, opacity: 1 },
  stepComplete: { borderBottomColor: nativeColors.ruleStrong, opacity: 1 },
  stepNumber: { color: nativeColors.accent, fontFamily: nativeTypography.mono, fontSize: 11 },
  stepLabel: { color: nativeColors.inkMuted, fontSize: 11, fontWeight: "600" },
  sectionTitle: { color: nativeColors.ink, fontSize: 17, fontWeight: "700" },
  stageHeader: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: 12 },
  muted: { color: nativeColors.inkMuted, fontSize: 13, lineHeight: 19 },
  mono: { color: nativeColors.accent, fontFamily: nativeTypography.mono, fontSize: 11 },
  fileCard: { gap: 4, padding: 12, borderWidth: 1, borderColor: nativeColors.rule, backgroundColor: nativeColors.paperMuted },
  fileName: { color: nativeColors.ink, fontSize: 15, fontWeight: "700" },
  selectedFilesBox: { gap: 8, paddingTop: 8 },
  fileRow: { flexDirection: "row", alignItems: "center", gap: 8, paddingVertical: 8, borderTopWidth: 1, borderTopColor: nativeColors.rule },
  fileRowMain: { flex: 1, gap: 2 },
  fileStatus: { color: nativeColors.inkFaint, fontSize: 11 },
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
  previewItem: { gap: 0 },
  previewRow: { minHeight: 72, flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: 12, padding: 12, borderWidth: 1, borderColor: nativeColors.rule, backgroundColor: nativeColors.paperRaised },
  rowMain: { flex: 1, gap: 3 },
  amount: { color: nativeColors.ink, fontFamily: nativeTypography.mono, fontSize: 13, fontWeight: "700" },
  allocationDetail: { gap: 8, padding: 12, borderWidth: 1, borderTopWidth: 0, borderColor: nativeColors.rule, borderLeftWidth: 3, borderLeftColor: nativeColors.ruleStrong, backgroundColor: nativeColors.paperMuted },
  allocationDetailComplete: { borderLeftColor: nativeColors.income, backgroundColor: nativeColors.successSurface },
  allocationFields: { gap: 0 },
  allocationField: { flexDirection: "row", alignItems: "center", gap: 12, paddingVertical: 10, borderTopWidth: 1, borderTopColor: nativeColors.rule },
  allocationLabel: { flex: 1, gap: 2 },
  allocationHint: { color: nativeColors.inkFaint, fontSize: 11 },
  allocationInput: { width: 132, minHeight: 44, paddingHorizontal: 10, borderWidth: 1, borderColor: nativeColors.rule, borderRadius: 3, color: nativeColors.ink, backgroundColor: nativeColors.paperRaised, fontFamily: nativeTypography.mono, fontSize: 16, textAlign: "right" },
  allocationSummary: { alignItems: "flex-end", paddingTop: 8, borderTopWidth: 1, borderTopColor: nativeColors.rule },
  allocationComplete: { color: nativeColors.income, fontFamily: nativeTypography.mono, fontSize: 12 },
  allocationIncomplete: { color: nativeColors.danger, fontFamily: nativeTypography.mono, fontSize: 12 },
  relationList: { gap: 10 },
  relationCard: { gap: 8, padding: 12, borderWidth: 1, borderColor: nativeColors.rule, backgroundColor: nativeColors.paperRaised },
  relationRejected: { borderColor: nativeColors.danger, opacity: 0.72 },
  stageActions: { flexDirection: "row", justifyContent: "space-between", gap: 8 },
  successMark: { color: nativeColors.income, fontFamily: nativeTypography.mono, fontSize: 42, fontWeight: "700" },
  successTitle: { color: nativeColors.ink, fontSize: 21, fontWeight: "700" },
  successDetail: { color: nativeColors.inkMuted, fontSize: 14, lineHeight: 21 },
});
