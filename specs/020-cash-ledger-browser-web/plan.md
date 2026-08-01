# 实施方案：收支账本浏览 Web

**分支**：`020-cash-ledger-browser-web` ｜ **日期**：2026-07-29 ｜ **规格**：[spec.md](spec.md)

> 实施必须由项目级 `speckit_implementer` 使用 `$speckit-implement` 按 [tasks.md](tasks.md) 执行。

## 目标

把现有“原始现金流水 + 关系摘要”页面改为只读取持久化收支投影：无关系流水投影到自身，同笔支付只计
一次，退款冲销原消费，已确认内部转账进入列表并以“个人转账”显示，全额退款保留派生结果但不进入列表。

## 摘要

新增纯领域投影构建器、事务内投影维护 Application Service 和 5 张派生读模型表。日常事实或关系变更
锁定工作区状态，只重建变更前后受影响的完整关系连通组；全量命令构建暂存数据集，验证后原子切换活动
指针。Python API 仅查询活动数据集，独立 Node 前端改为“收支账本”，不保留原始流水回退端点。
现有投影列表响应同时返回当前活动版本的全量分类、币种选项，前端以原生下拉框消费，不新增选项端点。

## 技术上下文

**语言/版本**：Python 3.11+、TypeScript、Node.js 20 LTS+。

**主要依赖**：既有 SQLAlchemy、Alembic、FastAPI、Uvicorn、React、Vite、pytest、Vitest 和
Playwright；不新增第三方图算法、任务队列或缓存依赖。

**存储**：`FT_DATABASE_URL` 显式选择 PostgreSQL 或用户指定的持久化文件型 SQLite。新增投影状态、
数据集、条目、成员和关系依据表；Web 对 SQLite 继续使用现有只读快照策略，维护命令和业务写入使用
常规读写 Unit of Work。

**测试**：纯领域单元测试、Application Service 事务测试、Alembic 升降级测试、共享 Web/CLI 合同、
真实 SQLite/真实 PostgreSQL 集成矩阵、Vitest 组件测试、Playwright 与 gstack `qa`。

**性能目标**：最多 50 条的投影列表单页 p95 不超过 500 ms；增量路径只读取受影响关系前沿，不扫描或
重算无关投影；固定、去标识化的 10,000 条有效现金流水全量重建工作负载必须覆盖单成员、同笔支付关系
（`payment_mirror`）、退款冲销关系（`refund_offset`）和转账配对关系（`transfer_pair`）。在 SQLite 与真实
PostgreSQL 上分别预热 3 次后，正式计时 `CashProjectionService.rebuild()` 20 次，p95 均不得超过 10 秒。测试
必须输出后端、夹具摘要、预热和样本数、p95、Python 版本及运行平台；PostgreSQL 不可用时仅报告未执行，
不得将 SQLite 结果表述为双后端通过。2026-07-31 的失败基线为 SQLite `6.546 s`、PostgreSQL `11.584 s`；
修复仅限关系型投影适配器：以 SQLAlchemy Core 的 `session.execute(insert(Model), mappings)` 分批批量插入投影条目，
再按 `(workspace_id, dataset_id, projection_id)` 受限回查代理 ID，严格校验输入与回查的投影标识集合和基数全等，
否则抛出 `RuntimeError('projection.incomplete')`，最后分批批量插入投影成员与投影关系依据。不得依赖 `RETURNING`
返回顺序、方言分支、原始 DBAPI 或 COPY。

PostgreSQL 的单条语句最多接受 65,535 个 bind 参数；父投影回查的每个 `projection_id` 都会占用一个
`IN` 参数，因此不得把全量投影标识放入同一查询。关系型适配器使用一个共享批次大小常量（`900`）：它同时
限制 Core 批量插入和父投影回查的每批 `projection_id` 数量。父投影回查最多有 900 个投影标识加
`workspace_id` 与 `dataset_id` 两个条件，共 902 个绑定参数，低于传统 SQLite 的 999 参数上限。父投影回查
必须对每个批次保持相同的 `workspace_id` 与 `dataset_id` 限制，合并所有查询结果后，才执行既有的输入/
回查投影标识集合与基数全等校验。空投影集继续在写入前直接返回；事务边界、父代理 ID 映射、成员角色、
顺序和跨后端业务结果均不得改变。

**约束**：精确十进制；`Asia/Shanghai` 日期归属；仅 `accepted` 双边现金关系；主记录字段整体采用；
投影不可用时失败关闭；无自动回退、双写、隐式迁移或查询时实时计算。

## 已有能力与复用

- `src/ft/domain/relations/core/projection.py` 已实现余额与收支汇总，但结果只按币种聚合、会把超额退款截为
  零，不能作为条目读模型；保留其现有公共行为，新投影构建器复用 `FactView`、关系枚举和精确十进制工具。
- `src/ft/application/relations.py` 已集中关系检查、确认、取代和逻辑删除，是候选关系校验及增量维护的接入点。
- `src/ft/adapters/relational/uow.py` 已让 PostgreSQL 使用事务、文件型 SQLite 使用 `BEGIN IMMEDIATE`，
  可承载工作区投影状态锁和源/投影同事务提交。
- `src/ft/adapters/relational/dialect.py` 已处理 Web 的 SQLite 只读动态快照；投影查询沿用该能力，不另建副本机制。
- `src/ft/application/web_queries.py`、`src/ft/adapters/relational/web_queries.py` 和 `src/ft/web/` 已建立
  传输无关 DTO、脱敏错误和本机 API 边界；本次替换查询模型，不另起服务。
- `web/` 已有高密度表格、筛选、证据详情、键盘焦点和错误状态组件；本次复用 `EvidenceDetail` 改为覆盖式右侧模态证据抽屉，不重建设计系统。

## 系统架构

