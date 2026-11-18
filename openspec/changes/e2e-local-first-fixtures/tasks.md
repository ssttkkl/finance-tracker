## 1. 思考

- [ ] 1.1 盘点 14 个 E2E 文件的投影路由、行字段与选择器依赖
- [ ] 1.2 对照 `/ledger/snapshot` DTO 与本地派生，列出字段映射和不可一一对应的断言
- [ ] 1.3 记录分级依据：仅测试基础设施，B 类

## 2. 计划

- [ ] 2.1 设计 `snapshotFixture` 输入结构与默认快照
- [ ] 2.2 列出每个文件的迁移清单与需要更新的断言

## 3. 任务拆分

- [ ] 3.1 先写一条失败用例：旧断言在新快照下确实失败
- [x] 3.2 实现夹具构造器并让默认单条支出用例通过
  - 临时浏览器用例验证：默认快照在账本页显示，交易 1001、条目 2001。
- [ ] 3.3 逐文件迁移并运行

## 4. 构建

- [x] 4.1 实现 `snapshotFixture` 与 `installComposeApiFixtures` 的 `snapshot` 选项
  - `buildLedgerSnapshot` 已加入 `compose-fixtures.ts`；默认单条支出快照在 `/w/workspace-1/` 实际渲染为 `ledger.record-cash-entry:2001`。本地快照要求事务／条目 ID 为数字串，非数字 ID 会报 `unknown_error`。
- [ ] 4.2 迁移 `compose-cash-current-list.e2e.ts`
- [ ] 4.3 迁移 `compose-cash-detail*.e2e.ts`
- [ ] 4.4 迁移 `compose-cash-relations.e2e.ts`
- [ ] 4.5 迁移 `compose-cash-draft／entry／manual／member-issues／ui-audit.e2e.ts`
- [ ] 4.6 迁移 `compose-cash-import*.e2e.ts`
- [ ] 4.7 迁移 `compose-local-ledger／pages.e2e.ts`

## 5. 审查

- [ ] 5.1 范围 diff 复核：无产品代码、无测试专用分支
- [ ] 5.2 检查夹具可复用性与断言是否仍覆盖原场景

## 6. 测试与 QA

- [ ] 6.1 `npm run test:node`
- [ ] 6.2 全量 `npx playwright test` 本地通过
- [ ] 6.3 `openspec validate --all --strict`

## 7. 发布

- [ ] 7.1 更新功能地图／E2E 说明（如涉及）
- [ ] 7.2 归档本 change 并创建 PR
