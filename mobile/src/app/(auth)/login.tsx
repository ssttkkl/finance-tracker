import { useState } from "react";
import { KeyboardAvoidingView, Platform, StyleSheet, Text, TextInput, View } from "react-native";
import { router } from "expo-router";
import type { Session } from "@finance-tracker/contracts";
import { Button, Screen, StatusMessage, Surface } from "@/components/NativeShell";
import { errorMessage, useSession } from "@/state/session";
import { nativeColors, nativeTypography } from "@finance-tracker/design-tokens";

export default function LoginScreen() {
  const { state, login, register } = useSession();
  const [registering, setRegistering] = useState(false);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const busy = state.status === "loading";

  async function submit() {
    setFormError(null);
    let session: Session;
    try {
      session = await (registering ? register : login)(email.trim(), password);
    } catch (cause) {
      setFormError(cause instanceof Error && cause.message === "api_origin_invalid"
        ? errorMessage("api_origin_invalid")
        : registering ? "账户创建失败，请检查信息后重试。" : "邮箱或密码不正确，请重试。");
      return;
    }
    router.replace((session.active_workspace_id ? "/(app)/ledger" : "/(app)/workspace") as never);
  }

  return <Screen>
    <KeyboardAvoidingView behavior={Platform.OS === "ios" ? "padding" : undefined} style={styles.wrapper}>
      <View style={styles.brand}><Text style={styles.brandName}>Finance Tracker</Text><Text style={styles.brandRule}>ANDROID · IOS</Text></View>
      <Surface style={styles.card}>
        <Text style={styles.eyebrow}>工作区访问</Text>
        <Text accessibilityRole="header" style={styles.heading}>{registering ? "创建你的账户" : "登录到你的账本"}</Text>
        <Text style={styles.description}>同一账户在 Web 和手机上看到相同的账。</Text>
        <View style={styles.form}>
          <View style={styles.field}><Text style={styles.label}>邮箱</Text><TextInput autoCapitalize="none" autoComplete="email" keyboardType="email-address" editable={!busy} onChangeText={setEmail} placeholder="name@example.com" placeholderTextColor={nativeColors.inkFaint} style={styles.input} value={email} /></View>
          <View style={styles.field}><Text style={styles.label}>密码</Text><TextInput autoComplete={registering ? "new-password" : "current-password"} editable={!busy} onChangeText={setPassword} placeholder="至少 12 个字符" placeholderTextColor={nativeColors.inkFaint} secureTextEntry style={styles.input} value={password} /></View>
          {formError && <StatusMessage title={formError} tone="error" />}
          <Button disabled={busy || !email.trim() || !password} onPress={() => void submit()} variant="primary">{busy ? "正在处理…" : registering ? "注册" : "登录"}</Button>
        </View>
        <Button disabled={busy} onPress={() => { setRegistering((value) => !value); setFormError(null); }} variant="secondary">{registering ? "已有账户？登录" : "还没有账户？注册"}</Button>
      </Surface>
    </KeyboardAvoidingView>
  </Screen>;
}

const styles = StyleSheet.create({
  wrapper: { flex: 1, justifyContent: "center", gap: 24 },
  brand: { gap: 8, paddingVertical: 16, borderBottomWidth: 1, borderBottomColor: nativeColors.ruleStrong },
  brandName: { color: nativeColors.ink, fontSize: 17, fontWeight: "700" },
  brandRule: { color: nativeColors.accent, fontFamily: nativeTypography.mono, fontSize: 10, letterSpacing: 1.1 },
  card: { gap: 16 },
  eyebrow: { color: nativeColors.accent, fontFamily: nativeTypography.mono, fontSize: 11, letterSpacing: 1 },
  heading: { color: nativeColors.ink, fontSize: 25, fontWeight: "700" },
  description: { color: nativeColors.inkMuted, fontSize: 14, lineHeight: 21 },
  form: { gap: 16 },
  field: { gap: 7 },
  label: { color: nativeColors.inkMuted, fontSize: 13, fontWeight: "600" },
  input: { minHeight: 48, paddingHorizontal: 12, borderWidth: 1, borderColor: nativeColors.rule, borderRadius: 3, color: nativeColors.ink, backgroundColor: nativeColors.paperRaised, fontSize: 16 },
});
