import { afterEach, describe, expect, it, vi } from "vitest";
import { login, logout, session, SESSION_TOKEN_STORAGE_KEY } from "../src/api/access";
import { openInvestmentPortfolioStream } from "../src/api/investmentLedger";

const sessionPayload = {
  user: { email: "member@example.com" },
  active_workspace_id: null,
  workspaces: [],
};

function json(value: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  }));
}

afterEach(() => {
  localStorage.clear();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe("Bearer 会话 API", () => {
  it("登录后持久化令牌，并在会话请求中使用 Authorization 而不是 Cookie", async () => {
    vi.stubEnv("VITE_FT_API_ORIGIN", "https://api.example.com");
    const fetch = vi.fn<(input: string, init?: RequestInit) => Promise<Response>>((input: string) => input.endsWith("/login")
      ? json({ ...sessionPayload, access_token: "token-1" })
      : json(sessionPayload));
    vi.stubGlobal("fetch", fetch);

    await login("member@example.com", "a secure password");
    expect(localStorage.getItem(SESSION_TOKEN_STORAGE_KEY)).toBe("token-1");
    await session();

    const [, init] = fetch.mock.calls[1];
    expect(new Headers(init?.headers).get("Authorization")).toBe("Bearer token-1");
    expect(init).not.toHaveProperty("credentials");
  });

  it("退出后清除持久化令牌", async () => {
    vi.stubEnv("VITE_FT_API_ORIGIN", "https://api.example.com");
    localStorage.setItem(SESSION_TOKEN_STORAGE_KEY, "token-1");
    const fetch = vi.fn<(input: string, init?: RequestInit) => Promise<Response>>(() => json({ ok: true }));
    vi.stubGlobal("fetch", fetch);

    await logout();

    expect(localStorage.getItem(SESSION_TOKEN_STORAGE_KEY)).toBeNull();
    expect(new Headers(fetch.mock.calls[0][1]?.headers).get("Authorization")).toBe("Bearer token-1");
  });

  it("持仓实时流使用带 Bearer 的 fetch，不把令牌放进 URL", () => {
    vi.stubEnv("VITE_FT_API_ORIGIN", "https://api.example.com");
    localStorage.setItem(SESSION_TOKEN_STORAGE_KEY, "token-1");
    const fetch = vi.fn<(input: string, init?: RequestInit) => Promise<Response>>(() => Promise.resolve(new Response(null, { status: 200 })));
    vi.stubGlobal("fetch", fetch);

    const stream = openInvestmentPortfolioStream(undefined, "24h", {
      onPortfolio: () => undefined,
      onRefreshError: () => undefined,
    });

    expect(fetch).toHaveBeenCalled();
    const [url, init] = fetch.mock.calls[0];
    expect(url).not.toContain("token-1");
    expect(new Headers(init?.headers).get("Authorization")).toBe("Bearer token-1");
    stream.close();
  });
});
