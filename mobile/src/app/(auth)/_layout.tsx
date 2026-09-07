import { Redirect, Stack, type Href } from "expo-router";
import { ActivityIndicator, StyleSheet, View } from "react-native";
import { useSession } from "@/state/session";
import { nativeColors } from "@finance-tracker/design-tokens";

export default function AuthLayout() {
  const { state } = useSession();
  if (state.status === "idle" || state.status === "loading") {
    return <View style={styles.loading}><ActivityIndicator color={nativeColors.accent} /></View>;
  }
  if (state.session) return <Redirect href={(state.session.active_workspace_id ? "/(app)/ledger" : "/(app)/workspace") as Href} />;
  return <Stack screenOptions={{ headerShown: false }} />;
}

const styles = StyleSheet.create({ loading: { flex: 1, alignItems: "center", justifyContent: "center", backgroundColor: nativeColors.paper } });
