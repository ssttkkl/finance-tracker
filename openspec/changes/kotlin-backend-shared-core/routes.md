# HTTP 迁移矩阵

固定基线 `0c038a75e34264bd6d4aa5cce2070727df9d9ff7`。接口按方法与路径计数。当前 Kotlin 已接入 80/86 个操作，其中 6 个维护计划操作已完成完整合同验收；其余 6 个操作未接入。接入数不代表全部原测试或模块验收完成，证据与遗留项见 [实施任务](tasks.md)。

框架元数据入口 `GET /openapi.json` 已接通，不计入原 86 项业务操作；只列出实际注册的操作，未装配的导入和未接入 API 不出现在文档。原 `/docs`／`/redoc` 保持关闭。

计划 JSON 已统一按实际字节执行原 64 MiB 流预算，并在 IO 调度器解码／校验；确认先核对幂等键，保留严格修订及原协议错误。该修复不增加业务操作数，完整维护验收矩阵见 tasks。

| 方法 | 路径 | Python 入口 | Kotlin 状态 |
|---|---|---|---|
| POST | `/api/v1/auth/register` | `src/ft/web/access_routes.py:23` | 已实现；匿名双库 HTTP 与数据库结果对照通过 |
| POST | `/api/v1/auth/login` | `src/ft/web/access_routes.py:29` | 已实现；匿名双库 HTTP 与数据库结果对照通过 |
| POST | `/api/v1/auth/logout` | `src/ft/web/access_routes.py:35` | 已实现；匿名双库 HTTP 与数据库结果对照通过 |
| GET | `/api/v1/auth/session` | `src/ft/web/access_routes.py:38` | 首片已实现；双库黄金及真实 Netty HTTP 通过 |
| POST | `/api/v1/auth/workspaces` | `src/ft/web/access_routes.py:42` | 已实现；匿名双库 HTTP 与数据库结果对照通过 |
| POST | `/api/v1/auth/workspaces/{workspace_id}/select` | `src/ft/web/access_routes.py:47` | 已实现；匿名双库 HTTP 与数据库结果对照通过 |
| POST | `/api/v1/auth/invitations` | `src/ft/web/access_routes.py:51` | 已实现；匿名双库 HTTP 与数据库结果对照通过 |
| POST | `/api/v1/auth/invitations/{invitation_token}/accept` | `src/ft/web/access_routes.py:56` | 已实现；匿名双库 HTTP 与数据库结果对照通过 |
| GET | `/api/v1/auth/invitations/{invitation_token}` | `src/ft/web/access_routes.py:60` | 已实现；匿名双库 HTTP 与数据库结果对照通过 |
| GET | `/api/v1/auth/members` | `src/ft/web/access_routes.py:64` | 已实现；匿名双库 HTTP 与数据库结果对照通过 |
| GET | `/api/v1/auth/workspace` | `src/ft/web/access_routes.py:68` | 已实现；匿名双库 HTTP 与数据库结果对照通过 |
| PUT | `/api/v1/auth/workspace` | `src/ft/web/access_routes.py:72` | 已实现；匿名双库 HTTP 与数据库结果对照通过 |
| DELETE | `/api/v1/auth/workspace` | `src/ft/web/access_routes.py:78` | 已实现；匿名双库 HTTP、完整财务删除及分类后稳定收支清理／另一工作区保留的原服务断言验证，当前证据见 tasks |
| PUT | `/api/v1/auth/members/{user_id}` | `src/ft/web/access_routes.py:84` | 已实现；匿名双库 HTTP 与数据库结果对照通过 |
| DELETE | `/api/v1/auth/members/{user_id}` | `src/ft/web/access_routes.py:89` | 已实现；匿名双库 HTTP 与数据库结果对照通过 |
| POST | `/api/v1/accounts` | `src/ft/web/account_routes.py:160` | 已实现；匿名双库 HTTP/状态、原生 editor/修订/原键/回滚/权限及实际 Compose 解码通过；完整账户任务未完成 |
| GET | `/api/v1/accounts/by-id/{account_id}` | `src/ft/web/account_routes.py:164` | 已实现；匿名双库 HTTP/状态、原生 editor/修订/原键/回滚/权限及实际 Compose 解码通过；完整账户任务未完成 |
| PATCH | `/api/v1/accounts/by-id/{account_id}` | `src/ft/web/account_routes.py:173` | 已实现；匿名双库 HTTP/状态、原生 editor/修订/原键/回滚/权限及实际 Compose 解码通过；完整账户任务未完成 |
| DELETE | `/api/v1/accounts/by-id/{account_id}` | `src/ft/web/account_routes.py:177` | 已实现；匿名双库 HTTP/状态、原生 editor/修订/原键/回滚/权限及实际 Compose 解码通过；完整账户任务未完成 |
| POST | `/api/v1/cash-import/sessions` | `src/ft/web/cash_import_routes.py:116` | 已实现会话/文件生命周期；解析锁外、发布锁内重验权限/修订/永久收据，匿名双库 HTTP 通过；真实 R2/真实账单专项待验 |
| POST | `/api/v1/cash-import/sessions/{session_id}/files` | `src/ft/web/cash_import_routes.py:121` | 已实现会话/文件生命周期；解析锁外、发布锁内重验权限/修订/永久收据，匿名双库 HTTP 通过；真实 R2/真实账单专项待验 |
| GET | `/api/v1/cash-import/sessions/{session_id}` | `src/ft/web/cash_import_routes.py:125` | 已实现会话/文件生命周期；解析锁外、发布锁内重验权限/修订/永久收据，匿名双库 HTTP 通过；真实 R2/真实账单专项待验 |
| PATCH | `/api/v1/cash-import/sessions/{session_id}/files/{file_ref}` | `src/ft/web/cash_import_routes.py:162` | 已实现会话/文件生命周期；解析锁外、发布锁内重验权限/修订/永久收据，匿名双库 HTTP 通过；真实 R2/真实账单专项待验 |
| DELETE | `/api/v1/cash-import/sessions/{session_id}/files/{file_ref}` | `src/ft/web/cash_import_routes.py:168` | 已实现会话/文件生命周期；解析锁外、发布锁内重验权限/修订/永久收据，匿名双库 HTTP 通过；真实 R2/真实账单专项待验 |
| DELETE | `/api/v1/cash-import/sessions/{session_id}` | `src/ft/web/cash_import_routes.py:172` | 已实现会话/文件生命周期；解析锁外、发布锁内重验权限/修订/永久收据，匿名双库 HTTP 通过；真实 R2/真实账单专项待验 |
| POST | `/api/v1/cash-import/sessions/{session_id}/plans` | `src/ft/web/cash_plan_routes.py:155` | 已实现完整业务预览与共享暂存条件发布；双库 HTTP、失败保留原计划及现有 Compose 消费通过 |
| GET | `/api/v1/cash-records/{transaction_id}` | `src/ft/web/cash_plan_routes.py:160` | 已实现；双库 36 组完整响应/状态/无写入、角色与损坏状态边界、独立复核及现有 Compose 来源消费通过 |
| GET | `/api/v1/cash-records/{transaction_id}/association-targets` | `src/ft/web/cash_plan_routes.py:164` | 已实现；当前权限、原字段、关系闭包排除、ID 翻页、精确金额与本地日期，双库及现有 Compose 消费证据见 tasks |
| PATCH | `/api/v1/cash-records/{transaction_id}/draft` | `src/ft/web/cash_plan_routes.py:173` | 已实现；普通保存保持草稿／余额不变，原键永久收据与当前详情分离；双库原断言、完整 HTTP／COMMIT 故障／锁后权限及现有 Compose 消费见 tasks |
| POST | `/api/v1/cash-mutations/plans` | `src/ft/web/cash_plan_routes.py:179` | save_draft/edit_record/finalize_draft/edit_entry/resolve_issue 已接入；maintain_relations 已接当前完整组／候选、上一方案及人工关联预览，55 组 Python 检查点、错误与只读通过；来源问题服务 22 原定义／78 参数及完整只读计划、真实 HTTP／Compose 消费通过；确认独立记录 9 原定义／46 参数、完整绑定摘要及真实 HTTP／Compose 消费通过；稳定收支编辑／拆分／重新导入保护的原服务断言已补齐；完整维护合同验收通过，证据见 tasks |
| GET | `/api/v1/cash-mutations/plans/{plan_id}` | `src/ft/web/cash_plan_routes.py:184` | save_draft 及 maintain_relations 当前关系计划读取已接入；双库与实际 Compose 消费通过，完整维护合同验收完成 |
| GET | `/api/v1/cash-mutations/edit-contexts/{edit_context_id}/plan` | `src/ft/web/cash_plan_routes.py:189` | save_draft 及 maintain_relations 当前关系计划读取已接入；双库与实际 Compose 消费通过，完整维护合同验收完成 |
| GET | `/api/v1/cash-mutations/plans/{plan_id}/items` | `src/ft/web/cash_plan_routes.py:194` | 全部维护意图使用共享冻结分页，双库原发布合同和实际 Compose 消费通过 |
| GET | `/api/v1/cash-mutations/plans/{plan_id}/items/{preview_item_id}` | `src/ft/web/cash_plan_routes.py:203` | save_draft 及 maintain_relations 当前关系／人工关联明细已接入；双库与实际 Compose 消费通过，完整维护合同验收完成 |
| POST | `/api/v1/cash-mutations/plans/{plan_id}/commit` | `src/ft/web/cash_plan_routes.py:208` | edit_record/finalize_draft/save_draft/resolve_issue 已实现；双库 Python 收据/状态/问题/余额/投影 oracle、幂等重放与上下文消费通过；maintain_relations 的当前组决定与人工关联已接入，32 个原定义／98 双库参数、真实 COMMIT 故障／重放及公开 HTTP／Compose 消费通过；来源问题采用／保留／清空的 22 原定义／78 参数、事项变化／真实写后回滚及实际 Compose 消费通过；手工转账双端原 4 定义／16 参数和授权公共编辑修复新增 1 定义／16 参数、双库原子回滚及实际 Compose 消费通过；确认独立记录原 9 定义／46 参数、绑定变化拒绝、实际写后回滚和原键重放通过；草稿合并原 29 定义／88 参数、完整引用／绑定重验、实际写后回滚及 Compose 消费通过；恢复组合、依赖重验和纯输入原断言已补齐，完整维护合同验收通过 |
| GET | `/api/v1/cash-import/plans/{plan_id}` | `src/ft/web/cash_plan_routes.py:213` | 已实现冻结计划读取；双库公开视图、分页/详情、权限/收据围栏及无写入通过 |
| GET | `/api/v1/cash-import/plans/{plan_id}/items` | `src/ft/web/cash_plan_routes.py:218` | 已实现冻结计划读取；双库公开视图、分页/详情、权限/收据围栏及无写入通过 |
| GET | `/api/v1/cash-import/plans/{plan_id}/items/{preview_item_id}` | `src/ft/web/cash_plan_routes.py:227` | 已实现冻结计划读取；双库公开视图、分页/详情、权限/收据围栏及无写入通过 |
| GET | `/api/v1/cash-import/plans/{plan_id}/processing-details` | `src/ft/web/cash_plan_routes.py:232` | 已实现冻结计划读取；双库公开视图、分页/详情、权限/收据围栏及无写入通过 |
| POST | `/api/v1/cash-import/plans/{plan_id}/commit` | `src/ft/web/cash_plan_routes.py:241` | 已接入联合确认；写锁后完整重验、原子收据、同键重放、COMMIT 前后故障及原键核实，双库与现有 Compose 消费见 tasks；原组合测试仍 pending |
| GET | `/api/v1/cash-mutations/receipts/by-key` | `src/ft/web/cash_plan_routes.py:249` | 已实现；当前权限/所有者、旧键、分页与投影引用，双库及实际 Compose 解码证据见 tasks |
| GET | `/api/v1/cash-mutations/receipts/by-key/{key}` | `src/ft/web/cash_plan_routes.py:258` | 已实现；当前权限/所有者、旧键、分页与投影引用，双库及实际 Compose 解码证据见 tasks |
| GET | `/api/v1/cash-mutations/receipts/{receipt_id}` | `src/ft/web/cash_plan_routes.py:263` | 已实现；当前权限/所有者、旧键、分页与投影引用，双库及实际 Compose 解码证据见 tasks |
| GET | `/api/v1/cash-mutations/receipts/{receipt_id}/rows` | `src/ft/web/cash_plan_routes.py:279` | 已实现；当前权限/所有者、旧键、分页与投影引用，双库及实际 Compose 解码证据见 tasks |
| GET | `/api/v1/cash-mutations/receipts/{receipt_id}/relations` | `src/ft/web/cash_plan_routes.py:284` | 已实现；当前权限/所有者、旧键、分页与投影引用，双库及实际 Compose 解码证据见 tasks |
| POST | `/api/v1/cash-mutations/resolve` | `src/ft/web/cash_plan_routes.py:289` | 已接入导入/维护核实；严格输入、永久收据优先、当前权限及导入锁冲突 pending 通过；其余维护意图仍随计划迁移 |
| POST | `/api/v1/cashflow/add` | `src/ft/web/operation_routes.py:113` | 已实现；旧来源/日期/精确金额、editor、响应编码失败全回滚；双库 HTTP/13 表全列、原断言及真实 Netty 通过 |
| POST | `/api/v1/cashflow/checkin` | `src/ft/web/operation_routes.py:134` | 已实现；零金额事实、指定币种余额及快照错误顺序；双库 HTTP/13 表全列、原断言及真实 Netty 通过；Compose 无现有消费者 |
| POST | `/api/v1/cashflow/transfer` | `src/ft/web/operation_routes.py:150` | 已实现；现金/贷款/借款/证券/加密双侧事件、现金关系、余额和投影同事务；双库 92 组 HTTP/全部财务列、editor 原断言及现有 Compose 消费通过 |
| GET | `/api/v1/queries/accounts` | `src/ft/web/operation_routes.py:171` | 已实现；原账户顺序／分币种余额、实际估值及成本回退、完整 HTTP／权限／快照与双库回归通过；当前 Compose 无此旧 GET 消费者 |
| GET | `/api/v1/queries/transactions` | `src/ft/web/operation_routes.py:175` | 已实现；双库原 HTTP／十字段 DTO、UTC 月份／父和现金部分账户／分类、精确金额、1–200 条、权限／只读快照／读取故障及两个原应用定义验证，当前 Compose 无此 GET 消费者 |
| GET | `/api/v1/reports/finance` | `src/ft/web/operation_routes.py:186` | 已实现；原当前正式投影／删除来源／统计资格、80 位合计、全期流向、未初始化 503／月份 400、真实发布快照及双库回归通过；当前 Compose 无此旧 GET 消费者 |
| POST | `/api/v1/relations/aliases` | `src/ft/web/operation_routes.py:196` | 已通过原 HTTP／状态和事务合同；账户别名登记 |
| GET | `/api/v1/funding-relations/pending` | `src/ft/web/operation_routes.py:211` | 已接入；双库完整验证通过 |
| POST | `/api/v1/funding-relations/scan` | `src/ft/web/operation_routes.py:218` | 已接入；双库完整验证通过 |
| POST | `/api/v1/funding-relations/{relation_id}/confirm` | `src/ft/web/operation_routes.py:225` | 已接入；双库完整验证通过 |
| POST | `/api/v1/funding-relations/{relation_id}/reject` | `src/ft/web/operation_routes.py:238` | 已接入；双库完整验证通过 |
| POST | `/api/v1/investment-events` | `src/ft/web/operation_routes.py:251` | 未迁移 |
| POST | `/api/v1/investment-import` | `src/ft/web/operation_routes.py:298` | 未迁移 |
| POST | `/api/v1/operations/sync` | `src/ft/web/operation_routes.py:339` | 未迁移 |
| GET | `/api/v1/operations/projections` | `src/ft/web/operation_routes.py:371` | 已实现；当前工作区管理员、原状态字段/缺省和固定失败合同，双库证据见 tasks |
| POST | `/api/v1/operations/projections/rebuild` | `src/ft/web/operation_routes.py:381` | 已实现；双库实际发布/完整状态、真实 HTTP、领域/运行错误及锁等待撤权验证通过 |
| GET | `/api/v1/accounts` | `src/ft/web/routes.py:56` | 已实现；匿名双库 HTTP/状态、原生 editor/修订/原键/回滚/权限及实际 Compose 解码通过；完整账户任务未完成 |
| GET | `/api/v1/cash-projections` | `src/ft/web/routes.py:62` | 已实现；完整筛选、游标／序号分页、版本围栏、全局选项与精确月汇总，双库及现有 Compose 消费证据见 tasks |
| GET | `/api/v1/evidence/cash-projections/{projection_id}` | `src/ft/web/routes.py:70` | 已实现；双库完整响应/财务快照/权限边界、现有 Compose 消费与独立复核通过，完整组合原测试仍 pending |
| GET | `/api/v1/cash-projections/{projection_id}` | `src/ft/web/routes.py:75` | 已实现；双库完整响应/财务快照/权限边界、现有 Compose 消费与独立复核通过，完整组合原测试仍 pending |
| GET | `/api/v1/cash-records/{transaction_id}/projection` | `src/ft/web/routes.py:86` | 已实现；双库完整响应/财务快照/权限边界、现有 Compose 消费与独立复核通过，完整组合原测试仍 pending |
| GET | `/api/v1/investment-events` | `src/ft/web/routes.py:181` | 已通过原筛选／版本游标／精确金额及资金关系读取，双库完整 HTTP／只读／故障与现有 Compose 消费验收 |
| GET | `/api/v1/evidence/investment-events/{event_id:path}` | `src/ft/web/routes.py:191` | 已通过原来源身份／原文白名单／工作区隔离，双库完整 HTTP／只读／故障与现有 Compose 消费验收 |
| GET | `/api/v1/investment-portfolio` | `src/ft/web/routes.py:199` | 未迁移 |
| GET | `/api/v1/investment-portfolio/stream` | `src/ft/web/routes.py:212` | 未迁移 |
| POST | `/api/v1/investment-portfolio/refresh` | `src/ft/web/routes.py:231` | 未迁移 |
| GET | `/api/v1/cash-categories` | `src/ft/web/routes.py:97` | 首片已实现；双库黄金及真实 Netty HTTP 通过 |
| POST | `/api/v1/cash-categories` | `src/ft/web/routes.py:101` | 已实现；目录切片双库合同与 Compose 回包消费见 tasks，完整分类功能已迁移 |
| PATCH | `/api/v1/cash-categories/{category_id}` | `src/ft/web/routes.py:112` | 已实现；保留字段缺省/null/父级语义，同步修复后代深度；投影调用边界及分类完整功能已复核 |
| POST | `/api/v1/cash-categories/{category_id}/reorder` | `src/ft/web/routes.py:124` | 已实现；目录修订、边界无操作与兄弟顺序/时间戳，专项证据见 tasks |
| GET | `/api/v1/cash-categories/{category_id}/deletion-impact` | `src/ft/web/routes.py:135` | 已实现；双库完整响应/财务状态、原生回滚/撤权/性能与实际 Compose 确认协议通过 |
| DELETE | `/api/v1/cash-categories/{category_id}` | `src/ft/web/routes.py:142` | 已实现；双库完整响应/财务状态、原生回滚/撤权/性能与实际 Compose 确认协议通过 |
| PUT | `/api/v1/cash-projections/categories` | `src/ft/web/routes.py:157` | 已实现；完整成员/版本/人工保护/原子回滚及当前授权，双库 HTTP/状态和实际 Compose 解码通过 |
| PUT | `/api/v1/cash-projections/{projection_id}/category` | `src/ft/web/routes.py:169` | 已实现；完整成员/版本/人工保护/原子回滚及当前授权，双库 HTTP/状态和实际 Compose 解码通过 |
| GET | `/api/v1/cash-ledger/options` | `src/ft/web/routes.py:238` | 已实现；完整选项/合法子类型/标签/顺序、读权限及真实 Compose 消费，双库证据见 tasks |
| GET | `/api/v1/cash-records` | `src/ft/web/routes.py:242` | 已实现；原时间／编号游标、页长压到 1–20、文字／本地日期、人工／草稿、精确字段及错误；双库原四定义七参数、完整 HTTP／无写入／快照与权限回归通过；当前 Compose 无实际调用者 |
| POST | `/api/v1/cash-records` | `src/ft/web/routes.py:258` | 已实现；严格公开输入、editor、完整现金部分/余额/稳定收支/投影原子保存，双库 HTTP/全部财务列及现有 Compose 消费通过；OpenAPI schema 保持 pending |
| DELETE | `/api/v1/cash-records/{fact_id}` | `src/ft/web/routes.py:273` | 已实现；仅删当前／删除确认闭包，清理父端点、未确认决定组及候选，保留原文／金额／现金部分，其余关系、余额和投影同事务维护；旧路径当前无实际 Compose 消费者 |
| POST | `/api/v1/cash-projections/delete-impact` | `src/ft/web/routes.py:286` | 已实现；原选择／活动版本／完整成员与影响计数，只读快照及返回前权限复核，双库 HTTP 和现有 Compose 消费通过 |
| DELETE | `/api/v1/cash-projections` | `src/ft/web/routes.py:299` | 已实现；明确确认、锁后权限／版本重验，完整成员、引用／余额／稳定收支与投影原子删除，公开回包省略内部事实编号；双库原断言、真实锁等待、回滚及现有 Compose 消费通过 |
