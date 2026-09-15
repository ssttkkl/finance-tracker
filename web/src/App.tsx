import { lazy, Suspense, useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { CashLedgerPage } from "./pages/CashLedgerPage";
import { CashCategoriesPage } from "./pages/CashCategoriesPage";
import { CashImportPage } from "./pages/CashImportPage";
import { parseWorkspacePath, workspacePath } from "./routing";
import { copy, semanticIds } from "@finance-tracker/presentation";
import { usePresentationLayout } from "./presentation";

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
  const presentationLayout = usePresentationLayout();
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
  const goTo = useCallback((childPath: string) => {
    navigate(childPath, workspaceId);
    setPath(childPath);
  }, [workspaceId]);

  return <div className={`page-layout${isInvestment ? " investment-page" : ""}`} data-presentation-layout={presentationLayout}>
    <main className="app-shell" inert={modalOpen || undefined}>
      <aside className={`sidebar${mobileNavOpen ? " is-nav-open" : ""}`}>
        <div className="sidebar-head">
          <div className="mobile-menu-slot"><button ref={mobileNavToggle} data-testid={semanticIds.navigationMenu} className="menu-toggle" type="button" aria-expanded={mobileNavOpen} aria-controls="primary-navigation" aria-label={mobileNavOpen ? copy.navigation.closeMenu : copy.navigation.openMenu} onClick={() => { setMobileNavOpen((open) => !open); window.dispatchEvent(new CustomEvent("mobile-menu-toggled")); }}><span className="menu-icon" aria-hidden="true">{mobileNavOpen ? "×" : "☰"}</span><span className="menu-label">{copy.navigation.menu}</span></button></div>
          <strong>{copy.product.name}</strong>
          <div className="mobile-account">{mobileAccount}</div>
        </div>
          <nav id="primary-navigation" aria-label={copy.navigation.main} onClick={closeMobileNav}>
          <div className="nav-group"><a data-testid={semanticIds.navigationLedger} className="nav-parent" aria-current={isCashLedger ? "page" : undefined} href={route("/")} onClick={(event) => { event.preventDefault(); onLedgerNavigation?.(); goTo("/"); }}>{copy.navigation.cashLedger}</a><div className="nav-subnav" aria-label={copy.navigation.cashLedgerGroup}><a className="subnav-link" aria-current={isCashCategory ? "page" : undefined} href={route("/cash-categories")} onClick={(event) => { event.preventDefault(); onLedgerNavigation?.(); goTo("/cash-categories"); }}>{copy.navigation.categories}</a><a data-testid={semanticIds.navigationImport} className="subnav-link" aria-current={isCashImport ? "page" : undefined} href={route("/cash-import")} onClick={(event) => { event.preventDefault(); onLedgerNavigation?.(); goTo("/cash-import"); }}>{copy.navigation.import}</a></div></div>
          <div className="nav-group"><a className="nav-parent" aria-current={isInvestmentHoldings ? "page" : undefined} href={route("/investment-holdings")} onClick={(event) => { event.preventDefault(); onLedgerNavigation?.(); goTo("/investment-holdings"); }}>{copy.navigation.investmentLedger}</a><div className="nav-subnav" aria-label={copy.navigation.investmentLedger}><a className="subnav-link" href={route("/investment-holdings")} onClick={(event) => { event.preventDefault(); onLedgerNavigation?.(); goTo("/investment-holdings"); }}>{copy.navigation.holdings}</a><a className="subnav-link" aria-current={isInvestmentEvents ? "page" : undefined} href={route("/investment-events")} onClick={(event) => { event.preventDefault(); onLedgerNavigation?.(); goTo("/investment-events"); }}>{copy.navigation.investmentEvents}</a></div></div>
          {onWorkspaceManagement && <a className="nav-parent" aria-current={workspaceManagementActive ? "page" : undefined} href={route("/workspace-management")} onClick={(event) => { event.preventDefault(); onWorkspaceManagement(); }}>{copy.navigation.workspaceManagement}</a>}
        </nav>
        {sidebarFooter}
      </aside>
      {workspaceManagementActive && workspacePage ? workspacePage : isInvestment ? <Suspense fallback={<section className="ledger" aria-label="投资账本"><div className="status-view" role="status"><p>正在打开账本…</p></div></section>}><InvestmentLedgerPage view={isInvestmentEvents ? "events" : "holdings"} onModalStateChange={onModalStateChange} /></Suspense> : isCashImport ? <CashImportPage onBack={() => goTo("/")} onDone={() => undefined} /> : isCashCategory ? <CashCategoriesPage embedded /> : <CashLedgerPage embedded onOpenImport={() => goTo("/cash-import")} onModalStateChange={onModalStateChange} />}
    </main>
  </div>;
}
