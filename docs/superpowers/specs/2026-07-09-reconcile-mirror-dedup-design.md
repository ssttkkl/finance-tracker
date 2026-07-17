# Reconcile Mirror Dedup Design

**Status:** approved in chat on 2026-07-09

## Goal

重构 reconcile 的重复冗余识别逻辑：当同一笔交易同时出现在支付宝/微信与银行卡/信用卡流水中时，优先保留更强源（支付宝、微信），删除更弱源（银行卡、信用卡）；对高置信场景自动处理，对低置信场景只做字段标注并进入 pending/audit。

## Real-data basis

本设计基于对 20+ 份真实 records 文件的抽样分析，覆盖：

- `records/loan/2026-06.csv`
- `records/loan/2025-12.csv`
- `records/loan/2025-11.csv`
- `records/loan/2025-10.csv`
- `records/loan/2025-09.csv`
- `records/loan/2025-08.csv`
- `records/loan/2025-07.csv`
- `records/loan/2025-06.csv`
- `records/loan/2025-05.csv`
- `records/loan/2025-04.csv`
- `records/loan/2025-03.csv`
- `records/loan/2025-02.csv`
- `records/loan/2025-01.csv`
- `records/cash/2026-06.csv`
- `records/cash/2025-12.csv`
- `records/cash/2025-09.csv`
- `records/cash/2025-08.csv`
- `records/cash/2025-07.csv`
- `records/cash/2025-06.csv`
- `records/cash/2025-05.csv`
- `records/cash/2025-04.csv`
- `records/cash/2025-03.csv`
- `records/cash/2024-09.csv`
- `records/cash/2023-08.csv`
- `records/cash/2023-07.csv`
- `records/cash/2023-06.csv`

## Non-goals

- 不处理同源对同源去重（如 wechat↔wechat、icbc_credit↔icbc_credit）。
- 不试图做到“无遗漏无误删”。目标是：高置信自动删弱源，低置信显式标注，不静默漏过。
- 不改变现有 transfer / refund 的业务定义，只增加一层 mirror dedup。

## Mirror duplicate taxonomy

### A. 信用卡消费镜像

模式：`alipay/wechat -> icbc_credit`

特征：
- 同 `account_name`
- 同 `amount`
- 同 `currency`
- 时间差通常在 0-4 秒，少量到 10 秒
- 强源商户更细，弱源常是清算主体或有限公司

处理：高置信优先自动删弱源。

### B. 借记卡消费镜像

模式：`wechat/alipay -> icbc_debit/ccb_debit`

特征：
- 工行借记卡时间精度更高，可沿用近时刻判定
- 建行借记卡经常只有日期，没有时分秒，必须从严处理

处理：
- `wechat/alipay -> icbc_debit` 可较积极自动化
- `wechat/alipay -> ccb_debit` 仅在同日唯一、文本明确时自动删，否则降级

### C. 充值/群收款/二维码/转账镜像

模式：`wechat -> ccb_debit/icbc_debit`

强源常见：
- `群收款`
- `转账备注:微信转账`
- `收款方备注:二维码收款`
- `钱包充值`

弱源常见：
- `充值`
- `消费`
- `扫二维码付款`

处理：
- 仅极窄高置信子类自动删弱源
- 其余一律只标注，不自动删

### D. 带退款链的镜像

模式：主消费本身是镜像对，后续两边各自存在退款链或 offset 信息。

处理：作为安全阀。若删弱源会破坏 refund/offset 链，则降级为低置信，只标注。

## Source strength policy

统一优先级：

- 强源：`alipay`, `wechat`
- 弱源：`icbc_credit`, `icbc_debit`, `ccb_debit`

一旦高置信判定为同一笔：
- 保留支付宝/微信
- 删除银行卡/信用卡

## Architecture

在 reconcile 中新增一层 mirror detection，并放在 transfer / single-leg / refund 识别之前。

### Stage 1: mirror detection

输入：scoped active rows（排除 `locked=1`）

输出：
- `auto_drop_pairs`: 高置信镜像对，直接删弱源
- `review_pairs`: 低置信镜像候选，仅标注
- `mirror_annotations`: 写回到行的字段信息

### Stage 2: existing reconcile pipeline

mirror 层执行后，再进入：
- transfer matching
- single-leg transfer
- 现有 refund / offset 相关流程

理由：先压缩多源消费镜像，避免污染后续转账与退款判定。

## Data model

在 reconcile 内部引入 mirror 元数据字段：

- `mirror_group`
- `mirror_role`：`keep` / `drop_candidate`
- `mirror_confidence`：`high` / `low`
- `mirror_rule_hint`
- `mirror_counterpart_record_id`
- `mirror_action`：`auto_drop_weak_source` / `review_mirror_candidate`

