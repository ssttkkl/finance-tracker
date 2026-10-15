## 1. 思考

- [x] 1.1 阅读 `openspec/project-context.md`、`import/statement-import` 主规格、现有导入变更、关系规划代码及 Compose 预览流程；在新 SQLite 工作区复现 6 条支付宝流水有未填写组成金额，关系预览因 `decimal.InvalidOperation` 返回 HTTP 500。
- [x] 1.2 确认边界：待分摊流水继续显示在预览中；金额未完整前不生成关系候选、不确认导入；不得推测或改写金额。密码和分摊金额不写入聊天记录。

## 2. 计划

- [x] 2.1 更新 `import/statement-import` delta 与技术设计，明确 Web 手工分摊、关系预览和确认门禁；CLI 非交互跳过合同保持独立。
- [x] 2.2 Cross-platform Impact Check：Compose Web 和 Native 共用现金导入 API；服务端修复对两端生效。Compose 页面和共享 UI 代码不变；更新 F-05 实现证据，不新增页面或跨端 TODO。

## 3. 任务拆分与一致性

- [x] 3.1 将修复限制在关系预览输入筛选：完整预览行和 `requires_allocation` 状态不变，只有金额不完整的支付组成项暂不传入关系规划。
- [x] 3.2 新增 `test_relation_preview_skips_composite_rows_without_exact_allocations`，验证映射已完成但组成金额不完整的流水不会传入关系规划器；本次受影响测试回归通过。
- [x] 3.3 对照 proposal、delta、design、tasks、实现与主规格；关系候选只接收组件金额完整、有限且合计等于父流水金额的行。

## 4. 构建

- [x] 4.1 关系预览仅接收金额完整、有限且守恒的组成项；不完整流水仍保留为待分摊并跳过候选规划。历史真实账单曾返回 HTTP 500；新增守卫回归及修复后的真实 API 预览均通过。
- [x] 4.2 在新库对 8 个现金账单执行批量扫描与预览：HTTP 200，7,342 行、6 条待分摊、0 条未解析/不支持，关系候选 280 条。随后为 5 条找到并核实分摊金额；支付宝最终批次新增 1,366 条，剩余 1 条无金额凭据而单独排除。
- [x] 4.3 更新 `docs/feature-map.md` 的 F-05 后端实现证据。

## 5. 审查

- [x] 5.1 产品/范围复核：账户映射、来源行快照、父流水金额、关系规则和提交事务均未改动。
- [x] 5.2 工程复核：缺失/非法/非有限组件金额或组件金额合计不等于父流水时，不调用关系规划器；预览行仍保留，提交前的分摊门禁未改动。修复后真实批量预览返回成功。
- [x] 5.3 独立复核覆盖本 change、关系服务和新增回归用例，无新增 finding；真实账单及金额未提供给复核者。

## 6. 测试与 QA

- [x] 6.1 `openspec --version` → 1.7.0；本轮最终 `openspec validate --all --strict`、`openspec doctor` 与 `git diff --check` 结果见下方验证记录。六个受影响测试文件 317 passed、18 skipped；全量双后端 1,815 passed、2 skipped、3 failed（1 项 PostgreSQL 性能边界单次超时、2 项与本次无关的旧 Mobile CI 断言；性能项隔离重跑两后端均通过）。
- [x] 6.2 API 已用真实账单走通扫描、账户映射、预览和导入；本 change 未改 Web UI、交互、路由或文案，浏览器 QA 不适用。
- [x] 6.3 本 change 未改界面布局或状态，`COMPACT`、`REGULAR`、`WIDE` 视口 QA 不适用。
- [x] 6.4 本变更不改 schema/SQL；按仓库门禁执行了全量 PostgreSQL `_test` 契约回归。无导入持久化或完整性失败；一项独立性能门槛在全量运行中单次超出 1.44 ms，隔离重跑通过。

## 7. 发布

- [x] 7.1 后端连接 `~/.ft/finance-tracker-bills-import-20261014.db`。`~/.ft` 仅保留该正式 `.db` 文件；11 个旧库与 22 个 SQLite sidecar 已移入 macOS 废纸篓。后端 OpenAPI、Compose Web 首页返回 HTTP 200。
- [x] 7.2 `HEAD=47b713c`；现金流水 11,394 条，来源分别为支付宝 3,058、微信 3,331、工行信用卡 2,847、工行借记卡 1,206、建行借记卡 952；36 条既有 IBKR 投资事件未变。支付宝最终批次新增 1,366 条；1 条无分摊凭据组合流水未导入。未提交或推送。

## 8. 反思

- [x] 8.1 已将防复发规则落实为关系预览输入守卫：不完整支付组成项不得作为关系端点；金额完整后须重新生成关系计划。

### 验证记录

- 时间：2026-10-14 17:05 UTC（2026-10-15 01:05 Asia/Shanghai）。
- 对比基线：`HEAD=47b713c`；工作树包含本 change 的应用服务、功能地图与 OpenSpec 文档修改，另有既存未跟踪目录 `compose/qa/openspec/`。
- 验证记录：2026-10-15 05:19 Asia/Shanghai，`HEAD=47b713c`；`openspec --version` 1.7.0；`openspec validate --all --strict` 31 passed、0 failed；`openspec doctor` Root OK；`git diff --check` 通过。
- 全量 SQLite + PostgreSQL `_test` 回归：1,815 passed、2 skipped、3 failed、5 subtests passed；其中 PostgreSQL 关联编辑性能 p95 单次为 101.44 ms，隔离重跑同一性能测试 SQLite/PostgreSQL 均通过。另两项失败的 Mobile CI 测试要求当前 HEAD 已移除的工作流/旧版 Native 文档，未修改相关目录。
- 受影响六文件回归 317 passed、18 skipped（当次未配置 PostgreSQL）；正式库只读复核：SQLite 完整性 `ok`、外键错误 0。没有 UI 变化，浏览器与三类视口 QA 不适用。
