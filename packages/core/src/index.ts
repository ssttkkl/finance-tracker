import type {
  ApiErrorCode,
  CashPage,
  DecimalString,
  ImportCommitResult,
  ImportPreviewItem,
  ImportPreview,
  Role,
  Session,
  Workspace,
} from "@finance-tracker/contracts";

/** Calculate a content digest for selection-time de-duplication only. */
export function sha1Hex(input: Uint8Array): string {
  const bitLength = input.length * 8;
  const totalLength = Math.ceil((input.length + 9) / 64) * 64;
  const message = new Uint8Array(totalLength);
  message.set(input);
  message[input.length] = 0x80;
  const view = new DataView(message.buffer);
  view.setUint32(totalLength - 8, Math.floor(bitLength / 0x100000000));
  view.setUint32(totalLength - 4, bitLength >>> 0);

  let h0 = 0x67452301;
  let h1 = 0xefcdab89;
  let h2 = 0x98badcfe;
  let h3 = 0x10325476;
  let h4 = 0xc3d2e1f0;
  const words = new Uint32Array(80);
  for (let offset = 0; offset < totalLength; offset += 64) {
    for (let index = 0; index < 16; index += 1) {
      words[index] = view.getUint32(offset + index * 4);
    }
    for (let index = 16; index < 80; index += 1) {
      words[index] = ((words[index - 3] ^ words[index - 8] ^ words[index - 14] ^ words[index - 16]) << 1)
        | ((words[index - 3] ^ words[index - 8] ^ words[index - 14] ^ words[index - 16]) >>> 31);
    }
    let a = h0;
    let b = h1;
    let c = h2;
    let d = h3;
    let e = h4;
    for (let index = 0; index < 80; index += 1) {
      let functionValue: number;
      let constant: number;
      if (index < 20) {
        functionValue = (b & c) | (~b & d);
        constant = 0x5a827999;
      } else if (index < 40) {
        functionValue = b ^ c ^ d;
        constant = 0x6ed9eba1;
      } else if (index < 60) {
        functionValue = (b & c) | (b & d) | (c & d);
        constant = 0x8f1bbcdc;
      } else {
        functionValue = b ^ c ^ d;
        constant = 0xca62c1d6;
      }
      const rotated = (a << 5) | (a >>> 27);
      const next = (rotated + functionValue + e + constant + words[index]) >>> 0;
      e = d;
      d = c;
      c = (b << 30) | (b >>> 2);
      b = a;
      a = next;
    }
    h0 = (h0 + a) >>> 0;
    h1 = (h1 + b) >>> 0;
    h2 = (h2 + c) >>> 0;
    h3 = (h3 + d) >>> 0;
    h4 = (h4 + e) >>> 0;
  }
  return [h0, h1, h2, h3, h4]
    .map((word) => word.toString(16).padStart(8, "0"))
    .join("");
}

export type SessionStatus = "idle" | "loading" | "authenticated" | "signed_out" | "error";
export type SessionState = {
  status: SessionStatus;
  session: Session | null;
  errorCode: ApiErrorCode | null;
};

export type SessionEvent =
  | { type: "request_started" }
  | { type: "request_succeeded"; session: Session }
  | { type: "request_failed"; errorCode: ApiErrorCode }
  | { type: "signed_out" };

export const initialSessionState: SessionState = {
  status: "idle",
  session: null,
  errorCode: null,
};

export function sessionReducer(state: SessionState, event: SessionEvent): SessionState {
  switch (event.type) {
    case "request_started":
      return { status: "loading", session: state.session, errorCode: null };
    case "request_succeeded":
      return { status: "authenticated", session: event.session, errorCode: null };
    case "request_failed":
      return { status: "error", session: null, errorCode: event.errorCode };
    case "signed_out":
      return { status: "signed_out", session: null, errorCode: null };
  }
}

export const SESSION_RESTORE_ATTEMPTS = 3;
const SESSION_RESTORE_RETRY_DELAY_MS = 200;

