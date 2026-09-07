import { describe, expect, it } from "vitest";
import { ApiError, createApiClient, type FetchLike, type TokenStore } from "./index";

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

  it("normalizes server errors without leaking response bodies", async () => {
    const fetcher: FetchLike = async () => new Response(JSON.stringify({ error: { code: "workspace_forbidden", message: "secret detail" } }), { status: 403 });
    const client = createApiClient({ baseUrl: "https://api.example.com", fetch: fetcher, tokenStore: tokenStore() });

    await expect(client.session()).rejects.toMatchObject({ code: "workspace_forbidden", status: 403 });
    await expect(client.session()).rejects.not.toThrow("secret detail");
    expect(new ApiError("conflict", 409).code).toBe("conflict");
  });
});
