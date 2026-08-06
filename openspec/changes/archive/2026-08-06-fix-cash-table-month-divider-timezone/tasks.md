## 1. 思考

- [x] 1.1 确认月份键直接截取 UTC 时间戳而日期展示使用 `Asia/Shanghai`，记录 C 类最小修复边界。

## 2. 计划

- [x] 2.1 记录从 `Intl.DateTimeFormat` 时区字段生成稳定月份键的选择、替代方案和风险。

## 3. 任务拆分与一致性

- [x] 3.1 编写 UTC 月末在上海跨入下月的失败组件回归测试。

## 4. 构建

- [x] 4.1 让月份分割行使用与发生时间展示一致的 `Asia/Shanghai` 月份键。

## 5. 审查

- [x] 5.1 复核时区边界、修复范围、既有月度汇总关联与最终 diff；运行 Hallmark UI 审计并记录 finding。

## 6. 测试与 QA

- [x] 6.1 运行新增回归、受影响 Vitest、构建、功能端到端、视觉快照、生产预览、`git diff --check`、OpenSpec 严格校验和 `openspec doctor`，记录结果与残余风险。

## 7. 发布

- [x] 7.1 记录交付证据、回滚方式和未执行外部发布的原因。

## 8. 反思

- [x] 8.1 记录跨时区月份边界的防复发测试及术语不变结论。

## 审查与验证记录

- 基线与当前 `HEAD`：`e119eec`。本次仅修正浏览器内派生的月份键和组件测试；未触及流水、投影、月度汇总响应、API、持久化或财务计算，因此无需数据库双后端矩阵。
- 测试先行：修复前执行 `npm test -- --run tests/CashTable.test.tsx`，新增场景失败。`2026-06-30T17:32:00Z` 显示为 `2026年7月1日 01:32`，旧代码却生成 `2026-06` 分割行；修复后同一场景通过。
- 受影响测试：`npm test` 通过，4 个测试文件共 36 条断言通过。
- 构建：`npm run build` 通过；`tsc -b` 与 Vite 生产构建均成功。
- 浏览器 QA：临时隔离端口上的 `npx playwright test --config=playwright.local.config.ts` 通过，4 条功能端到端测试覆盖加载、失败重试、规定视口、键盘路径及长交易信息回归。临时配置已删除。
- 视觉 QA：`npm run test:visual` 通过，10 条快照覆盖 1440、1024、768、390 px 及加载、错误、详情状态；本次无视觉基线变动。
- 生产预览：`FT_PREVIEW_WEB_PORT=5179 npm run test:preview` 通过。
- 规格与静态检查：`openspec validate --all --strict` 通过（30 项）；`openspec doctor` 正常；`git diff --check` 通过。
- UI 审计：对 `web/src/components/CashTable.tsx` 执行 Hallmark audit。时区月份键不改变现有 Workbench 视觉结构、响应式行为、焦点样式或文本布局。结论：0 critical、0 major、0 minor。
- 范围复核：仅用与显示日期一致的 `Asia/Shanghai` 月份生成分割行；现有服务端月度汇总仍按 API 返回的月份匹配。残余风险仅为极端运行环境缺失 IANA 时区数据，但项目既有日期展示已依赖同一标准平台能力。
- 发布准备：未执行提交、推送、创建 PR、部署或其他外部写入，因为尚未获得相应授权。回滚只需恢复月份键计算，无需数据回滚。
- 反思：组件测试已覆盖 UTC 月末在上海跨入下月的情形。此次未引入新领域概念或语义变化，`DOMAIN_GLOSSARY.md` 无需更新。

## 归档记录

- 2026-08-06：已将 delta 规格同步到 `020-cash-ledger-browser-web` 主规格，并归档至 `openspec/changes/archive/2026-08-06-fix-cash-table-month-divider-timezone/`。
