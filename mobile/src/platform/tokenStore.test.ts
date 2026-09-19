import { beforeEach, describe, expect, it, vi } from "vitest";

const secureStore = vi.hoisted(() => ({ getItem: vi.fn() }));
vi.mock("expo-secure-store", () => secureStore);

import { readNativeTokenAtStartup } from "./tokenStore";

describe("native startup token read", () => {
  beforeEach(() => secureStore.getItem.mockReset());

  it("returns null without a saved token", () => {
    secureStore.getItem.mockReturnValue(null);
    expect(readNativeTokenAtStartup()).toBeNull();
  });

  it("returns a saved token synchronously", () => {
    secureStore.getItem.mockReturnValue("stored-token");
    expect(readNativeTokenAtStartup()).toBe("stored-token");
  });
});
