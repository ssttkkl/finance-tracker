import { usePathname, router } from "expo-router";
import { Pressable, ScrollView, StyleSheet, Text, View, useWindowDimensions } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { useState, type ReactNode } from "react";
import { componentTokens, nativeColors, nativeTypography, designTokens } from "@finance-tracker/design-tokens";
import { copy, layoutClassForWidth, semanticIds, type LayoutClass } from "@finance-tracker/presentation";
import { navigationModeForLayout } from "../platform/layout";

type ScreenProps = {
  children: ReactNode;
  scroll?: boolean;
  navigation?: boolean;
  testID?: string;
};

export function useResponsiveLayout(): LayoutClass {
  const { width } = useWindowDimensions();
  return layoutClassForWidth(width);
}

export function Screen({ children, scroll = true, navigation = true, testID }: ScreenProps) {
  const layout = useResponsiveLayout();
  const navigationMode = navigationModeForLayout(layout);
  const content = <View testID={testID} style={[styles.content, contentStyleFor(layout)]}>{children}</View>;
  const body = scroll
    ? <ScrollView contentContainerStyle={[styles.scroll, scrollStyleFor(layout)]} keyboardShouldPersistTaps="handled">{content}</ScrollView>
    : <View style={[styles.nonScrolling, contentStyleFor(layout)]}>{content}</View>;
  return <SafeAreaView edges={["top", "bottom"]} style={styles.screen}>
    {navigation && navigationMode === "compact-menu" ? <CompactNavigation /> : null}
    {navigation && navigationMode === "wide-rail" ? <WideNavigation /> : null}
    <View style={styles.viewport}>{body}</View>
  </SafeAreaView>;
}

export function Header({ eyebrow, title, detail, action, testID }: { eyebrow?: string; title: string; detail?: string; action?: ReactNode; testID?: string }) {
  return <View testID={testID} style={styles.header}><View style={styles.headerCopy}>{eyebrow && <Text style={styles.eyebrow}>{eyebrow}</Text>}<Text accessibilityRole="header" style={styles.title}>{title}</Text>{detail && <Text style={styles.detail}>{detail}</Text>}</View>{action}</View>;
}

export function Surface({ children, style, testID }: { children: ReactNode; style?: object; testID?: string }) {
  return <View testID={testID} style={[styles.surface, style]}>{children}</View>;
}

export function Button({ children, onPress, disabled = false, variant = "secondary", accessibilityLabel, testID }: { children: ReactNode; onPress?: () => void; disabled?: boolean; variant?: "primary" | "secondary" | "danger"; accessibilityLabel?: string; testID?: string }) {
  return <Pressable testID={testID} accessibilityLabel={accessibilityLabel} accessibilityRole="button" disabled={disabled} onPress={onPress} style={({ pressed }) => [styles.button, styles[`button_${variant}`], pressed && !disabled && styles.buttonPressed, disabled && styles.buttonDisabled]}><View pointerEvents="none" style={styles.buttonContent}>{typeof children === "string" || typeof children === "number" ? <Text style={[styles.buttonText, variant !== "secondary" && styles.buttonTextStrong]}>{children}</Text> : children}</View></Pressable>;
}

export function StatusMessage({ title, detail, tone = "muted", action, testID }: { title: string; detail?: string; tone?: "muted" | "error" | "success"; action?: ReactNode; testID?: string }) {
  return <View testID={testID} accessibilityLiveRegion="polite" style={[styles.status, tone === "error" && styles.statusError, tone === "success" && styles.statusSuccess]}><Text style={styles.statusTitle}>{title}</Text>{detail && <Text style={styles.statusDetail}>{detail}</Text>}{action}</View>;
}

export function Label({ children }: { children: ReactNode }) {
  return <Text style={styles.label}>{children}</Text>;
}

function contentStyleFor(layout: LayoutClass) {
  return layout === "wide" ? styles.contentWide : layout === "regular" ? styles.contentRegular : styles.contentCompact;
}

function scrollStyleFor(layout: LayoutClass) {
  return layout === "wide" ? styles.scrollWide : layout === "regular" ? styles.scrollRegular : styles.scrollCompact;
}

function WideNavigation() {
  const pathname = usePathname();
  const isLedger = pathname.endsWith("/ledger") || pathname.endsWith("/record");
  const isImport = pathname.endsWith("/import");
  const isWorkspace = pathname.endsWith("/workspace");
  return <View style={styles.rail}>
    <Text style={styles.railBrand}>{copy.product.name}</Text>
    <View style={styles.railNav} accessibilityLabel={copy.navigation.main}>
      <View style={styles.railGroup}>
        <RailLink active={isLedger} label={copy.navigation.cashLedger} onPress={() => router.replace("/(app)/ledger" as never)} />
        <RailLink active={isImport} indent label={copy.navigation.import} onPress={() => router.push("/(app)/import" as never)} />
      <RailLink disabled indent label={`${copy.navigation.categories} · ${copy.navigation.unavailable}`} />
      </View>
      <View style={styles.railGroup}>
        <RailLink disabled label={copy.navigation.investmentLedger} />
        <RailLink disabled indent label={copy.navigation.holdings} />
        <RailLink disabled indent label={copy.navigation.investmentEvents} />
      </View>
      <RailLink disabled label={copy.navigation.workspaceManagement} />
    </View>
    <View style={styles.railFooter}>
      <Text style={styles.railFooterLabel}>{copy.workspace.current}</Text>
      <Text style={styles.railFooterValue}>{isWorkspace ? copy.workspace.title : copy.navigation.cashLedger}</Text>
    </View>
  </View>;
}

