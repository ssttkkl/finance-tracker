# Finance Tracker 产品化重构顶层路线

> 状态：Active top-level roadmap
>
> 目标分支：`refactor/web`
>
> 面向范围：产品定位、阶段依赖、feature 顺序和进入下一阶段的证据门槛

## 1. 文档权威与边界

本文只回答“产品往哪里走、为什么按这个顺序走”，不是可直接实施的 feature spec。

- `.specify/memory/constitution.md` 定义不可妥协的工程原则。
- `specs/<feature>/spec.md` 定义该变更做什么和为什么。
- `plan.md` 定义技术方案、数据模型和 contracts。
- `tasks.md` 定义测试先行的执行顺序和完成状态。
- gstack 产品或架构评审结论必须回写上述 artifacts，不能在 `docs/` 独立演进。

当前相关材料：

- [项目 README](../README.md)：运行时、CLI、导入与同步。
- [文档索引](README.md)
- [001-postgres-only-storage](../specs/001-postgres-only-storage/spec.md)：历史 PostgreSQL-only 收口（后由 002 双后端叙事覆盖）。
- [002-dual-database-runtime](../specs/002-dual-database-runtime/spec.md)：双数据库运行时（Phase 1 现金链起点）。
- [017-asset-valuation-quote](../specs/017-asset-valuation-quote/spec.md) / [018-investment-connector-sync](../specs/018-investment-connector-sync/spec.md)：估值与连接器（Phase 1 已 Complete）。
- [database-schema.md](database-schema.md)：015+016+018 落地态结构速查（Alembic `20260726_10`）。
- [财富解释与趋势对比设计](productization-wealth-report-design.md)：已批准、但非实施权威的产品决策输入。

## 2. 产品定位

Finance Tracker 面向同时使用银行、支付平台、券商和交易所的多账户、多币种、多资产用户。

产品的核心价值不是“再做一个记账 App”，而是：

> 把消费、储蓄和投资事实放进同一套可审计账本，并可信地解释财富为什么变化。

三个长期产品域：

1. **消费记账**：收入、消费、退款、转账和对账。
2. **资产交易**：证券、加密资产、现金腿、公司行为和 Connector。
3. **财富分析**：聚合前两者，解释净资产变化、投资表现、FX 和数据缺口。

## 3. 当前工程基线

### 已完成：Phase 1 Application Services

- CLI 叶子命令已经通过 Application Service 和 ports 编排。
- CLI 负责参数、确认、退出码和展示；Application 层负责验证、事务和状态机。
- 当前命令矩阵和边界测试见 Phase 1 文档。

### 已完成：Phase 2 PostgreSQL Storage

- 已具备 workspace-scoped PostgreSQL schema、精确金额、事务边界、来源记录和追加式修订。
- 已具备 PostgreSQL repositories、queries、Alembic 和真实 PostgreSQL 集成测试。
- Phase 2 同时交付了 local/postgres 选择、迁移和 shadow comparison；这些是实验性过渡产物，
  不再代表目标架构。

### 已完成：001-postgres-only-storage

新产品能力开始前的存储收口已经完成：

- PostgreSQL 成为 CLI、Web、Worker 和 MCP 的唯一运行时事实源。
- 删除 CSV/YAML/Git 文件账本 backend、backend 选择、迁移兼容和 shadow comparison。
- 原始 CSV、XLS/XLSX 和 PDF 只作为输入证据，不成为正式账本或运行时回退。
- 当前数据可丢弃；应用不得读取、迁移或自动删除用户目录中的旧账本。
- 财务语义、来源审计、精确金额、幂等和事务原子性必须保留。

具体范围和验证证据见 `specs/001-postgres-only-storage/`。README、CLI help 和当前操作文档已经同步为
PostgreSQL-only；旧文件账本、迁移、shadow comparison、Connector sync 和文件 reconcile 已从产品表面删除。

### 已完成：PostgreSQL 与 SQLite 双数据库运行时（`002`）

