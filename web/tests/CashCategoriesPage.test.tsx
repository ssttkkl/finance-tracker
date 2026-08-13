import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CashCategoriesPage } from "../src/pages/CashCategoriesPage";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); });
beforeEach(() => vi.stubEnv("VITE_FT_API_ORIGIN", "http://127.0.0.1:8000"));

const directory = {
  revision: 2,
  items: [
    { id: "food", parent_id: null, name: "餐饮", description: null, path: [{ id: "food", name: "餐饮" }], depth: 1, sort_order: 1, revision: 1 },
    { id: "lunch", parent_id: "food", name: "工作餐", description: "工作日用餐", path: [{ id: "food", name: "餐饮" }, { id: "lunch", name: "工作餐" }], depth: 2, sort_order: 1, revision: 1 },
  ],
};

function json(value: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json" } }));
}

describe("CashCategoriesPage", () => {
  it("空目录也把新建一级分类作为分类列表最后一行", async () => {
    const fetch = vi.fn((input: string) => {
      if (input.endsWith("/cash-categories")) return json({ revision: 0, items: [] });
      throw new Error(`unexpected request: ${input}`);
    });
    vi.stubGlobal("fetch", fetch);

    render(<CashCategoriesPage />);

    const list = await screen.findByRole("tree", { name: "收支分类目录" });
    expect(list.lastElementChild).toHaveTextContent("新建一级分类");
    expect(screen.queryByText("还没有收支分类。" )).not.toBeInTheDocument();
    expect(screen.getByRole("treeitem")).toHaveTextContent("新建一级分类");
  });

  it("把新建一级分类放在分类列表最后，并在创建后仍保持最后", async () => {
    let current = directory;
    const fetch = vi.fn((input: string, init?: RequestInit) => {
      if (input.endsWith("/cash-categories") && (!init?.method || init.method === "GET")) return json(current);
      if (input.endsWith("/cash-categories") && init?.method === "POST") {
        const body = JSON.parse(String(init.body));
        const created = { id: "transport", parent_id: null, name: body.name, description: null, path: [{ id: "transport", name: body.name }], depth: 1, sort_order: 2, revision: 1 };
        current = { revision: 3, items: [...current.items, created] };
        return json(created, 201);
      }
      throw new Error(`unexpected request: ${input}`);
    });
    vi.stubGlobal("fetch", fetch);

    render(<CashCategoriesPage />);
    await screen.findByRole("heading", { name: "分类管理" });
    const list = screen.getByRole("tree", { name: "收支分类目录" });
    expect(screen.getByRole("heading", { name: "分类管理" }).parentElement?.parentElement?.querySelector("button")).toBeNull();
    expect(list.lastElementChild).toHaveTextContent("新建一级分类");

    fireEvent.click(screen.getByRole("button", { name: "新建一级分类" }));
    fireEvent.change(screen.getByLabelText("分类名称"), { target: { value: "交通" } });
    fireEvent.click(screen.getByRole("button", { name: "创建分类" }));

    await waitFor(() => expect(screen.getByRole("tree").lastElementChild).toHaveTextContent("新建一级分类"));
    expect(screen.getByRole("tree")).toHaveTextContent("交通");
    expect(fetch.mock.calls.some(([input, init]) => String(input).endsWith("/cash-categories") && init?.method === "POST")).toBe(true);
  });

  it("点击分类进入编辑，保存名称和描述", async () => {
    const fetch = vi.fn((input: string, init?: RequestInit) => {
      if (input.endsWith("/cash-categories") && (!init?.method || init.method === "GET")) return json(directory);
      if (input.endsWith("/cash-categories/food") && init?.method === "PATCH") return json({ ...directory.items[0], name: "餐食", description: "日常用餐" });
      throw new Error(`unexpected request: ${input}`);
    });
    vi.stubGlobal("fetch", fetch);
    render(<CashCategoriesPage />);
    await screen.findByText("餐饮", { selector: "strong" });
    const rootItem = screen.getAllByRole("treeitem").find((item) => item.querySelector("strong")?.textContent === "餐饮");
    expect(rootItem).toBeDefined();
    fireEvent.click(rootItem!);
    fireEvent.change(screen.getByLabelText("分类名称"), { target: { value: "餐食" } });
    fireEvent.change(screen.getByLabelText("分类描述"), { target: { value: "日常用餐" } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));
    await waitFor(() => expect(fetch.mock.calls.some(([input, init]) => String(input).endsWith("/cash-categories/food") && init?.method === "PATCH")).toBe(true));
  });
});
