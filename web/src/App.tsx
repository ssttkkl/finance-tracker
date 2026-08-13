import { lazy, Suspense, useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { CashLedgerPage } from "./pages/CashLedgerPage";
import { CashCategoriesPage } from "./pages/CashCategoriesPage";
import { CashImportPage } from "./pages/CashImportPage";

function navigate(path: string) {
  window.history.pushState({}, "", path);
  window.dispatchEvent(new PopStateEvent("popstate"));
}

function normalizeRoute(pathname: string, hash: string): string {
  if (pathname !== "/" || !hash) return pathname;
  if (hash === "#investment-events") return "/investment-events";
  if (hash === "#investment-holdings" || hash === "#investment-ledger") return "/investment-holdings";
  return pathname;
}

const InvestmentLedgerPage = lazy(async () => {
  const module = await import("./pages/InvestmentLedgerPage");
  return { default: module.InvestmentLedgerPage };
});

export function App({ sidebarFooter, mobileAccount, workspacePage, onWorkspaceManagement, workspaceManagementActive = false }: { sidebarFooter?: ReactNode; mobileAccount?: ReactNode; workspacePage?: ReactNode; onWorkspaceManagement?: () => void; workspaceManagementActive?: boolean } = {}) {
  const [path, setPath] = useState(() => normalizeRoute(window.location.pathname, window.location.hash));
  const [modalOpen, setModalOpen] = useState(false);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const mobileNavToggle = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const onPopState = () => setPath(window.location.pathname);
    window.addEventListener("popstate", onPopState);
    const normalized = normalizeRoute(window.location.pathname, window.location.hash);
    if (normalized !== window.location.pathname) {
      window.history.replaceState({}, "", normalized);
      setPath(normalized);
    }
    return () => window.removeEventListener("popstate", onPopState);
  }, []);

  const onModalStateChange = useCallback((open: boolean) => setModalOpen(open), []);
  const closeMobileNav = () => {
    if (!mobileNavOpen) return;
    setMobileNavOpen(false);
    requestAnimationFrame(() => mobileNavToggle.current?.focus());
  };
  const isInvestmentEvents = path === "/investment-events";
  const isInvestment = isInvestmentEvents || path === "/investment-holdings";
  const isCashCategory = !isInvestment && path === "/cash-categories";
  const isCashLedger = !isInvestment && !isCashCategory;
  const isInvestmentHoldings = isInvestment && !isInvestmentEvents;

  if (path === "/cash-import") return <CashImportPage onBack={() => navigate("/")} onDone={() => undefined} />;

  return <div className={`page-layout${isInvestment ? " investment-page" : ""}`}>
    <main className="app-shell" inert={modalOpen || undefined}>
      <aside className={`sidebar${mobileNavOpen ? " is-nav-open" : ""}`}>
        <div className="sidebar-head">
          <div className="mobile-menu-slot"><button ref={mobileNavToggle} className="menu-toggle" type="button" aria-expanded={mobileNavOpen} aria-controls="primary-navigation" aria-label={mobileNavOpen ? "关闭菜单" : "打开菜单"} onClick={() => { setMobileNavOpen((open) => !open); window.dispatchEvent(new CustomEvent("mobile-menu-toggled")); }}><span className="menu-icon" aria-hidden="true">{mobileNavOpen ? "×" : "☰"}</span><span className="menu-label">菜单</span></button></div>
          <strong>Finance Tracker</strong>
          <div className="mobile-account">{mobileAccount}</div>
        </div>
        <nav id="primary-navigation" aria-label="主要导航" onClick={closeMobileNav}>
          <div className="nav-group"><a className="nav-parent" aria-current={isCashLedger ? "page" : undefined} href="/" onClick={(event) => { event.preventDefault(); navigate("/"); }}>收支账本</a><div className="nav-subnav" aria-label="收支账本页面"><a className="subnav-link" aria-current={isCashCategory ? "page" : undefined} href="/cash-categories" onClick={(event) => { event.preventDefault(); navigate("/cash-categories"); }}>分类管理</a></div></div>
          <div className="nav-group"><a className="nav-parent" aria-current={isInvestmentHoldings ? "page" : undefined} href="/investment-holdings" onClick={(event) => { event.preventDefault(); navigate("/investment-holdings"); }}>投资账本</a><div className="nav-subnav" aria-label="投资账本页面"><a className="subnav-link" href="/investment-holdings" onClick={(event) => { event.preventDefault(); navigate("/investment-holdings"); }}>当前持仓</a><a className="subnav-link" aria-current={isInvestmentEvents ? "page" : undefined} href="/investment-events" onClick={(event) => { event.preventDefault(); navigate("/investment-events"); }}>投资事件</a></div></div>
          {onWorkspaceManagement && <a className="nav-parent" aria-current={workspaceManagementActive ? "page" : undefined} href="/workspace-management" onClick={(event) => { event.preventDefault(); onWorkspaceManagement(); }}>工作区管理</a>}
        </nav>
        {sidebarFooter}
      </aside>
      {workspaceManagementActive && workspacePage ? workspacePage : isInvestment ? <Suspense fallback={<section className="ledger" aria-label="投资账本"><div className="status-view" role="status"><p>正在打开账本…</p></div></section>}><InvestmentLedgerPage view={isInvestmentEvents ? "events" : "holdings"} onModalStateChange={onModalStateChange} /></Suspense> : isCashCategory ? <CashCategoriesPage embedded /> : <CashLedgerPage embedded onOpenImport={() => navigate("/cash-import")} onModalStateChange={onModalStateChange} />}
    </main>
  </div>;
}