PostgreSQL 与文件型 SQLite 均为正式运行时后端，由 `FT_DATABASE_URL` 显式选择。
两个后端共享 Application Service、CLI 契约、财务语义、审计关系和 schema 迁移入口；不提供自动回退、
双写或隐式跨后端迁移。SQLite 使用 WAL、外键和有界写锁等待；既有权限过宽的文件只给出修复建议，
不会自动 chmod。`001-postgres-only-storage` 保留为已完成的历史收口记录，不回写新需求。

### 当前基线（`refactor/web` @ 018 后）

- **现金链** `002`–`008`：双 DB、mapping/开放币种、多币种账户、关系、导入与 kind 解耦 — **Complete**。
- **投资文件导入与账本收口** `009`–`016`：多券商文件导入（DFZQ/IBKR/Schwab/uSmart 等）、行级幂等、成本币种与 cash-like actions、事实字段统一、内联溯源、**整数代理主键** — **Complete**。
- **估值** `017-asset-valuation-quote`：统一 `ValuationService` + 组合本币/展示币市值与 `quote_status` — **Complete**（已合入）。
- **连接器同步** `018-investment-connector-sync`：ccxt（binance/kraken/okx）+ Polymarket Activity/pUSD checkin、`ft sync`、行级幂等与 `sync_cursors` — **Complete**（2026-07-28 全量双后端回归绿）。
- **Alembic / `SCHEMA_REVISION` head**：`20260726_10`。
- **编号漂移（重要）**：仓库 `011`–`016` 已用于 uSmart/成本/字段/provenance/bigint。路线图旧名 `011-asset-valuation-quote` → **`017`**；旧 `012` connector-sync → **`018`**。账单 Web 落地时用新序号。
- 活跃指针：Phase 1 关账后无强制活跃 feature；下一产品方向为 Phase 2 `transaction-browser-web`（新序号）。

## 4. 产品与架构原则

1. **一次选择一个事实源**：运行时通过 `FT_DATABASE_URL` 显式选择 PostgreSQL 或 SQLite，不建设双写、
   自动回退或文件账本回退。
2. **业务规则只有一份**：CLI、Web、Worker、AI 和 MCP 调用相同 Application Service。
3. **模块化单体优先**：没有当前 feature 的具体需求，不增加微服务、队列或通用平台层。
4. **可审计优先**：导入、自动规则、人工决定和 AI 建议必须能追溯来源与修订。
5. **确定性优先**：可以由规则可靠处理的行为不交给模型。
6. **AI 草稿优先**：AI 不绕过后端校验、确认和事务直接写正式事实。
7. **读模型可重建**：snapshot、财富序列和缓存不能成为唯一事实源。
8. **按证据扩张**：没有重复使用和真实阻碍，不提前建设多用户、Connector 平台或 AI 基础设施。

## 5. Feature 路线

### 5.1 `001-postgres-only-storage`：存储收口（完成）

项目未上线，开发数据可丢弃，因此直接完成破坏性替换而没有兼容或迁移阶段。

已满足的退出条件：

- 不存在可执行 local backend 或隐式回退；
- 当前受支持入口绑定明确 workspace；
- 写入使用数据库事务且失败不发布部分事实；
- 原始输入、正式事实和修订仍可追溯；
- 文档、CLI help 和测试不再承诺旧运行方式。

### 5.2 Phase 1：数据模型与数据导入（基座）

本阶段目标：建立可信的现金与投资事实读模型，使账单条目可独立展示与审计。

#### 现金/消费链（按序收敛）

- `002-dual-database-runtime`：PostgreSQL 与 SQLite 双运行时等价。
- `004-mapping-import-open-currency`：mapping 导入 + 开放币种。
- `005-multi-currency-accounts`：多币种账户建模。
- `006-transaction-relations`：关系（去重/退款核销/转账配对/投影）。
- `007-closed-trade-refund-import`：导入 no-skip + 原始 payload + 统一关系扫描。
- `008-relations-kind-decouple`：关系识别 kind 竖切解耦。

#### 投资链与账本收口

- **`009-investment-account-import`**（**Complete**）：投资事件领域模型与文件/手动导入（DFZQ/IBKR/Schwab 等）。
  非目标：Connector 自动同步（历史路线图编号 `012` / 能力名 connector-sync）；行情/估值（能力名 asset-valuation-quote）。

