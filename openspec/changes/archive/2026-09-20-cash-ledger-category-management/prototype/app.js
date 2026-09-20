const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

const categoryData = {
  food: { name: "餐饮", parent: "", description: "日常吃饭与聚餐支出", usage: 8, children: 2, path: "餐饮" },
  "work-meal": { name: "工作餐", parent: "food", description: "工作日期间的早午晚餐，包含公司附近堂食与外卖。", usage: 12, children: 0, path: "餐饮 / 工作餐" },
  gathering: { name: "聚餐", parent: "food", description: "朋友与家庭聚会", usage: 6, children: 0, path: "餐饮 / 聚餐" },
  transport: { name: "交通", parent: "", description: "通勤与出行费用", usage: 2, children: 2, path: "交通" },
  "public-transit": { name: "公共交通", parent: "transport", description: "公交、地铁与城际交通", usage: 15, children: 0, path: "交通 / 公共交通" },
  taxi: { name: "打车", parent: "transport", description: "出租车与网约车", usage: 7, children: 0, path: "交通 / 打车" },
  home: { name: "居住", parent: "", description: "住房与日常居住成本", usage: 0, children: 1, path: "居住" },
  utilities: { name: "水电燃气", parent: "home", description: "家庭公用事业账单", usage: 9, children: 0, path: "居住 / 水电燃气" }
};

const categorySelections = {
  uncategorized: { name: "无分类", path: "—" },
  food: { name: "餐饮", path: "餐饮" },
  "work-meal": { name: "工作餐", path: "餐饮 / 工作餐" },
  gathering: { name: "聚餐", path: "餐饮 / 聚餐" },
  transport: { name: "交通", path: "交通" },
  taxi: { name: "打车", path: "交通 / 打车" },
  "public-transit": { name: "公共交通", path: "交通 / 公共交通" },
  utilities: { name: "水电燃气", path: "居住 / 水电燃气" }
};

let activePage = "categories";
let selectedCategoryId = "work-meal";
let creatingCategory = false;
let pickerMode = "batch";
let pickerTargetRow = null;
let detailRowId = null;
let detailOpener = null;

function setScenario(value) {
  $$(`.ready-content`).forEach((element) => element.classList.add("hidden"));
  $$(`[data-state-for]`).forEach((element) => element.classList.add("hidden"));
  if (value === "ready") {
    $(`[data-ready-for="${activePage}"]`)?.classList.remove("hidden");
  } else {
    $(`[data-state-for="${activePage}"][data-state="${value}"]`)?.classList.remove("hidden");
  }
  clearSelection();
}

function showPage(pageName, updateHash = true) {
  activePage = pageName;
  $("#category-inspector").classList.remove("is-open");
  $$(`[data-page]`).forEach((page) => { page.hidden = page.dataset.page !== pageName; });
  $$(`[data-page-link]`).forEach((link) => {
    if (link.dataset.pageLink === pageName) link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  });
  setScenario("ready");
  if (updateHash) history.replaceState(null, "", pageName === "categories" ? "#cash-categories" : "#cash-ledger");
  window.scrollTo({ top: 0, behavior: "auto" });
}

function getCategoryItem(id) {
  return $(`[data-category="${id}"]`);
}

function refreshTreeVisibility() {
  const query = $("#category-search").value.trim().toLocaleLowerCase("zh-CN");
  const items = $$(".tree-item");
  if (query) {
    const visibleIds = new Set();
    items.forEach((item) => {
      const data = categoryData[item.dataset.category];
      if (`${data.name} ${data.description} ${data.path}`.toLocaleLowerCase("zh-CN").includes(query)) {
        visibleIds.add(item.dataset.category);
        let parentId = item.dataset.parent;
        while (parentId) {
          visibleIds.add(parentId);
          parentId = getCategoryItem(parentId)?.dataset.parent || "";
        }
      }
    });
    items.forEach((item) => { item.hidden = !visibleIds.has(item.dataset.category); });
    return;
  }

  items.forEach((item) => {
    let parentId = item.dataset.parent;
    let hidden = false;
    while (parentId) {
      const parent = getCategoryItem(parentId);
      if (parent?.dataset.expanded === "false") hidden = true;
      parentId = parent?.dataset.parent || "";
    }
    item.hidden = hidden;
  });
}

