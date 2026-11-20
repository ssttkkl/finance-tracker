## Why

现有主流程在首次记账、成员权限和导入收尾时要求使用者绕路或承担系统处理。其余操作中，可见入口与实际结果不一致，增加了误操作和重复劳动。

## What Changes

- 注册后进入首次工作区创建时，预填按界面语言表达的可编辑工作区名称。
- 在记账表单内创建账户，保存已有填写内容，自动选用新账户。
- 在移除成员和授予管理员权限前确认影响。邀请创建防重复，链接独立标识实际权限。接受邀请前显示当前登录邮箱，换账号后继续原邀请，失效时指明如何取得新链接。
- 确认已保存的导入由应用接续更新。结果页能直接打开本批待确认收支。
- 收支快捷筛选明确类型与待确认状态。需要整体核对的关联只展示能够完成的操作。
- 收支分类可以一次移动到同级目标前或末尾。投资流水区分没有记录和筛选无结果。
- 本次范围为正式 Web、Android、iOS。投资持仓、密码找回和其他功能保持在本次范围之外。

## Capabilities

### Modified Capabilities

- `cash-ledger/cash-ledger-browser` 记账内新建账户、快捷筛选和导入结果接续。
- `cash-ledger/transaction-relations` 联合关联建议的可执行操作。
- `cash-ledger/cash-category-management` 一次定位分类顺序。
- `workspace/workspace-entry` 首次创建时预填可编辑的工作区名称。
- `workspace/workspace-management` 成员确认和邀请结果。
- `authentication/user-workspace-access` 邀请接受时选择登录账号。
- `investment/investment-event-model` 列表空状态和清除筛选。

## Impact

共享 Compose 交互、数据合同和回归测试。分类排序与已保存导入收据采用增量合同，保留旧调用与旧收据的兼容性。无 schema 迁移，已完成写入在回滚客户端后仍有效。影响广、覆盖共享工作区权限、收支写入恢复和双后端行为，按 A 类验证。
