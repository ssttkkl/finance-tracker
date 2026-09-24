import { describe, expect, it } from "vitest";
import type { FileSource } from "@finance-tracker/contracts";
import { ApiError, createApiClient, isAuthenticationError, type FetchLike, type TokenStore } from "./index";

function tokenStore(initial: string | null = null): TokenStore & { value: string | null } {
  return {
    value: initial,
    async get() { return this.value; },
    async set(value) { this.value = value; },
    async clear() { this.value = null; },
  };
}

describe("shared API client", () => {
  it("sends the bearer token and stores the returned session token", async () => {
    const requests: Array<{ url: string; init?: Parameters<FetchLike>[1] }> = [];
    const fetcher: FetchLike = async (url, init) => {
      requests.push({ url, init });
      return new Response(JSON.stringify({
        access_token: "session-2",
        user: { email: "owner@example.com" },
        active_workspace_id: null,
        workspaces: [],
      }), { status: 200, headers: { "Content-Type": "application/json" } });
    };
    const store = tokenStore("session-1");
    const client = createApiClient({ baseUrl: "https://api.example.com", fetch: fetcher, tokenStore: store });

    await client.login("owner@example.com", "secret");

    expect(requests[0]?.url).toBe("https://api.example.com/api/v1/auth/login");
    expect(requests[0]?.init?.headers?.Authorization).toBe("Bearer session-1");
    expect(store.value).toBe("session-2");
  });

  it("resolves a dynamic base URL for each request", async () => {
    const requests: string[] = [];
    const fetcher: FetchLike = async (url) => {
      requests.push(url);
      return new Response(JSON.stringify({ ok: true }), { status: 200 });
    };
    let origin = "https://build.example.com";
    const client = createApiClient({ baseUrl: () => origin, fetch: fetcher, tokenStore: tokenStore() });

    await client.request("/api/v1/health");
    origin = "https://debug.example.com";
    await client.request("/api/v1/health");

    expect(requests).toEqual([
      "https://build.example.com/api/v1/health",
      "https://debug.example.com/api/v1/health",
    ]);
  });

  it("normalizes server errors without leaking response bodies", async () => {
    const fetcher: FetchLike = async () => new Response(JSON.stringify({ error: { code: "workspace_forbidden", message: "secret detail" } }), { status: 403 });
    const client = createApiClient({ baseUrl: "https://api.example.com", fetch: fetcher, tokenStore: tokenStore() });

    await expect(client.session()).rejects.toMatchObject({ code: "workspace_forbidden", status: 403 });
    await expect(client.session()).rejects.not.toThrow("secret detail");
    expect(new ApiError("conflict", 409).code).toBe("conflict");
  });

  it("identifies only authentication failures as non-retryable session errors", () => {
    expect(isAuthenticationError(new ApiError("authentication_required", 401))).toBe(true);
    expect(isAuthenticationError(new ApiError("workspace_forbidden", 403))).toBe(false);
    expect(isAuthenticationError(new Error("request_failed"))).toBe(false);
  });

  it("uploads a batch once and sends later passwords without re-uploading files", async () => {
    const requests: Array<{ url: string; init?: Parameters<FetchLike>[1] }> = [];
    const fetcher: FetchLike = async (url, init) => {
      requests.push({ url, init });
      return new Response(JSON.stringify({ ready: true, files: [] }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    };
    const client = createApiClient({
      baseUrl: "https://api.example.com",
      fetch: fetcher,
      tokenStore: tokenStore("session-1"),
    });
    const files: FileSource[] = [
      { name: "alipay.csv", mediaType: "text/csv", size: 1, read: async () => new Uint8Array([97]) },
      { name: "wechat.csv", mediaType: "text/csv", size: 1, read: async () => new Uint8Array([98]) },
    ];

    await client.scanCashImportBatch(files, "CNY", { "1": "second-password" });
    const firstBody = JSON.parse(String(requests[0]?.init?.body));
    expect(requests[0]?.url).toBe("https://api.example.com/api/v1/cash-import/scan");
    expect(requests[0]?.init?.headers?.["X-FT-Statement-Passwords"]).toBe(JSON.stringify({ "1": "second-password" }));
    expect(firstBody).toEqual({
      files: [
        { filename: "alipay.csv", content_base64: "YQ==" },
        { filename: "wechat.csv", content_base64: "Yg==" },
      ],
      currency: "CNY",
    });

    await client.scanCashImportBatch(files, "CNY", { "0": "first-password" }, "batch-token");
    const secondBody = JSON.parse(String(requests[1]?.init?.body));
    expect(secondBody).toEqual({
      import_token: "batch-token",
      batch: true,
      source: "",
      currency: "CNY",
      preview_digest: null,
      preview_channel: null,
      relations: null,
      mapping: null,
    });
    expect(requests[1]?.init?.headers?.["X-FT-Statement-Passwords"]).toBe(JSON.stringify({ "0": "first-password" }));
  });
});