function openInspector() {
  $("#category-inspector").classList.add("is-open");
}

function buildCategoryItem(id, name, parentId) {
  const parentItem = parentId ? getCategoryItem(parentId) : null;
  const depth = parentItem ? Number(parentItem.getAttribute("aria-level")) + 1 : 1;
  const item = document.createElement("li");
  item.className = "tree-item";
  item.setAttribute("role", "treeitem");
  item.setAttribute("aria-level", String(depth));
  item.setAttribute("aria-selected", "false");
  item.dataset.category = id;
  item.dataset.parent = parentId;
  item.style.setProperty("--depth", String(depth));

  const primary = document.createElement("div");
  primary.className = "tree-primary";
  const leafToggle = document.createElement("button");
  leafToggle.className = "tree-toggle leaf";
  leafToggle.type = "button";
  leafToggle.tabIndex = -1;
  leafToggle.setAttribute("aria-hidden", "true");
  const select = document.createElement("button");
  select.className = "tree-select";
  select.type = "button";
  const strong = document.createElement("strong");
  strong.textContent = name;
  select.append(strong);
  primary.append(leafToggle, select);

  const actions = document.createElement("span");
  actions.className = "tree-actions";
  actions.innerHTML = `<button type="button" data-add-category="${id}" aria-label="新增子分类"><svg class="icon" aria-hidden="true"><use href="#icon-plus"/></svg></button><button type="button" data-edit-category="${id}" aria-label="编辑分类"><svg class="icon" aria-hidden="true"><use href="#icon-edit"/></svg></button>`;
  item.append(primary, actions);

  if (parentItem) {
    const parentToggle = $(".tree-toggle", parentItem);
    parentToggle.classList.remove("leaf");
    parentToggle.removeAttribute("tabindex");
    parentToggle.removeAttribute("aria-hidden");
    parentToggle.setAttribute("aria-label", `折叠${categoryData[parentId].name}`);
    parentToggle.innerHTML = `<svg class="icon" aria-hidden="true"><use href="#icon-chevron"/></svg>`;
    parentItem.dataset.expanded = "true";
    parentItem.setAttribute("aria-expanded", "true");
    const allItems = $$(".tree-item");
    const parentIndex = allItems.indexOf(parentItem);
    let lastDescendant = parentItem;
    for (let index = parentIndex + 1; index < allItems.length; index += 1) {
      if (Number(allItems[index].getAttribute("aria-level")) <= depth - 1) break;
      lastDescendant = allItems[index];
    }
    lastDescendant.after(item);
  } else {
    $("#category-tree").insertBefore(item, $(".tree-add-root"));
  }
  return item;
}

function selectCategory(id, shouldOpen = true) {
  const data = categoryData[id];
  if (!data) return;
  creatingCategory = false;
  selectedCategoryId = id;
  $$(".tree-item").forEach((item) => item.setAttribute("aria-selected", String(item.dataset.category === id)));
  $("#inspector-mode").textContent = "编辑分类";
  $("#inspector-title").textContent = data.name;
  $("#category-name").value = data.name;
  $("#category-name").setAttribute("aria-invalid", "false");
  $("#category-name-error").classList.remove("is-visible");
  $("#category-name-error").setAttribute("aria-hidden", "true");
  $("#category-parent").value = data.parent;
  $("#category-description").value = data.description;
  $("#delete-category").hidden = false;
  $("#save-category").textContent = "保存修改";
  $("#save-category").disabled = false;
  if (data.children > 0) {
    $("#delete-category").disabled = true;
    $("#delete-category").title = "请先处理子分类";
  } else {
    $("#delete-category").disabled = false;
    $("#delete-category").removeAttribute("title");
  }
  if (shouldOpen) openInspector();
}

function startCategoryCreation(parentId) {
  creatingCategory = true;
  selectedCategoryId = null;
  $$(".tree-item").forEach((item) => item.setAttribute("aria-selected", "false"));
  const parentName = parentId && categoryData[parentId] ? categoryData[parentId].name : "";
  $("#inspector-mode").textContent = parentName ? "新增子分类" : "新增一级分类";
  $("#inspector-title").textContent = parentName ? `在“${parentName}”下新增` : "新增分类";
  $("#category-name").value = "";
  $("#category-name").setAttribute("aria-invalid", "false");
  $("#category-name-error").classList.remove("is-visible");
  $("#category-name-error").setAttribute("aria-hidden", "true");
  $("#category-parent").value = parentId && categoryData[parentId] ? parentId : "";
  $("#category-description").value = "";
  $("#delete-category").hidden = true;
  $("#save-category").textContent = "创建分类";
  $("#save-category").disabled = true;
  openInspector();
  $("#category-name").focus();
}