function CompactNavigation() {
  const pathname = usePathname();
  const [open, setOpen] = useState(false);
  const isLedger = pathname.endsWith("/ledger") || pathname.endsWith("/record");
  const isImport = pathname.endsWith("/import");

  const navigate = (target: "ledger" | "import") => {
    setOpen(false);
    if (target === "ledger") router.replace("/(app)/ledger" as never);
    else router.push("/(app)/import" as never);
  };

  return <View style={styles.mobileNav}>
    <View style={styles.mobileNavBar}>
      <Text style={styles.mobileNavBrand}>{copy.product.name}</Text>
      <Pressable testID={semanticIds.navigationMenu} accessibilityRole="button" accessibilityLabel={open ? copy.navigation.closeMenu : copy.navigation.openMenu} accessibilityState={{ expanded: open }} onPress={() => setOpen((current) => !current)} style={styles.mobileMenuButton}>
        <Text style={styles.mobileMenuButtonText}>{open ? "×" : "☰"}</Text>
      </Pressable>
    </View>
    {open && <View style={styles.mobileNavPanel} accessibilityLabel={copy.navigation.main}>
      <Pressable testID={semanticIds.navigationLedger} accessibilityRole="button" accessibilityState={{ selected: isLedger }} onPress={() => navigate("ledger")} style={[styles.mobileNavLink, isLedger && styles.mobileNavLinkActive]}><Text style={[styles.mobileNavLinkText, isLedger && styles.mobileNavLinkTextActive]}>{copy.navigation.cashLedger}</Text></Pressable>
      <Pressable testID={semanticIds.navigationImport} accessibilityRole="button" accessibilityState={{ selected: isImport }} onPress={() => navigate("import")} style={[styles.mobileNavLink, isImport && styles.mobileNavLinkActive]}><Text style={[styles.mobileNavLinkText, isImport && styles.mobileNavLinkTextActive]}>{copy.navigation.import}</Text></Pressable>
      <Text style={styles.mobileNavDisabled}>{copy.navigation.categories} · {copy.navigation.unavailable}</Text>
      <Text style={styles.mobileNavDisabled}>{copy.navigation.investmentLedger} · {copy.navigation.unavailable}</Text>
      <Text style={styles.mobileNavDisabled}>{copy.navigation.workspaceManagement} · {copy.navigation.unavailable}</Text>
    </View>}
  </View>;
}

function RailLink({ label, onPress, active = false, disabled = false, indent = false }: { label: string; onPress?: () => void; active?: boolean; disabled?: boolean; indent?: boolean }) {
  return <Pressable accessibilityRole="button" accessibilityState={{ disabled, selected: active }} disabled={disabled} onPress={onPress} style={[styles.railLink, indent && styles.railLinkIndent, active && styles.railLinkActive, disabled && styles.railLinkDisabled]}><Text style={[styles.railLinkText, active && styles.railLinkTextActive]}>{label}</Text></Pressable>;
}