```text
现金写入 / 关系写入 / 重建命令
            │
            ▼
┌─────────────────────────────────────────────┐
│ Application Service + 同一个 Unit of Work │
│ 1. 锁投影状态                              │
│ 2. 读取变更前后关系连通组                  │
│ 3. 调用纯领域构建器                        │
│ 4. 校验并替换派生行                        │
│ 5. 源数据 + 投影 + 版本一次提交            │
└──────────────────────┬──────────────────────┘
                       │
              PostgreSQL / SQLite
                       │
          ┌────────────┴────────────┐
          ▼                         ▼
  事实源：现金流水/关系       派生：活动投影数据集
                                    │
                                    ▼
                         Python 只读查询 Application Service
                                    │ HTTP
                                    ▼
                              独立 Node 前端
```

### 组件与文件职责

| 组件 | 文件 | 职责 |
|------|------|------|
| 投影领域模型 | `src/ft/domain/cash_projection.py` | 关系图折叠、唯一主记录、经济类型、退款净额、不变量与确定性输出；不访问数据库。 |
| 投影应用编排 | `src/ft/application/cash_projections.py` | 全量构建、变更前后连通组增量重建、候选关系预校验、状态/版本与安全诊断。 |
| 仓储协议 | `src/ft/repositories/protocols.py` | 定义投影状态锁、数据集、图输入、组替换和发布接口。 |
| 关系型适配器 | `src/ft/adapters/relational/projections.py` | PostgreSQL/SQLite 等价的批量读取、活动组替换、暂存写入、校验和原子发布。 |
| ORM 与迁移 | `src/ft/adapters/relational/models.py`、`migrations/versions/20260729_11_cash_projections.py` | 实现 [data-model.md](data-model.md) 的 5 张表、约束和索引。 |
| 事务接入 | `src/ft/adapters/relational/uow.py`、现金/导入/关系 Application Service | 确保所有现金事实和生效关系语义变更在提交前维护投影。 |
| 维护 CLI | `src/ft/cli.py`、`src/ft/runtime.py` | `ft projections rebuild/status`，输出脱敏状态。 |
| Web 查询 | `src/ft/application/web_queries.py`、`src/ft/adapters/relational/web_queries.py`、`src/ft/web/routes.py` | 版本化投影分页、全量筛选选项、证据详情和稳定错误合同。 |
| 前端 API | `web/src/api/cashLedger.ts`、`web/src/api/types.ts` | 只调用投影端点并区分更新/不可用错误。 |
| 前端页面 | `web/src/pages/CashLedgerPage.tsx` 及既有组件 | 收支筛选、投影表格、备注展示和投影证据；组成方式只用于筛选和证据详情。 |

领域构建器保持单文件内聚；关系型写适配器与现有只读 Web 查询分离，避免把投影发布事务塞进已较复杂的
`web_queries.py`。现有大文件只增加装配点，不做无关拆分。

## 投影算法

### 输入筛选

1. 只读取未逻辑删除现金流水。
2. 只读取 `status = 'accepted'`、端点齐全且两个端点类型均为 `cash` 的关系。
3. 非 `accepted` 关系不进入图；证据详情稍后按成员批量查询为未生效提示。

### 关系处理

```text
有效现金流水
   │
   ├─ payment_mirror：secondary → primary，先折叠并只保留主金额
   │
   ├─ transfer_pair：将折叠后的组归为 internal_transfer
   │
   └─ refund_offset：退款组并入 primary 消费组，最后冲销净额
        │
        ├─ 剩余金额 < 0：显示消费
        ├─ 剩余金额 = 0：投影存在，hidden_reason=full_refund
        └─ 剩余金额 > 0：关系非法，构建失败
```

每条关系表达 `secondary_fact_id → primary_fact_id`。折叠后必须得到唯一根；成员指向多个父级、出现有向
环、端点缺失、关系不兼容或根不唯一时抛出 `InvalidProjectionRelationError`。`projection_id` 固定为
`cash:<root_id>`。展示字段全部复制根记录，其他成员只用于金额、类型、组成摘要和证据。

关系构建器先按种类验证端点：`payment_mirror` 必须两端金额、币种和经济方向一致；所有 `transfer_pair` 必须
两端金额异号，且只在币种相同时比较金额绝对值并要求相等；币种不同时不比较金额绝对值。`currency_exchange` 还必须币种不同；`refund_offset` 必须保持既有消费为负、退款为正、同币种和累计金额
约束，唯一例外是两端均为零且同币种：该组生成 `expense`、净额为零、`hidden_reason=full_refund` 的隐藏投影。单侧零金额退款仍失败。一个已确认关系连通组不得同时含有 `transfer_pair` 与 `refund_offset`。任何失败都在持久化投影或确认
关系前以 `projection.invalid_relation` 阻断，绝不通过隐藏条目静默处理。

自动确认不是投影校验的例外。写入候选前，关系服务必须把候选与其两个端点可达的完整已确认关系连通组
合并判断：`payment_mirror` 是多渠道表示边，可与退款冲销或内部转账共存；只有合并后同时出现
`refund_offset` 和 `transfer_pair` 时，自动确认降为 `pending_review`，在证据中写入脱敏原因
`relation.kind_conflict`。原始账单和候选行仍在同一导入事务中提交；人工确认复用完整投影校验并拒绝冲突图。
系统候选的重扫升级不是该门禁的例外：每次从 `pending_review` 尝试升级前，必须重新计算完整已确认连通组；
冲突未消除时保留原状态和 `relation.kind_conflict` 原因，不得交给投影维护路径回滚。

同笔支付的自动确认以候选组而非单条候选决定。对同一渠道对、账户、交易对方、币种、金额、方向和
`Asia/Shanghai` 自然日相同的候选，先按渠道分为两侧；仅当两侧数量相等时，分别按 `occurred_at ASC, id ASC`
排序并逐项配对。数量不等、字段不完整或无法组成两侧候选组时，保持 `pending_review`，不得按最近候选猜测。
同一渠道对的已确认配对占用两端；重扫必须把这些端点排除在该渠道对的自动确认候选之外，防止补出交叉边。
端点占用跨全部渠道对生效。若同一端点同时落入多个完整候选组，按规范化渠道对键升序处理并即时占用已接受端点，
其余渠道对不得再生成共享端点的镜像关系。

### 历史关系修复

