# 提案：投资账本浏览 Web

## Why

系统已经能够导入、保存和估值投资事件，但当前 Web 只提供收支账本，使用者无法在浏览器中筛选投资事件、核对来源与关系证据，或把“发生过什么”与“现在持有什么”放在同一只读工作流中查看。

原 `022-investment-ledger-browser-web` 是未实现的 Spec Kit 草案。它不属于当前主规格，本 change 将其整理为可实施、可验证的 OpenSpec 规划；原始草案保存在 `legacy/022-investment-ledger-browser-web/`。

## What Changes

- 新增只读投资账本 Web 视图和 API，支持投资事件筛选、稳定分页、关系摘要和受控证据详情。
- 在独立的当前持仓区域复用既有估值合同，明确区分投资事件与持仓现状。
- 保留行情局部失败、工作区隔离、精确十进制、明确时间、双后端等价和可访问性边界。
- 在实现前完成 A 类 UI 原型、产品与工程复核，并以失败测试驱动 API、查询和界面实现。

### Scope

- 投资事件与持仓的只读 Application Service、Web API 和 Web 页面。
- 日期、账户、事件类型和标的筛选，版本化稳定分页，批量关系摘要和证据详情。
- 响应式、键盘操作、生产预览、视觉审查和双后端契约验证。

### Non-goals

- 不新增或修改投资事件、持仓、行情或交易关系。
- 不改变投资导入、估值计算、成本基础和关系扫描规则。
- 不把行情不可用解释为投资事件不可浏览。

## Capabilities

### New Capabilities

- `investment-ledger-browser`: 投资事件浏览、证据核对、持仓估值展示和只读 Web 交互。

### Modified Capabilities

- 无。该能力消费 `investment-event-model`、`ledger-records`、`portfolio-valuation` 与 `time-semantics` 的既有合同。

## Impact

- 预计影响投资查询 Application Service、Web API、Web 页面、测试与 OpenSpec artifact。
- 涉及工作区隐私、精确金额、时间、稳定分页、行情局部失败、PostgreSQL/SQLite 等价和用户可见 UI，按 A 类变更执行。
- 完成实现、验证和归档前，`openspec/specs/` 不得出现 `investment-ledger-browser` 主规格。
