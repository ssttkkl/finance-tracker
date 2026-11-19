## Why

现金流水详情、常规快照和部分导入准备在 Chrome 实测超过页面线程预算。按实测优先级迁移已有计算，减少高频交互卡顿。

## What Changes

复用流水 Worker 的当前工作区索引，后台处理全量快照、导入文件准备、账户提示和归档分页。按捕获的数据定位大请求拷贝，减少持仓页面的重复派生和不可见行计算。

## Capabilities

- `cash-ledger/cash-ledger-browser` 的 Web 详情、证据与目录读取。
- `import/local-import-pipeline` 的后台文件准备及结果整理。

## Impact

B 类。围绕现有后台协议和页面输入，保留原持久化和 HTTP 合同、结果展示、精度、权限及恢复行为。