真实 SQLite 的历史关系修复是一次性运维步骤，不是导入时自动纠正。实施前停止本机写入进程并创建不可覆盖的
时间戳备份；使用 `sqlite3 .backup` 后在备份上执行 `integrity_check` 和 SHA-256 摘要核对。随后在单个 SQLite
事务内，以工作区、关系 ID、当前 `accepted` 状态、关系种类和两端账单 ID 为前置条件，更新 `1541`、`2643`、
`2834` 的 `status` 与 `decision_reason`。不得改写 `created_*`、`decided_by`、`decided_at` 或 `evidence_json`，不得
删除原始账单、关系行，也不得触碰其余关系。任何前置条件不符均回滚，保留备份并报告差异。

`1541` 因经 `3085` 与 `1054` 连通后同时包含内部转账和退款冲销，更新为
`rejected` / `legacy_relation_repair:kind_conflict`。第二组按时间顺序保留
`1339 (6903→6523)` 与 `3055 (7053→10673)`；交叉边 `2643` 和 `2834` 更新为
`rejected` / `legacy_relation_repair:mirror_time_order`。提交后才运行 `ft projections rebuild`；失败不撤销已获审计的
关系修复，但投影保留未发布状态供诊断。

该事务的不可变前置条件清单为：目标 `1541/default/transfer_pair/1771/10919`、
`2643/default/payment_mirror/6903/10673`、`2834/default/payment_mirror/7053/6523` 均为 `accepted`；保留
`1054/default/refund_offset/2913/2440`、`3085/default/payment_mirror/2913/1771`、
`1339/default/payment_mirror/6903/6523`、`3055/default/payment_mirror/7053/10673` 均为 `accepted`。三个目标
`UPDATE` 必须全部命中才提交。提交后逐字段复核全部 7 条关系，并复核 8 条受影响账单
`2913`、`2440`、`1771`、`10919`、`6903`、`7053`、`6523`、`10673` 未变。

若已提交的修复经复核不符合该清单，停止写入进程并确认没有打开该数据库的连接，先校验修复前备份的
`integrity_check` 和摘要，再恢复精确主文件 `/Users/huangwenlong/.ft/finance-tracker.db`，并处理同目录、同文件名的
`finance-tracker.db-wal` 与 `finance-tracker.db-shm`。恢复后先执行 `integrity_check` 和备份摘要比对，再重新执行
7 条关系和 8 条账单的只读验收，最后决定是否重试。恢复操作只针对这三个明确路径，不得使用通配符或目录级替换。

这一修复只作用于用户授权的 SQLite 数据库，不执行跨后端迁移或双写。SQLite 临时副本先验证相同前置条件和
原子更新；本机 PostgreSQL `finance_tracker_test` 以去标识化 fixture 验证同一状态转换和投影重建契约。两个后端
对状态转换、审计字段、投影结果和失败条件提供等价行为，运行期仍只由 `FT_DATABASE_URL` 显式选择一个后端。

无关系负金额形成消费，正金额形成收入，零金额校准形成
`internal_transfer/balance_adjustment` 隐藏投影。已确认 `transfer_pair` 形成可见的内部转账，投影净额保持 0，列表通过已采用关系读取实际转出/转入账户、金额和币种；subtype
`credit_repayment` 原样映射，普通转账缺省为 `ordinary_transfer`。不根据备注产生换汇或银证转账关系。

### 增量重建

```text
锁工作区，再锁定或创建状态
  │
  ├─ 在变更前 accepted 图中，从所有端点扩展完整连通组 → before_ids
  ├─ 暂存源/关系变更
  ├─ 在变更后 accepted 图中，从 before_ids + 新端点再次扩展 → impacted_ids
  ├─ 删除活动数据集中成员与 impacted_ids 相交的旧投影
  ├─ 对 impacted_ids 当前各连通组分别构建并写入
  ├─ 验证 impacted_ids 中每条有效流水恰好归属一次
  └─ projection_version + 1，与源变更一起 commit
```

删除或失效关系可能把一个组拆成多个，因此必须从完整 `before_ids` 重新遍历新图，不能只重算仍直接相连
的端点。已有活动投影版本时，现金或关系语义写入必须与增量维护同事务提交；投影维护失败时回滚事实源。投影
尚未初始化时，合法事实源写入可以提交，但不生成或维护投影；决定跳过维护前必须在与首次重建相同的工作区—投影状态锁域内重新确认未初始化，只有显式 `ft projections rebuild` 可以发布首个活动数据集，读取端在此之前继续返回 `ProjectionUnavailableError`。

`ft transfer` 是例外中的已知事实构造：它在同一事务创建两条现金流水和一条已确认
`transfer_pair`，随后构建一个隐藏投影，不依赖后续匹配器猜测。

### 全量重建与发布

```text
schema 已升级
   │
   ▼
锁状态并标记 running ──> 创建 staging dataset
   │                            │
   │                            ▼
   │                    构建全部投影与来源摘要
   │                            │
   │                    完整性/唯一性/金额校验
   │                      │成功          │失败
   │                      ▼              ▼
   └────────────── 原子切换 active     删除 staging
                    version + 1         保留旧 active
```

全量构建在同一写事务中先锁 `workspaces` 行，再锁定或创建投影状态行；所有源写路径也按相同顺序获取
这两级锁，所以状态尚未初始化时也不会出现并发插入竞态。发布前重新计算来源摘要并与构建输入比较；摘要必须规范包含每条有效现金流水的身份、金额、币种、时间、
账户和全部复制到投影或证据的展示字段，以及每条已确认关系的端点、种类、状态和 subtype；
即使有外部 SQL 绕过 Application Service，也会以 `ConcurrentProjectionUpdateError` 失败关闭。
发布后保留至多 1 个退休数据集供事务内读者完成，后续维护安全清理；暂存和退休数据均可重建，不参与事实
审计。

构建事务失败时，暂存行、状态变化和源变化先整体回滚；随后由维护服务开启一个独立短事务，只在活动指针
和来源版本仍与失败前一致时写入 `last_build_status = 'failed'`、稳定错误码和脱敏摘要。诊断写入再次失败
只记录安全日志，不得覆盖原始构建错误，也不得改变活动数据集。

