# 原始账单导入、关系与同步

可执行行为以 `specs/` 与代码为准。本文描述 **015 之后** 的运行时语义：无文件 reconcile、无 converted-CSV `append`、无独立 import/raw 作业表。

## 命令

### 一步导入（主路径）

```bash
# 现金：禁止 --account；账户由账单字段 + ~/.ft/mapping.yaml 推断
ft import FILE --source alipay|wechat|icbc|icbc-debit|ccb-debit|icbc-asia \
  [--currency CURRENCY] [--password-file FILE]

# 投资：必须 --account（security 或 crypto）
ft import FILE --source dfzq|ibkr|schwab|usmart-hk \
  --account NAME [--currency CURRENCY] [--password-file FILE]
```

- `--currency` 可选：仅当行内缺省币种时回退；任意 3 位字母码，归一大写。
- 密码只允许 `--password-file`（不写 argv）。
- 同一文件可路由到多个现金账户；整批在一个 UnitOfWork 内提交。

### 解析预览（非账本）

```bash
ft convert FILE --source alipay -o preview.csv
ft stock convert FILE --source dfzq -o preview.csv
```

导出不被读取为账本；正式写入仍用 `ft import` / `ft sync` / 手动命令。

### 连接器同步（018）

```bash
ft sync --source binance|kraken|okx|polymarket --account NAME [--full] [--batch-size N]
```

凭据：`~/.ft/credentials.yaml`。增量游标：`sync_cursors`（`workspace_id + account_id + source_type`）。失败 fail-closed，不留部分事件/游标。

### 关系审查（006+）

```bash
ft relations pending [--kind …]
ft relations check [--fact-id …] [--batch-id …]
ft relations accept|reject <relation_id> […]
ft relations alias-add --type card_tail --value 1234 --account '某储蓄卡'
ft relations alias-add --type account_identifier --value 6222000000001234 --account '某储蓄卡'
ft fact-delete <fact_id> --reason '…'
```

`card_tail` 仅接受四位尾号；`account_identifier` 用于完整数字账号。它们是用户显式登记的本人账户标识：关系扫描必须同时读取导入期生成的 `counterparty_account` 与 `counterparty_account_attrs`，按完整、尾号、掩码或严格重建语义匹配。属性缺失、未知、重复、顺序非法或与账号表示矛盾时忽略账号证据，不得按来源、字符串长度或掩码字符补造属性。账号命中只参与运行时筛选，不写入关系记录；别名冲突时不得自动确认关系。

## 事务语义（015 后）

```text
原始文件或 API 页
  → parser / connector 映射为行或投资事件
  → 幂等键：source_type × record_id（渠道名 × 业务行键）
  → 已存在则跳过；新行写入 cash_transactions 或 investment_events
       （现金 source_payload 仅为该业务行的完整原始列和值；无 raw_records 表）
  → 投资：apply_investment_event → ledger_snapshots + 校验
  → 现金导入后可触发 relations check（镜像 / 转账 / 退款等）
  → 同步成功则 upsert sync_cursors
  → COMMIT

任一行映射/校验/分页失败 → 回滚本批；无部分正式事实或游标
```

报告与持仓只消费 **未逻辑删除的活跃事实** 与 **已 accept 的关系**。配对不物理删事实、不改写金额；历史重复用 `fact-delete` 逻辑删除。

## Provider 与时间

| Source | 输入 | 无 offset 时间 |
|---|---|---|
| Alipay / WeChat / ICBC / CCB / DFZQ 等文件 | CSV/XLSX/PDF/… | Asia/Shanghai |
| ccxt 交易所 / Polymarket Activity | API | 连接器规范（见 018） |

带 offset 的时间保留瞬时；事实侧统一可查询的 UTC 存储，分桶按 workspace 时区。

## Mapping

`~/.ft/mapping.yaml`（缺失时建默认模板）。匹配优先更长 `fnmatch`；`default: error|fail` 时未匹配整批失败。

工银亚洲活期账户账单使用 `icbc_asia` 作为持久化导入渠道。样本未提供可用的下挂账户尾号时，使用通用规则：

```yaml
rules:
  - source: icbc_asia
    match: "工银亚洲活期账户"
    account: "工银亚洲账户"
    currency: HKD
```

若账单提供完整币种子账号，可用更高优先级的 `source: icbc_asia_<规范账号尾号>`、`match: "*"` 规则，其中规范账号尾号为完整子账号末位标准化为 `0` 后的最后四位。例如 `…74240` 和 `…74241` 都使用 `4240`。相同规范账号的不同币种子账号会路由到同一账本账户；完整子账号与账单币种仍参与业务行键。账单内币种是权威值，账户必须支持相应币种；映射中的 `currency` 只作为既有路由配置字段。

## 失败与幂等

- 文件/解析/密码/工具缺失：非零退出，不写库。
- 账户不存在、类型不符、币种非法、金额非有限或超精度：整批回滚。
- 无法唯一保留完整原始业务行（例如表头为空、重复或行列数不一致）：整批回滚；不以标准化字段补造 `source_payload`。
- 重复 `(workspace, source_type, record_id)`：跳过，不重复发布。
- API 可重试错误：有限次退避后仍失败则本批不提交。

## 来源行与对方账号

`source_payload` 保存原始账单中该业务行的全部列名和值，空值列也必须保留。它不保存来源文件路径、整份文件、解析结果、账户映射结果或关系处理字段。

现金流水的 `counterparty_account` 是独立正式列，仅从账单直接提供且可归属到当前业务行的对方账号、到账卡或账户标识提取并在导入期规范化。`counterparty_account_attrs` 与账号成对生成，合法组合为 `[]`、`["full"]`、`["tail"]`、`["masked"]` 和 `["masked", "reconstructed"]`；新导入的非空账号缺少合法属性时整批回滚。可识别的掩码或非数字标识保留在正式字段中，即使当前没有可用的本人账户别名；来源未提供账号或只提供空值标记时保存空字符串与空数组。

来源提取使用专用结构：支付宝读取 `对方账号`；微信仅将 `零钱提现` 的到账卡作为对方账号；建行拆分 `对方账号与户名`；工行借记卡和工银亚洲读取对方账号专列；工行信用卡仅从明确的 `转帐` / `转账` 业务行提取独立掩码账号单元。付款方式、本方卡号、对方户名、商户名、摘要、备注和账户映射不得补造对方账号。原始值始终保留在同一行的 `source_payload`，账号属于隐私数据，错误信息、日志和测试夹具不得回显真实账号。

升级前已经丢失原始列的历史快照保持原样。迁移只从既有规范数字值和可证明的完整来源行回填账号与属性；无法证明属性的历史非空账号保留原值和空属性，不参与账号匹配。迁移不伪造完整来源行，也不会自动重导 `~/.ft/bills` 中的账单。

## 关系种类（摘要）

导入提交后可能产生：

- `payment_mirror` — 平台与银行同一支付
- `transfer_pair`（可含 `credit_repayment` 子类型）
- `refund_offset` — 退款核销

扫描分 Phase（硬键退款 → mirror → transfer → 弱退款/diamond）；细节见 `statement-source-onboarding` skill 与 `006`/`007`/`008` specs。
