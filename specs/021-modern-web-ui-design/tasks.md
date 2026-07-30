# Tasks：现代化收支账本界面

**Input**：`/specs/021-modern-web-ui-design/` 中的设计产物

**Prerequisites**：`plan.md`、`spec.md`、`research.md`、`data-model.md`、`contracts/web-ui-compatibility.md`、`quickstart.md`

**测试**：所有可执行的展示、交互、可访问性和视觉快照变更均须测试先行。先让新增断言因当前行为缺失而失败，再实施最小展示层改造。021 不涉及持久化；不得修改既有 SQLite/PostgreSQL 合同或以 mock 替代其既有回归证据。

**组织方式**：任务按用户故事分组，确保每项用户故事可独立验证。除注明外，路径均相对于仓库根目录。

## 格式：`[ID] [P?] [Story] 描述`

- **[P]**：可与其他不同文件、无未完成依赖的任务并行。
- **[Story]**：任务所属用户故事（`US1`、`US2`、`US3`）。
- 所有任务均列出确切文件路径。

## Phase 1：基线与规格准备

**目的**：冻结 020 行为基线，并把 021 的范围、术语和验证边界写成可执行约束。

- [ ] T001 核对 `specs/020-cash-ledger-browser-web/spec.md`、`tasks.md`、`quickstart.md` 与当前实现及验证记录，将 020 收敛为明确提交；在本文件与 `specs/021-modern-web-ui-design/plan.md` 写入实际冻结 SHA，未形成冻结 SHA 前不得开始 021 实现。
- [ ] T002 [P] 使用 `$domain-glossary` 审校 `DOMAIN_GLOSSARY.md`、`specs/021-modern-web-ui-design/spec.md` 与本文件中的“收支账本”“投影条目”“证据详情”等术语；仅在概念新增、语义变化或歧义时更新 `DOMAIN_GLOSSARY.md`。
- [ ] T003 [P] 使用 `$chinese-documentation` 审校 `.specify/feature.json`、`specs/021-modern-web-ui-design/spec.md`、`plan.md` 与本文件的新增中文内容，运行 `git diff --check`。
- [ ] T004 在 `.specify/feature.json`、`specs/021-modern-web-ui-design/spec.md`、`plan.md` 与 `contracts/web-ui-compatibility.md` 复核范围一致：只现代化既有收支账本展示层，排除月度收支摘要、净现金流、跨币种计算、投资账本、API、数据模型和持久化。
- [ ] T005 在 `specs/021-modern-web-ui-design/plan.md`、`research.md` 与本文件确认允许路径仅为 `web/src/pages/`、`web/src/components/`、`web/src/styles.css`、可选 `web/tokens.css`、`web/tests/` 和 021 artifacts；确认禁止 `web/src/api/`、`src/ft/`、`migrations/`、依赖清单、020/022 artifacts。

**检查点**：020 已有可复现冻结提交；021 范围、术语和可修改路径一致，才可进入故事实现。

---

## Phase 2：共同回归与视觉测试基础

**目的**：在改动视觉前锁定 020 已有行为与固定去标识化快照输入。

**⚠️ CRITICAL**：本阶段完成前，不得改造任何页面或组件展示结构。

- [ ] T006 [P] 在 `web/tests/CashLedgerPage.test.tsx` 先补失败断言，锁定日期、账户、交易对方、分类、币种、最低金额、最高金额、经济类型和组成方式筛选的请求参数、筛选后回到首页、请求取消、迟到响应保护与投影版本更新行为。
- [ ] T007 [P] 在 `web/tests/CashTable.test.tsx` 和 `web/tests/accessibility.test.tsx` 先补失败断言，锁定八列顺序、备注位置、金额精确字符串和右对齐、非颜色唯一的收支含义、`caption`、`thead`、`th[scope="col"]` 与窄屏字段关联。
- [ ] T008 [P] 在 `web/tests/cash-ledger.e2e.ts` 先补失败断言，锁定 1440 × 900、1024 × 768、768 × 1024、390 × 844 的筛选、分页、状态、无横向溢出、键盘路径、44 px 触控目标与减少动效。
- [ ] T009 在 `web/tests/cash-ledger.visual.e2e.ts` 创建固定去标识化 fixture 的视觉快照测试，覆盖主列表、加载、空、错误、1440 × 900/1024 × 768 并列证据详情及 390 × 844 全屏证据详情；首次运行确认快照因未批准基线而失败。
- [ ] T010 在 `web/tests/cash-ledger.visual.e2e.ts` 和 `specs/021-modern-web-ui-design/quickstart.md` 定义快照人工批准流程：仅展示层差异可更新基线；API 请求、字段、文案含义、焦点或业务状态变化一律拒绝批准。

