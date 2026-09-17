import { semanticIds, type SemanticId } from "./semantic-ids";
import type { LayoutClass } from "./responsive";

export type ScreenState = "loading" | "normal" | "empty" | "error" | "disabled" | "success";
export type ScreenId = "auth" | "workspace" | "ledger" | "record" | "import";

export type ScreenContract = {
  id: ScreenId;
  regions: readonly string[];
  actions: readonly SemanticId[];
  states: readonly ScreenState[];
  layouts: readonly LayoutClass[];
};

const allLayouts = ["compact", "regular", "wide"] as const;
const allStates = ["loading", "normal", "empty", "error", "disabled", "success"] as const;

export const screenContracts = {
  auth: {
    id: "auth",
    regions: ["access-card", "email-field", "password-field", "submit", "mode-toggle"],
    actions: [semanticIds.authSubmit, semanticIds.authToggleMode],
    states: allStates,
    layouts: allLayouts,
  },
  workspace: {
    id: "workspace",
    regions: ["header", "workspace-list", "workspace-create", "account-action"],
    actions: [semanticIds.workspaceSwitcher, semanticIds.workspaceCreate, semanticIds.workspaceLogout, semanticIds.workspaceRetry],
    states: allStates,
    layouts: allLayouts,
  },
  ledger: {
    id: "ledger",
    regions: ["header", "summary", "filters", "transaction-list", "primary-actions", "status"],
    actions: [semanticIds.ledgerAdd, semanticIds.ledgerImport, semanticIds.ledgerOpenRecord, semanticIds.ledgerRetry],
    states: allStates,
    layouts: allLayouts,
  },
  record: {
    id: "record",
    regions: ["header", "amount", "record-fields", "relations", "primary-actions", "status"],
    actions: [semanticIds.recordSave, semanticIds.recordCancel],
    states: allStates,
    layouts: allLayouts,
  },
  import: {
    id: "import",
    regions: ["header", "stepper", "file-selection", "account-mapping", "preview", "relations", "success", "status"],
    actions: [semanticIds.importChooseFile, semanticIds.importNext, semanticIds.importPrevious, semanticIds.importConfirm],
    states: allStates,
    layouts: allLayouts,
  },
} as const satisfies Record<ScreenId, ScreenContract>;

export function getScreenContract(id: ScreenId): ScreenContract {
  return screenContracts[id];
}
