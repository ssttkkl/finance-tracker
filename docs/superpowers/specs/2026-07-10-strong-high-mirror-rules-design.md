# Strong/High Mirror Rules Design

**Status:** approved in chat on 2026-07-10

## Goal

把真实数据里已经稳定的镜像重复，从 `review` / `possible_mirror_weak_30s_cross_source` 提升成新的 `strong/high auto-drop` 规则，减少 `pending/reconcile` 中需要 AI 继续审核的重复消费记录，同时尽量不依赖大量写死商户名。

## Why now

当前 `src/ft/mirror_rules.py` 已经完成两层能力：

- 高置信镜像直接进入 `auto_drop_pairs`
- 宽松跨源候选进入 `review_pairs`

真实 `.ft` 数据已经通过 `ft reconcile` + `ai_working.csv` 审过一轮，并产出审计文件 `/Users/huangwenlong/.ft/audit/reconcile/2026-07-09_23-57-43.csv`。从这批真实删除样本可以确认，仍有一批模式非常稳定，但现在还停留在弱候选阶段，值得升级为自动去重规则。

## Non-goals

- 不重新设计整套 dedup/reconcile 架构。
- 不把所有弱候选都升级成自动删除。
- 不靠硬编码维护一长串商户名清单。
- 不改变 refund、transfer、locked 的既有优先级语义。

## Design principles

### 1. 结构优先，别名兜底

规则先看“谁更像真实消费对象，谁更像扣款通道残影”，而不是先写死商户名。

优先使用：
- 来源强弱：`wechat` / `alipay` 强于银行卡源
- 文本具体度：具体商户/服务名 强于 `消费` / `财付通` / `支付宝消费` 这类通道文本
- 通道特征词：`财付通`、`微信支付`、`支付宝`、`网络技术有限公司`、`银联`、`快捷支付`

只有当双方都很具体、只是名字不同（例如品牌别名、主体公司名）时，才允许使用少量高频 alias 兜底。

### 2. 自动删除只做“删弱侧”

一旦判定为高置信镜像：
- 保留 `wechat` / `alipay` 侧
- 删除 `icbc_credit` / `icbc_debit` / `ccb_debit` 侧

不做反向删除，不改变现有 keep/drop 方向。

### 3. 低置信候选继续走 AI 审查

如果规则无法仅靠结构证据稳定判定，就保持现状：
- 继续进入 `review_pairs`
- 继续写入 `pending/reconcile/.../ai_working.csv`
- 不因为想提升自动化而冒险误删

## Scope

本次只扩展 `src/ft/mirror_rules.py` 中的 high-confidence 判定，不新增新的 pending schema，不重写 reconcile 编排。

影响范围：
- `src/ft/mirror_rules.py`
- `tests/test_mirror_rules.py`
- 视需要补充 `tests/test_reconcile.py` 与 `tests/test_dedup.py` 回归

## Candidate families to promote

### Family A: 强侧是具体商户，弱侧是银行卡通道文本

典型模式：
- 强侧：`wechat` / `alipay`
- 弱侧：`icbc_credit` / `icbc_debit`
- 同账户、同金额、同币种、时间接近
- 强侧文本是具体商户或服务
- 弱侧文本只是通道/清算/泛化消费描述

示例弱侧文本：
- `消费`
- `财付通-微信支付`
- `支付宝消费`
- `支付宝(中国)网络技术有限公司`
- `银联无卡快捷支付`

处理：升级为新的 `high auto-drop` 规则。

### Family B: 强侧是具体服务主体，弱侧是信用卡泛化主体

典型模式：
- `wechat/alipay -> icbc_credit`
- 强侧文本是明确服务主体，如外卖、铁路、缴费、咖啡、游戏平台
- 弱侧仍然是银行侧的有限公司/消费残影

这里的关键不是写死“某个商户名”，而是识别：
- 强侧更具体
- 弱侧更像通道结算记录

处理：在文本互证不足但结构证据足够时，允许直接升级为 `high auto-drop`。

### Family C: 少量高频 alias 兜底

只处理真实样本中反复出现、且结构上已经很像镜像，但双方文本都比较具体的情况。

例如：
- 品牌名 vs 公司主体名
- App 展示名 vs 结算主体名

这类 alias 必须满足：
- 高频
- 稳定
- 有明确真实样本支撑
- 不会扩大到模糊类目词

处理：用非常小的 alias 集合兜底，不建设大型商户字典。

## Architecture changes

### `src/ft/mirror_rules.py`

在现有结构上增量扩展，不改公开接口：