**检查点**：所有新回归/快照断言已先失败，并已记录其失败原因与人工快照批准规则。

---

## Phase 3：用户故事 1——高效筛选并浏览收支账本（优先级：P1）🎯 MVP

**目标**：以更清晰、紧凑、工具化的页面组织既有筛选、投影条目列表、状态与分页，同时完全保留既有读取和交互合同。

**独立测试**：在去标识化 fixture 中依次应用全部既有筛选条件、连续翻页并清除筛选；确认请求、结果、分页位置、八列、金额和键盘行为不变，且无虚构指标、图表或新增动作。

### 用户故事 1 的测试（必须先行）

- [ ] T011 [P] [US1] 在 `web/tests/CashLedgerPage.test.tsx` 扩展 T006 断言，覆盖所有既有筛选控件、筛选重置游标、加载/空/错误/投影不可用状态及不请求旧 `/cash-transactions` 的回归。
- [ ] T012 [P] [US1] 在 `web/tests/CashTable.test.tsx` 扩展 T007 断言，覆盖发生时间、账户、交易对方、备注、分类、金额、来源、证据入口的固定顺序，以及“组成方式”不作为列表列或关系摘要。
- [ ] T013 [P] [US1] 在 `web/tests/accessibility.test.tsx` 扩展 T007 断言，覆盖筛选、分页、状态、证据入口的可访问名称、可见焦点和窄屏表头语义。
- [ ] T014 [P] [US1] 在 `web/tests/cash-ledger.e2e.ts` 扩展 T008 断言，覆盖四个规定视口中的筛选、连续分页、键盘操作、横向溢出与长中文操作文字不换行。

### 用户故事 1 的实现

- [ ] T015 [US1] 在 `web/src/styles.css`（或新增 `web/tokens.css` 并由 `web/src/main.tsx` 现有入口加载）定义受限的 `--color-*`、`--font-*`、`--space-*`、`--radius-*`、`--rule-*`、`--dur-*`、`--ease-*`、`--z-*` 令牌，并在文件首行记录 Hallmark 戳记；全部颜色使用 OKLCH 令牌，不新增字体、UI 库或主题系统。
- [ ] T016 [US1] 在 `web/src/pages/CashLedgerPage.tsx` 重组既有工作台容器、标题和说明的展示标记，保留 filters、cursor、selected、请求序号、`AbortController`、投影版本处理和焦点恢复状态机，不新增读取、路由或状态服务。
- [ ] T017 [US1] 在 `web/src/components/CashFilters.tsx` 和 `web/src/styles.css` 重组既有筛选的视觉容器与响应式布局，保持字段、选项、`onChange` 和请求语义不变。
- [ ] T018 [US1] 在 `web/src/components/CashTable.tsx` 和 `web/src/styles.css` 重组宽屏表格与窄屏卡片的视觉层级，保留原生表格语义、八列、金额/时间等宽呈现、备注位置、来源和“查看”入口；组件不得发起请求。
- [ ] T019 [US1] 在 `web/src/components/Pagination.tsx`、`web/src/components/StatusView.tsx` 和 `web/src/styles.css` 统一分页与加载、空、错误、账本更新、投影不可用状态的视觉呈现，保持既有文字含义、重试和确认事件不变。
- [ ] T020 [US1] 在 `web/tests/CashLedgerPage.test.tsx`、`web/tests/CashTable.test.tsx`、`web/tests/accessibility.test.tsx` 和 `web/tests/cash-ledger.e2e.ts` 运行 T011～T014 的受影响测试，确认其在 T016～T019 后通过且未新增 API 请求。

**检查点**：用户故事 1 可独立完成筛选、浏览、分页和状态处理，且已证明行为与 020 基线一致。

---

## Phase 4：用户故事 2——打开证据详情完成线上核对（优先级：P1）

**目标**：增强既有证据详情的审阅层级，不删除证据、不改变脱敏或错误合同，并保持焦点管理和核对上下文。

**独立测试**：从任一投影条目打开详情，核对投影结果、主记录、来源行快照、成员流水、生效/未生效关系和退款时间线；通过关闭按钮及 `Escape` 关闭，确认焦点、筛选和分页上下文保持不变。

### 用户故事 2 的测试（必须先行）

