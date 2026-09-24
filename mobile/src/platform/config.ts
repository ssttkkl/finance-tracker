export const NATIVE_API_ORIGIN_OVERRIDE_STORAGE_KEY = "finance-tracker-api-origin-override";

export type ApiOriginStorage = {
  get(): Promise<string | null>;
  set(value: string): Promise<void>;
  clear(): Promise<void>;
};

let apiOriginOverride: string | null = null;

export function nativeApiOriginOverrideEnabled(): boolean {
  const value = process.env.EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED?.trim().toLowerCase();
  return value === "1" || value === "true";
}

export function normalizeNativeApiOrigin(value: string): string {
  const candidate = value.trim();
  if (!candidate) throw new Error("api_origin_invalid");
  let parsed: URL;
  try { parsed = new URL(candidate); } catch { throw new Error("api_origin_invalid"); }
  const localHttp = parsed.protocol === "http:" && parsed.hostname !== "" && parsed.port !== "" && process.env.NODE_ENV !== "production";
  const overrideHttp = parsed.protocol === "http:" && parsed.hostname !== "" && parsed.port !== "" && nativeApiOriginOverrideEnabled();
  const hostedHttps = parsed.protocol === "https:" && parsed.hostname !== "";
  const hasUnexpectedPath = parsed.pathname !== "/" && parsed.pathname !== "";
  if ((!localHttp && !overrideHttp && !hostedHttps) || parsed.username || parsed.password || hasUnexpectedPath || parsed.search || parsed.hash) {
    throw new Error("api_origin_invalid");
  }
  return candidate.replace(/\/$/, "");
}

export function nativeBuildApiOrigin(): string {
  const configured = process.env.EXPO_PUBLIC_FT_API_ORIGIN?.trim() ?? "";
  return configured ? normalizeNativeApiOrigin(configured) : "";
}

export function nativeApiOrigin(): string {
  const buildOrigin = nativeBuildApiOrigin();
  return nativeApiOriginOverrideEnabled() ? apiOriginOverride ?? buildOrigin : buildOrigin;
}

export function selectNativeApiOrigin(value: string): string {
  if (!nativeApiOriginOverrideEnabled()) return nativeBuildApiOrigin();
  apiOriginOverride = normalizeNativeApiOrigin(value);
  return apiOriginOverride;
}

export function currentNativeApiOriginOverride(): string | null {
  return nativeApiOriginOverrideEnabled() ? apiOriginOverride : null;
}

export function resetNativeApiOriginOverride(): void {
  apiOriginOverride = null;
}

export async function restoreNativeApiOriginOverride(storage: ApiOriginStorage): Promise<string> {
  resetNativeApiOriginOverride();
  const buildOrigin = nativeBuildApiOrigin();
  if (!nativeApiOriginOverrideEnabled()) return buildOrigin;
  let stored: string | null;
  try {
    stored = await storage.get();
  } catch {
    return buildOrigin;
  }
  if (!stored) return buildOrigin;
  try {
    return selectNativeApiOrigin(stored);
  } catch {
    try { await storage.clear(); } catch { /* 无效的历史值不应阻断本次启动。 */ }
    return buildOrigin;
  }
}

export async function persistNativeApiOriginOverride(value: string, storage: ApiOriginStorage): Promise<string> {
  if (!nativeApiOriginOverrideEnabled()) {
    resetNativeApiOriginOverride();
    return nativeBuildApiOrigin();
  }
  const selected = selectNativeApiOrigin(value);
  await storage.set(selected);
  return selected;
}

export async function clearNativeApiOriginOverride(storage: ApiOriginStorage): Promise<string> {
  resetNativeApiOriginOverride();
  const buildOrigin = nativeBuildApiOrigin();
  if (nativeApiOriginOverrideEnabled()) {
    try {
      await storage.clear();
    } catch {
      // 恢复默认地址优先更新当前会话，存储失败不应阻断表单操作。
    }
  }
  return buildOrigin;
}
