import { useMemo, useReducer, useState } from "react";
import { router } from "expo-router";
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View } from "react-native";
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
      {file && <View style={styles.fileCard}><Text style={styles.fileName}>{file.name}</Text></View>}
      <Button testID={semanticIds.importChooseFile} disabled={!writable || importState.status === "loading"} onPress={() => void chooseFile()} variant="primary">{importState.status === "loading" ? copy.import.scanning : file ? copy.import.rescan : copy.import.selectFile}</Button>
      {passwordRequired && <View style={styles.passwordBox}><Label>{copy.import.password}</Label><TextInput testID={semanticIds.importPassword} autoCapitalize="none" editable={importState.status !== "loading"} onChangeText={setPassword} placeholder="输入密码后重新扫描" placeholderTextColor={nativeColors.inkFaint} secureTextEntry style={styles.input} value={password} /><Button disabled={!password || importState.status === "loading"} onPress={() => void scanWithPassword()} variant="secondary">{copy.import.usePassword}</Button></View>}
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
          <View style={styles.choiceList}>{scan.accounts.map((account) => <Button key={account.id} disabled={!writable || importState.status === "loading"} onPress={() => setDrafts((current) => ({ ...current, [group.group_id]: { accountId: account.id, newAccount: null } }))} variant={draft.accountId === account.id ? "primary" : "secondary"}>{account.name}</Button>)}</View>
          <Button disabled={!writable || importState.status === "loading"} onPress={() => setDrafts((current) => ({ ...current, [group.group_id]: { accountId: null, newAccount: draft.newAccount ?? { draftId: `draft-${group.group_id}`, name: group.display_name, type: "cash", currencies: [...group.currencies] } } }))} variant={draft.newAccount ? "primary" : "secondary"}>{draft.newAccount ? `${copy.import.newAccountPrefix}${draft.newAccount.name}` : copy.import.createNamedAccount}</Button>
          {draft.newAccount && <View style={styles.newAccountBox}><Label>{copy.import.newAccountName}</Label><TextInput editable={writable} onChangeText={(name) => setDrafts((current) => ({ ...current, [group.group_id]: { ...draft, newAccount: draft.newAccount ? { ...draft.newAccount, name } : null } }))} style={styles.input} value={draft.newAccount.name} /><Label>{copy.import.accountType}</Label><View style={styles.choiceList}>{[["cash", copy.import.cashAccount], ["loan", copy.import.loanAccount], ["lend", copy.import.lendAccount]].map(([value, label]) => <Button key={value} disabled={!writable} onPress={() => setDrafts((current) => ({ ...current, [group.group_id]: { ...draft, newAccount: draft.newAccount ? { ...draft.newAccount, type: value } : null } }))} variant={draft.newAccount?.type === value ? "primary" : "secondary"}>{label}</Button>)}</View></View>}
          {missingCurrencies.length > 0 && <Text style={styles.warning}>{copy.import.currencySupplementPrefix}「{selected?.name}」{copy.import.currencySupplementSuffix}：{missingCurrencies.join("、")}</Text>}
          {!draft.accountId && !draft.newAccount && <Text style={styles.warning}>{copy.import.selectMapping}</Text>}
        </View>;
      })}
      <View style={styles.stageActions}><Button testID={semanticIds.importPrevious} disabled={importState.status === "loading"} onPress={() => setStage("select")}>{copy.import.previous}</Button><Button testID={semanticIds.importNext} disabled={!mappingComplete || importState.status === "loading" || !writable} onPress={() => void loadPreview()} variant="primary">{importState.status === "loading" ? copy.import.mapping : copy.import.mapConfirm}</Button></View>
    </Surface>}

    {stage === "preview" && preview && <Surface testID={semanticIds.importPreview}>
      <View style={styles.stageHeader}><Text style={styles.sectionTitle}>{copy.import.preview}</Text><Text style={styles.mono}>{preview.channel_label}</Text></View>
      <View style={styles.summary}><Summary label={copy.import.all} value={preview.summary.total} /><Summary label={copy.import.new} value={preview.summary.new} /><Summary label={copy.import.existing} value={preview.summary.existing} /><Summary label={copy.import.statusUnresolved} value={preview.summary.unresolved ?? 0} /></View>
      {preview.items.length === 0 ? <StatusMessage title={copy.import.noPreviewRecords} /> : <View style={styles.previewList}>{preview.items.slice(0, 40).map((item) => <View key={item.record_id} style={styles.previewRow}><View style={styles.rowMain}><Text style={styles.groupName}>{item.counterparty || copy.ledger.noCounterparty}</Text><Text style={styles.muted}>{item.account_name} · {item.occurred_at}</Text><Text style={styles.muted}>{recordTypeLabels[item.record_type] ?? copy.record.typeLabels.other} · {item.status === "new" ? copy.import.statusNew : item.status === "existing" ? copy.import.statusExisting : item.status === "unresolved" ? copy.import.statusUnresolved : copy.import.statusUnsupported}</Text></View><Text style={styles.amount}>{item.amount} {item.currency}</Text></View>)}</View>}
      {preview.items.length > 40 && <Text style={styles.muted}>{copy.import.previewFirst40}</Text>}
      {preview.summary.unresolved ? <Text style={styles.warning}>{preview.summary.unresolved} 条无法识别，确认后会跳过。</Text> : null}
      {ordinaryUnsupportedCount(preview) > 0 ? <Text style={styles.warning}>{copy.import.unsupportedCannotConfirm}</Text> : null}
      <View style={styles.stageActions}><Button testID={semanticIds.importPrevious} disabled={importState.status === "loading"} onPress={() => setStage("mapping")}>{copy.import.previous}</Button><Button testID={semanticIds.importNext} disabled={importState.status === "loading"} onPress={() => openRelations()} variant="primary">{copy.import.next}</Button></View>
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
      <View style={styles.stageActions}><Button testID={semanticIds.importPrevious} disabled={importState.status === "committing"} onPress={() => setStage("preview")}>{copy.import.previous}</Button><Button testID={semanticIds.importConfirm} disabled={!writable || importState.status === "committing" || ordinaryUnsupportedCount(preview) > 0} onPress={() => void confirmImport()} variant="primary">{copy.import.confirm}</Button></View>
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
  stageActions: { flexDirection: "row", justifyContent: "space-between", gap: 8 },
  successMark: { color: nativeColors.income, fontFamily: nativeTypography.mono, fontSize: 42, fontWeight: "700" },
  successTitle: { color: nativeColors.ink, fontSize: 21, fontWeight: "700" },
  successDetail: { color: nativeColors.inkMuted, fontSize: 14, lineHeight: 21 },
});
