import { useCallback, useEffect, useState } from "react";
import { ActivityIndicator, FlatList, Pressable, StyleSheet, Text, TextInput, View } from "react-native";
import { router } from "expo-router";
import type { Account, CashFilters, CashPage, CashProjection } from "@finance-tracker/contracts";
import { canWrite, createLedgerLoadState, ledgerLoadFailed, ledgerLoadStarted, ledgerLoadSucceeded, selectActiveWorkspace, type LedgerLoadState } from "@finance-tracker/core";
import { Button, Header, Screen, StatusMessage, Surface } from "@/components/NativeShell";
import { errorMessage, useSession } from "@/state/session";
import { nativeColors, nativeTypography } from "@finance-tracker/design-tokens";
import { copy, semanticIds } from "@finance-tracker/presentation";

export default function LedgerScreen() {
  const { client, state, activeRole } = useSession();
  const [loadState, setLoadState] = useState<LedgerLoadState>(createLedgerLoadState);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [filters, setFilters] = useState<CashFilters>({});
  const [filtersOpen, setFiltersOpen] = useState(false);
  const session = state.session;
  const workspace = selectActiveWorkspace(session);
  const writable = canWrite(activeRole);

  const load = useCallback(async () => {
    if (!session?.active_workspace_id) return;
    setLoadState((current) => ledgerLoadStarted(current));
    try {
      const [page, nextAccounts] = await Promise.all([
        client.fetchCashPage(filters),
        client.fetchCashAccounts(),
      ]);
      setAccounts(nextAccounts);
      setLoadState(ledgerLoadSucceeded(page));
    } catch (cause) {
      const code = cause instanceof Error ? cause.message : "request_failed";
      setLoadState((current) => ledgerLoadFailed(current, code));
    }
  }, [client, filters, session?.active_workspace_id]);

  useEffect(() => { void load(); }, [load]);

  function updateFilter(key: keyof CashFilters, value: string) {
    setFilters((current) => ({ ...current, [key]: value || undefined }));
  }

  function openRecord(item: CashProjection) {
    router.push({ pathname: "/(app)/record", params: { projectionId: item.projection_id } } as never);
  }

  function renderItem({ item }: { item: CashProjection }) {
    const negative = item.amount.startsWith("-") || item.economic_type === "expense";
    const category = item.category?.path.map(({ name }) => name).join(" / ") || copy.ledger.noCategory;
    return <View testID={semanticIds.ledgerRecord}><Pressable testID={semanticIds.ledgerOpenRecord} accessibilityLabel={`${copy.ledger.view}：${item.counterparty || copy.ledger.noCounterparty}`} accessibilityRole="button" onPress={() => openRecord(item)} style={({ pressed }) => [styles.row, pressed && styles.rowPressed]}>
      <View style={styles.rowMain}><Text style={styles.counterparty}>{item.counterparty || copy.ledger.noCounterparty}</Text><Text style={styles.meta}>{item.account?.name ?? "多个账户"} · {category} · {item.occurred_at}</Text><Text style={styles.note}>{item.note || copy.ledger.noNote}</Text></View>
      <View style={styles.rowAmount}><Text style={[styles.amount, negative ? styles.expense : styles.income]}>{item.amount} {item.currency}</Text><Text style={styles.source}>{item.source_type ?? copy.ledger.manualSource}</Text><Text style={styles.viewAction}>{copy.ledger.view}</Text></View>
    </Pressable></View>;
  }

  if (!session) return <Screen testID={semanticIds.ledgerScreen}><StatusMessage title={copy.ledger.sessionExpired} tone="error" action={<Button onPress={() => router.replace("/(auth)/login" as never)} variant="primary">{copy.ledger.reLogin}</Button>} /></Screen>;

  const page: CashPage | null = loadState.page;
  const monthSummary = page?.monthly_summaries?.[0];
  const firstCurrency = monthSummary?.currencies[0];
  const accountId = filters.account_id;
  const filterSummary = [
    accountId ? accounts.find((account) => String(account.id) === accountId)?.name : copy.ledger.allAccounts,
    filters.counterparty ? `${copy.ledger.transactionInfo}：${filters.counterparty}` : "",
    filters.currency || "",
    filters.date_from || filters.date_to ? `${filters.date_from ?? "不限"} 至 ${filters.date_to ?? "不限"}` : "",
    copy.ledger.allIncome,
  ].filter(Boolean).join(" · ");

  return <Screen scroll={false} testID={semanticIds.ledgerScreen}>
    <Header testID={semanticIds.ledgerHeader} title={copy.ledger.title} detail={`${workspace?.name ?? copy.workspace.current} · ${copy.ledger.detail}`} action={<Button testID={semanticIds.workspaceSwitcher} onPress={() => router.replace("/(app)/workspace" as never)}>{copy.workspace.switch}</Button>} />
    {firstCurrency && <View testID={semanticIds.ledgerSummary} style={styles.summary}><View style={styles.summaryItem}><Text style={styles.summaryLabel}>{copy.ledger.summaryIncome}</Text><Text style={[styles.summaryValue, styles.income]}>{firstCurrency.income} {firstCurrency.currency}</Text></View><View style={styles.summaryItem}><Text style={styles.summaryLabel}>{copy.ledger.summaryExpense}</Text><Text style={[styles.summaryValue, styles.expense]}>{firstCurrency.expense} {firstCurrency.currency}</Text></View></View>}
    <View testID={semanticIds.ledgerFilters} style={styles.filterBlock}><Pressable accessibilityRole="button" onPress={() => setFiltersOpen((open) => !open)} style={styles.filterSummary}><Text style={styles.filterTitle}>{copy.ledger.filter}</Text><Text numberOfLines={1} style={styles.filterDescription}>{filterSummary}</Text><Text style={styles.filterToggle}>{filtersOpen ? "⌃" : "⌄"}</Text></Pressable>{filtersOpen && <Surface style={styles.filterSurface}><View style={styles.accountBlock}><Text style={styles.toolbarLabel}>{copy.ledger.account}</Text><FlatList data={[{ id: "", name: copy.ledger.allAccounts, type: "", active: true }, ...accounts]} horizontal showsHorizontalScrollIndicator={false} keyExtractor={(item) => String(item.id)} contentContainerStyle={styles.accountList} renderItem={({ item }) => <Pressable accessibilityRole="button" onPress={() => setFilters((current) => ({ ...current, account_id: item.id ? String(item.id) : undefined }))} style={[styles.accountChip, (item.id ? String(item.id) : undefined) === accountId && styles.accountChipActive]}><Text style={[styles.accountChipText, (item.id ? String(item.id) : undefined) === accountId && styles.accountChipTextActive]}>{item.name}</Text></Pressable>} /></View><View style={styles.filterGrid}><View style={styles.field}><Text style={styles.toolbarLabel}>{copy.ledger.dateFrom}</Text><TextInput value={filters.date_from ?? ""} onChangeText={(value) => updateFilter("date_from", value)} placeholder="YYYY-MM-DD" placeholderTextColor={nativeColors.inkFaint} style={styles.input} /></View><View style={styles.field}><Text style={styles.toolbarLabel}>{copy.ledger.dateTo}</Text><TextInput value={filters.date_to ?? ""} onChangeText={(value) => updateFilter("date_to", value)} placeholder="YYYY-MM-DD" placeholderTextColor={nativeColors.inkFaint} style={styles.input} /></View><View style={styles.field}><Text style={styles.toolbarLabel}>{copy.ledger.transactionInfo}</Text><TextInput value={filters.counterparty ?? ""} onChangeText={(value) => updateFilter("counterparty", value)} style={styles.input} /></View><View style={styles.field}><Text style={styles.toolbarLabel}>{copy.ledger.currency}</Text><TextInput autoCapitalize="characters" value={filters.currency ?? ""} onChangeText={(value) => updateFilter("currency", value)} style={styles.input} /></View></View></Surface>}</View>
    {loadState.status === "loading" && !page && <StatusMessage title={copy.ledger.loading} testID="ledger.loading" action={<ActivityIndicator color={nativeColors.accent} />} />}
    {loadState.status === "error" && <StatusMessage title={errorMessage(loadState.errorCode)} tone="error" testID={semanticIds.ledgerRetry} action={<Button onPress={() => void load()} variant="primary">{copy.ledger.reload}</Button>} />}
    {loadState.status === "ready" && page && page.items.length === 0 && <StatusMessage title={copy.ledger.empty} detail={copy.ledger.emptyDetail} testID={semanticIds.ledgerEmpty} />}
    {page && page.items.length > 0 && <View testID={semanticIds.ledgerList} style={styles.listWrap}><FlatList data={page.items} keyExtractor={(item) => item.projection_id} renderItem={renderItem} contentContainerStyle={styles.list} /></View>}
    <View style={styles.actions}><Button testID={semanticIds.ledgerAdd} disabled={!writable} onPress={() => router.push("/(app)/record?mode=create" as never)} variant="primary">{writable ? copy.ledger.create : copy.ledger.readOnly}</Button><Button testID={semanticIds.ledgerImport} disabled={!writable} onPress={() => router.push("/(app)/import" as never)}>{copy.ledger.import}</Button></View>
  </Screen>;
}

