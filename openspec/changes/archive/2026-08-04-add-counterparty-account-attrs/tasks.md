## 1. 思考

- [x] 1.1 阅读项目上下文、主规格、active changes、术语词表、导入器、迁移和关系匹配代码，确认本变更属于 A 类数据模型与持久化变更。
- [x] 1.2 只读审计 `~/.ft/bills`，按支付宝、微信、建行借记卡、工行借记卡、工行信用卡和工银亚洲建立去标识化账号覆盖桶表。
- [x] 1.3 确认根因是导入规范化清空掩码/非数字账号且匹配层重新猜测字符串语义；不改变无资金事实的白名单跳过规则。

## 2. 计划

- [x] 2.1 完成 proposal、3 份 delta spec、design 和来源分类附件，明确属性枚举、来源提取、迁移、隐私和回滚合同。
- [x] 2.2 更新 `DOMAIN_GLOSSARY.md` 中的对方账号与对方账号属性定义，并按中文文档规范复核 artifacts。
- [x] 2.3 运行 `openspec validate add-counterparty-account-attrs --strict`，修复 proposal、spec 和 design 的一致性问题后再实施。

## 3. 任务拆分与一致性

- [x] 3.1 先增加领域失败测试，覆盖空值、完整、尾号、掩码、非数字标识、严格重建和非法属性组合。
- [x] 3.2 先增加六类现金来源失败测试，证明可提取账号及属性不再丢失，且付款方式、本方账号与文本不会误提升。
- [x] 3.3 先增加 SQLite/PostgreSQL migration 与导入契约失败测试，覆盖列默认值、保守历史回填、幂等、工作区隔离和公开字段。
- [x] 3.4 先增加转账匹配失败测试，覆盖完整、尾号、掩码、重建、冲突、未知/缺失属性、短窗口回退和 7 天窗口门禁。

## 4. 构建

- [x] 4.1 实现对方账号值对象、合法属性组合校验和统一来源规范化，保证账号与属性成对生成。
- [x] 4.2 更新支付宝、微信、建行借记卡、工行借记卡、工行信用卡和工银亚洲解析路径，补齐来源专用账号提取。
- [x] 4.3 增加 `counterparty_account_attrs` 模型、migration、运行时 revision、仓库、查询和 CSV 合同，并保守回填已有事实。
- [x] 4.4 更新 `FactView` 与转账匹配，使所有 `counterparty_account` 消费点显式验证属性并按唯一别名失败关闭。
- [x] 4.5 更新导入流程文档和数据库结构说明，记录属性枚举、隐私边界、迁移限制和来源专用规则。

## 5. 审查

- [x] 5.1 完成产品/范围复核，检查“所有可提取账号”覆盖、非目标来源字段和既有白名单跳过边界。
- [x] 5.2 完成工程与安全复核，检查 JSON 组合、迁移事务、掩码唯一性、工作区隔离、日志暴露、性能和回滚。
- [x] 5.3 完成独立最终 diff 复核，按严重级别记录 finding、采纳/拒绝理由和修复后的复核结论。
- [x] 5.4 记录 UI、Hallmark、Web QA 和浏览器检查不适用：本变更不调整页面、交互、路由或 Web 展示合同。

## 6. 测试与 QA

- [x] 6.1 运行新增领域、来源、导入、迁移、关系和 CLI/CSV 回归测试，记录失败转绿证据。
- [x] 6.2 在 SQLite 与真实 PostgreSQL 执行同一 Application Service 和关系合同矩阵，验证 schema、JSON、幂等和结果等价。
- [x] 6.3 使用 fresh SQLite 对 `~/.ft/bills` 运行去标识化来源覆盖校准，核对六类来源计数与属性分布，不向仓库或输出写入真实账号。
- [x] 6.4 运行完整 Python 回归、`python -m compileall`、`uv build`、`git diff --check`、`openspec validate --all --strict` 和 `openspec doctor`。
- [x] 6.5 在本文件记录比较基线、当前 `HEAD`、实际命令、执行时间、结果、未运行项及准确补跑条件。

