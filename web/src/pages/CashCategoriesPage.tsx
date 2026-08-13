import { useEffect, useMemo, useState } from "react";
import {
  createCashCategory,
  deleteCashCategory,
  fetchCashCategories,
  fetchCashCategoryDeletionImpact,
  reorderCashCategory,
  updateCashCategory,
} from "../api/cashLedger";
import type { CashCategory } from "../api/types";
import { UiIcon } from "../components/UiIcon";

type Editor = { id: string | null; parentId: string | null; name: string; description: string };
type PageState = "loading" | "ready" | "empty" | "error";

const messages: Record<string, string> = {
  "category.duplicate_name": "同级分类名称已存在。",
  "category.depth_limit": "分类最多 5 级。",
  "category.invalid_name": "请输入分类名称。",
  "category.invalid_description": "描述不能超过 500 个字符。",
  "category.revision_conflict": "分类已更新，请刷新后重试。",
  "category.has_children": "请先处理子分类。",
};

function categoryPath(category: CashCategory): string {
  return category.path.map((item) => item.name).join(" / ");
}

function errorMessage(error: unknown): string {
  const code = error instanceof Error ? error.message : "";
  return messages[code] ?? "分类无法保存，请稍后重试。";
}

export function CashCategoriesPage({ embedded = false }: { embedded?: boolean } = {}) {
  const [items, setItems] = useState<CashCategory[]>([]);
  const [revision, setRevision] = useState(0);
  const [status, setStatus] = useState<PageState>("loading");
  const [error, setError] = useState<string>();
  const [editor, setEditor] = useState<Editor | null>(null);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string>();
  const [deleting, setDeleting] = useState<CashCategory | null>(null);
  const [impact, setImpact] = useState<{ revision: number; category_revision: number; direct_usage_count: number; child_count: number } | null>(null);
  const [deleteError, setDeleteError] = useState<string>();
  const [search, setSearch] = useState("");

  const load = () => {
    setStatus("loading");
    setError(undefined);
    fetchCashCategories().then((value) => {
      setItems(value.items);
      setRevision(value.revision);
      setStatus(value.items.length ? "ready" : "empty");
    }).catch((cause) => {
      setStatus("error");
      setError(errorMessage(cause));
    });
  };
  useEffect(load, []);

  const visibleItems = useMemo(() => {
    const term = search.trim().toLocaleLowerCase();
    return term ? items.filter((item) => categoryPath(item).toLocaleLowerCase().includes(term)) : items;
  }, [items, search]);
  const parentOptions = items.filter((item) => item.id !== editor?.id && !item.path.some((pathItem) => pathItem.id === editor?.id));

  const openEditor = (category: CashCategory) => {
    setFormError(undefined);
    setEditor({ id: category.id, parentId: category.parent_id, name: category.name, description: category.description ?? "" });
  };
  const openNew = (parentId: string | null) => {
    setFormError(undefined);
    setEditor({ id: null, parentId, name: "", description: "" });
  };
  const save = async () => {
    if (!editor || !editor.name.trim()) { setFormError("请输入分类名称。"); return; }
    setSaving(true); setFormError(undefined);
    try {
      const value = editor.id
        ? await updateCashCategory(editor.id, { name: editor.name, description: editor.description, parent_id: editor.parentId, expected_revision: revision })
        : await createCashCategory({ name: editor.name, description: editor.description, parent_id: editor.parentId, expected_revision: revision });
      setRevision(value.revision);
      load();
      setEditor(null);
    } catch (cause) {
      setFormError(errorMessage(cause));
    } finally { setSaving(false); }
  };
  const requestDelete = async (category: CashCategory) => {
    setDeleteError(undefined);
    try {
      const value = await fetchCashCategoryDeletionImpact(category.id);
      setDeleting(category);
      setImpact(value);
    } catch (cause) { setDeleteError(errorMessage(cause)); }
  };
  const confirmDelete = async () => {
    if (!deleting || !impact) return;
    setSaving(true); setDeleteError(undefined);
    try {
      const result = await deleteCashCategory(deleting.id, { expected_revision: impact.revision, expected_category_revision: impact.category_revision, expected_usage_count: impact.direct_usage_count, confirmed: true });
      setRevision(result.revision);
      setDeleting(null); setImpact(null);
      await load();
    } catch (cause) { setDeleteError(errorMessage(cause)); }
    finally { setSaving(false); }
  };
  const move = async (category: CashCategory, direction: "before" | "after") => {
    try {
      const value = await reorderCashCategory(category.id, direction, revision);
      setRevision(value.revision);
      await load();
    } catch (cause) { setError(errorMessage(cause)); }
  };

  const content = <section className="ledger category-workbench" id="cash-categories" aria-label="分类管理">
    <header className="page-header"><div><h1>分类管理</h1></div></header>
    {status === "error" ? <div className="status-view status-error" role="alert"><p>{error}</p><button type="button" onClick={load}>重试</button></div> : null}
    {status === "loading" ? <div className="status-view" role="status"><p>正在读取分类…</p></div> : null}
    {status === "empty" || status === "ready" || items.length ? <div className="category-layout"><section className="category-directory" aria-label="分类目录"><div className="category-toolbar"><h2>分类目录</h2><label className="category-search"><span className="sr-only">搜索分类</span><input type="search" placeholder="搜索分类" value={search} onChange={(event) => setSearch(event.target.value)} /></label></div><ul className="category-tree" role="tree" aria-label="收支分类目录">
      {visibleItems.map((category, index) => <li className="category-tree-item" role="treeitem" key={category.id} aria-level={category.depth} onClick={() => openEditor(category)}><span className="category-tree-copy"><strong>{category.name}</strong><small>{categoryPath(category)}</small></span><span className="category-tree-actions"><button type="button" aria-label={`在${category.name}下新增子分类`} onClick={(event) => { event.stopPropagation(); openNew(category.id); }}><UiIcon name="plus" /></button><button type="button" aria-label={`编辑${category.name}`} onClick={(event) => { event.stopPropagation(); openEditor(category); }}><UiIcon name="pencil" /></button>{index > 0 ? <button type="button" aria-label={`${category.name}上移`} onClick={(event) => { event.stopPropagation(); void move(category, "before"); }}>↑</button> : null}</span></li>)}
      <li className="category-tree-add" role="treeitem"><button type="button" onClick={() => openNew(null)}><UiIcon name="plus" /><span>新建一级分类</span></button></li>
    </ul></section><section className="category-editor" aria-label="分类编辑">{editor ? <><div className="category-editor-header"><h2>{editor.id ? "编辑分类" : editor.parentId ? "新增子分类" : "新增一级分类"}</h2><button type="button" className="icon-only-button icon-quiet-button" aria-label="关闭分类编辑" onClick={() => setEditor(null)}><UiIcon name="x" /></button></div><div className="category-fields"><label>分类名称<input aria-label="分类名称" maxLength={40} value={editor.name} onChange={(event) => setEditor({ ...editor, name: event.target.value })} /></label><label>上级分类<select aria-label="上级分类" value={editor.parentId ?? ""} onChange={(event) => setEditor({ ...editor, parentId: event.target.value || null })}><option value="">无（一级分类）</option>{parentOptions.map((item) => <option value={item.id} key={item.id}>{categoryPath(item)}</option>)}</select></label><label>分类描述<textarea aria-label="分类描述" maxLength={500} value={editor.description} onChange={(event) => setEditor({ ...editor, description: event.target.value })} /></label></div>{formError ? <p className="form-error" role="alert">{formError}</p> : null}<div className="drawer-actions"><button type="button" className="button-primary" disabled={saving} onClick={() => void save()}>{saving ? "保存中…" : editor.id ? "保存" : "创建分类"}</button>{editor.id ? <button type="button" className="button-danger" onClick={() => { const category = items.find((item) => item.id === editor.id); if (category) void requestDelete(category); }}>删除</button> : null}</div></> : <p className="category-editor-empty">选择一个分类。</p>}</section></div> : null}
    {deleting && impact ? <div className="confirm-layer" role="alertdialog" aria-label="删除分类确认"><div className="confirm-card"><h3>删除「{deleting.name}」？</h3>{impact.child_count ? <p>请先处理子分类。</p> : impact.direct_usage_count ? <p>有 {impact.direct_usage_count} 笔流水会改为无分类。</p> : <p>确认删除这个分类。</p>}{deleteError ? <p className="form-error">{deleteError}</p> : null}<div className="drawer-actions"><button type="button" onClick={() => { setDeleting(null); setImpact(null); }}>取消</button><button type="button" className="button-danger" disabled={saving || Boolean(impact.child_count)} onClick={() => void confirmDelete()}>删除</button></div></div></div> : null}
  </section>;
  return embedded ? content : <div className="page-layout"><main className="app-shell"><aside className="sidebar"><strong>Finance Tracker</strong><nav aria-label="主要导航"><a href="/">收支账本</a><a href="/cash-categories" aria-current="page">分类管理</a></nav></aside>{content}</main></div>;
}
