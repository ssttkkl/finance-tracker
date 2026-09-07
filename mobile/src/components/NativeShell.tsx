import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import type { ReactNode } from "react";
import { nativeColors, nativeTypography, designTokens } from "@finance-tracker/design-tokens";

export function Screen({ children, scroll = true }: { children: ReactNode; scroll?: boolean }) {
  const content = <View style={styles.content}>{children}</View>;
  return <SafeAreaView edges={["top", "bottom"]} style={styles.screen}>{scroll ? <ScrollView contentContainerStyle={styles.scroll}>{content}</ScrollView> : content}</SafeAreaView>;
}

export function Header({ eyebrow, title, detail, action }: { eyebrow?: string; title: string; detail?: string; action?: ReactNode }) {
  return <View style={styles.header}><View style={styles.headerCopy}>{eyebrow && <Text style={styles.eyebrow}>{eyebrow}</Text>}<Text accessibilityRole="header" style={styles.title}>{title}</Text>{detail && <Text style={styles.detail}>{detail}</Text>}</View>{action}</View>;
}

export function Surface({ children, style }: { children: ReactNode; style?: object }) {
  return <View style={[styles.surface, style]}>{children}</View>;
}

export function Button({ children, onPress, disabled = false, variant = "secondary", accessibilityLabel }: { children: ReactNode; onPress?: () => void; disabled?: boolean; variant?: "primary" | "secondary" | "danger"; accessibilityLabel?: string }) {
  return <Pressable accessibilityLabel={accessibilityLabel} accessibilityRole="button" disabled={disabled} onPress={onPress} style={({ pressed }) => [styles.button, styles[`button_${variant}`], pressed && !disabled && styles.buttonPressed, disabled && styles.buttonDisabled]}><View pointerEvents="none" style={styles.buttonContent}>{typeof children === "string" || typeof children === "number" ? <Text style={[styles.buttonText, variant !== "secondary" && styles.buttonTextStrong]}>{children}</Text> : children}</View></Pressable>;
}

export function StatusMessage({ title, detail, tone = "muted", action }: { title: string; detail?: string; tone?: "muted" | "error" | "success"; action?: ReactNode }) {
  return <View accessibilityLiveRegion="polite" style={[styles.status, tone === "error" && styles.statusError, tone === "success" && styles.statusSuccess]}><Text style={styles.statusTitle}>{title}</Text>{detail && <Text style={styles.statusDetail}>{detail}</Text>}{action}</View>;
}

export function Label({ children }: { children: ReactNode }) {
  return <Text style={styles.label}>{children}</Text>;
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: nativeColors.paper },
  scroll: { paddingBottom: designTokens.space.eight },
  content: { width: "100%", maxWidth: 720, alignSelf: "center", paddingHorizontal: designTokens.space.four, gap: designTokens.space.four },
  header: { flexDirection: "row", alignItems: "flex-end", justifyContent: "space-between", gap: designTokens.space.three, paddingTop: designTokens.space.three, paddingBottom: designTokens.space.three, borderBottomWidth: 1, borderBottomColor: nativeColors.ruleStrong },
  headerCopy: { flex: 1, gap: designTokens.space.one },
  eyebrow: { color: nativeColors.accent, fontFamily: nativeTypography.mono, fontSize: 11, letterSpacing: 1.2, textTransform: "uppercase" },
  title: { color: nativeColors.ink, fontSize: 28, fontWeight: "700", letterSpacing: -0.6 },
  detail: { color: nativeColors.inkMuted, fontSize: 14, lineHeight: 21 },
  surface: { gap: designTokens.space.three, padding: designTokens.space.three, borderWidth: 1, borderColor: nativeColors.rule, backgroundColor: nativeColors.paperRaised },
  button: { minHeight: 48, paddingHorizontal: designTokens.space.three, alignItems: "center", justifyContent: "center", borderWidth: 1, borderRadius: designTokens.radius.one },
  buttonContent: { alignItems: "center", justifyContent: "center", width: "100%" },
  button_primary: { borderColor: nativeColors.accent, backgroundColor: nativeColors.accent },
  button_secondary: { borderColor: nativeColors.ruleStrong, backgroundColor: nativeColors.paperRaised },
  button_danger: { borderColor: nativeColors.danger, backgroundColor: nativeColors.paperRaised },
  buttonPressed: { opacity: 0.78 },
  buttonDisabled: { opacity: 0.45 },
  buttonText: { color: nativeColors.ink, fontSize: 15, fontWeight: "600" },
  buttonTextStrong: { color: nativeColors.accentInk },
  status: { gap: designTokens.space.one, padding: designTokens.space.three, borderLeftWidth: 3, borderLeftColor: nativeColors.ruleStrong, backgroundColor: nativeColors.paperMuted },
  statusError: { borderLeftColor: nativeColors.danger, backgroundColor: nativeColors.errorSurface },
  statusSuccess: { borderLeftColor: nativeColors.income, backgroundColor: nativeColors.successSurface },
  statusTitle: { color: nativeColors.ink, fontSize: 15, fontWeight: "700" },
  statusDetail: { color: nativeColors.inkMuted, fontSize: 13, lineHeight: 19 },
  label: { color: nativeColors.inkMuted, fontSize: 13, fontWeight: "600" },
});

export const shellStyles = styles;