## 7. 发布

- [x] 7.1 记录部署前数据库备份、revision/行数/完整性基线、升级后属性分布与关系抽样检查，以及从备份回滚步骤。
- [x] 7.2 确认真实数据库重建、本地提交、推送和 PR 均在取得用户明确授权后执行；未执行合并、部署、流量切换或正式回滚。

## 8. 反思

- [x] 8.1 记录“来源表示在导入边界显式化、匹配器不猜字符串语义”的防复发规则和新来源接入检查项。
- [x] 8.2 将已验证 delta 规格同步到主规格，复核每项 requirement 与场景后归档本变更。

## 审查记录

- 产品/范围复核：覆盖支付宝、微信、建行借记卡、工行借记卡、工行信用卡和工银亚洲。只提升来源直接提供且可归属到业务行的对方账号；付款方式、本方账号、账户映射、对方名称与商户文本保持非目标。支付宝未支付关闭与失败还款白名单跳过计数未改变。
- 工程与安全复核：账号和属性原子写入；JSON 组合固定且写入/匹配双重校验；重建证明绑定当前 `source_type` 与 `source_payload` 摘要，不持久化、不进入来源快照、业务行哈希、公开输出、日志或关系证据；关系别名查询保持工作区隔离；migration 事务内保守回填并支持双后端降级。
- 第一轮独立复核发现 3 个 Major 与 1 个 Minor，全部采纳：宽泛数字掩码会误开 7 天窗口；重建属性可被直接伪造；PostgreSQL 未覆盖真实 `20 → 21` 回填与 downgrade；属性校验错误接受 `dict`。修复后增加对应回归与真实 PostgreSQL 升降级矩阵。
- 第二轮独立复核发现 1 个 Major：合法重建 proof 可复制到另一来源行。已采纳并将 proof 绑定渠道和原始行摘要，增加跨行、跨渠道复制失败测试。另一个“任务记录未完成” Major 通过本节及后续验证、发布与反思记录收口。
- 第三轮独立复核确认无 blocking finding：proof 的当前来源行绑定、持久化与公开泄露边界、业务行哈希排除，以及 PostgreSQL 重建回填和索引升降级覆盖均已闭合。
- UI、Hallmark、Web QA 与浏览器检查不适用：本变更没有页面、交互、路由、响应式或 Web 展示合同改动。

## 验证记录

