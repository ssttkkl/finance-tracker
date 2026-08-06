## 1. 思考

- [x] 1.1 确认宽屏覆盖规则误将账户设为省略号，且金额继承不换行导致跨币种内容覆盖操作列；记录 C 类最小修复边界与不升级理由。

## 2. 计划

- [x] 2.1 记录账户和金额局部换行、固定列比例与操作列独立空间的实现选择、替代方案及风险。

## 3. 任务拆分与一致性

- [x] 3.1 为长账户名称和跨币种金额编写会在旧样式下失败的宽屏浏览器回归断言。

## 4. 构建

- [x] 4.1 新增宽屏回归夹具和布局断言，确认旧样式失败。
- [x] 4.2 调整宽屏账户、金额与操作列样式，使账户和金额在列内换行且不覆盖「查看」。

## 5. 审查

- [x] 5.1 复核根因、修复边界、表头语义、键盘可达性和最终 diff；运行 Hallmark UI 审计并记录 finding。

## 6. 测试与 QA

- [x] 6.1 运行新增回归、受影响 Vitest、构建、Playwright、视觉快照、生产预览、`git diff --check`、OpenSpec 严格校验和 `openspec doctor`，记录命令、结果与残余风险。

## 7. 发布

- [x] 7.1 记录交付证据、回滚方式和未执行外部发布的原因。

## 8. 反思

- [x] 8.1 记录防复发测试及无需新增领域术语或规则的结论。

## 审查与验证记录

- 基线与当前 `HEAD`：`e119eecc7dada466a2340cd04c3043061c404c4f`。本次仅修改宽屏表格样式、浏览器回归、视觉基线和 OpenSpec 记录；未触及账本记录、金额数值、币种、API、持久化或财务计算，因此无需数据库双后端矩阵。
- 测试先行：临时隔离端口上的新回归在修复前失败，`td.account` 的计算 `text-overflow` 为 `ellipsis`，与验收要求的 `clip` 不符；修复后同一用例通过，并确认账户和金额的 `white-space` 为 `normal`、金额右边界不越过操作列。
- 范围与工程复核：固定表格布局和交易信息省略规则保持不变；账户、金额和操作列的例外规则只在宽屏断点生效。所有表头 `headers` 关联与既有「查看」按钮的名称、焦点和键盘操作保持不变。最终范围化 diff 无未使用生产代码、未声明依赖或数据合同变更。
- UI 审计：按 Hallmark audit 复核 `web/src/styles.css` 与 `web/src/components/CashTable.tsx`。现有 Workbench / Ledger Grid 标记与实际表格结构一致；未引入渐变、额外卡片、图标、两行可点击文本、页面横向滚动或文字重叠。结论：0 critical、0 major、0 minor。
- 受影响测试：`npm test` 通过，4 个测试文件共 37 条测试通过；临时隔离端口上的 `npx playwright test --config=playwright.local.config.ts` 通过，5 条功能端到端测试覆盖既有交易信息省略和新增长账户、跨币种金额、证据入口边界回归。
- 构建与视觉 QA：`npm run build` 通过；`npm run test:visual` 通过，10 条快照覆盖 1440、1024、768、390 px、详情、加载、错误和空状态。审阅后更新了受宽屏列宽调整影响的基线，未改变窄屏布局。
- 生产预览与静态检查：`FT_PREVIEW_WEB_PORT=5179 npm run test:preview` 通过；`openspec validate --all --strict` 通过（30 项）；`openspec doctor` 正常；`git diff --check` 通过。
- 发布准备：未执行提交、推送、创建 PR、部署或其他外部写入，因为未获得相应授权。回滚只需恢复宽屏账户、金额和操作列样式，无需数据回滚。
- 反思：浏览器级样式和几何边界断言可防止账户省略和金额覆盖操作列再次出现；本次未引入新领域概念或语义，`DOMAIN_GLOSSARY.md` 无需更新。
- OpenSpec 归档：delta 规格已同步到 `020-cash-ledger-browser-web` 主规格，并归档至 `openspec/changes/archive/2026-08-06-fix-cash-table-account-amount-wrapping/`。
