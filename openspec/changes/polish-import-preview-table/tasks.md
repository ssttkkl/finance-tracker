## 1. 思考与计划

- [x] 1.1 完成 `grill-me` 澄清：范围包含月份行铺满、状态标签层级、零金额语义和摘要筛选，不改变导入合同。
- [x] 1.2 阅读 UI 设计规则、共享表格实现和既有导入 / 账本测试，确认这是共享组件级 B 类修复，无需新增原型。

## 2. 测试先行与实现

- [x] 2.1 增加月份行 `colSpan`、待新增 / 已存在状态和零金额的失败回归断言。
- [x] 2.2 修正共享表格月份列数、导入状态标签样式以及两个场景的零金额映射。
- [x] 2.3 保持导入摘要、状态判定、月汇总和确认请求不变。
- [x] 2.4 将导入摘要块改为可访问筛选按钮，覆盖状态筛选、恢复全部和无匹配状态。

## 3. 审查与验证

- [x] 3.1 运行受影响测试、全量 Web 测试、类型检查、构建和 `git diff --check`。
- [x] 3.2 使用真实浏览器检查桌面 / 小屏截图、摘要点击与键盘筛选、月份条宽度、状态标签和零金额展示；运行 Hallmark 等价人工审查并记录 finding。
- [x] 3.3 运行 OpenSpec 严格校验，记录命令、结果、当前 HEAD 和未解决风险。

## 4. 发布

- [ ] 4.1 提交并推送修复分支，创建目标为 `refactor/web` 的 PR；在合入前记录完整验证结果。

## 验证证据

- 基线 / 当前 HEAD：`origin/refactor/web` 与本地 `HEAD` 均为 `3711c07f1d865fb5bdf29388323074c61acc98ce`；修复分支从该基线创建，未修改 API、导入请求、状态判定或数据库。
- 测试：`cd web && npm test -- --run tests/CashImportPage.test.tsx` → 18/18 通过；`cd web && npm test` → 11 个测试文件 / 132 个测试通过；`npx tsc --noEmit`、`npm run build`、`git diff --check` → 通过。
- 浏览器 QA：`cd web && npx playwright test tests/cash-ledger.e2e.ts -g "独立导入处理页面扫描账户并完成四步确认|导入预览加载、空和错误状态保持统一表格语义|导入处理页面在四个目标宽度不产生页面级横向滚动"` → 3/3 通过；覆盖正常、空、错误状态，摘要点击筛选、键盘回车筛选、恢复全部，以及 320、375、390、414、768、1440 px 的无横向滚动和按钮不换行检查，并确认月份行、两种状态标签和零金额展示。截图：`/tmp/cash-import-preview-production-1440.png`、`/tmp/cash-import-preview-production-390.png`。
- 全量 E2E：`cd web && npm run test:e2e` → 33/35 通过；失败项均在本变更范围外：既有 `web/tests/cash-category-management.e2e.ts:423` 侧栏颜色元素数量断言（期望 7、实际 8），以及 `web/tests/workspace-entry.e2e.ts:69` 返回账本流程的两个请求被浏览器取消（`ERR_ABORTED`）。后者单独复跑 `cd web && npx playwright test tests/workspace-entry.e2e.ts -g "已有工作区主动创建时返回按钮回到账本"` → 1/1 通过，属于并发下的既有时序波动；本修复未修改相关页面或测试。
- Hallmark：运行时无独立 `audit` action，按等价人工清单审查最终共享组件、摘要筛选按钮、状态标签和两张截图；未发现 critical / major finding。
- OpenSpec：`openspec validate --all --strict` → 33/33 通过；`openspec doctor` → Root / OpenSpec root ok。
- 残余风险：状态标签颜色在深色系统主题下沿用现有 token，仍需在真实部署环境观察；全量 E2E 的侧栏数量基线失败未在本变更中处理。
