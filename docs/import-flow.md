# 原始账单导入、关系与同步

可执行行为以 `openspec/specs/` 与代码为准。本文描述 015 之后的运行时语义：没有文件 reconcile、converted-CSV append 或独立 raw 作业表。Web 与 Expo 使用相同的导入 API 和导入会话。

## 一步导入（主路径）

客户端调用以下接口，顺序为：

```text
POST /api/v1/cash-import/detect
POST /api/v1/cash-import/scan
POST /api/v1/cash-import/preview
POST /api/v1/cash-import/commit
```

文件通过 `application/octet-stream` 上传，文件名、来源和可选币种放在查询参数；确认阶段也可以使用 JSON `content_base64`。账单密码通过 `X-FT-Statement-Password` 请求头传递，不放进 URL、日志或持久化对象。确认导入必须携带 `Idempotency-Key`。

- 现金账单：账户由账单字段和 workspace 映射决定，客户端不覆盖账户。
- 投资账单：确认时选择 `security` 或 `crypto` 账户。
- `currency` 只在行内缺省币种时作为回退；有效值为任意 3 位字母码并归一为大写。
- 同一文件可以路由到多个现金账户；整批在一个 Unit of Work 内提交。
- `convert` 不再是独立用户步骤，预览直接留在导入会话中。

## 事务语义

```text
原始文件或 API 页
  → parser / connector 映射为行或投资事件
  → 幂等键：source_type × record_id（渠道名 × 业务行键）
  → 已存在则跳过；新行写入 cash_transactions 或 investment_events
       （现金 source_payload 仅保存该业务行的完整原始列和值；无 raw_records 表）
  → 投资：apply_investment_event → ledger_snapshots + 校验
  → 现金导入后可触发关系检查（镜像 / 转账 / 退款等）
  → 同步成功则 upsert sync_cursors
  → COMMIT

任一行映射、校验、分页或账户决策失败 → 回滚本批；无部分正式事实或游标
```

报告与持仓只消费未逻辑删除的活跃事实与已确认关系。配对不物理删除事实、不改写金额；历史重复使用 Web/Expo 的事实删除确认流程。

## 连接器同步

同步接口为 `POST /api/v1/operations/sync`，仅管理员可调用。请求只包含 `source`、`account`、`full` 和 `batch_size`；交易所/Polymarket 凭据从后端受控配置加载，客户端永远不接触密钥。增量游标为 `workspace_id + account_id + source_type`，拉取、映射、事件写入和游标更新保持同一事务语义。

首批 provider：`binance`、`kraken`、`okx`、`polymarket`。失败 fail-closed，不留部分事件或游标。

## 关系审查

关系接口如下：

```text
GET  /api/v1/relations/pending
POST /api/v1/relations/check
POST /api/v1/relations/{relation_id}/accept
POST /api/v1/relations/{relation_id}/reject
POST /api/v1/relations/aliases
DELETE /api/v1/cash-facts/{fact_id}
```

资金调拨关系使用 `/api/v1/funding-relations/*`，包括 `scan`、`pending`、`confirm` 和 `reject`。`card_tail` 只接受四位尾号；`account_identifier` 用于完整数字账号。别名冲突时不得自动确认关系。

导入关系决策包含 proposal key、关系种类、子类型、规则版本、两端事实和 `accepted` / `rejected` 状态。服务端会检查预览 digest 与当前关系上下文；过期、冲突或重复提交返回可识别错误，不伪造成功。

## Provider、Mapping 与时间

| Source | 输入 | 无 offset 时间 |
|---|---|---|
| Alipay / WeChat / ICBC / CCB / DFZQ 等文件 | CSV/XLSX/PDF | Asia/Shanghai |
| ccxt 交易所 / Polymarket Activity | API | 连接器规范 |

带 offset 的时间保留瞬时；事实侧统一保存为 UTC，分桶使用 workspace 时区。

历史 mapping 文件可能位于受控本地配置中。匹配优先更长 `fnmatch`；`default: error|fail` 时未匹配整批失败。映射只决定账户路由，不覆盖账单行的权威币种。

## 失败、精度与溯源

- 文件、解析、密码或工具缺失：API 返回安全错误，不写库。
- 账户不存在、类型不符、币种非法、金额非有限或超精度：整批回滚。
- 无法保留完整原始业务行：整批回滚，不以标准化字段补造 `source_payload`。
- 重复 `(workspace, source_type, record_id)`：跳过，不重复发布。
- 所有金额、数量和成本保持精确十进制文本；客户端不使用浮点计算账务。
- `source_payload` 保存该业务行原始列和值，不保存来源路径、整份文件、映射结果或关系处理字段。
- 账号隐私数据不得回显到错误信息、日志或测试夹具。

关系种类包括 `payment_mirror`、`transfer_pair`（可含 `credit_repayment`）和 `refund_offset`；扫描细节见对应 statement-source skill 与 006/007/008 specs。