## API 与查询

合同见 [web-api.md](contracts/web-api.md)。列表查询必须以活动状态 CTE 读取版本和数据集，并由该
CTE 同时约束游标版本校验、限长页面候选行和投影关系依据；查询结果在应用层按投影行归组为关系摘要，下一页
游标只编码这条查询返回的版本与末行排序键。解码 cursor 后必须先验证 JSON 顶层为对象，再验证 `v` 与 `version` 为非布尔整数、`workspace`、`occurred_at` 与 `projection_id` 为字符串、`filters` 为完整筛选对象；任何解码、形状或字段类型错误统一返回 `invalid_cursor`。不得先读取活动状态后再发起页面或关系摘要 SQL，也不得依赖
PostgreSQL 默认 `READ COMMITTED` 会话在多条 SQL 间保持快照；SQLite 和 PostgreSQL 都以同一只读查询上下文
读取活动版本。每个成功页面响应还要在同一活动数据集上聚合不受当前筛选影响的非空分类和币种选项；选项与
页面版本绑定，游标追加可以复用该版本选项。游标版本不一致抛出 `ProjectionUpdatedError`；无活动数据集抛出
`ProjectionUnavailableError`。
证据金额和投影净额统一使用无指数形式的规范十进制字符串，不依赖
SQLite 或 PostgreSQL 的存储小数位。证据读取必须采用一条查询或显式快照事务，在同一快照内以
`projection_id + active_dataset_id` 定位投影并批量读取成员、已采用关系及所有成员的未生效关系，禁止逐成员
N+1 或在 PostgreSQL `READ COMMITTED` 下依赖多条查询的偶然一致性。查询适配器捕获运行期 DBAPI 和 SQLite
快照读取错误，统一转换为 `StorageError`，由 API 输出稳定脱敏错误码。

旧 `/cash-transactions` 和 `/evidence/cash/{id}` 路由、DTO 与前端调用直接删除。现金账户目录保留，
但用户文案从“消费账本视图”改为“收支账本”。

## 已确认界面设计

**视觉方向**：保留默认浅色、高密度、静默专业型。继续使用既有页面级 CSS 令牌、`Noto Sans SC`、
`IBM Plex Mono`、不超过 8 px 圆角、可见焦点和非纯颜色状态；不增加渐变、统计卡片或装饰图形。

**信息架构**：页面标题改为“收支账本”。主工作区从上到下为筛选条、投影表格和分页；证据详情通过覆盖式右侧
模态证据抽屉显示。列表列依次为发生时间、账户、交易信息、经济类型、金额（含币种）和证据入口；交易信息
单元格上下显示交易对方主文本与备注次文本，任一字段为空时显示 `-`；筛选栏的“交易信息”同时匹配交易对方和备注，不再重复展示来源或真实分类。“组成方式”不占用列表列，只保留
为筛选条件，并在证据详情的已采用关系中说明。分类与币种筛选使用后端全量选项下拉框。

详情打开后，所有视口在页面上方显示遮罩和右侧模态证据抽屉，背景收支账本保持可见但不可交互；面板从右侧
滑入，关闭时滑出并与遮罩淡出。点击遮罩关闭，点击面板内容不关闭；窄屏面板宽度为 `100vw`，宽屏面板使用
不超过 `480 px` 的固定阅读宽度。关闭详情后，焦点返回原证据入口；`prefers-reduced-motion: reduce` 时取消非必要动效。

```text
┌──────────────────────────────────────────────┬──────────────┐
│ Finance Tracker | 收支账本（背景被遮罩）        │ 证据详情     │
│ 当前视图 / 筛选 / 投影条目表                  │ 投影结果     │
│                                              │ 主记录       │
│                                              │ 全部成员流水 │
│                                              │ 生效关系     │
│                                              │ 退款时间线   │
└──────────────────────────────────────────────┴──────────────┘
```

经济类型使用“全部 / 消费 / 收入 / 个人转账”选项菜单；分类和币种使用后端聚合的下拉框；组成方式使用选项菜单。全额退款和余额校准没有列表筛选项，
可见内部转账通过“个人转账”筛选。已采用关系只在证据详情说明；未生效关系只在证据详情提示。

**窄屏**：顶部栏显示产品和“收支账本”；筛选收纳为可展开区域；投影卡片保留时间、交易信息、净额、
账户、经济类型和证据入口；详情为宽度 `100vw` 的右侧模态证据抽屉，关闭后焦点返回原投影条目。表头可视觉隐藏，但仍必须通过
原生表格语义与列作用域关联每个字段，不能只由 CSS 伪元素提供标签。触摸目标不小于 44 × 44 px。

**状态**：

| 状态 | 页面行为 |
|------|----------|
| 加载 | 保留投影列头、列宽和骨架行。 |
| 无数据 | 说明当前筛选没有匹配收支，并保留修改筛选入口。 |
| 投影已更新 | 显示“账本已更新，请刷新列表”，保留筛选并自动从第一页重读。 |
| 投影不可用 | 说明尚未生成有效账本，提供重试；不请求原始流水端点。 |
| 请求失败 | 显示脱敏原因类别和重试操作。 |
| 证据不完整 | 在对应字段说明未提供，不猜测或补造。 |

投影版本更新时，已打开的证据属于旧版本，页面必须先关闭该详情，再保留筛选从第一页读取。刷新完成后，
焦点移到更新提示；用户确认提示后再移到第一条投影的证据入口。不得把旧证据继续显示在新列表旁边。

### 已有设计资产

- 复用 `web/src/styles.css` 的浅色页面令牌、细边框、`Noto Sans SC`、`IBM Plex Mono`、可见焦点和
  `44 × 44 px` 触摸目标。
- 复用 `CashLedgerPage`、`CashFiltersBar`、`CashTable`、`EvidenceDetail`、`Pagination` 和
  `StatusView` 的页面职责，但把数据合同和用户文案全部切换为收支投影。
