import { useEffect, useMemo, useState } from "react";
import { ActivityIndicator, StyleSheet, Text, TextInput, View } from "react-native";
import { router, useLocalSearchParams } from "expo-router";
import type { Account, CashCategory, CashRecordDetail, Evidence, LedgerOptions } from "@finance-tracker/contracts";
import { buildCashRecordPayload, canWrite, type CashRecordComponentDraft } from "@finance-tracker/core";
import { Button, Header, Label, Screen, StatusMessage, Surface } from "@/components/NativeShell";
import { errorMessage, useSession } from "@/state/session";
import { withWriteTimeout } from "@/platform/timeout";
import { nativeColors, nativeTypography } from "@finance-tracker/design-tokens";
import { copy, semanticIds } from "@finance-tracker/presentation";

const fallbackTypes = [
  { value: "consumption", label: copy.record.typeLabels.consumption, subtypes: [{ value: "not_applicable", label: copy.record.subtypeLabels.ordinaryConsumption }] },
  { value: "income", label: copy.record.typeLabels.income, subtypes: [{ value: "not_applicable", label: copy.record.subtypeLabels.ordinaryIncome }] },
  { value: "transfer_out", label: copy.record.typeLabels.transferOut, subtypes: [{ value: "ordinary_transfer", label: copy.record.subtypeLabels.ordinaryTransfer }] },
];

const recordTypeLabels: Record<string, string> = {
  consumption: copy.record.typeLabels.consumption,
  expense: copy.record.typeLabels.expense,
  refund: copy.record.typeLabels.refund,
  reversal: copy.record.typeLabels.reversal,
  transfer_reversal: copy.record.typeLabels.transferReversal,
  withdrawal_in: copy.record.typeLabels.withdrawalIn,
  withdrawal_out: copy.record.typeLabels.withdrawalOut,
  transfer_in: copy.record.typeLabels.transferIn,
  transfer_out: copy.record.typeLabels.transferOut,
  repayment: copy.record.typeLabels.repayment,
  income: copy.record.typeLabels.income,
  investment_in: copy.record.typeLabels.investmentIn,
  investment_out: copy.record.typeLabels.investmentOut,
  interest: copy.record.typeLabels.interest,
  fee: copy.record.typeLabels.fee,
  fx_in: copy.record.typeLabels.fxIn,
  fx_out: copy.record.typeLabels.fxOut,
  other: copy.record.typeLabels.other,
};

function formatOccurredAt(value: string): string {
  if (!value || Number.isNaN(new Date(value).getTime())) return copy.record.notProvided;
  return new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}

function economicTypeLabel(value: Evidence["projection"]): string {
  if (value.transfer_subtype === "bank_security_transfer") return copy.record.typeLabels.bankSecurityTransfer;
  return value.economic_type === "expense" ? copy.record.typeLabels.consumption : value.economic_type === "income" ? copy.record.typeLabels.income : copy.record.typeLabels.merged;
}

function recordTypeLabel(evidence: Evidence): string {
  const record = evidence.root_record;
  const fallback = record.record_type ?? (evidence.projection.economic_type === "expense" ? "consumption" : evidence.projection.economic_type === "income" ? "income" : record.amount.startsWith("-") ? "transfer_out" : "transfer_in");
  return recordTypeLabels[fallback] ?? copy.record.typeLabels.other;
}

function recordSubtypeLabel(value: string | undefined): string | null {
  if (!value || value === "not_applicable") return null;
  const labels: Record<string, string> = {
    ordinary_transfer: copy.record.subtypeLabels.ordinaryTransfer,
    cross_border_remittance: copy.record.subtypeLabels.crossBorderRemittance,
    internal_account_transfer: copy.record.subtypeLabels.internalAccountTransfer,
    currency_exchange: copy.record.subtypeLabels.currencyExchange,
    withdraw_to_bank: copy.record.subtypeLabels.withdrawToBank,
    credit_repayment: copy.record.subtypeLabels.creditRepayment,
  };
  return labels[value] ?? value;
}

