## Why

个人记账场景下账本数据本可完整落在端侧，但当前全部解析、类型分类、查重、账户归属、关系配对与投影计算都在服务端完成，客户端每次浏览还要联网取分页投影；服务端因此既承担不必要的算力，又保存了本可省去的存储（物化投影、导入暂存与原文）。Kotlin 迁移完成后共享 KMP 模块（`business-contracts`/`business-domain`）已就位，具备把导入计算与账本缓存前移到端侧、让服务端退化为"规范事实持久化 + 全量同步"后端的基础，从而降低服务端算力与存储成本，并让导入更快、可离线。

本变更以 Kotlin 架构为基线、彻底废弃 Python，作为迁移完成后的独立变更落地；它不改动 `kotlin-backend-shared-core` 的 parity 目标，而是在其之上重定义目标契约。迁移期间仍按既有 parity 推进，届时被本变更删除的接口与测试随本变更标废。

## What Changes

- 端侧新增**全历史规范事实缓存**（本地索引、加密存储、登出清理）。刷新时按工作区 `ledger_seq` 判断，版本变化即整包重下全量快照；本期不做变更日志/差量，但保留可平滑升级为差量的形状。**BREAKING**：客户端不再依赖服务端分页投影取数。
- 导入阶段 1–7（解析、行归一化/类型分类、对缓存账本查重、账户归属、操作计划、候选召回、关系配对）**迁移到端侧执行**，端侧提交**完整写集**（transactions/components、relations/candidates、entries/members、issues、mappings、bindings）；服务端只校验结构与不变量、落库、发收据，**不重跑匹配启发式**。**BREAKING**：导入由"上传文件→服务端计划→确认"改为"端侧计算→提交增量"。
- 服务端不再物化投影：删除 `cash_projection_*` 及 `GET /cash-projections`、投影详情/证据、`operations/projections` 等读取面；浏览、筛选、分页、月度汇总与聚合全部由端侧用共享 KMP 规则派生。**BREAKING**。
- 新增服务端合同：工作区 `ledger_seq`（一次提交一个号）、轻量版本查询、全量事实快照接口（只读事务锁定版本一致读）。**BREAKING**（公共 API 增删）。
- 并发：v1 采用**整工作区基线（L1）**，提交时基线过期即整批拒绝；端侧基于稳定输入（文件 + 账户映射 + 行/entry/配对决策，均按稳定引用寻址）重派并复用决策，**结论指纹一致则静默重提交**，否则提示"已发生变更，请重新核对"。协议保留可平滑收窄为作用域基线（L3）的形状。
- 幂等两层：数据层（来源身份 + 确定性指纹，含 relations）+ 提交层（`Idempotency-Key` + 收据 + `resolve`）。
- 解析边界：端侧支持 CSV/XLSX；PDF 走服务端 **parse-only** 端点（只解析返回归一化行，不读账本、不落库）。
- 算法下沉 `business-domain` 共享 KMP，三端与服务端**共用一份实现**，避免多份漂移；投影与余额规则一并共享。
- Web 端用 **OPFS + SQLDelight-wasm** 持久化；端侧缓存加密、登出清缓存。
- 余额保持服务端派生维护、**端侧不缓存**（记为后续可砍项，不在本次范围）。
- 投资本轮不纳入：仅**只读缓存** `cash_investment_funding_relations` 及其中已接受且生效的关联资金记录之最小投资端字段（账户、原币种、原资金金额），用于现金匹配与银证转账双端展示；不缓存完整投资事件、不做任何投资计算。

## Capabilities

### New Capabilities
- `shared/offline-ledger-cache`: 端侧全历史规范事实缓存、本地索引、加密与登出清理，以及按版本判断与全量快照替换、端侧派生投影与聚合的行为合同。
- `shared/ledger-snapshot-sync`: 服务端工作区 `ledger_seq`、轻量版本查询与全量事实快照接口（一致读锁定版本）的合同。
- `import/local-import-pipeline`: 端侧导入阶段 1–7、完整写集提交合同、稳定输入重派与决策复用、数据层与提交层幂等，以及 PDF parse-only 协作。

### Modified Capabilities
- `import/cash-import-session`: 导入会话、预览与确认的职责调整——计算移至端侧，服务端只校验不变量与落库。
- `cash-ledger/cash-ledger-browser`: 收支投影与浏览读取改为端侧派生，服务端不再提供物化投影。

## Impact

- **代码**：`compose/backend`（删除投影物化与相关接口、新增 `ledger_seq`/版本查询/快照与写集校验落库、保留 parse-only）、`compose/shared`（新增本地库、索引、加密、快照同步、端侧导入与派生）、`compose/business-domain`/`business-contracts`（下沉配对/投影/余额规则与写集 DTO）。
- **接口**：删除 `GET /cash-projections`（含详情/证据）、`operations/projections(+rebuild)` 等服务端投影读取面；新增版本查询与全量快照接口、端侧写集提交入口、PDF parse-only 入口。属公共契约变更。
- **数据与迁移**：删除 `cash_projection_*` 物化表；schema 仍由 Alembic 独占，Kotlin 不改 schema。`ledger_seq` 需要持久化的工作区版本来源。删除为破坏性且未上线，可接受；无历史数据迁移。
- **依赖**：端侧新增 SQLDelight（含 wasm/OPFS）与平台安全存储用于缓存密钥。
- **文档与规格**：需同步 `docs/feature-map.md`、`DOMAIN_GLOSSARY.md`、cash 导入流程文档，以及迁移变更中被本变更覆盖的 parity 项。
- **非目标**：不做变更日志/差量同步（保留升级口）；不砍余额能力；不纳入投资导入/估值算法；不做多人协同或自动合并。
- **回滚**：本次为未上线产品的破坏性重设计，回滚以版本回退迁移后的 parity 基线与服务端投影物化为准；端侧缓存可整体丢弃并重下快照，不产生服务端孤儿数据。