- 仓库没有独立 `DESIGN.md`；本 feature 的视觉约束以本节为准，不为单页改造另建通用设计系统。

### 设计非目标

- 不增加统计卡片、图表、营销区、引导游览或装饰性插图。
- 不提供内部转账、全额退款或原始现金流水的列表切换入口。
- 不为本 feature 新增主题切换、动画体系或跨产品组件库。

## 错误与恢复

| 失败点 | 领域/应用错误 | 处理与恢复 | 用户可见结果 | 测试 |
|--------|---------------|------------|--------------|------|
| 无活动数据集 | `ProjectionUnavailableError` | 不读事实源；先运行重建。 | API `503 projection.unavailable`；CLI 可操作提示。 | Application、API、前端、双后端。 |
| 旧版本游标 | `ProjectionUpdatedError` | 保留筛选并从第一页读取。 | API `409 projection.updated`。 | 游标单元、API、浏览器。 |
| 非法关系图 | `InvalidProjectionRelationError` | 回滚候选关系/源变更；全量时保留旧版本。 | CLI 稳定错误；Web 继续旧活动版本。 | 纯领域、关系服务、双后端。 |
| 成员遗漏/重复 | `ProjectionInvariantError` | 回滚增量或拒绝全量发布。 | `projection.incomplete`。 | 仓储约束、发布测试。 |
| 并发来源变更 | `ConcurrentProjectionUpdateError` | 回滚并要求重试；不发布过期结果。 | `projection.concurrent_update`。 | PostgreSQL 锁测试、SQLite 忙碌矩阵。 |
| 存储忙碌/只读/断开 | 既有 `StorageError` | 整个 Unit of Work 回滚并脱敏映射。 | `storage.busy` / `storage.readonly` / `storage.connect`。 | 共享存储错误矩阵。 |
| 只读状态检查目标无效 | `StorageError` | 仅以既有文件的严格只读连接读取，不创建 SQLite 文件或旁路文件。 | CLI 非零退出和 `storage.*` 摘要。 | CLI、SQLite 文件系统测试。 |
| 来源证据缺失 | 无异常，显式缺失状态 | 返回可用投影与缺失说明。 | “此记录未提供该证据”。 | API、前端。 |

`ft projections status` 与 `ft projections rebuild` 使用不同的引擎路径：前者只能打开已存在的只读数据库，
SQLite 连接不得创建文件或旁路文件，PostgreSQL 读取必须在数据库级 `READ ONLY` 事务中执行；后者才可使用
可写事务引擎。CLI 必须捕获投影领域、存储、SQLite 快照与引擎清理异常，输出稳定错误码与脱敏摘要后非零
退出，不能泄露 traceback 或绝对源码路径。

所有构建开始、成功和失败写结构化日志：工作区、构建 ID、投影版本、规则版本、耗时、投影数、成员数和
稳定错误码；禁止记录交易对方、备注、来源行快照、SQL、数据库 URL 或绝对路径。`ft projections status`
是本地运维入口，不新增仪表盘或网络管理端点。

既有 `RelationService.check()` 的宽泛异常捕获必须改为：投影/存储/已知校验错误映射稳定错误码，未知错误
只写脱敏内部日志并返回通用失败文案。不得继续把 `str(exc)` 直接写入 `OperationResult` 或导入结果；
失败关系事务不得再开启无意义的二次 Unit of Work 提交。

## 安全与隐私

- Web 继续仅绑定 `127.0.0.1`，只允许一个明确本机来源。
- Node 前端只通过 HTTP 读取投影，不能直连数据库或触发重建。
- 投影表复制主记录必要展示字段，不复制 `source_payload`；来源快照仅在证据读取时经过既有白名单脱敏。
- 错误和日志不得包含原始财务数据、驱动文本、凭据、数据库 URL、SQL、完整路径或 traceback。
- `projection_id` 只由内部现金流水代理 ID 构成，不包含业务行标识或账户信息。

## PostgreSQL / SQLite 差异清单

| 主题 | PostgreSQL | SQLite | 共享证明 |
|------|------------|--------|----------|
| schema | `BIGINT` 代理键、原生并发写锁 | `INTEGER` 代理键、文件数据库 | Alembic 升降级与模型约束测试。 |
| 事务 | 状态行 `FOR UPDATE` | 现有 `BEGIN IMMEDIATE` | 同一 Application Service 事务矩阵。 |
| 批量写 | SQLAlchemy Core 分块参数化 `INSERT`，父投影按受限键回查代理 ID | 同一 SQLAlchemy Core 分块参数化 `INSERT`，父投影按受限键回查代理 ID | 输入与回查的投影标识集合和基数全等；成员与关系依据保持角色、顺序和数据集幂等。 |
| 活动读取 | SQL 联接活动指针 | 只读动态快照中的同一 SQL 语义 | 共享 API 响应矩阵。 |
| 竞争失败 | 等待锁或序列化后重试错误 | `storage.busy` | 允许运行错误不同，禁止业务结果不同。 |

禁止自动回退、双写、shadow compare 和隐式跨后端迁移。

## Constitution Check

| 原则 | 方案响应 | 结果 |
|------|----------|------|
| 财务正确性与可审计性 | 精确十进制、唯一成员归属、主记录方向、超额退款失败关闭和完整证据链。 | 通过 |
| Spec Kit 规格驱动 | 先回写 Living Spec，再同步方案、合同和任务；产品代码只由 implementer 执行。 | 通过 |
| 测试先行与验证证据 | 每项领域、迁移、事务、接口和 UI 行为先写失败测试；覆盖恢复与并发路径。既有财富冷构建性能用例按 Complexity Tracking 暂不纳入本 feature 验收。 | 已批准例外 |
| 显式数据库选择与行为等价 | 共享领域/Application Service，明确方言差异并运行真实 SQLite/真实 PostgreSQL 矩阵。 | 通过 |
| 清晰边界与最小复杂度 | 纯构建器、应用编排、持久化和 Web 查询分层；不引入队列、缓存、触发器或新框架。 | 通过 |