function updateSelectionBar() {
  const checkboxes = $$(".row-select");
  const selected = checkboxes.filter((checkbox) => checkbox.checked);
  const header = $("#select-loaded");
  header.checked = selected.length > 0 && selected.length === checkboxes.length;
  header.indeterminate = selected.length > 0 && selected.length < checkboxes.length;
  $("#selected-count").textContent = String(selected.length);
  $("#batch-bar").hidden = selected.length === 0;
}

function clearSelection() {
  $$(".row-select").forEach((checkbox) => { checkbox.checked = false; });
  if ($("#select-loaded")) {
    $("#select-loaded").checked = false;
    $("#select-loaded").indeterminate = false;
  }
  if ($("#batch-bar")) $("#batch-bar").hidden = true;
}

function rowCategory(rowId) {
  const row = $(`[data-ledger-row="${rowId}"]`);
  if (!row) return { name: "无分类", path: "—" };
  return { name: $(".row-category-name", row).textContent.trim(), path: $(".row-category-path", row).textContent.trim() };
}

function updateRowCategory(row, selection) {
  const name = $(".row-category-name", row);
  const path = $(".row-category-path", row);
  name.textContent = selection.name;
  path.textContent = selection.path;
  name.classList.toggle("muted", selection.name === "无分类");
}

function openCategoryPicker(mode, rowId = null) {
  pickerMode = mode;
  pickerTargetRow = rowId;
  const selectedCount = $$(".row-select").filter((checkbox) => checkbox.checked).length;
  if (mode === "batch") {
    $("#picker-title").textContent = `修改 ${selectedCount} 条分类`;
    $("#picker-description").textContent = "选择分类";
  } else {
    $("#picker-title").textContent = "修改分类";
    $("#picker-description").textContent = "选择分类";
    const current = rowCategory(rowId);
    const radio = $$("input[name='picker-category']").find((input) => categorySelections[input.value].name === current.name);
    if (radio) radio.checked = true;
  }
  $("#category-picker").showModal();
}

function openDetail(rowId, opener) {
  detailRowId = rowId;
  detailOpener = opener;
  const row = $(`[data-ledger-row="${rowId}"]`);
  const category = rowCategory(rowId);
  $("#detail-category-name").textContent = category.name;
  $("#detail-category-path").textContent = category.path;
  $("#detail-layer").hidden = false;
  $("[data-close-detail]:not(.drawer-backdrop)").focus();
  if (row) row.setAttribute("aria-current", "true");
}

function closeDetail() {
  if (detailRowId) $(`[data-ledger-row="${detailRowId}"]`)?.removeAttribute("aria-current");
  $("#detail-layer").hidden = true;
  detailOpener?.focus();
  detailOpener = null;
}

$$(`[data-page-link]`).forEach((link) => link.addEventListener("click", (event) => {
  event.preventDefault();
  showPage(link.dataset.pageLink);
}));

$$(`[data-retry]`).forEach((button) => button.addEventListener("click", () => {
  setScenario("ready");
}));

$("#category-tree").addEventListener("click", (event) => {
  const toggle = event.target.closest(".tree-toggle:not(.leaf)");
  if (toggle) {
    const item = toggle.closest(".tree-item");
    const expanded = item.dataset.expanded !== "false";
    item.dataset.expanded = String(!expanded);
    item.setAttribute("aria-expanded", String(!expanded));
    toggle.setAttribute("aria-label", `${expanded ? "展开" : "折叠"}${categoryData[item.dataset.category].name}`);
    refreshTreeVisibility();
    return;
  }
  const addButton = event.target.closest("[data-add-category]");
  if (addButton) {
    startCategoryCreation(addButton.dataset.addCategory);
    return;
  }
  const editButton = event.target.closest("[data-edit-category]");
  if (editButton) {
    selectCategory(editButton.dataset.editCategory);
    return;
  }
  const selectButton = event.target.closest(".tree-select");
  if (selectButton) selectCategory(selectButton.closest(".tree-item").dataset.category);
});