- 保持 `detect_mirror_pairs(rows) -> MirrorDetectionResult`
- 保持 `auto_drop_pairs` / `review_pairs` 双桶输出
- 保持 `dedup.py` 只消费 `auto_drop_pairs`
- 保持 `reconcile.py` 继续消费 `review_pairs`

新增或细化的内部能力：

1. **文本强弱判定**
   - 判断一条记录是否更像“具体商户/服务文本”
   - 判断一条记录是否更像“通道/清算文本”

2. **通道特征识别**
   - 识别银行卡弱侧中的通道关键词簇
   - 不局限于已有 `_weak_channel_kind()` 的网关分类

3. **少量 alias 互证**
   - 当双方都较具体但明显是同一消费对象时，提供小规模兜底

4. **新 high 规则分支**
   - 在 `_classify_candidate()` 中为现有 candidate family 增加更强的自动判定分支
   - 只在候选唯一、金额/币种/账户一致、时间足够近、且不涉及 refund 风险时生效

## Data rules

### 硬条件

所有新增 high 规则都必须继续满足：
- `account_name` 相等
- `amount` 相等
- `currency` 相等
- 来源一强一弱
- 候选唯一，或至少当前弱侧只对应唯一更优强侧
- 不命中 refund safety gate

### 时间条件

沿用现有渠道时间策略，不单独放宽：
- `icbc_credit`：`<= 10s`
- `icbc_debit` 网关型：`<= 30s`
- `ccb_debit`：仍然从严，只在已有安全分支内考虑

本次目标不是继续放宽时间，而是在**现有时间窗口内提升结构判定能力**。

### 文本强弱判定规则

可操作定义：

弱侧文本通常满足以下之一：
- 明显是支付通道：`财付通`、`微信支付`、`支付宝`、`银联`
- 明显是结算主体：`网络技术有限公司` 等
- 明显是泛化动作：`消费`、`快捷支付`

强侧文本通常满足以下之一：
- 具体商户名
- 具体服务主体
- 具体缴费/平台/品牌/票务/餐饮名

如果强侧更具体、弱侧更通道化，则可作为 high 的重要证据。

### Alias 使用边界

alias 只用于补充互证，不单独作为唯一证据来源。

也就是说，不允许这种规则：
- “只要看到某商户名就自动删另一边”

必须是：
- 结构条件先成立
- alias 只是帮助确认“两边确实是同一消费对象”

## Safety rules

以下场景继续禁止自动删除：
- `locked=1`
- 任意退款链/offset 相关记录
- `群收款`、`红包`、`转账`、`二维码收款` 这类高歧义社交流/收款流
- 弱侧存在多个强侧候选
- 双方都不够具体，只能靠时间+金额硬猜
- `ccb_debit` 的日期级歧义场景

## Testing strategy

坚持 TDD：先写失败测试，再写最小实现。

### Unit tests: `tests/test_mirror_rules.py`

新增高置信测试，覆盖：

1. **具体商户 vs 信用卡泛化消费**
   - 当前只进 weak/review 的样本，升级后应进入 `auto_drop_pairs`

2. **具体商户 vs 工行借记卡通道文本**
   - 当前 review 样本，升级后应 `auto_drop`

3. **通道强特征 + 结构唯一**
   - 验证不依赖具体商户名也能判定 high

4. **alias 兜底样本**
   - 小规模稳定别名样本命中 high

5. **保护性测试**
   - refund 场景仍然降级 review
   - social/红包/群收款仍然不自动删
   - 多候选场景仍然不自动删
   - 现有高规则优先级不被破坏

### Integration tests

补充/回归：
- `tests/test_dedup.py`
- `tests/test_reconcile.py`
- `tests/test_reconcile_locked.py`

验证：
- 新 high 规则会真正减少 records 中保留的重复账
- review-only 语义不变
- mixed high+review 语义不变
- locked 不参与

## Acceptance criteria

实现完成后应满足：

1. 一批当前落在 `possible_mirror_weak_30s_cross_source` 的稳定样本，能升级成 `auto_drop_pairs`
2. 升级后的规则主要依赖结构与文本强弱，而不是大规模硬编码商户名
3. 退款、社交流、日期级歧义、多候选场景不误升 high
4. `dedup.py`、`reconcile.py` 的职责边界不变
5. 现有测试与新增测试全部通过

## Rollout note

本次实现完成后，下一轮真实数据验证应重点观察：
- `possible_mirror_weak_30s_cross_source` 数量是否明显下降
- 新增 `auto_drop` 是否集中在结构稳定样本
- 是否出现新的误删类型

如果某些模式仍需要商户 alias 才能稳定命中，再基于真实 `ai_working.csv` 抽样决定是否补充极小规模 alias 集。