- [ ] T021 [P] [US2] 在 `web/tests/CashLedgerPage.test.tsx` 先补失败断言，锁定证据详情字段、脱敏错误提示、加载/不完整/重试状态和 `projection.updated` 时关闭旧详情并刷新首页的行为。
- [ ] T022 [P] [US2] 在 `web/tests/accessibility.test.tsx` 先补失败断言，锁定关闭按钮初始焦点、`Escape`、焦点圈定、背景 `inert`、关闭后返回原“查看”入口及可访问名称。
- [ ] T023 [P] [US2] 在 `web/tests/cash-ledger.e2e.ts` 先补失败断言，锁定 1440 × 900 和 1024 × 768 并列详情、390 × 844 全屏详情、键盘关闭和背景不可交互。

### 用户故事 2 的实现

- [ ] T024 [US2] 在 `web/src/components/EvidenceDetail.tsx` 和 `web/src/styles.css` 以审阅分组、定义列表和响应式容器重构详情展示，保留所有字段、既有顺序语义、错误状态、焦点圈定、`Escape`、`inert` 和关闭后的焦点恢复；不得重命名业务术语或发起请求。
- [ ] T025 [US2] 在 `web/src/pages/CashLedgerPage.tsx` 和 `web/src/styles.css` 调整既有列表/详情容器，保证宽屏并列、窄屏全屏与原选择上下文保持一致，不改变详情请求编排。
- [ ] T026 [US2] 在 `web/tests/CashLedgerPage.test.tsx`、`web/tests/accessibility.test.tsx` 和 `web/tests/cash-ledger.e2e.ts` 运行 T021～T023 的受影响测试，确认详情字段、错误、焦点和响应式合同均通过。

**检查点**：用户故事 2 可独立完成证据核对，且关闭后仍保留原列表上下文与键盘位置。

---

## Phase 5：用户故事 3——在不同屏幕和输入方式下完成核对（优先级：P2）

**目标**：保证现代化展示在桌面、平板和手机上保持语义、可读性、键盘可用性和触控可操作性。

**独立测试**：在 1440 × 900、1024 × 768、768 × 1024、390 × 844 视口中，以鼠标、触摸和键盘完成筛选、分页、详情开关；确认没有横向溢出、遮挡、焦点丢失或过小的触控目标。

### 用户故事 3 的测试（必须先行）

- [ ] T027 [P] [US3] 在 `web/tests/accessibility.test.tsx` 先补失败断言，覆盖视觉隐藏表头不使用 `display: none`、字段关联、focus ring、非颜色唯一状态和 `prefers-reduced-motion: reduce`。
- [ ] T028 [P] [US3] 在 `web/tests/cash-ledger.e2e.ts` 先补失败断言，覆盖 768 × 1024 顶部导航/筛选重排、390 × 844 卡片/全屏详情，以及 320、375、414、768 px 的无横向溢出、触控目标和单行可点击文案。

### 用户故事 3 的实现

- [ ] T029 [US3] 在 `web/src/styles.css` 调整断点、`overflow-x: clip`、卡片与详情布局、`focus-visible`、触控高度和减少动效规则，确保不使用 `overflow-x: hidden`、`transition: all` 或以 `display: none` 隐藏表头。
- [ ] T030 [US3] 在 `web/src/components/CashTable.tsx`、`web/src/components/CashFilters.tsx`、`web/src/components/Pagination.tsx`、`web/src/components/EvidenceDetail.tsx` 和 `web/src/styles.css` 修正窄屏结构与操作目标，保持原生语义、既有 props 和事件合同。
- [ ] T031 [US3] 在 `web/tests/accessibility.test.tsx` 和 `web/tests/cash-ledger.e2e.ts` 运行 T027～T028 的测试，确认四个验收视口和 Hallmark 补充宽度均通过。

**检查点**：三个用户故事均可在规定视口及键盘/触摸输入下独立完成。

---

## Phase 6：视觉快照、Hallmark 与跨故事收敛

**目的**：批准视觉基线，验证设计约束，并完成所有项目门禁。