- **`010-row-idempotent-import`**（**Complete**）：消费与投资导入 **业务行幂等 + 重叠文件可增量**
  （`record_id` × `source_type`；015 后无独立 import/raw 作业表）。

- **后续已合入（强化导入/schema，不替代估值门槛）**：
  `011-usmart-hk-import`、`012-investment-base-currency-cost`、`013-investment-cash-event-kinds`、
  `014-fact-field-unify`、`015-inline-row-provenance`、`016-bigint-surrogate-ids` — 均为 **Complete**。

- **`017-asset-valuation-quote`（Complete；历史路线图名 asset-valuation-quote / 旧编号 011）**：
  组合持仓 **本币估值**（分币种不折算）与可选 **`display_currency` 只读 FX 折算**；统一 `ValuationService`（security/crypto/pm/cash）与 **complete/stale/partial/unsupported**。
  非目标：历史时间序列、期初/期末边界估值、收益率归因（归 Phase 3）。

- **`018-investment-connector-sync`（Complete；历史路线图编号 `012` / 能力名 investment-connector-sync）**：
  手动 `ft sync`：ccxt 交易所（binance/kraken/okx）私有成交+ledger、Polymarket Activity（含 REDEEM/YIELD）与当前 pUSD `balanceOf` → USD checkin；
  `source_type`×`record_id` 幂等、`sync_cursors` 增量、`~/.ft/credentials.yaml` 本地凭据；有界 `ft stock list` 渲染。
  非目标：定时 Worker、Web、secret vault、通用 Connector 平台、行情/FX 自动更新（017）。

#### Phase 1 完成门槛

- `002`–`008` 全部收敛；✅
- `009` 落地（投资事件可导入，多券商解析可用）；✅
- `010` 落地（现金/投资导入行级幂等与重叠增量）；✅
- **实时估值 + 组合市值**落地（`017`：本币/展示币 + coverage 状态）；✅
- **Connector 同步**落地（`018`：交易所/Polymarket API → 统一投资事件）；✅
  （原「可延后不阻塞 Phase 2」仍成立；现已交付并关账。）

### 5.3 Phase 2：账单浏览 Web（只读）

依赖：投资/现金事实导入与行幂等已就绪（`009`+`010` 及后续 schema 收口），且 **估值能力**（`asset-valuation-quote`）完成；Connector 不阻塞。

**`transaction-browser-web`（历史路线图编号 `013`；落地时用新序号）**：独立只读 Web，连接本机 workspace，展示已导入账单条目。

范围：

- 按 `account.type` 分投资（investment）与消费（cash）两视图；
- 可选展示当前市值（来自估值 port）；
- 关系状态（accepted/pending）、来源（`source_payload` / `source_type`×`record_id`）与证据下钻；
- loading/empty/partial/stale/unsupported 状态；
- 本地打包、API schema、浏览器 QA 和无障碍基线。

非目标：写入、财富归因、认证、Review Inbox accept/reject 交互。

### 5.4 Phase 3：财富分析

依赖：Phase 1 完成（投资事实与估值基础就绪）。

- **`003-wealth-attribution-core`**：财富归因内核。
  财富变化恒等式与符号；期初/期末估值、外部现金流、投资收益、FX、负债重估和差额；
  每日原子桶与日/周/月聚合；投资市场收益率、coverage、partial/stale/unsupported；
  component、evidence 和 canonical DTO；PostgreSQL/SQLite 等价 contract、性能基线和重建测试。
  非目标：Web、认证、Review Inbox、Connector、AI 和 MCP。
  实现交接以 [`003-wealth-attribution-core`](../specs/003-wealth-attribution-core/spec.md) 的
  Spec Kit artifacts 为唯一事实源。内核保持 transport-neutral：Web/API 适配和展示 URL 不属于该
  feature；正式估值、账户生命周期和不可变 generation/evidence 是 PostgreSQL 与 SQLite 共享的
  可重建输入/读模型边界。

- **`wealth-report-web`**：本地只读财富报告。
  依赖财富归因内核完成；可复用 `013` 的 Web 应用与 API 骨架。
  区间解释和趋势对比；日/周/月净资产折线、组成项柱和独立投资收益率线；
  component/evidence 下钻；loading/empty/partial/stale/unsupported 和 coverage 断线；
  本地打包、API schema、浏览器 QA 和无障碍基线。第一版不增加登录、组织成员或云端上传。

