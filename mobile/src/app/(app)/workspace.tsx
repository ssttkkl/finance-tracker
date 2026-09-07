import { useState } from "react";
import { router } from "expo-router";
import { StyleSheet, Text, TextInput, View } from "react-native";
import { Button, Header, Label, Screen, StatusMessage, Surface } from "@/components/NativeShell";
import { errorMessage, useSession } from "@/state/session";
import { nativeColors, nativeTypography } from "@finance-tracker/design-tokens";
import { roleLabel } from "@finance-tracker/contracts";

export default function WorkspaceScreen() {
  const { state, selectWorkspace, createWorkspace, logout } = useSession();
  const [name, setName] = useState("");
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const workspaces = state.session?.workspaces ?? [];

  async function choose(id: string) {
    setBusyId(id); setError(null);
    try { await selectWorkspace(id); router.replace("/(app)/ledger" as never); }
    catch (cause) { setError(errorMessage(cause instanceof Error ? cause.message : "request_failed")); }
    finally { setBusyId(null); }
  }

  async function create() {
    if (!name.trim()) return;
    setBusyId("create"); setError(null);
    try { const session = await createWorkspace(name.trim()); setName(""); if (session.active_workspace_id) router.replace("/(app)/ledger" as never); }
    catch (cause) { setError(cause instanceof Error && cause.message === "workspace_forbidden" ? errorMessage("workspace_forbidden") : "无法创建工作区，请重试。"); }
    finally { setBusyId(null); }
  }

  return <Screen>
    <Header title="选择工作区" detail={`${state.session?.user.email ?? ""} · 选择后进入收支账本`} action={<Button onPress={() => void logout()}>退出</Button>} />
    {error && <StatusMessage title={error} tone="error" />}
    <Surface>
      <View style={styles.surfaceHeader}><View><Text style={styles.surfaceTitle}>你的工作区</Text><Text style={styles.surfaceDetail}>可编辑成员可以记账。</Text></View><Text style={styles.count}>{workspaces.length} 个</Text></View>
      <View style={styles.list}>{workspaces.length === 0 && <StatusMessage title="还没有工作区" detail="创建一个工作区开始记账。" />}{workspaces.map((workspace) => <Button key={workspace.id} disabled={busyId !== null} onPress={() => void choose(workspace.id)} variant={workspace.id === state.session?.active_workspace_id ? "primary" : "secondary"}><View style={styles.workspaceButton}><Text style={[styles.workspaceName, workspace.id === state.session?.active_workspace_id && styles.workspaceNameActive]}>{workspace.name}</Text><Text style={[styles.role, workspace.id === state.session?.active_workspace_id && styles.roleActive]}>{roleLabel[workspace.role]}</Text></View></Button>)}</View>
    </Surface>
    <Surface>
      <Label>新建工作区</Label>
      <TextInput editable={busyId === null} maxLength={255} onChangeText={setName} placeholder="例如：家庭账本" placeholderTextColor={nativeColors.inkFaint} style={styles.input} value={name} />
      <Button disabled={!name.trim() || busyId !== null} onPress={() => void create()} variant="primary">{busyId === "create" ? "正在创建…" : "创建工作区"}</Button>
    </Surface>
  </Screen>;
}

const styles = StyleSheet.create({
  surfaceHeader: { flexDirection: "row", justifyContent: "space-between", gap: 12, paddingBottom: 12, borderBottomWidth: 1, borderBottomColor: nativeColors.rule },
  surfaceTitle: { color: nativeColors.ink, fontSize: 17, fontWeight: "700" },
  surfaceDetail: { marginTop: 4, color: nativeColors.inkMuted, fontSize: 13 },
  count: { color: nativeColors.ink, fontFamily: nativeTypography.mono, fontSize: 14 },
  list: { gap: 8 },
  workspaceButton: { width: "100%", flexDirection: "row", justifyContent: "space-between", gap: 12 },
  workspaceName: { color: nativeColors.ink, fontSize: 15, fontWeight: "700" },
  workspaceNameActive: { color: nativeColors.accentInk },
  role: { color: nativeColors.inkMuted, fontFamily: nativeTypography.mono, fontSize: 12 },
  roleActive: { color: nativeColors.accentInk },
  input: { minHeight: 48, paddingHorizontal: 12, borderWidth: 1, borderColor: nativeColors.rule, borderRadius: 3, color: nativeColors.ink, backgroundColor: nativeColors.paperRaised, fontSize: 16 },
});