**设计后复核**：投影是派生读模型，不改变事实源地位；迁移和回滚不改写事实表。完整测试套件中的既有
财富冷构建性能用例采用下述已批准例外，其余 constitution 要求均满足。

### Complexity Tracking

| 例外 | 批准与理由 | 风险控制 | 到期与消除路径 |
|------|------------|----------|----------------|
| Spec 020 验收运行完整测试套件时，排除 `test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets` 的 SQLite 与 PostgreSQL 参数实例。 | 用户于 2026-07-29 明确批准。该用例验证既有财富读模型的 10 万条事实冷构建，不覆盖本 feature 的收支投影；用户计划后续改造该模块。 | 不删除、不修改、不永久跳过测试，也不放宽 `5.000 s` / `6.500 s` 预算；仅在 Spec 020 的验收命令中用精确 `-k` 表达式排除。收支投影性能、双后端合同和其余测试仍必须通过。 | 后续财富读模型改造 feature 完成时到期；该 feature 必须优化或重新论证预算，并在默认完整测试中恢复该用例通过。 |

## 测试策略

1. **领域规则**：单成员、镜像链、退款镜像、部分/全额退款、多笔退款、转账 subtype、零金额校准、
   组合顺序、非生效关系忽略、缺端点、跨币种、超额、多根和环；补充零金额或同号内部转账、两端均为零的退款例外、单侧零金额退款、同币种金额不等、异币种金额不等但合法，以及金额或币种不一致的
   同笔支付、内部转账与退款混用等关系种类不变量。
2. **增量事务**：新事实、关系合并、关系拆分、逻辑删除、自动接受、人工确认、失败回滚、未初始化阻断，
   状态缺失时的工作区锁顺序，并证明未受影响投影的代理行不重写。待配对退款无论自动接受还是人工确认，
   都必须规范为原消费为主记录、退款为对侧流水。补充经同笔支付间接相连的退款与内部转账候选：自动扫描
   必须保留待审核、导入可提交，人工确认和两后端投影构建必须拒绝。补充相同字段的多笔平台/银行候选：
   两侧数量相等时按时间和稳定 ID 一对一自动确认，数量不等时保持待审核，重扫不得产生交叉边。
3. **全量与迁移**：空工作区、已有历史数据、成功发布、失败保留旧版本、幂等重建、升降级不改事实表、
   活动/暂存约束、发布前完整来源摘要复核（含展示字段并发变更）和回滚后独立失败诊断。
4. **共享后端合同**：同一夹具在 `~/.ft` SQLite 测试副本和本机
   `postgresql+psycopg:///finance_tracker_test` 运行，比较规范化构建结果、CLI、API 和错误码。
5. **Web**：全部筛选、3 页游标、版本变化、在活动状态读取与页面读取之间发生的并发重建、证据单快照读取、
   运行期存储错误映射、不可用/空/失败状态、金额字符串和时区。
6. **前端与浏览器**：组成筛选（含请求参数）、更新后刷新、无回退请求、证据章节、纯键盘、焦点返回、
   宽窄屏一致的表头语义，以及 `1440 × 900` 与 `390 × 844` 无重叠。
7. **回归**：既有关系匹配、现金写入、账单导入、SQLite 只读动态快照、排除 Complexity Tracking 所列
   既有财富冷构建性能用例后的完整 pytest、前端测试和构建。

所有 SQLite 集成测试使用临时副本，绝不写用户的 `~/.ft/finance-tracker.db`；该真实数据库只用于最终
只读 QA 和显式、事先备份后的本地重建验证。PostgreSQL 必须使用本机安装实例，不使用容器。

## 部署与回滚顺序

```text
1. 备份目标数据库
2. Alembic 升级到 20260729_11（只新增派生表）
3. ft projections rebuild
4. ft projections status 必须 ready
5. 启动 ft web 与 Node 前端
6. 冒烟：列表、部分退款、证据、旧游标、空态
```

失败处理：步骤 2 失败则回滚迁移；步骤 3 失败保留事实源和上一活动数据集，没有活动版本时不启动用户
浏览流程；步骤 5/6 失败则停止新前端/API，投影表保留供诊断。需要应用回滚时，旧代码可忽略派生表，
但旧原始流水 Web 不视为符合当前规格的回退方案；恢复浏览前必须修复并重新验证投影版本。确认无新代码
依赖后才可执行 Alembic downgrade 删除派生表。

## 实施阶段

1. 先完成领域规则、数据模型和迁移的失败测试与最小实现。
2. 完成全量重建、状态命令、活动数据集发布和双后端持久化契约。
3. 将所有现金与生效关系写路径接入增量维护，并完成非法关系阻断与事务回滚。
4. 替换 Web API/DTO/查询为版本化投影合同。
5. 改造前端列表、筛选、证据和错误状态。
6. 运行迁移、双后端、完整测试、gstack `review`、gstack `qa` 和 `$speckit-converge`。

## 非目标

- 不新增换汇、银证转账或其他关系候选识别算法。
- 不新增关系审查 Web、投影编辑、原始流水开关、统计图表或管理后台。
- 不把投资事件、持仓或估值写入收支投影。
- 不引入后台任务、消息队列、缓存、数据库触发器或通用投影框架。

## 12 个月理想状态差距

