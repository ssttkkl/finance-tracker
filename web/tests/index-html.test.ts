import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const indexHtml = readFileSync(resolve(process.cwd(), "index.html"), "utf8");

describe("生产 Web 入口 viewport", () => {
  it("禁止移动设备通过用户手势缩放页面", () => {
    const document = new DOMParser().parseFromString(indexHtml, "text/html");
    const viewport = document.querySelector('meta[name="viewport"]');

    expect(viewport?.getAttribute("content")).toBe(
      "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no",
    );
  });
});