const styles = StyleSheet.create({
  screen: { flex: 1, flexDirection: "row", backgroundColor: nativeColors.paper },
  viewport: { flex: 1, minWidth: 0 },
  scroll: { flexGrow: 1, paddingBottom: designTokens.space.eight },
  scrollCompact: { paddingHorizontal: componentTokens.page.mobilePadding },
  scrollRegular: { paddingHorizontal: componentTokens.page.regularPadding },
  scrollWide: { paddingHorizontal: componentTokens.page.widePadding },
  nonScrolling: { flex: 1 },
  content: { width: "100%", alignSelf: "center", flexGrow: 1, gap: designTokens.space.four },
  contentCompact: { maxWidth: componentTokens.page.formMaxWidth },
  contentRegular: { maxWidth: componentTokens.page.formMaxWidth },
  contentWide: { maxWidth: componentTokens.page.maxContentWidth },
  header: { flexDirection: "row", alignItems: "flex-end", justifyContent: "space-between", gap: designTokens.space.three, paddingTop: designTokens.space.three, paddingBottom: designTokens.space.three, borderBottomWidth: 1, borderBottomColor: nativeColors.ruleStrong },
  headerCopy: { flex: 1, minWidth: 0, gap: designTokens.space.one },
  eyebrow: { color: nativeColors.accent, fontFamily: nativeTypography.mono, fontSize: 11, letterSpacing: 1.2, textTransform: "uppercase" },
  title: { color: nativeColors.ink, fontSize: 28, fontWeight: "700", letterSpacing: -0.6 },
  detail: { color: nativeColors.inkMuted, fontSize: 14, lineHeight: 21 },
  surface: { gap: designTokens.space.three, padding: componentTokens.surface.padding, borderWidth: 1, borderColor: nativeColors.rule, backgroundColor: nativeColors.paperRaised },
  button: { minHeight: componentTokens.control.normalHeight, paddingHorizontal: designTokens.space.three, alignItems: "center", justifyContent: "center", borderWidth: 1, borderRadius: designTokens.radius.one },
  buttonContent: { alignItems: "center", justifyContent: "center", width: "100%" },
  button_primary: { borderColor: nativeColors.accent, backgroundColor: nativeColors.accent },
  button_secondary: { borderColor: nativeColors.ruleStrong, backgroundColor: nativeColors.paperRaised },
  button_danger: { borderColor: nativeColors.danger, backgroundColor: nativeColors.paperRaised },
  buttonPressed: { opacity: 0.78 },
  buttonDisabled: { opacity: 0.45 },
  buttonText: { color: nativeColors.ink, fontSize: 15, fontWeight: "600", textAlign: "center" },
  buttonTextStrong: { color: nativeColors.accentInk },
  status: { gap: designTokens.space.one, padding: designTokens.space.three, borderLeftWidth: 3, borderLeftColor: nativeColors.ruleStrong, backgroundColor: nativeColors.paperMuted },
  statusError: { borderLeftColor: nativeColors.danger, backgroundColor: nativeColors.errorSurface },
  statusSuccess: { borderLeftColor: nativeColors.income, backgroundColor: nativeColors.successSurface },
  statusTitle: { color: nativeColors.ink, fontSize: 15, fontWeight: "700" },
  statusDetail: { color: nativeColors.inkMuted, fontSize: 13, lineHeight: 19 },
  label: { color: nativeColors.inkMuted, fontSize: 13, fontWeight: "600" },
  rail: { width: componentTokens.shell.wideRailWidth, flexShrink: 0, gap: designTokens.space.eight, paddingHorizontal: designTokens.space.four, paddingVertical: designTokens.space.six, backgroundColor: nativeColors.sidebar },
  railBrand: { color: nativeColors.accentInk, fontSize: 16, fontWeight: "700" },
  railNav: { gap: designTokens.space.six },
  railGroup: { gap: 4 },
  railLink: { minHeight: componentTokens.control.normalHeight, justifyContent: "center", paddingHorizontal: designTokens.space.two, borderRadius: designTokens.radius.one },
  railLinkIndent: { paddingLeft: designTokens.space.four },
  railLinkActive: { borderLeftWidth: 3, borderLeftColor: nativeColors.sidebarCurrentRule, backgroundColor: nativeColors.sidebarRaised },
  railLinkDisabled: { opacity: 0.5 },
  railLinkText: { color: nativeColors.sidebarLink, fontSize: 14, fontWeight: "600" },
  railLinkTextActive: { color: nativeColors.accentInk },
  railFooter: { gap: designTokens.space.one, marginTop: "auto", paddingTop: designTokens.space.four, borderTopWidth: 1, borderTopColor: nativeColors.sidebarRaised },
  railFooterLabel: { color: nativeColors.sidebarLink, fontSize: 11 },
  railFooterValue: { color: nativeColors.accentInk, fontSize: 13, fontWeight: "700" },
  mobileNav: { backgroundColor: nativeColors.sidebar },
  mobileNavBar: { minHeight: 64, flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: 10, paddingHorizontal: 16, borderBottomWidth: 1, borderBottomColor: nativeColors.sidebarRaised },
  mobileNavBrand: { color: nativeColors.sidebarLink, fontSize: 14, fontWeight: "700" },
  mobileMenuButton: { minWidth: 44, minHeight: 44, alignItems: "center", justifyContent: "center", borderWidth: 1, borderColor: nativeColors.ruleStrong },
  mobileMenuButtonText: { color: nativeColors.sidebarLink, fontSize: 22, lineHeight: 24 },
  mobileNavPanel: { gap: 4, paddingHorizontal: 16, paddingVertical: 12, borderBottomWidth: 1, borderBottomColor: nativeColors.sidebarRaised },
  mobileNavLink: { minHeight: 44, justifyContent: "center", paddingHorizontal: 12, borderRadius: designTokens.radius.one },
  mobileNavLinkActive: { borderLeftWidth: 2, borderLeftColor: nativeColors.sidebarCurrentRule, backgroundColor: nativeColors.sidebarRaised },
  mobileNavLinkText: { color: nativeColors.sidebarLink, fontSize: 14, fontWeight: "600" },
  mobileNavLinkTextActive: { color: nativeColors.accentInk },
  mobileNavDisabled: { minHeight: 38, paddingHorizontal: 12, paddingVertical: 9, color: nativeColors.sidebarLink, fontSize: 13, opacity: 0.5 },
});

export const shellStyles = styles;