### 5.5 A3：外部产品验证

依赖：Phase 3 完成。

找到 5 位符合首批画像的用户，采用辅助安装和数据准备，不做引导演示。

观察：

- 是否能解释一个完整自然月的财富变化；
- 是否能分别找到净资产变化最大/最小桶和有效投资收益率最高/最低桶；
- 是否能区分外部现金流、投资表现、FX 和数据缺口；
- 哪个数字首先被质疑；
- 是否在下个月主动再次使用；
- 是否愿意付费或要求持续服务。

没有重复使用证据时，继续改进 A，不进入完整平台建设。

## 6. A 到 C 的触发式路线

以下不是当前 backlog。只有触发条件出现后，才创建独立 Spec Kit feature。

### C1：托管单 workspace

触发：本地运行方式仍成为重复使用的主要阻碍，而非产品价值不足。

可能范围：最小登录、单 workspace 授权、托管数据库、备份与恢复。

### C2：自助导入与 Review Inbox

触发：人工准备数据成为新用户增长瓶颈，且用户愿意托管原始数据。

可能范围：上传、解析 job、case 级审查、draft/validate/confirm/commit、审计与重试。

### C3：Connector 与持续更新

触发：用户主要因为数据陈旧而不再回访。

可能范围：Connector account、secret store、增量游标、行情/FX 更新和每日投影。

### C4：多用户协作

触发：出现真实的家庭、顾问或团队协作请求。

可能范围：membership、角色、授权、数据保留、删除、导出与合规策略。

### C5：AI 与 Remote MCP

触发：确定性查询和审查工作流已经稳定，用户明确需要自然语言或 Agent 集成。

顺序：只读查询 → 草稿 → 用户确认写入。所有工具继续调用相同 Application Service。

## 7. 当前明确不做

- 为可丢弃开发数据提供隐式跨数据库迁移、双写或自动回退；
- 在财富报告前建设完整登录、家庭协作或组织模型；
- 通用对象存储、任务平台或微服务拆分；
- 在确定性财务口径稳定前加入 AI 写入；
- XIRR、税务、预算、自动理财或基准指数比较；
- 为假设中的未来 Connector 提前建设平台层。

## 8. 成功指标

### 工程

- Application Service 被 CLI/Web/MCP 复用；
- PostgreSQL/SQLite repository、事务和 workspace 隔离等价测试通过；
- 导入和同步重复执行保持幂等；
- 财富恒等式在受支持范围内 100% 成立；
- 任意金额可以定位到来源、规则和修订；
- 未运行的验证有明确原因、风险和补跑命令。

### 产品

- 用户获得第一份可信报告所需时间；
- 可解释的净资产变化比例；
- 用户独立完成时间桶比较和 evidence 下钻的比例；
- 下个月主动再次使用的用户数；
- 愿意付费或要求持续服务的用户数；
- 自动更新、托管和协作请求的真实频率。

## 9. 开放决策

这些决策不阻塞下一项财富归因 feature，在对应 feature 创建时解决：

1. 长期默认云 SaaS、自托管还是双轨；
2. 何时进入需要保护真实持久化数据的阶段，并恢复 schema migration、备份和灾难恢复门禁；
3. CNY 之后是否允许 workspace 修改基础展示币种；
4. 原始输入、AI trace 和历史 evidence 的保留期限；
5. 第一批正式支持的 Connector 与券商；
6. 多用户角色和家庭协作的最小授权模型；
7. AI 是否允许读取未脱敏交易描述，以及默认隐私边界。

## 10. 文档维护规则

- 本文只维护方向、顺序和触发门槛，不加入表结构、API schema 或任务清单。
- feature 决策改变路线时，先更新对应 Spec Kit artifact，再同步本文摘要。
- 已完成实现的详细证据进入当前架构文档；历史计划由 Git history 保存。
- gstack 产品设计可以作为决策记录保留，但必须指向吸收其内容的 Spec Kit features，并停止独立演进。
