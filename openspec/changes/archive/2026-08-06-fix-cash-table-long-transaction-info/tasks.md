## 1. 思考

- [x] 1.1 确认根因是宽屏表格由长交易信息的固有宽度驱动，记录 C 类修复边界与不升级理由。

## 2. 计划

- [x] 2.1 记录固定列布局、单行省略号和既有证据详情保留完整字段的实现选择及风险。

## 3. 任务拆分与一致性

- [x] 3.1 为超长连续交易信息编写会在旧实现失败的宽屏浏览器回归断言。

## 4. 构建

- [x] 4.1 为宽屏表格声明稳定列宽，并限制交易对方和备注在本列内以省略号显示。

## 5. 审查

- [x] 5.1 复核根因、修复边界、可访问性关联和最终 diff；运行 Hallmark UI 审计并记录 finding。

## 6. 测试与 QA

- [x] 6.1 运行新增回归、受影响 Vitest、构建、Playwright、视觉快照、`git diff --check`、OpenSpec 严格校验和 `openspec doctor`，记录命令、结果与残余风险。

## 7. 发布

- [x] 7.1 记录交付证据、回滚方式和未执行外部发布的原因。

## 8. 反思

- [x] 8.1 记录防复发测试及无需新增领域术语或规则的结论。

## 审查与验证记录

- 基线与当前 `HEAD`：`e119eec`。本次仅修改前端表格布局、浏览器回归测试、OpenSpec 变更记录和对应视觉基线；未触及数据、API、持久化或财务语义，因此无需数据库双后端矩阵。
- 测试先行：修复前执行临时隔离端口上的长交易信息回归，失败结果为 `scrollWidth=2322`、`clientWidth=1099`，证明超长连续文本会扩大表格；修复后同一用例通过。
- 受影响测试：`npm test` 通过，4 个测试文件共 35 条断言通过。
- 构建：`npm run build` 通过；`tsc -b` 与 Vite 生产构建均成功。
- 浏览器 QA：隔离端口上的 `npx playwright test --config=playwright.local.config.ts` 通过，4 条功能端到端测试覆盖连续加载、失败重试、规定视口、键盘路径与长交易信息回归。
- 视觉 QA：`npm run test:visual` 通过，10 条快照覆盖 1440、1024、768、390 px 及加载、错误、详情状态。列宽重新分配导致的 1440/1024 px 基线已人工复核后更新；窄屏卡片基线未变化。
- 生产预览：`FT_PREVIEW_WEB_PORT=5179 npm run test:preview` 通过，确认生产构建可读取自包含 API。
- 规格与静态检查：`openspec validate --all --strict` 通过（30 项）；`openspec doctor` 正常；`git diff --check` 通过。
- UI 审计：对 `web/src/components/CashTable.tsx` 与 `web/src/styles.css` 执行 Hallmark audit。固定列布局、截断和响应式断点符合现有 Workbench / Ledger Grid 标记；未发现渐变、嵌套卡片、无焦点状态、文本溢出或移动端横向页面滚动等问题。结论：0 critical、0 major、0 minor。
- 范围复核：长交易信息仅在宽屏表格内省略，完整字段继续由既有证据详情提供。残余风险是 821 至 1023 px 的既有表格容器内横向浏览模式仍保留，但不会产生页面级横向溢出，且本次变更未扩大其宽度。
- 发布准备：未执行提交、推送、创建 PR、部署或其他外部写入，因为尚未获得相应授权。回滚只需恢复 `CashTable` 列定义和宽屏布局规则；无需数据回滚。
- OpenSpec 归档：delta 规格已同步到 `openspec/specs/020-cash-ledger-browser-web/spec.md`，随后归档到 `openspec/changes/archive/2026-08-06-fix-cash-table-long-transaction-info/`。
- 反思：浏览器级 `scrollWidth` 与后续列边界断言已防止同类回归。此次未引入新领域概念或语义变化，`DOMAIN_GLOSSARY.md` 无需更新。
