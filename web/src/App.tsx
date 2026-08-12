import { useEffect, useState } from "react";
import { CashImportPage } from "./pages/CashImportPage";
import { CashLedgerPage } from "./pages/CashLedgerPage";

function navigate(path: string) {
  window.history.pushState({}, "", path);
  window.dispatchEvent(new PopStateEvent("popstate"));
}

export function App() {
  const [path, setPath] = useState(window.location.pathname);
  useEffect(() => {
    const handlePopState = () => setPath(window.location.pathname);
    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, []);
  if (path === "/cash-import") {
    return <CashImportPage onBack={() => navigate("/")} onDone={() => undefined} />;
  }
  return <CashLedgerPage onOpenImport={() => navigate("/cash-import")} />;
}
