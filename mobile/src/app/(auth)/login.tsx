import { useEffect, useState } from "react";
import { KeyboardAvoidingView, Platform, Pressable, StyleSheet, Text, TextInput, View } from "react-native";
import { router } from "expo-router";
import type { Session } from "@finance-tracker/contracts";
import { Button, Screen, StatusMessage, Surface } from "@/components/NativeShell";
import { errorMessage, useSession } from "@/state/session";
import { nativeColors, nativeTypography } from "@finance-tracker/design-tokens";
import { copy, semanticIds } from "@finance-tracker/presentation";

export default function LoginScreen() {
  const { state, login, register, apiOrigin } = useSession();
  const [registering, setRegistering] = useState(false);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [apiOriginDraft, setApiOriginDraft] = useState(apiOrigin.value);
  const [apiOriginError, setApiOriginError] = useState<string | null>(null);
  const [resettingApiOrigin, setResettingApiOrigin] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const busy = state.status === "loading";

  useEffect(() => {
    setApiOriginDraft(apiOrigin.value);
  }, [apiOrigin.value]);

  async function submit() {
    setFormError(null);
    setApiOriginError(null);
    if (apiOrigin.enabled) {
      try {
        setApiOriginDraft(apiOrigin.select(apiOriginDraft));
      } catch {
        setApiOriginError(copy.auth.apiOriginInvalid);
        return;
      }
    }
    let session: Session;
    try {
      session = await (registering ? register : login)(email.trim(), password);
    } catch (cause) {
      setFormError(cause instanceof Error && cause.message === "api_origin_invalid"
        ? errorMessage("api_origin_invalid")
        : copy.auth.error);
      return;
    }
    router.replace((session.active_workspace_id ? "/(app)/ledger" : "/(app)/workspace") as never);
  }

  async function resetApiOrigin() {
    setApiOriginError(null);
    setFormError(null);
    setResettingApiOrigin(true);
    try {
      setApiOriginDraft(await apiOrigin.reset());
    } finally {
      setResettingApiOrigin(false);
    }
  }

  return <Screen navigation={false} testID={semanticIds.authScreen}>
      <KeyboardAvoidingView behavior={Platform.OS === "ios" ? "padding" : undefined} style={styles.wrapper}>
      <View style={styles.brand}><Text style={styles.brandName}>{copy.product.name}</Text></View>
      <Surface style={styles.card}>
        <Text style={styles.eyebrow}>{copy.auth.eyebrow}</Text>
        <Text accessibilityRole="header" style={styles.heading}>{registering ? copy.auth.registerTitle : copy.auth.loginTitle}</Text>
        <View style={styles.form}>
          <View style={styles.field}><Text style={styles.label}>{copy.auth.email}</Text><TextInput testID={semanticIds.authEmail} autoCapitalize="none" autoComplete="email" keyboardType="email-address" editable={!busy} onChangeText={setEmail} placeholder="name@example.com" placeholderTextColor={nativeColors.inkFaint} style={styles.input} value={email} /></View>
          <View style={styles.field}><Text style={styles.label}>{copy.auth.password}</Text><TextInput testID={semanticIds.authPassword} autoComplete={registering ? "new-password" : "current-password"} editable={!busy} onChangeText={setPassword} placeholder="至少 12 个字符" placeholderTextColor={nativeColors.inkFaint} secureTextEntry style={styles.input} value={password} /></View>
          {apiOrigin.enabled && <View style={styles.field}>
            <View style={styles.fieldHeader}>
              <Text style={styles.label}>{copy.auth.apiOrigin}</Text>
              <Pressable
                testID={semanticIds.authApiOriginReset}
                accessibilityRole="button"
                accessibilityLabel={copy.auth.apiOriginReset}
                accessibilityState={{ disabled: busy || resettingApiOrigin }}
                disabled={busy || resettingApiOrigin}
                onPress={() => void resetApiOrigin()}
                style={({ pressed }) => [styles.resetButton, pressed && !busy && !resettingApiOrigin && styles.resetButtonPressed]}
              >
                <Text style={styles.resetText}>{copy.auth.apiOriginReset}</Text>
              </Pressable>
            </View>
            <TextInput
              testID={semanticIds.authApiOrigin}
              accessibilityLabel={copy.auth.apiOrigin}
              autoCapitalize="none"
              autoComplete="url"
              autoCorrect={false}
              editable={!busy && !resettingApiOrigin}
              keyboardType="url"
              onChangeText={(value) => { setApiOriginDraft(value); setApiOriginError(null); }}
              placeholder={apiOrigin.buildValue || "https://api.example.com"}
              placeholderTextColor={nativeColors.inkFaint}
              spellCheck={false}
              style={styles.input}
              value={apiOriginDraft}
            />
            {apiOriginError && <StatusMessage title={apiOriginError} tone="error" />}
          </View>}
          {formError && <StatusMessage title={formError} tone="error" />}
          <Button testID={semanticIds.authSubmit} disabled={busy || !email.trim() || !password} onPress={() => void submit()} variant="primary">{busy ? copy.auth.processing : registering ? copy.auth.register : copy.auth.login}</Button>
        </View>
        <Button testID={semanticIds.authToggleMode} disabled={busy} onPress={() => { setRegistering((value) => !value); setFormError(null); setApiOriginError(null); }} variant="secondary">{registering ? copy.auth.switchToLogin : copy.auth.switchToRegister}</Button>
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
  fieldHeader: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: 12 },
  label: { color: nativeColors.inkMuted, fontSize: 13, fontWeight: "600" },
  input: { minHeight: 48, paddingHorizontal: 12, borderWidth: 1, borderColor: nativeColors.rule, borderRadius: 3, color: nativeColors.ink, backgroundColor: nativeColors.paperRaised, fontSize: 16 },
  resetButton: { minHeight: 44, paddingHorizontal: 8, alignItems: "center", justifyContent: "center" },
  resetButtonPressed: { opacity: 0.78 },
  resetText: { color: nativeColors.accent, fontSize: 13, fontWeight: "600" },
});
