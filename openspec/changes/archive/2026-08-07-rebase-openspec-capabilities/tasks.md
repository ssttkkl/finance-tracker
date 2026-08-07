## 1. 思考

- [x] 1.1 盘点主规格、active changes、归档、代码与测试，记录迁移基线、已知矛盾和不修改运行时行为的边界。
- [x] 1.2 逐项判定旧 requirement 的去向：当前行为、已被取代、未来需求、实现细节、一次性迁移步骤或占位内容。

## 2. 计划

- [x] 2.1 完成 `proposal.md`、13 份新 capability delta spec、1 份既有 capability 修改 delta 与 `design.md`，覆盖目标、非目标、映射、风险和回滚。
- [x] 2.2 明确 active change 的处置：已交付变更先同步并归档，未实现的投资流水浏览器继续保留为 active change。

## 3. 任务拆分与一致性

- [x] 3.1 严格校验本 change，修正 requirement 规范性问题，并复核 proposal、specs、design、tasks 的一致性。
- [x] 3.2 在迁移前运行结构守卫并确认其因编号 capability、占位场景及过早写入的 022 主规格而失败，固定迁移验收条件。

## 4. 构建

- [x] 4.1 核验并归档已经完成的 `local-timezone-data-boundary`、`match-transfers-by-counterparty-account` 与 `cash-ledger-filter-hierarchy`。
- [x] 4.2 完成 `preserve-complete-statement-source-rows` 的剩余验证，满足门禁后同步并归档。
- [x] 4.3 将本 change 的 delta specs 同步为新的 capability 主规格，保留三个既有稳定 capability。
- [x] 4.4 精确移除 24 个编号主规格和临时的时间边界主规格，更新 `openspec/MIGRATION.md` 的事实源清单与旧新映射。
- [x] 4.5 将未实现的 `022-investment-ledger-browser-web` 重命名为 `investment-ledger-browser`，使其 delta spec 仅描述尚未进入当前事实源的未来行为。

## 5. 审查

- [x] 5.1 完成产品与范围复核：用户价值、当前行为边界、非目标和验收标准无偏移。
- [x] 5.2 完成工程复核：capability 边界、数据与时间语义、兼容、回滚和 active change 关系完整。
- [x] 5.3 按中文文档规范复核新增与修改文本，确认术语与 `DOMAIN_GLOSSARY.md` 一致。
- [x] 5.4 完成最终 diff 复核，按严重级别记录 finding、处理结论和残余风险。

## 6. 测试与 QA

- [x] 6.1 运行 `openspec validate --all --strict` 与 `openspec doctor --json`，确认全部主规格、active changes 和归档可解析。
- [x] 6.2 运行结构与语义守卫：无编号 capability、无占位场景、无泛化 requirement 名称、022 未进入主规格、主规格清单与映射一致。
- [x] 6.3 运行受影响测试、完整 Python 回归、Web 测试与构建、`git diff --check`，记录命令、结果、基线与当前 `HEAD`。
- [x] 6.4 记录 UI、双后端、性能和安全专项检查的不适用理由、残余风险与补跑条件。

## 7. 发布准备

- [x] 7.1 汇总交付证据、回滚方法、观察项和未解决风险；未经用户授权不提交、不推送、不创建 PR。

## 8. 反思

- [x] 8.1 将 capability 命名、主规格写入时机和占位内容禁入规则沉淀到迁移文档，防止再次把 feature/change 当作当前事实源。

## 执行证据

- 基线：`HEAD=c66a66f747ca4d4ddcd7f92af78f046b86483029`；27 个主规格中有 24 个编号 capability；5 个 active change；24 份主规格共命中 202 处迁移占位文案；`022-investment-ledger-browser-web` 同时存在于未实施 change 和主规格。
- 需求处置：`design.md` 已逐项映射 24 个旧编号主规格，并区分当前行为、被取代行为、未来行为、内部实现、一次性迁移和占位内容。
- 规划校验：首次严格校验发现 4 条 requirement 缺少规范性 `MUST`，修正后 `openspec validate rebase-openspec-capabilities --strict` 通过。
- 迁移前守卫：编号目录检查和 022 future-only 检查按预期失败；占位检查确认 24 份文件、202 处命中。迁移后的验收值均为零或不存在。
- active change 收口：逐 delta requirement 与 scenario 比较后补齐缺口，保留后续更严格合同；`openspec validate --all --strict` 为 34/34。四个 change 均以 0 个未完成任务归档至 `openspec/changes/archive/2026-08-07-*`。
- 来源行快照完整回归：`uv run pytest -q` 为 `1265 passed, 142 skipped, 1 warning`，耗时 `233.09s`；warning 为测试依赖的 Starlette 弃用提示。
- 主规格切换：13 份新 capability 主规格与 delta 逐字比较一致，另保留 3 份既有稳定主规格；24 个编号主规格和临时 `time-boundary-contract` 已从当前事实源退役，历史仍可从归档和 `legacy/` 恢复。
- 未实现 change：更名为 `investment-ledger-browser`，移除迁移占位内容并按 7 条具体行为合同、19 项八阶段任务重新组织；`openspec validate investment-ledger-browser --strict` 通过，主规格路径确认不存在。
- 语义复核：根据 `src/ft/domain/investment_record_type.py` 将误写的 `swap` 修正为当前协议 `trade(security|fx|repo)`；根据已归档时区 change 将资金调拨窗口统一为 UTC。