本方案建立可复用的“事实 + 已确认关系 → 版本化派生读模型”边界，但只覆盖现金收支。后续若投资账本或
更多关系需要类似能力，应先验证是否复用接口语义，而不是提前抽象通用投影框架。多工作区批量运维、关系
修复工具和跨设备部署仍不在 020 中。

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `plan-ceo-review` | 产品范围与价值 | 1 | CLEAR | `HOLD_SCOPE`；0 个范围扩张，首次构建、诊断和回滚完整性已回写规格。 |
| Eng Review | `plan-eng-review` | 架构与测试（必需） | 1 | CLEAR | 4 个问题已回写：初始锁、失败诊断事务、发布前来源复核、关系错误脱敏。 |
| Design Review | `plan-design-review` | UI/UX | 2（本规格 1） | CLEAR | 8/10 → 9/10；前次结论明确桌面并列重排，已由本轮 Living Spec 回写为所有视口覆盖式模态证据抽屉；版本更新时关闭旧证据和既有设计资产复用保持不变；本机缺少设计器 API key，未生成 PNG。 |
| Codex Review | 直接 `codex exec` 只读评审 | 实现与并发边界 | 1 | FAIL | 5 个 P1、2 个 P2：关系种类不变量、完整来源摘要、证据单快照、严格只读状态命令、运行期存储错误映射、CLI 脱敏失败和前端错误文案。已回写 T079～T084。 |
| Codex Review | 隔离环境直接 `codex exec` 只读复审 | T079～T084 修复 | 1 | FAIL | 1 个 P1、2 个 P2：`transfer_pair` 端点不变量未实现、PostgreSQL 状态查询未设数据库级只读事务、SQLite 快照引擎错误可绕过 CLI 脱敏。已回写 T085～T087。 |
| Codex Review | 隔离环境直接 `codex exec` 只读复审 | T085～T089 与异币种内部转账规则 | 2 | CLEAR | 首轮发现零金额端点与隐藏投影持久化断言缺口，已回写 T089；复审未发现可操作问题。 |
| Codex Review | 临时 `HOME` 的隔离只读 `codex exec` 复审 | T090 零金额退款例外 | 1 | CLEAR | 实现、领域测试和 SQLite/PostgreSQL 契约测试与“双方为零且同币种”的受限例外一致；无可操作问题。 |
| gstack Review | 只读评审 `a88139e..3e1e4b3` 的 020 范围 | 最终收敛前的代码、合同与可访问性复核 | 1 | CLEAR | 未发现可操作问题；收支账本列表的列顺序、备注回退、组成方式筛选、证据详情和窄屏表头语义均与 FR-022 一致。 |
| gstack QA | 最终 Web QA | 真实浏览器流程复核 | 0 | 未运行 | QA skill 要求干净工作树；当前存在用户保留的 019、021、022、023 未提交工作，且本次禁止提交或暂存。以本轮标准 Playwright `npm run test:e2e`（5 passed）和 `npm run test:preview`（1 passed）提供等价证据。 |

**UNRESOLVED**：无。
**VERDICT**：020 收敛通过。真实 SQLite 已完成授权的历史关系修复和投影重建；本轮双后端矩阵、共享合同、获批排除的 Python 回归、标准 Web 测试和构建均已通过。gstack QA 因工作树前置条件未运行，等价浏览器证据已记录在 `quickstart.md`。

## 021 审计工作台合并记录

**例外授权**：用户于 2026-07-30 明确授权把 `021-modern-web-ui-design` 的已交付展示层规范、验证记录和兼容性合同合并回本 Complete feature，并删除 021 重复 artifacts。本次受控 Flow-Back 例外只整理当前收支账本的唯一规格入口；不改变 `src/ft/`、`web/src/api/`、数据库、迁移、依赖或 `022-investment-ledger-browser-web`。

### 现行展示层架构

```text
既有本机 API（投影、账户、证据端点）
             │
             ▼
CashLedgerPage（请求取消、请求代次、版本更新、焦点恢复）
  ├── CashFiltersBar（默认折叠、范围摘要、即时筛选）
  ├── CashTable（交易信息/经济类型六列语义、移动端真实字段）
  ├── LoadMoreControl（observer 自动加载与按钮回退）
  └── EvidenceDetail（模态证据抽屉、遮罩关闭、焦点圈定与关闭回焦）
```

前端继续只消费既有 DTO。列表状态由累计记录、下一个 cursor、首批加载、追加加载和追加错误组成：首批成功替换记录；追加按服务端顺序并以 `projection_id` 去重；筛选或版本更新取消请求、递增代次并从 `null` cursor 重读；追加失败保留记录且只允许人工重试。

## Hallmark 审计 Flow-Back（2026-08-01）

### 范围与不采纳结论

Hallmark 审计已采纳三项展示层修复：筛选控件状态、样式标记和错误状态层级。筛选 `<summary>` 中“筛选”标题与当前范围摘要形成两行整体点击区域是有意的信息层级，保留原生 `<summary>` 的完整点击范围，不拆分、缩短或另设控件。

本轮不修改后端、收支投影、筛选参数、证据详情数据、分页语义、路由、持久化、迁移或依赖。

### 前端状态方案

- `CashLedgerPage` 继续以现有 `requestErrorMessages` 映射稳定错误码。仅当首批请求返回 `invalid_filter` 时，将页面错误状态传递给 `CashFiltersBar`；网络、存储、投影或 cursor 错误不得标记筛选字段无效。
- `CashFiltersBar` 接收一个可选的金额筛选错误状态：最低金额与最高金额输入添加 `aria-invalid`，通过关联的 `role="alert"` 文字说明错误；用户修改任一金额字段时立即清除该状态。成功响应后也保持清除，避免迟到响应把旧错误重新标记到已修正输入。
- 现有筛选控件继续复用 `styles.css` 的命名颜色、间距、边界和动效令牌。为输入框与下拉框补齐 hover、active、disabled、错误和成功选择器；错误与成功状态同时使用边界、背景和可见文字/语义，不能仅以颜色区分。
- `StatusView` 继续保留文字、`role="status"` 与错误时的 `role="alert"`。CSS 将加载和空态维持居中说明；`.status-error` 改为左对齐、紧凑的操作布局，并使重试入口与错误说明同一阅读起点对齐。
- `web/src/styles.css` 的首个非空内容改为可解析的 Hallmark 标记，记录 `macrostructure: Workbench`、`genre: modern-minimal` 和 `theme: Cobalt`，不伪造页面不存在的设计系统资产。

### 测试与验证

