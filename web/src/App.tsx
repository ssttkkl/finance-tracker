import { lazy, Suspense, useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { CashLedgerPage } from "./pages/CashLedgerPage";
import { CashCategoriesPage } from "./pages/CashCategoriesPage";
import { CashImportPage } from "./pages/CashImportPage";
import { parseWorkspacePath, workspacePath } from "./routing";

function navigate(path: string, workspaceId?: string) {
  const target = workspaceId ? workspacePath(workspaceId, path) : path;
  window.history.pushState({}, "", target);
  window.dispatchEvent(new PopStateEvent("popstate"));
}

function normalizeRoute(pathname: string, hash: string, workspaceId?: string): string {
  const localPath = workspaceId ? parseWorkspacePath(pathname)?.path ?? pathname : pathname;
  if (localPath !== "/" || !hash) return localPath;
  if (hash === "#investment-events") return "/investment-events";
  if (hash === "#investment-holdings" || hash === "#investment-ledger") return "/investment-holdings";
  return localPath;
}

const InvestmentLedgerPage = lazy(async () => {
  const module = await import("./pages/InvestmentLedgerPage");
  return { default: module.InvestmentLedgerPage };
});

export function App({ workspaceId, sidebarFooter, mobileAccount, workspacePage, onWorkspaceManagement, onLedgerNavigation, workspaceManagementActive = false }: { workspaceId?: string; sidebarFooter?: ReactNode; mobileAccount?: ReactNode; workspacePage?: ReactNode; onWorkspaceManagement?: () => void; onLedgerNavigation?: () => void; workspaceManagementActive?: boolean } = {}) {
  const [path, setPath] = useState(() => normalizeRoute(window.location.pathname, window.location.hash, workspaceId));
  const [modalOpen, setModalOpen] = useState(false);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const mobileNavToggle = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const onPopState = () => setPath(normalizeRoute(window.location.pathname, window.location.hash, workspaceId));
    window.addEventListener("popstate", onPopState);
    const normalized = normalizeRoute(window.location.pathname, window.location.hash, workspaceId);
    const normalizedLocation = workspaceId ? workspacePath(workspaceId, normalized) : normalized;
    const currentLocation = `${window.location.pathname}${window.location.search}`;
    const targetLocation = `${normalizedLocation}${window.location.search}`;
    if (targetLocation !== currentLocation) {
      window.history.replaceState({}, "", targetLocation);
      setPath(normalized);
    }
    return () => window.removeEventListener("popstate", onPopState);
  }, [workspaceId]);

  const onModalStateChange = useCallback((open: boolean) => setModalOpen(open), []);
  const closeMobileNav = () => {
    if (!mobileNavOpen) return;
    setMobileNavOpen(false);
    requestAnimationFrame(() => mobileNavToggle.current?.focus());
  };
  const isInvestmentEvents = !workspaceManagementActive && path === "/investment-events";
  const isInvestment = !workspaceManagementActive && (isInvestmentEvents || path === "/investment-holdings");
  const isCashImport = !workspaceManagementActive && path === "/cash-import";
  const isCashCategory = !workspaceManagementActive && !isInvestment && !isCashImport && path === "/cash-categories";
  const isCashLedger = !workspaceManagementActive && !isInvestment && !isCashCategory && !isCashImport;
  const isInvestmentHoldings = isInvestment && !isInvestmentEvents;

  const route = (childPath: string) => workspaceId ? workspacePath(workspaceId, childPath) : childPath;

  return <div className={`page-layout${isInvestment ? " investment-page" : ""}`}>
    <main className="app-shell" inert={modalOpen || undefined}>
      <aside className={`sidebar${mobileNavOpen ? " is-nav-open" : ""}`}>
        <div className="sidebar-head">
          <div className="mobile-menu-slot"><button ref={mobileNavToggle} className="menu-toggle" type="button" aria-expanded={mobileNavOpen} aria-controls="primary-navigation" aria-label={mobileNavOpen ? "关闭菜单" : "打开菜单"} onClick={() => { setMobileNavOpen((open) => !open); window.dispatchEvent(new CustomEvent("mobile-menu-toggled")); }}><span className="menu-icon" aria-hidden="true">{mobileNavOpen ? "×" : "☰"}</span><span className="menu-label">菜单</span></button></div>
          <strong>Finance Tracker</strong>
          <div className="mobile-account">{mobileAccount}</div>
        </div>
        <nav id="primary-navigation" aria-label="主要导航" onClick={closeMobileNav}>
          <div className="nav-group"><a className="nav-parent" aria-current={isCashLedger ? "page" : undefined} href={route("/")} onClick={(event) => { event.preventDefault(); onLedgerNavigation?.(); navigate("/", workspaceId); }}>收支账本</a><div className="nav-subnav" aria-label="收支账本页面"><a className="subnav-link" aria-current={isCashCategory ? "page" : undefined} href={route("/cash-categories")} onClick={(event) => { event.preventDefault(); onLedgerNavigation?.(); navigate("/cash-categories", workspaceId); }}>分类管理</a><a className="subnav-link" aria-current={isCashImport ? "page" : undefined} href={route("/cash-import")} onClick={(event) => { event.preventDefault(); onLedgerNavigation?.(); navigate("/cash-import", workspaceId); }}>导入账单</a></div></div>
          <div className="nav-group"><a className="nav-parent" aria-current={isInvestmentHoldings ? "page" : undefined} href={route("/investment-holdings")} onClick={(event) => { event.preventDefault(); onLedgerNavigation?.(); navigate("/investment-holdings", workspaceId); }}>投资账本</a><div className="nav-subnav" aria-label="投资账本页面"><a className="subnav-link" href={route("/investment-holdings")} onClick={(event) => { event.preventDefault(); onLedgerNavigation?.(); navigate("/investment-holdings", workspaceId); }}>当前持仓</a><a className="subnav-link" aria-current={isInvestmentEvents ? "page" : undefined} href={route("/investment-events")} onClick={(event) => { event.preventDefault(); onLedgerNavigation?.(); navigate("/investment-events", workspaceId); }}>投资事件</a></div></div>
          {onWorkspaceManagement && <a className="nav-parent" aria-current={workspaceManagementActive ? "page" : undefined} href={route("/workspace-management")} onClick={(event) => { event.preventDefault(); onWorkspaceManagement(); }}>工作区管理</a>}
        </nav>
        {sidebarFooter}
      </aside>
      {workspaceManagementActive && workspacePage ? workspacePage : isInvestment ? <Suspense fallback={<section className="ledger" aria-label="投资账本"><div className="status-view" role="status"><p>正在打开账本…</p></div></section>}><InvestmentLedgerPage view={isInvestmentEvents ? "events" : "holdings"} onModalStateChange={onModalStateChange} /></Suspense> : isCashImport ? <CashImportPage onBack={() => navigate("/", workspaceId)} onDone={() => undefined} /> : isCashCategory ? <CashCategoriesPage embedded /> : <CashLedgerPage embedded onOpenImport={() => navigate("/cash-import", workspaceId)} onModalStateChange={onModalStateChange} />}
    </main>
  </div>;
}
