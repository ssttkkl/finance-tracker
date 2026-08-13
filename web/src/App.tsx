import { lazy, Suspense, useCallback, useEffect, useRef, useState } from "react";
import { CashLedgerPage } from "./pages/CashLedgerPage";
import { CashCategoriesPage } from "./pages/CashCategoriesPage";
import { CashImportPage } from "./pages/CashImportPage";

function navigate(path: string) {
  window.history.pushState({}, "", path);
  window.dispatchEvent(new PopStateEvent("popstate"));
}

const InvestmentLedgerPage = lazy(async () => {
  const module = await import("./pages/InvestmentLedgerPage");
  return { default: module.InvestmentLedgerPage };
});

export function App() {
  const [hash, setHash] = useState(window.location.hash);
  const [path, setPath] = useState(window.location.pathname);
  const [modalOpen, setModalOpen] = useState(false);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const mobileNavToggle = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const onHashChange = () => { setHash(window.location.hash); setMobileNavOpen(false); };
    const onPopState = () => setPath(window.location.pathname);
    window.addEventListener("hashchange", onHashChange);
    window.addEventListener("popstate", onPopState);
    return () => { window.removeEventListener("hashchange", onHashChange); window.removeEventListener("popstate", onPopState); };
  }, []);

  const onModalStateChange = useCallback((open: boolean) => setModalOpen(open), []);
  const closeMobileNav = () => {
    if (!mobileNavOpen) return;
    setMobileNavOpen(false);
    requestAnimationFrame(() => mobileNavToggle.current?.focus());
  };
  const isInvestmentEvents = hash === "#investment-events";
  const isInvestment = isInvestmentEvents || hash === "#investment-holdings" || hash === "#investment-ledger";
  const isCashCategory = !isInvestment && path === "/cash-categories";
  const isCashLedger = !isInvestment && !isCashCategory;
  const isInvestmentHoldings = isInvestment && !isInvestmentEvents;

  if (path === "/cash-import") return <CashImportPage onBack={() => navigate("/")} onDone={() => undefined} />;

  return <div className={`page-layout${isInvestment ? " investment-page" : ""}`}>
    <main className="app-shell" inert={modalOpen || undefined}>
      <aside className={`sidebar${mobileNavOpen ? " is-nav-open" : ""}`}>
        <div className="sidebar-head">
          <strong>Finance Tracker</strong>
          <button ref={mobileNavToggle} className="menu-toggle" type="button" aria-expanded={mobileNavOpen} aria-controls="primary-navigation" aria-label={mobileNavOpen ? "关闭菜单" : "打开菜单"} onClick={() => setMobileNavOpen((open) => !open)}>
            <span className="menu-icon" aria-hidden="true">{mobileNavOpen ? "×" : "☰"}</span><span>{mobileNavOpen ? "收起" : "菜单"}</span>
          </button>
        </div>
        <nav id="primary-navigation" aria-label="主要导航" onClick={closeMobileNav}>
          <div className="nav-group"><a className="nav-parent" aria-current={isCashLedger ? "page" : undefined} href="/" onClick={(event) => { event.preventDefault(); navigate("/"); }}>收支账本</a><div className="nav-subnav" aria-label="收支账本页面"><a className="subnav-link" aria-current={isCashCategory ? "page" : undefined} href="/cash-categories" onClick={(event) => { event.preventDefault(); navigate("/cash-categories"); }}>分类管理</a></div></div>
          <div className="nav-group"><a className="nav-parent" aria-current={isInvestmentHoldings ? "page" : undefined} href="#investment-holdings">投资账本</a><div className="nav-subnav" aria-label="投资账本页面"><a className="subnav-link" href="#investment-holdings">当前持仓</a><a className="subnav-link" aria-current={isInvestmentEvents ? "page" : undefined} href="#investment-events">投资事件</a></div></div>
        </nav>
      </aside>
      {isInvestment ? <Suspense fallback={<section className="ledger" aria-label="投资账本"><div className="status-view" role="status"><p>正在打开账本…</p></div></section>}><InvestmentLedgerPage view={isInvestmentEvents ? "events" : "holdings"} onModalStateChange={onModalStateChange} /></Suspense> : isCashCategory ? <CashCategoriesPage embedded /> : <CashLedgerPage embedded onOpenImport={() => navigate("/cash-import")} onModalStateChange={onModalStateChange} />}
    </main>
  </div>;
}
