import { Redirect, type Href } from "expo-router";
import { useSession } from "@/state/session";
import { SessionLoading } from "@/components/SessionLoading";

export default function Index() {
  const { state } = useSession();
  if (state.status === "loading" || state.status === "idle") return <SessionLoading />;
  if (state.session) return <Redirect href={(state.session.active_workspace_id ? "/(app)/ledger" : "/(app)/workspace") as Href} />;
  return <Redirect href={"/(auth)/login" as Href} />;
}