$$(`[data-add-category]`).filter((button) => !button.closest("#category-tree")).forEach((button) => {
  button.addEventListener("click", () => startCategoryCreation(button.dataset.addCategory === "root" ? "" : button.dataset.addCategory));
});

$("#category-search").addEventListener("input", refreshTreeVisibility);
$("#close-inspector").addEventListener("click", () => $("#category-inspector").classList.remove("is-open"));
$("#category-name").addEventListener("input", (event) => {
  const length = Array.from(event.target.value.trim()).length;
  $("#save-category").disabled = length === 0;
  event.target.setAttribute("aria-invalid", String(length === 0));
  $("#category-name-error").classList.toggle("is-visible", length === 0);
  $("#category-name-error").setAttribute("aria-hidden", String(length !== 0));
});

$("#category-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const name = $("#category-name").value.trim();
  if (!name) return;
  const save = $("#save-category");
  const original = save.textContent;
  const wasCreating = creatingCategory;
  save.disabled = true;
  save.textContent = "正在保存…";
  window.setTimeout(() => {
    if (!creatingCategory && selectedCategoryId) {
      const data = categoryData[selectedCategoryId];
      data.name = name;
      data.description = $("#category-description").value.trim();
      const item = getCategoryItem(selectedCategoryId);
      $(".tree-select strong", item).textContent = name;
      $("#inspector-title").textContent = name;
    } else {
      const parentId = $("#category-parent").value;
      const id = `custom-${Date.now()}`;
      const parentPath = parentId ? categoryData[parentId].path : "";
      categoryData[id] = {
        name,
        parent: parentId,
        description: $("#category-description").value.trim(),
        usage: 0,
        children: 0,
        path: parentPath ? `${parentPath} / ${name}` : name
      };
      if (parentId) categoryData[parentId].children += 1;
      buildCategoryItem(id, name, parentId);
      $("#category-count").textContent = String(Object.keys(categoryData).length);
      selectCategory(id, false);
    }
    save.textContent = wasCreating ? "已创建" : "已保存";
    window.setTimeout(() => {
      save.disabled = false;
      save.textContent = wasCreating ? "保存修改" : original;
    }, 720);
  }, 420);
});

function moveSelectedCategory(direction) {
  if (!selectedCategoryId) return;
  const item = getCategoryItem(selectedCategoryId);
  const allSiblings = $$(".tree-item").filter((candidate) => candidate.dataset.parent === item.dataset.parent);
  const index = allSiblings.indexOf(item);
  const target = direction < 0 ? allSiblings[index - 1] : allSiblings[index + 1];
  if (!target) return;
  const allItems = $$(".tree-item");
  const blockFor = (root) => {
    const rootIndex = allItems.indexOf(root);
    const rootLevel = Number(root.getAttribute("aria-level"));
    const block = [root];
    for (let cursor = rootIndex + 1; cursor < allItems.length; cursor += 1) {
      if (Number(allItems[cursor].getAttribute("aria-level")) <= rootLevel) break;
      block.push(allItems[cursor]);
    }
    return block;
  };
  const currentBlock = blockFor(item);
  if (direction < 0) {
    currentBlock.forEach((node) => item.parentNode.insertBefore(node, target));
  } else {
    const targetBlock = blockFor(target);
    const marker = targetBlock[targetBlock.length - 1].nextSibling;
    currentBlock.forEach((node) => item.parentNode.insertBefore(node, marker));
  }
  item.querySelector(".tree-select").focus();
}

$("#move-up").addEventListener("click", () => moveSelectedCategory(-1));
$("#move-down").addEventListener("click", () => moveSelectedCategory(1));

$("#delete-category").addEventListener("click", () => {
  if (!selectedCategoryId) return;
  const data = categoryData[selectedCategoryId];
  if (data.children > 0) return;
  $("#delete-title").textContent = `删除“${data.name}”？`;
  $("#delete-impact-count").textContent = String(data.usage);
  const impact = $(".delete-impact");
  impact.innerHTML = data.usage > 0
    ? `当前有 <strong id="delete-impact-count">${data.usage}</strong> 笔现金流水直接使用这个分类。删除后，这些流水会被改为“无分类”。`
    : "当前没有现金流水使用这个分类。确认后将只删除分类目录节点。";
  $("#delete-dialog").showModal();
});