- 比较基线与当前 `HEAD` 均为 `4fa34f0f95d69d04de3819bd0a07298b37113c78`，分支为 `counterparty-account-attrs`；验证期间改动均未提交。执行日期为 2026-08-04（Asia/Shanghai）。
- 测试先行证据：初始新增测试为 `33 failed, 27 passed, 7 skipped`；首轮实现后范围测试为 `60 passed, 7 skipped`，扩展相关测试为 `82 passed, 7 skipped`。独立 finding 回归先得到预期 `3 failed, 23 passed, 2 skipped`；修复后为 `27 passed, 1 skipped`。proof 跨行复制测试先按预期失败，绑定当前来源行后 SQLite 通过，SQLite/PostgreSQL 定向矩阵为 `3 passed`。
- 双后端相关矩阵：在专用临时 PostgreSQL 数据库与 SQLite 执行领域、来源、仓库、公开字段、真实 migration、导入幂等、工作区隔离和关系测试，命令为 `FT_TEST_POSTGRES_URL=... FT_REQUIRE_TEST_POSTGRES=1 uv run pytest -q tests/test_counterparty_account_attrs.py tests/test_complete_statement_source_payload.py tests/test_postgres_adapter.py tests/test_alembic_migration.py tests/contract/test_dual_backend_counterparty_account_transfer_matching.py tests/contract/test_dual_backend_record_type.py tests/contract/test_row_idempotent_import.py`，结果为 `110 passed`。PostgreSQL migration 覆盖 `20260804_20 → 20260804_21 → 20260804_20 → 20260804_21`、默认空数组、`full`、`masked`、`masked + reconstructed`、未知历史值和活跃业务行唯一索引。
- 真实账单 fresh SQLite 匿名校准：递归导入 `~/.ft/bills` 中全部 36 份账单，得到现金流水 `11,460` 条、投资事件 `1,010` 条；再次导入全部账单时新增数为 `0`。现金来源覆盖支付宝、微信、建行借记卡、工行借记卡、工行信用卡和工银亚洲，投资来源覆盖东方证券、IBKR 和盈立。20 份受保护 PDF 的口令只从文件名或父目录名写入权限为 `600` 的临时文件并通过 `qpdf --password-file` 使用，未进入参数、输出、日志或仓库；验证后临时口令和导入日志均已删除。
- 用户明确授权后，真实 SQLite 先通过在线备份保留旧库，再在同一文件系统创建 `20260804_21` 临时新库、复制工作区和账户主数据、全量导入账单并原子替换。替换后现金与投资各导入渠道计数均与旧库一致；共 `4,831` 条现金流水具有对方账号，属性分布为 `244 full`、`4,574 masked`、`13 tail` 和 `6,629` 空数组，非法 JSON、空账号带属性、非空账号缺属性、领域非法组合和外键问题均为 `0`。最终 `integrity_check=ok`，收支投影为 `ready` 并覆盖全部 `11,460` 条现金流水；旧库保留在 `~/.ft/backups/`，专用临时目录已删除。
- 修复前完整回归为 `1189 passed, 124 skipped, 1 failed`，唯一失败是既有财富性能预算；第一次审查修复后的功能回归（排除该门禁）为 `1195 passed, 125 skipped`。最终修复后运行 `uv run pytest -q --ignore=tests/test_wealth_performance.py`，结果为 `1195 passed, 125 skipped, 1 warning`，耗时 `194.56 s`；warning 是既有 Starlette/httpx 弃用提示。
- 财富性能门禁对照：本分支 SQLite 冷重建 P95 约 `6.52 s`、热缓存约 `66.8 ms`；干净基线冷重建 P95 约 `6.32 s`、热缓存约 `66.1 ms`。两者都超过既有 `5 s` 冷重建门槛，且财富查询未选择新增列，因此判定为环境/基线已有失败，不是本变更回归；准确补跑条件是使用同一机器、同一依赖和无并发负载复测基线与本分支。
- 最终工具验证：`uv run python -m compileall -q src tests`、`uv build`、`git diff --check` 均通过；`openspec validate --all --strict` 为 `30 passed, 0 failed`，`openspec doctor` 正常；OpenSpec CLI 为 `1.7.0`。完成时间为 `2026-08-04 18:16:55 CST`，当前 `HEAD` 未变化。

## 发布准备与反思

- 发布前必须对目标数据库执行可恢复备份，并记录当前 Alembic revision、工作区与现金流水行数、`counterparty_account` 非空数、属性组合分布、非法组合数和活跃业务行唯一索引定义。升级后复核相同指标，抽样检查账号与属性成对、关系只引用显式别名且没有跨工作区结果。
- 回滚优先恢复升级前备份。确认允许保留升级补回的账号证据时，才可 downgrade 到 `20260804_20` 删除属性列；downgrade 不主动清空补回账号。代码与 schema 必须同批部署或同批回滚。
- 真实 SQLite 重建在用户明确授权后完成；变更交付阶段同样按用户授权执行本地提交、推送，并创建以 `refactor/web` 为 base 的 draft PR。未执行合并、部署、流量切换或正式回滚。专用临时 PostgreSQL 测试库结束时确认无活跃连接后已删除，并复核数据库不存在。
- 防复发规则：新来源必须在导入边界从直接来源单元同时生成账号值、属性与适用的同源重建证明；匹配器只消费正式字段和当前工作区显式别名，不得重新读取来源或猜测字符串。接入检查必须覆盖空值、完整、尾号、掩码、冲突、多候选、幂等、双后端迁移和公开输出。
- 3 份 delta 已同步到 `006-transaction-relations`、`015-inline-row-provenance` 与 `counterparty-account-transfer-matching` 主规格；逐项复核 requirement 描述与全部场景后无剩余差异。
