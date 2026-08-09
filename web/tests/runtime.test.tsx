import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { CashLedgerPage } from "../src/pages/CashLedgerPage";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

describe("独立 Node 运行时", () => {
  it("缺少 VITE_FT_API_ORIGIN 时显示可操作配置错误", async () => {
    vi.stubEnv("VITE_FT_API_ORIGIN", "");
    vi.stubGlobal("fetch", vi.fn());

    render(<CashLedgerPage />);

    expect(await screen.findByText("账本暂不可用，请稍后重试。"))
      .toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("生产预览默认固定使用 API 允许的本机地址", () => {
    const packageJson = JSON.parse(readFileSync(resolve(process.cwd(), "package.json"), "utf8"));

    expect(packageJson.scripts.start).toContain("--host 127.0.0.1");
    expect(packageJson.scripts.start).toContain("--port 5173");
    expect(packageJson.scripts.start).toContain("--strictPort");
  });

  it("本地开发默认使用同源 API 代理", () => {
    const packageJson = JSON.parse(readFileSync(resolve(process.cwd(), "package.json"), "utf8"));
    const viteConfig = readFileSync(resolve(process.cwd(), "vite.config.ts"), "utf8");

    expect(packageJson.scripts.dev).toContain("VITE_FT_API_ORIGIN=${VITE_FT_API_ORIGIN:-http://127.0.0.1:5174}");
    expect(packageJson.scripts.dev).toContain("--host 127.0.0.1");
    expect(packageJson.scripts.dev).toContain("--port 5174");
    expect(packageJson.scripts.dev).toContain("--strictPort");
    expect(viteConfig).toContain('"/api": "http://127.0.0.1:8000"');
  });
});