这些字段用于：
- audit 输出
- pending/ai_working.csv 标注
- 调试和真实数据回归分析

## File responsibilities

### `src/ft/mirror_rules.py`

新增，负责：
- 场景分类
- 候选生成
- high/low 置信度判定
- mirror pair 决策结构输出

### `src/ft/dedup.py`

改为协调层：
- 调用 mirror rules
- 汇总 keep/remove/review 结果
- 尽量保留原先对 reconcile 的接口稳定性

### `src/ft/reconcile.py`

负责：
- 接入 mirror detection
- 将 high 自动删除结果并入 kept/removed/audit
- 将 low 候选写入 pending / proposed_audit / ai_working.csv
- 保持与 locked / refund / transfer 的顺序关系

## High-confidence rules

### Rule A1: card_channel_purchase_mirror

自动删弱源，条件：
- 强源 `bill_source in {alipay, wechat}`
- 弱源 `bill_source == icbc_credit`
- 同账户、同金额、同币种
- 时间差 `<= 10s`
- 文本有明确对应：counterparty/description 子串或稳定平台别名
- 候选唯一

### Rule B1: debit_purchase_mirror_icbc

自动删弱源，条件：
- 强源 `bill_source in {alipay, wechat}`
- 弱源 `bill_source == icbc_debit`
- 同账户、同金额、同币种
- 时间差 `<= 10s`
- 文本明确对应
- 候选唯一

### Rule B2: debit_purchase_mirror_ccb_unique_day

自动删弱源，条件：
- 强源 `bill_source in {alipay, wechat}`
- 弱源 `bill_source == ccb_debit`
- 同账户、同金额、同币种
- 同日唯一候选
- 强源文本明确
- 弱源不属于红包/群收款/转账备注型歧义

### Rule C1: wechat_qr_or_topup_mirror_unique_day

自动删弱源，条件：
- 强源 `bill_source == wechat`
- 弱源 `bill_source in {ccb_debit, icbc_debit}`
- 同账户、同金额、同币种
- 同日唯一候选
- 强源描述属于明确模式：`收款方备注:二维码收款`、`钱包充值`
- 弱源属于稳定弱源残影：`充值`、`扫二维码付款`

## Low-confidence rules

以下命中后只标注，不自动删：

- `wechat 群收款` ↔ `ccb_debit 充值`
- `wechat 转账备注:微信转账` ↔ `ccb_debit 充值`
- 任意建行仅日期、同日同额多候选场景
- 任意红包/群红包场景
- 任意退款链不完整或删弱源会破坏 offset 链的场景
- 文本证据不足，仅靠金额+时间猜测的场景

## Audit and pending behavior

### High confidence

- 直接在 reconcile 结果中删除弱源
- 在 audit 中写出一对 keep/remove
- 增加 mirror 字段，`mirror_decision=auto_drop_weak_source`

### Low confidence

- 两边保留
- 在行上写 mirror 字段
- pending 时整份 `ai_working.csv` 暴露这些标注
- `ai_group=mirror_xxx`
- `rule_hint=possible_mirror_low_confidence`

## Safety gates

以下场景不得自动删：
- `locked=1`
- 同源对同源
- 0 元或极小收益类噪音记录
- 候选不唯一
- 文本证据不足
- 退款链不完整

## Idempotency

必须保持：
- 再次 reconcile 不重复制造新的 mirror group
- 已自动处理的弱源不会重复出现在候选中
- 已标注的低置信候选再次跑时标注稳定
- `locked=1` 永远跳过

## Testing strategy

### Unit tests

新增/扩展：
- `tests/test_dedup.py`
- 新的 `tests/test_mirror_rules.py`

覆盖：
- A/B/C/D 各自 high 命中
- A/B/C/D 各自 low 降级
- 候选不唯一降级
- refund gate 降级

### Reconcile integration tests

扩展 `tests/test_reconcile.py`：
- high 会自动删弱源
- low 不删但带 mirror 字段进入 pending
- locked 行跳过 mirror
- refund 链场景不误删

### Real-data regression fixtures

回归样本至少包含：
- 工行信用卡镜像
- 工行借记卡镜像
- 建行纯消费镜像
- 建行群收款/二维码/充值低置信样本
- 带退款链的镜像消费样本

## Acceptance criteria

- 工行信用卡镜像消费大部分被自动删弱源
- 工行借记卡镜像消费高置信样本被自动删弱源
- 建行高风险场景不误删，至少能显式标注
- pending / audit 能清楚暴露所有低置信镜像候选
- reconcile 幂等性与 locked 语义保持不变