const styles = StyleSheet.create({
  summary: { flexDirection: "row", gap: 8 },
  summaryItem: { flex: 1, gap: 4, padding: 12, borderWidth: 1, borderColor: nativeColors.rule, backgroundColor: nativeColors.paperRaised },
  summaryLabel: { color: nativeColors.inkMuted, fontSize: 12 },
  summaryValue: { fontFamily: nativeTypography.mono, fontSize: 14, fontWeight: "700" },
  filterBlock: { gap: 8 },
  filterSummary: { minHeight: 52, flexDirection: "row", alignItems: "center", gap: 8, paddingHorizontal: 12, borderWidth: 1, borderColor: nativeColors.rule, backgroundColor: nativeColors.paperRaised },
  filterTitle: { color: nativeColors.ink, fontSize: 15, fontWeight: "700" },
  filterDescription: { flex: 1, color: nativeColors.inkMuted, fontSize: 12 },
  filterToggle: { color: nativeColors.accent, fontFamily: nativeTypography.mono, fontSize: 16 },
  filterSurface: { gap: 12, padding: 12 },
  accountBlock: { gap: 8 },
  toolbarLabel: { color: nativeColors.inkMuted, fontSize: 12, fontWeight: "700" },
  accountList: { gap: 8, paddingVertical: 2 },
  accountChip: { minHeight: 44, paddingHorizontal: 14, alignItems: "center", justifyContent: "center", borderWidth: 1, borderColor: nativeColors.rule, borderRadius: 3, backgroundColor: nativeColors.paperRaised },
  accountChipActive: { borderColor: nativeColors.accent, backgroundColor: nativeColors.accentSoft },
  accountChipText: { color: nativeColors.inkMuted, fontSize: 13, fontWeight: "600" },
  accountChipTextActive: { color: nativeColors.accent },
  filterGrid: { gap: 12 },
  field: { gap: 7 },
  input: { minHeight: 44, paddingHorizontal: 12, borderWidth: 1, borderColor: nativeColors.rule, borderRadius: 3, color: nativeColors.ink, backgroundColor: nativeColors.paperRaised, fontSize: 15 },
  listWrap: { flex: 1, minHeight: 160 },
  list: { gap: 8, paddingBottom: 8 },
  row: { minHeight: 88, flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: 12, padding: 14, borderWidth: 1, borderColor: nativeColors.rule, backgroundColor: nativeColors.paperRaised },
  rowPressed: { borderColor: nativeColors.accent, backgroundColor: nativeColors.accentSoft },
  rowMain: { flex: 1, minWidth: 0, gap: 4 },
  counterparty: { color: nativeColors.ink, fontSize: 16, fontWeight: "700" },
  meta: { color: nativeColors.inkMuted, fontSize: 12 },
  note: { color: nativeColors.inkFaint, fontSize: 12 },
  rowAmount: { alignItems: "flex-end", gap: 5 },
  amount: { fontFamily: nativeTypography.mono, fontSize: 14, fontWeight: "700" },
  income: { color: nativeColors.income },
  expense: { color: nativeColors.expense },
  source: { color: nativeColors.inkFaint, fontFamily: nativeTypography.mono, fontSize: 10 },
  viewAction: { color: nativeColors.accent, fontSize: 12 },
  actions: { flexDirection: "row", flexWrap: "wrap", gap: 8, paddingVertical: 8 },
});
