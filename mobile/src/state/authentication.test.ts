import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  currentNativeApiOriginOverride,
  resetNativeApiOriginOverride,
  selectNativeApiOrigin,
  type ApiOriginStorage,
} from "../platform/config";
import { authenticateWithNativeApiOrigin } from "./authentication";

function memoryStorage(): ApiOriginStorage & { events: string[] } {
  const events: string[] = [];
  return {
    get: async () => null,
    set: async (value) => { events.push(`set:${value}`); },
    clear: async () => { events.push("clear"); },
    events,
  };
}

describe("Native authentication origin persistence", () => {
  beforeEach(() => {
    vi.stubEnv("EXPO_PUBLIC_FT_API_ORIGIN", "https://build.example.com");
    vi.stubEnv("EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED", "1");
    vi.stubEnv("NODE_ENV", "development");
    resetNativeApiOriginOverride();
  });

  afterEach(() => {
    resetNativeApiOriginOverride();
    vi.unstubAllEnvs();
  });

  it("does not persist an origin when authentication fails", async () => {
    selectNativeApiOrigin("https://retry.example.com");
    const storage = memoryStorage();
    const operation = vi.fn(async () => { throw new Error("invalid_credentials"); });

    await expect(authenticateWithNativeApiOrigin(operation, storage)).rejects.toThrow("invalid_credentials");
    expect(operation).toHaveBeenCalledOnce();
    expect(storage.events).toEqual([]);
    expect(currentNativeApiOriginOverride()).toBe("https://retry.example.com");
  });

  it("persists the selected origin only after authentication succeeds", async () => {
    selectNativeApiOrigin("https://success.example.com/");
    const storage = memoryStorage();

    await expect(authenticateWithNativeApiOrigin(async () => "session", storage)).resolves.toBe("session");
    expect(storage.events).toEqual(["set:https://success.example.com"]);
  });
});