## 审查记录

- 产品与范围复核：通过。变更只重组规格、active change、归档和当前文档引用；`git diff` 不包含 Python、TypeScript、数据库 schema 或迁移。当前主规格只描述已实现行为，未实现投资账本继续留在 active change，运行时非目标未扩大。
- 工程复核：修复 2 个 major finding：一是新投资事件规格误写不存在的正式 `swap` 类型，已按领域枚举修正为 `trade(security|fx|repo)`；二是资金调拨主规格同时存在固定上海日历与 UTC 合同，已统一为 UTC 并增加 `MODIFIED` delta。修复 2 个 minor finding：7 份 Purpose 过短导致主规格严格校验失败，以及 5 份当前文档仍链接退役编号主规格；均已修正。未发现未处理的 critical、major、minor finding。
- 中文文档复核：沿用 `DOMAIN_GLOSSARY.md` 中“主规格”“OpenSpec 变更”“delta 规格”“来源行快照”“业务行标识”等现有术语，无新概念需要更新词表；新增文本的中英文空格、全角标点、代码字面量和链接已按 `$chinese-documentation` 复核，混排扫描无命中。
- 最终 diff 复核：通过。旧编号主规格只删除当前投影，不删除归档或 `legacy/`；四个已交付 change 的目录完整移动到 2026-08-07 归档；新 capability、迁移清单和当前文档引用一致。残余风险仅是人工压缩后的主规格可能遗漏旧文件中的非占位边缘描述，已通过逐 requirement 映射、完整回归和保留历史证据降低风险。

## 最终验证与发布准备

- OpenSpec：`openspec validate --all --strict` 为 18/18；`openspec doctor --json` 返回 `healthy: true` 且无 status；`openspec list --specs --json` 返回 16 个无编号 capability。
- 结构守卫：16 个主规格、0 个编号目录、0 个迁移占位场景、0 个主规格 delta header、24 条旧 feature 映射、0 个当前文档旧规格路径；active change 仅为 `investment-ledger-browser` 与归档前的本 change，投资账本主规格不存在。
- Python：`uv run pytest -q` 为 `1265 passed, 142 skipped, 1 warning`；`uv run python -m compileall -q src` 与 `uv build` 成功。项目未配置独立静态类型检查器。
- Web：`npm ci` 成功且审计为 0 vulnerabilities；`npm test` 为 4 个文件、43 个测试通过；`VITE_FT_API_ORIGIN=http://127.0.0.1:8866 npm run build` 成功。
- 文档与 diff：修改文档的相对链接检查通过；`git diff --check` 与新增文本尾随空白检查通过。
- 不适用项：本 change 不修改运行时、持久化、查询、Web 组件或交互，因此不重复执行真实 PostgreSQL 矩阵、性能基准、生产预览、Playwright 或 Hallmark UI audit；若后续实现 `investment-ledger-browser`、修改任一数据库合同或改变 UI，必须按其 tasks 补跑这些门禁。安全复核限定于证据最小披露、历史保留和无敏感数据写入，未发现新增攻击面。
- 回滚：恢复迁移前的 `openspec/specs/`、active change 名称和 `openspec/MIGRATION.md` 即可；不涉及代码或数据回滚。交付后观察项是后续 change 是否继续修改既有 capability，而不是创建新的顺序 feature 主规格。
- 外部写：当前 `HEAD=c66a66f747ca4d4ddcd7f92af78f046b86483029`，比较基线同为该提交；本次未提交、未推送、未创建 PR、未合并或部署。
