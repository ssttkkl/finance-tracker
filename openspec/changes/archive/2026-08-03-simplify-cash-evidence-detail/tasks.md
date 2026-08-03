## 1. 思考与计划

- [x] 1.1 阅读项目上下文、主规格、词表、现有详情抽屉与测试，确认本次为 B 类前端行为收敛，不改变投影或接口。
- [x] 1.2 更新 proposal、delta 规格与设计，明确关系投影、关联记录和审计数据的界面边界。

## 2. 任务拆分与一致性

- [x] 2.1 将列表、收支详情、关联记录、审计字段和无障碍焦点的验收条件映射到测试与实现任务。
- [x] 2.2 复核词表和规格使用「关系投影」「关联记录」「收支详情」等统一术语。

## 3. 测试先行

- [x] 3.1 先更新列表、详情、无障碍和端到端测试，使其要求单源无标签、关系仅显示「关系投影」、无关联空态和审计信息。
- [x] 3.2 运行受影响测试并确认实现尚未满足新断言。

## 4. 构建

- [x] 4.1 调整收支列表，仅为关系投影显示无成员数的关系标记。
- [x] 4.2 重构收支详情抽屉，条件展示关联记录并删除审计内容和相关状态。
- [x] 4.3 删除不再使用的样式和辅助逻辑，保持现有响应式与模态行为。

## 5. 审查

- [x] 5.1 完成产品/范围与工程复核，确认不改变投影计算、证据接口或持久化行为。
- [x] 5.2 按 Hallmark `audit` 技能流程审查最终列表和抽屉，修复 critical 与 major finding 后复审。
- [x] 5.3 完成范围化最终 diff 复核，检查术语、无障碍与用户既有改动边界。

## 6. 测试与 QA

- [x] 6.1 运行 Vitest、Web 构建、Playwright 交互与视觉测试、生产预览、`git diff --check`。
- [x] 6.2 运行 `openspec validate`、全量严格校验和 `openspec doctor`，记录实际证据和残余风险。

## 7. 发布准备

- [x] 7.1 记录交付与回滚证据；不执行提交、推送、部署或其他外部写入。

## 8. 反思

- [x] 8.1 记录以业务核对边界收敛详情抽屉的防回归结论。

## 验证与审查证据

- 基线：`8c18ed7`；验证 `HEAD`：`dd77c95`；执行时间：2026-08-03 19:04-19:12 CST。本次范围限于收支列表、详情抽屉、样式、测试、词表、规格和视觉快照；工作树中其他既有未提交改动未被回退或修改。
- 产品与工程复核：关系投影仍由服务端 `composition` 与 `member_count` 判定；组件不重新推断关系。`Evidence` API、投影计算、关系匹配、金额语义和持久化均未改动。完整追溯字段仍由接口保留，但收支详情不再呈现。
- Hallmark `audit` 技能复核范围：`web/src/components/CashTable.tsx`、`web/src/components/EvidenceDetail.tsx`、`web/src/styles.css` 与 390 px 详情快照。结果：0 critical、0 major、0 minor。复核了 Workbench / Ledger Grid 标记与页面结构一致性、关系标记的非交互层级、可见焦点、无横向溢出、无冗余空态和无底层审计内容。
- 验证命令与结果：`npm test -- --run CashTable.test.tsx CashLedgerPage.test.tsx accessibility.test.tsx`（32 项先失败 5 项，实施后通过）；`npm test -- --run`（34 项通过）；`npm run build`（通过）；`npx playwright test`（3 项通过；首次因同 worktree 遗留的 `5174` 服务占用端口未启动，清理该冗余服务后按原配置重跑通过）；`npm run test:visual -- --update-snapshots` 与 `npm run test:visual`（各 10 项通过，覆盖 1440、1024、768、390 px）；`FT_PREVIEW_WEB_PORT=5180 npm run test:preview`（1 项通过）；`openspec validate simplify-cash-evidence-detail --strict`（通过）；`openspec validate --all --strict`（26 项通过）；`openspec doctor`（健康）；`git diff --check`（通过）。
- 残余风险：未单独运行 Firefox 或 WebKit；Chromium 的交互、视觉和生产预览覆盖了本次抽屉与响应式范围。
- 回滚：还原本次列表、详情抽屉、样式、测试、词表、规格和视觉快照即可恢复此前呈现；无数据库、接口或外部状态需要回滚。未执行提交、推送、部署或其他外部写入。

## 反思

- 日常收支核对只显示会改变理解结果的关系信息；完整追溯数据留在接口边界，避免把实现模型作为普通使用门槛。针对单源、关系和抽屉无障碍的回归测试可防止该边界再次被打破。
