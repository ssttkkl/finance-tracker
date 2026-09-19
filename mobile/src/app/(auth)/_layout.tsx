import { Redirect, Stack, type Href } from "expo-router";
import { useSession } from "@/state/session";
import { SessionLoading } from "@/components/SessionLoading";

export default function AuthLayout() {
  const { state } = useSession();
  if (state.status === "idle" || state.status === "loading") {
    return <SessionLoading />;
  }
  if (state.session) return <Redirect href={(state.session.active_workspace_id ? "/(app)/ledger" : "/(app)/workspace") as Href} />;
  return <Stack screenOptions={{ headerShown: false }} />;
}
