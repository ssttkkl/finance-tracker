import { ActivityIndicator, StyleSheet, Text, View } from "react-native";
import { nativeColors } from "@finance-tracker/design-tokens";
import { copy } from "@finance-tracker/presentation";

export function SessionLoading() {
  return <View style={styles.loading}><ActivityIndicator color={nativeColors.accent} /><Text style={styles.label}>{copy.auth.loading}</Text></View>;
}

const styles = StyleSheet.create({
  loading: { flex: 1, alignItems: "center", justifyContent: "center", gap: 12, backgroundColor: nativeColors.paper },
  label: { color: nativeColors.inkMuted, fontSize: 14 },
});
