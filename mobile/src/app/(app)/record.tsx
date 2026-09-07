import { useEffect, useMemo, useState } from "react";
import { ActivityIndicator, StyleSheet, Text, TextInput, View } from "react-native";
import { router, useLocalSearchParams } from "expo-router";
import type { Account, CashRecordDetail, Evidence, LedgerOptions } from "@finance-tracker/contracts";
import { buildCashRecordPayload, canWrite } from "@finance-tracker/core";
import { Button, Header, Label, Screen, StatusMessage, Surface } from "@/components/NativeShell";
import { errorMessage, useSession } from "@/state/session";
import { withWriteTimeout } from "@/platform/timeout";
import { nativeColors, nativeTypography } from "@finance-tracker/design-tokens";

const fallbackTypes = [
  { value: "consumption", label: "消费", subtypes: [{ value: "not_applicable", label: "普通消费" }] },
  { value: "income", label: "收入", subtypes: [{ value: "not_applicable", label: "普通收入" }] },
  { value: "transfer_out", label: "转账转出", subtypes: [{ value: "ordinary_transfer", label: "普通转账" }] },
];

export default function RecordScreen() {
  const { client, activeRole } = useSession();
  const params = useLocalSearchParams<{ projectionId?: string; mode?: string }>();
  const projectionId = typeof params.projectionId === "string" ? params.projectionId : undefined;
  const creating = params.mode === "create" || !projectionId;
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [options, setOptions] = useState<LedgerOptions | null>(null);
  const [evidence, setEvidence] = useState<Evidence | null>(null);
  const [loading, setLoading] = useState(!creating);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [writeError, setWriteError] = useState<string | null>(null);
  const [amount, setAmount] = useState("0.00");
  const [currency, setCurrency] = useState("CNY");
  const [accountId, setAccountId] = useState<number | null>(null);
  const [recordType, setRecordType] = useState("consumption");
  const [recordSubtype, setRecordSubtype] = useState("not_applicable");
  const [occurredAt, setOccurredAt] = useState(() => new Date().toISOString());
  const [counterparty, setCounterparty] = useState("");
  const [note, setNote] = useState("");
  const writable = canWrite(activeRole);

  useEffect(() => {
    let active = true;
    if (creating) {
      Promise.all([client.fetchCashAccounts(), client.fetchLedgerOptions()]).then(([nextAccounts, nextOptions]) => {
        if (!active) return;
        setAccounts(nextAccounts);
        setOptions(nextOptions);
        setAccountId(nextAccounts[0]?.id ?? null);
        setCurrency(nextAccounts[0]?.currencies?.[0] ?? "CNY");
      }).catch((cause: unknown) => { if (active) setLoadError(errorMessage(cause instanceof Error ? cause.message : "request_failed")); });
    } else if (projectionId) {
      client.fetchEvidence(projectionId).then((nextEvidence) => { if (active) setEvidence(nextEvidence); }).catch((cause: unknown) => { if (active) setLoadError(errorMessage(cause instanceof Error ? cause.message : "request_failed")); }).finally(() => { if (active) setLoading(false); });
    }
    return () => { active = false; };
  }, [client, creating, projectionId]);

  const typeOptions = options?.record_types?.length ? options.record_types : fallbackTypes;
  const selectedType = useMemo(() => typeOptions.find(({ value }) => value === recordType) ?? typeOptions[0], [recordType, typeOptions]);

  function chooseType(value: string) {
    const next = typeOptions.find((item) => item.value === value) ?? typeOptions[0];
    setRecordType(next.value);
    setRecordSubtype(next.subtypes[0]?.value ?? "not_applicable");
  }

  async function save() {
    const selectedAccount = accounts.find(({ id }) => id === accountId);
    if (!writable || !selectedAccount) return;
    setWriteError(null); setSubmitting(true);
    try {
      const payload = buildCashRecordPayload({ accountName: selectedAccount.name, amount, currency, occurredAt, recordType, recordSubtype, counterparty, counterpartyAccount: "", note });
      await withWriteTimeout(client.createCashRecord(payload));
      router.replace("/(app)/ledger" as never);
    } catch (cause) {
      setWriteError(errorMessage(cause instanceof Error ? cause.message : "request_failed"));
    } finally { setSubmitting(false); }
  }

  if (loading) return <Screen><StatusMessage title="正在读取凭证…" action={<ActivityIndicator color={nativeColors.accent} />} /></Screen>;
  if (loadError) return <Screen><StatusMessage title={loadError} tone="error" action={<Button onPress={() => router.back()} variant="primary">返回账本</Button>} /></Screen>;
  if (!creating && evidence) return <Screen><Header title="流水凭证" detail={`${evidence.projection.counterparty || "未填写对方"} · 已记录`} action={<Button onPress={() => router.back()}>返回</Button>} /><Surface><DetailRow label="金额" value={`${evidence.projection.amount} ${evidence.projection.currency}`} /><DetailRow label="账户" value={evidence.projection.account.name} /><DetailRow label="时间" value={evidence.projection.occurred_at} /><DetailRow label="类型" value={evidence.projection.economic_type} /><DetailRow label="来源" value={evidence.projection.source_type ?? "手工"} /><DetailRow label="备注" value={evidence.projection.note || "无备注"} /></Surface><Surface><Text style={styles.sectionTitle}>关系</Text>{evidence.accepted_relations.length === 0 ? <Text style={styles.muted}>暂无已确认关系。</Text> : evidence.accepted_relations.map((relation) => <Text key={relation.id} style={styles.relation}>{relation.kind} · {relation.confidence}</Text>)}</Surface></Screen>;

  return <Screen><Header title="记一笔" detail="金额与币种将按记录保存" action={<Button onPress={() => router.back()}>取消</Button>} />
    {!writable && <StatusMessage title="当前角色仅可查看" detail="请切换到可编辑工作区后再记账。" tone="error" />}
    {writeError && <StatusMessage title={writeError} detail="请回到账本重新读取。" tone="error" />}
    <Surface>
      <View style={styles.form}>
        <View style={styles.field}><Label>金额</Label><TextInput editable={writable && !submitting} keyboardType="decimal-pad" onChangeText={setAmount} style={styles.amountInput} value={amount} /></View>
        <View style={styles.field}><Label>币种</Label><TextInput editable={writable && !submitting} autoCapitalize="characters" maxLength={3} onChangeText={setCurrency} style={styles.input} value={currency} /></View>
        <View style={styles.field}><Label>账户</Label><View style={styles.choiceList}>{accounts.map((account) => <Button key={account.id} disabled={!writable || submitting} onPress={() => { setAccountId(account.id); setCurrency(account.currencies?.[0] ?? currency); }} variant={accountId === account.id ? "primary" : "secondary"}>{account.name}</Button>)}</View></View>
        <View style={styles.field}><Label>流水类型</Label><View style={styles.choiceList}>{typeOptions.map((type) => <Button key={type.value} disabled={!writable || submitting} onPress={() => chooseType(type.value)} variant={recordType === type.value ? "primary" : "secondary"}>{type.label}</Button>)}</View></View>
        <View style={styles.field}><Label>细分</Label><View style={styles.choiceList}>{(selectedType?.subtypes ?? []).map((subtype) => <Button key={subtype.value} disabled={!writable || submitting} onPress={() => setRecordSubtype(subtype.value)} variant={recordSubtype === subtype.value ? "primary" : "secondary"}>{subtype.label}</Button>)}</View></View>
        <View style={styles.field}><Label>发生时间</Label><TextInput editable={writable && !submitting} onChangeText={setOccurredAt} style={styles.input} value={occurredAt} /></View>
        <View style={styles.field}><Label>交易对方</Label><TextInput editable={writable && !submitting} onChangeText={setCounterparty} placeholder="可选" placeholderTextColor={nativeColors.inkFaint} style={styles.input} value={counterparty} /></View>
        <View style={styles.field}><Label>备注</Label><TextInput editable={writable && !submitting} multiline onChangeText={setNote} placeholder="可选" placeholderTextColor={nativeColors.inkFaint} style={[styles.input, styles.multiline]} value={note} /></View>
      </View>
      <Button disabled={!writable || submitting || accountId === null} onPress={() => void save()} variant="primary">{submitting ? "提交中…" : "保存"}</Button>
    </Surface>
  </Screen>;
}