export default function RecordScreen() {
  const { client, activeRole } = useSession();
  const params = useLocalSearchParams<{ projectionId?: string; mode?: string }>();
  const projectionId = typeof params.projectionId === "string" ? params.projectionId : undefined;
  const editing = params.mode === "edit" && Boolean(projectionId);
  const creating = params.mode === "create" || (!projectionId && !editing);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [categories, setCategories] = useState<CashCategory[]>([]);
  const [options, setOptions] = useState<LedgerOptions | null>(null);
  const [evidence, setEvidence] = useState<Evidence | null>(null);
  const [loading, setLoading] = useState(!creating);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [writeError, setWriteError] = useState<string | null>(null);
  const [amount, setAmount] = useState("0.00");
  const [currency, setCurrency] = useState("CNY");
  const [components, setComponents] = useState<CashRecordComponentDraft[]>([{ accountName: "", amount: "0.00" }]);
  const [recordType, setRecordType] = useState("consumption");
  const [recordSubtype, setRecordSubtype] = useState("not_applicable");
  const [occurredAt, setOccurredAt] = useState(() => new Date().toISOString());
  const [counterparty, setCounterparty] = useState("");
  const [counterpartyAccount, setCounterpartyAccount] = useState("");
  const [categoryId, setCategoryId] = useState<string | null>(null);
  const [note, setNote] = useState("");
  const writable = canWrite(activeRole);

  useEffect(() => {
    let active = true;
    if (creating) {
      Promise.all([client.fetchCashAccounts(), client.fetchLedgerOptions(), client.fetchCashCategories()]).then(([nextAccounts, nextOptions, nextCategories]) => {
        if (!active) return;
        setAccounts(nextAccounts);
        setOptions(nextOptions);
        setCategories(nextCategories.items);
        const firstAccount = nextAccounts[0];
        setCurrency(firstAccount?.currencies?.[0] ?? "CNY");
        setComponents([{ accountName: firstAccount?.name ?? "", amount: "0.00" }]);
      }).catch((cause: unknown) => { if (active) setLoadError(errorMessage(cause instanceof Error ? cause.message : "request_failed")); });
    } else if (projectionId) {
      Promise.all([client.fetchEvidence(projectionId), client.fetchCashAccounts(), client.fetchLedgerOptions(), client.fetchCashCategories()]).then(([nextEvidence, nextAccounts, nextOptions, nextCategories]) => {
        if (!active) return;
        const record = nextEvidence.root_record;
        setEvidence(nextEvidence);
        setAccounts(nextAccounts);
        setOptions(nextOptions);
        setCategories(nextCategories.items);
        setAmount(record.amount);
        setCurrency(record.currency);
        setComponents(record.components?.length
          ? record.components.map((component) => ({ accountName: component.account_name, amount: component.amount }))
          : [{ accountName: record.account?.name ?? record.account_name ?? "", amount: record.amount }]);
        setRecordType(record.record_type ?? (nextEvidence.projection.economic_type === "expense" ? "consumption" : nextEvidence.projection.economic_type === "income" ? "income" : "transfer_out"));
        setRecordSubtype(record.record_subtype ?? "not_applicable");
        setOccurredAt(record.occurred_at.slice(0, 16));
        setCounterparty(record.counterparty);
        setCounterpartyAccount(record.counterparty_account ?? "");
        setCategoryId(record.category?.id ?? record.category_id ?? null);
        setNote(record.note);
      }).catch((cause: unknown) => { if (active) setLoadError(errorMessage(cause instanceof Error ? cause.message : "request_failed")); }).finally(() => { if (active) setLoading(false); });
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
    if (!writable) return;
    setWriteError(null); setSubmitting(true);
    try {
      const payload = buildCashRecordPayload({ accountName: components.length === 1 ? components[0].accountName : "", amount, currency, occurredAt, recordType, recordSubtype, counterparty, counterpartyAccount, note, categoryId, components });
      if (editing && evidence) await withWriteTimeout(client.updateCashRecord(evidence.root_record.id, payload));
      else await withWriteTimeout(client.createCashRecord(payload));
      router.replace("/(app)/ledger" as never);
    } catch (cause) {
      setWriteError(errorMessage(cause instanceof Error ? cause.message : "request_failed"));
    } finally { setSubmitting(false); }
  }

  function updateComponent(index: number, value: Partial<CashRecordComponentDraft>) {
    setComponents((current) => current.map((component, currentIndex) => currentIndex === index ? { ...component, ...value } : component));
  }

  function addComponent() {
    setComponents((current) => [...current, { accountName: "", amount: "" }]);
  }

  function removeComponent(index: number) {
    setComponents((current) => current.length > 1 ? current.filter((_, currentIndex) => currentIndex !== index) : current);
  }

  const allocationReady = components.length > 0 && components.every((component) => component.accountName.trim() && component.amount.trim()) && (() => {
    try {
      buildCashRecordPayload({ accountName: components.length === 1 ? components[0].accountName : "", amount, currency, occurredAt, recordType, recordSubtype, counterparty, counterpartyAccount, note, categoryId, components });
      return true;
    } catch {
      return false;
    }
  })();

  if (loading) return <Screen testID={semanticIds.recordEvidence}><StatusMessage title={copy.record.loading} action={<ActivityIndicator color={nativeColors.accent} />} /></Screen>;
  if (loadError) return <Screen testID={semanticIds.recordEvidence}><StatusMessage title={loadError} tone="error" action={<Button onPress={() => router.back()} variant="primary">{copy.common.back}</Button>} /></Screen>;
  if (!creating && evidence && !editing) return <Screen testID={semanticIds.recordEvidence}><Header title={copy.record.evidenceTitle} detail={`${evidence.projection.counterparty || copy.ledger.noCounterparty} · ${copy.record.recorded}`} action={<View style={styles.headerActions}><Button testID={semanticIds.recordCancel} onPress={() => router.back()}>{copy.common.back}</Button>{writable && <Button onPress={() => router.replace({ pathname: "/(app)/record", params: { projectionId: evidence.projection.projection_id, mode: "edit" } } as never)}>{copy.record.editTitle}</Button>}</View>} /><Surface><DetailRow label={copy.record.amount} value={`${evidence.projection.amount} ${evidence.projection.currency}`} /><DetailRow label={copy.record.economicType} value={economicTypeLabel(evidence.projection)} /><DetailRow label={copy.record.counterparty} value={evidence.root_record.counterparty || "-"} /><DetailRow label={copy.record.counterpartyAccount} value={evidence.root_record.counterparty_account || "-"} /><DetailRow label={copy.record.occurredAt} value={formatOccurredAt(evidence.root_record.occurred_at)} /><DetailRow label={copy.record.account} value={evidence.root_record.account?.name ?? "多个账户"} />{evidence.root_record.components?.length ? <View style={styles.componentDetail}><Text style={styles.detailLabel}>账户分配</Text>{evidence.root_record.components.map((component) => <Text key={component.id} style={styles.componentDetailValue}>{component.account_name} · {component.amount} {component.currency}</Text>)}</View> : null}<DetailRow label={copy.record.type} value={recordTypeLabel(evidence)} /><DetailRow label={copy.record.subtype} value={recordSubtypeLabel(evidence.root_record.record_subtype) || "-"} /><DetailRow label={copy.record.category} value={evidence.root_record.category?.path.map(({ name }) => name).join(" / ") || copy.ledger.noCategory} /><DetailRow label={copy.record.note} value={evidence.root_record.note || copy.ledger.noNote} /><DetailRow label={copy.record.source} value={evidence.root_record.source_type ?? copy.ledger.manualSource} /></Surface><Surface><Text style={styles.sectionTitle}>{copy.record.relation}</Text>{evidence.accepted_relations.length === 0 ? <Text style={styles.muted}>{copy.record.noRelations}</Text> : evidence.accepted_relations.map((relation) => <Text key={relation.id} style={styles.relation}>{relation.kind} · {relation.confidence}</Text>)}</Surface></Screen>;

  return <Screen testID={semanticIds.recordScreen}><Header title={editing ? copy.record.editTitle : copy.record.newTitle} detail={copy.ledger.title} action={<Button testID={semanticIds.recordCancel} onPress={() => router.back()}>{editing ? copy.common.back : copy.common.cancel}</Button>} />
    {!writable && <StatusMessage title={copy.ledger.readOnly} detail={copy.ledger.readOnlyRecordDetail} tone="error" />}
    {writeError && <StatusMessage title={writeError} detail={copy.record.writeErrorDetail} tone="error" />}
    <Surface>
      <View style={styles.form}>
        <View style={styles.field}><Label>{copy.record.amount}</Label><TextInput testID={semanticIds.recordAmount} editable={writable && !submitting} keyboardType="decimal-pad" onChangeText={(value) => { setAmount(value); if (components.length === 1) updateComponent(0, { amount: value }); }} style={styles.amountInput} value={amount} /></View>
        <View style={styles.field}><Label>{copy.record.currency}</Label><TextInput testID={semanticIds.recordCurrency} editable={writable && !submitting} autoCapitalize="characters" maxLength={3} onChangeText={setCurrency} style={styles.input} value={currency} /></View>
        <View testID={semanticIds.recordAllocation} style={styles.field}><Label>{copy.record.allocation}</Label>{components.map((component, index) => <View style={styles.componentEditor} key={`${index}-${component.accountName}`}><View style={styles.choiceList}>{accounts.map((account) => <Button testID={index === 0 && component.accountName === account.name ? semanticIds.recordAccount : undefined} key={account.id} disabled={!writable || submitting} onPress={() => updateComponent(index, { accountName: account.name })} variant={component.accountName === account.name ? "primary" : "secondary"}>{account.name}</Button>)}</View><View style={styles.componentAmountRow}><TextInput editable={writable && !submitting} keyboardType="decimal-pad" accessibilityLabel={`第${index + 1}个${copy.record.allocation}`} onChangeText={(value) => { updateComponent(index, { amount: value }); if (components.length === 1) setAmount(value); }} style={styles.input} value={component.amount} /><Button accessibilityLabel={copy.record.allocationRemove} disabled={!writable || submitting || components.length <= 1} onPress={() => removeComponent(index)} variant="danger">×</Button></View></View>)}<Button disabled={!writable || submitting} onPress={addComponent}>{copy.record.allocationAdd}</Button><Text style={[styles.muted, allocationReady ? styles.valid : styles.invalid]}>{allocationReady ? copy.record.allocationMatch : copy.record.allocationIncomplete}</Text></View>
        <View style={styles.field}><Label>{copy.record.category}</Label><View style={styles.choiceList}><Button testID={categoryId === null ? semanticIds.recordCategory : undefined} disabled={!writable || submitting} onPress={() => setCategoryId(null)} variant={categoryId === null ? "primary" : "secondary"}>{copy.ledger.noCategory}</Button>{categories.map((category) => <Button testID={categoryId === category.id ? semanticIds.recordCategory : undefined} key={category.id} disabled={!writable || submitting} onPress={() => setCategoryId(category.id)} variant={categoryId === category.id ? "primary" : "secondary"}>{category.path.map(({ name }) => name).join(" / ")}</Button>)}</View></View>
        <View style={styles.field}><Label>{copy.record.type}</Label><View style={styles.choiceList}>{typeOptions.map((type) => <Button key={type.value} disabled={!writable || submitting} onPress={() => chooseType(type.value)} variant={recordType === type.value ? "primary" : "secondary"}>{type.label}</Button>)}</View></View>
        <View style={styles.field}><Label>{copy.record.subtype}</Label><View style={styles.choiceList}>{(selectedType?.subtypes ?? []).map((subtype) => <Button key={subtype.value} disabled={!writable || submitting} onPress={() => setRecordSubtype(subtype.value)} variant={recordSubtype === subtype.value ? "primary" : "secondary"}>{subtype.label}</Button>)}</View></View>
        <View style={styles.field}><Label>{copy.record.occurredAt}</Label><TextInput editable={writable && !submitting} onChangeText={setOccurredAt} style={styles.input} value={occurredAt} /></View>
        <View style={styles.field}><Label>{copy.record.counterparty}</Label><TextInput editable={writable && !submitting} onChangeText={setCounterparty} placeholder={copy.common.optional} placeholderTextColor={nativeColors.inkFaint} style={styles.input} value={counterparty} /></View>
        <View style={styles.field}><Label>{copy.record.counterpartyAccount}</Label><TextInput editable={writable && !submitting} onChangeText={setCounterpartyAccount} placeholder={copy.common.optional} placeholderTextColor={nativeColors.inkFaint} style={styles.input} value={counterpartyAccount} /></View>
        <View style={styles.field}><Label>{copy.record.note}</Label><TextInput editable={writable && !submitting} multiline onChangeText={setNote} placeholder={copy.common.optional} placeholderTextColor={nativeColors.inkFaint} style={[styles.input, styles.multiline]} value={note} /></View>
      </View>
      <Button testID={semanticIds.recordSave} disabled={!writable || submitting || !allocationReady} onPress={() => void save()} variant="primary">{submitting ? copy.record.saving : copy.record.save}</Button>
    </Surface>
  </Screen>;
}

function DetailRow({ label, value }: { label: string; value: string }) { return <View style={styles.detailRow}><Text style={styles.detailLabel}>{label}</Text><Text style={styles.detailValue}>{value}</Text></View>; }

const styles = StyleSheet.create({
  headerActions: { flexDirection: "row", gap: 8 },
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
  componentEditor: { gap: 8, paddingBottom: 8 },
  componentAmountRow: { flexDirection: "row", gap: 8, alignItems: "center" },
  componentDetail: { paddingVertical: 10, borderBottomWidth: 1, borderBottomColor: nativeColors.rule, gap: 5 },
  componentDetailValue: { color: nativeColors.ink, fontFamily: nativeTypography.mono, fontSize: 13 },
  valid: { color: nativeColors.income },
  invalid: { color: nativeColors.danger },
});