export type SessionRestoreOptions = {
  isAuthenticationFailure: (cause: unknown) => boolean;
  attempts?: number;
  wait?: (retryNumber: number) => Promise<void>;
};

async function waitBeforeSessionRestoreRetry(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, SESSION_RESTORE_RETRY_DELAY_MS));
}

export async function restoreSession<T>(operation: () => Promise<T>, options: SessionRestoreOptions): Promise<T> {
  const attempts = Math.max(1, Math.floor(options.attempts ?? SESSION_RESTORE_ATTEMPTS));
  const wait = options.wait ?? waitBeforeSessionRestoreRetry;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      return await operation();
    } catch (cause) {
      if (options.isAuthenticationFailure(cause) || attempt === attempts) throw cause;
      await wait(attempt);
    }
  }
  throw new Error("session_restore_failed");
}

export function selectActiveWorkspace(session: Session | null): Workspace | null {
  if (!session?.active_workspace_id) return null;
  return session.workspaces.find(({ id }) => id === session.active_workspace_id) ?? null;
}

export function canWrite(role: Role | null | undefined): boolean {
  return role === "admin" || role === "editor";
}

export type CashRecordDraft = {
  accountName: string;
  amount: DecimalString;
  currency: string;
  occurredAt: string;
  recordType: string;
  recordSubtype: string;
  counterparty: string;
  counterpartyAccount: string;
  note: string;
  categoryId?: string | null;
};

const DECIMAL_PATTERN = /^[+-]?\d+(?:\.\d+)?$/;

export function isExactDecimalString(value: unknown): value is DecimalString {
  return typeof value === "string" && DECIMAL_PATTERN.test(value);
}

type AllocationPreviewItem = Pick<ImportPreviewItem, "amount" | "components">;
type ExactDecimalParts = { digits: bigint; scale: number };

export type AllocationBalance = {
  state: "complete" | "incomplete" | "invalid";
  difference: string;
  total: string;
};

function parseUnsignedDecimal(value: string): ExactDecimalParts | null {
  const normalized = value.trim();
  if (!/^\d+(?:\.\d+)?$/.test(normalized)) return null;
  const [integer, fraction = ""] = normalized.split(".");
  return { digits: BigInt(`${integer}${fraction}`), scale: fraction.length };
}

function unsignedAmount(value: string | null | undefined): string {
  return String(value ?? "").trim().replace(/^[+-]/, "");
}

function formatExactDecimal(digits: bigint, scale: number): string {
  const sign = digits < 0n ? "-" : "";
  const raw = (digits < 0n ? -digits : digits).toString().padStart(scale + 1, "0");
  if (scale === 0) return sign + raw;
  return sign + raw.slice(0, -scale) + "." + raw.slice(-scale);
}

export function allocationBalance(item: AllocationPreviewItem, values: string[]): AllocationBalance {
  const components = item.components ?? [];
  const target = parseUnsignedDecimal(unsignedAmount(item.amount));
  if (components.length < 2 || values.length !== components.length || !target) {
    return { state: "invalid", difference: "", total: "" };
  }

  let hasEmptyValue = false;
  const amounts = values.map((value) => {
    if (value.trim() === "") {
      hasEmptyValue = true;
      return { digits: 0n, scale: 0 };
    }
    return parseUnsignedDecimal(value);
  });
  if (amounts.some((value): value is null => value === null)) {
    return { state: "invalid", difference: "", total: "" };
  }

  const scale = Math.max(target.scale, ...amounts.map((value) => value?.scale ?? 0));
  const factor = (part: ExactDecimalParts) => part.digits * 10n ** BigInt(scale - part.scale);
  const difference = factor(target) - amounts.reduce((sum, value) => sum + factor(value!), 0n);
  return {
    state: difference === 0n && !hasEmptyValue ? "complete" : "incomplete",
    difference: formatExactDecimal(difference, scale),
    total: formatExactDecimal(factor(target), scale),
  };
}

export function allocationMatches(item: AllocationPreviewItem, values: string[]): boolean {
  return allocationBalance(item, values).state === "complete";
}