1. 先在 `web/tests/CashLedgerPage.test.tsx` 编写失败测试：`invalid_filter` 会标记两项金额输入并给出关联错误说明；编辑任一金额输入后状态清除；加载、空态、错误态仍保留文字，错误状态使用约定的左对齐类。
2. 在 `web/tests/accessibility.test.tsx` 或相邻前端测试增加标准 Hallmark 标记和筛选摘要整体点击行为的静态/DOM 回归，确保不采纳结论不被误改。
3. 再修改 `CashLedgerPage.tsx`、`CashFilters.tsx`、`StatusView.tsx` 与 `styles.css`，只实现上述前端状态合同。
4. 运行受影响 Vitest、完整 `npm test`、`npm run test:e2e`、`npm run test:preview`、`npm run build`、gstack `/qa` 与 Hallmark `audit`；再运行 `$speckit-converge`。根据发布门禁规则，把实际 HEAD、比较基线、命令、结果、时间和风险记录到 `tasks.md` / `quickstart.md`。

### 视觉、响应式与验证

- 使用现有 `Noto Sans SC` 和 `IBM Plex Mono`；颜色、字体、间距、边界、动效和层级只引用 `web/src/styles.css` 的命名令牌。
- `IntersectionObserver` 与“加载更多”共享防重入加载逻辑；无更多记录、追加中、追加失败和卸载时停止自动触发。
- 1440 × 900、1024 × 768、768 × 1024 和 390 × 844 均使用覆盖式右侧模态证据抽屉；宽屏面板不超过 `480 px`，窄屏面板为 `100vw`；额外检查 320、375、414、768 px 的横向溢出、焦点、遮罩点击、触控目标和表头语义。
- `contracts/web-ui-compatibility.md` 是本计划的 UI 读取、筛选、连续加载、证据、失败与视觉快照合同；`web-api.md`、`projection-cli.md` 和 `local-runtime.md` 继续定义既有 API、CLI 和本机运行时合同。

### 回滚

本次 artifact 整合可通过回退本次提交恢复 020/021 分离目录；不涉及数据、schema 或运行时回滚。021 实现与验证证据位于提交 `3822ecd`、`7471a8d`，并由本目录的任务、quickstart 和兼容性合同追溯。

## Living Spec 更新：全量筛选选项与表格语义（2026-08-01）

### 设计决策

- 不新增筛选选项路由。`GET /cash-projections` 的成功响应增加 `filter_options`，包含 `categories` 和
  `currencies` 两个稳定排序数组；它们从当前工作区活动数据集的全部可见消费、收入和内部转账投影聚合，不套用本次
  请求的日期、账户、交易对方、分类、币种、金额、经济类型或组成方式条件。
- `filter_options` 与列表响应共用活动投影版本和数据集读取上下文。后端 DTO 明确返回 `projection_version`、
  `items` 与 `filter_options`，前端不从当前页内容推导选项，也不维护静态枚举。
- 分类选项使用投影的真实 `category` 值；币种选项使用大写三位码。两者去除空值、去重并使用应用层稳定
  排序，避免 PostgreSQL 与 SQLite 的默认排序/NULL 行为造成差异。
- `CashFiltersBar` 将分类、币种从 `<input>` 改为 `<select>`，分别提供“全部分类”“全部币种”空值选项；
  首批响应到达前禁用控件，追加响应继续复用已加载选项。列表表格同时收敛为“交易信息”和“经济类型”语义，
  移除来源列。

### 转账投影展示决策

- `transfer_pair` 形成的投影保持 `net_amount = 0`，但 `visible = true`，并允许通过 `economic_type=internal_transfer`
  筛选；全额退款和余额校准继续隐藏。
- API 在转账投影上返回 `transfer` 双端展示 DTO。它从同一活动数据集的已采用关系和成员事实读取转出/转入账户、
  实际金额与币种并保留事实方向符号；前端以“转出账户 → 转入账户”显示账户，同币种去掉符号后只显示一次金额，跨币种以“转出金额 币种 → 转入金额 币种”显示，避免把净额 0 误读为收入。
- API 同时返回 `monthly_summaries`，在当前筛选条件下跨完整结果集按 `Asia/Shanghai` 月份和币种聚合收入/支出净额；不把
  `limit`、`cursor` 或当前页边界带入聚合，内部转账不计入收入/支出。前端只负责按列表实际出现的月份插入分割行和格式化精确十进制字符串。

### 影响范围与实现顺序

1. 先在 Python Web DTO、关系型只读查询、API 契约测试中增加 `filter_options` 失败断言，证明选项跨当前
   筛选保持完整且为空值安全；同时在前端测试中断言两个输入框已不存在、下拉选项来自响应。
2. 修改应用查询与关系型查询适配器，使用活动数据集过滤 `visible = true` 的行，允许 `economic_type` 为
   `expense`、`income` 或 `internal_transfer`；聚合非空分类和币种；不改变列表过滤、金额精度、cursor、证据或持久化。
3. 修改 `CashFiltersBar`、`CashLedgerPage`、前端类型和 fixture，保留请求取消、迟到响应保护和即时筛选
   语义；仅将控件值继续写入既有 `category` / `currency` 查询参数。
4. 运行 SQLite 与真实 PostgreSQL Web 合同矩阵，随后执行前端测试、构建、代码审查、Hallmark 审计和 gstack
   浏览器 QA；若发现合同或设计问题，先回写本节与相应任务再修复。

### 验证门禁

- 后端：应用/关系型 Web 查询测试、API 合同测试、SQLite 与 PostgreSQL 真实 Web 集成和响应矩阵；额外验证
  空分类、空币种、可见内部转账进入列表和选项，隐藏全额退款/余额校准不进入选项。
- 前端：Vitest 覆盖首批响应、空选项、下拉选择触发 `category` / `currency` 请求、加载禁用和键盘焦点；
  Playwright 覆盖展开筛选、桌面/移动视口无溢出。
- 静态：`uv run compileall src tests`、`npm run build`、`git diff --check`；依赖未安装时必须明确记录未
  执行，不得用旧结果代替当前验证。

本轮手工 Hallmark `audit` 只读检查页面标记、Workbench/Ledger Grid 结构、蓝灰白主题、焦点轮廓与响应式表格，结果为
`0 critical · 0 major · 0 minor`；自动化 Vitest、Playwright、build、gstack `/review`、`/qa` 与 `$speckit-converge`
因前端依赖安装阻塞和当前工作树未提交而保留为未完成门禁，证据见 `quickstart.md`。
