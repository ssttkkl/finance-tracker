import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const renderConfig = readFileSync(resolve(process.cwd(), "../render.yaml"), "utf8");

describe("Render SPA 配置", () => {
  it("将未知前端路径重写到应用入口", () => {
    expect(renderConfig).toContain("type: rewrite");
    expect(renderConfig).toContain("source: /*");
    expect(renderConfig).toContain("destination: /index.html");
  });
});
