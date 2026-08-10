import "@fontsource/noto-sans-sc/chinese-simplified-400.css";
import "@fontsource/noto-sans-sc/chinese-simplified-600.css";
import "@fontsource/ibm-plex-mono/400.css";
import "./styles.css";
import { createRoot } from "react-dom/client";
import { useCallback, useEffect, useRef, useState } from "react";
import { CashLedgerPage } from "./pages/CashLedgerPage";
import { InvestmentLedgerPage } from "./pages/InvestmentLedgerPage";

export function App() {
  const [hash, setHash] = useState(window.location.hash);
  const [modalOpen, setModalOpen] = useState(false);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const mobileNavToggle = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const onHashChange = () => {
      setHash(window.location.hash);
      setMobileNavOpen(false);
    };
    window.addEventListener("hashchange", onHashChange);
    return () => window.removeEventListener("hashchange", onHashChange);
  }, []);
  const onModalStateChange = useCallback((open: boolean) => setModalOpen(open), []);
  const closeMobileNav = () => {
    if (!mobileNavOpen) return;
    setMobileNavOpen(false);
    requestAnimationFrame(() => mobileNavToggle.current?.focus());
  };
  const isEvents = hash === "#investment-events";
  const isInvestment = isEvents || hash === "#investment-holdings" || hash === "#investment-ledger";
  const currentInvestmentHash = isInvestment ? (isEvents ? "#investment-events" : "#investment-holdings") : "";

  return <div className={`page-layout${isInvestment ? " investment-page" : ""}`}>
    <main className="app-shell" inert={modalOpen || undefined}>
      <aside className={`sidebar${mobileNavOpen ? " is-nav-open" : ""}`}>
        <div className="sidebar-head">
          <strong>Finance Tracker</strong>
          <button ref={mobileNavToggle} className="menu-toggle" type="button" aria-expanded={mobileNavOpen} aria-controls="primary-navigation" aria-label={mobileNavOpen ? "关闭菜单" : "打开菜单"} onClick={() => setMobileNavOpen((open) => !open)}>
            <span className="menu-icon" aria-hidden="true">{mobileNavOpen ? "×" : "☰"}</span>
            <span>{mobileNavOpen ? "收起" : "菜单"}</span>
          </button>
        </div>
        <nav id="primary-navigation" aria-label="主要导航" onClick={closeMobileNav}>
          <a aria-current={!isInvestment ? "page" : undefined} href="#cash-ledger">收支账本</a>
          <div className="nav-group">
            <a className="nav-parent" aria-current={isInvestment ? "page" : undefined} href="#investment-holdings">投资账本</a>
            <div className="nav-subnav" aria-label="投资账本页面">
              <a className="subnav-link" aria-current={currentInvestmentHash === "#investment-holdings" ? "page" : undefined} href="#investment-holdings">当前持仓</a>
              <a className="subnav-link" aria-current={currentInvestmentHash === "#investment-events" ? "page" : undefined} href="#investment-events">投资事件</a>
            </div>
          </div>
        </nav>
      </aside>
      {isInvestment ? <InvestmentLedgerPage view={isEvents ? "events" : "holdings"} onModalStateChange={onModalStateChange} /> : <CashLedgerPage onModalStateChange={onModalStateChange} />}
    </main>
  </div>;
}

const root = document.getElementById("root");
if (root) createRoot(root).render(<App />);