function DetailRow({ label, value }: { label: string; value: string }) { return <View style={styles.detailRow}><Text style={styles.detailLabel}>{label}</Text><Text style={styles.detailValue}>{value}</Text></View>; }

const styles = StyleSheet.create({
  form: { gap: 16 },
  field: { gap: 7 },
  input: { minHeight: 48, paddingHorizontal: 12, borderWidth: 1, borderColor: nativeColors.rule, borderRadius: 3, color: nativeColors.ink, backgroundColor: nativeColors.paperRaised, fontSize: 16 },
  amountInput: { minHeight: 58, paddingHorizontal: 12, borderWidth: 1, borderColor: nativeColors.accent, borderRadius: 3, color: nativeColors.ink, backgroundColor: nativeColors.paperRaised, fontFamily: nativeTypography.mono, fontSize: 25 },
  multiline: { minHeight: 80, paddingTop: 12, textAlignVertical: "top" },
  choiceList: { flexDirection: "row", flexWrap: "wrap", gap: 8 },
  detailRow: { flexDirection: "row", gap: 12, paddingVertical: 10, borderBottomWidth: 1, borderBottomColor: nativeColors.rule },
  detailLabel: { width: 70, color: nativeColors.inkMuted, fontSize: 13 },
  detailValue: { flex: 1, color: nativeColors.ink, fontFamily: nativeTypography.mono, fontSize: 13 },
  sectionTitle: { color: nativeColors.ink, fontSize: 16, fontWeight: "700" },
  muted: { color: nativeColors.inkMuted, fontSize: 13 },
  relation: { color: nativeColors.ink, fontSize: 13 },
});
