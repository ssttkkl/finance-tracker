## Why

工银亚洲解析器已经存在，但 `ft import` 的参数白名单遗漏该来源，导致命令无法执行。这是既有导入合同的入口缺陷，阻断了用户已授权的真实账单导入。

## What Changes

- 在现金导入与转换 CLI 的来源白名单中加入 `icbc-asia-current-account`。
- 增加 CLI 回归，确保该来源能够到达既有导入服务。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。主规格已要求该来源可通过此命令导入；本次只恢复入口实现。

## Impact

- 影响 `src/ft/cli.py` 和 CLI 回归测试。
- 不改数据库、映射、解析逻辑或正式事实。
