import { describe, expect, it } from "vitest";
import { NATIVE_SESSION_TOKEN_STORAGE_KEY } from "./tokenKey";

describe("native session storage key", () => {
  it("uses the key character set accepted by SecureStore", () => {
    expect(NATIVE_SESSION_TOKEN_STORAGE_KEY).toMatch(/^[A-Za-z0-9._-]+$/);
  });
});
