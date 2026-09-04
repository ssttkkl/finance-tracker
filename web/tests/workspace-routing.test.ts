import { describe, expect, it } from "vitest";
import { parseWorkspacePath, workspaceChildPath, workspacePath, workspaceUrl } from "../src/routing";

describe("工作区 URL 路由", () => {
  it("解析工作区根路径和子路径", () => {
    expect(parseWorkspacePath("/w/workspace-1/")).toEqual({ workspaceId: "workspace-1", path: "/" });
    expect(parseWorkspacePath("/w/workspace-1/cash-categories")).toEqual({ workspaceId: "workspace-1", path: "/cash-categories" });
    expect(parseWorkspacePath("/cash-categories")).toBeNull();
  });

  it("保留工作区 ID 并规范化子路径", () => {
    expect(workspacePath("workspace 1", "cash-categories")).toBe("/w/workspace%201/cash-categories");
    expect(workspaceChildPath("/w/workspace-1/cash-import")).toBe("/cash-import");
    expect(workspaceUrl("workspace-1", "/w/workspace-1/cash-import")).toBe("/w/workspace-1/cash-import");
  });
});