export function buildCashRecordPayload(draft: CashRecordDraft): Record<string, unknown> {
  if (!isExactDecimalString(draft.amount)) throw new Error("amount_invalid");
  if (!draft.accountName || !draft.currency || !draft.occurredAt || !draft.recordType || !draft.recordSubtype) {
    throw new Error("cash_record_incomplete");
  }
  return {
    account_name: draft.accountName,
    amount: draft.amount,
    currency: draft.currency,
    occurred_at: draft.occurredAt,
    record_type: draft.recordType,
    record_subtype: draft.recordSubtype,
    counterparty: draft.counterparty,
    counterparty_account: draft.counterpartyAccount,
    note: draft.note,
    category_id: draft.categoryId ?? null,
  };
}

export type ImportSessionStatus = "idle" | "file_selected" | "loading" | "ready" | "committing" | "success" | "error";
export type ImportSessionState = {
  status: ImportSessionStatus;
  fileName: string | null;
  importToken: string | null;
  preview: ImportPreview | null;
  result: ImportCommitResult | null;
  errorCode: ApiErrorCode | null;
};

export type ImportSessionEvent =
  | { type: "file_selected"; fileName: string }
  | { type: "file_cancelled" }
  | { type: "request_started" }
  | { type: "preview_ready"; preview: ImportPreview }
  | { type: "commit_started" }
  | { type: "commit_succeeded"; result: ImportCommitResult }
  | { type: "request_failed"; errorCode: ApiErrorCode; importToken?: string };

export function createImportSession(): ImportSessionState {
  return {
    status: "idle",
    fileName: null,
    importToken: null,
    preview: null,
    result: null,
    errorCode: null,
  };
}

export function importSessionReducer(state: ImportSessionState, event: ImportSessionEvent): ImportSessionState {
  switch (event.type) {
    case "file_selected":
      return { ...createImportSession(), status: "file_selected", fileName: event.fileName };
    case "file_cancelled":
      return createImportSession();
    case "request_started":
      return { ...state, status: "loading", errorCode: null };
    case "preview_ready":
      return { ...state, status: "ready", preview: event.preview, importToken: event.preview.import_token ?? null, errorCode: null };
    case "commit_started":
      return { ...state, status: "committing", errorCode: null };
    case "commit_succeeded":
      return { ...state, status: "success", result: event.result, errorCode: null };
    case "request_failed":
      return { ...state, status: "error", errorCode: event.errorCode, importToken: event.importToken ?? state.importToken };
  }
}

export type ImportCommitDraft = {
  importToken: string;
  source: string;
  currency?: string | null;
  previewDigest?: string | null;
  previewRelationDigest?: string | null;
  previewChannel?: string | null;
  relations: Record<string, unknown>[];
  mapping: Record<string, unknown>[];
};

export function buildImportCommitPayload(draft: ImportCommitDraft): Record<string, unknown> {
  if (!draft.importToken) throw new Error("import_session_not_found");
  return {
    import_token: draft.importToken,
    source: draft.source,
    currency: draft.currency ?? null,
    preview_digest: draft.previewDigest ?? null,
    preview_relation_digest: draft.previewRelationDigest ?? null,
    preview_channel: draft.previewChannel ?? null,
    relations: draft.relations,
    mapping: draft.mapping,
  };
}

export type LedgerLoadState =
  | { status: "idle" | "loading"; page: CashPage | null; errorCode: null }
  | { status: "ready"; page: CashPage; errorCode: null }
  | { status: "error"; page: CashPage | null; errorCode: ApiErrorCode };

export function createLedgerLoadState(): LedgerLoadState {
  return { status: "idle", page: null, errorCode: null };
}

export function ledgerLoadStarted(state: LedgerLoadState): LedgerLoadState {
  return { status: "loading", page: state.page, errorCode: null };
}

export function ledgerLoadSucceeded(page: CashPage): LedgerLoadState {
  return { status: "ready", page, errorCode: null };
}

export function ledgerLoadFailed(state: LedgerLoadState, errorCode: ApiErrorCode): LedgerLoadState {
  return { status: "error", page: state.page, errorCode };
}
