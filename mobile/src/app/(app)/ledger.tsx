import { useCallback, useEffect, useState } from "react";
import { ActivityIndicator, FlatList, Pressable, StyleSheet, Text, View } from "react-native";
import { router } from "expo-router";
import type { Account, CashProjection, CashPage } from "@finance-tracker/contracts";
import { canWrite, createLedgerLoadState, ledgerLoadFailed, ledgerLoadStarted, ledgerLoadSucceeded, selectActiveWorkspace, type LedgerLoadState } from "@finance-tracker/core";
import { Button, Header, Screen, StatusMessage, Surface } from "@/components/NativeShell";
import { errorMessage, useSession } from "@/state/session";
import { nativeColors, nativeTypography } from "@finance-tracker/design-tokens";

export default function LedgerScreen() {
  const { client, state, activeRole } = useSession();
  const [loadState, setLoadState] = useState<LedgerLoadState>(createLedgerLoadState);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [accountId, setAccountId] = useState<string | undefined>();
  const session = state.session;
  const workspace = selectActiveWorkspace(session);
  const writable = canWrite(activeRole);

  const load = useCallback(async () => {
    if (!session?.active_workspace_id) return;
    setLoadState((current) => ledgerLoadStarted(current));
    try {
      const [page, nextAccounts] = await Promise.all([
        client.fetchCashPage(accountId ? { account_id: accountId } : {}),
        client.fetchCashAccounts(),
      ]);
      setAccounts(nextAccounts);
      setLoadState(ledgerLoadSucceeded(page));
    } catch (cause) {
      const code = cause instanceof Error ? cause.message : "request_failed";
      setLoadState((current) => ledgerLoadFailed(current, code));
    }
  }, [accountId, client, session?.active_workspace_id]);

  useEffect(() => { void load(); }, [load]);

  function openRecord(item: CashProjection) {
    router.push({ pathname: "/(app)/record", params: { projectionId: item.projection_id } } as never);
  }

  function renderItem({ item }: { item: CashProjection }) {
    const negative = item.amount.startsWith("-") || item.economic_type === "expense";
    return <Pressable accessibilityRole="button" onPress={() => openRecord(item)} style={({ pressed }) => [styles.row, pressed && styles.rowPressed]}>
      <View style={styles.rowMain}><Text style={styles.counterparty}>{item.counterparty || "未填写对方"}</Text><Text style={styles.meta}>{item.account.name} · {item.category?.name ?? "未分类"}</Text><Text style={styles.note}>{item.note || "无备注"}</Text></View>
      <View style={styles.rowAmount}><Text style={[styles.amount, negative ? styles.expense : styles.income]}>{item.amount} {item.currency}</Text><Text style={styles.source}>{item.source_type ?? "手工"}</Text></View>
    </Pressable>;
  }

  if (!session) return <Screen><StatusMessage title="登录状态已失效" tone="error" action={<Button onPress={() => router.replace("/(auth)/login" as never)} variant="primary">重新登录</Button>} /></Screen>;

  const page: CashPage | null = loadState.page;
  return <Screen scroll={false}>
    <Header title="收支账本" detail={`${workspace?.name ?? "当前工作区"} · 最新账本状态`} action={<Button onPress={() => router.replace("/(app)/workspace" as never)}>切换</Button>} />
    <View style={styles.toolbar}><Text style={styles.toolbarLabel}>账户</Text><FlatList data={[{ id: "", name: "全部", type: "", active: true }, ...accounts]} horizontal showsHorizontalScrollIndicator={false} keyExtractor={(item) => String(item.id)} contentContainerStyle={styles.accountList} renderItem={({ item }) => <Pressable accessibilityRole="button" onPress={() => setAccountId(item.id ? String(item.id) : undefined)} style={[styles.accountChip, (item.id ? String(item.id) : undefined) === accountId && styles.accountChipActive]}><Text style={[styles.accountChipText, (item.id ? String(item.id) : undefined) === accountId && styles.accountChipTextActive]}>{item.name}</Text></Pressable>} /></View>
    {loadState.status === "loading" && !page && <StatusMessage title="正在读取账本…" action={<ActivityIndicator color={nativeColors.accent} />} />}
    {loadState.status === "error" && <StatusMessage title={errorMessage(loadState.errorCode)} tone="error" action={<Button onPress={() => void load()} variant="primary">重新读取</Button>} />}
    {loadState.status === "ready" && page && page.items.length === 0 && <StatusMessage title="还没有收支记录" detail="记下第一笔，账本就会开始工作。" />}
    {page && page.items.length > 0 && <FlatList data={page.items} keyExtractor={(item) => item.projection_id} renderItem={renderItem} contentContainerStyle={styles.list} />}
    <View style={styles.actions}><Button disabled={!writable} onPress={() => router.push("/(app)/record?mode=create" as never)} variant="primary">{writable ? "记一笔" : "仅可查看"}</Button><Button disabled={!writable} onPress={() => router.push("/(app)/import" as never)}>导入账单</Button></View>
  </Screen>;
}

const styles = StyleSheet.create({
  toolbar: { gap: 8 },
  toolbarLabel: { color: nativeColors.inkMuted, fontSize: 12, fontWeight: "700" },
  accountList: { gap: 8, paddingVertical: 2 },
  accountChip: { minHeight: 44, paddingHorizontal: 14, alignItems: "center", justifyContent: "center", borderWidth: 1, borderColor: nativeColors.rule, borderRadius: 3, backgroundColor: nativeColors.paperRaised },
  accountChipActive: { borderColor: nativeColors.accent, backgroundColor: nativeColors.accentSoft },
  accountChipText: { color: nativeColors.inkMuted, fontSize: 13, fontWeight: "600" },
  accountChipTextActive: { color: nativeColors.accent },
  list: { gap: 8, paddingBottom: 8 },
  row: { minHeight: 88, flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: 12, padding: 14, borderWidth: 1, borderColor: nativeColors.rule, backgroundColor: nativeColors.paperRaised },
  rowPressed: { borderColor: nativeColors.accent, backgroundColor: nativeColors.accentSoft },
  rowMain: { flex: 1, gap: 4 },
  counterparty: { color: nativeColors.ink, fontSize: 16, fontWeight: "700" },
  meta: { color: nativeColors.inkMuted, fontSize: 12 },
  note: { color: nativeColors.inkFaint, fontSize: 12 },
  rowAmount: { alignItems: "flex-end", gap: 5 },
  amount: { fontFamily: nativeTypography.mono, fontSize: 14, fontWeight: "700" },
  income: { color: nativeColors.income },
  expense: { color: nativeColors.expense },
  source: { color: nativeColors.inkFaint, fontFamily: nativeTypography.mono, fontSize: 10 },
  actions: { flexDirection: "row", gap: 8, paddingVertical: 8 },
});
