# HTTP 迁移矩阵

固定基线 `0c038a75e34264bd6d4aa5cce2070727df9d9ff7`。状态仅在相应 HTTP/错误/双库证据完成后更新。

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
| DELETE | `/api/v1/auth/workspace` | `src/ft/web/access_routes.py:78` | 已实现；匿名双库 HTTP 与数据库结果对照通过；完整财务数据删除专项待验收 |
| PUT | `/api/v1/auth/members/{user_id}` | `src/ft/web/access_routes.py:84` | 已实现；匿名双库 HTTP 与数据库结果对照通过 |
| DELETE | `/api/v1/auth/members/{user_id}` | `src/ft/web/access_routes.py:89` | 已实现；匿名双库 HTTP 与数据库结果对照通过 |
| POST | `/api/v1/accounts` | `src/ft/web/account_routes.py:160` | 已实现；匿名双库 HTTP/状态、原生 editor/修订/原键/回滚/权限及实际 Compose 解码通过；完整账户任务未完成 |
| GET | `/api/v1/accounts/by-id/{account_id}` | `src/ft/web/account_routes.py:164` | 已实现；匿名双库 HTTP/状态、原生 editor/修订/原键/回滚/权限及实际 Compose 解码通过；完整账户任务未完成 |
| PATCH | `/api/v1/accounts/by-id/{account_id}` | `src/ft/web/account_routes.py:173` | 已实现；匿名双库 HTTP/状态、原生 editor/修订/原键/回滚/权限及实际 Compose 解码通过；完整账户任务未完成 |
| DELETE | `/api/v1/accounts/by-id/{account_id}` | `src/ft/web/account_routes.py:177` | 已实现；匿名双库 HTTP/状态、原生 editor/修订/原键/回滚/权限及实际 Compose 解码通过；完整账户任务未完成 |
| POST | `/api/v1/cash-import/sessions` | `src/ft/web/cash_import_routes.py:116` | 未迁移 |
| POST | `/api/v1/cash-import/sessions/{session_id}/files` | `src/ft/web/cash_import_routes.py:121` | 未迁移 |
| GET | `/api/v1/cash-import/sessions/{session_id}` | `src/ft/web/cash_import_routes.py:125` | 未迁移 |
| PATCH | `/api/v1/cash-import/sessions/{session_id}/files/{file_ref}` | `src/ft/web/cash_import_routes.py:162` | 未迁移 |
| DELETE | `/api/v1/cash-import/sessions/{session_id}/files/{file_ref}` | `src/ft/web/cash_import_routes.py:168` | 未迁移 |
| DELETE | `/api/v1/cash-import/sessions/{session_id}` | `src/ft/web/cash_import_routes.py:172` | 未迁移 |
| POST | `/api/v1/cash-import/sessions/{session_id}/plans` | `src/ft/web/cash_plan_routes.py:155` | 未迁移 |
| GET | `/api/v1/cash-records/{transaction_id}` | `src/ft/web/cash_plan_routes.py:160` | 已实现；双库 36 组完整响应/状态/无写入、角色与损坏状态边界、独立复核及现有 Compose 来源消费通过 |
| GET | `/api/v1/cash-records/{transaction_id}/association-targets` | `src/ft/web/cash_plan_routes.py:164` | 未迁移 |
| PATCH | `/api/v1/cash-records/{transaction_id}/draft` | `src/ft/web/cash_plan_routes.py:173` | 未迁移 |
| POST | `/api/v1/cash-mutations/plans` | `src/ft/web/cash_plan_routes.py:179` | save_draft/edit_record/finalize_draft 已实现；双库 Python 视图/单项对照、错误与无写入通过；其余意图未完成 |
| GET | `/api/v1/cash-mutations/plans/{plan_id}` | `src/ft/web/cash_plan_routes.py:184` | save_draft 计划读取已实现；双库通过，其余意图未完成 |
| GET | `/api/v1/cash-mutations/edit-contexts/{edit_context_id}/plan` | `src/ft/web/cash_plan_routes.py:189` | save_draft 计划读取已实现；双库通过，其余意图未完成 |
| GET | `/api/v1/cash-mutations/plans/{plan_id}/items` | `src/ft/web/cash_plan_routes.py:194` | save_draft 分页已实现；双库通过，其余意图未完成 |
| GET | `/api/v1/cash-mutations/plans/{plan_id}/items/{preview_item_id}` | `src/ft/web/cash_plan_routes.py:203` | save_draft 单项详情已实现；双库通过，其余意图未完成 |
| POST | `/api/v1/cash-mutations/plans/{plan_id}/commit` | `src/ft/web/cash_plan_routes.py:208` | 未迁移 |
| GET | `/api/v1/cash-import/plans/{plan_id}` | `src/ft/web/cash_plan_routes.py:213` | 未迁移 |
| GET | `/api/v1/cash-import/plans/{plan_id}/items` | `src/ft/web/cash_plan_routes.py:218` | 未迁移 |
| GET | `/api/v1/cash-import/plans/{plan_id}/items/{preview_item_id}` | `src/ft/web/cash_plan_routes.py:227` | 未迁移 |
| GET | `/api/v1/cash-import/plans/{plan_id}/processing-details` | `src/ft/web/cash_plan_routes.py:232` | 未迁移 |
| POST | `/api/v1/cash-import/plans/{plan_id}/commit` | `src/ft/web/cash_plan_routes.py:241` | 未迁移 |
| GET | `/api/v1/cash-mutations/receipts/by-key` | `src/ft/web/cash_plan_routes.py:249` | 已实现；当前权限/所有者、旧键、分页与投影引用，双库及实际 Compose 解码证据见 tasks |
| GET | `/api/v1/cash-mutations/receipts/by-key/{key}` | `src/ft/web/cash_plan_routes.py:258` | 已实现；当前权限/所有者、旧键、分页与投影引用，双库及实际 Compose 解码证据见 tasks |
| GET | `/api/v1/cash-mutations/receipts/{receipt_id}` | `src/ft/web/cash_plan_routes.py:263` | 已实现；当前权限/所有者、旧键、分页与投影引用，双库及实际 Compose 解码证据见 tasks |
| GET | `/api/v1/cash-mutations/receipts/{receipt_id}/rows` | `src/ft/web/cash_plan_routes.py:279` | 已实现；当前权限/所有者、旧键、分页与投影引用，双库及实际 Compose 解码证据见 tasks |
| GET | `/api/v1/cash-mutations/receipts/{receipt_id}/relations` | `src/ft/web/cash_plan_routes.py:284` | 已实现；当前权限/所有者、旧键、分页与投影引用，双库及实际 Compose 解码证据见 tasks |
| POST | `/api/v1/cash-mutations/resolve` | `src/ft/web/cash_plan_routes.py:289` | 未迁移 |
| POST | `/api/v1/cashflow/add` | `src/ft/web/operation_routes.py:113` | 已实现；旧来源/日期/精确金额、editor、响应编码失败全回滚；双库 HTTP/13 表全列、原断言及真实 Netty 通过 |
| POST | `/api/v1/cashflow/checkin` | `src/ft/web/operation_routes.py:134` | 已实现；零金额事实、指定币种余额及快照错误顺序；双库 HTTP/13 表全列、原断言及真实 Netty 通过；Compose 无现有消费者 |
| POST | `/api/v1/cashflow/transfer` | `src/ft/web/operation_routes.py:150` | 已实现；现金/贷款/借款/证券/加密双侧事件、现金关系、余额和投影同事务；双库 92 组 HTTP/全部财务列、editor 原断言及现有 Compose 消费通过 |
| GET | `/api/v1/queries/accounts` | `src/ft/web/operation_routes.py:171` | 未迁移 |
| GET | `/api/v1/queries/transactions` | `src/ft/web/operation_routes.py:175` | 未迁移 |
| GET | `/api/v1/reports/finance` | `src/ft/web/operation_routes.py:186` | 未迁移 |
| POST | `/api/v1/relations/aliases` | `src/ft/web/operation_routes.py:196` | 未迁移 |
| GET | `/api/v1/funding-relations/pending` | `src/ft/web/operation_routes.py:211` | 未迁移 |
| POST | `/api/v1/funding-relations/scan` | `src/ft/web/operation_routes.py:218` | 未迁移 |
| POST | `/api/v1/funding-relations/{relation_id}/confirm` | `src/ft/web/operation_routes.py:225` | 未迁移 |
| POST | `/api/v1/funding-relations/{relation_id}/reject` | `src/ft/web/operation_routes.py:238` | 未迁移 |
| POST | `/api/v1/investment-events` | `src/ft/web/operation_routes.py:251` | 未迁移 |
| POST | `/api/v1/investment-import` | `src/ft/web/operation_routes.py:298` | 未迁移 |
| POST | `/api/v1/operations/sync` | `src/ft/web/operation_routes.py:339` | 未迁移 |
| GET | `/api/v1/operations/projections` | `src/ft/web/operation_routes.py:371` | 已实现；当前工作区管理员、原状态字段/缺省和固定失败合同，双库证据见 tasks |
| POST | `/api/v1/operations/projections/rebuild` | `src/ft/web/operation_routes.py:381` | 已实现；双库实际发布/完整状态、真实 HTTP、领域/运行错误及锁等待撤权验证通过 |
| GET | `/api/v1/accounts` | `src/ft/web/routes.py:56` | 已实现；匿名双库 HTTP/状态、原生 editor/修订/原键/回滚/权限及实际 Compose 解码通过；完整账户任务未完成 |
| GET | `/api/v1/cash-projections` | `src/ft/web/routes.py:62` | 未迁移 |
| GET | `/api/v1/evidence/cash-projections/{projection_id}` | `src/ft/web/routes.py:70` | 已实现；双库功能/实际客户端验证，最终独立审查待完成 |
| GET | `/api/v1/cash-projections/{projection_id}` | `src/ft/web/routes.py:75` | 已实现并通过 SQLite/PG18 功能、快照/权限交错及现有 Compose 成员消费；最终独立审查待完成，暂不计已验收 |
| GET | `/api/v1/cash-records/{transaction_id}/projection` | `src/ft/web/routes.py:86` | 已实现并通过 SQLite/PG18 功能、隐藏/缺失/跨工作区边界及真实 Netty；最终独立审查待完成，暂不计已验收 |
| GET | `/api/v1/investment-events` | `src/ft/web/routes.py:181` | 未迁移 |
| GET | `/api/v1/evidence/investment-events/{event_id:path}` | `src/ft/web/routes.py:191` | 未迁移 |
| GET | `/api/v1/investment-portfolio` | `src/ft/web/routes.py:199` | 未迁移 |
| GET | `/api/v1/investment-portfolio/stream` | `src/ft/web/routes.py:212` | 未迁移 |
| POST | `/api/v1/investment-portfolio/refresh` | `src/ft/web/routes.py:231` | 未迁移 |
| GET | `/api/v1/cash-categories` | `src/ft/web/routes.py:97` | 首片已实现；双库黄金及真实 Netty HTTP 通过 |
| POST | `/api/v1/cash-categories` | `src/ft/web/routes.py:101` | 已实现；目录切片双库合同与 Compose 回包消费见 tasks，分类完整模块仍未完成 |
| PATCH | `/api/v1/cash-categories/{category_id}` | `src/ft/web/routes.py:112` | 已实现；保留字段缺省/null/父级语义，同步修复后代深度；完整投影验收待后续切片 |
| POST | `/api/v1/cash-categories/{category_id}/reorder` | `src/ft/web/routes.py:124` | 已实现；目录修订、边界无操作与兄弟顺序/时间戳，专项证据见 tasks |
| GET | `/api/v1/cash-categories/{category_id}/deletion-impact` | `src/ft/web/routes.py:135` | 已实现；双库完整响应/财务状态、原生回滚/撤权/性能与实际 Compose 确认协议通过 |
| DELETE | `/api/v1/cash-categories/{category_id}` | `src/ft/web/routes.py:142` | 已实现；双库完整响应/财务状态、原生回滚/撤权/性能与实际 Compose 确认协议通过 |
| PUT | `/api/v1/cash-projections/categories` | `src/ft/web/routes.py:157` | 已实现；完整成员/版本/人工保护/原子回滚及当前授权，双库 HTTP/状态和实际 Compose 解码通过 |
| PUT | `/api/v1/cash-projections/{projection_id}/category` | `src/ft/web/routes.py:169` | 已实现；完整成员/版本/人工保护/原子回滚及当前授权，双库 HTTP/状态和实际 Compose 解码通过 |
| GET | `/api/v1/cash-ledger/options` | `src/ft/web/routes.py:238` | 已实现；完整选项/合法子类型/标签/顺序、读权限及真实 Compose 消费，双库证据见 tasks |
| GET | `/api/v1/cash-records` | `src/ft/web/routes.py:242` | 未迁移 |
| POST | `/api/v1/cash-records` | `src/ft/web/routes.py:258` | 已实现；严格公开输入、editor、完整现金部分/余额/稳定收支/投影原子保存，双库 HTTP/全部财务列及现有 Compose 消费通过；OpenAPI schema 保持 pending |
| DELETE | `/api/v1/cash-records/{fact_id}` | `src/ft/web/routes.py:273` | 未迁移 |
| POST | `/api/v1/cash-projections/delete-impact` | `src/ft/web/routes.py:286` | 未迁移 |
| DELETE | `/api/v1/cash-projections` | `src/ft/web/routes.py:299` | 未迁移 |
