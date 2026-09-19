import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  clearNativeApiOriginOverride,
  currentNativeApiOriginOverride,
  nativeApiOrigin,
  nativeApiOriginOverrideEnabled,
  nativeBuildApiOrigin,
  normalizeNativeApiOrigin,
  persistNativeApiOriginOverride,
  resetNativeApiOriginOverride,
  restoreNativeApiOriginOverride,
  selectNativeApiOrigin,
  type ApiOriginStorage,
} from "./config";

function memoryStorage(initial: string | null = null): ApiOriginStorage & {
  value: string | null;
  clearCalls: number;
  events: string[];
} {
  let value = initial;
  const events: string[] = [];
  return {
    get: async () => {
      events.push("get");
      return value;
    },
    set: async (next) => {
      events.push(`set:${next}`);
      value = next;
    },
    clear: async () => {
      events.push("clear");
      value = null;
    },
    get value() { return value; },
    get clearCalls() { return events.filter((event) => event === "clear").length; },
    events,
  };
}

describe("Native API origin configuration", () => {
  beforeEach(() => {
    vi.stubEnv("EXPO_PUBLIC_FT_API_ORIGIN", "https://build.example.com/");
    vi.stubEnv("EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED", "false");
    vi.stubEnv("NODE_ENV", "development");
    resetNativeApiOriginOverride();
  });

  afterEach(() => {
    resetNativeApiOriginOverride();
    vi.unstubAllEnvs();
  });

  it("enables the override only for explicit true values", () => {
    for (const value of ["1", "true", " TRUE "]) {
      vi.stubEnv("EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED", value);
      expect(nativeApiOriginOverrideEnabled()).toBe(true);
    }
    for (const value of ["0", "false", "yes", ""]) {
      vi.stubEnv("EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED", value);
      expect(nativeApiOriginOverrideEnabled()).toBe(false);
    }
  });

  it("normalizes a valid origin and rejects non-origin input", () => {
    expect(normalizeNativeApiOrigin(" https://api.example.com/ ")).toBe("https://api.example.com");
    expect(normalizeNativeApiOrigin("http://127.0.0.1:8000/")).toBe("http://127.0.0.1:8000");
    for (const value of [
      "",
      "https://api.example.com/api",
      "https://api.example.com/?debug=1",
      "https://user:password@api.example.com",
      "http://api.example.com",
    ]) {
      expect(() => normalizeNativeApiOrigin(value)).toThrow("api_origin_invalid");
    }
  });

  it("keeps HTTP disabled in production even when a port is present", () => {
    vi.stubEnv("NODE_ENV", "production");
    expect(() => normalizeNativeApiOrigin("http://192.168.1.10:8000")).toThrow("api_origin_invalid");
    expect(normalizeNativeApiOrigin("https://api.example.com:8443")).toBe("https://api.example.com:8443");
  });

  it("uses the selected origin only while debug mode is enabled", () => {
    expect(nativeBuildApiOrigin()).toBe("https://build.example.com");
    expect(nativeApiOrigin()).toBe("https://build.example.com");

    vi.stubEnv("EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED", "1");
    expect(selectNativeApiOrigin("https://other.example.com/")).toBe("https://other.example.com");
    expect(currentNativeApiOriginOverride()).toBe("https://other.example.com");
    expect(nativeApiOrigin()).toBe("https://other.example.com");

    vi.stubEnv("EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED", "0");
    expect(nativeApiOrigin()).toBe("https://build.example.com");
  });

  it("restores a valid saved origin before using the client", async () => {
    vi.stubEnv("EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED", "true");
    const storage = memoryStorage("https://saved.example.com/");

    await expect(restoreNativeApiOriginOverride(storage)).resolves.toBe("https://saved.example.com");
    expect(nativeApiOrigin()).toBe("https://saved.example.com");
    expect(storage.events).toEqual(["get"]);
  });

  it("clears an invalid saved origin and falls back to the build address", async () => {
    vi.stubEnv("EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED", "1");
    const storage = memoryStorage("https://saved.example.com/path");

    await expect(restoreNativeApiOriginOverride(storage)).resolves.toBe("https://build.example.com");
    expect(storage.events).toEqual(["get", "clear"]);
    expect(nativeApiOrigin()).toBe("https://build.example.com");
  });

  it("falls back even when invalid-value cleanup cannot write", async () => {
    vi.stubEnv("EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED", "1");
    const storage: ApiOriginStorage = {
      get: async () => "https://saved.example.com/path",
      set: async () => undefined,
      clear: async () => { throw new Error("storage_unavailable"); },
    };

    await expect(restoreNativeApiOriginOverride(storage)).resolves.toBe("https://build.example.com");
    expect(nativeApiOrigin()).toBe("https://build.example.com");
  });

  it("falls back to the build address when storage cannot be read", async () => {
    vi.stubEnv("EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED", "1");
    const storage: ApiOriginStorage = {
      get: async () => { throw new Error("storage_unavailable"); },
      set: async () => undefined,
      clear: async () => undefined,
    };

    await expect(restoreNativeApiOriginOverride(storage)).resolves.toBe("https://build.example.com");
    expect(nativeApiOrigin()).toBe("https://build.example.com");
  });

  it("ignores saved storage when debug mode is disabled", async () => {
    const storage = memoryStorage("https://saved.example.com");

    await expect(restoreNativeApiOriginOverride(storage)).resolves.toBe("https://build.example.com");
    expect(storage.events).toEqual([]);
    expect(nativeApiOrigin()).toBe("https://build.example.com");
  });

  it("persists only the normalized selected origin and reset clears it", async () => {
    vi.stubEnv("EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED", "1");
    const storage = memoryStorage();

    await expect(persistNativeApiOriginOverride("https://selected.example.com/", storage)).resolves.toBe("https://selected.example.com");
    expect(storage.value).toBe("https://selected.example.com");
    expect(nativeApiOrigin()).toBe("https://selected.example.com");

    await expect(clearNativeApiOriginOverride(storage)).resolves.toBe("https://build.example.com");
    expect(storage.value).toBeNull();
    expect(nativeApiOrigin()).toBe("https://build.example.com");
  });

  it("resets the in-memory origin when clearing storage cannot write", async () => {
    vi.stubEnv("EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED", "1");
    const storage: ApiOriginStorage = {
      get: async () => null,
      set: async () => undefined,
      clear: async () => { throw new Error("storage_unavailable"); },
    };
    selectNativeApiOrigin("https://selected.example.com");

    await expect(clearNativeApiOriginOverride(storage)).resolves.toBe("https://build.example.com");
    expect(nativeApiOrigin()).toBe("https://build.example.com");
  });
});
