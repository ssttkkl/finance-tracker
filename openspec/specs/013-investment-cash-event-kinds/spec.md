# Investment cash event kinds

## Purpose
| `deposit` / `withdraw` | True customer funding; internal transfers (P1) | 本能力的行为契约由迁移后的需求与场景持续维护。

## Requirements

### Requirement: 历史行为基线
系统 MUST 保持迁移前规格文件记录的可观察行为、错误边界和非目标。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：WHEN 该能力被修改，THEN 变更必须同步更新本能力的需求与场景。
- THEN 系统满足该条件，并保留可复核的验证证据。

## Source
完整迁移来源与原始验证证据：[013-investment-cash-event-kinds/spec.md](../../changes/archive/2026-08-01-013-investment-cash-event-kinds/legacy/013-investment-cash-event-kinds/spec.md)。
本文件是 OpenSpec 的行为导向投影；实现细节、研究记录和历史任务保留在对应 change 的 `legacy/` 目录。