- [ ] T032 在 `web/tests/cash-ledger.visual.e2e.ts` 运行 T009 的视觉快照矩阵，人工审核 1440 × 900、1024 × 768、768 × 1024、390 × 844 下的主流程、加载、空、错误及详情；仅批准符合 `specs/021-modern-web-ui-design/contracts/web-ui-compatibility.md` 的展示层差异并提交快照基线。
- [ ] T033 [P] 在 `web/src/styles.css`、可选 `web/tokens.css` 与 `specs/021-modern-web-ui-design/quickstart.md` 执行 Hallmark 检查：modern-minimal / Workbench / Cobalt 适配、令牌纪律、对比度、320/375/414/768 px 响应式、焦点、动效与适用 slop-test；回写评分、失败修复及不适用项理由。
- [ ] T034 在 `web/` 运行 `npm test`、`npm run build`、`npm run test:e2e`、`npm run test:preview` 和 `npm run test:e2e -- cash-ledger.visual.e2e.ts`，将实际命令、结果及未运行项（含原因、风险、准确命令）写入 `specs/021-modern-web-ui-design/quickstart.md`。
- [ ] T035 在仓库根目录运行 `git diff --check` 和 `git diff --name-only <021-baseline-sha>...HEAD`，对照 `specs/021-modern-web-ui-design/quickstart.md` 审核禁止路径、未跟踪文件与依赖清单；发现越界时先 Flow-Back 更新 artifacts，不得以实现掩盖范围变更。
- [ ] T036 使用 `$speckit-converge` 对照 `specs/021-modern-web-ui-design/spec.md`、`plan.md`、本文件与实现收敛；将缺口回写对应 artifact，并按需要追加失败测试、实现和验证任务。
- [ ] T037 运行 gstack `review` 与 gstack `qa`，覆盖主流程、加载/空/错误状态、键盘、宽窄屏和视觉回归；阻断性结论须先修复并重新评审，涉及规格或方案缺口时先 Flow-Back 回写 `specs/021-modern-web-ui-design/`。

---

## 依赖与执行顺序

### Phase 依赖

- **Phase 1（基线与规格准备）**：立即开始，T001 是全部实现的硬前置。
- **Phase 2（共同回归基础）**：依赖 T001～T005；阻断全部故事实现。
- **用户故事 1（Phase 3，P1）**：依赖 Phase 2，是最小可交付增量。
- **用户故事 2（Phase 4，P1）**：依赖 Phase 2 和用户故事 1 的列表入口；证据内容与焦点验收独立执行。
- **用户故事 3（Phase 5，P2）**：依赖用户故事 1、2 的展示结构；负责跨视口与输入方式收敛。
- **Phase 6（视觉快照与收敛）**：依赖全部用户故事完成。

### 每个用户故事内部顺序

1. 先完成标为测试的任务，并运行确认其因缺少目标展示行为而失败。
2. 再按页面容器、筛选/列表、状态/分页、详情与响应式的依赖顺序进行最小实现。
3. 每项实现完成后立即运行受影响测试并勾选任务。
4. 若需要改动 API、后端、数据、领域规则、路由、依赖或用户可见业务语义，停止实施并 Flow-Back 到 021 artifacts。

### 并行机会

- T002、T003 可在 T001 的 020 收敛核对期间并行。
- T006～T008 可在 T005 后按不同测试文件并行准备；T009 依赖现有 fixture，但不依赖组件改造。
- 用户故事 1 的 T011～T014 可并行编写；T017 与 T019 可在 T016 后由不同实现者准备，但共享 `web/src/styles.css` 的最终编辑必须串行合并。
- 用户故事 2 的 T021～T023 可并行；T024 和 T025 均改动展示容器，必须顺序合并。
- 用户故事 3 的 T027～T028 可并行；T029 后再处理 T030，避免 `web/src/styles.css` 冲突。
- T032 与 T033 可并行审查，但 T034～T037 必须在快照和 Hallmark 结果收敛后依次完成。

---

## 并行示例：用户故事 1

```text
任务：“在 web/tests/CashLedgerPage.test.tsx 锁定筛选、游标和版本更新合同。”
任务：“在 web/tests/CashTable.test.tsx 锁定八列、备注和金额呈现合同。”
任务：“在 web/tests/accessibility.test.tsx 锁定可访问名称、焦点和表头语义。”
任务：“在 web/tests/cash-ledger.e2e.ts 锁定多视口筛选、分页和无溢出。”
```

---

## 实施策略

### MVP 优先

1. 完成 Phase 1，取得明确的 020 冻结 SHA。
2. 完成 Phase 2，先锁定既有行为与快照输入。
3. 完成用户故事 1，验证筛选、列表、状态与分页。
4. **停止并独立验证**用户故事 1；确认无新增 API 请求、用户工作流或业务能力。

### 增量交付

1. Phase 1 + 2 完成后，展示层边界与测试基础就绪。
2. 完成用户故事 1 并独立验证。
3. 完成用户故事 2 并独立验证证据详情与焦点。
4. 完成用户故事 3 并独立验证响应式与无障碍。
5. 最后批准视觉快照、完成 Hallmark、全量回归、converge、review 和 QA。

## 说明

- `[P]` 表示任务可并行准备，不表示可绕过依赖或在同一文件中并发编辑。
- 视觉快照使用去标识化 fixture；不得使用真实账本数据。
- 021 只交付本地实现与验证；除非用户明确授权，不提交、不推送、不创建 PR。
