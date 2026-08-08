import "@fontsource/noto-sans-sc/chinese-simplified-400.css";
import "@fontsource/noto-sans-sc/chinese-simplified-600.css";
import "@fontsource/ibm-plex-mono/400.css";
import "./styles.css";
import { createRoot } from "react-dom/client";
import { useEffect, useState } from "react";
import { CashLedgerPage } from "./pages/CashLedgerPage";
import { InvestmentLedgerPage } from "./pages/InvestmentLedgerPage";

function App() {
  const [hash, setHash] = useState(window.location.hash);
  useEffect(() => {
    const onHashChange = () => setHash(window.location.hash);
    window.addEventListener("hashchange", onHashChange);
    return () => window.removeEventListener("hashchange", onHashChange);
  }, []);
  if (hash === "#investment-events") return <InvestmentLedgerPage view="events" />;
  if (hash === "#investment-holdings" || hash === "#investment-ledger") return <InvestmentLedgerPage />;
  return <CashLedgerPage />;
}

createRoot(document.getElementById("root")!).render(<App />);