$$(`[data-close-delete]`).forEach((button) => button.addEventListener("click", () => $("#delete-dialog").close()));
$("#confirm-delete").addEventListener("click", () => {
  const id = selectedCategoryId;
  const data = categoryData[id];
  const button = $("#confirm-delete");
  button.disabled = true;
  button.textContent = "正在删除…";
  window.setTimeout(() => {
    getCategoryItem(id)?.remove();
    $$(".row-category-name").forEach((name) => {
      if (name.textContent.trim() === data.name) updateRowCategory(name.closest("tr"), categorySelections.uncategorized);
    });
    delete categoryData[id];
    $("#category-count").textContent = String(Object.keys(categoryData).length);
    $("#delete-dialog").close();
    button.disabled = false;
    button.textContent = "删除并改为无分类";
    const parentId = data.parent;
    if (parentId && categoryData[parentId]) {
      categoryData[parentId].children -= 1;
      selectCategory(parentId);
    }
  }, 480);
});

$("#select-loaded").addEventListener("change", (event) => {
  $$(".row-select").forEach((checkbox) => { checkbox.checked = event.target.checked; });
  updateSelectionBar();
});
document.addEventListener("change", (event) => {
  if (event.target.matches(".row-select")) updateSelectionBar();
  if (event.target.matches("input[name='filter-category']")) {
    const hadSelection = $$(".row-select").some((checkbox) => checkbox.checked);
    $("#category-filter-label").textContent = event.target.value;
    $("#filter-summary").textContent = `2026 年 8 月 · 全部账户 · ${event.target.value}`;
    $("#category-filter").open = false;
    clearSelection();
    if (hadSelection) $("#category-filter").querySelector("summary").focus();
  }
});

$("#clear-selection").addEventListener("click", clearSelection);
$("#batch-category").addEventListener("click", () => openCategoryPicker("batch"));

document.addEventListener("click", (event) => {
  const categoryButton = event.target.closest("[data-single-category]");
  if (categoryButton) openCategoryPicker("single", categoryButton.dataset.singleCategory);
  const detailButton = event.target.closest("[data-open-detail]");
  if (detailButton) openDetail(detailButton.dataset.openDetail, detailButton);
  if (event.target.closest("[data-close-detail]")) closeDetail();
});

$("#detail-edit-category").addEventListener("click", () => openCategoryPicker("single", detailRowId));
$$(`[data-close-picker]`).forEach((button) => button.addEventListener("click", () => $("#category-picker").close()));
$("#apply-category").addEventListener("click", () => {
  const selected = $("input[name='picker-category']:checked");
  if (!selected) return;
  const choice = categorySelections[selected.value];
  const button = $("#apply-category");
  button.disabled = true;
  button.textContent = "正在修改…";
  window.setTimeout(() => {
    const rows = pickerMode === "batch"
      ? $$(".row-select").filter((checkbox) => checkbox.checked).map((checkbox) => checkbox.closest("tr"))
      : [$(`[data-ledger-row="${pickerTargetRow}"]`)];
    rows.filter(Boolean).forEach((row) => updateRowCategory(row, choice));
    if (detailRowId && rows.some((row) => row?.dataset.ledgerRow === detailRowId)) {
      $("#detail-category-name").textContent = choice.name;
      $("#detail-category-path").textContent = choice.path;
    }
    $("#category-picker").close();
    button.disabled = false;
    button.textContent = "确认修改";
    if (pickerMode === "batch") clearSelection();
  }, 520);
});

$("#load-more").addEventListener("click", (event) => {
  const button = event.currentTarget;
  button.disabled = true;
  button.textContent = "正在加载…";
  window.setTimeout(() => {
    button.textContent = "没有更多了";
    updateSelectionBar();
  }, 420);
});

document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && !$("#detail-layer").hidden) closeDetail();
});

const initialPage = location.hash === "#cash-ledger" ? "ledger" : "categories";
const initialState = new URLSearchParams(location.search).get("state") || "ready";
showPage(initialPage, false);
setScenario(initialState);
selectCategory("work-meal", false);
